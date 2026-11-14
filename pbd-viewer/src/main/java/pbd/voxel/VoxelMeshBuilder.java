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

    /**
     * The colour of one corner of one face of one box: the box ({@code voxel}: x, y, z and, when it is a merged region, its size in voxels),
     * the corner in grid metres (voxel units times the voxel size, from the grid's own corner), and the direction the face looks
     * ({@code nx, ny, nz}: a unit vector along one axis). The direction is what lets a texture be mapped as the shader maps it: a corner on
     * an edge of the shape belongs to as many faces as meet there, and only the face being drawn says which one this corner is on.
     */
    @FunctionalInterface
    public interface FaceColor {
        float[] colorOf(int[] voxel, float[] corner, float nx, float ny, float nz);
    }

    /** A colour that does not depend on the direction of the face (the older, two-argument form). */
    public static FaceColor ignoringFace(java.util.function.BiFunction<int[], float[], float[]> colorFn) {
        return (voxel, corner, nx, ny, nz) -> colorFn.apply(voxel, corner);
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
        return buildMeshWithColor(voxels, voxelWorldSize, ignoringFace(colorFn), cullHiddenFaces);
    }

    /** As above, with a colour that knows which way the face looks (see {@link FaceColor}). */
    public static ColoredMesh buildMeshWithColor(List<int[]> voxels, float voxelWorldSize, FaceColor colorFn, boolean cullHiddenFaces) {
        return buildMeshWithColor(voxels, voxelWorldSize, colorFn, cullHiddenFaces, false);
    }

    /**
     * The most cells a face of a big box is cut into along one side by {@code perVoxelFaces}: a box of up to this many voxels is cut
     * into voxel-sized cells, a bigger one (in powers of two) into this many.
     */
    public static final int MAX_FACE_CELLS = 32;

    /**
     * perVoxelFaces: a face of a box bigger than one voxel that can be seen from outside is built from voxel-sized cells, each with the
     * colours of its own four corners, instead of one quad with four (the GPU blends four colours across a quad, which is no picture:
     * a texture needs a colour for every voxel of the skin, not for every box). Only the cells whose outward neighbour is empty are
     * built, so the hidden part of a face costs nothing. Needs the culling that knows what is outside (an octree-aligned list with
     * {@code cullHiddenFaces}); without it a big face stays one quad. A box bigger than {@link #MAX_FACE_CELLS} voxels is cut into at most
     * that many cells a side, so a very large flat skin stays within a bounded number of quads.
     */
    public static ColoredMesh buildMeshWithColor(List<int[]> voxels, float voxelWorldSize, FaceColor colorFn, boolean cullHiddenFaces, boolean perVoxelFaces) {
        int cap = voxels.size() * 24;
        FloatBuf positions = new FloatBuf(cap * 3), normals = new FloatBuf(cap * 3), uvs = new FloatBuf(cap * 2), colors = new FloatBuf(cap * 3);
        IntBuf indices = new IntBuf(voxels.size() * 36);

        VoxelSolid solid = (cullHiddenFaces && VoxelSolid.isOctreeAligned(voxels)) ? new VoxelSolid(voxels) : null;

        for (int[] voxel : voxels) {
            int sizeInVoxels = voxel.length > 3 ? voxel[3] : 1;
            addBox(positions, normals, uvs, indices, colors, colorFn, voxel, solid, perVoxelFaces && solid != null ? voxelWorldSize : 0f,
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
            addBox(positions, normals, uvs, indices, null, null, null, null, 0f,
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
                                FloatBuf colors, FaceColor colorFn, int[] voxel,
                                VoxelSolid solid, float cellVoxelSize,
                                float x, float y, float z, float worldSize, int sizeInVoxels) {
        float x0 = x, x1 = x + worldSize;
        float y0 = y, y1 = y + worldSize;
        float z0 = z, z1 = z + worldSize;

        // +X face
        if (faceVisible(solid, voxel, 1, 0, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel, solid, cellVoxelSize,
                new float[]{x1, y0, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, new float[]{x1, y0, z1},
                1, 0, 0, sizeInVoxels, sizeInVoxels);
        // -X face
        if (faceVisible(solid, voxel, -1, 0, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel, solid, cellVoxelSize,
                new float[]{x0, y0, z1}, new float[]{x0, y1, z1}, new float[]{x0, y1, z0}, new float[]{x0, y0, z0},
                -1, 0, 0, sizeInVoxels, sizeInVoxels);
        // +Y face
        if (faceVisible(solid, voxel, 0, 1, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel, solid, cellVoxelSize,
                new float[]{x0, y1, z0}, new float[]{x0, y1, z1}, new float[]{x1, y1, z1}, new float[]{x1, y1, z0},
                0, 1, 0, sizeInVoxels, sizeInVoxels);
        // -Y face
        if (faceVisible(solid, voxel, 0, -1, 0))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel, solid, cellVoxelSize,
                new float[]{x0, y0, z1}, new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y0, z1},
                0, -1, 0, sizeInVoxels, sizeInVoxels);
        // +Z face
        if (faceVisible(solid, voxel, 0, 0, 1))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel, solid, cellVoxelSize,
                new float[]{x1, y0, z1}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1}, new float[]{x0, y0, z1},
                0, 0, 1, sizeInVoxels, sizeInVoxels);
        // -Z face
        if (faceVisible(solid, voxel, 0, 0, -1))
            addFace(pos, nrm, uv, idx, colors, colorFn, voxel, solid, cellVoxelSize,
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
                                 FloatBuf colors, FaceColor colorFn, int[] voxel,
                                 VoxelSolid solid, float cellVoxelSize,
                                 float[] a, float[] b, float[] c, float[] d,
                                 float nx, float ny, float nz, float tileW, float tileH) {
        int boxVoxels = Math.round(tileW);
        if (cellVoxelSize > 0f && colorFn != null && boxVoxels > 1) {
            int cells = boxVoxels <= MAX_FACE_CELLS ? boxVoxels : (boxVoxels % MAX_FACE_CELLS == 0 ? MAX_FACE_CELLS : 0);
            if (cells > 0) {
                addFaceByCells(pos, nrm, uv, idx, colors, colorFn, solid, cellVoxelSize, a, b, d, nx, ny, nz, cells, boxVoxels / cells);
                return;
            }
        }
        addQuad(pos, nrm, uv, idx, colors, colorFn, voxel, a, b, c, d, nx, ny, nz, tileW, tileH);
    }

    /**
     * The face a, b, c, d (a to b along one side, a to d along the other) built from {@code cells} x {@code cells} cells of {@code tile}
     * voxels a side, each with the colours of its own corners - only those with something empty outside them. The box a cell is
     * coloured as is the one that stands on that cell (its corner voxel and its size), so the colour of a cell is what it would be if the
     * object had been cut into single voxels.
     */
    private static void addFaceByCells(FloatBuf pos, FloatBuf nrm, FloatBuf uv, IntBuf idx, FloatBuf colors, FaceColor colorFn,
                                       VoxelSolid solid, float voxelSize, float[] a, float[] b, float[] d,
                                       float nx, float ny, float nz, int cells, int tile) {
        double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double[] ad = {d[0] - a[0], d[1] - a[1], d[2] - a[2]};
        double[] outward = {nx, ny, nz};
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < cells; j++) {
                if (solid != null && !exposed(solid, voxelSize, a, ab, ad, outward, i, j, cells, tile)) continue;
                float[] p00 = onFace(a, ab, ad, i / (double) cells, j / (double) cells);
                float[] p10 = onFace(a, ab, ad, (i + 1) / (double) cells, j / (double) cells);
                float[] p11 = onFace(a, ab, ad, (i + 1) / (double) cells, (j + 1) / (double) cells);
                float[] p01 = onFace(a, ab, ad, i / (double) cells, (j + 1) / (double) cells);
                int[] cellBox = new int[4];
                for (int axis = 0; axis < 3; axis++) {
                    float low = Math.min(Math.min(p00[axis], p10[axis]), Math.min(p11[axis], p01[axis]));
                    cellBox[axis] = (int) Math.round(low / (double) voxelSize);
                }
                cellBox[3] = tile;
                addQuad(pos, nrm, uv, idx, colors, colorFn, cellBox, p00, p10, p11, p01, nx, ny, nz, tile, tile);
            }
        }
    }

    private static float[] onFace(float[] a, double[] ab, double[] ad, double s, double t) {
        return new float[]{(float) (a[0] + ab[0] * s + ad[0] * t), (float) (a[1] + ab[1] * s + ad[1] * t), (float) (a[2] + ab[2] * s + ad[2] * t)};
    }

    /** Whether any voxel just outside the cell (i, j) of the face - the tile x tile voxels it covers, one voxel out - is empty. */
    private static boolean exposed(VoxelSolid solid, float voxelSize, float[] a, double[] ab, double[] ad, double[] outward,
                                   int i, int j, int cells, int tile) {
        int steps = cells * tile;
        for (int u = 0; u < tile; u++) {
            for (int v = 0; v < tile; v++) {
                double s = (i * tile + u + 0.5) / steps, t = (j * tile + v + 0.5) / steps;
                int vx = (int) Math.floor((a[0] + ab[0] * s + ad[0] * t + outward[0] * 0.5 * voxelSize) / voxelSize);
                int vy = (int) Math.floor((a[1] + ab[1] * s + ad[1] * t + outward[1] * 0.5 * voxelSize) / voxelSize);
                int vz = (int) Math.floor((a[2] + ab[2] * s + ad[2] * t + outward[2] * 0.5 * voxelSize) / voxelSize);
                if (solid.entryAt(vx, vy, vz) < 0) return true;
            }
        }
        return false;
    }

    /** One quad: four vertices a, b, c, d (colour of each from colorFn, if any), two triangles. */
    private static void addQuad(FloatBuf pos, FloatBuf nrm, FloatBuf uv, IntBuf idx,
                                 FloatBuf colors, FaceColor colorFn, int[] voxel,
                                 float[] a, float[] b, float[] c, float[] d,
                                 float nx, float ny, float nz, float tileW, float tileH) {
        int base = pos.n / 3;
        for (float[] v : new float[][]{a, b, c, d}) {
            pos.add(v[0]); pos.add(v[1]); pos.add(v[2]);
            nrm.add(nx); nrm.add(ny); nrm.add(nz);
            if (colorFn != null) {
                float[] vc = colorFn.colorOf(voxel, v, nx, ny, nz);
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
