package pbd.voxel;

import java.util.List;

/**
 * The little cubes a hit throws up: chips of wood from a crater, splinters from a piece that breaks away. Each one is a
 * point mass with a size, a colour and a lifetime - far lighter than a voxel chunk (no mesh of its own, no rigid body), so
 * a few hundred can fly at once and the renderer draws every one with the same shared cube.
 *
 * Pure and GL-free like the rest of the destruction chain: PbdRenderer only reads {@link #view} and draws, so everything
 * here (gravity, drag, bouncing off the floor / boxes / stumps, coming to rest, fading, the cap) is checked under
 * tools/regression without a window.
 *
 * PHYSICS. A particle falls under {@link RigidDebris#GRAVITY} (times its own multiple), loses a little speed to the air,
 * and is tested against the same static colliders the falling chunks use - the floor, the boxes of nearby cubes and
 * cylinders, the voxels of what is left of a destroyed instance. Its contact point is the middle of its bottom face, so a
 * chip lies ON the floor, not half sunk into it. A contact pushes it out along the surface normal, takes the part of its
 * velocity that points into the surface (kept in part, as a bounce, above {@link #MIN_BOUNCE_SPEED}), and takes off what
 * slides along the surface by Coulomb friction: at most {@link #SURFACE_FRICTION} times what the surface just took off the
 * speed into it, so a chip sliding on a floor slows at about 0.35 g whatever the time step, and a hard landing scrubs off
 * more than a resting contact. Once it is nearly still on a surface that faces up it comes to rest and costs nothing more
 * until {@link #wakeNear} says its support may be gone; one that touches a wall in the air does not stick to it.
 *
 * LIFE. A particle lives for the time it was given, shrinking over the last {@link #FADE_FRACTION} of it; the system holds at
 * most {@link #MAX_PARTICLES}, and a particle added to a full system replaces the one closest to the end of its life.
 *
 * Nothing here is random: the spin and everything else a particle needs is handed over by the emitter ({@link DebrisEmitter}),
 * which draws from a seeded generator, so the same hit always throws the same particles.
 */
public final class ParticleSystem {

    /** Most particles alive at once. */
    public static final int MAX_PARTICLES = 1500;
    /** Longest step simulated in one call - a frame hitch must not become one giant step. */
    static final double MAX_DT = 1.0 / 20.0;
    /** Sub-step: a chip at the largest speed a material may ask for (30 m/s) moves 25 cm in one, far less than a floor or a wall under it is deep. */
    static final double SUBSTEP = 1.0 / 120.0;
    /** Share of its speed a particle loses to the air per second. */
    static final double AIR_DRAG = 0.4;
    /** Coulomb coefficient of the surfaces: the speed along a surface is reduced by this times the speed the surface took off the way in. */
    static final double SURFACE_FRICTION = 0.35;
    /** A particle slower than this (m/s) after a contact with a surface that faces up stays where it is. */
    static final double REST_SPEED = 0.2;
    /** A surface carries a particle when its normal points up at least this much (cos of 60 degrees from the vertical). */
    static final double SUPPORT_NORMAL_Y = 0.5;
    /** Impacts slower than this (m/s) do not bounce - resting contact must not jitter. */
    static final double MIN_BOUNCE_SPEED = 0.6;
    /** The last part of its life during which a particle shrinks to nothing. */
    public static final double FADE_FRACTION = 0.25;

    /** Everything the emitter decides about one particle. */
    public static final class Spec {
        public double x, y, z, vx, vy, vz;
        public float size, r, g, b;
        public float lifetime;
        public float gravity = 1f;
        public float bounce;
        /** Unit axis the cube turns about, its speed (rad/s) and its angle at birth. */
        public float axisX = 0f, axisY = 1f, axisZ = 0f, spin, angle;
    }

    /** One particle as the renderer draws it (filled by {@link #view}). */
    public static final class View {
        public double x, y, z;
        /** Edge length to draw, already shrunk by the fade. */
        public float size;
        public float r, g, b;
        public float axisX, axisY, axisZ, angle;
    }

    private int count;
    private double[] x = new double[64], y = new double[64], z = new double[64];
    private double[] vx = new double[64], vy = new double[64], vz = new double[64];
    private float[] size = new float[64], red = new float[64], green = new float[64], blue = new float[64];
    private float[] age = new float[64], life = new float[64], gravity = new float[64], bounce = new float[64];
    private float[] axisX = new float[64], axisY = new float[64], axisZ = new float[64], spin = new float[64], angle = new float[64];
    private boolean[] resting = new boolean[64];

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void clear() {
        count = 0;
    }

