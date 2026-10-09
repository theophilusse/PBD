package pbd.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pbd.format.PbdInstance;
import pbd.format.PbdScene;

import java.util.List;

/**
 * Computes world transforms from local transforms (position, rotation,
 * scale, all relative to the parent) and the hierarchy already resolved
 * by PbdScene.resolveHierarchy() (invariant: parentIndex < own index). A
 * single sequential pass is enough, no recursion needed, since an
 * instance's parent has always already been processed by the time we
 * reach it.
 */
public final class HierarchyResolver {

    public Matrix4f[] resolve(PbdScene scene) {
        return resolve(scene, null, null);
    }

    /**
     * perInstanceAnimTime[i] drives instance i's keyframes (see
     * PbdInstance.keyframes) if it has any - null, or a null/NaN entry
     * for a specific index, falls back to that instance's own static
     * pos/rot. Per-instance (not one global clock) so independent
     * containers - two cabinets, say - can each be opened or closed on
     * their own schedule rather than sharing one animation state.
     *
     * Equivalent to resolve(scene, perInstanceAnimTime, null) - no
     * lever-arm instance gets a driving value, so every leverArm-bearing
     * instance falls back to its own static pos/rot here, same as
     * before lever-arm existed at all.
     */
    public Matrix4f[] resolve(PbdScene scene, double[] perInstanceAnimTime) {
        return resolve(scene, perInstanceAnimTime, null);
    }

    /**
     * perInstanceArmValue[i] drives instance i's lever-arm (see
     * PbdInstance.leverArm) if it has one - null, or a NaN entry for a
     * specific index, falls back to that instance's own static pos/rot,
     * the same convention perInstanceAnimTime already uses for
     * keyframes. If an instance somehow has both a non-empty keyframes
     * list AND a non-null leverArm, with valid driving values for both
     * (not how either mechanism is meant to be authored - see
     * PbdInstance.leverArm's own doc - but not rejected at parse time
     * either), keyframes win outright; the two are never blended.
     *
     * perInstanceArmValue is double[], same type as perInstanceAnimTime,
     * even though an arm value is always meant to sit within [0,1] (a
     * float would hold that just as well) - PbdRenderer's own per-
     * instance driving arrays are both double[] (its real-time clock
     * needs double precision; its arm-value array doesn't strictly
     * need to, but matching its sibling array's type means one caller-
     * side array type throughout instead of a float/double split that
     * buys nothing here).
     */
    public Matrix4f[] resolve(PbdScene scene, double[] perInstanceAnimTime, double[] perInstanceArmValue) {
        int n = scene.instances.size();
        Matrix4f[] world = new Matrix4f[n];

        for (int i = 0; i < n; i++) {
            PbdInstance inst = scene.instances.get(i);
            Matrix4f local = localTransform(inst,
                perInstanceAnimTime != null ? perInstanceAnimTime[i] : Double.NaN,
                perInstanceArmValue != null ? perInstanceArmValue[i] : Double.NaN);

            world[i] = (inst.parentIndex < 0)
                ? local
                : new Matrix4f(world[inst.parentIndex]).mul(local);
        }

        return world;
    }

    /**
     * One instance's transform relative to its parent (translate x rotate
     * x scale) for the given driving values - the single piece resolve()
     * repeats per instance, public so a caller that only needs ONE
     * instance at many values (LeverArmSystem sampling a drag: world =
     * the parent's current world x localTransform at each candidate arm
     * value) does not have to re-resolve the whole scene per sample.
     * animTime drives the keyframes, armValue the lever arm; NaN means
     * "not driven" (the instance's own static pos/rot), and keyframes
     * win outright when both are driven - the rules documented on
     * resolve(), which is now built on this.
     */
    public Matrix4f localTransform(PbdInstance inst, double animTime, double armValue) {
        Vector3f pos = inst.position;
        Quaternionf rot = inst.rotation;
        if (!inst.keyframes.isEmpty() && !Double.isNaN(animTime)) {
            Interpolated result = interpolate(inst, animTime);
            pos = result.pos;
            rot = result.rot;
        } else if (inst.leverArm != null && !Double.isNaN(armValue)) {
            Interpolated result = interpolateLeverArm(inst, armValue);
            pos = result.pos;
            rot = result.rot;
        }
        return new Matrix4f().translationRotateScale(pos, rot, inst.scale);
    }

    private static final class Interpolated {
        final Vector3f pos;
        final Quaternionf rot;
        Interpolated(Vector3f pos, Quaternionf rot) { this.pos = pos; this.rot = rot; }
    }

