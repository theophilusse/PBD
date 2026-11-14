package pbd.render;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import pbd.format.MaterialPictures;
import pbd.format.MaterialUv;
import pbd.format.TextureTurn;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL43.*;

/**
 * Builds a single GL_TEXTURE_2D_ARRAY from a set of resolved file paths,
 * returning the array's GL id plus each path's layer index. A texture
 * array (not N separate sampler2D) is what lets pbd.frag pick "the right
 * texture for this fragment" from a single draw call covering potentially
 * many different materials at once - the same reason PbdRenderer already
 * batches every instance's geometry into one draw.
 *
 * The real constraint that comes with it: every layer must be the same
 * width/height. The first texture successfully loaded fixes the array's
 * size; anything after that doesn't match is skipped (with a message on
 * stderr, same "degrade, don't crash" policy as TextureLoader) rather
 * than resized to fit - silently stretching a mismatched texture into
 * someone else's aspect ratio would be a worse surprise than it just not
 * appearing.
 *
 * <p><b>A picture is a file AND a number of quarter turns.</b> A material's {@code uvRotation} ({@link MaterialUv#quarterTurns}) turns
 * its picture once, where it is loaded ({@link TextureTurn}): the shader, the voxels of a shot object and the classic mesh renderer
 * all read the turned picture, so none of them has a turn of its own to get wrong. Two materials may use one file turned differently,
 * so a layer belongs to a {@link MaterialPictures.Request} (file + turns), not to a file. Which pictures there are, and that a file
 * is decoded once however many turns of it are asked for, is {@link MaterialPictures#plan} - plain Java, run by a test without a
 * window; what is left here is what needs OpenGL.
 */
public final class MaterialTextureArray implements AutoCloseable {

    public final int glId; // 0 if nothing was loaded
    private final Map<String, Integer> layerByKey = new LinkedHashMap<>();
    // Same successfully-decoded RGBA bytes already uploaded to glId above,
    // kept here too - see this class's own build() for why: a SEPARATE
    // consumer (voxel-destruction color sampling) used to call
    // STBImage.stbi_load a SECOND time on these exact same files, right
    // after this method's own call already decoded (and freed) them -
    // confirmed, via a real crash's own log, to land right after THIS
    // class's own resize log line for a large (3000x2000) JPEG, strongly
    // suggesting the double-decode itself (two native
    // allocate/decode/free cycles on the same large image in quick
    // succession), not any question of WHEN/WHERE stbi_load ran, was
    // the actual native heap corruption. Copied into a plain Java byte[]
    // here, from the SAME already-successful decode this method needed
    // anyway for its own GL upload, before that decode's own native
    // buffer gets freed - the only copy of this data
    // that will ever exist from this point on, no second stbi_load
    // anywhere for these paths, ever. (Turned, when the request was.)
    private final Map<String, PixelData> pixelDataByKey = new LinkedHashMap<>();

    /** RGBA8 pixel bytes for one already-loaded texture, plus its real
     * (pre-array-resize, but already turned) width/height - see pixelDataByKey's own doc
     * above for where this comes from and why it exists. */
    public record PixelData(byte[] rgba, int width, int height) {}

    private MaterialTextureArray(int glId, Map<String, Integer> layerByKey, Map<String, PixelData> pixelDataByKey) {
        this.glId = glId;
        this.layerByKey.putAll(layerByKey);
        this.pixelDataByKey.putAll(pixelDataByKey);
    }

    /** -1 if this exact picture (file and turns) was never successfully loaded into the array (missing file, or didn't match the array's fixed size). */
    public int layerOf(Path resolvedPath, int quarterTurns) {
        Integer layer = layerByKey.get(MaterialPictures.keyOf(resolvedPath, quarterTurns));
        return layer != null ? layer : -1;
    }

    /** null if this exact picture (file and turns) was never successfully decoded (missing
     * file - a mismatched-size texture still decodes fine and still
     * gets an entry here, unlike layerOf above, since pixel data for
     * voxel-coloring purposes doesn't care about this array's own
     * fixed-size constraint the way the GPU layer upload does). */
    public PixelData pixelDataOf(Path resolvedPath, int quarterTurns) {
        return pixelDataByKey.get(MaterialPictures.keyOf(resolvedPath, quarterTurns));
    }

