package pbd.format;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the show lines of a scene description file - {@code show} and {@code at <time> <action> ...} - into a {@link ShowScript}.
 *
 * <pre>{@code
 * show   overlay=off vsync=off report=stress.txt warmup=2     # once, anywhere; every field optional
 * at 0      camera pos=(0, 1.7, 8) look=(0, 1.2, 0)          # a key of the camera path: where it is, what it looks at
 * at 4      camera pos=(3, 1.7, 4) look=locker_1             # look=<id> looks at the centre of that instance
 * at 0:10   orbit center=(0,0,0) radius=30 height=12 for=40 turns=1   # a whole circle of camera keys
 * at 12     key E                                            # a key of the keyboard pressed once (E, G or H)
 * at 15     key G times=5 every=0.4                          # ...five times, 0.4 s apart
 * at 20     click                                            # the left mouse button
 * at 21     open locker_1.door                               # a door opened; "close" closes it
 * at 0      time hour=6 day=120                              # the sun's clock: keys, with a straight line between them
 * at 30     time hour=20
 * at 45     quit                                             # the window closes
 * }</pre>
 * Times are seconds ({@code 12}, {@code 12.5}) or {@code minutes:seconds} ({@code 1:30}).
 */
final class ShowParser {

    private static final Pattern MINUTES_SECONDS = Pattern.compile("([0-9]{1,4}):([0-9]{1,2}(?:\\.[0-9]+)?)");
    private static final Pattern REPORT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,59}\\.txt");

    /** Camera keys an orbit writes for each turn of it: a key every 10 degrees (so an orbit is 720 keys at most). */
    static final int ORBIT_KEYS_PER_TURN = 36;
    static final int ORBIT_MAX_TURNS = 20;

    /** The most times one {@code key} line may repeat. */
    static final int MAX_REPEATS = 1000;

    private final ShowScript.Builder show = new ShowScript.Builder();
    private boolean headerSeen;
    private boolean any;

    /** Whether any show line was read: a scene file without one has no show. */
    boolean hasContent() {
        return any;
    }

    ShowScript build() {
        return show.build();
    }

    // ------------------------------------------------------------------ show

    void header(SceneLine l) {
        if (headerSeen) throw l.error("'show' is written twice");
        headerSeen = true;
        any = true;
        l.expectPositional();
        l.allowOnly("overlay", "vsync", "report", "warmup");
        show.overlay = l.flag("overlay", true);
        show.vsync = l.flag("vsync", true);
        String report = l.raw("report");
        if (report != null) {
            if (!REPORT_NAME.matcher(report).matches() || PbdLimits.isWindowsDeviceName(report)) {
                throw l.error("report '" + SceneLine.shorten(report) + "' must be a plain file name ending in .txt (letters, digits, '.', '_' and '-'; no folder): it is written next to the scene file");
            }
            show.report = report;
        }
        show.warmup = l.numberIn("warmup", 2.0, 0, 3600);
    }

    // ------------------------------------------------------------------ at

    void at(SceneLine l) {
        if (l.positional.size() < 2) throw l.error("'at' is written: at <time> <action> [...] - for example: at 12.5 camera pos=(0,2,5) look=(0,1,0)");
        any = true;
        double time = parseTime(l, l.positional.get(0));
        String action = l.positional.get(1);
        switch (action) {
            case "camera" -> camera(l, time);
            case "orbit" -> orbit(l, time);
            case "key" -> key(l, time);
            case "click" -> click(l, time);
            case "open" -> door(l, time, ShowScript.Kind.OPEN);
            case "close" -> door(l, time, ShowScript.Kind.CLOSE);
            case "time" -> clock(l, time);
            case "quit" -> quit(l, time);
            default -> throw l.error("unknown action '" + SceneLine.shorten(action) + "' - the actions are camera, orbit, key, click, open, close, time and quit");
        }
    }

    static double parseTime(SceneLine l, String word) {
        double seconds;
        Matcher minutes = MINUTES_SECONDS.matcher(word);
        if (minutes.matches()) {
            double s = Double.parseDouble(minutes.group(2));
            if (s >= 60) throw l.error("the seconds of the time '" + word + "' must be below 60");
            seconds = Integer.parseInt(minutes.group(1)) * 60.0 + s;
        } else {
            seconds = l.parseNumber("the time", word);
        }
        if (seconds < 0 || seconds > ShowScript.MAX_TIME) throw l.error("the time '" + word + "' must be between 0 and " + (long) ShowScript.MAX_TIME + " seconds");
        return seconds;
    }

    private void camera(SceneLine l, double time) {
        l.expectPositional("<time>", "camera");
        l.allowOnly("pos", "look");
        ShowScript.Vec position = vec(l.requiredVec3("pos"));
        ShowScript.Target look = target(l, "look");
        addCamera(l, new ShowScript.CameraKey(time, position, look, l.number));
    }

    private void addCamera(SceneLine l, ShowScript.CameraKey key) {
        if (show.camera.size() >= ShowScript.MAX_CAMERA_KEYS) throw l.error("a show holds at most " + ShowScript.MAX_CAMERA_KEYS + " camera keys");
        show.camera.add(key);
    }

    /** {@code look=(x,y,z)} or {@code look=<instance id>}. */
    private static ShowScript.Target target(SceneLine l, String key) {
        String text = l.requiredRaw(key);
        if (text.trim().startsWith("(")) return ShowScript.Target.at(vec(l.parseTuple(key, text, 3)));
        return ShowScript.Target.of(l.checkInstanceId(key, text));
    }

    private static ShowScript.Vec vec(double[] v) {
        return new ShowScript.Vec(v[0], v[1], v[2]);
    }

    /** A whole circle (or several, or part of one) of camera keys, a key every 10 degrees, looking at the centre unless told otherwise. */
    private void orbit(SceneLine l, double time) {
        l.expectPositional("<time>", "orbit");
        l.allowOnly("center", "radius", "height", "for", "turns", "start", "look");
        double[] center = l.requiredVec3("center");
        double radius = l.requiredNumber("radius");
        if (radius <= 0) throw l.error("radius must be above 0, got " + radius);
        double height = l.requiredNumber("height");
        double duration = l.requiredNumber("for");
        if (duration <= 0) throw l.error("for (the seconds the orbit lasts) must be above 0, got " + duration);
        double turns = l.number("turns", 1.0);
        if (turns == 0 || Math.abs(turns) > ORBIT_MAX_TURNS) throw l.error("turns must not be 0 and must be at most " + ORBIT_MAX_TURNS + " either way, got " + turns);
        double start = l.number("start", 0.0);
        ShowScript.Target look = l.has("look") ? target(l, "look") : ShowScript.Target.at(vec(center));
        if (time + duration > ShowScript.MAX_TIME) throw l.error("the orbit ends after " + (long) ShowScript.MAX_TIME + " seconds");

        int steps = (int) Math.max(4, Math.ceil(Math.abs(turns) * ORBIT_KEYS_PER_TURN));
        for (int k = 0; k <= steps; k++) {
            double angle = Math.toRadians(start + 360.0 * turns * k / steps);
            ShowScript.Vec position = new ShowScript.Vec(center[0] + radius * Math.sin(angle), height, center[2] + radius * Math.cos(angle));
            addCamera(l, new ShowScript.CameraKey(time + duration * k / steps, position, look, l.number));
        }
    }

    private void key(SceneLine l, double time) {
        l.expectPositional("<time>", "key", "<E|G|H>");
        String letter = l.positional.get(2);
        if (!List.of("E", "G", "H").contains(letter)) throw l.error("key must be E, G or H (the keys of the game), got '" + SceneLine.shorten(letter) + "'");
        repeated(l, time, ShowScript.Kind.KEY, letter);
    }

    private void click(SceneLine l, double time) {
        l.expectPositional("<time>", "click");
        repeated(l, time, ShowScript.Kind.CLICK, null);
    }

    private void repeated(SceneLine l, double time, ShowScript.Kind kind, String argument) {
        l.allowOnly("times", "every");
        int times = l.integer("times", 1, 1, MAX_REPEATS);
        double every = l.numberIn("every", 0, 0, 3600);
        if (times > 1 && every < 0.05) throw l.error("times=" + times + " needs every=<seconds> (0.05 or more) between two presses");
        if (time + every * (times - 1) > ShowScript.MAX_TIME) throw l.error("the last repetition comes after " + (long) ShowScript.MAX_TIME + " seconds");
        for (int i = 0; i < times; i++) addEvent(l, new ShowScript.Event(time + every * i, kind, argument, l.number));
    }

    private void door(SceneLine l, double time, ShowScript.Kind kind) {
        l.expectPositional("<time>", kind == ShowScript.Kind.OPEN ? "open" : "close", "<instance id>");
        l.allowOnly();
        String id = l.checkInstanceId("the instance id", l.positional.get(2));
        addEvent(l, new ShowScript.Event(time, kind, id, l.number));
    }

    private void quit(SceneLine l, double time) {
        l.expectPositional("<time>", "quit");
        l.allowOnly();
        addEvent(l, new ShowScript.Event(time, ShowScript.Kind.QUIT, null, l.number));
    }

    private void addEvent(SceneLine l, ShowScript.Event event) {
        if (show.events.size() >= ShowScript.MAX_EVENTS) throw l.error("a show holds at most " + ShowScript.MAX_EVENTS + " events");
        show.events.add(event);
    }

    /** The sun's clock: a key of the hour (0..48, past 24 is the next day) and/or of the day of the year (0..365). */
    private void clock(SceneLine l, double time) {
        l.expectPositional("<time>", "time");
        l.allowOnly("hour", "day");
        if (!l.has("hour") && !l.has("day")) throw l.error("'time' needs hour= and/or day=");
        if (l.has("hour")) addChannel(l, show.hour, new ShowScript.ChannelKey(time, l.numberIn("hour", 0, 0, 48), l.number));
        if (l.has("day")) addChannel(l, show.day, new ShowScript.ChannelKey(time, l.numberIn("day", 0, 0, 365), l.number));
    }

    private static void addChannel(SceneLine l, List<ShowScript.ChannelKey> keys, ShowScript.ChannelKey key) {
        if (keys.size() >= ShowScript.MAX_CHANNEL_KEYS) throw l.error("a show holds at most " + ShowScript.MAX_CHANNEL_KEYS + " keys of the sun's clock");
        keys.add(key);
    }
}
