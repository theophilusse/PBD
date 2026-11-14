package pbd.render;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * What a stress test measures: how long each frame took, and what that comes to - the average, the worst, and the percentiles that
 * tell a smooth run from a stuttering one (an average of 60 frames a second hides a pause of a quarter of a second).
 *
 * <p>The first seconds of a run are left out ({@code warmupSeconds}): shaders compile, caches fill, the first frames are never the
 * ones to judge. Percentiles are the nearest-rank kind: the 99th percentile of 1000 frames is the 990th, the 99.9th the 999th, ordered
 * by time - a real frame that happened, not an interpolation between two.
 *
 * <p>"1% low" and "0.1% low" are frame rates: the rate of the frame that only 1% (0.1%) of the frames were slower than.
 *
 * <p>Pure arithmetic; the application only feeds it the time of each frame.
 */
public final class FrameStats {

    /** More frames than this are counted but no longer kept for the percentiles (2 million frames: 4.6 hours at 120 frames a second). */
    public static final int MAX_KEPT = 2_000_000;

    /**
     * A clock cannot tell a frame from no time at all below this: the rate of a frame it saw as shorter is given as the rate of one
     * this long (a million a second), never as infinity and never as 0 (which would read as a freeze).
     */
    public static final double CLOCK_RESOLUTION_SECONDS = 1e-6;

    /** A frame slower than this is a stutter at 30 frames a second, and one slower than this is a visible freeze. */
    public static final double SLOW_FRAME_SECONDS = 1.0 / 30.0;
    public static final double FREEZE_SECONDS = 0.100;

    public record Summary(int frames, int framesKept, double seconds, double averageFps, double minimumFps,
                          double low1PercentFps, double low01PercentFps,
                          double medianMs, double p95Ms, double p99Ms, double p999Ms, double maxMs,
                          int slowFrames, int freezes) {}

    private final double warmupSeconds;
    private double elapsed;
    private float[] kept = new float[1024];
    private int keptCount;
    private int frames;
    private double seconds;
    private int slow;
    private int freezes;

    public FrameStats(double warmupSeconds) {
        this.warmupSeconds = Math.max(0.0, warmupSeconds);
    }

    /** One more frame, which took {@code frameSeconds}. A frame that ends inside the warm-up is not counted. */
    public void add(double frameSeconds) {
        if (!(frameSeconds >= 0.0) || Double.isInfinite(frameSeconds)) return; // a clock that went backwards or a NaN is not a frame
        elapsed += frameSeconds;
        if (elapsed <= warmupSeconds) return;
        frames++;
        seconds += frameSeconds;
        if (frameSeconds > SLOW_FRAME_SECONDS) slow++;
        if (frameSeconds > FREEZE_SECONDS) freezes++;
        if (keptCount < MAX_KEPT) {
            if (keptCount == kept.length) kept = Arrays.copyOf(kept, Math.min(MAX_KEPT, kept.length * 2));
            kept[keptCount++] = (float) frameSeconds;
        }
    }

    /** Frames counted so far. */
    public int frames() {
        return frames;
    }

    public Summary summary() {
        if (frames == 0) return new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        float[] sorted = Arrays.copyOf(kept, keptCount);
        Arrays.sort(sorted);
        double max = sorted[keptCount - 1];
        double p99 = percentile(sorted, 990);
        double p999 = percentile(sorted, 999);
        // (the frame that ended the warm-up is longer than 0, so the seconds and the worst frame are above 0; the 99th and 99.9th
        // percentile can be 0 when nearly every frame took no time the clock could see - hence rate())
        return new Summary(frames, keptCount, seconds, frames / Math.max(seconds, frames * CLOCK_RESOLUTION_SECONDS), rate(max),
            rate(p99), rate(p999),
            percentile(sorted, 500) * 1000.0, percentile(sorted, 950) * 1000.0, p99 * 1000.0, p999 * 1000.0, max * 1000.0,
            slow, freezes);
    }

    /** Frames a second for a frame that took {@code seconds}, at most a frame per {@link #CLOCK_RESOLUTION_SECONDS}. */
    static double rate(double seconds) {
        return 1.0 / Math.max(seconds, CLOCK_RESOLUTION_SECONDS);
    }

    /**
     * The nearest-rank percentile of an ascending array: the value at rank ceil(thousandths * n / 1000) (990 = the 99th
     * percentile, 999 = the 99.9th). Worked in whole numbers on purpose: {@code Math.ceil(99.9 / 100.0 * 1000)} is 1000, not 999, because
     * 99.9 / 100.0 is a hair above 0.999 - the 99.9th percentile of 1000 frames would have been the worst frame.
     */
    static double percentile(float[] ascending, int thousandths) {
        int n = ascending.length;
        int rank = (int) ((thousandths * (long) n + 999L) / 1000L);
        return ascending[Math.max(1, Math.min(n, rank)) - 1];
    }

    /** The text of a stress report: the facts the application gives (what was loaded, how long it took), then the numbers. */
    public static String report(Summary s, Map<String, String> facts) {
        StringBuilder out = new StringBuilder("PBD stress report\n");
        for (Map.Entry<String, String> fact : facts.entrySet()) out.append(fact.getKey()).append(": ").append(fact.getValue()).append('\n');
        if (s.frames() == 0) {
            out.append("frames: 0 (the run ended inside the warm-up, or before the first frame)\n");
            return out.toString();
        }
        out.append(String.format(Locale.ROOT, "frames: %d over %.2f s%s\n", s.frames(), s.seconds(),
            s.framesKept() < s.frames() ? " (percentiles of the first " + s.framesKept() + ")" : ""));
        out.append(String.format(Locale.ROOT, "average: %.1f FPS\n", s.averageFps()));
        out.append(String.format(Locale.ROOT, "1%% low: %.1f FPS   0.1%% low: %.1f FPS   minimum: %.1f FPS\n", s.low1PercentFps(), s.low01PercentFps(), s.minimumFps()));
        out.append(String.format(Locale.ROOT, "frame time: median %.2f ms   95%% %.2f ms   99%% %.2f ms   99.9%% %.2f ms   worst %.2f ms\n",
            s.medianMs(), s.p95Ms(), s.p99Ms(), s.p999Ms(), s.maxMs()));
        out.append(String.format(Locale.ROOT, "slower than 30 FPS: %d frames   freezes (over 100 ms): %d\n", s.slowFrames(), s.freezes()));
        return out.toString();
    }
}
