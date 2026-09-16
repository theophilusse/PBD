package pbd.classicmesh;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import pbd.render.FlyCamera;
import pbd.render.ShaderProgram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL43.*;

/**
 * Traditional VBO/IBO rendering path for a loaded Wavefront OBJ mesh - the
 * baseline the PBD pipeline is benchmarked against. No tessellation, no
 * procedural generation: every vertex is uploaded once as real attribute
 * data and stays fixed regardless of camera distance, unlike the PBD
 * path's dynamic, distance-driven tessellation. One fixed material for
 * the whole mesh - there is no per-instance material system on this path,
 * since the point of this module is a single reference mesh to compare
 * against, not a full classic-mesh engine.
 *
 * Optional single-texture support (see setTexture): a "mesh"-type PBD
 * instance carries real per-vertex UVs (ObjMesh.hasUv), but this
 * renderer used to only ever draw baseColor flat regardless - no UV
 * attribute uploaded, no sampler in the fragment shader at all. Reported
 * as "UV mapping doesn't work in Java" - accurately, since nothing about
 * UVs was wired up here to NOT work; there was simply no texture path to
 * begin with. A mesh with hasUv=false (the OBJ-benchmark path, container
 * items) is completely unaffected: the UV attribute location is only
 * enabled when mesh.hasUv is true, and useTexture only turns on when
 * setTexture is actually called.
 */
public final class ClassicMeshRenderer implements AutoCloseable {

    private static final int LOC_VIEW_PROJ  = 0;
    private static final int LOC_CAMERA_POS = 1;
    private static final int LOC_LIGHT_DIR  = 2;
    private static final int LOC_BASE_COLOR = 3;
    private static final int LOC_WORLD_MATRIX = 4;
    private static final int LOC_USE_TEXTURE = 5;
    private static final int LOC_UV_SCALE = 6;

    private final ShaderProgram program;
    private final int vao;
    private final int vbo;
    private final int ibo;
    private final int indexCount;
    private final boolean meshHasUv;
    private int textureGlId = -1; // -1 = no texture bound, render flat baseColor as before

    public final long uploadedBytes;
    public float[] lightDir = {0.3f, -1.0f, -0.2f};
    public float[] baseColor = {0.62f, 0.63f, 0.66f};
    public float[] uvScale = {1f, 1f};

    public ClassicMeshRenderer(Path shaderDir, ObjMesh mesh) throws IOException {
        program = new ShaderProgram(shaderDir.resolve("classic.vert"), shaderDir.resolve("classic.frag"));
        meshHasUv = mesh.hasUv;
        int stride = meshHasUv ? 8 : 6;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, mesh.vertexData, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride * Float.BYTES, 3L * Float.BYTES);
        if (meshHasUv) {
            // Location 2 left DISABLED (not even declared here) for a
            // non-UV mesh - OpenGL reads a disabled attribute as a
            // constant (0,0,0,1) rather than erroring, so the shader can
            // safely declare a vec2 UV input unconditionally and this
            // constructor just never turns location 2 on for a mesh that
            // has nothing meaningful to put there.
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, stride * Float.BYTES, 6L * Float.BYTES);
        }

        ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices, GL_STATIC_DRAW);

        indexCount = mesh.indices.length;
        uploadedBytes = mesh.byteSize();
    }

    /** Loads texturePath as a single 2D texture and binds it for
     * subsequent render() calls - only meaningful when the mesh this
     * renderer was built from has real UVs (mesh.hasUv); called on one
     * without them does nothing useful (the shader would still sample
     * whatever (0,0) resolves to for every vertex, an unhelpful, solid-
     * colored result), so the caller is expected to only call this for
     * a "mesh"-type instance's own renderer, not a container-item or
     * OBJ-benchmark one. Logs and leaves textureGlId at -1 (flat
     * baseColor, same as never calling this) on any load failure -
     * consistent with the rest of this project's "a missing/bad texture
     * shouldn't crash rendering" posture (see MaterialTextureArray). */
    public void setTexture(Path texturePath) {
        if (!meshHasUv) {
            System.err.println("[ClassicMeshRenderer] setTexture called on a mesh with no UVs - ignoring (nothing meaningful to map onto)");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer pixels = STBImage.stbi_load(texturePath.toString(), w, h, channels, 4);
            if (pixels == null) {
                System.err.println("[ClassicMeshRenderer] Failed to load '" + texturePath + "': " + STBImage.stbi_failure_reason());
                return;
            }
            try {
                if (textureGlId != -1) glDeleteTextures(textureGlId); // replacing a previously-set texture - not leaking the old one
                textureGlId = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, textureGlId);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                glGenerateMipmap(GL_TEXTURE_2D);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        }
    }

    public void render(FlyCamera camera, float aspectRatio) {
        render(camera, aspectRatio, new org.joml.Matrix4f());
    }

    /** worldMatrix: where this mesh actually sits in the scene - added
     * for container-contents item placement (see pbd.pz.ContainerContents),
     * where the same loaded FBX mesh needs rendering at a specific,
     * changing (falling) position rather than always at its own local
     * origin. Every pre-existing caller uses the identity-matrix
     * overload above and is completely unaffected. */
    public void render(FlyCamera camera, float aspectRatio, org.joml.Matrix4f worldMatrix) {
        program.use();
        glBindVertexArray(vao);

        glUniform3f(LOC_CAMERA_POS, camera.position.x, camera.position.y, camera.position.z);
        glUniform3f(LOC_LIGHT_DIR, lightDir[0], lightDir[1], lightDir[2]);
        glUniform3f(LOC_BASE_COLOR, baseColor[0], baseColor[1], baseColor[2]);
        glUniform2f(LOC_UV_SCALE, uvScale[0], uvScale[1]);

        if (textureGlId != -1) {
            glUniform1i(LOC_USE_TEXTURE, 1);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureGlId);
            // No glUniform1i needed for the sampler itself: classic.frag
            // declares it with an explicit `layout(binding = 0)`, which
            // permanently fixes it to texture unit 0 - matching
            // GL_TEXTURE0 above - without the application ever needing
            // to query or set a sampler uniform value at all.
        } else {
            glUniform1i(LOC_USE_TEXTURE, 0);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(LOC_VIEW_PROJ, false, camera.viewProj(aspectRatio).get(stack.mallocFloat(16)));
            glUniformMatrix4fv(LOC_WORLD_MATRIX, false, worldMatrix.get(stack.mallocFloat(16)));
        }

        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    @Override
    public void close() {
        if (textureGlId != -1) glDeleteTextures(textureGlId);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ibo);
        glDeleteVertexArrays(vao);
        program.close();
    }
}
