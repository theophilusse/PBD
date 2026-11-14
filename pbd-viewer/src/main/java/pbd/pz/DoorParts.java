package pbd.pz;

import pbd.voxel.SlabOpenings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * What a door is made of, and which of its parts a hit has reached - without a window.
 *
 * <p>A container names its door by one instance (its {@code containerTrigger}); but what one sees, and what a shot lands on, is the
 * panel and the handle that hang from it. The usual door is a {@code group hinge} carrying the {@code leverArm}, with the panel and
 * the handle written {@code parent = hinge} (or parented to the panel, a level deeper): the G key turns the PANEL into voxels, never
 * the group, which has no geometry of its own. A door judged by the named instance alone could be shot to pieces and still count as
 * whole - and a hiding place behind it would go on hiding someone in plain sight.
 *
 * <p>So the door is the named instance AND everything that hangs from it, at any depth of parent links; it is <b>altered</b> as soon as
 * a hit has reached any one of those instances (turned it into voxels, or destroyed it outright). The parent links are those of the
 * scene ({@code PbdInstance.parentIndex}, -1 for a root); a chain that loops - which a loaded scene never has - ends the walk
 * instead of looping the game.
 */
public final class DoorParts {

    private DoorParts() {}

    /**
     * Whether instance {@code part} is the door {@code door} itself or hangs from it, at any depth. False when either index is
     * outside the {@code instanceCount} instances of the scene.
     */
    public static boolean isPartOf(int door, int part, int instanceCount, IntUnaryOperator parentIndexOf) {
        int at = part;
        // a chain of parents visits each instance once at most: after instanceCount steps it has looped, and the walk ends
        for (int steps = 0; steps < instanceCount && at >= 0 && at < instanceCount; steps++) {
            if (at == door) return true;
            at = parentIndexOf.applyAsInt(at);
        }
        return false;
    }

    /**
     * Whether a hit has reached the door {@code door} or anything that hangs from it. {@code altered}: the instances the hits have
     * reached (DestructionWorld.alteredInstances). The cost is that of the instances hit, however many instances the scene has.
     */
    public static boolean isAltered(int door, int[] altered, int instanceCount, IntUnaryOperator parentIndexOf) {
        for (int hit : altered) {
            if (isPartOf(door, hit, instanceCount, parentIndexOf)) return true;
        }
        return false;
    }

    /**
     * What the hits left of the door: the report ({@code seen}, DestructionWorld.openingsOf) of each altered instance that is the
     * door or hangs from it, in the order of {@code altered}. Empty when no hit reached it. An altered instance that has no report
     * is left out.
     */
    public static List<SlabOpenings.Report> reportsOf(int door, int[] altered, int instanceCount, IntUnaryOperator parentIndexOf,
                                                      IntFunction<SlabOpenings.Report> seen) {
        List<SlabOpenings.Report> reports = new ArrayList<>();
        for (int hit : altered) {
            if (!isPartOf(door, hit, instanceCount, parentIndexOf)) continue;
            SlabOpenings.Report report = seen.apply(hit);
            if (report != null) reports.add(report);
        }
        return reports;
    }
}
