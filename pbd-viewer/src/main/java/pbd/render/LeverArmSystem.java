package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import pbd.format.PbdInstance;
import pbd.format.PbdMeshData;
import pbd.format.PbdScene;

import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Everything a lever arm does at run time, minus the GPU: the live 0..1
 * arm value of every instance that has a leverArm (0 = closed, its own
 * base pose; 1 = the authored open pose), who is driving it, what a
 * player can grab, how a drag turns an aim into a value, locking, what
 * happens when a held arm is let go, and the open/close sounds.
 * PbdRenderer is only the adapter around this - it owns the GL half,
 * feeds the values to HierarchyResolver and plays the sounds - which is
 * what lets all of it run under tools/regression without a window.
 *
 * WHO DRIVES A VALUE, one at a time per instance:
 * <ul>
 *   <li>scripted easing ({@link #setTarget}, then {@link #update}): the
 *       value walks to 0 or 1 at the arm's own `speed`, or ping-pongs
 *       forever for a looping decorative arm;</li>
 *   <li>an external caller ({@link #setValue}, a mouse drag or later a
 *       physics step): the value is wherever it was last put and easing
 *       leaves it alone until a script takes over again;</li>
 *   <li>a lock ({@link #setLocked}): nothing moves it at all.</li>
 * </ul>
 *
 * WHAT IS "ATTACHED TO AN ARM". A child's world matrix is its parent's
 * times its own (HierarchyResolver), so anything parented to an arm
 * instance - at any depth, any primitive type, lights included - follows
 * it at every value. {@link #ownerOf} names the arm that moves when a
 * given instance is grabbed: the nearest ancestor-or-self that has a
 * leverArm (a latch on a door moves the latch, the door's panel moves the
 * door), and {@link #pick} lets the player grab through ANY of them, so a
 * handle, a pin or a pivot's visible panel all drag the arm.
 *
 * NOTHING HERE IS CACHED ACROSS A SCENE CHANGE: a system is built for one
 * PbdScene (PbdRenderer.upload builds a fresh one), reads the instances'
 * authored data live and keeps only run-time state, so assets loaded or
 * edited on the fly are simply a new system.
 */
public final class LeverArmSystem {

    /** At most this is "fully closed" - the threshold the open/closed queries have always used. */
    public static final double CLOSED_AT_MOST = 0.01;
    /** At least this is "fully open". */
    public static final double OPEN_AT_LEAST = 1.0 - 0.01;
    /** Once closed, an arm only counts as having LEFT the closed end past this (a little hysteresis, so a hair of aim jitter cannot chatter the sound). */
    static final double LEAVES_CLOSED_ABOVE = 0.02;
    /** Coarse samples of the arm's travel searched per drag update, then refined. */
    static final int DRAG_SAMPLES = 40;
    /** Golden-section steps refining the best coarse sample (interval 2/40 shrinks by 0.618 per step). */
    static final int DRAG_REFINE_STEPS = 18;
    /** A pick box is the canonical +-0.5 box grown by this (an edge click still lands). */
    static final float PICK_HALF_EXTENT = 0.55f;

    /** The arm that moves when the player grabs {@code owner} or something attached to it, and the grabbed point in that arm's own local space (fixed for the whole drag, whatever the arm does). */
    public record Grab(int owner, Vector3f localPoint) {}

    private final PbdScene scene;
    private final int count;
    private final double[] value;         // NaN = this instance has no lever arm
    private final boolean[] driven;       // an external caller owns the value (setValue)
    private final boolean[] open;         // scripted easing target: true = toward 1, false = toward 0
    private final boolean[] loop;         // ping-pongs forever (a decorative arm)
    private final boolean[] loopForward;
    private final boolean[] locked;
    private final boolean[] wasClosed;    // the closed/open state the sounds last saw
    private final int[] owner;            // nearest ancestor-or-self with a lever arm, -1 = none
    private final float[][] meshBounds;   // lazily computed local bounds of mesh instances, else null

    public LeverArmSystem(PbdScene scene) {
        this.scene = scene;
        this.count = scene.instances.size();
        value = new double[count];
        driven = new boolean[count];
        open = new boolean[count];
        loop = new boolean[count];
        loopForward = new boolean[count];
        locked = new boolean[count];
        wasClosed = new boolean[count];
        owner = new int[count];
        meshBounds = new float[count][];
        for (int i = 0; i < count; i++) {
            PbdInstance inst = scene.instances.get(i);
            PbdInstance.LeverArm arm = inst.leverArm;
            // Starts closed - a cabinet loads shut, not mid-swing - unless the file says it starts locked, which pins it at lockAt.
            value[i] = arm == null ? Double.NaN : (arm.locked ? clamp01(arm.lockAt) : 0.0);
            locked[i] = arm != null && arm.locked;
            loop[i] = "true".equals(inst.params.get("loop"));
            loopForward[i] = true;
            wasClosed[i] = arm != null && value[i] <= CLOSED_AT_MOST;
            // parents precede children (PbdScene.resolveHierarchy), so the parent's owner is already known
            owner[i] = arm != null ? i : (inst.parentIndex >= 0 ? owner[inst.parentIndex] : -1);
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Whether instance i has a lever arm of its own. */
    public boolean has(int i) { return i >= 0 && i < count && !Double.isNaN(value[i]); }

    /** The live arm value, NaN for an instance with no arm. */
    public double value(int i) { return i >= 0 && i < count ? value[i] : Double.NaN; }

    /** The live values, indexed like scene.instances (NaN = no arm) - HierarchyResolver's per-instance arm input. Read-only by contract. */
    public double[] values() { return value; }

    /** The arm that moves when i is grabbed (i itself if it has an arm, else its nearest ancestor with one), -1 if none. */
    public int ownerOf(int i) { return i >= 0 && i < count ? owner[i] : -1; }

    public boolean isLocked(int i) { return has(i) && locked[i]; }
    public boolean isLooping(int i) { return has(i) && loop[i]; }

    /** The scripted open/closed target last set (what setOpen asked for), false for an instance with no arm. */
    public boolean targetsOpen(int i) { return has(i) && open[i]; }

    /** True while an external caller (a drag) owns the value rather than the scripted easing. */
    public boolean isExternallyDriven(int i) { return has(i) && driven[i]; }

    /** Moved open by any visible amount - read off the value itself, so a hand-dragged arm with no toggle at all counts. */
    public boolean isOpen(int i) { return has(i) && value[i] > CLOSED_AT_MOST; }

    public boolean isFullyOpen(int i) { return has(i) && value[i] >= OPEN_AT_LEAST; }

    /** An instance with no arm counts as closed (nothing to wait out), the rule the facade has always had. */
    public boolean isFullyClosed(int i) { return !has(i) || value[i] <= CLOSED_AT_MOST; }

    public PbdInstance.LeverArm.Release releaseOf(int i) { return has(i) ? scene.instances.get(i).leverArm.release : null; }

    // ------------------------------------------------------------------
    // Driving
    // ------------------------------------------------------------------

    /** Advances every unlocked, scripted arm one step and reports whether any value changed; plays an arm's open/close sound once when it leaves/returns to its closed end - whoever moved it (this easing, a drag, a script). sounds may be null (silent). */
    public boolean update(double dt, Consumer<String> sounds) {
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            if (Double.isNaN(value[i]) || locked[i] || driven[i]) continue; // a lock holds it, a drag/script owns it
            double before = value[i];
            double step = scene.instances.get(i).leverArm.speed * dt;
            if (loop[i]) {
                if (loopForward[i]) {
                    value[i] += step;
                    if (value[i] >= 1.0) { value[i] = 1.0; loopForward[i] = false; }
                } else {
                    value[i] -= step;
                    if (value[i] <= 0.0) { value[i] = 0.0; loopForward[i] = true; }
                }
            } else {
                double target = open[i] ? 1.0 : 0.0;
                if (value[i] < target) value[i] = Math.min(target, value[i] + step);
                else if (value[i] > target) value[i] = Math.max(target, value[i] - step);
            }
            if (value[i] != before) changed = true;
        }
        fireSounds(sounds);
        return changed;
    }

    /** Detects closed<->open transitions of every non-looping arm and plays the matching sound. Also keeps the remembered state current for arms without sounds, so one gained later cannot misfire. */
    private void fireSounds(Consumer<String> sounds) {
        for (int i = 0; i < count; i++) {
            if (Double.isNaN(value[i])) continue;
            boolean closedNow = wasClosed[i] ? !(value[i] > LEAVES_CLOSED_ABOVE) : value[i] <= CLOSED_AT_MOST;
            if (closedNow == wasClosed[i]) continue;
            wasClosed[i] = closedNow;
            if (sounds == null || loop[i]) continue; // a looping fan would otherwise click every cycle
            PbdInstance.LeverArm arm = scene.instances.get(i).leverArm;
            String file = closedNow ? arm.closeSound : arm.openSound;
            if (file != null) sounds.accept(file);
        }
    }

    /** Hands instance i to the scripted easing, heading for the open (true) or closed end. False - nothing changes - if it has no arm or is locked. The caller handles linkGroup partners (it also spans keyframed ones). */
    public boolean setTarget(int i, boolean toOpen) {
        if (!has(i) || locked[i]) return false;
        open[i] = toOpen;
        driven[i] = false;
        return true;
    }

    /**
     * Puts instance i's arm at v (clamped to 0..1) and leaves it there:
     * the value now belongs to the caller until setTarget takes it back.
     * Every arm sharing i's linkGroup follows (a wardrobe's twin doors),
     * except any that is locked. False - nothing changes at all - if i
     * has no arm or is itself locked.
     */
    public boolean setValue(int i, double v) {
        if (!has(i) || locked[i]) return false;
        double clamped = clamp01(v);
        for (int k = 0; k < count; k++) {
            if (!movesWith(i, k) || locked[k]) continue;
            value[k] = clamped;
            driven[k] = true;
        }
        return true;
    }

    /**
     * The player let go of arm i (a drag ended). A `snap` arm is handed
     * back to the scripted easing, heading for whichever end its value is
     * nearer (a door left at 0.3 swings shut, one left at 0.7 swings
     * open - and plays the matching sound on arrival); a `free` arm stays
     * exactly where it was let go and keeps belonging to the caller. Every
     * arm that was dragged along by i's linkGroup is released with it, each
     * by its own policy. Returns whether i itself went back to the easing.
     * A locked arm is untouched (it was never being dragged).
     */
    public boolean release(int i) {
        if (!has(i)) return false;
        boolean snapped = false;
        for (int k = 0; k < count; k++) {
            if (!movesWith(i, k) || locked[k]) continue;
            if (scene.instances.get(k).leverArm.release == PbdInstance.LeverArm.Release.SNAP) {
                open[k] = value[k] >= 0.5;
                driven[k] = false;
                if (k == i) snapped = true;
            }
        }
        return snapped;
    }

    /** Whether arm k moves when arm i is moved: k is i itself, or an arm sharing i's linkGroup. */
    private boolean movesWith(int i, int k) {
        if (Double.isNaN(value[k])) return false;
        if (k == i) return true;
        String group = scene.instances.get(i).params.get("linkGroup");
        return group != null && group.equals(scene.instances.get(k).params.get("linkGroup"));
    }

    /**
     * Locks instance i's arm in place where it stands (nothing moves it
     * until it is unlocked - not a drag, not setTarget, not easing), or
     * unlocks it. Unlocking behaves like letting go of a held arm: a
     * `snap` arm eases to its nearer end, a `free` one stays where it is.
     * False if i has no arm.
     */
    public boolean setLocked(int i, boolean lock) {
        if (!has(i)) return false;
        if (locked[i] == lock) return true;
        locked[i] = lock;
        if (!lock) {
            if (scene.instances.get(i).leverArm.release == PbdInstance.LeverArm.Release.FREE) {
                driven[i] = true;
            } else {
                open[i] = value[i] >= 0.5;
                driven[i] = false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Grabbing and dragging
    // ------------------------------------------------------------------

    /**
     * What a ray would grab: the nearest hit among every instance that
     * belongs to an arm - its own box and the box of anything attached
     * under it - answered as the arm that moves plus the hit point in
     * that arm's local space. null on a miss.
     *
     * worldOf gives an instance's CURRENT world matrix (PbdRenderer passes
     * the live one, so a shot-up hidden door can still be grabbed).
     * Skipped: a looping arm (decorative; clicks and grabs have no effect
     * on it), instances with no geometry of their own (group pivots and
     * lights - they are only grabbed through what is attached to them) and
     * metadata volumes (invisible container boxes). Never changes state.
     */
    public Grab pick(IntFunction<Matrix4f> worldOf, Vector3f rayOrigin, Vector3f rayDir) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        Vector3f bestLocal = new Vector3f();
        Matrix4f bestWorld = null;
        for (int i = 0; i < count; i++) {
            int o = owner[i];
            if (o < 0 || loop[o]) continue;
            PbdInstance inst = scene.instances.get(i);
            if (!hasGeometry(inst)) continue;
            Matrix4f world = worldOf.apply(i);
            if (world == null) continue;
            Matrix4f inv = new Matrix4f(world).invert();
            Vector3f localOrigin = inv.transformPosition(new Vector3f(rayOrigin));
            Vector3f localDir = inv.transformDirection(new Vector3f(rayDir));
            float[] lo = new float[3], hi = new float[3];
            localBox(i, lo, hi);
            float t = rayBox(localOrigin, localDir, lo, hi);
            if (t < 0) continue;
            Vector3f localHit = new Vector3f(localDir).mul(t).add(localOrigin);
            float worldDist = world.transformPosition(new Vector3f(localHit)).distance(rayOrigin);
            if (Float.isNaN(worldDist)) continue;
            if (worldDist < bestDist) {
                bestDist = worldDist;
                best = i;
                bestLocal.set(localHit);
                bestWorld = world;
            }
        }
        if (best < 0) return null;
        int o = owner[best];
        if (best == o) return new Grab(o, bestLocal);
        // Re-express the hit in the OWNER's local frame: that is what a drag keeps fixed while the arm swings.
        Matrix4f ownerWorld = worldOf.apply(o);
        if (ownerWorld == null) return null;
        Vector3f p = new Matrix4f(ownerWorld).invert().transformPosition(bestWorld.transformPosition(new Vector3f(bestLocal)));
        if (Float.isNaN(p.x) || Float.isNaN(p.y) || Float.isNaN(p.z)) return null;
        return new Grab(o, p);
    }

    /**
     * One frame of drag for a grabbed arm: finds the arm value that puts
     * localGrab (the point grabbed, in the arm's own local space) closest
     * to this frame's ray, and commits it with setValue. parentWorld is
     * the arm's PARENT's current world matrix (identity for a root),
     * which the arm's own motion does not change - so each candidate
     * value costs one local matrix, not a whole-scene resolve.
     *
     * Search: DRAG_SAMPLES coarse samples across 0..1, then a golden-
     * section refinement around the best one, so the arm follows the aim
     * smoothly instead of in 2.5 % steps. Like the plain sampling it
     * replaces, it works for any arm - pure rotation, pure slide or both
     * - without assuming a hinge axis, and assumes the distance to the
     * ray has a single minimum across a sensible swing.
     *
     * Returns the value committed, or NaN (nothing changes) if i has no
     * arm or is locked.
     */
    public double dragToward(int i, Vector3f localGrab, Vector3f rayOrigin, Vector3f rayDir, Matrix4f parentWorld) {
        if (!has(i) || locked[i]) return Double.NaN;
        PbdInstance inst = scene.instances.get(i);
        HierarchyResolver resolver = new HierarchyResolver();
        Vector3f dir = new Vector3f(rayDir).normalize();
        java.util.function.DoubleUnaryOperator distanceAt = t -> {
            Matrix4f world = new Matrix4f(parentWorld).mul(resolver.localTransform(inst, Double.NaN, t));
            Vector3f p = world.transformPosition(new Vector3f(localGrab));
            float along = new Vector3f(p).sub(rayOrigin).dot(dir);
            if (along < 0f) along = 0f; // never extrapolate behind the camera - measure to the ray's own start instead
            return p.distance(new Vector3f(dir).mul(along).add(rayOrigin));
        };
        double bestT = value[i];
        double bestD = Double.MAX_VALUE;
        for (int s = 0; s <= DRAG_SAMPLES; s++) {
            double t = (double) s / DRAG_SAMPLES;
            double d = distanceAt.applyAsDouble(t);
            if (d < bestD) { bestD = d; bestT = t; }
        }
        // refine inside the bracket around the best sample
        double a = Math.max(0.0, bestT - 1.0 / DRAG_SAMPLES), b = Math.min(1.0, bestT + 1.0 / DRAG_SAMPLES);
        final double invPhi = (Math.sqrt(5) - 1) / 2;
        double c = b - invPhi * (b - a), d = a + invPhi * (b - a);
        double fc = distanceAt.applyAsDouble(c), fd = distanceAt.applyAsDouble(d);
        for (int k = 0; k < DRAG_REFINE_STEPS; k++) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - invPhi * (b - a); fc = distanceAt.applyAsDouble(c); }
            else { a = c; c = d; fc = fd; d = a + invPhi * (b - a); fd = distanceAt.applyAsDouble(d); }
        }
        double refined = (a + b) / 2;
        if (distanceAt.applyAsDouble(refined) <= bestD) bestT = refined; // never worse than the coarse winner (also keeps the exact ends 0 and 1 when the aim is past them)
        setValue(i, bestT);
        return clamp01(bestT);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private static boolean hasGeometry(PbdInstance inst) {
        return !inst.type.equals("group") && !inst.type.equals("light") && !"true".equals(inst.params.get("metadata"));
    }

    /** The local-space box a click can land in: the canonical +-0.5 cube grown a little, or a mesh's own bounds grown a little (a mesh's local coordinates are not canonical). */
    private void localBox(int i, float[] lo, float[] hi) {
        PbdInstance inst = scene.instances.get(i);
        if (inst.type.equals("mesh") && inst.meshData != null && inst.meshData.vertexCount() > 0) {
            float[] b = meshBounds[i];
            if (b == null) b = meshBounds[i] = boundsOf(inst.meshData);
            for (int a = 0; a < 3; a++) {
                float grow = Math.max(0.05f * (b[3 + a] - b[a]), 0.005f);
                lo[a] = b[a] - grow;
                hi[a] = b[3 + a] + grow;
            }
            return;
        }
        for (int a = 0; a < 3; a++) { lo[a] = -PICK_HALF_EXTENT; hi[a] = PICK_HALF_EXTENT; }
    }

    private static float[] boundsOf(PbdMeshData mesh) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < mesh.vertexCount(); v++) {
            for (int a = 0; a < 3; a++) {
                float p = mesh.positions[v * 3 + a];
                b[a] = Math.min(b[a], p);
                b[3 + a] = Math.max(b[3 + a], p);
            }
        }
        return b;
    }

    /**
     * Ray against an axis-aligned box in the box's own space: the ray
     * parameter of the nearest intersection (0 when the origin is already
     * inside), -1 for a miss - NaN included, which can only come from a
     * degenerate matrix and must never look like a hit.
     */
    static float rayBox(Vector3f origin, Vector3f dir, float[] lo, float[] hi) {
        float tMin = Float.NEGATIVE_INFINITY, tMax = Float.POSITIVE_INFINITY;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {dir.x, dir.y, dir.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Float.isNaN(o[axis]) || Float.isNaN(d[axis])) return -1;
            if (Math.abs(d[axis]) < 1e-8f) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) return -1; // parallel to this axis's faces and outside the slab
                continue;
            }
            float t1 = (lo[axis] - o[axis]) / d[axis];
            float t2 = (hi[axis] - o[axis]) / d[axis];
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        if (tMax < 0) return -1; // entirely behind the origin
        float result = Math.max(tMin, 0f);
        return Float.isNaN(result) ? -1 : result;
    }
}
