package pbd.classicmesh;

/**
 * Parsed Wavefront OBJ geometry: interleaved position(3)+normal(3) floats
 * per vertex, and a flat triangle index list (n-gons already fan-
 * triangulated by the parser). This is the traditional, pre-baked mesh
 * representation the PBD pipeline is benchmarked against - every vertex
 * here is real, uploaded data, unlike a PBD instance's few dozen bytes.
 */
public final class ObjMesh {

    public final float[] vertexData; // pos.x, pos.y, pos.z, nrm.x, nrm.y, nrm.z, repeated
    public final int[] indices;

    public ObjMesh(float[] vertexData, int[] indices) {
        this.vertexData = vertexData;
        this.indices = indices;
    }

    public int vertexCount() {
        return vertexData.length / 6;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** Bytes that would be uploaded to the GPU for this mesh (vertex buffer + index buffer). */
    public long byteSize() {
        return (long) vertexData.length * Float.BYTES + (long) indices.length * Integer.BYTES;
    }
}
