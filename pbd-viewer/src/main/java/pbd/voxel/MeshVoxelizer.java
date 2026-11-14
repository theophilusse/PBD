package pbd.voxel;

import pbd.format.PbdInstance;
import pbd.format.PbdMeshData;

import java.util.HashMap;
import java.util.Map;

/**
 * The meshes whose inside cannot be found by counting the crossings of a ray: a single triangle, a flat quad, a sheet, a cloth, a
 * cup made of one open surface - anything that is not closed. Such a mesh has no inside, only a surface, so the surface is what is
 * voxelized, thickened into a wall: every voxel whose centre is within half a wall's thickness of a triangle is filled
 * ({@link ForwardVoxelizer#SHELL_VOXELS} voxels thick, grown to both sides because a mesh has no inward). A closed mesh keeps the
 * ray-parity voxelization of {@link PrimitiveVoxelizer} - it has an inside, and the parity finds it.
 *
 * Also here: the test that tells the two apart ({@link #isClosed}, every edge shared by an even number of triangles once the
 * vertices that coincide are welded) and the validation of a mesh before anything reads it ({@link #isUsable}) - the arrays of a
 * mesh come out of a file, so an index outside the vertices or a NaN is something to refuse, never to trust.
 *
 * How a triangle is voxelized: its thickened box is scanned along the axis its normal is most aligned with, one column at a time -
 * a column crosses the slab of the triangle's plane over a few voxels only, so the work follows the area of the triangle, not the
 * volume of its bounding box (a 100 m floor is two triangles). Each candidate voxel is decided by its exact distance to the
 * triangle. The grid is as coarse as it has to be for the work and the octree to stay within their limits, like every other
 * voxelization (the result says which voxel size it used).
 */
public final class MeshVoxelizer {
    private MeshVoxelizer() {}

    /** Most distance evaluations one voxelization may need: about a second of work. */
    static final long MAX_WORK = 40_000_000L;
    /** Welding tolerance, as a fraction of the mesh's longest side. */
    static final double WELD = 1e-5;

    // ------------------------------------------------------------------
    // Validation and closedness
    // ------------------------------------------------------------------

    /** Whether the mesh can be read at all: it has at least one whole triangle, every index names a vertex, every coordinate is a number. */
    public static boolean isUsable(PbdMeshData mesh) {
        if (mesh == null || mesh.positions == null || mesh.indices == null) return false;
        if (mesh.indices.length < 3 || mesh.positions.length < 9) return false;
        int vertexCount = mesh.positions.length / 3;
        int whole = mesh.indices.length - mesh.indices.length % 3;   // an index left over after the last whole triangle is never read
        for (int i = 0; i < whole; i++) {
            int idx = mesh.indices[i];
            if (idx < 0 || idx >= vertexCount) return false;
        }
        for (int i = 0; i < vertexCount * 3; i++) {
            if (!Float.isFinite(mesh.positions[i])) return false;
        }
        return true;
    }

    /**
     * Whether the surface has no boundary: with the vertices that stand at the same place welded together, every edge of a
     * triangle is shared with another one (an even number of triangles per edge, so two shells that touch are still closed).
     * A triangle with two corners at the same place is a line and counts for nothing. A mesh with no whole triangle is not closed.
     */
    public static boolean isClosed(PbdMeshData mesh) {
        int[] weld = weld(mesh);
        Map<Long, Integer> edges = new HashMap<>();
        boolean any = false;
        for (int i = 0; i + 2 < mesh.indices.length; i += 3) {
            int a = weld[mesh.indices[i]], c = weld[mesh.indices[i + 1]], d = weld[mesh.indices[i + 2]];
            if (a == c || c == d || a == d) continue;
            any = true;
            edges.merge(edgeKey(a, c), 1, Integer::sum);
            edges.merge(edgeKey(c, d), 1, Integer::sum);
            edges.merge(edgeKey(d, a), 1, Integer::sum);
        }
        if (!any) return false;
        for (int count : edges.values()) {
            if ((count & 1) != 0) return false;
        }
        return true;
    }

