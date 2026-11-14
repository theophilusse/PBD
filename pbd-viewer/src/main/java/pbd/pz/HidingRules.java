package pbd.pz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * The rules of a hiding place, without a window: whether its doors hide someone inside, and whether the H key may take the
 * player in or out. The facts come from the game (is each door fully closed, has a hit altered it - see
 * pbd.voxel.SlabOpenings for what is left of it); what they MEAN is decided here, once, so that the facade getter an AI reads
 * (Main.isHideoutConcealing) and the key handler that refuses to open a shut door can never disagree, and so that a test can
 * pin every rule.
 *
 * <ul>
 *   <li><b>A door</b> is an instance the place lists as its trigger that CAN be opened (it has keyframes or a lever arm), together
 *       with everything that hangs from it ({@link DoorParts}): the usual door is a group carrying the lever arm, with the panel and
 *       the handle as its children, and a hit lands on the panel, not on the group. A listed instance with neither keyframes nor a
 *       lever arm is scenery that cannot move - always "closed" for the eviction of a container's contents - and is not a door
 *       here: a place whose only "door" is a wall that never opens would otherwise be shut for good, and nobody could ever hide in it.</li>
 *   <li><b>Shut door</b> - fully closed (not swinging, not ajar) AND <b>unaltered</b>: no hit has turned the door, or any part
 *       hanging from it, into voxels or destroyed it. A door that was altered counts as <b>open</b>, whatever its arm says and
 *       however small the damage - a dent, a bullet hole, a handle shot off: the place is no longer closed.</li>
 *   <li><b>Concealing</b> - someone inside cannot be seen from outside: the place has at least one door and EVERY door is shut.</li>
 *   <li><b>Shut</b> - nobody can get in or out: the place has at least one door and EVERY door is shut. (It is the same condition
 *       as concealing: a door that is not shut, ajar or altered, is a way to look in and a way to walk in. Two names for two
 *       questions, "can I be seen?" and "can I get in?".) A place with no door at all is never shut and never concealing: there is
 *       nothing to open.</li>
 *   <li><b>The H key</b> - taking cover in, or coming out of, a shut place is refused with a message that says why; a place
 *       too small to crouch in is refused as before; anything else goes ahead.</li>
 * </ul>
 *
 * <p>How much is gone from an altered door - the share of its face still in the way (integrity) and the side of the widest
 * square one can look through (widest gap) - is reported for a HUD or a smarter AI, but decides nothing: altered is altered.
 */
public final class HidingRules {

    private HidingRules() {}

    /**
     * A hiding place must measure at least this (world units) on every axis: roughly what one can crouch into. Blender
     * scale (0.2, 0.2, 0.2) on a native cube, measured by hand against what feels big enough to crouch into; that scale exports
     * to 0.4 world units per axis (checked against the real export pipeline: the cube writes its dimensions directly as scale=,
     * no further factor), which is what the instance's world size is compared with.
     */
    public static final float MIN_HIDE_DIMENSION = 0.4f;

    /**
     * One door of a hiding place, as the game sees it now. fullyClosed: shut all the way (a door still swinging, or ajar, is not).
     * integrity: the share of the door's face still in the way, 1 = whole (pbd.voxel.SlabOpenings.Report). widestGap: the side in
     * metres of the largest square one can look through it, 0 = no hole. altered: a hit has reached the door or a part hanging from
     * it - a door with a piece missing (integrity under 1, or a hole) is altered whatever the flag says, a dent that took nothing out
     * is altered because the flag says so.
     */
    public record Door(String id, boolean fullyClosed, double integrity, double widestGap, boolean altered) {
        /** Numbers that are not numbers cannot make a door look whole: an unknown integrity is 0, an unknown gap is as wide as can be. */
        public Door {
            integrity = Double.isNaN(integrity) ? 0.0 : Math.max(0.0, Math.min(1.0, integrity));
            widestGap = Double.isNaN(widestGap) ? Double.POSITIVE_INFINITY : Math.max(0.0, widestGap);
            altered = altered || integrity < 1.0 || widestGap > 0.0;
        }

        /** A door known by its numbers alone: altered when they say a piece of it is missing. */
        public Door(String id, boolean fullyClosed, double integrity, double widestGap) {
            this(id, fullyClosed, integrity, widestGap, false);
        }

        /** Shut: fully closed and untouched. It neither lets one see in nor lets one in. */
        public boolean shut() {
            return fullyClosed && !altered;
        }
    }