    /**
     * Linear interpolation on position and on Euler degrees (not
     * quaternion slerp) between the two keyframes surrounding t, clamped
     * to the first/last keyframe outside that range. Plain lerp on
     * degrees is only correct for angle differences well under 360 - true
     * for a door/drawer/lid swinging between two fixed poses, which is
     * what this is for; a multi-turn rotation would need slerp instead. A
     * keyframe that omits pos or rot falls back to the instance's own
     * base value for that field, so a door's keyframes can mention only
     * rot without repeating its position every time (see PbdInstance.Keyframe).
     */
    private Interpolated interpolate(PbdInstance inst, double t) {
        List<PbdInstance.Keyframe> kf = inst.keyframes;
        // Keyframes are used exactly as parsed/written, in file order -
        // callers are expected to list them in increasing time order
        // (matching how every other example .pbd in this project is
        // hand- or export-authored), not sorted defensively here.
        if (t <= kf.get(0).time || kf.size() == 1) {
            return resolveKeyframe(inst, kf.get(0));
        }
        PbdInstance.Keyframe last = kf.get(kf.size() - 1);
        if (t >= last.time) {
            return resolveKeyframe(inst, last);
        }
        for (int i = 0; i < kf.size() - 1; i++) {
            PbdInstance.Keyframe a = kf.get(i);
            PbdInstance.Keyframe b = kf.get(i + 1);
            if (t >= a.time && t <= b.time) {
                float f = (b.time - a.time) > 1e-6f ? (float) ((t - a.time) / (b.time - a.time)) : 0f;
                Vector3f posA = a.pos != null ? a.pos : inst.position;
                Vector3f posB = b.pos != null ? b.pos : inst.position;
                Vector3f rotA = a.rotDeg != null ? a.rotDeg : inst.rotationDeg;
                Vector3f rotB = b.rotDeg != null ? b.rotDeg : inst.rotationDeg;
                Vector3f pos = new Vector3f(
                    posA.x + (posB.x - posA.x) * f,
                    posA.y + (posB.y - posA.y) * f,
                    posA.z + (posB.z - posA.z) * f);
                Vector3f rotDeg = new Vector3f(
                    rotA.x + (rotB.x - rotA.x) * f,
                    rotA.y + (rotB.y - rotA.y) * f,
                    rotA.z + (rotB.z - rotA.z) * f);
                return new Interpolated(pos, quaternionFromDeg(rotDeg));
            }
        }
        return resolveKeyframe(inst, last); // unreachable given the bounds checks above, kept for safety
    }

    private Interpolated resolveKeyframe(PbdInstance inst, PbdInstance.Keyframe k) {
        Vector3f pos = k.pos != null ? k.pos : inst.position;
        Quaternionf rot = k.rotDeg != null ? quaternionFromDeg(k.rotDeg) : inst.rotation;
        return new Interpolated(pos, rot);
    }

    /**
     * Lever-arm counterpart to interpolate() above: a direct 2-point
     * lerp between this instance's own base pos/rotationDeg (armValue
     * 0 - "closed", exactly what resolve() uses with no lever-arm
     * override at all) and its leverArm's authored open pose (armValue
     * 1), using the same plain-lerp-on-degrees convention as keyframes
     * (valid for the same reason: a hinge swinging well under 360
     * degrees, not a multi-turn rotation). armValue is clamped to [0,1]
     * here - the one chokepoint every caller funnels through - so an
     * easing overshoot upstream (in PbdRenderer's own per-frame easing
     * step) can't extrapolate past the two authored endpoints.
     */
    private Interpolated interpolateLeverArm(PbdInstance inst, double armValue) {
        float t = (float) Math.max(0.0, Math.min(1.0, armValue));
        PbdInstance.LeverArm arm = inst.leverArm;
        Vector3f openPos = arm.openPos != null ? arm.openPos : inst.position;
        Vector3f pos = new Vector3f(
            inst.position.x + (openPos.x - inst.position.x) * t,
            inst.position.y + (openPos.y - inst.position.y) * t,
            inst.position.z + (openPos.z - inst.position.z) * t);
        // If openRotDeg is omitted, the rotation end of this lerp is
        // inst.rotationDeg at BOTH endpoints (0 and 1), i.e. it never
        // actually changes - skip the degrees->quaternion rebuild
        // entirely in that case and reuse inst.rotation directly, same
        // "prefer the already-built quaternion when nothing's actually
        // interpolating" spirit as resolveKeyframe's own rotDeg==null
        // branch just above.
        if (arm.openRotDeg == null) {
            return new Interpolated(pos, inst.rotation);
        }
        Vector3f rotDeg = new Vector3f(
            inst.rotationDeg.x + (arm.openRotDeg.x - inst.rotationDeg.x) * t,
            inst.rotationDeg.y + (arm.openRotDeg.y - inst.rotationDeg.y) * t,
            inst.rotationDeg.z + (arm.openRotDeg.z - inst.rotationDeg.z) * t);
        return new Interpolated(pos, quaternionFromDeg(rotDeg));
    }

    // Same composition order as PbdParser.applyInstanceField's "rot" case
    // and parsePbdRef's group anchor - kept in sync with both by hand
    // since a quaternion-to-Euler round trip (needed for the "keyframe
    // omitted rot, fall back to the instance's own" case) has no
    // canonical single JOML call this project already established a
    // pattern for.
    private Quaternionf quaternionFromDeg(Vector3f deg) {
        return new Quaternionf().identity()
            .rotateZ((float) Math.toRadians(deg.z))
            .rotateY((float) Math.toRadians(deg.y))
            .rotateX((float) Math.toRadians(deg.x));
    }
}
