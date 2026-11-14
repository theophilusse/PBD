package pbd.render;

/**
 * A camera path: where the camera is, and what it looks at, at every moment between a few keys.
 *
 * <p>Each of the six numbers (the position and the point looked at) follows its own <i>monotone cubic</i> curve through its keys
 * (Fritsch-Butland, as scipy's PCHIP): smooth, passing exactly through every key, and never going past the keys on either side of a stretch - so a
 * camera that holds still between two equal keys stays still instead of drifting, a path that runs straight stays straight, and a
 * circle of keys comes out as a circle (to a fraction of a percent). The speed is zero at the first and the last key: the take
 * starts and ends at rest. Before the first key the camera stands at the first, after the last key at the last.
 *
 * <p>The direction is derived from the two curves: from the position toward the point looked at, as a yaw and a pitch in the
 * convention of {@link FlyCamera} (yaw 0 looks along -Z, a positive yaw turns toward +X; pitch is limited to 89 degrees either way,
 * like the camera's own). Looking at a point rather than turning by angles means a path never has to care about a yaw that wraps.
 *
 * <p>Pure arithmetic: no window, no clock - the same time always gives the same pose.
 */
public final class CameraPath {

    /** Where the camera stands and which way it looks. */
    public record Pose(double x, double y, double z, double yaw, double pitch) {}

    /** The steepest look: 89 degrees up or down (as {@link FlyCamera} limits the mouse), so the "up" of the view is never degenerate. */
    public static final double PITCH_LIMIT = Math.toRadians(89.0);

    private final double[] times;
    private final double[][] values;   // [channel][key]: px py pz lx ly lz
    private final double[][] tangents; // [channel][key]

    /**
     * @param times    strictly increasing key times (at least one)
     * @param position {@code position[k]} = x, y, z of the camera at key k
     * @param look     {@code look[k]} = x, y, z of the point looked at at key k
     */
    public CameraPath(double[] times, double[][] position, double[][] look) {
        int n = times.length;
        if (n == 0) throw new IllegalArgumentException("a camera path needs at least one key");
        if (position.length != n || look.length != n) throw new IllegalArgumentException("one position and one look point per key");
        for (int k = 1; k < n; k++) {
            if (!(times[k] > times[k - 1])) throw new IllegalArgumentException("the key times must increase: " + times[k - 1] + " then " + times[k]);
        }
        this.times = times.clone();
        this.values = new double[6][n];
        this.tangents = new double[6][];
        for (int k = 0; k < n; k++) {
            for (int axis = 0; axis < 3; axis++) {
                values[axis][k] = position[k][axis];
                values[3 + axis][k] = look[k][axis];
            }
        }
        for (int channel = 0; channel < 6; channel++) tangents[channel] = monotoneTangents(this.times, values[channel]);
    }

    /** The time of the last key. */
    public double end() {
        return times[times.length - 1];
    }

    public int keys() {
        return times.length;
    }

    /** The pose at time {@code t} seconds. */
    public Pose at(double t) {
        double[] v = new double[6];
        int n = times.length;
        if (!(t > times[0]) || n == 1) { // (a time that is NaN is the start)
            for (int c = 0; c < 6; c++) v[c] = values[c][0];
        } else if (t >= times[n - 1]) {
            for (int c = 0; c < 6; c++) v[c] = values[c][n - 1];
        } else {
            int k = segment(t);
            double h = times[k + 1] - times[k];
            double s = (t - times[k]) / h;
            double s2 = s * s, s3 = s2 * s;
            double h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
            for (int c = 0; c < 6; c++) {
                v[c] = h00 * values[c][k] + h10 * h * tangents[c][k] + h01 * values[c][k + 1] + h11 * h * tangents[c][k + 1];
            }
        }
        double dx = v[3] - v[0], dy = v[4] - v[1], dz = v[5] - v[2];
        double[] angles = direction(dx, dy, dz);
        return new Pose(v[0], v[1], v[2], angles[0], angles[1]);
    }

    /** The index k of the segment [times[k], times[k+1]] that holds t (times[0] < t < times[n-1]): a binary search. */
    private int segment(double t) {
        int lo = 0, hi = times.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) lo = mid; else hi = mid;
        }
        return lo;
    }

    /**
     * The yaw and pitch of a look direction, in {@link FlyCamera}'s convention: forward = (cos p sin y, sin p, -cos p cos y). A zero
     * direction (the point looked at is where the camera stands) looks straight ahead, along -Z.
     */
    public static double[] direction(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 1.0e-9)) return new double[]{0.0, 0.0};
        double pitch = Math.asin(Math.max(-1.0, Math.min(1.0, dy / length)));
        pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, pitch));
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = horizontal < 1.0e-9 ? 0.0 : Math.atan2(dx, -dz);
        return new double[]{yaw, pitch};
    }

    /**
     * The tangents of the monotone cubic through (times[k], y[k]) (Fritsch and Butland, 1984): the harmonic mean of the slopes on both
     * sides of a key, zero where the curve turns round or stands still, and zero at both ends.
     */
    static double[] monotoneTangents(double[] t, double[] y) {
        int n = t.length;
        double[] m = new double[n];
        if (n < 3) return m; // two keys: zero tangents at both, a smooth step from one to the other
        double[] h = new double[n - 1];
        double[] delta = new double[n - 1];
        for (int k = 0; k < n - 1; k++) {
            h[k] = t[k + 1] - t[k];
            delta[k] = (y[k + 1] - y[k]) / h[k];
        }
        for (int k = 1; k < n - 1; k++) {
            if (delta[k - 1] * delta[k] <= 0) {
                m[k] = 0;
            } else {
                double w1 = 2 * h[k] + h[k - 1];
                double w2 = h[k] + 2 * h[k - 1];
                m[k] = (w1 + w2) / (w1 / delta[k - 1] + w2 / delta[k]);
            }
        }
        return m;
    }
}