    private void ensureCapacity(int n) {
        if (n <= x.length) return;
        int cap = Math.min(MAX_PARTICLES, Math.max(n, x.length * 2));
        x = java.util.Arrays.copyOf(x, cap); y = java.util.Arrays.copyOf(y, cap); z = java.util.Arrays.copyOf(z, cap);
        vx = java.util.Arrays.copyOf(vx, cap); vy = java.util.Arrays.copyOf(vy, cap); vz = java.util.Arrays.copyOf(vz, cap);
        size = java.util.Arrays.copyOf(size, cap); red = java.util.Arrays.copyOf(red, cap);
        green = java.util.Arrays.copyOf(green, cap); blue = java.util.Arrays.copyOf(blue, cap);
        age = java.util.Arrays.copyOf(age, cap); life = java.util.Arrays.copyOf(life, cap);
        gravity = java.util.Arrays.copyOf(gravity, cap); bounce = java.util.Arrays.copyOf(bounce, cap);
        axisX = java.util.Arrays.copyOf(axisX, cap); axisY = java.util.Arrays.copyOf(axisY, cap);
        axisZ = java.util.Arrays.copyOf(axisZ, cap); spin = java.util.Arrays.copyOf(spin, cap);
        angle = java.util.Arrays.copyOf(angle, cap); resting = java.util.Arrays.copyOf(resting, cap);
    }

    /**
     * Adds a particle. A spec that cannot be simulated (a position or velocity that is not a finite number, no size, no
     * time to live) is refused and nothing is added: returns whether the particle was added.
     */
    public boolean emit(Spec s) {
        if (!(s.size > 0f) || !(s.lifetime > 0f)) return false;
        if (!Double.isFinite(s.x) || !Double.isFinite(s.y) || !Double.isFinite(s.z)
            || !Double.isFinite(s.vx) || !Double.isFinite(s.vy) || !Double.isFinite(s.vz)) return false;
        int i;
        if (count < MAX_PARTICLES) {
            i = count++;
            ensureCapacity(count);
        } else {
            i = oldest();
        }
        x[i] = s.x; y[i] = s.y; z[i] = s.z;
        vx[i] = s.vx; vy[i] = s.vy; vz[i] = s.vz;
        size[i] = s.size; red[i] = s.r; green[i] = s.g; blue[i] = s.b;
        age[i] = 0f; life[i] = s.lifetime; gravity[i] = s.gravity; bounce[i] = s.bounce;
        axisX[i] = s.axisX; axisY[i] = s.axisY; axisZ[i] = s.axisZ; spin[i] = s.spin; angle[i] = s.angle;
        resting[i] = false;
        return true;
    }

    /** The index of the particle that has used the largest share of its life (the lowest index among equals, so the choice never depends on anything but the particles themselves). */
    private int oldest() {
        int best = 0;
        float bestShare = -1f;
        for (int i = 0; i < count; i++) {
            float share = age[i] / life[i];
            if (share > bestShare) {
                bestShare = share;
                best = i;
            }
        }
        return best;
    }

    private void remove(int i) {
        int last = --count;
        if (i == last) return;
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
        size[i] = size[last]; red[i] = red[last]; green[i] = green[last]; blue[i] = blue[last];
        age[i] = age[last]; life[i] = life[last]; gravity[i] = gravity[last]; bounce[i] = bounce[last];
        axisX[i] = axisX[last]; axisY[i] = axisY[last]; axisZ[i] = axisZ[last]; spin[i] = spin[last]; angle[i] = angle[last];
        resting[i] = resting[last];
    }

    /** Fills {@code out} with particle i as it is to be drawn. */
    public void view(int i, View out) {
        out.x = x[i]; out.y = y[i]; out.z = z[i];
        out.size = size[i] * scaleAt(age[i], life[i]);
        out.r = red[i]; out.g = green[i]; out.b = blue[i];
        out.axisX = axisX[i]; out.axisY = axisY[i]; out.axisZ = axisZ[i]; out.angle = angle[i];
    }

    /** The share of its size a particle is drawn with at this age: 1 for most of a life, falling straight to 0 over the last {@link #FADE_FRACTION} of it (and 0 from the end of it on). */
    public static float scaleAt(float age, float life) {
        float left = (life - age) / life;
        if (left >= FADE_FRACTION) return 1f;
        return Math.max(0f, (float) (left / FADE_FRACTION));
    }