    /**
     * For each vertex, the first vertex standing within {@link #WELD} times the mesh's longest side of it (itself when there is none): the
     * vertices of a mesh whose faces each have their own corners coincide, and are one and the same corner for what follows. Found
     * with a grid of cells one tolerance wide and a look at the 27 cells around a vertex - two corners a hair apart are welded
     * even when a cell boundary falls between them.
     */
    public static int[] weld(PbdMeshData mesh) {
        int vertexCount = mesh.positions.length / 3;
        double[] b = boundsOf(mesh);
        double tol = Math.max(Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2])), 1e-9) * WELD;
        double tol2 = tol * tol;
        Map<Long, Integer> head = new HashMap<>();   // cell -> the latest representative in it
        int[] chain = new int[vertexCount];          // representative -> the one before it in the same cell, or -1
        int[] rep = new int[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            double x = mesh.positions[v * 3], y = mesh.positions[v * 3 + 1], z = mesh.positions[v * 3 + 2];
            long cx = (long) Math.floor((x - b[0]) / tol), cy = (long) Math.floor((y - b[1]) / tol), cz = (long) Math.floor((z - b[2]) / tol);
            int found = -1;
            search:
            for (long dx = -1; dx <= 1; dx++) for (long dy = -1; dy <= 1; dy++) for (long dz = -1; dz <= 1; dz++) {
                Integer r = head.get(cellKey(cx + dx, cy + dy, cz + dz));
                while (r != null && r >= 0) {
                    double ex = mesh.positions[r * 3] - x, ey = mesh.positions[r * 3 + 1] - y, ez = mesh.positions[r * 3 + 2] - z;
                    if (ex * ex + ey * ey + ez * ez <= tol2) { found = r; break search; }
                    r = chain[r];
                }
            }
            if (found >= 0) {
                rep[v] = found;
            } else {
                long key = cellKey(cx, cy, cz);
                Integer previous = head.get(key);
                chain[v] = previous == null ? -1 : previous;
                head.put(key, v);
                rep[v] = v;
            }
        }
        return rep;
    }

    /** One long per cell: 21 bits per axis (a cell index never exceeds 1e5 + 1, and the neighbours of cell 0 are -1: a bias keeps it positive). */
    private static long cellKey(long cx, long cy, long cz) {
        return ((cx + 2) << 42) | ((cy + 2) << 21) | (cz + 2);
    }

    private static long edgeKey(int a, int b) {
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return (lo << 32) | hi;
    }

    private static double[] boundsOf(PbdMeshData mesh) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int v = 0; v < mesh.positions.length / 3; v++) {
            for (int a = 0; a < 3; a++) {
                double p = mesh.positions[v * 3 + a];
                if (p < b[a]) b[a] = p;
                if (p > b[3 + a]) b[3 + a] = p;
            }
        }
        return b;
    }

    // ------------------------------------------------------------------
    // The thin shell
    // ------------------------------------------------------------------

    /**
     * Voxelizes the surface of a mesh as a wall. Positions are taken with the instance scale applied (the frame of the result is the
     * instance's local frame in metres, like every other result). Returns null when nothing is left at this voxel size (every
     * triangle degenerate, or the mesh smaller than the wall) - the caller destroys such an object outright.
     */
    static PrimitiveVoxelizer.Result voxelizeThin(PbdInstance inst, float targetVoxelWorldSize) {
        PbdMeshData mesh = inst.meshData;
        int vertexCount = mesh.positions.length / 3;
        double[] p = new double[vertexCount * 3];
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] sc = {inst.scale.x, inst.scale.y, inst.scale.z};
        for (int v = 0; v < vertexCount; v++) {
            for (int a = 0; a < 3; a++) {
                double x = mesh.positions[v * 3 + a] * sc[a];
                if (!Double.isFinite(x)) return null;
                p[v * 3 + a] = x;
                lo[a] = Math.min(lo[a], x);
                hi[a] = Math.max(hi[a], x);
            }
        }
        double extent = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        if (!(extent > 1e-9)) return null;

        double vws = targetVoxelWorldSize;
        long work = 0;
        for (int attempt = 0; attempt < 30; attempt++) {
            double h = wallThickness(vws) / 2.0;
            int pad = (int) Math.ceil(h / vws) + 1;
            double gridMax = Math.ceil(extent / vws) + 2 * pad + 1;
            if (gridMax > PrimitiveVoxelizer.MAX_GRID_CELLS) {
                vws *= Math.max(1.05, gridMax / PrimitiveVoxelizer.MAX_GRID_CELLS);
                continue;
            }
            work = estimateWork(mesh, p, vws, h);
            if (work > MAX_WORK) {
                vws *= Math.max(1.1, Math.sqrt((double) work / MAX_WORK));
                continue;
            }
            return fill(inst, mesh, p, lo, hi, vws, h, pad);
        }
        return null;
    }

    /**
     * How thick the wall of a thin mesh is made at this voxel size: three voxels (15 mm at 5 mm) - but never more than a slab's
     * 10 cm unless two voxels already are, and never fewer than two voxels, which is the thinnest wall a grid can hold.
     */
    static double wallThickness(double voxelWorldSize) {
        return Math.max(2.0 * voxelWorldSize, Math.min(ForwardVoxelizer.SHELL_VOXELS * voxelWorldSize, ForwardVoxelizer.SLAB_MAX));
    }

    /** Number of distance evaluations the fill will make: for each triangle, its columns times the voxels a column crosses. */
    private static long estimateWork(PbdMeshData mesh, double[] p, double vws, double h) {
        long total = 0;
        int[] t = new int[3];
        for (int i = 0; i + 2 < mesh.indices.length; i += 3) {
            Column c = Column.of(p, mesh.indices[i], mesh.indices[i + 1], mesh.indices[i + 2], vws, h);
            if (c == null) continue;
            total += c.cols() * c.depthVoxels();
            if (total > MAX_WORK * 4) return total; // already far past the limit
        }
        return total;
    }

    /** How one triangle is scanned: the axis its normal is most aligned with ("depth"), the other two ("columns"), and the extent of both in voxel indices. */
    private record Column(int depthAxis, int pAxis, int qAxis, double nx, double ny, double nz, double lenN,
                          long p0, long p1, long q0, long q1, double vws, double h, double slope) {
        static Column of(double[] p, int ia, int ib, int ic, double vws, double h) {
            double ax = p[ia * 3], ay = p[ia * 3 + 1], az = p[ia * 3 + 2];
            double ux = p[ib * 3] - ax, uy = p[ib * 3 + 1] - ay, uz = p[ib * 3 + 2] - az;
            double wx = p[ic * 3] - ax, wy = p[ic * 3 + 1] - ay, wz = p[ic * 3 + 2] - az;
            double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(len > 1e-18)) return null;                   // a line or a point: no surface
            double anx = Math.abs(nx), any = Math.abs(ny), anz = Math.abs(nz);
            int depth = anx >= any && anx >= anz ? 0 : any >= anz ? 1 : 2;
            int pa = depth == 0 ? 1 : 0, qa = depth == 2 ? 1 : 2;
            double[] tri = {ax, ay, az, p[ib * 3], p[ib * 3 + 1], p[ib * 3 + 2], p[ic * 3], p[ic * 3 + 1], p[ic * 3 + 2]};
            double pmin = Math.min(tri[pa], Math.min(tri[3 + pa], tri[6 + pa])) - h, pmax = Math.max(tri[pa], Math.max(tri[3 + pa], tri[6 + pa])) + h;
            double qmin = Math.min(tri[qa], Math.min(tri[3 + qa], tri[6 + qa])) - h, qmax = Math.max(tri[qa], Math.max(tri[3 + qa], tri[6 + qa])) + h;
            // voxel k has its centre at (k + 0.5) * vws: the indices whose centres lie in [min, max]
            long p0 = (long) Math.ceil(pmin / vws - 0.5), p1 = (long) Math.floor(pmax / vws - 0.5);
            long q0 = (long) Math.ceil(qmin / vws - 0.5), q1 = (long) Math.floor(qmax / vws - 0.5);
            double slope = len / (depth == 0 ? anx : depth == 1 ? any : anz); // 1 / cos(angle between the normal and the depth axis)
            return new Column(depth, pa, qa, nx, ny, nz, len, p0, p1, q0, q1, vws, h, slope);
        }

        long cols() { return Math.max(0, p1 - p0 + 1) * Math.max(0, q1 - q0 + 1); }

        /** Voxels along the depth axis a column of the slab crosses (at most). */
        long depthVoxels() { return (long) Math.ceil(2.0 * h * slope / vws) + 2; }
    }

    private static PrimitiveVoxelizer.Result fill(PbdInstance inst, PbdMeshData mesh, double[] p, double[] lo, double[] hi, double vws, double h, int pad) {
        // the grid: absolute voxel indices from gmin to gmax on each axis, inside the octree
        long[] gmin = new long[3], gmax = new long[3];
        for (int a = 0; a < 3; a++) {
            gmin[a] = (long) Math.floor(lo[a] / vws) - pad;
            gmax[a] = (long) Math.floor(hi[a] / vws) + pad;
        }
        int[] g = {(int) (gmax[0] - gmin[0] + 1), (int) (gmax[1] - gmin[1] + 1), (int) (gmax[2] - gmin[2] + 1)};
        int gridMax = Math.max(g[0], Math.max(g[1], g[2]));
        int depth = 1;
        while ((1 << depth) < gridMax && depth < PrimitiveVoxelizer.MAX_OCTREE_DEPTH) depth++;
        VoxelOctree octree = new VoxelOctree(depth);
        int size = octree.gridSize();
        int[] off = {(size - g[0]) / 2, (size - g[1]) / 2, (size - g[2]) / 2};

        double h2 = h * h * (1.0 + 1e-9);   // a centre exactly h away is in (a surface on a voxel boundary would otherwise be 2 or 4 voxels thick at the whim of rounding)
        long inserted = 0;
        long[] idx = new long[3];
        for (int i = 0; i + 2 < mesh.indices.length; i += 3) {
            int ia = mesh.indices[i], ib = mesh.indices[i + 1], ic = mesh.indices[i + 2];
            Column c = Column.of(p, ia, ib, ic, vws, h);
            if (c == null) continue;
            double ax = p[ia * 3], ay = p[ia * 3 + 1], az = p[ia * 3 + 2];
            double bx = p[ib * 3], by = p[ib * 3 + 1], bz = p[ib * 3 + 2];
            double cx = p[ic * 3], cy = p[ic * 3 + 1], cz = p[ic * 3 + 2];
            double[] n = {c.nx(), c.ny(), c.nz()};
            double[] a0 = {ax, ay, az};
            double nd = n[c.depthAxis()];
            double reach = h * c.slope();                // half the height of the slab over a column
            for (long ip = c.p0(); ip <= c.p1(); ip++) {
                double xp = (ip + 0.5) * vws;
                for (long iq = c.q0(); iq <= c.q1(); iq++) {
                    double xq = (iq + 0.5) * vws;
                    // the height of the triangle's plane over this column
                    double plane = a0[c.depthAxis()] - (n[c.pAxis()] * (xp - a0[c.pAxis()]) + n[c.qAxis()] * (xq - a0[c.qAxis()])) / nd;
                    long k0 = (long) Math.ceil((plane - reach) / vws - 0.5), k1 = (long) Math.floor((plane + reach) / vws - 0.5);
                    for (long ik = k0; ik <= k1; ik++) {
                        double xk = (ik + 0.5) * vws;
                        idx[c.pAxis()] = ip; idx[c.qAxis()] = iq; idx[c.depthAxis()] = ik;
                        double x = c.depthAxis() == 0 ? xk : c.pAxis() == 0 ? xp : xq;
                        double y = c.depthAxis() == 1 ? xk : c.pAxis() == 1 ? xp : xq;
                        double z = c.depthAxis() == 2 ? xk : c.pAxis() == 2 ? xp : xq;
                        if (distanceSquaredToTriangle(x, y, z, ax, ay, az, bx, by, bz, cx, cy, cz) > h2) continue;
                        int gx = (int) (idx[0] - gmin[0]), gy = (int) (idx[1] - gmin[1]), gz = (int) (idx[2] - gmin[2]);
                        if (gx < 0 || gy < 0 || gz < 0 || gx >= g[0] || gy >= g[1] || gz >= g[2]) continue;
                        octree.insert(off[0] + gx, off[1] + gy, off[2] + gz);
                        inserted++;
                    }
                }
            }
        }
        if (inserted == 0) return null;
        org.joml.Vector3f origin = new org.joml.Vector3f(
            (float) ((gmin[0] - off[0]) * vws), (float) ((gmin[1] - off[1]) * vws), (float) ((gmin[2] - off[2]) * vws));
        return new PrimitiveVoxelizer.Result(octree, (float) vws, origin, inst);
    }

    /** Squared distance from a point to a triangle (Ericson, Real-Time Collision Detection, 5.1.5). */
    public static double distanceSquaredToTriangle(double px, double py, double pz,
                                                   double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double acx = cx - ax, acy = cy - ay, acz = cz - az;
        double apx = px - ax, apy = py - ay, apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) return apx * apx + apy * apy + apz * apz;
        double bpx = px - bx, bpy = py - by, bpz = pz - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) return bpx * bpx + bpy * bpy + bpz * bpz;
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            return sq(px - (ax + v * abx), py - (ay + v * aby), pz - (az + v * abz));
        }
        double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
        double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) return cpx * cpx + cpy * cpy + cpz * cpz;
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            return sq(px - (ax + w * acx), py - (ay + w * acy), pz - (az + w * acz));
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return sq(px - (bx + w * (cx - bx)), py - (by + w * (cy - by)), pz - (bz + w * (cz - bz)));
        }
        double denom = 1.0 / (va + vb + vc);
        double v = vb * denom, w = vc * denom;
        return sq(px - (ax + abx * v + acx * w), py - (ay + aby * v + acy * w), pz - (az + abz * v + acz * w));
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
