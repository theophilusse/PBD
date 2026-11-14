package pbd.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The "show" of a scene description file: what happens while the scene plays on its own - where the camera goes, which keys are
 * pressed when, which doors are opened, what time of day it is. Plain data, read from the {@code at} lines of a {@code .pbdscene}
 * (see {@link SceneExpander}) and played by {@code pbd.render.SceneDirector}.
 *
 * <p>The camera is a list of keys: at each time a position and a point to look at (a point, or the centre of an instance, found when
 * the show starts). The time of day is two lists of keys (hour, day of the year) between which the value moves in a straight line.
 * Everything else is an event at a time: a key pressed, a click, a door opened or closed, the end.
 */
public final class ShowScript {

    /** The longest show: 24 hours, in seconds. */
    public static final double MAX_TIME = 86_400.0;
    public static final int MAX_CAMERA_KEYS = 100_000;
    public static final int MAX_CHANNEL_KEYS = 10_000;
    public static final int MAX_EVENTS = 100_000;

    public record Vec(double x, double y, double z) {}

    /** Where the camera looks: a point, or the centre of the instance with that id - exactly one of the two is set. */
    public record Target(Vec point, String instance) {
        public static Target at(Vec point) { return new Target(point, null); }
        public static Target of(String instance) { return new Target(null, instance); }
    }

    public record CameraKey(double time, Vec position, Target look, int line) {}

    /** A value of the sun's clock (hour of the day, or day of the year) at a time. */
    public record ChannelKey(double time, double value, int line) {}

    public enum Kind {
        /** A key of the keyboard pressed once: {@code argument} is its letter (E, G or H). */
        KEY,
        /** The left mouse button pressed once. */
        CLICK,
        /** A door (any animated instance) opened: {@code argument} is its id. */
        OPEN,
        /** ...and closed. */
        CLOSE,
        /** The end of the show: the window closes. */
        QUIT
    }

    public record Event(double time, Kind kind, String argument, int line) {}

    /** The keys of the camera, by time. */
    public final List<CameraKey> camera;
    /** The keys of the hour of the day (0..48: past 24 is the next day), by time. */
    public final List<ChannelKey> hour;
    /** The keys of the day of the year, by time. */
    public final List<ChannelKey> day;
    /** Every event, by time (events at the same time keep the order of the file). */
    public final List<Event> events;
    /** Whether the statistics overlay stays on screen (a clean take turns it off). */
    public final boolean overlay;
    /** Whether the window waits for the screen (vertical sync): a stress test turns it off to see how fast the scene can really go. */
    public final boolean vsync;
    /** The file name of the report written at the end (a plain name, in the folder of the scene file), or null. */
    public final String report;
    /** Seconds at the start that the frame statistics leave out (shaders, caches: the first frames are never typical). */
    public final double warmup;

    private ShowScript(Builder b) {
        List<CameraKey> cameraKeys = new ArrayList<>(b.camera);
        cameraKeys.sort(Comparator.comparingDouble(CameraKey::time));
        List<ChannelKey> hourKeys = new ArrayList<>(b.hour);
        hourKeys.sort(Comparator.comparingDouble(ChannelKey::time));
        List<ChannelKey> dayKeys = new ArrayList<>(b.day);
        dayKeys.sort(Comparator.comparingDouble(ChannelKey::time));
        List<Event> sorted = new ArrayList<>(b.events);
        sorted.sort(Comparator.comparingDouble(Event::time)); // a stable sort: events at one time stay in the order of the file
        this.camera = Collections.unmodifiableList(cameraKeys);
        this.hour = Collections.unmodifiableList(hourKeys);
        this.day = Collections.unmodifiableList(dayKeys);
        this.events = Collections.unmodifiableList(sorted);
        this.overlay = b.overlay;
        this.vsync = b.vsync;
        this.report = b.report;
        this.warmup = b.warmup;
    }

    /** Whether there is anything to play: a camera path, a time of day or an event. */
    public boolean isEmpty() {
        return camera.isEmpty() && hour.isEmpty() && day.isEmpty() && events.isEmpty();
    }

    /** The time of the last key or event, in seconds (0 for a show with nothing in it). */
    public double duration() {
        double end = 0;
        if (!camera.isEmpty()) end = Math.max(end, camera.get(camera.size() - 1).time());
        if (!hour.isEmpty()) end = Math.max(end, hour.get(hour.size() - 1).time());
        if (!day.isEmpty()) end = Math.max(end, day.get(day.size() - 1).time());
        if (!events.isEmpty()) end = Math.max(end, events.get(events.size() - 1).time());
        return end;
    }

    /**
     * Everything the show says about the scene that the scene must be able to answer: for each id the show names (a camera that looks
     * at an instance, a door to open), the first line that names it.
     */
    public Map<String, Integer> instanceReferences() {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (CameraKey key : camera) {
            if (key.look().instance() != null) found.putIfAbsent(key.look().instance(), key.line());
        }
        for (Event event : events) {
            if (event.kind() == Kind.OPEN || event.kind() == Kind.CLOSE) found.putIfAbsent(event.argument(), event.line());
        }
        return found;
    }

    /**
     * What is wrong between this show and the scene it plays in: one message per id that names no instance. {@code exists} answers
     * for an id of the scene. An empty list is a show that can play.
     */
    public List<String> problems(Predicate<String> exists) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> reference : instanceReferences().entrySet()) {
            if (!exists.test(reference.getKey())) {
                problems.add("scene line " + reference.getValue() + ": '" + reference.getKey() + "' is not an instance of the scene"
                    + (reference.getKey().indexOf('.') < 0 ? " (a part of a placed model is written placement.part, e.g. locker_1.door)" : ""));
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------

    /** Collects the lines of a show; {@link #build} checks what only the whole show can be checked for. */
    static final class Builder {
        final List<CameraKey> camera = new ArrayList<>();
        final List<ChannelKey> hour = new ArrayList<>();
        final List<ChannelKey> day = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        boolean overlay = true;
        boolean vsync = true;
        String report;
        double warmup = 2.0;

        ShowScript build() {
            checkDistinctTimes("camera", camera.stream().map(k -> new double[]{k.time(), k.line()}).toList());
            checkDistinctTimes("hour", hour.stream().map(k -> new double[]{k.time(), k.line()}).toList());
            checkDistinctTimes("day", day.stream().map(k -> new double[]{k.time(), k.line()}).toList());
            return new ShowScript(this);
        }

        /** Two keys of one curve at one time have no curve between them: say so, with both lines. */
        private static void checkDistinctTimes(String what, List<double[]> keys) {
            Map<Double, Integer> firstLine = new LinkedHashMap<>();
            for (double[] key : keys) {
                Integer earlier = firstLine.putIfAbsent(key[0], (int) key[1]);
                if (earlier != null) {
                    throw SceneLine.error((int) key[1], "two " + what + " keys at the same time (" + key[0] + " s; the other is on line " + earlier + ")");
                }
            }
        }
    }
}