    public double velocityX(int i) { return vx[i]; }
    public double velocityY(int i) { return vy[i]; }
    public double velocityZ(int i) { return vz[i]; }
    public float ageOf(int i) { return age[i]; }
    public float lifeOf(int i) { return life[i]; }
    public boolean isResting(int i) { return resting[i]; }
    /** The gravity multiple, the bounce and the turning speed (rad/s) the emitter gave particle i. */
    public float gravityOf(int i) { return gravity[i]; }
    public float bounceOf(int i) { return bounce[i]; }
    public float spinOf(int i) { return spin[i]; }

    /** Wakes the resting particles within {@code radius} of a point: something they lay on may have just been destroyed. */
    public void wakeNear(double px, double py, double pz, double radius) {
        double r2 = radius * radius;
        for (int i = 0; i < count; i++) {
            double dx = x[i] - px, dy = y[i] - py, dz = z[i] - pz;
            if (resting[i] && dx * dx + dy * dy + dz * dz <= r2) resting[i] = false;
        }
    }

    /** The bounding sphere {cx, cy, cz, radius} of every live particle, or null when there are none (what picks the colliders worth testing). */
    public double[] bounds() {
        if (count == 0) return null;
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0; i < count; i++) {
            x0 = Math.min(x0, x[i]); x1 = Math.max(x1, x[i]);
            y0 = Math.min(y0, y[i]); y1 = Math.max(y1, y[i]);
            z0 = Math.min(z0, z[i]); z1 = Math.max(z1, z[i]);
        }
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
        double dx = x1 - cx, dy = y1 - cy, dz = z1 - cz;
        return new double[]{cx, cy, cz, Math.sqrt(dx * dx + dy * dy + dz * dz)};
    }

    /**
     * Advances every particle by dt seconds (at most {@link #MAX_DT} per call, in sub-steps of at most {@link #SUBSTEP}).
     * A particle that has used up its life, or whose position stopped being a finite number, is dropped.
     */
    public void step(double dt, List<RigidDebris.Collider> colliders) {
        if (count == 0 || !(dt > 0)) return;
        double total = Math.min(dt, MAX_DT);
        int sub = Math.max(1, (int) Math.ceil(total / SUBSTEP));
        double h = total / sub;
        double[] hit = new double[4];
        for (int s = 0; s < sub; s++) {
            for (int i = 0; i < count; i++) {
                if (!resting[i]) move(i, h, colliders, hit);
            }
        }
        float step = (float) total;
        for (int i = count - 1; i >= 0; i--) {
            age[i] += step;
            if (!resting[i]) angle[i] += spin[i] * step;
            boolean lost = !(Double.isFinite(x[i]) && Double.isFinite(y[i]) && Double.isFinite(z[i]));
            if (lost || age[i] >= life[i]) remove(i);
        }
    }

    private void move(int i, double h, List<RigidDebris.Collider> colliders, double[] hit) {
        vy[i] -= RigidDebris.GRAVITY * gravity[i] * h;
        double keep = Math.max(0, 1 - AIR_DRAG * h);
        vx[i] *= keep; vy[i] *= keep; vz[i] *= keep;
        x[i] += vx[i] * h; y[i] += vy[i] * h; z[i] += vz[i] * h;
        double half = size[i] * 0.5;
        boolean supported = false;
        for (RigidDebris.Collider c : colliders) {
            if (!c.penetration(x[i], y[i] - half, z[i], hit)) continue;
            double nx = hit[0], ny = hit[1], nz = hit[2], depth = hit[3];
            if (ny >= SUPPORT_NORMAL_Y) supported = true;
            x[i] += nx * depth; y[i] += ny * depth; z[i] += nz * depth;
            double vn = vx[i] * nx + vy[i] * ny + vz[i] * nz;
            if (vn < 0) {
                // the normal speed is taken away (and given back as a bounce above MIN_BOUNCE_SPEED); jn is how much the surface changed it
                double rebound = (-vn > MIN_BOUNCE_SPEED) ? bounce[i] : 0.0;
                double jn = -vn * (1 + rebound);
                vx[i] += jn * nx; vy[i] += jn * ny; vz[i] += jn * nz;
                // Coulomb friction along the surface: at most SURFACE_FRICTION * jn comes off the sliding speed
                double tvn = vx[i] * nx + vy[i] * ny + vz[i] * nz;
                double tx = vx[i] - tvn * nx, ty = vy[i] - tvn * ny, tz = vz[i] - tvn * nz;
                double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl > 0) {
                    double slow = Math.min(tl, SURFACE_FRICTION * jn) / tl;
                    vx[i] -= tx * slow; vy[i] -= ty * slow; vz[i] -= tz * slow;
                }
            }
        }
        if (supported && vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i] < REST_SPEED * REST_SPEED) {
            vx[i] = 0; vy[i] = 0; vz[i] = 0;
            resting[i] = true;
        }
    }
}
