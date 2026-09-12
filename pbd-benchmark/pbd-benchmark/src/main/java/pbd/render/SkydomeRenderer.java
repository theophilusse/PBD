package pbd.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL43.*;

/**
 * Renders the sky as a single full-screen triangle, behind everything
 * else: a procedural day gradient, an equirectangular-sampled night
 * starfield (if a texture was supplied), and a sun disc positioned by the
 * real direction SolarCalculator computes for the current in-scene time.
 *
 * Uniform locations (see skydome.frag):
 *   0=invViewProj 1=sunDirection 2=dayFactor 3=starRotation 4=hasNightTexture
 */
public final class SkydomeRenderer implements AutoCloseable {

    private final ShaderProgram program;
    private final int vao;
    private final int nightTexture; // 0 if no texture file was found - see TextureLoader

    public SkydomeRenderer(Path shaderDir, Path nightTexturePath) throws IOException {
        program = new ShaderProgram(shaderDir.resolve("skydome.vert"), shaderDir.resolve("skydome.frag"));
        vao = glGenVertexArrays(); // intentionally empty - the vertex shader needs no attributes, only gl_VertexID
        nightTexture = TextureLoader.load2D(nightTexturePath);
    }

    /** sunDirection: normalized, world Y-up (see SolarCalculator.toDirection). dayFactor: 0=full night, 1=full day. starRotation: radians. */
    public void render(FlyCamera camera, float aspectRatio, float[] sunDirection, float dayFactor, float starRotation) {
        program.use();
        glBindVertexArray(vao);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Matrix4f invViewProj = camera.viewProj(aspectRatio).invert();
            glUniformMatrix4fv(0, false, invViewProj.get(stack.mallocFloat(16)));
        }
        glUniform3f(1, sunDirection[0], sunDirection[1], sunDirection[2]);
        glUniform1f(2, dayFactor);
        glUniform1f(3, starRotation);
        glUniform1i(4, nightTexture != 0 ? 1 : 0);

        if (nightTexture != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, nightTexture);
        }

        // The sky sits at a constant clip-space z=0 (see skydome.vert) -
        // not the far plane, so depth WRITES must stay off here regardless
        // of the depth TEST state, or this full-screen pass would stamp a
        // near-camera depth across the whole buffer and the real scene,
        // drawn right after with depth testing on, would fail against it
        // and never appear. Depth test is also disabled, matching "always
        // draw, this is the background" - both are restored immediately
        // after so the main scene render that follows is unaffected.
        glDepthMask(false);
        glDisable(GL_DEPTH_TEST);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        program.close();
        if (nightTexture != 0) glDeleteTextures(nightTexture);
    }
}
