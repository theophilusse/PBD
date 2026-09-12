package pbd.render;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 */
public final class MaterialTextureArray implements AutoCloseable {

    public final int glId; // 0 if nothing was loaded
    private final Map<String, Integer> layerByPath = new LinkedHashMap<>();

    private MaterialTextureArray(int glId, Map<String, Integer> layerByPath) {
        this.glId = glId;
        this.layerByPath.putAll(layerByPath);
    }

    /** -1 if this exact path was never successfully loaded into the array (missing file, or didn't match the array's fixed size). */
    public int layerOf(Path resolvedPath) {
        Integer layer = layerByPath.get(resolvedPath.toString());
        return layer != null ? layer : -1;
    }

    public boolean isEmpty() {
        return glId == 0;
    }

    /** Loads every path in `paths` into one array, sized exactly for however many distinct, loadable files that turns out to be. Returns an empty (glId=0) instance if nothing loaded successfully. */
    public static MaterialTextureArray build(Iterable<Path> paths) {
        // Pre-pass: dedupe and drop anything missing, so the array can be
        // allocated at exactly the right depth up front - simpler and
        // lower-risk than growing it later (which would need
        // glCopyImageSubData, untested elsewhere in this project, for a
        // capacity this project's actual use so far never approaches).
        java.util.List<Path> toLoad = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Path path : paths) {
            if (seen.add(path.toString()) && Files.exists(path)) {
                toLoad.add(path);
            } else if (!Files.exists(path)) {
                System.err.println("[MaterialTextureArray] Not found, skipping: " + path);
            }
        }
        if (toLoad.isEmpty()) {
            return new MaterialTextureArray(0, java.util.Map.of());
        }

        Map<String, Integer> layerByPath = new LinkedHashMap<>();
        int width = 0, height = 0;
        int glId = 0;
        int nextLayer = 0;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (Path path : toLoad) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                STBImage.stbi_set_flip_vertically_on_load(true);
                ByteBuffer pixels = STBImage.stbi_load(path.toString(), w, h, channels, 4);
                if (pixels == null) {
                    System.err.println("[MaterialTextureArray] Failed to load " + path + ": " + STBImage.stbi_failure_reason());
                    continue;
                }
                boolean pixelsFromMemoryUtil = false; // which allocator owns `pixels` right now - determines how it gets freed below

                if (glId == 0) {
                    width = w.get(0);
                    height = h.get(0);
                    glId = glGenTextures();
                    glBindTexture(GL_TEXTURE_2D_ARRAY, glId);
                    glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, width, height, toLoad.size(), 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
                    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
                    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                } else if (w.get(0) != width || h.get(0) != height) {
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
                    int srcW = w.get(0), srcH = h.get(0);
                    ByteBuffer resized = bilinearResize(pixels, srcW, srcH, width, height);
                    STBImage.stbi_image_free(pixels); // done with the original either way
                    System.out.println("[MaterialTextureArray] " + path + " resized from " + srcW + "x" + srcH
                        + " to " + width + "x" + height + " to match this array's existing textures");
                    pixels = resized;
                    pixelsFromMemoryUtil = true;
                }

                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, nextLayer, width, height, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                if (pixelsFromMemoryUtil) {
                    org.lwjgl.system.MemoryUtil.memFree(pixels);
                } else {
                    STBImage.stbi_image_free(pixels);
                }
                layerByPath.put(path.toString(), nextLayer);
                nextLayer++;
            }
            if (glId != 0) {
                glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
            }
        }

        return new MaterialTextureArray(glId, layerByPath);
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
