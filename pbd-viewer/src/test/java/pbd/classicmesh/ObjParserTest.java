package pbd.classicmesh;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ObjParserTest {

    @Test
    void deduplicatesHardEdgeVerticesAndTriangulatesQuads() throws Exception {
        ObjMesh mesh = ObjParser.parse(Path.of("src/test/resources/cube.obj"));

        // 8 positions, each used by exactly 3 faces with a different normal
        // per face (hard edges) -> 24 unique (position, normal) vertices.
        assertEquals(24, mesh.vertexCount());
        // 6 quads, fan-triangulated -> 2 triangles each.
        assertEquals(12, mesh.triangleCount());
        assertEquals(36, mesh.indices.length);

        assertEquals(-1f, mesh.vertexData[0], 1e-6f);
        assertEquals(-1f, mesh.vertexData[1], 1e-6f);
        assertEquals(-1f, mesh.vertexData[2], 1e-6f);
        assertEquals(0f, mesh.vertexData[3], 1e-6f);
        assertEquals(0f, mesh.vertexData[4], 1e-6f);
        assertEquals(-1f, mesh.vertexData[5], 1e-6f);

        assertEquals((long) mesh.vertexData.length * Float.BYTES + (long) mesh.indices.length * Integer.BYTES,
            mesh.byteSize());
    }

    @Test
    void mergesPositiveAndNegativeIndicesReferringToTheSameVertex() throws Exception {
        Path path = Path.of("build/tmp/test-tri-no-normals.obj");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
            f -3 -2 -1
            """);

        ObjMesh mesh = ObjParser.parse(path);

        // Both faces reference the same 3 positions (once via 1-based
        // absolute indices, once via negative/relative ones) - they must
        // resolve to the same 3 output vertices, not 6.
        assertEquals(3, mesh.vertexCount());
        assertEquals(2, mesh.triangleCount());

        // No vn anywhere in the file -> flat per-vertex normals computed
        // from the two (identical, oppositely-wound or not) triangles.
        float nz = mesh.vertexData[5];
        assertEquals(1f, Math.abs(nz), 1e-4f);
    }
}
