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

    /** Result of buildMeshWithColor below - PbdMeshData alone has
     * nowhere to carry a color (see that class's own fixed position/
     * normal/uv/indices fields - shared with the actual .pbd file
     * format's own serialization, not something to add a new field to
     * for a purely transient, never-saved voxel-destruction mesh), so
     * colors travels alongside it instead: 3 floats per vertex,
     * parallel to meshData's own position/normal arrays, ready to hand
     * straight to ObjMesh's own colors parameter. */
    public static final class ColoredMesh {
        public final PbdMeshData meshData;
        public final float[] colors;
        ColoredMesh(PbdMeshData meshData, float[] colors) {
            this.meshData = meshData;
            this.colors = colors;
        }
    }

    /** Same box-per-region geometry as buildMesh above, but for
     * INDIVIDUAL unit voxels (size always 1 - see
     * VoxelOctree.collectFilledUnitVoxels, not collectFilledRegions:
     * merging same-material voxels back into large regions the way
     * buildMesh does would average away exactly the per-voxel color
     * variation this method exists to add), each colored by colorFn -
     * called once per voxel with its OWN grid coordinate (x,y,z, the
     * same coordinate space collectFilledUnitVoxels' own int[] uses),
     * returning that voxel's {r,g,b}. A flat color function (every
     * voxel gets the primitive's own single material color) is a valid
     * colorFn - nothing here requires per-voxel variation, only
     * supports it. No UV tiling at all here (unlike buildMesh) - a
     * uv=(0,0) placeholder on every vertex, since a flat per-voxel
     * color has no texture to tile in the first place; classic.frag's
     * useVertexColor mode ignores vUV entirely when active. */
    public static ColoredMesh buildMeshWithColor(List<int[]> voxels, float voxelWorldSize,
                                                   java.util.function.Function<int[], float[]> colorFn) {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        List<Float> colors = new ArrayList<>();

        for (int[] voxel : voxels) {
            int vertsBefore = positions.size() / 3;
            int sizeInVoxels = voxel.length > 3 ? voxel[3] : 1;
            // sizeInVoxels used for BOTH the world-space box size AND
            // addBox's own UV-tiling parameter (buildMesh's own doc
            // above explains why size is passed both ways) - this
            // fixes a real bug: this method originally only ever
            // received collectFilledUnitVoxels' own output, where every
            // entry genuinely is size 1, so hardcoding 1 here happened
            // to be harmless at the time. It stopped being harmless the
            // moment a caller (Main.java's G-key handler, doing hybrid
            // region/voxel crater filtering) started passing a MIX of
            // untouched large regions and individual crater-adjacent
            // voxels through this same method - a hardcoded 1 would
            // have drawn every large region as a single tiny voxel-
            // sized box at its own corner, leaving the rest of that
            // region's actual volume completely unrendered.
            addBox(positions, normals, uvs, indices,
                voxel[0] * voxelWorldSize, voxel[1] * voxelWorldSize, voxel[2] * voxelWorldSize,
                sizeInVoxels * voxelWorldSize, sizeInVoxels);
            int vertsAdded = positions.size() / 3 - vertsBefore;
            float[] c = colorFn.apply(voxel);
            for (int i = 0; i < vertsAdded; i++) {
                colors.add(c[0]); colors.add(c[1]); colors.add(c[2]);
            }
        }

        PbdMeshData meshData = new PbdMeshData(toArray(positions), toArray(normals), toArray(uvs), toIntArray(indices));
        return new ColoredMesh(meshData, toArray(colors));
    }

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
