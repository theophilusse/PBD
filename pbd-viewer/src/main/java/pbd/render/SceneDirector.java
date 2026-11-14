package pbd.render;

import org.joml.Vector3f;
import pbd.format.ShowScript;

import java.util.List;
import java.util.Map;

/**
 * Plays the show of a scene description file ({@link ShowScript}): moves the camera along its path, presses the keys, opens the
 * doors, runs the sun's clock - on its own, at the same moments, every time, so that a take can be repeated and a stress test
 * measures the same thing twice.
 *
 * <p>The director knows nothing of windows, keys or the renderer. Each frame the application tells it how much time went by
 * ({@link #advance}) and it tells a {@link Host} what is now due; it hands back where the camera should be ({@link #steer}). What a
 * virtual key press does is exactly what a real one does, because the host simply sets the same flag the keyboard callback sets: the
 * shot is the player's own G, the light switch is the player's own E.
 *
 * <p><b>Time.</b> The show's clock is the sum of the frame times it is given, not the wall clock: a frame that takes a second (a
 * stress test) delays the show by a second instead of skipping what happened meanwhile, and every event happens, in order. An event
 * fires on the first frame whose end is at or after its time; events at time 0 fire on the first frame.
 *
 * <p><b>The camera.</b> Where the script has a camera path the director drives the camera ({@link CameraPath}), unless the engine has
 * taken it (hiding in a container moves the camera by itself): while the engine holds the camera the path is not applied, and when
 * the engine lets go the camera glides back onto the path in {@link #BLEND_SECONDS} instead of jumping. Pressing a movement key stops
 * the show ({@link #userMoved}): the person takes over, for good.
 *
 * <p><b>The sun.</b> The hour and the day of the year are keys with a straight line between them. An hour past 24 is the next day
 * (22 to 28 crosses midnight); a channel the script has no key for stays what the person left it ({@code startHour}, {@code startDay}).
 */
public final class SceneDirector {

    /** What the director asks of the application. Every method is a plain action: no logic behind it that the director would have to know. */
    public interface Host {
        /** A key of the keyboard pressed once ("E", "G" or "H"). */
        void press(String key);

        /** The left mouse button pressed once. */
        void click();

        /** A door (or any animated instance) told to open or close. */
        void setOpen(String instanceId, boolean open);

        /** The sun's clock: hour in [0, 24), day of the year in [0, 365). */
        void setTime(double hour, double day);

        /** The show is over: close the window. */
        void quit();
    }

    /** How long the camera takes to glide back onto the path after the engine had it. */
    public static final double BLEND_SECONDS = 0.8;

    private final List<ShowScript.Event> events;
    private final List<ShowScript.ChannelKey> hourKeys;
    private final List<ShowScript.ChannelKey> dayKeys;
    private final CameraPath path;
    private final double startHour;
    private final double startDay;

    private double time;
    private int next;
    private boolean playing = true;
    private boolean quitFired;

    private boolean engineHadCamera;
    private CameraPath.Pose blendFrom;
    private double blendElapsed = BLEND_SECONDS; // nothing to blend at the start: the first frame is the first pose

    /**
     * @param centers where every instance the camera looks at is (see {@link ShowTargets}); a look target that is not in the map is a
     *                mistake of the script and is refused here, with its line
     * @param startHour the hour of the day when the show starts, kept for as long as the script has no key for it
     * @param startDay  likewise the day of the year
     */
    public SceneDirector(ShowScript script, Map<String, Vector3f> centers, double startHour, double startDay) {
        this.events = script.events;
        this.hourKeys = script.hour;
        this.dayKeys = script.day;
        this.startHour = startHour;
        this.startDay = startDay;
        if (script.camera.isEmpty()) {
            this.path = null;
        } else {
            int n = script.camera.size();
            double[] times = new double[n];
            double[][] position = new double[n][];
            double[][] look = new double[n][];
            for (int k = 0; k < n; k++) {
                ShowScript.CameraKey key = script.camera.get(k);
                times[k] = key.time();
                position[k] = new double[]{key.position().x(), key.position().y(), key.position().z()};
                if (key.look().instance() != null) {
                    Vector3f center = centers.get(key.look().instance());
                    if (center == null) {
                        throw new IllegalStateException("scene line " + key.line() + ": the camera looks at '" + key.look().instance() + "', which is not in the scene");
                    }
                    look[k] = new double[]{center.x, center.y, center.z};
                } else {
                    look[k] = new double[]{key.look().point().x(), key.look().point().y(), key.look().point().z()};
                }
            }
            this.path = new CameraPath(times, position, look);
        }
    }

