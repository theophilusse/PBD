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
        return resolve(scene, null);
    }

    /**
     * perInstanceAnimTime[i] drives instance i's keyframes (see
     * PbdInstance.keyframes) if it has any - null, or a null/NaN entry
     * for a specific index, falls back to that instance's own static
     * pos/rot. Per-instance (not one global clock) so independent
     * containers - two cabinets, say - can each be opened or closed on
     * their own schedule rather than sharing one animation state.
     */
    public Matrix4f[] resolve(PbdScene scene, double[] perInstanceAnimTime) {
        int n = scene.instances.size();
        Matrix4f[] world = new Matrix4f[n];

        for (int i = 0; i < n; i++) {
            PbdInstance inst = scene.instances.get(i);
            Vector3f pos = inst.position;
            Quaternionf rot = inst.rotation;
            if (!inst.keyframes.isEmpty() && perInstanceAnimTime != null && !Double.isNaN(perInstanceAnimTime[i])) {
                Interpolated result = interpolate(inst, perInstanceAnimTime[i]);
                pos = result.pos;
                rot = result.rot;
            }
            Matrix4f local = new Matrix4f().translationRotateScale(pos, rot, inst.scale);

            world[i] = (inst.parentIndex < 0)
                ? local
                : new Matrix4f(world[inst.parentIndex]).mul(local);
        }

        return world;
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
