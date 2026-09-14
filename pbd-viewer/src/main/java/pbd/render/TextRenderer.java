package pbd.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL43.*;

/**
 * On-screen text rendering for the stats overlay, using BitmapFont's 5x7
 * bitmap font. One draw call per character (GL_TRIANGLE_STRIP, 4
 * attributeless vertices, position/size/UV driven by uniforms) - plenty
 * for a few lines of stats per frame; not designed for large volumes of text.
 *
 * A program kept separate from the PBD tessellation pipeline, with its
 * own uniform locations starting at 0 - two independently linked programs
 * don't need to worry about colliding with each other (only uniforms
 * within the SAME linked program need unique locations, a lesson this
 * project has already paid for twice).
 */
public final class TextRenderer implements AutoCloseable {

    private static final int LOC_SCREEN_SIZE = 0;
    private static final int LOC_CHAR_POS    = 1;
    private static final int LOC_CHAR_SIZE   = 2;
    private static final int LOC_UV_RECT     = 3;
    private static final int LOC_FONT_ATLAS  = 4;
    private static final int LOC_TEXT_COLOR  = 5;

    private final ShaderProgram program;
    private final int vao;
    private final int atlasTexture;

    /** Display size of one font pixel, in screen pixels (2-3 is legible without being huge). */
    public float scale = 2.0f;
    public float[] color = {0.2f, 1.0f, 0.4f};

    public TextRenderer(Path shaderDir) throws IOException {
        program = new ShaderProgram(shaderDir.resolve("text.vert"), shaderDir.resolve("text.frag"));

        vao = glGenVertexArrays();

        byte[] pixels = BitmapFont.buildAtlasPixels();
        ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(pixels.length);
        buf.put(pixels).flip();

        atlasTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, atlasTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, BitmapFont.ATLAS_W, BitmapFont.ATLAS_H,
            0, GL_RED, GL_UNSIGNED_BYTE, buf);
        org.lwjgl.system.MemoryUtil.memFree(buf);
    }

    /** Width in screen pixels this string would occupy, to align/center a block. */
    public float measure(String text) {
        return text.length() * (BitmapFont.GLYPH_W + 1) * scale;
    }

    public float lineHeight() {
        return (BitmapFont.GLYPH_H + 3) * scale;
    }

    /** Draws one line, origin = top-left corner of the first character, in screen pixels. */
    public void drawLine(String text, float x, float y, int screenW, int screenH) {
        program.use();
        glBindVertexArray(vao);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTexture);
        glUniform1i(LOC_FONT_ATLAS, 0);
        glUniform3f(LOC_TEXT_COLOR, color[0], color[1], color[2]);
        glUniform2f(LOC_SCREEN_SIZE, screenW, screenH);

        float charAdvance = (BitmapFont.GLYPH_W + 1) * scale;
        float cursorX = x;
        for (int i = 0; i < text.length(); i++) {
            float[] uv = BitmapFont.uvOf(text.charAt(i));
            glUniform2f(LOC_CHAR_POS, cursorX, y);
            glUniform2f(LOC_CHAR_SIZE, BitmapFont.GLYPH_W * scale, BitmapFont.GLYPH_H * scale);
            glUniform4f(LOC_UV_RECT, uv[0], uv[1], uv[2], uv[3]);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            cursorX += charAdvance;
        }
    }

    /** Draws several lines stacked from (x,y), with no automatic line wrapping. */
    public void drawLines(String[] lines, float x, float y, int screenW, int screenH) {
        float lh = lineHeight();
        for (int i = 0; i < lines.length; i++) {
            drawLine(lines[i], x, y + i * lh, screenW, screenH);
        }
    }

    @Override
    public void close() {
        glDeleteTextures(atlasTexture);
        glDeleteVertexArrays(vao);
        program.close();
    }
}
