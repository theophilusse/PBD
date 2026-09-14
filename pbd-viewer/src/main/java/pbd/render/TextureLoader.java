package pbd.render;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL43.*;

/**
 * Loads an image file into a GL_TEXTURE_2D via stb_image (JPEG/PNG/BMP/TGA
 * - not the .exr HDR variant, nor Blender/Godot-native formats like
 * .blend/.tres/.usdc, none of which stb_image reads; a tonemapped LDR
 * export - a .jpg or .png - is the right file to point this at).
 */
public final class TextureLoader {

    private TextureLoader() {}

    /** Returns the GL texture id, or 0 (with a message on stderr) if the file is missing or unreadable - never throws, so a missing optional texture (e.g. the night sky) degrades gracefully rather than crashing the app. */
    public static int load2D(Path path) {
        if (!Files.exists(path)) {
            System.err.println("[TextureLoader] Not found, skipping: " + path);
            return 0;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(true); // GL's texture origin is bottom-left; image files are usually stored top-first
            ByteBuffer pixels = STBImage.stbi_load(path.toString(), w, h, channels, 4);
            if (pixels == null) {
                System.err.println("[TextureLoader] Failed to load " + path + ": " + STBImage.stbi_failure_reason());
                return 0;
            }

            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); // equirectangular: wraps around, but not over the poles
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            STBImage.stbi_image_free(pixels);
            System.out.println("[TextureLoader] Loaded " + path + " (" + w.get(0) + "x" + h.get(0) + ")");
            return tex;
        }
    }
}
