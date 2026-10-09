package pbd.pz;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Which groups of containers have their contents out right now, and what is remembered about the ones whose doors
 * are shut - the bookkeeping that used to be an if/else inside Main's frame loop, pulled out so it can be tested
 * without a window.
 *
 * <p>The rules, per group of boxes (a group is the set of boxes one door set controls; the key is the list of their
 * instance indices):
 * <ul>
 *   <li>Nothing is loaded before a linked door is open (the project's rule: no computation, no display before the
 *       container opens). The first opening calls the loader, once.</li>
 *   <li>While any door is open the contents stay out. While the doors are closing but not all shut yet, they stay out
 *       too (items must not vanish while still visible through a half-closed gap).</li>
 *   <li>When every door is shut the contents are <b>shelved</b>, not thrown away: the next opening gives back the very
 *       same contents object - what was taken stays gone, what fell stays where it landed, and nothing is rolled again.
 *       (They used to be thrown away and loaded again with the same seed, so a container emptied by the player came
 *       back full the next time its door opened.)</li>
 *   <li>{@link #clear()} forgets everything - a new scene means new containers.</li>
 * </ul>
 *
 * <p>Memory: a shelved group keeps its items' meshes in memory until the scene changes (at most the 50 items a group
 * is ever filled with; the GPU side - one renderer per item - is released by the caller when a group is shelved and
 * made again, lazily, when it is shown again). This remembers a <i>session</i>: nothing is written to disk, so
 * restarting the viewer refills the containers.
 *
 * <p>Not thread-safe, like the frame loop that drives it.
 *
 * @param <C> what a group's contents are (in the viewer, {@link ContainerContents})
 */
public final class ContainerStock<C> {

    /** What one call to {@link #step} did to the group. */
    public enum Change {
        /** Nothing happened: not loaded and still shut, or open and still open, or closing and not yet shut. */
        NONE,
        /** First opening since the scene was loaded: the loader ran. */
        LOADED,
        /** Opened again after being shelved: the contents it had when it was shut are back; the loader did not run. */
        REOPENED,
        /** Every door is shut: the contents were put on the shelf. */
        SHELVED
    }

    /**
     * The outcome of one {@link #step}.
     *
     * @param contents for {@code LOADED}, {@code REOPENED} and {@code SHELVED} the contents concerned; for
     *                 {@code NONE} the contents currently out, or null when the group is not out
     */
    public record Step<C>(Change change, C contents) {}

    private final Map<List<Integer>, C> out = new LinkedHashMap<>();
    private final Map<List<Integer>, C> shelf = new HashMap<>();

    /**
     * One frame's bookkeeping for one group.
     *
     * @param group        the group's key (its boxes' instance indices)
     * @param anyDoorOpen  whether any linked door is open (or being opened) right now
     * @param allDoorsShut whether every linked door is completely shut. Asked only when it matters - no door open and the
     *                     contents out - because answering is a scan of the scene and this runs every frame for every group
     * @param load         builds the contents the first time they are needed; not called for a group that was shelved
     */
    public Step<C> step(List<Integer> group, boolean anyDoorOpen, BooleanSupplier allDoorsShut, Supplier<C> load) {
        C current = out.get(group);
        if (anyDoorOpen && current == null) {
            C back = shelf.remove(group);
            if (back != null) {
                out.put(group, back);
                return new Step<>(Change.REOPENED, back);
            }
            C fresh = load.get();
            out.put(group, fresh);
            return new Step<>(Change.LOADED, fresh);
        }
        if (!anyDoorOpen && current != null && allDoorsShut.getAsBoolean()) {
            out.remove(group);
            shelf.put(group, current);
            return new Step<>(Change.SHELVED, current);
        }
        return new Step<>(Change.NONE, current);
    }

    /** The groups whose contents are out right now, in the order they were first shown (a live read-only view). */
    public Map<List<Integer>, C> open() {
        return Collections.unmodifiableMap(out);
    }

    /** The contents that are out right now (a live read-only view). */
    public Collection<C> openContents() {
        return Collections.unmodifiableCollection(out.values());
    }

    /** How many groups have their contents on the shelf (shut, but remembered). */
    public int shelvedCount() {
        return shelf.size();
    }

    /** Forgets everything, shown or shelved: a new scene starts with every container unopened. */
    public void clear() {
        out.clear();
        shelf.clear();
    }
}
