package pbd.classicmesh;

import org.lwjgl.system.MemoryStack;
import pbd.render.FlyCamera;
import pbd.render.ShaderProgram;

import java.io.IOException;
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
 */
public final class ClassicMeshRenderer implements AutoCloseable {

    private static final int LOC_VIEW_PROJ  = 0;
    private static final int LOC_CAMERA_POS = 1;
    private static final int LOC_LIGHT_DIR  = 2;
    private static final int LOC_BASE_COLOR = 3;
    private static final int LOC_WORLD_MATRIX = 4;

    private final ShaderProgram program;
    private final int vao;
    private final int vbo;
    private final int ibo;
    private final int indexCount;

    public final long uploadedBytes;
    public float[] lightDir = {0.3f, -1.0f, -0.2f};
    public float[] baseColor = {0.62f, 0.63f, 0.66f};

    public ClassicMeshRenderer(Path shaderDir, ObjMesh mesh) throws IOException {
        program = new ShaderProgram(shaderDir.resolve("classic.vert"), shaderDir.resolve("classic.frag"));

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, mesh.vertexData, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);

        ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices, GL_STATIC_DRAW);

        indexCount = mesh.indices.length;
        uploadedBytes = mesh.byteSize();
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

        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(LOC_VIEW_PROJ, false, camera.viewProj(aspectRatio).get(stack.mallocFloat(16)));
            glUniformMatrix4fv(LOC_WORLD_MATRIX, false, worldMatrix.get(stack.mallocFloat(16)));
        }

        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ibo);
        glDeleteVertexArrays(vao);
        program.close();
    }
}