    /** Whether the show is still running (not over, not stopped by the person). */
    public boolean isPlaying() {
        return playing;
    }

    /** Whether the show ended by itself, with its {@code quit}. */
    public boolean quitFired() {
        return quitFired;
    }

    /** Seconds of show played so far. */
    public double time() {
        return time;
    }

    /** The person took over: the show stops where it is. */
    public void stop() {
        playing = false;
    }

    /** Tell it whether a movement key is down: the person wants the camera, and the show lets go for good. */
    public void userMoved(boolean movementKeyDown) {
        if (movementKeyDown) stop();
    }

    /** Plays {@code dt} more seconds: fires the events that came due, in order, and sets the sun's clock. */
    public void advance(double dt, Host host) {
        if (!playing) return;
        time += step(dt);
        while (next < events.size() && events.get(next).time() <= time) {
            fire(events.get(next++), host);
            if (!playing) return;
        }
        if (!hourKeys.isEmpty() || !dayKeys.isEmpty()) {
            double[] clock = clockAt(time);
            host.setTime(clock[0], clock[1]);
        }
    }

    /** A frame time the clock can use: a time that went backwards, a NaN or an infinity (a clock that failed) moves nothing. */
    static double step(double dt) {
        return dt > 0.0 && !Double.isInfinite(dt) ? dt : 0.0;
    }

    private void fire(ShowScript.Event event, Host host) {
        switch (event.kind()) {
            case KEY -> host.press(event.argument());
            case CLICK -> host.click();
            case OPEN -> host.setOpen(event.argument(), true);
            case CLOSE -> host.setOpen(event.argument(), false);
            case QUIT -> {
                quitFired = true;
                playing = false;
                host.quit();
            }
        }
    }

    // ------------------------------------------------------------------ the sun

    /** {hour in [0, 24), day in [0, 365)} at show time t. */
    double[] clockAt(double t) {
        double hour = channelAt(hourKeys, t, startHour);
        double day = channelAt(dayKeys, t, startDay);
        double carried = Math.floor(hour / 24.0);
        hour -= 24.0 * carried;
        day = (((day + carried) % 365.0) + 365.0) % 365.0;
        return new double[]{hour, day};
    }

    /** A channel's value at t: held before the first key and after the last, a straight line between two keys, {@code fallback} with no key. */
    static double channelAt(List<ShowScript.ChannelKey> keys, double t, double fallback) {
        if (keys.isEmpty()) return fallback;
        ShowScript.ChannelKey first = keys.get(0);
        if (t <= first.time()) return first.value();
        ShowScript.ChannelKey last = keys.get(keys.size() - 1);
        if (t >= last.time()) return last.value();
        for (int k = 0; k < keys.size() - 1; k++) {
            ShowScript.ChannelKey a = keys.get(k), b = keys.get(k + 1);
            if (t >= a.time() && t <= b.time()) {
                return a.value() + (b.value() - a.value()) * (t - a.time()) / (b.time() - a.time());
            }
        }
        return last.value();
    }

    // ------------------------------------------------------------------ the camera

    /**
     * Where the camera should be this frame, or null to leave it alone (no camera path, the show is over, or the engine has the
     * camera). {@code actual} is where the camera is now, {@code engineOwnsCamera} says the engine is moving it itself, {@code dt} is
     * the frame time.
     */
    public CameraPath.Pose steer(CameraPath.Pose actual, boolean engineOwnsCamera, double dt) {
        if (!playing || path == null) return null;
        if (engineOwnsCamera) {
            engineHadCamera = true;
            return null;
        }
        CameraPath.Pose scripted = path.at(time);
        if (engineHadCamera) {
            engineHadCamera = false;
            blendFrom = actual;
            blendElapsed = 0.0;
        }
        if (blendElapsed < BLEND_SECONDS) {
            blendElapsed += step(dt);
            double u = Math.min(1.0, blendElapsed / BLEND_SECONDS);
            return mix(blendFrom, scripted, u * u * (3.0 - 2.0 * u));
        }
        return scripted;
    }

    /** A pose between two, s from 0 to 1; the yaw takes the short way round. */
    static CameraPath.Pose mix(CameraPath.Pose a, CameraPath.Pose b, double s) {
        double twoPi = 2.0 * Math.PI;
        double turn = ((b.yaw() - a.yaw() + Math.PI) % twoPi + twoPi) % twoPi - Math.PI;
        return new CameraPath.Pose(
            a.x() + (b.x() - a.x()) * s,
            a.y() + (b.y() - a.y()) * s,
            a.z() + (b.z() - a.z()) * s,
            a.yaw() + turn * s,
            a.pitch() + (b.pitch() - a.pitch()) * s);
    }
}