    /**
     * What the doors of a hiding place add up to.
     * doorCount: how many doors it has. closed: it has doors and every one is fully closed (their pose; an altered door can be
     * "closed" and still not shut). altered: some door has been altered. integrity: the lowest integrity of its doors (0 when it
     * has none: nothing is in the way). widestGap: the widest opening in any of its doors, in metres.
     * shut: nobody can get in or out. concealing: someone inside cannot be seen. reason: one line saying which, and why.
     */
    public record Assessment(int doorCount, boolean closed, boolean altered, double integrity, double widestGap, boolean shut, boolean concealing, String reason) {}

    /**
     * A door as the game reports it when ONE instance carries it: its id, whether it is fully closed, and what the destruction world
     * says of it ({@code seen}, from DestructionWorld.openingsOf: null for a door no hit ever reached, which is whole and unaltered).
     */
    public static Door doorOf(String id, boolean fullyClosed, pbd.voxel.SlabOpenings.Report seen) {
        return doorOfParts(id, fullyClosed, seen == null ? List.of() : List.of(seen));
    }

    /**
     * A door as the game reports it when parts hang from it: {@code seen} holds the report of every part of the door a hit reached
     * (DoorParts.reportsOf; null elements are ignored). None: a whole door. Otherwise the door is altered, its integrity is the
     * lowest of the parts' and its widest gap the widest.
     */
    public static Door doorOfParts(String id, boolean fullyClosed, List<pbd.voxel.SlabOpenings.Report> seen) {
        double integrity = 1.0, gap = 0.0;
        boolean altered = false;
        if (seen != null) {
            for (pbd.voxel.SlabOpenings.Report report : seen) {
                if (report == null) continue;
                altered = true;
                integrity = Math.min(integrity, report.integrity());
                gap = Math.max(gap, report.widestGap());
            }
        }
        return new Door(id, fullyClosed, integrity, gap, altered);
    }

    /** The assessment of a place whose doors are {@code doors} (null or empty: it has none). */
    public static Assessment assess(List<Door> doors) {
        if (doors == null || doors.isEmpty()) {
            return new Assessment(0, false, false, 0.0, 0.0, false, false, "no door: nothing hides or blocks");
        }
        boolean closed = true, shut = true, altered = false;
        double integrity = 1.0, gap = 0.0;
        Door notClosed = null, touched = null;
        for (Door d : doors) {
            closed &= d.fullyClosed();
            shut &= d.shut();
            altered |= d.altered();
            integrity = Math.min(integrity, d.integrity());
            gap = Math.max(gap, d.widestGap());
            if (notClosed == null && !d.fullyClosed()) notClosed = d;
            if (touched == null && d.altered()) touched = d;
        }
        String reason;
        if (shut) {
            reason = "concealing: " + count(doors.size()) + " fully closed and untouched; shut: no way in or out";
        } else if (notClosed != null) {
            reason = "not concealing: door '" + notClosed.id() + "' is not fully closed; not shut: door '" + notClosed.id() + "' is open";
        } else {
            reason = "not concealing: door '" + touched.id() + "' was altered by a hit (integrity " + fmt(touched.integrity()) + ", widest hole "
                + fmt(touched.widestGap()) + " m) and counts as open; not shut: door '" + touched.id() + "' is open";
        }
        return new Assessment(doors.size(), closed, altered, integrity, gap, shut, shut, reason);
    }

    /**
     * The doors of the hiding place whose linked doors are named {@code triggerNames} (its containerTriggers), among the
     * {@code instanceCount} instances of the scene: the index of every instance that carries one of the names AND can be opened
     * ({@code movable}), in the order of the names and then of the instances. A name two instances carry is two doors, a name no
     * instance carries is none, and an instance that cannot move is not a door (see the class comment). Null or no names: none.
     */
    public static int[] doorsOf(List<String> triggerNames, int instanceCount, IntFunction<String> idOf, IntPredicate movable) {
        if (triggerNames == null) return new int[0];
        List<Integer> doors = new ArrayList<>();
        for (String name : triggerNames) {
            for (int i = 0; i < instanceCount; i++) {
                if (name == null || !name.equals(idOf.apply(i)) || !movable.test(i)) continue;
                doors.add(i);
            }
        }
        int[] found = new int[doors.size()];
        for (int k = 0; k < found.length; k++) found[k] = doors.get(k);
        return found;
    }

