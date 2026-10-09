package pbd.voxel;

import pbd.format.PbdMeshData;

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

    /** Growable primitive float array. This class used to accumulate
     * every vertex attribute in a List&lt;Float&gt; - one boxed object per
     * float, ~16-24 bytes each against 4 for the float itself. With a
     * solid crater on a real object that is tens of thousands of cubes
     * (hundreds of thousands of vertices), i.e. a transient allocation
     * of hundreds of MB for what is ~20 MB of actual data, which is the
     * very memory pressure behind the crater OutOfMemoryError this
     * project already hit once. */
    private static final class FloatBuf {
        float[] a;
        int n;
        FloatBuf(int capacity) { a = new float[Math.max(16, capacity)]; }
        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    private static final class IntBuf {
        int[] a;
        int n;
        IntBuf(int capacity) { a = new int[Math.max(16, capacity)]; }
        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    /** Same box-per-region geometry as buildMesh below, but each
     * vertex carries its own color from colorFn, called ONCE PER VERTEX
     * (voxel, worldVertexPosition) - see addFace for why per-vertex and
     * not per-region (a region can be dozens of voxels across; one flat
     * color per region discarded essentially all the texture detail on
     * the large, untouched blocks that make up most of a real object's
     * surface - a real, reported bug). Equivalent to the overload
     * below with cullHiddenFaces=true.
     *
     * Entries may be any size (a mix of large untouched regions and
     * single crater-adjacent voxels is the normal case - the size is
     * used for BOTH the box extent and the UV tiling), each drawn as
     * a box at (x,y,z)*voxelWorldSize. */
    public static ColoredMesh buildMeshWithColor(List<int[]> voxels, float voxelWorldSize,
                                                   java.util.function.BiFunction<int[], float[], float[]> colorFn) {
        return buildMeshWithColor(voxels, voxelWorldSize, colorFn, true);
    }

    /**
     * cullHiddenFaces: skip a box's face when the voxel just beyond it
     * belongs to a box at least as large (so that neighbour covers the
     * whole face - nothing of it could ever be seen). With a solid
     * crater most boxes are buried inside other boxes, and this is what
     * keeps the vertex count proportional to the VISIBLE surface rather
     * than to the number of boxes times 24. Only valid for octree-
     * aligned entries (see VoxelSolid); for anything else - or when
     * false - every face of every box is emitted, exactly as before.
     */
    public static ColoredMesh buildMeshWithColor(List<int[]> voxels, float voxelWorldSize,
                                                   java.util.function.BiFunction<int[], float[], float[]> colorFn,
                                                   boolean cullHiddenFaces) {
        int cap = voxels.size() * 24;
        FloatBuf positions = new FloatBuf(cap * 3), normals = new FloatBuf(cap * 3), uvs = new FloatBuf(cap * 2), colors = new FloatBuf(cap * 3);
        IntBuf indices = new IntBuf(voxels.size() * 36);

        VoxelSolid solid = (cullHiddenFaces && VoxelSolid.isOctreeAligned(voxels)) ? new VoxelSolid(voxels) : null;

        for (int[] voxel : voxels) {
            int sizeInVoxels = voxel.length > 3 ? voxel[3] : 1;
            addBox(positions, normals, uvs, indices, colors, colorFn, voxel, solid,
                voxel[0] * voxelWorldSize, voxel[1] * voxelWorldSize, voxel[2] * voxelWorldSize,
                sizeInVoxels * voxelWorldSize, sizeInVoxels);
        }

        PbdMeshData meshData = new PbdMeshData(positions.toArray(), normals.toArray(), uvs.toArray(), indices.toArray());
        return new ColoredMesh(meshData, colors.toArray());
    }

    public static PbdMeshData buildMesh(List<int[]> regions, float voxelWorldSize) {
        FloatBuf positions = new FloatBuf(regions.size() * 72), normals = new FloatBuf(regions.size() * 72), uvs = new FloatBuf(regions.size() * 48);
        IntBuf indices = new IntBuf(regions.size() * 36);

        for (int[] region : regions) {
            // region: [x, y, z, sizeInVoxels] - x/y/z * voxelWorldSize
            // for WORLD-space position, sizeInVoxels kept separately
            // (not pre-multiplied) so addFace can compute UV tiling
            // directly from it without needing to divide back out of a
            // world-space number.
            addBox(positions, normals, uvs, indices, null, null, null, null,
                region[0] * voxelWorldSize, region[1] * voxelWorldSize, region[2] * voxelWorldSize,
                region[3] * voxelWorldSize, region[3]);
        }

        return new PbdMeshData(positions.toArray(), normals.toArray(), uvs.toArray(), indices.toArray());
    }

    /** One box, corner at world position (x,y,z), world-space edge
     * length worldSize (== sizeInVoxels * voxelWorldSize) - up to 6
     * faces, 4 vertices each (no sharing across faces, same "simple
     * over clever" choice export_pbd.py's own mesh export already made
     * for procedural geometry), 2 triangles per face. sizeInVoxels is
     * passed through separately for addFace's own UV tiling - see this
     * class's own doc on why tiling is by voxel count, not world size
     * directly. colors/colorFn/voxel: null for buildMesh's own
     * uncolored path (no per-vertex color list to build at all);
     * non-null for buildMeshWithColor, which needs every vertex's own
     * color appended in the exact same order/count as positions (see
     * addFace's own doc for why this is now done per-vertex here
     * rather than broadcast by the caller after the fact). solid: when
     * non-null, a face whose outward neighbour box is at least as large
     * is skipped (see buildMeshWithColor's cullHiddenFaces). */
    private static void addBox(FloatBuf pos, FloatBuf nrm, FloatBuf uv, IntBuf idx,
                                FloatBuf colors, java.util.function.BiFunction<int[], float[], float[]> colorFn, int[] voxel,
                                VoxelSolid solid,
                                float x, float y, float z, float worldSize, int sizeInVoxels) {
        float x0 = x, x1 = x + worldSize;
        float y0 = y, y1 = y + worldSize;
        float z0 = z, z1 = z + worldSize;

        // +X face
        if (faceVisible(solid, voxel, 1, 0, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel,
                new float[]{x1, y0, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, new float[]{x1, y0, z1},
                1, 0, 0, sizeInVoxels, sizeInVoxels);
        // -X face
        if (faceVisible(solid, voxel, -1, 0, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel,
                new float[]{x0, y0, z1}, new float[]{x0, y1, z1}, new float[]{x0, y1, z0}, new float[]{x0, y0, z0},
                -1, 0, 0, sizeInVoxels, sizeInVoxels);
        // +Y face
        if (faceVisible(solid, voxel, 0, 1, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel,
                new float[]{x0, y1, z0}, new float[]{x0, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y1, z0},
                0, 1, 0, sizeInVoxels, sizeInVoxels);
        // -Y face
        if (faceVisible(solid, voxel, 0, -1, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel,
                new float[]{x0, y0, z1}, new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y0, z1},
                0, -1, 0, sizeInVoxels, sizeInVoxels);
        // +Z face
        if (faceVisible(solid, voxel, 0, 0, 1))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel,
                new float[]{x1, y0, z1}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1}, new float[]{x0, y0, z1},
                0, 0, 1, sizeInVoxels, sizeInVoxels);
        // -Z face
        if (faceVisible(solid, voxel, 0, 0, -1))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel,
                new float[]{x0, y0, z0}, new float[]{x0, y1, z0}, new float[]{x1, y1, z0}, new float[]{x1, y0, z0},
                0, 0, -1, sizeInVoxels, sizeInVoxels);
    }

    /** False only when solid != null and the voxel just beyond this
     * box's low corner on that face belongs to a box at least as large
     * as this one - which, for octree-aligned boxes, means it covers
     * the whole face (see VoxelConnectivity's class doc for the
     * argument). A smaller or absent neighbour leaves the face
     * (at least partly) exposed, so it is kept. */
    private static boolean faceVisible(VoxelSolid solid, int[] voxel, int nx, int ny, int nz) {
        if (solid == null || voxel == null) return true;
        int s = voxel.length > 3 ? voxel[3] : 1;
        int px = voxel[0] + (nx > 0 ? s : nx < 0 ? -1 : 0);
        int py = voxel[1] + (ny > 0 ? s : ny < 0 ? -1 : 0);
        int pz = voxel[2] + (nz > 0 ? s : nz < 0 ? -1 : 0);
        int j = solid.entryAt(px, py, pz);
        if (j < 0) return true;
        return solid.sizeOf(j) < s;
    }

    /** tileW/tileH: this face's UV span, in voxel-count units (1.0 = one
     * voxel's worth of texture) - both equal to sizeInVoxels here,
     * since every region is cubic, but kept as separate parameters
     * rather than hardcoded equal, for the same "don't assume the only
     * caller forever" reason as addBox's own doc gives. colorFn, when
     * non-null, is called once per vertex (a,b,c,d each individually -
     * NOT once for the whole face) with that vertex's own real world
     * position - see buildMeshWithColor's own doc for why this changed
     * from one shared call per region. */
    private static void addFace(FloatBuf pos, FloatBuf nrm, FloatBuf uv, IntBuf idx,
                                 FloatBuf colors, java.util.function.BiFunction<int[], float[], float[]> colorFn, int[] voxel,
                                 float[] a, float[] b, float[] c, float[] d,
                                 float nx, float ny, float nz, float tileW, float tileH) {
        int base = pos.n / 3;
        for (float[] v : new float[][]{a, b, c, d}) {
            pos.add(v[0]); pos.add(v[1]); pos.add(v[2]);
            nrm.add(nx); nrm.add(ny); nrm.add(nz);
            if (colorFn != null) {
                float[] vc = colorFn.apply(voxel, v);
                colors.add(vc[0]); colors.add(vc[1]); colors.add(vc[2]);
            }
        }
        uv.add(0f); uv.add(0f);
        uv.add(tileW); uv.add(0f);
        uv.add(tileW); uv.add(tileH);
        uv.add(0f); uv.add(tileH);

        idx.add(base); idx.add(base + 1); idx.add(base + 2);
        idx.add(base); idx.add(base + 2); idx.add(base + 3);
    }
}
