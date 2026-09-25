package pbd.classicmesh;

/**
 * Parsed Wavefront OBJ geometry: interleaved position(3)+normal(3)[+uv(2)]
 * floats per vertex, and a flat triangle index list (n-gons already fan-
 * triangulated by the parser). This is the traditional, pre-baked mesh
 * representation the PBD pipeline is benchmarked against - every vertex
 * here is real, uploaded data, unlike a PBD instance's few dozen bytes.
 */
public final class ObjMesh {

    public final float[] vertexData; // pos.x,y,z, nrm.x,y,z[, u,v], repeated - see hasUv for which
    public final int[] indices;
    // False (6 floats/vertex, pos+normal only) for the traditional OBJ
    // benchmark path - ObjParser doesn't extract UVs from a .obj file
    // (a separate, real gap; not attempted here alongside this one).
    // True (8 floats/vertex, pos+normal+uv) for a "mesh"-type PBD
    // instance, which already HAS real per-vertex UVs (see
    // PbdMeshData.toPositionNormalUvInterleaved) - kept as an explicit
    // flag rather than inferred from array length, since inference here
    // would be guessing at a caller's intent from a number that could
    // coincidentally divide evenly by 8 for a 6-float mesh too.
    public final boolean hasUv;
    // Per-voxel color for a voxel-destruction mesh (see
    // VoxelMeshBuilder's own doc on why a shared UV-tiled texture
    // isn't used for these) - 3 floats/vertex, parallel to vertexData
    // rather than interleaved into it (keeping the existing 6/8-float
    // stride logic below untouched for every OTHER caller, which never
    // sets this). Null for every mesh that isn't a voxel result.
    public final float[] colors;

    public ObjMesh(float[] vertexData, int[] indices) {
        this(vertexData, indices, false);
    }

    public ObjMesh(float[] vertexData, int[] indices, boolean hasUv) {
        this(vertexData, indices, hasUv, null);
    }

    public ObjMesh(float[] vertexData, int[] indices, boolean hasUv, float[] colors) {
        this.vertexData = vertexData;
        this.indices = indices;
        this.hasUv = hasUv;
        this.colors = colors;
    }

    private int stride() {
        return hasUv ? 8 : 6;
    }

    public int vertexCount() {
        return vertexData.length / stride();
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** Bytes that would be uploaded to the GPU for this mesh (vertex buffer + index buffer). */
    public long byteSize() {
        return (long) vertexData.length * Float.BYTES + (long) indices.length * Integer.BYTES;
    }
}