    /**
     * Whether a hit has altered any door of the hiding place named by {@code triggerNames} - a door (see {@link #doorsOf}) or anything
     * hanging from it ({@link DoorParts}; {@code parentOf}: the parent of instance i, -1 for a root; {@code altered}: the instances
     * the hits have reached). With no hit anywhere nothing is asked of the scene at all.
     */
    public static boolean anyAltered(List<String> triggerNames, int instanceCount, IntFunction<String> idOf, IntPredicate movable,
                                     IntUnaryOperator parentOf, int[] altered) {
        if (altered == null || altered.length == 0) return false;
        for (int door : doorsOf(triggerNames, instanceCount, idOf, movable)) {
            if (DoorParts.isAltered(door, altered, instanceCount, parentOf)) return true;
        }
        return false;
    }

    /**
     * The assessment of the hiding place whose linked doors are named {@code triggerNames} (its containerTriggers), among the
     * {@code instanceCount} instances of the scene. One door for every instance {@link #doorsOf} finds; {@code idOf}: the id of
     * instance i; {@code fullyClosed}: whether it is shut all the way; {@code parentOf} and {@code altered}: the parent links of
     * the scene and the instances the hits have reached, from which every door gets the parts hanging from it that were hit
     * ({@link DoorParts#reportsOf}); {@code seen}: what the destruction world says of one such instance (DestructionWorld.openingsOf).
     * Null or no names: a place with no door.
     */
    public static Assessment assessPlace(List<String> triggerNames, int instanceCount, IntFunction<String> idOf, IntPredicate movable,
                                         IntPredicate fullyClosed, IntUnaryOperator parentOf, int[] altered,
                                         IntFunction<pbd.voxel.SlabOpenings.Report> seen) {
        int[] hit = altered == null ? new int[0] : altered;
        List<Door> doors = new ArrayList<>();
        for (int i : doorsOf(triggerNames, instanceCount, idOf, movable)) {
            doors.add(doorOfParts(idOf.apply(i), fullyClosed.test(i), DoorParts.reportsOf(i, hit, instanceCount, parentOf, seen)));
        }
        return assess(doors);
    }

    /** The index of the first of the {@code instanceCount} instances whose id is {@code id}; -1 when none has it, or when {@code id} is null. */
    public static int indexOf(String id, int instanceCount, IntFunction<String> idOf) {
        if (id == null) return -1;
        for (int i = 0; i < instanceCount; i++) {
            if (id.equals(idOf.apply(i))) return i;
        }
        return -1;
    }

    /** Whether someone inside this place cannot be seen: false for a place nothing is known of (null). */
    public static boolean concealing(Assessment a) {
        return a != null && a.concealing();
    }

    /**
     * Whether the PLAYER is hidden and cannot be seen: inside a hiding place ({@code place} is its index, -1 when the player is in
     * none), the move in finished ({@code transitionT} has reached 1: halfway through the camera is still in the open), and the
     * doors of that place ({@code a}) conceal.
     */
    public static boolean playerConcealed(int place, float transitionT, Assessment a) {
        return place >= 0 && transitionT >= 1f && concealing(a);
    }

    private static String count(int doors) {
        return doors == 1 ? "its door is" : "its " + doors + " doors are";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** What pressing H does. */
    public enum Action { NONE, ENTER, EXIT }

    /** What to do, and (for a refusal) what to tell the player; the message is empty when the action goes ahead. */
    public record Verdict(Action action, String message) {}

    /**
     * What the H key does. {@code hiding}: the player is in a hiding place now (then {@code containerId} is that place), else
     * {@code containerId} is the one the crosshair is on, null when it is on none. sx, sy, sz: its size in world units (floats, as
     * the renderer reports them: a place of exactly {@link #MIN_HIDE_DIMENSION} is big enough, which a double 0.4 would not be).
     * {@code place}: its assessment (ignored when containerId is null).
     */
    public static Verdict onHideKey(boolean hiding, String containerId, float sx, float sy, float sz, Assessment place) {
        if (hiding) {
            if (place != null && place.shut()) {
                return new Verdict(Action.NONE, "[Hide] The door of '" + containerId + "' is closed - open it before getting out");
            }
            return new Verdict(Action.EXIT, "");
        }
        if (containerId == null) {
            return new Verdict(Action.NONE, "[Hide] Not aiming at a container");
        }
        if (sx < MIN_HIDE_DIMENSION || sy < MIN_HIDE_DIMENSION || sz < MIN_HIDE_DIMENSION) {
            return new Verdict(Action.NONE, "[Hide] Too small to hide in (" + String.format(Locale.ROOT, "%.2f x %.2f x %.2f", sx, sy, sz)
                + ", need at least " + MIN_HIDE_DIMENSION + " on every axis)");
        }
        if (place != null && place.shut()) {
            return new Verdict(Action.NONE, "[Hide] The door of '" + containerId + "' is closed - open it before getting in");
        }
        return new Verdict(Action.ENTER, "");
    }
}
