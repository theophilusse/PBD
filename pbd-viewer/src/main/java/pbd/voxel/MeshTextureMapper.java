package pbd.voxel;

import pbd.format.MaterialUv;
import pbd.format.PbdInstance;
import pbd.format.PbdMeshData;

/**
 * The colour of a voxel face of a shot MESH, from the texture of the mesh and the UVs of its own triangles.
 *
 * <p>A procedural primitive knows its own surface in closed form ({@link VoxelTextureMapper} runs the shader backwards); a mesh is
 * whatever triangles the file holds, and its texture coordinates are stored per vertex ({@link PbdMeshData#uvs}, two floats each).
 * What the GPU shows at a point of a triangle is the texel at the UV interpolated from its three vertices (times the material's
 * UV scale), so the voxels do the same: the corner of a face is taken to the nearest point of the mesh's surface, that point is
 * written in barycentric coordinates of the triangle it lies on, and the UV of the three corners is blended with them. A texture
 * mirrored by a negative UV scale is mirrored here too, and the texture repeats - exactly the {@link VoxelTextureMapper#texel} that
 * the primitives read.
 *
 * <p>Where two triangles are nearly as near as each other - a corner of a box-shaped mesh sits on its edge - the one the voxel face
 * LOOKS toward wins ({@link #FACING}), within {@link #TIE_VOXELS} voxels of the nearest: otherwise the top of a crate would take a
 * strip of the picture of its side.
 *
 * <p>The nearest triangle is found with a bounding-volume hierarchy built once per mapper ({@link TriangleBvh} below), so a corner far
 * inside the object (the wall of a deep crater) costs about as much as one on the skin. The positions are those the voxelizer used:
 * the vertices of the mesh times the instance scale, the grid's corner being {@link PrimitiveVoxelizer.Result#gridOriginLocal}.
 *
 * <p>Pure and single-threaded like the rest of the voxel code: nothing here is GL. Verified by RegressionTextureMapper (against a
 * brute-force search over every triangle, on a coded texture whose texels name themselves).
 */
public final class MeshTextureMapper {
    /** How far (in voxels) a triangle may be from the nearest one and still win the tie by facing the way the voxel face looks. */
    static final double TIE_VOXELS = VoxelTextureMapper.TIE_VOXELS;
    /** From this |cosine| between a triangle's normal and the way a voxel face looks, the triangle "faces" the voxel face (60 degrees). */
    static final double FACING = 0.5;
    /**
     * How far (as a share of the way to the middle of the triangle) the point whose UV is read is moved inside its triangle. A corner
     * that falls on the border of the mesh's surface reads a vertex or an edge, and a UV of exactly 1 repeats to 0 - the other end of
     * the picture. The pixel the GPU draws is always inside its triangle, so the UV is taken a hair inside too: of the two tiles that
     * meet at a border, the one the triangle lies in.
     */
    static final double INSIDE = 1e-9;
    /** The thinnest triangle that is read: twice its area over the square of its longest edge (the sine of its smallest angle, about). */
    static final double MIN_AREA_RATIO = 1e-9;

    private final double[] pos;        // the vertices, scaled to metres (3 per vertex)
    private final float[] uv;          // 2 per vertex, as stored in the mesh
    private final int[] tri;           // 3 vertex indices per usable triangle
    private final double[] normal;     // the unit normal of each usable triangle (3 each)
    private final TriangleBvh bvh;
    private final double ox, oy, oz;
    private final double voxelMetres;
    private final double tieMetres;

    private MeshTextureMapper(double[] pos, float[] uv, int[] tri, double[] normal, TriangleBvh bvh, float[] originLocal, double voxelWorldSize) {
        this.pos = pos;
        this.uv = uv;
        this.tri = tri;
        this.normal = normal;
        this.bvh = bvh;
        this.ox = originLocal[0];
        this.oy = originLocal[1];
        this.oz = originLocal[2];
        this.voxelMetres = voxelWorldSize;
        this.tieMetres = TIE_VOXELS * voxelWorldSize;
    }

    /** The mapper of a voxelized mesh, or null when the result is not a readable mesh with a UV for every vertex. */
    public static MeshTextureMapper of(PrimitiveVoxelizer.Result grid) {
        if (grid == null) return null;
        return of(grid.source, grid.gridOriginLocal, grid.voxelWorldSize);
    }