    public boolean isEmpty() {
        return glId == 0;
    }

    /**
     * The picture of a file as stb decodes it (flipped, so row 0 is the bottom), copied into plain Java and the native buffer freed at
     * once; null, reported on stderr, when the file cannot be read. The three one-int buffers are taken from the thread's (64 KB) stack
     * and given back when this method returns - one call, one frame, never taken in a loop.
     */
    private static TextureTurn.Image decode(Path path) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer decoded = STBImage.stbi_load(path.toString(), w, h, channels, 4);
            if (decoded == null) {
                System.err.println("[MaterialTextureArray] Failed to load " + path + ": " + STBImage.stbi_failure_reason());
                return null;
            }
            try {
                if ((long) w.get(0) * h.get(0) * 4 > Integer.MAX_VALUE) {
                    System.err.println("[MaterialTextureArray] " + path + " is " + w.get(0) + "x" + h.get(0) + ": too big to be held in memory, skipping");
                    return null;
                }
                // This decode's own real (pre-array-resize) dimensions and bytes: from here on the picture is plain Java (turned,
                // resized) until it is handed to GL.
                byte[] bytes = new byte[w.get(0) * h.get(0) * 4];
                decoded.get(0, bytes); // absolute get - does not move the buffer's own position
                return new TextureTurn.Image(bytes, w.get(0), h.get(0));
            } finally {
                STBImage.stbi_image_free(decoded);
            }
        }
    }

    /** Loads every picture in `requests` into one array, sized exactly for however many distinct, loadable pictures that turns out to be. Returns an empty (glId=0) instance if nothing loaded successfully. */
    public static MaterialTextureArray build(Iterable<MaterialPictures.Request> requests) {
        // Pre-pass (plain Java, MaterialPictures.plan): dedupe, drop anything missing or unreadable, decode each file once and turn it as
        // asked - so the array can be allocated at exactly the right depth up front, simpler and lower-risk than growing it later
        // (which would need glCopyImageSubData, untested elsewhere in this project, for a capacity this project's actual use so far
        // never approaches).
        List<MaterialPictures.Picture> pictures = MaterialPictures.plan(requests, Files::exists, MaterialTextureArray::decode,
            line -> System.err.println("[MaterialTextureArray] " + line));
        if (pictures.isEmpty()) {
            return new MaterialTextureArray(0, java.util.Map.of(), java.util.Map.of());
        }

        Map<String, Integer> layerByKey = new LinkedHashMap<>();
        Map<String, PixelData> pixelDataByKey = new LinkedHashMap<>();
        int width = 0, height = 0;
        int glId = 0;
        int nextLayer = 0;

        for (MaterialPictures.Picture picture : pictures) {
            MaterialPictures.Request request = picture.request();
            TextureTurn.Image turned = picture.image();
            int turnedW = turned.width(), turnedH = turned.height();
            String name = request.path() + (request.quarterTurns() != 0 ? " turned by " + request.quarterTurns() * 90 + " degrees" : "");
            // pixelDataByKey keeps the ORIGINAL resolution (turned) - voxel color sampling has no reason to share this array's own
            // fixed-size constraint.
            pixelDataByKey.put(request.key(), new PixelData(turned.rgba(), turnedW, turnedH));

            if (glId == 0) {
                width = turnedW;
                height = turnedH;
                glId = glGenTextures();
                glBindTexture(GL_TEXTURE_2D_ARRAY, glId);
                glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, width, height, pictures.size(), 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            }

            // The bytes GL reads: a native copy of the (turned) picture, taken with MemoryUtil.memAlloc and given back with memFree.
            ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(turned.rgba().length);
            pixels.put(turned.rgba(), 0, turned.rgba().length).flip();
            if (turnedW != width || turnedH != height) {
                // Resized, not skipped: every texture in one array
                // MUST share one resolution (a real GL_TEXTURE_2D_ARRAY
                // constraint, not something this project chose), but
                // requiring the source FILES to already match was a
                // much stricter demand than that - textures from two
                // different packs (a downloaded PBR set alongside a
                // hand-made one, say) are essentially never the same
                // resolution by coincidence. A mismatched file used to
                // be silently dropped here, indistinguishable from the
                // file never having been found at all - exactly what
                // was actually happening to a real multi-material
                // scene: the first-loaded texture fixed the array's
                // size and everything else with a different
                // resolution just vanished, typo or no typo.
                //
                // Resized with a small bilinear function written
                // directly below, not STBImageResize - its exact
                // method signature turned out to differ between LWJGL
                // versions in a way that wasn't resolvable with
                // confidence (the previous attempt compiled against
                // documentation for the wrong version), and this is
                // simple enough to just own outright rather than
                // guess at a third time.
                ByteBuffer resized = bilinearResize(pixels, turnedW, turnedH, width, height);
                org.lwjgl.system.MemoryUtil.memFree(pixels); // done with the unresized copy either way
                System.out.println("[MaterialTextureArray] " + name + " resized from " + turnedW + "x" + turnedH
                    + " to " + width + "x" + height + " to match this array's existing textures");
                pixels = resized;
            } else if (request.quarterTurns() != 0) {
                System.out.println("[MaterialTextureArray] " + name + " (" + turnedW + "x" + turnedH + ")");
            }

            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, nextLayer, width, height, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            org.lwjgl.system.MemoryUtil.memFree(pixels);
            layerByKey.put(request.key(), nextLayer);
            nextLayer++;
        }
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);

        return new MaterialTextureArray(glId, layerByKey, pixelDataByKey);
    }

    /**
     * Bilinear resize, RGBA (4 channels), 8 bits per channel. Written
     * directly instead of relying on STBImageResize - see the call
     * site's comment for why. For each destination pixel, maps back to a
     * fractional source coordinate (using the standard "pixel center"
     * convention: sample position (d+0.5)*ratio-0.5, not just d*ratio,
     * which is what avoids a half-pixel drift toward one corner),
     * samples the 4 nearest source pixels, and blends by how close each
     * one is. Source coordinates are clamped to the image edge rather
     * than wrapping - correct for a texture that will itself repeat via
     * GL_REPEAT, which should not see a seam blended in from the far
     * edge of a single resized layer.
     */
    private static ByteBuffer bilinearResize(ByteBuffer src, int srcW, int srcH, int dstW, int dstH) {
        ByteBuffer dst = org.lwjgl.system.MemoryUtil.memAlloc(dstW * dstH * 4);
        float xRatio = (float) srcW / dstW;
        float yRatio = (float) srcH / dstH;
        for (int dy = 0; dy < dstH; dy++) {
            float srcYf = (dy + 0.5f) * yRatio - 0.5f;
            int y0 = (int) Math.floor(srcYf);
            float fy = srcYf - y0;
            int y0c = Math.max(0, Math.min(srcH - 1, y0));
            int y1c = Math.max(0, Math.min(srcH - 1, y0 + 1));
            for (int dx = 0; dx < dstW; dx++) {
                float srcXf = (dx + 0.5f) * xRatio - 0.5f;
                int x0 = (int) Math.floor(srcXf);
                float fx = srcXf - x0;
                int x0c = Math.max(0, Math.min(srcW - 1, x0));
                int x1c = Math.max(0, Math.min(srcW - 1, x0 + 1));

                for (int c = 0; c < 4; c++) {
                    int p00 = src.get((y0c * srcW + x0c) * 4 + c) & 0xFF;
                    int p10 = src.get((y0c * srcW + x1c) * 4 + c) & 0xFF;
                    int p01 = src.get((y1c * srcW + x0c) * 4 + c) & 0xFF;
                    int p11 = src.get((y1c * srcW + x1c) * 4 + c) & 0xFF;
                    float top = p00 * (1 - fx) + p10 * fx;
                    float bottom = p01 * (1 - fx) + p11 * fx;
                    float value = top * (1 - fy) + bottom * fy;
                    dst.put((dy * dstW + dx) * 4 + c, (byte) Math.round(value));
                }
            }
        }
        return dst;
    }

    @Override
    public void close() {
        if (glId != 0) glDeleteTextures(glId);
    }
}
