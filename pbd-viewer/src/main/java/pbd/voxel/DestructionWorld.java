package pbd.voxel;

import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import pbd.format.PbdInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Everything one G-key hit does, minus the GPU: which thing the ray hits,
 * the crater it carves, what that leaves attached, what breaks off and
 * falls, and the physics of the falling pieces. PbdRenderer is only the
 * GL adapter around this - it supplies a {@link Host} that builds and
 * frees meshes and says how instances are placed in the world right now -
 * which is what lets the whole chain (including a rotated door, a piece
 * landing on a table, a second shot at a piece lying on the floor) run
 * under tools/regression without a window or a GPU.
 *
 * THE FLOW of {@link #destroyAt}. Whatever the ray reaches FIRST is hit:
 * <ol>
 *   <li>a never-destroyed instance (the caller finds it with the engine's
 *       existing bounding-box ray test and passes it in as {@link Fresh};
 *       here the exact impact point is refined against its real voxels),</li>
 *   <li>what is left of an already-destroyed instance ("remains"),</li>
 *   <li>a piece that already broke off ("chunk").</li>
 * </ol>
 * The last two are tested against their actual voxels, so a shot through a
 * hole in a door carries on to whatever stands behind it. A hit carves a
 * crater (VoxelCrater); the cubes it leaves are split into face-connected
 * pieces (VoxelDebris.split): pieces still attached stay as the instance's
 * voxel mesh, the rest become rigid bodies (RigidDebris) that fall under
 * {@link #update}, landing on the scene's floor, on the remains, and on the
 * boxes of nearby cubes and cylinders.
 *
 * NOTHING IS CACHED BETWEEN HITS beyond the live state itself: the voxel
 * grid is rebuilt from the instance on every first hit, the scene's floor
 * and boxes are re-derived whenever a piece needs them, so assets loaded
 * or replaced on the fly are always seen as they are now.
 *
 * KNOWN LIMITS (recorded in docs/ROADMAP.md): "what stays attached" is a
 * heuristic (the format has no support/hinge data); pieces do not collide
 * with each other; a piece does not carry an animated instance's own
 * motion; sphere/cone/torus/mesh instances are not landing surfaces.
 */
public final class DestructionWorld<M> {

    /** Edge length of one destruction voxel, in world units (5 mm). */
    public static final float VOXEL_WORLD_SIZE = 0.005f;

    /**
     * THE single knob for "how much" one hit destroys: the nominal radius of
     * the crater in voxels (30 voxels = 0.15 world units, a hole about 0.30
     * across, digging up to half again as deep along the line of fire). The
     * crater is a SOLID irregular volume (VoxelCrater), so this really sets
     * how much material goes - the previous shape was 40 one-voxel-wide
     * tunnels, ~500 voxels whatever the radius, which could never cut
     * anything in two.
     *
     * One hit at 30, straight at the middle (tools/regression/VoxelFallDemo):
     * a 0.1 x 1.0 x 0.1 post and a 0.05 x 1.0 x 0.25 plank are cut in two
     * and the top piece falls; a 0.04 x 1.0 x 0.45 door panel gets a big
     * round hole but stays in one piece (wider than the crater - it needs a
     * second hit near its edge); a 0.4 cube loses ~16% of its volume. Rule
     * of thumb: one hit severs anything up to about 2 x radius across.
     *
     * Cost (measured on the JVM, one hit): about 25-60 ms to carve plus
     * about 50 ms to mesh at 30; at 60 about three times that. No merged
     * region is ever expanded into unit voxels, so memory stays small.
     */
    public static final float CRATER_RADIUS_VOXELS = 30f;

    /** Most debris chunks alive at once; beyond this the oldest is dropped (its mesh freed). */
    public static final int MAX_LIVE_CHUNKS = 24;
    /** Longest physics step fed to the debris - a frame hitch must not become one giant step. */
    static final double MAX_DEBRIS_DT = 1.0 / 20.0;
    /** How far (world units) from a chunk another instance's box still counts as something it can land on. */
    static final double COLLIDER_REACH = 3.0;
    /** A collider a piece starts out embedded in by more than this (world units) is ignored for that piece. */
    static final double EMBEDDED_TOLERANCE = 0.01;
    /** A hit wakes (and re-evaluates the supports of) resting chunks within this distance of where it landed. */
    static final double WAKE_RADIUS = 2.0;

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    /** How the faces of one destroyed instance are coloured - shared by its remains and every chunk that breaks off it, so the texture runs on across a break. */
    public static final class Style {
        public final BiFunction<int[], float[], float[]> colorFn;
        public final float[] fallback;

        public Style(BiFunction<int[], float[], float[]> colorFn, float[] fallback) {
            this.colorFn = colorFn;
            this.fallback = fallback;
        }

        public static Style flat(float[] color) {
            return new Style((voxel, worldPos) -> color, color);
        }
    }

    /** What the world needs from its surroundings; PbdRenderer implements it over GL, tests over plain objects. */
    public interface Host<M> {
        /** The current live world transform of every instance (animation applied, NOT zeroed for hidden ones). */
        Matrix4f[] liveTransforms();

        boolean isHidden(int index);

        /** Stops drawing the original instance - voxel geometry replaces it. */
        void hideInstance(int index);

        /** How this instance's voxel faces are coloured. voxelized is a copy carrying the world scale the grid was built at. */
        Style styleFor(PbdInstance original, PbdInstance voxelized, int gridSize, float voxelWorldSize);

        /** A drawable mesh for these cubes (mesh space = grid coordinates times voxelWorldSize), or null if it cannot be built. */
        M buildMesh(List<int[]> entries, float voxelWorldSize, Style style);

        /** Frees whatever buildMesh made. */
        void closeMesh(M mesh);
    }

    /** The never-destroyed instance the bounding-box ray test ({@link #findFresh}) found: its index, the world distance to the box, and the hit point in the instance's own local space (the canonical -0.5..0.5 box for a primitive, the mesh's own units for a mesh). */
    public record Fresh(int index, double distance, Vector3f localHit) {}

    /** hit: whether anything was destroyed or further carved (the cue for the destruction SFX). message: a ready-to-print log line. */
    public record Outcome(boolean hit, String message) {}

    /** What is left of one destroyed instance: the cubes still ATTACHED to it, where its grid sits, and what a later hit needs to decide what stays. */
    public static final class Remains<M> {
        public final int instanceIndex;
        /** Grid corner relative to the instance centre, in the (world-)scaled local frame. */
        public final Vector3f gridOrigin;
        public final float voxelWorldSize;
        final Style style;
        final boolean grounded;
        final double downX, downY, downZ;
        final double bottomProjection;
        private M mesh;
        private List<int[]> entries;
        private VoxelSolid solid;

        Remains(int instanceIndex, Vector3f gridOrigin, float voxelWorldSize, Style style, boolean grounded,
                double downX, double downY, double downZ, double bottomProjection, List<int[]> entries) {
            this.instanceIndex = instanceIndex;
            this.gridOrigin = gridOrigin;
            this.voxelWorldSize = voxelWorldSize;
            this.style = style;
            this.grounded = grounded;
            this.downX = downX; this.downY = downY; this.downZ = downZ;
            this.bottomProjection = bottomProjection;
            this.entries = entries;
        }

        public M mesh() { return mesh; }
        public List<int[]> entries() { return entries; }
        public boolean grounded() { return grounded; }

        public VoxelSolid solid() {
            if (solid == null) solid = new VoxelSolid(entries);
            return solid;
        }

        void set(List<int[]> newEntries, M newMesh) {
            entries = newEntries;
            solid = null;
            mesh = newMesh;
        }
    }

    /** One detached piece: a rigid body drawn from its own mesh (draw with T(position) * R(orientation) * T(-comMesh)). */
    public static final class Chunk<M> {
        public final List<int[]> entries;
        public final VoxelSolid solid;
        public final RigidDebris body;
        public final M mesh;
        public final float voxelWorldSize;
        final Style style;
        /** Static things it can land on; null = (re)build before the next physics step. */
        List<RigidDebris.Collider> colliders;

        Chunk(List<int[]> entries, RigidDebris body, M mesh, float voxelWorldSize, Style style) {
            this.entries = entries;
            this.solid = new VoxelSolid(entries);
            this.body = body;
            this.mesh = mesh;
            this.voxelWorldSize = voxelWorldSize;
            this.style = style;
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final List<PbdInstance> instances;
    private final Host<M> host;
    private final Map<Integer, Remains<M>> remains = new HashMap<>();
    private final List<Chunk<M>> chunks = new ArrayList<>();
    private int serial; // counts hits; seeds each piece's tumble so a replay is identical

    public DestructionWorld(List<PbdInstance> instances, Host<M> host) {
        this.instances = instances;
        this.host = host;
    }

    public Collection<Remains<M>> remains() { return Collections.unmodifiableCollection(remains.values()); }
    public List<Chunk<M>> chunks() { return Collections.unmodifiableList(chunks); }
    public boolean isEmpty() { return remains.isEmpty() && chunks.isEmpty(); }

    /** Frees every mesh and forgets everything (scene switch / shutdown). */
    public void clear() {
        for (Remains<M> r : remains.values()) if (r.mesh != null) host.closeMesh(r.mesh);
        remains.clear();
        for (Chunk<M> c : chunks) host.closeMesh(c.mesh);
        chunks.clear();
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    /** Where a voxel grid sits in the world: world = origin + rotation * (grid * voxelWorldSize). */
    private record GridFrame(Vector3d origin, Quaterniond rotation) {}

    /** A ray's exact hit on voxel geometry, in that geometry's own grid frame (impact is half a voxel inside the surface). */
    private record GridHit(double distance, Vector3d impactGrid, Vector3d directionGrid) {}

    /**
     * Rotation of a world matrix with the scale divided out. Same as
     * PbdRenderer's own helper (JOML's getNormalizedRotation() returns
     * something that is not even a unit quaternion for strongly
     * non-uniform scale - confirmed on a real 27:1 door).
     */
    public static Quaternionf rotationOf(Matrix4f m) {
        Vector3f colX = new Vector3f(m.m00(), m.m01(), m.m02()).normalize();
        Vector3f colY = new Vector3f(m.m10(), m.m11(), m.m12()).normalize();
        Vector3f colZ = new Vector3f(m.m20(), m.m21(), m.m22()).normalize();
        Matrix4f rotOnly = new Matrix4f(
            colX.x, colX.y, colX.z, 0,
            colY.x, colY.y, colY.z, 0,
            colZ.x, colZ.y, colZ.z, 0,
            0, 0, 0, 1);
        return rotOnly.getNormalizedRotation(new Quaternionf());
    }

    private static GridFrame gridFrame(Matrix4f liveWorld, Vector3f gridOrigin) {
        Quaternionf q = rotationOf(liveWorld);
        Quaterniond rotation = new Quaterniond(q.x, q.y, q.z, q.w);
        Vector3d offset = new Vector3d(gridOrigin.x, gridOrigin.y, gridOrigin.z);
        rotation.transform(offset);
        Vector3f t = liveWorld.getTranslation(new Vector3f());
        return new GridFrame(new Vector3d(t.x, t.y, t.z).add(offset), rotation);
    }

    private static Vector3d worldPointToGrid(GridFrame frame, double voxelWorldSize, Vector3d worldPoint) {
        Vector3d g = new Vector3d(worldPoint).sub(frame.origin());
        frame.rotation().transformInverse(g);
        return g.div(voxelWorldSize);
    }

    private static Vector3d worldDirToGrid(GridFrame frame, Vector3d worldDir) {
        Vector3d g = new Vector3d(worldDir);
        frame.rotation().transformInverse(g);
        return g;
    }

    private static Vector3d gridPointToWorld(GridFrame frame, double voxelWorldSize, Vector3d gridPoint) {
        Vector3d w = new Vector3d(gridPoint).mul(voxelWorldSize);
        frame.rotation().transform(w);
        return w.add(frame.origin());
    }

    /** World-space bounds {minX,minY,minZ,maxX,maxY,maxZ} of a voxel solid's bounding box placed by frame. */
    private static double[] worldBounds(GridFrame frame, double voxelWorldSize, VoxelSolid solid) {
        double[] r = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        Vector3d v = new Vector3d();
        for (int ix = 0; ix < 2; ix++) for (int iy = 0; iy < 2; iy++) for (int iz = 0; iz < 2; iz++) {
            v.set((ix == 0 ? solid.minX : solid.maxX) * voxelWorldSize,
                  (iy == 0 ? solid.minY : solid.maxY) * voxelWorldSize,
                  (iz == 0 ? solid.minZ : solid.maxZ) * voxelWorldSize);
            frame.rotation().transform(v).add(frame.origin());
            r[0] = Math.min(r[0], v.x); r[1] = Math.min(r[1], v.y); r[2] = Math.min(r[2], v.z);
            r[3] = Math.max(r[3], v.x); r[4] = Math.max(r[4], v.y); r[5] = Math.max(r[5], v.z);
        }
        return r;
    }

    /**
     * A copy of inst whose scale is the instance's actual WORLD scale (a
     * parent's scale included) - what the voxelizer has to use, because the
     * voxel mesh is placed with rotation and translation only. Equal to
     * inst.scale for a top-level instance. Null for a degenerate (zero or
     * NaN) scale.
     */
    static PbdInstance withWorldScale(PbdInstance inst, Matrix4f world) {
        float sx = new Vector3f(world.m00(), world.m01(), world.m02()).length();
        float sy = new Vector3f(world.m10(), world.m11(), world.m12()).length();
        float sz = new Vector3f(world.m20(), world.m21(), world.m22()).length();
        if (!(sx > 1e-6f && sy > 1e-6f && sz > 1e-6f)) return null;
        PbdInstance copy = new PbdInstance(inst.id, inst.type);
        copy.scale.set(sx, sy, sz);
        copy.modifiers.addAll(inst.modifiers);
        copy.params.putAll(inst.params);
        copy.meshData = inst.meshData;
        copy.indestructible = inst.indestructible;
        copy.material = inst.material;
        return copy;
    }

    private static long volumeOf(List<int[]> entries) {
        long v = 0;
        for (int[] e : entries) {
            long s = e.length > 3 ? e[3] : 1;
            v += s * s * s;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // What the scene looks like to falling debris
    // ------------------------------------------------------------------

    /** One instance as something solid a piece can land on: an oriented box plus its world bounds. */
    private static final class SceneBox {
        final int index;
        final Vector3d centre;
        final Quaterniond rotation;
        final Vector3d half;
        final double minX, minY, minZ, maxX, maxY, maxZ;

        private SceneBox(int index, Vector3d centre, Quaterniond rotation, Vector3d half) {
            this.index = index;
            this.centre = centre;
            this.rotation = rotation;
            this.half = half;
            double lx = Double.POSITIVE_INFINITY, ly = lx, lz = lx, hx = Double.NEGATIVE_INFINITY, hy = hx, hz = hx;
            Vector3d v = new Vector3d();
            for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) for (int sz = -1; sz <= 1; sz += 2) {
                v.set(sx * half.x, sy * half.y, sz * half.z);
                rotation.transform(v).add(centre);
                lx = Math.min(lx, v.x); ly = Math.min(ly, v.y); lz = Math.min(lz, v.z);
                hx = Math.max(hx, v.x); hy = Math.max(hy, v.y); hz = Math.max(hz, v.z);
            }
            minX = lx; minY = ly; minZ = lz; maxX = hx; maxY = hy; maxZ = hz;
        }

        /** Null for a degenerate (zero-size or NaN) matrix. */
        static SceneBox of(int index, Matrix4f m) {
            float sx = new Vector3f(m.m00(), m.m01(), m.m02()).length();
            float sy = new Vector3f(m.m10(), m.m11(), m.m12()).length();
            float sz = new Vector3f(m.m20(), m.m21(), m.m22()).length();
            if (!(sx > 1e-6f && sy > 1e-6f && sz > 1e-6f)) return null;
            Quaternionf q = rotationOf(m);
            Vector3f t = m.getTranslation(new Vector3f());
            return new SceneBox(index, new Vector3d(t.x, t.y, t.z), new Quaterniond(q.x, q.y, q.z, q.w),
                new Vector3d(sx * 0.5, sy * 0.5, sz * 0.5));
        }

        boolean within(Vector3d p, double reach) {
            double dx = Math.max(Math.max(minX - p.x, 0), p.x - maxX);
            double dy = Math.max(Math.max(minY - p.y, 0), p.y - maxY);
            double dz = Math.max(Math.max(minZ - p.z, 0), p.z - maxZ);
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        }
    }

    /** The scene as debris sees it at one moment: boxes to land on, and the floor level. */
    private static final class SceneSnapshot {
        final List<SceneBox> boxes = new ArrayList<>();
        double floorY;
    }

    /**
     * Whether instance i can be something a piece lands on: a visible, real
     * volume - a cube or a cylinder (their canonical box is a fair stand-in;
     * a sphere's or a cone's box is not, so those, planes/discs, meshes and
     * invisible helper volumes are ignored: a piece falls through them).
     */
    private boolean isBoxCollider(int i) {
        if (host.isHidden(i)) return false;
        PbdInstance inst = instances.get(i);
        if ("true".equals(inst.params.get("metadata"))) return false;
        return "cube".equals(inst.type) || "cylinder".equals(inst.type);
    }

    /**
     * Boxes + floor from the given live transforms. Built fresh each time
     * (never kept between frames: the scene can change under it). The floor
     * is the lowest solid thing in the scene - the scene format has no floor
     * of its own.
     */
    private SceneSnapshot snapshotScene(Matrix4f[] live) {
        SceneSnapshot snap = new SceneSnapshot();
        double floor = Double.POSITIVE_INFINITY;
        for (int i = 0; i < instances.size() && i < live.length; i++) {
            if (!isBoxCollider(i)) continue;
            SceneBox box = SceneBox.of(i, live[i]);
            if (box == null) continue;
            snap.boxes.add(box);
            floor = Math.min(floor, box.minY);
        }
        // What is left of destroyed instances is solid too (and hidden, so not a box above) - it may be the lowest thing there is.
        for (Remains<M> r : remains.values()) {
            double[] wb = worldBounds(gridFrame(live[r.instanceIndex], r.gridOrigin), r.voxelWorldSize, r.solid());
            floor = Math.min(floor, wb[1]);
        }
        snap.floorY = Double.isFinite(floor) ? floor : 0.0;
        return snap;
    }

    /** Whether something solid sits right under an object whose lowest point is at lowestY and whose footprint is the given x/z range: the floor level, or the top of another instance's box (a table, a floor slab). */
    private static boolean restsOnSomething(SceneSnapshot snap, int selfIndex, double lowestY,
                                            double minX, double maxX, double minZ, double maxZ) {
        double tol = VoxelDebris.GROUNDED_TOLERANCE;
        if (lowestY <= snap.floorY + tol) return true;
        for (SceneBox b : snap.boxes) {
            if (b.index == selfIndex) continue;
            if (Math.abs(b.maxY - lowestY) > tol) continue;
            if (b.maxX < minX || b.minX > maxX || b.maxZ < minZ || b.minZ > maxZ) continue;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The hit
    // ------------------------------------------------------------------

    /**
     * The box an instance fills in its OWN local space, {minX, minY, minZ, maxX, maxY, maxZ}: the
     * canonical -0.5..0.5 cube for every primitive (its world matrix carries position, rotation
     * and scale), but for a "mesh" instance the bounds of its own vertices - a mesh's coordinates
     * are not normalised, so testing the canonical box for one would miss geometry that sits off
     * its origin and "hit" empty space beside it.
     */
    public static float[] localBox(PbdInstance inst) {
        if ("mesh".equals(inst.type) && inst.meshData != null) {
            float[] b = inst.meshData.bounds();
            if (b != null) return b;
        }
        return new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
    }

    /** Slab test of a ray (in the box's own space) against box = {min xyz, max xyz}: the ray parameter of the first touch (0 when the origin is inside), -1 for a miss - NaN included, which only a degenerate matrix produces and must never look like a hit. */
    static float rayBox(Vector3f origin, Vector3f dir, float[] box) {
        float tMin = Float.NEGATIVE_INFINITY, tMax = Float.POSITIVE_INFINITY;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {dir.x, dir.y, dir.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Float.isNaN(o[axis]) || Float.isNaN(d[axis])) return -1;
            if (Math.abs(d[axis]) < 1e-8f) {
                if (o[axis] < box[axis] || o[axis] > box[3 + axis]) return -1; // parallel to this axis's faces and outside the slab
                continue;
            }
            float t1 = (box[axis] - o[axis]) / d[axis];
            float t2 = (box[3 + axis] - o[axis]) / d[axis];
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        if (tMax < 0) return -1; // the box is entirely behind the origin
        float result = Math.max(tMin, 0f);
        return Float.isNaN(result) ? -1 : result;
    }

    /**
     * The broad phase of a shot: the nearest never-destroyed instance whose bounding box the ray
     * crosses, as the {@link Fresh} candidate {@link #destroyAt} takes (the exact impact against
     * the instance's real voxels is found there), or null if the ray touches none.
     *
     * Skipped, each with its reason logged: an indestructible instance; an instance with no
     * geometry of its own (ref, group, light); an invisible metadata volume; an instance already
     * destroyed (what is left of it is hit through its voxels, see destroyAt - its original box
     * means nothing any more). The box is {@link #localBox}. Modifiers (bend, shear, ...) are not
     * accounted for: the box is the unmodified one (ROADMAP backlog item 12).
     *
     * log may be null (silent); the game passes System.out. This per-instance trace is what
     * finally separated "the ray never reaches a geometry test" from "the geometry test missed"
     * from "it hit but nothing happened" in the G-finds-nothing reports, so keep its wording.
     */
    public Fresh findFresh(Vector3d rayOrigin, Vector3d rayDir, Consumer<String> log) {
        Consumer<String> say = log != null ? log : s -> { };
        Vector3f origin = new Vector3f((float) rayOrigin.x, (float) rayOrigin.y, (float) rayOrigin.z);
        Vector3f dir = new Vector3f((float) rayDir.x, (float) rayDir.y, (float) rayDir.z);
        Matrix4f[] live = host.liveTransforms();
        say.accept("[G-key] ray origin=" + origin + " dir=" + dir + " checking " + instances.size() + " instance(s)");
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        Vector3f bestLocal = null;
        for (int i = 0; i < instances.size() && i < live.length; i++) {
            PbdInstance inst = instances.get(i);
            if (inst.indestructible) {
                say.accept("  #" + i + " '" + inst.id + "' SKIPPED (indestructible=true)");
                continue;
            }
            if ("ref".equals(inst.type) || "group".equals(inst.type) || "light".equals(inst.type)) {
                say.accept("  #" + i + " '" + inst.id + "' SKIPPED (type=" + inst.type + ", no geometry of its own to hit)");
                continue;
            }
            if ("true".equals(inst.params.get("metadata"))) {
                say.accept("  #" + i + " '" + inst.id + "' SKIPPED (metadata=true, an invisible bin-packing volume)");
                continue;
            }
            if (host.isHidden(i)) {
                say.accept("  #" + i + " '" + inst.id + "' SKIPPED (already destroyed - see the retarget/re-hit path instead)");
                continue;
            }
            Matrix4f inverse = new Matrix4f(live[i]).invert();
            Vector3f localOrigin = inverse.transformPosition(new Vector3f(origin));
            Vector3f localDir = inverse.transformDirection(new Vector3f(dir));
            float t = rayBox(localOrigin, localDir, localBox(inst));
            if (t < 0) {
                say.accept("  #" + i + " '" + inst.id + "' type=" + inst.type
                    + " world-pos=" + live[i].getTranslation(new Vector3f())
                    + " TESTED-MISSED (local ray-box test found no hit)");
                continue;
            }
            Vector3f localHit = new Vector3f(localDir).mul(t).add(localOrigin);
            double worldDist = live[i].transformPosition(new Vector3f(localHit)).distance(origin);
            say.accept("  #" + i + " '" + inst.id + "' type=" + inst.type + " HIT at world-dist=" + worldDist);
            if (worldDist < bestDist) { // a NaN distance can never win this comparison
                bestDist = worldDist;
                best = i;
                bestLocal = localHit;
            }
        }
        say.accept("[G-key] closest hit: " + (best < 0 ? "NONE" : "#" + best + " '" + instances.get(best).id + "'"));
        return best < 0 ? null : new Fresh(best, bestDist, bestLocal);
    }

    /**
     * Fires a ray (world space, any length direction) and applies the hit.
     * See the class doc for what is hit and what happens. fresh may be null
     * (no never-destroyed instance under the ray).
     */
    public Outcome destroyAt(Vector3d rayOrigin, Vector3d rayDir, Fresh fresh) {
        Vector3d origin = new Vector3d(rayOrigin);
        Vector3d dir = new Vector3d(rayDir);
        if (!(dir.lengthSquared() > 1e-12)) return new Outcome(false, "[Destroy] No aiming direction");
        dir.normalize();

        double best = fresh != null ? fresh.distance() : Double.POSITIVE_INFINITY;

        // Remains of destroyed instances, by their real voxels.
        Remains<M> hitRemains = null;
        GridHit remainsHit = null;
        GridFrame remainsFrame = null;
        if (!remains.isEmpty()) {
            Matrix4f[] live = host.liveTransforms();
            for (Remains<M> r : remains.values()) {
                GridFrame frame = gridFrame(live[r.instanceIndex], r.gridOrigin);
                GridHit hit = rayAgainstGrid(r.solid(), r.voxelWorldSize,
                    worldPointToGrid(frame, r.voxelWorldSize, origin), worldDirToGrid(frame, dir));
                if (hit != null && hit.distance() < best) {
                    best = hit.distance();
                    hitRemains = r;
                    remainsHit = hit;
                    remainsFrame = frame;
                }
            }
        }

        // Pieces that already broke off.
        Chunk<M> hitChunk = null;
        GridHit chunkHit = null;
        for (Chunk<M> c : chunks) {
            RigidDebris b = c.body;
            Vector3d gridOrigin = b.worldToBody(origin).add(b.comMesh).div(c.voxelWorldSize);
            Vector3d gridDir = new Vector3d(dir);
            b.orientation.transformInverse(gridDir);
            GridHit hit = rayAgainstGrid(c.solid, c.voxelWorldSize, gridOrigin, gridDir);
            if (hit != null && hit.distance() < best) {
                best = hit.distance();
                hitChunk = c;
                chunkHit = hit;
                hitRemains = null;
                remainsHit = null;
            }
        }

        if (hitChunk != null) return hitChunk(hitChunk, chunkHit, dir);
        if (hitRemains != null) return hitRemains(hitRemains, remainsFrame, remainsHit, dir);
        if (fresh != null) return hitFresh(fresh, origin, dir);
        return new Outcome(false, "[Destroy] Nothing destructible under the crosshair");
    }

    /** First solid voxel along a ray given in grid space (unit direction), or null. The impact is placed half a voxel INSIDE the surface so the crater is centred on the material, not on the air in front of it. */
    private static GridHit rayAgainstGrid(VoxelSolid solid, double voxelWorldSize, Vector3d gridOrigin, Vector3d gridDir) {
        double t = solid.firstSolidAlongRay(gridOrigin.x, gridOrigin.y, gridOrigin.z, gridDir.x, gridDir.y, gridDir.z, 1e9);
        if (t < 0) return null;
        return new GridHit(t * voxelWorldSize, new Vector3d(gridOrigin).fma(t + 0.5, gridDir), new Vector3d(gridDir));
    }

    private static VoxelCrater.Result carveCrater(List<int[]> entries, Vector3d impactGrid, Vector3d directionGrid) {
        VoxelCrater.Shape shape = VoxelCrater.create(
            (float) impactGrid.x, (float) impactGrid.y, (float) impactGrid.z, CRATER_RADIUS_VOXELS,
            (float) directionGrid.x, (float) directionGrid.y, (float) directionGrid.z);
        return VoxelCrater.carve(shape, entries);
    }

    private Outcome hitFresh(Fresh fresh, Vector3d origin, Vector3d dir) {
        int target = fresh.index();
        PbdInstance inst = instances.get(target);
        Matrix4f[] live = host.liveTransforms();
        PbdInstance voxInst = withWorldScale(inst, live[target]);
        if (voxInst == null) {
            return new Outcome(false, "[Destroy] '" + inst.id + "' has a degenerate world scale - nothing to voxelize");
        }
        PrimitiveVoxelizer.Result voxResult = PrimitiveVoxelizer.voxelize(voxInst, VOXEL_WORLD_SIZE);
        if (voxResult == null) {
            return new Outcome(false, "[Destroy] '" + inst.id + "' can't be voxelized (indestructible, an unknown type, or a mesh that encloses no volume)");
        }
        float vws = voxResult.voxelWorldSize;
        List<int[]> regions = voxResult.octree.collectFilledRegions();
        if (regions.isEmpty()) {
            return new Outcome(false, "[Destroy] '" + inst.id + "' has no solid voxels to destroy");
        }
        GridFrame frame = gridFrame(live[target], voxResult.gridOriginLocal);
        Vector3d gridDir = worldDirToGrid(frame, dir);
        VoxelSolid solid = new VoxelSolid(regions);

        // The bounding-box test that picked this instance only says the ray
        // crosses its box - find where it first touches actual material.
        Vector3d gridOrigin = worldPointToGrid(frame, vws, origin);
        double t = solid.firstSolidAlongRay(gridOrigin.x, gridOrigin.y, gridOrigin.z, gridDir.x, gridDir.y, gridDir.z, 1e9);
        Vector3d impact;
        if (t >= 0) {
            impact = new Vector3d(gridOrigin).fma(t + 0.5, gridDir);
        } else {
            // The ray crosses the box but misses all of its solid (a corner of a cylinder's box, say):
            // carve where it entered the box, as before - a near miss still dents the object.
            Vector3f lh = fresh.localHit();
            impact = new Vector3d(
                (lh.x * voxInst.scale.x - voxResult.gridOriginLocal.x) / vws,
                (lh.y * voxInst.scale.y - voxResult.gridOriginLocal.y) / vws,
                (lh.z * voxInst.scale.z - voxResult.gridOriginLocal.z) / vws);
        }
        VoxelCrater.Result carved = carveCrater(regions, impact, gridDir);

        // "What stays": a piece resting on something (the floor, a table) is held up by it; a lever-arm part hangs from its hinge instead.
        Vector3d down = new Vector3d(0, -1, 0);
        frame.rotation().transformInverse(down);
        double bottomProjection = VoxelConnectivity.maxProjection(regions, down.x, down.y, down.z);
        double lowestY = frame.origin().y - bottomProjection * vws;
        double[] bounds = worldBounds(frame, vws, solid);
        boolean grounded = inst.leverArm == null
            && restsOnSomething(snapshotScene(live), target, lowestY, bounds[0], bounds[3], bounds[2], bounds[5]);

        Style style = host.styleFor(inst, voxInst, voxResult.octree.gridSize(), vws);
        Remains<M> placement = new Remains<>(target, voxResult.gridOriginLocal, vws, style, grounded,
            down.x, down.y, down.z, bottomProjection, regions);
        return settle(placement, carved, frame, dir, gridPointToWorld(frame, vws, impact), true, volumeOf(regions));
    }

    /** A further hit on what is left of an already-destroyed instance. */
    private Outcome hitRemains(Remains<M> r, GridFrame frame, GridHit hit, Vector3d dir) {
        VoxelCrater.Result carved = carveCrater(r.entries(), hit.impactGrid(), hit.directionGrid());
        return settle(r, carved, frame, dir, gridPointToWorld(frame, r.voxelWorldSize, hit.impactGrid()),
            false, volumeOf(r.entries()));
    }

    /**
     * Applies a carved crater to an instance's remains: what the crater
     * leaves is split into face-connected pieces (VoxelDebris.split) - the
     * pieces still attached stay as the instance's voxel mesh, the others
     * become falling rigid bodies with a mesh each. Everything that can fail
     * (building meshes) is built BEFORE any state is touched, so a failure
     * leaves the scene exactly as it was.
     */
    private Outcome settle(Remains<M> placement, VoxelCrater.Result carved, GridFrame frame,
                           Vector3d shotWorld, Vector3d impactWorld, boolean fresh, long volumeBefore) {
        int index = placement.instanceIndex;
        PbdInstance inst = instances.get(index);
        float vws = placement.voxelWorldSize;
        VoxelDebris.Split split = VoxelDebris.split(carved.surviving, placement.downX, placement.downY, placement.downZ,
            placement.bottomProjection, placement.grounded);

        M baseMesh = null;
        if (!split.base.isEmpty()) {
            baseMesh = host.buildMesh(split.base, vws, placement.style);
            if (baseMesh == null) {
                return new Outcome(false, "[Destroy] '" + inst.id + "' -> crater computed but its mesh failed to build");
            }
        }
        int hitSerial = serial++;
        List<Chunk<M>> spawned = new ArrayList<>();
        long fallenVoxels = 0;
        for (VoxelConnectivity.Component piece : split.chunks) {
            M mesh = host.buildMesh(piece.entries, vws, placement.style);
            if (mesh == null) {
                if (baseMesh != null) host.closeMesh(baseMesh);
                for (Chunk<M> s : spawned) host.closeMesh(s.mesh);
                return new Outcome(false, "[Destroy] '" + inst.id + "' -> a detached piece's mesh failed to build");
            }
            RigidDebris body = VoxelDebris.spawn(piece, vws, frame.origin(), frame.rotation(),
                new Vector3d(), new Vector3d(), new Vector3d(), shotWorld, 7919L * index + 104729L * hitSerial + spawned.size());
            spawned.add(new Chunk<>(piece.entries, body, mesh, vws, placement.style));
            fallenVoxels += piece.volume;
        }

        // ---- commit ----
        Remains<M> previous = remains.get(index);
        if (previous != null && previous.mesh != null) host.closeMesh(previous.mesh); // real GL-resource leak otherwise - confirmed cause of a reported "crashes if I press G too much"
        if (baseMesh != null) {
            placement.set(split.base, baseMesh);
            remains.put(index, placement);
        } else {
            remains.remove(index);
        }
        if (fresh) host.hideInstance(index);
        wakeDebrisNear(impactWorld);
        chunks.addAll(spawned);
        while (chunks.size() > MAX_LIVE_CHUNKS) host.closeMesh(chunks.remove(0).mesh);

        long remaining = volumeOf(split.base);
        String chunkInfo = spawned.isEmpty() ? "" : "; " + spawned.size() + " piece(s) broke off and fall (" + fallenVoxels + " voxel(s))";
        String dustInfo = split.dustPieces > 0 ? ", " + split.dustPieces + " crumb(s) (" + split.dustVoxels + " voxel(s)) discarded" : "";
        if (baseMesh == null && spawned.isEmpty()) {
            return new Outcome(true, "[Destroy] '" + inst.id + "' -> fully destroyed (" + volumeBefore + " voxel(s), all within the crater)");
        }
        return new Outcome(true, "[Destroy] '" + inst.id + "' -> " + (fresh ? "" : "follow-up hit: ")
            + carved.removedVoxels + " voxel(s) removed, " + remaining + " still attached"
            + (baseMesh != null ? " (" + split.base.size() + " geometry piece(s))" : "")
            + chunkInfo + dustInfo);
    }

    /** A hit on a piece that already broke off: carve it; what is left becomes new free pieces carrying its motion. */
    private Outcome hitChunk(Chunk<M> chunk, GridHit hit, Vector3d dir) {
        VoxelCrater.Result carved = carveCrater(chunk.entries, hit.impactGrid(), hit.directionGrid());
        if (carved.removedVoxels == 0) {
            return new Outcome(false, "[Destroy] falling piece -> the hit removed nothing");
        }
        List<VoxelConnectivity.Component> pieces = VoxelConnectivity.split(carved.surviving);
        RigidDebris parent = chunk.body;
        Vector3d meshOrigin = parent.bodyToWorld(new Vector3d(parent.comMesh).negate());
        Quaterniond meshRotation = new Quaterniond(parent.orientation);

        List<Chunk<M>> spawned = new ArrayList<>();
        int hitSerial = serial++;
        int dust = 0;
        long fallenVoxels = 0;
        for (VoxelConnectivity.Component piece : pieces) { // biggest first
            if (piece.volume < VoxelDebris.MIN_CHUNK_VOXELS || spawned.size() >= VoxelDebris.MAX_CHUNKS_PER_HIT) { dust++; continue; }
            M mesh = host.buildMesh(piece.entries, chunk.voxelWorldSize, chunk.style);
            if (mesh == null) {
                for (Chunk<M> s : spawned) host.closeMesh(s.mesh);
                return new Outcome(false, "[Destroy] falling piece -> a mesh failed to build");
            }
            RigidDebris body = VoxelDebris.spawn(piece, chunk.voxelWorldSize, meshOrigin, meshRotation,
                parent.position, parent.velocity, parent.angularVelocity, dir, 31337L * hitSerial + spawned.size());
            spawned.add(new Chunk<>(piece.entries, body, mesh, chunk.voxelWorldSize, chunk.style));
            fallenVoxels += piece.volume;
        }

        chunks.remove(chunk);
        host.closeMesh(chunk.mesh);
        chunks.addAll(spawned);
        while (chunks.size() > MAX_LIVE_CHUNKS) host.closeMesh(chunks.remove(0).mesh);
        return new Outcome(true, "[Destroy] falling piece -> " + carved.removedVoxels + " voxel(s) removed, "
            + spawned.size() + " piece(s) left (" + fallenVoxels + " voxel(s))" + (dust > 0 ? ", " + dust + " crumb(s) discarded" : ""));
    }

    /** Something resting near the impact may have just lost its support (a stump carved away under it): wake it and re-derive what it can land on. */
    private void wakeDebrisNear(Vector3d where) {
        for (Chunk<M> c : chunks) {
            if (c.body.position.distance(where) <= WAKE_RADIUS + c.body.boundingRadius()) {
                c.colliders = null;
                c.body.wake();
            }
        }
    }

    // ------------------------------------------------------------------
    // Falling debris
    // ------------------------------------------------------------------

    /**
     * Advances every awake chunk by dt seconds: gravity, the floor, the
     * remains of destroyed instances, and the boxes of nearby cubes and
     * cylinders. Call once per frame. Does nothing - and costs nothing -
     * while no piece has broken off or all have settled.
     */
    public void update(double dt) {
        if (chunks.isEmpty()) return;
        double step = Math.min(dt, MAX_DEBRIS_DT);
        Matrix4f[] live = null;
        SceneSnapshot snapshot = null;
        for (Chunk<M> c : chunks) {
            if (c.body.asleep) continue;
            if (c.colliders == null) {
                if (snapshot == null) {
                    live = host.liveTransforms();
                    snapshot = snapshotScene(live);
                }
                c.colliders = buildColliders(c, live, snapshot);
            }
            c.body.step(step, c.colliders);
        }
        // A piece that somehow left the world (a numerical escape) is dropped rather than simulated forever.
        chunks.removeIf(c -> {
            boolean lost = !c.body.isFinite() || c.body.position.y < -10000;
            if (lost) host.closeMesh(c.mesh);
            return lost;
        });
    }

    private List<RigidDebris.Collider> buildColliders(Chunk<M> chunk, Matrix4f[] live, SceneSnapshot snapshot) {
        List<RigidDebris.Collider> colliders = new ArrayList<>();
        colliders.add(RigidDebris.floor(snapshot.floorY));
        RigidDebris body = chunk.body;
        double reach = COLLIDER_REACH + body.boundingRadius();
        for (SceneBox box : snapshot.boxes) {
            if (!box.within(body.position, reach)) continue;
            RigidDebris.Collider collider = RigidDebris.box(box.centre, box.rotation, box.half);
            if (body.embeddedIn(collider, EMBEDDED_TOLERANCE)) continue; // starts inside it (hung flush against a wall, say): ignore, don't shove
            colliders.add(collider);
        }
        for (Remains<M> r : remains.values()) {
            GridFrame frame = gridFrame(live[r.instanceIndex], r.gridOrigin);
            RigidDebris.Collider collider = RigidDebris.voxels(r.solid(), r.voxelWorldSize, frame.origin(), frame.rotation());
            if (body.embeddedIn(collider, EMBEDDED_TOLERANCE)) continue;
            colliders.add(collider);
        }
        return colliders;
    }
}