    /** The same from its parts: the voxelized mesh instance, the corner of the grid in the instance's frame and the voxel size. */
    public static MeshTextureMapper of(PbdInstance source, org.joml.Vector3f gridOriginLocal, float voxelWorldSize) {
        if (source == null || gridOriginLocal == null || !"mesh".equals(source.type)) return null;
        PbdMeshData mesh = source.meshData;
        if (!MeshVoxelizer.isUsable(mesh) || mesh.uvs == null) return null;
        int vertices = mesh.positions.length / 3;
        if (mesh.uvs.length < vertices * 2) return null;
        double[] scaled = new double[vertices * 3];
        double[] scale = {source.scale.x, source.scale.y, source.scale.z};
        for (int v = 0; v < vertices; v++) {
            for (int a = 0; a < 3; a++) scaled[v * 3 + a] = mesh.positions[v * 3 + a] * scale[a];
        }
        int whole = mesh.indices.length / 3;
        int[] kept = new int[whole * 3];
        double[] normals = new double[whole * 3];
        int count = 0;
        for (int t = 0; t < whole; t++) {
            int a = mesh.indices[t * 3], b = mesh.indices[t * 3 + 1], c = mesh.indices[t * 3 + 2];
            double ux = scaled[b * 3] - scaled[a * 3], uy = scaled[b * 3 + 1] - scaled[a * 3 + 1], uz = scaled[b * 3 + 2] - scaled[a * 3 + 2];
            double wx = scaled[c * 3] - scaled[a * 3], wy = scaled[c * 3 + 1] - scaled[a * 3 + 1], wz = scaled[c * 3 + 2] - scaled[a * 3 + 2];
            double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!isSurface(len, ux, uy, uz, wx, wy, wz)) continue;     // a line or a point: no surface to read a picture from
            kept[count * 3] = a;
            kept[count * 3 + 1] = b;
            kept[count * 3 + 2] = c;
            normals[count * 3] = nx / len;
            normals[count * 3 + 1] = ny / len;
            normals[count * 3 + 2] = nz / len;
            count++;
        }
        if (count == 0) return null;
        int[] tri = java.util.Arrays.copyOf(kept, count * 3);
        double[] normal = java.util.Arrays.copyOf(normals, count * 3);
        TriangleBvh bvh = new TriangleBvh(scaled, tri);
        return new MeshTextureMapper(scaled, mesh.uvs, tri, normal, bvh, new float[]{gridOriginLocal.x, gridOriginLocal.y, gridOriginLocal.z}, voxelWorldSize);
    }

    /**
     * What a lookup found: the triangle (its index among the usable ones), the distance to the nearest point of it, that point's
     * barycentric weights, and the UV to read - at the point moved {@link #INSIDE} toward the middle of the triangle.
     */
    public record Hit(int triangle, double distance, double b0, double b1, double b2, double u, double v) {}

    /**
     * The point of the mesh's surface a point of the grid reads its picture from, for a face looking along (nx, ny, nz) - each -1, 0 or
     * 1, all 0 for no face (a chip of debris). {@code gx, gy, gz} are metres from the grid's own corner.
     */
    public Hit hitOf(double gx, double gy, double gz, int nx, int ny, int nz) {
        double x = gx + ox, y = gy + oy, z = gz + oz;
        TriangleBvh.Candidates found = bvh.near(x, y, z, tieMetres);
        if (found.count == 0) return null;
        int pick = 0;
        for (int i = 1; i < found.count; i++) if (found.d2[i] < found.d2[pick]) pick = i;
        if (nx != 0 || ny != 0 || nz != 0) {
            // found holds the triangles within the tie of the nearest, no others
            double norm = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
            if (facing(found.tri[pick], nx / norm, ny / norm, nz / norm) < FACING) {
                int better = -1;
                for (int i = 0; i < found.count; i++) {
                    if (facing(found.tri[i], nx / norm, ny / norm, nz / norm) < FACING) continue;
                    if (better < 0 || found.d2[i] < found.d2[better]) better = i;
                }
                if (better >= 0) pick = better;
            }
        }
        int t = found.tri[pick];
        double b1 = found.b1[pick], b2 = found.b2[pick], b0 = 1.0 - b1 - b2;
        int va = tri[t * 3], vb = tri[t * 3 + 1], vc = tri[t * 3 + 2];
        double c0 = b0 + INSIDE * (1.0 / 3.0 - b0), c1 = b1 + INSIDE * (1.0 / 3.0 - b1), c2 = b2 + INSIDE * (1.0 / 3.0 - b2);
        double u = c0 * uv[va * 2] + c1 * uv[vb * 2] + c2 * uv[vc * 2];
        double v = c0 * uv[va * 2 + 1] + c1 * uv[vb * 2 + 1] + c2 * uv[vc * 2 + 1];
        return new Hit(t, Math.sqrt(found.d2[pick]), b0, b1, b2, u, v);
    }

    private double facing(int t, double lx, double ly, double lz) {
        return Math.abs(normal[t * 3] * lx + normal[t * 3 + 1] * ly + normal[t * 3 + 2] * lz);
    }

    /**
     * Whether a triangle with edges u and w from one corner, and twice the area {@code len}, is a surface: its area must be more than
     * a billionth of the square of its longest edge. Less is a line to any arithmetic (the triangles at the pole of a sphere, whose
     * three corners are the same point up to 1e-16 m), and a line has no picture to read.
     */
    static boolean isSurface(double len, double ux, double uy, double uz, double wx, double wy, double wz) {
        double longest = Math.max(ux * ux + uy * uy + uz * uz, Math.max(wx * wx + wy * wy + wz * wz,
            (ux - wx) * (ux - wx) + (uy - wy) * (uy - wy) + (uz - wz) * (uz - wz)));
        return len > MIN_AREA_RATIO * longest && len > 1e-18;
    }

    /** The number of triangles the mapper reads (the usable ones: a line or a point carries no picture). */
    public int triangleCount() {
        return tri.length / 3;
    }

    /** The same point the face corner is read at as for the primitives: {@link VoxelTextureMapper#nudgeInside}. */
    double[] insideTheFace(int[] voxel, float[] corner, int nx, int ny, int nz) {
        return VoxelTextureMapper.nudgeInside(voxel, corner, nx, ny, nz, voxelMetres);
    }

    /**
     * The colour of the corners of the faces of a voxelized mesh, from the pixels of the material's texture. {@code fallback} is the
     * colour of a corner the mapper cannot read (and of every corner when the mesh has no UVs).
     */
    public static VoxelMeshBuilder.FaceColor faceColor(PrimitiveVoxelizer.Result grid, byte[] rgba, int width, int height,
                                                       MaterialUv.Scale scale, float[] fallback) {
        MeshTextureMapper mapper = of(grid);
        if (mapper == null || rgba == null || width <= 0 || height <= 0 || rgba.length < width * height * 4) {
            return VoxelMeshBuilder.ignoringFace((voxel, corner) -> fallback);
        }
        return (voxel, corner, nx, ny, nz) -> {
            int fx = Math.round(nx), fy = Math.round(ny), fz = Math.round(nz);
            double[] where = mapper.insideTheFace(voxel, corner, fx, fy, fz);
            Hit hit = mapper.hitOf(where[0], where[1], where[2], fx, fy, fz);
            return hit == null ? fallback : VoxelTextureMapper.texel(rgba, width, height, hit.u(), hit.v(), scale);
        };
    }

    // ------------------------------------------------------------------
    // The nearest triangles
    // ------------------------------------------------------------------

    /**
     * A bounding-volume hierarchy over triangles (median split of the longest side of the centroids, a few triangles per leaf), asked
     * for the triangles nearest a point: the nearest, and the others within a window of it. Nodes are visited nearest first and a node
     * farther than the window is never entered, so the cost is that of the neighbourhood of the answer, whatever the distance.
     */
    static final class TriangleBvh {
        static final int LEAF = 4;
        static final int CAPACITY = 16;

        private final double[] pos;
        private final int[] tri;
        private final int[] order;
        private final double[] box;       // 6 per node: min xyz, max xyz
        private final int[] left, right;  // children, or -1 for a leaf
        private final int[] first, count; // a leaf's triangles in `order`
        private int nodes;

        /** The triangles found near a point: their indices, squared distances and the barycentric weights (b1, b2) of the nearest point on each. */
        static final class Candidates {
            final int[] tri = new int[CAPACITY];
            final double[] d2 = new double[CAPACITY];
            final double[] b1 = new double[CAPACITY];
            final double[] b2 = new double[CAPACITY];
            int count;
        }

        TriangleBvh(double[] pos, int[] tri) {
            this.pos = pos;
            this.tri = tri;
            int n = tri.length / 3;
            this.order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
            int max = 4 * (n / LEAF + 1) + 4;
            box = new double[max * 6];
            left = new int[max];
            right = new int[max];
            first = new int[max];
            count = new int[max];
            double[] centroid = new double[n * 3];
            for (int t = 0; t < n; t++) {
                for (int a = 0; a < 3; a++) {
                    centroid[t * 3 + a] = (pos[tri[t * 3] * 3 + a] + pos[tri[t * 3 + 1] * 3 + a] + pos[tri[t * 3 + 2] * 3 + a]) / 3.0;
                }
            }
            build(0, n, centroid);
        }

        private int build(int from, int to, double[] centroid) {
            int node = nodes++;
            double[] b = box;
            int o = node * 6;
            b[o] = b[o + 1] = b[o + 2] = Double.POSITIVE_INFINITY;
            b[o + 3] = b[o + 4] = b[o + 5] = Double.NEGATIVE_INFINITY;
            double[] clo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] chi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int i = from; i < to; i++) {
                int t = order[i];
                for (int k = 0; k < 3; k++) {
                    int v = tri[t * 3 + k];
                    for (int a = 0; a < 3; a++) {
                        double p = pos[v * 3 + a];
                        if (p < b[o + a]) b[o + a] = p;
                        if (p > b[o + 3 + a]) b[o + 3 + a] = p;
                    }
                }
                for (int a = 0; a < 3; a++) {
                    double c = centroid[t * 3 + a];
                    if (c < clo[a]) clo[a] = c;
                    if (c > chi[a]) chi[a] = c;
                }
            }
            int size = to - from;
            int axis = 0;
            for (int a = 1; a < 3; a++) if (chi[a] - clo[a] > chi[axis] - clo[axis]) axis = a;
            if (size <= LEAF || !(chi[axis] - clo[axis] > 0)) {
                // a leaf (or triangles that all share one centroid: nothing to split them by)
                left[node] = -1;
                right[node] = -1;
                first[node] = from;
                count[node] = size;
                return node;
            }
            int middle = from + size / 2;
            select(from, to - 1, middle, axis, centroid);
            int l = build(from, middle, centroid);
            int r = build(middle, to, centroid);
            left[node] = l;
            right[node] = r;
            return node;
        }

        /** Quickselect: puts the triangle that belongs at {@code k} in sorted order of the centroid along {@code axis} there, the smaller ones before it. */
        private void select(int lo, int hi, int k, int axis, double[] centroid) {
            while (lo < hi) {
                double pivot = centroid[order[(lo + hi) >>> 1] * 3 + axis];
                int i = lo, j = hi;
                while (i <= j) {
                    while (centroid[order[i] * 3 + axis] < pivot) i++;
                    while (centroid[order[j] * 3 + axis] > pivot) j--;
                    if (i <= j) {
                        int tmp = order[i]; order[i] = order[j]; order[j] = tmp;
                        i++; j--;
                    }
                }
                if (k <= j) hi = j;
                else if (k >= i) lo = i;
                else return;
            }
        }

        /**
         * The triangles within {@code window} metres of the nearest one to (x, y, z) (at most {@link #CAPACITY}, the nearest of them when
         * more are there), with the squared distance to each and the barycentric weights of the nearest point on it.
         */
        Candidates near(double x, double y, double z, double window) {
            Candidates out = new Candidates();
            int[] stack = new int[128];
            double[] stackDist = new double[128];
            int top = 0;
            stack[top] = 0;
            stackDist[top] = boxDistanceSquared(0, x, y, z);
            top++;
            double best = Double.POSITIVE_INFINITY;           // squared distance of the nearest triangle so far
            double[] result = new double[3];
            while (top > 0) {
                top--;
                int node = stack[top];
                double reach = Double.isInfinite(best) ? Double.POSITIVE_INFINITY : Math.sqrt(best) + window;
                if (stackDist[top] > reach * reach) continue;
                if (left[node] < 0) {
                    for (int i = first[node]; i < first[node] + count[node]; i++) {
                        int t = order[i];
                        int a = tri[t * 3] * 3, b = tri[t * 3 + 1] * 3, c = tri[t * 3 + 2] * 3;
                        closest(x, y, z, pos[a], pos[a + 1], pos[a + 2], pos[b], pos[b + 1], pos[b + 2], pos[c], pos[c + 1], pos[c + 2], result);
                        double d2 = result[0];
                        double limit = Double.isInfinite(best) ? Double.POSITIVE_INFINITY : Math.sqrt(best) + window;
                        if (d2 > limit * limit) continue;
                        if (d2 < best) best = d2;
                        offer(out, t, d2, result[1], result[2]);
                    }
                } else {
                    int l = left[node], r = right[node];
                    double dl = boxDistanceSquared(l, x, y, z), dr = boxDistanceSquared(r, x, y, z);
                    // the nearer child is entered first: it is pushed last
                    if (dl <= dr) {
                        stack[top] = r; stackDist[top++] = dr;
                        stack[top] = l; stackDist[top++] = dl;
                    } else {
                        stack[top] = l; stackDist[top++] = dl;
                        stack[top] = r; stackDist[top++] = dr;
                    }
                }
            }
            // what was kept while the nearest was still far may be outside the window of the final nearest
            double reach = Math.sqrt(best) + window;
            int kept = 0;
            for (int i = 0; i < out.count; i++) {
                if (out.d2[i] > reach * reach) continue;
                out.tri[kept] = out.tri[i];
                out.d2[kept] = out.d2[i];
                out.b1[kept] = out.b1[i];
                out.b2[kept] = out.b2[i];
                kept++;
            }
            out.count = kept;
            return out;
        }

        private static void offer(Candidates out, int t, double d2, double b1, double b2) {
            int at;
            if (out.count < CAPACITY) {
                at = out.count++;
            } else {
                at = 0;
                for (int i = 1; i < CAPACITY; i++) if (out.d2[i] > out.d2[at]) at = i;
                if (out.d2[at] <= d2) return;      // all of them are nearer
            }
            out.tri[at] = t;
            out.d2[at] = d2;
            out.b1[at] = b1;
            out.b2[at] = b2;
        }

        private double boxDistanceSquared(int node, double x, double y, double z) {
            int o = node * 6;
            double dx = Math.max(Math.max(box[o] - x, x - box[o + 3]), 0.0);
            double dy = Math.max(Math.max(box[o + 1] - y, y - box[o + 4]), 0.0);
            double dz = Math.max(Math.max(box[o + 2] - z, z - box[o + 5]), 0.0);
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * The point of the triangle nearest (px, py, pz) (Ericson, Real-Time Collision Detection, 5.1.5): out[0] = the squared distance to
         * it, out[1], out[2] = the barycentric weights of the corners b and c (the weight of a is the rest).
         */
        static void closest(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz,
                            double cx, double cy, double cz, double[] out) {
            double abx = bx - ax, aby = by - ay, abz = bz - az;
            double acx = cx - ax, acy = cy - ay, acz = cz - az;
            double apx = px - ax, apy = py - ay, apz = pz - az;
            double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
            double v, w;
            if (d1 <= 0 && d2 <= 0) {
                v = 0; w = 0;
            } else {
                double bpx = px - bx, bpy = py - by, bpz = pz - bz;
                double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
                if (d3 >= 0 && d4 <= d3) {
                    v = 1; w = 0;
                } else {
                    double vc = d1 * d4 - d3 * d2;
                    if (vc <= 0 && d1 >= 0 && d3 <= 0) {
                        v = d1 / (d1 - d3); w = 0;
                    } else {
                        double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
                        double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
                        if (d6 >= 0 && d5 <= d6) {
                            v = 0; w = 1;
                        } else {
                            double vb = d5 * d2 - d1 * d6;
                            if (vb <= 0 && d2 >= 0 && d6 <= 0) {
                                v = 0; w = d2 / (d2 - d6);
                            } else {
                                double va = d3 * d6 - d5 * d4;
                                if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                                    w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                                    v = 1.0 - w;
                                } else {
                                    double sum = va + vb + vc;
                                    if (sum != 0.0 && Double.isFinite(sum)) {
                                        double denom = 1.0 / sum;
                                        v = vb * denom; w = vc * denom;
                                    } else {
                                        v = 0; w = 0;     // a triangle with no area is a point to this arithmetic
                                    }
                                }
                            }
                        }
                    }
                }
            }
            double qx = ax + abx * v + acx * w, qy = ay + aby * v + acy * w, qz = az + abz * v + acz * w;
            out[0] = (px - qx) * (px - qx) + (py - qy) * (py - qy) + (pz - qz) * (pz - qz);
            out[1] = v;
            out[2] = w;
        }
    }
}
