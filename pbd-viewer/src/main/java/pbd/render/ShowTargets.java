package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import pbd.format.PbdInstance;
import pbd.format.PbdScene;
import pbd.voxel.DestructionWorld;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where the things a show looks at are: the centre of an instance, found once, when the show starts.
 *
 * <p>The centre of an instance with geometry is the centre of its box; the centre of a model (a group, as a placement is: it has
 * no geometry of its own) is the centre of the box that holds every part of it - not the group's origin, which for a locker is a
 * point on the floor. A light, or anything with no geometry at or below it, is where it stands.
 *
 * <p>Pure data in, answers out: no GL, so it runs under tools/regression and in the {@code check} command.
 */
public final class ShowTargets {
    private ShowTargets() {}

    /** Whether an instance is something that is seen (and shot at): not a group, a reference, a light or an invisible metadata volume. */
    static boolean hasGeometry(PbdInstance inst) {
        if ("ref".equals(inst.type) || "group".equals(inst.type) || "light".equals(inst.type)) return false;
        return !"true".equals(inst.params.get("metadata"));
    }

    /**
     * The centre of each of the instances named in {@code ids} that exists in the scene (an id the scene does not have is left out of
     * the answer). {@code world} is where every instance stands now.
     */
    public static Map<String, Vector3f> centers(PbdScene scene, Matrix4f[] world, Collection<String> ids) {
        Set<String> wanted = new HashSet<>(ids);
        Map<Integer, String> indexToId = new HashMap<>();
        for (int i = 0; i < scene.instances.size(); i++) {
            String id = scene.instances.get(i).id;
            if (wanted.contains(id)) indexToId.put(i, id);
        }
        Map<Integer, float[]> boxes = new HashMap<>();
        for (int index : indexToId.keySet()) {
            boxes.put(index, new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY});
        }
        if (!boxes.isEmpty()) {
            int n = scene.instances.size();
            Vector3f corner = new Vector3f();
            for (int i = 0; i < n && i < world.length; i++) {
                PbdInstance inst = scene.instances.get(i);
                if (!hasGeometry(inst)) continue;
                float[] local = DestructionWorld.localBox(inst);
                // the instance's own box, in the world
                float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                for (int c = 0; c < 8; c++) {
                    corner.set((c & 1) == 0 ? local[0] : local[3], (c & 2) == 0 ? local[1] : local[4], (c & 4) == 0 ? local[2] : local[5]);
                    world[i].transformPosition(corner);
                    box[0] = Math.min(box[0], corner.x); box[1] = Math.min(box[1], corner.y); box[2] = Math.min(box[2], corner.z);
                    box[3] = Math.max(box[3], corner.x); box[4] = Math.max(box[4], corner.y); box[5] = Math.max(box[5], corner.z);
                }
                // it counts for itself and for every ancestor that was asked for
                int a = i;
                for (int hops = 0; a >= 0 && hops <= n; hops++) {
                    float[] target = boxes.get(a);
                    if (target != null) {
                        for (int axis = 0; axis < 3; axis++) {
                            target[axis] = Math.min(target[axis], box[axis]);
                            target[3 + axis] = Math.max(target[3 + axis], box[3 + axis]);
                        }
                    }
                    a = scene.instances.get(a).parentIndex;
                }
            }
        }
        Map<String, Vector3f> centers = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : indexToId.entrySet()) {
            float[] box = boxes.get(entry.getKey());
            Vector3f center;
            if (box[0] <= box[3]) {
                center = new Vector3f((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
            } else if (entry.getKey() < world.length) {
                center = world[entry.getKey()].getTranslation(new Vector3f()); // no geometry at or below it: where it stands
            } else {
                continue;
            }
            centers.put(entry.getValue(), center);
        }
        return centers;
    }
}
