package pbd.voxel;

import pbd.format.PbdMeshData;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a renderable mesh from a VoxelOctree's filled regions - one
 * box per region (see VoxelOctree.collectFilledRegions), so a large
 * compressed region (a solid cube's whole interior, say - one region
 * covering many voxels) becomes ONE box's worth of geometry, not one
 * per underlying voxel. Outputs a PbdMeshData directly, which the
 * "mesh" instance rendering path (ClassicMeshRenderer, see Main.java's
 * buildMeshRenderer) already knows how to draw, textures and all -
 * this class only needs to get the geometry and UVs right, all the
 * actual GPU/rendering work is already built and already works.
 *
 * UV tiling: a face's UV span is its size IN VOXEL UNITS (world size
 * divided by voxelWorldSize), not a fixed 0..1 per face - a region
 * that's 4 voxels wide shows 4 texture repeats across that face, the
 * SAME density a true small-voxel edge would show, whether that
 * region is one merged box internally or (in principle) four separate
 * unmerged ones. This is what keeps the octree's own compression
 * invisible to how the result actually LOOKS - a viewer can't tell a
 * compressed region from an uncompressed run of individual voxels just
 * by how the texture tiles across it, which is the whole point of
 * getting this right rather than leaving every merged region's face
 * looking like one giant stretched texture.
 */
public final class VoxelMeshBuilder {

    private VoxelMeshBuilder() {}

    public static PbdMeshData buildMesh(List<int[]> regions, float voxelWorldSize) {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        for (int[] region : regions) {
            // region: [x, y, z, sizeInVoxels] - x/y/z * voxelWorldSize
            // for WORLD-space position, sizeInVoxels kept separately
            // (not pre-multiplied) so addFace can compute UV tiling
            // directly from it without needing to divide back out of a
            // world-space number.
            addBox(positions, normals, uvs, indices,
                region[0] * voxelWorldSize, region[1] * voxelWorldSize, region[2] * voxelWorldSize,
                region[3] * voxelWorldSize, region[3]);
        }

        return new PbdMeshData(toArray(positions), toArray(normals), toArray(uvs), toIntArray(indices));
    }

    /** One box, corner at world position (x,y,z), world-space edge
     * length worldSize (== sizeInVoxels * voxelWorldSize) - 6 faces, 4
     * vertices each (no sharing across faces, same "simple over clever"
     * choice export_pbd.py's own mesh export already made for
     * procedural geometry), 2 triangles per face. sizeInVoxels is
     * passed through separately for addFace's own UV tiling - see this
     * class's own doc on why tiling is by voxel count, not world size
     * directly. */
    private static void addBox(List<Float> pos, List<Float> nrm, List<Float> uv, List<Integer> idx,
                                float x, float y, float z, float worldSize, int sizeInVoxels) {
        float x0 = x, x1 = x + worldSize;
        float y0 = y, y1 = y + worldSize;
        float z0 = z, z1 = z + worldSize;

        // +X face
        addFace(pos, nrm, uv, idx,
            new float[]{x1, y0, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, new float[]{x1, y0, z1},
            1, 0, 0, sizeInVoxels, sizeInVoxels);
        // -X face
        addFace(pos, nrm, uv, idx,
            new float[]{x0, y0, z1}, new float[]{x0, y1, z1}, new float[]{x0, y1, z0}, new float[]{x0, y0, z0},
            -1, 0, 0, sizeInVoxels, sizeInVoxels);
        // +Y face
        addFace(pos, nrm, uv, idx,
            new float[]{x0, y1, z0}, new float[]{x0, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y1, z0},
            0, 1, 0, sizeInVoxels, sizeInVoxels);
        // -Y face
        addFace(pos, nrm, uv, idx,
            new float[]{x0, y0, z1}, new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y0, z1},
            0, -1, 0, sizeInVoxels, sizeInVoxels);
        // +Z face
        addFace(pos, nrm, uv, idx,
            new float[]{x1, y0, z1}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1}, new float[]{x0, y0, z1},
            0, 0, 1, sizeInVoxels, sizeInVoxels);
        // -Z face
        addFace(pos, nrm, uv, idx,
            new float[]{x0, y0, z0}, new float[]{x0, y1, z0}, new float[]{x1, y1, z0}, new float[]{x1, y0, z0},
            0, 0, -1, sizeInVoxels, sizeInVoxels);
    }

    /** tileW/tileH: this face's UV span, in voxel-count units (1.0 = one
     * voxel's worth of texture) - both equal to sizeInVoxels here,
     * since every region is cubic, but kept as separate parameters
     * rather than hardcoded equal, for the same "don't assume the only
     * caller forever" reason as addBox's own doc gives. */
    private static void addFace(List<Float> pos, List<Float> nrm, List<Float> uv, List<Integer> idx,
                                 float[] a, float[] b, float[] c, float[] d,
                                 float nx, float ny, float nz, float tileW, float tileH) {
        int base = pos.size() / 3;
        for (float[] v : new float[][]{a, b, c, d}) {
            pos.add(v[0]); pos.add(v[1]); pos.add(v[2]);
            nrm.add(nx); nrm.add(ny); nrm.add(nz);
        }
        uv.add(0f); uv.add(0f);
        uv.add(tileW); uv.add(0f);
        uv.add(tileW); uv.add(tileH);
        uv.add(0f); uv.add(tileH);

        idx.add(base); idx.add(base + 1); idx.add(base + 2);
        idx.add(base); idx.add(base + 2); idx.add(base + 3);
    }

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
