package pbd.voxel;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * A single detached chunk of voxel debris simulated as one rigid body:
 * gravity, a floor, and static colliders (a remaining stump, a table
 * underneath...), with friction, a little bounce, and sleep once it has
 * settled. Deliberately small and dependency-free (JOML only, no GL, no
 * physics library): the lever-arm roadmap entry records that no physics
 * engine exists in this codebase and that choosing one is an open
 * decision - this is NOT that engine, only what falling pieces need, and
 * it is small enough to replace wholesale if a real one is adopted.
 *
 * SHAPE. A chunk is an arbitrary voxel blob, but it is simulated through
 * a handful of SUPPORT POINTS: the extreme corners of its cubes along ~60
 * directions - a cheap stand-in for its convex hull. Resting contact,
 * toppling and sliding off an edge all come out of those points meeting a
 * collider. Inertia is that of its bounding box about its true centre of
 * mass (a slight overestimate for a lumpy blob - it only makes pieces a
 * touch more reluctant to spin, not unstable).
 *
 * FRAMES. Everything public is in WORLD space except the "mesh space" the
 * voxel mesh itself lives in (grid coordinates times voxelWorldSize, see
 * VoxelMeshBuilder): body space = mesh space minus {@link #comMesh}. To
 * draw a chunk: world = T(position) * R(orientation) * T(-comMesh).
 *
 * UNITS. Metres/seconds/kilograms-per-cubic-metre as far as the engine's
 * world units are metres. They are only conventions here: the scene scale
 * is whatever the author used, so treat GRAVITY as "world units per
 * second squared" and tune it alongside the scene's own scale if pieces
 * look floaty or leaden.
 */
public final class RigidDebris {

    public static final double GRAVITY = 9.81;
    public static final double RESTITUTION = 0.25;
    /** Impacts slower than this (m/s) do not bounce at all - resting contact must not jitter. */
    private static final double BOUNCE_THRESHOLD = 1.0;
    public static final double FRICTION = 0.6;
    private static final double LINEAR_DAMPING = 0.05;
    private static final double ANGULAR_DAMPING = 0.4;
    /** Physics step. Fixed small steps keep fast pieces from tunnelling through thin colliders. */
    private static final double MAX_SUBSTEP = 1.0 / 240.0;
    private static final int SOLVER_ITERATIONS = 8;
    /** Penetration tolerated before positional correction kicks in (avoids resting-contact jitter). */
    private static final double SLOP = 0.0005;
    private static final double SLEEP_LINEAR = 0.05, SLEEP_ANGULAR = 0.2, SLEEP_TIME = 0.6;

    /** Anything a chunk can land on. Colliders are static: they never move. */
    public interface Collider {
        /**
         * If the world-space point is inside the collider, writes the unit
         * outward normal (the direction to push the point out along) and
         * the penetration depth into out[0..3] (nx, ny, nz, depth) and
         * returns true.
         */
        boolean penetration(double x, double y, double z, double[] out);
    }

    /** The half-space below height y. */
    public static Collider floor(double y) {
        return (px, py, pz, out) -> {
            if (py >= y) return false;
            out[0] = 0; out[1] = 1; out[2] = 0; out[3] = y - py;
            return true;
        };
    }

    /** A solid oriented box. */
    public static Collider box(Vector3d center, Quaterniond rotation, Vector3d halfExtents) {
        final Vector3d c = new Vector3d(center);
        final Quaterniond q = new Quaterniond(rotation);
        final double hx = halfExtents.x, hy = halfExtents.y, hz = halfExtents.z;
        return (px, py, pz, out) -> {
            Vector3d l = new Vector3d(px - c.x, py - c.y, pz - c.z);
            q.transformInverse(l);
            double dx = hx - Math.abs(l.x), dy = hy - Math.abs(l.y), dz = hz - Math.abs(l.z);
            if (dx <= 0 || dy <= 0 || dz <= 0) return false;
            Vector3d n;
            double depth;
            if (dx <= dy && dx <= dz) { n = new Vector3d(Math.signum(l.x) == 0 ? 1 : Math.signum(l.x), 0, 0); depth = dx; }
            else if (dy <= dz) { n = new Vector3d(0, Math.signum(l.y) == 0 ? 1 : Math.signum(l.y), 0); depth = dy; }
            else { n = new Vector3d(0, 0, Math.signum(l.z) == 0 ? 1 : Math.signum(l.z)); depth = dz; }
            q.transform(n);
            out[0] = n.x; out[1] = n.y; out[2] = n.z; out[3] = depth;
            return true;
        };
    }

    /**
     * Real voxel geometry as a collider (the stump a chunk was cut from).
     * origin/rotation place mesh space in the world: world = origin +
     * rotation * mesh. A point inside a solid voxel is pushed out through
     * the nearest face - found by walking up to MAX_PUSH voxels along each
     * of the six axes until open air - which is exact for the flat faces
     * a cut leaves and a sensible answer for the lumpy crater rim.
     */
    public static Collider voxels(VoxelSolid solid, double voxelWorldSize, Vector3d origin, Quaterniond rotation) {
        final Vector3d o = new Vector3d(origin);
        final Quaterniond q = new Quaterniond(rotation);
        final int MAX_PUSH = 24;
        return (px, py, pz, out) -> {
            Vector3d m = new Vector3d(px - o.x, py - o.y, pz - o.z);
            q.transformInverse(m);
            double gx = m.x / voxelWorldSize, gy = m.y / voxelWorldSize, gz = m.z / voxelWorldSize;
            int vx = (int) Math.floor(gx), vy = (int) Math.floor(gy), vz = (int) Math.floor(gz);
            if (!solid.contains(vx, vy, vz)) return false;
            double fx = gx - vx, fy = gy - vy, fz = gz - vz;
            double best = Double.MAX_VALUE;
            int bestAxis = 0, bestSign = 1;
            for (int axis = 0; axis < 3; axis++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double f = axis == 0 ? fx : axis == 1 ? fy : fz;
                    double dist = sign > 0 ? (1 - f) : f; // distance to this voxel's own face, in voxels
                    int cx = vx, cy = vy, cz = vz;
                    int steps = 0;
                    while (steps < MAX_PUSH) {
                        if (axis == 0) cx += sign; else if (axis == 1) cy += sign; else cz += sign;
                        if (!solid.contains(cx, cy, cz)) break;
                        dist += 1;
                        steps++;
                    }
                    if (steps >= MAX_PUSH) continue; // buried too deep this way - not a real exit
                    if (dist < best) { best = dist; bestAxis = axis; bestSign = sign; }
                }
            }
            if (best == Double.MAX_VALUE) return false; // enclosed on every side: ignore rather than shoot the body away
            Vector3d n = new Vector3d(bestAxis == 0 ? bestSign : 0, bestAxis == 1 ? bestSign : 0, bestAxis == 2 ? bestSign : 0);
            q.transform(n);
            out[0] = n.x; out[1] = n.y; out[2] = n.z; out[3] = best * voxelWorldSize;
            return true;
        };
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Centre of mass, world space. */
    public final Vector3d position = new Vector3d();
    public final Quaterniond orientation = new Quaterniond();
    public final Vector3d velocity = new Vector3d();
    /** World-frame angular velocity, rad/s. */
    public final Vector3d angularVelocity = new Vector3d();
    public boolean asleep;

    /** Centre of mass in mesh space (where body-space origin sits in the voxel mesh). */
    public final Vector3d comMesh;
    /** Bounding box of the voxels relative to the centre of mass, body frame. */
    public final Vector3d boundsMin, boundsMax;
    public final double mass;

    private final double invMass;
    private final double invIx, invIy, invIz;
    private final double[] points; // body-space support points, x,y,z triples
    private double restTime;

    private RigidDebris(double mass, Vector3d comMesh, Vector3d boundsMin, Vector3d boundsMax, double[] points) {
        this.mass = mass;
        this.invMass = 1.0 / mass;
        this.comMesh = comMesh;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        double wx = boundsMax.x - boundsMin.x, wy = boundsMax.y - boundsMin.y, wz = boundsMax.z - boundsMin.z;
        // Box inertia about the centre of mass (see class doc). A floor
        // on each extent keeps a 1-voxel-thin piece from getting a zero
        // moment and an infinite inverse.
        double ex = Math.max(wx, 1e-3), ey = Math.max(wy, 1e-3), ez = Math.max(wz, 1e-3);
        this.invIx = 12.0 / (mass * (ey * ey + ez * ez));
        this.invIy = 12.0 / (mass * (ex * ex + ez * ez));
        this.invIz = 12.0 / (mass * (ex * ex + ey * ey));
        this.points = points;
    }

    /**
     * Builds a body from octree-aligned cubes. voxelWorldSize converts
     * grid units to world units; density is mass per unit volume (only
     * relative masses matter between chunks, so 1.0 is fine unless a
     * caller wants real kilograms).
     */
    public static RigidDebris fromEntries(List<int[]> entries, double voxelWorldSize, double density) {
        if (entries.isEmpty()) throw new IllegalArgumentException("no entries");
        double volume = 0, sx = 0, sy = 0, sz = 0;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int[] e : entries) {
            int s = e.length > 3 ? e[3] : 1;
            double v = (double) s * s * s;
            volume += v;
            sx += (e[0] + s / 2.0) * v; sy += (e[1] + s / 2.0) * v; sz += (e[2] + s / 2.0) * v;
            minX = Math.min(minX, e[0]); minY = Math.min(minY, e[1]); minZ = Math.min(minZ, e[2]);
            maxX = Math.max(maxX, e[0] + s); maxY = Math.max(maxY, e[1] + s); maxZ = Math.max(maxZ, e[2] + s);
        }
        double cx = sx / volume, cy = sy / volume, cz = sz / volume; // grid units
        Vector3d com = new Vector3d(cx, cy, cz).mul(voxelWorldSize);
        Vector3d bmin = new Vector3d(minX - cx, minY - cy, minZ - cz).mul(voxelWorldSize);
        Vector3d bmax = new Vector3d(maxX - cx, maxY - cy, maxZ - cz).mul(voxelWorldSize);
        double mass = volume * voxelWorldSize * voxelWorldSize * voxelWorldSize * density;

        // Support points: for each of the sample directions, the cube
        // corner reaching furthest along it. For a cube at centre c with
        // half-size h that corner is c + h*sign(d) and its projection is
        // c.d + h*(|dx|+|dy|+|dz|) - no need to test all 8 corners.
        double[][] dirs = sampleDirections();
        double[] best = new double[dirs.length];
        double[][] bestPt = new double[dirs.length][3];
        java.util.Arrays.fill(best, -Double.MAX_VALUE);
        for (int[] e : entries) {
            int s = e.length > 3 ? e[3] : 1;
            double h = s / 2.0;
            double ex = e[0] + h, ey = e[1] + h, ez = e[2] + h;
            for (int k = 0; k < dirs.length; k++) {
                double[] d = dirs[k];
                double p = ex * d[0] + ey * d[1] + ez * d[2] + h * (Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]));
                if (p > best[k]) {
                    best[k] = p;
                    bestPt[k][0] = ex + h * Math.signum(d[0]);
                    bestPt[k][1] = ey + h * Math.signum(d[1]);
                    bestPt[k][2] = ez + h * Math.signum(d[2]);
                }
            }
        }
        List<double[]> unique = new ArrayList<>();
        outer:
        for (double[] p : bestPt) {
            for (double[] u : unique) {
                if (Math.abs(u[0] - p[0]) < 1e-9 && Math.abs(u[1] - p[1]) < 1e-9 && Math.abs(u[2] - p[2]) < 1e-9) continue outer;
            }
            unique.add(p);
        }
        double[] pts = new double[unique.size() * 3];
        for (int i = 0; i < unique.size(); i++) {
            double[] p = unique.get(i);
            pts[i * 3] = (p[0] - cx) * voxelWorldSize;
            pts[i * 3 + 1] = (p[1] - cy) * voxelWorldSize;
            pts[i * 3 + 2] = (p[2] - cz) * voxelWorldSize;
        }
        return new RigidDebris(Math.max(mass, 1e-9), com, bmin, bmax, pts);
    }

    /** 26 grid directions plus a Fibonacci spiral over the sphere - deterministic, roughly even coverage. */
    private static double[][] sampleDirections() {
        List<double[]> dirs = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            double len = Math.sqrt(x * x + y * y + z * z);
            dirs.add(new double[]{x / len, y / len, z / len});
        }
        int n = 40;
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2.0 * (i + 0.5) / n;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double th = golden * i;
            dirs.add(new double[]{Math.cos(th) * r, y, Math.sin(th) * r});
        }
        return dirs.toArray(new double[0][]);
    }

    /** Number of support points (for tests). */
    public int supportPointCount() {
        return points.length / 3;
    }

    /**
     * Wakes a sleeping body - call it when something it was resting on
     * has changed (a stump carved away by a later hit). A body that finds
     * itself still supported simply falls asleep again after SLEEP_TIME.
     */
    public void wake() {
        asleep = false;
        restTime = 0;
    }

    /**
     * Radius of a sphere around the centre of mass that contains every
     * support point (so: the whole piece). For cheap "is this body near
     * that thing" broad-phase tests.
     */
    public double boundingRadius() {
        double r2 = 0;
        for (int i = 0; i < points.length; i += 3) {
            r2 = Math.max(r2, points[i] * points[i] + points[i + 1] * points[i + 1] + points[i + 2] * points[i + 2]);
        }
        return Math.sqrt(r2);
    }

    /**
     * True if, at the body's current pose, any support point sits inside
     * the collider by more than minDepth. Used to leave out colliders a
     * piece STARTS embedded in - a picture frame hung flush against a wall
     * box, a book sunk a few millimetres into a shelf - which would
     * otherwise shove the freshly detached piece out through the nearest
     * face the instant the simulation starts.
     */
    public boolean embeddedIn(Collider collider, double minDepth) {
        double[] out = new double[4];
        Vector3d r = new Vector3d();
        for (int i = 0; i < points.length; i += 3) {
            r.set(points[i], points[i + 1], points[i + 2]);
            orientation.transform(r);
            if (collider.penetration(position.x + r.x, position.y + r.y, position.z + r.z, out) && out[3] > minDepth) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** World point -> body space (centre of mass at the origin). */
    public Vector3d worldToBody(Vector3d worldPoint) {
        Vector3d b = new Vector3d(worldPoint).sub(position);
        orientation.transformInverse(b);
        return b;
    }

    /** Body space -> world. */
    public Vector3d bodyToWorld(Vector3d bodyPoint) {
        Vector3d w = new Vector3d(bodyPoint);
        orientation.transform(w);
        return w.add(position);
    }

    /** Lowest world-space Y over the support points. */
    public double lowestPointY() {
        double lo = Double.MAX_VALUE;
        Vector3d p = new Vector3d();
        for (int i = 0; i < points.length; i += 3) {
            p.set(points[i], points[i + 1], points[i + 2]);
            orientation.transform(p);
            lo = Math.min(lo, p.y + position.y);
        }
        return lo;
    }

    /**
     * Ray vs this body's bounding box (in body space, so it rotates with
     * the body). Returns the distance along the ray (direction is
     * normalized internally) to the first hit, 0 if the origin is inside,
     * or -1 on a miss.
     */
    public double intersectRay(Vector3d origin, Vector3d dir) {
        Vector3d o = worldToBody(origin);
        Vector3d d = new Vector3d(dir).normalize();
        orientation.transformInverse(d);
        double tMin = 0, tMax = Double.MAX_VALUE;
        double[] oo = {o.x, o.y, o.z}, dd = {d.x, d.y, d.z};
        double[] lo = {boundsMin.x, boundsMin.y, boundsMin.z}, hi = {boundsMax.x, boundsMax.y, boundsMax.z};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(dd[a]) < 1e-12) {
                if (oo[a] < lo[a] || oo[a] > hi[a]) return -1;
                continue;
            }
            double t1 = (lo[a] - oo[a]) / dd[a], t2 = (hi[a] - oo[a]) / dd[a];
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        return tMin;
    }

    /** Total mechanical energy (kinetic + rotational + potential relative to y=0) - for tests. */
    public double totalEnergy() {
        Vector3d w = new Vector3d(angularVelocity);
        orientation.transformInverse(w); // body-frame angular velocity
        double rot = 0.5 * (w.x * w.x / invIx + w.y * w.y / invIy + w.z * w.z / invIz);
        return 0.5 * mass * velocity.lengthSquared() + rot + mass * GRAVITY * position.y;
    }

    public boolean isFinite() {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
            && Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z)
            && Double.isFinite(angularVelocity.x) && Double.isFinite(angularVelocity.y) && Double.isFinite(angularVelocity.z)
            && Double.isFinite(orientation.x) && Double.isFinite(orientation.y)
            && Double.isFinite(orientation.z) && Double.isFinite(orientation.w);
    }

    // ------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------

    /** Advances the body by dt seconds (split into fixed sub-steps). A sleeping body is left untouched. */
    public void step(double dt, List<Collider> colliders) {
        if (asleep || dt <= 0) return;
        int sub = Math.max(1, (int) Math.ceil(dt / MAX_SUBSTEP));
        double h = dt / sub;
        for (int s = 0; s < sub; s++) {
            substep(h, colliders);
            if (!isFinite()) { // never let a numerical blow-up propagate into the renderer
                velocity.set(0); angularVelocity.set(0);
                asleep = true;
                return;
            }
        }
        // Sleep once nearly still for a while (checked per call, not per sub-step).
        if (velocity.length() < SLEEP_LINEAR && angularVelocity.length() < SLEEP_ANGULAR) {
            restTime += dt;
            if (restTime >= SLEEP_TIME) {
                asleep = true;
                velocity.set(0);
                angularVelocity.set(0);
            }
        } else {
            restTime = 0;
        }
    }

    private void substep(double h, List<Collider> colliders) {
        velocity.y -= GRAVITY * h;
        velocity.mul(Math.max(0, 1 - LINEAR_DAMPING * h));
        angularVelocity.mul(Math.max(0, 1 - ANGULAR_DAMPING * h));

        position.fma(h, velocity);
        double wx = angularVelocity.x, wy = angularVelocity.y, wz = angularVelocity.z;
        double qx = orientation.x, qy = orientation.y, qz = orientation.z, qw = orientation.w;
        double k = 0.5 * h;
        orientation.set(
            qx + k * (wx * qw + wy * qz - wz * qy),
            qy + k * (wy * qw + wz * qx - wx * qz),
            qz + k * (wz * qw + wx * qy - wy * qx),
            qw + k * (-wx * qx - wy * qy - wz * qz));
        orientation.normalize();

        solveContacts(colliders);
    }

    private void solveContacts(List<Collider> colliders) {
        // 1. Detect: every support point against every collider.
        double[] out = new double[4];
        List<double[]> contacts = new ArrayList<>(); // {rx,ry,rz, nx,ny,nz, depth, bias, jn, jt1, jt2}
        Vector3d r = new Vector3d();
        double deepest = 0;
        double[] deepestN = null;
        for (Collider c : colliders) {
            for (int i = 0; i < points.length; i += 3) {
                r.set(points[i], points[i + 1], points[i + 2]);
                orientation.transform(r);
                double wx = position.x + r.x, wy = position.y + r.y, wz = position.z + r.z;
                if (!c.penetration(wx, wy, wz, out)) continue;
                double[] ct = {r.x, r.y, r.z, out[0], out[1], out[2], out[3], 0, 0, 0, 0};
                // Velocity of the contact point BEFORE the solver runs decides whether this is an impact worth bouncing from.
                double vnx = velocity.x + angularVelocity.y * r.z - angularVelocity.z * r.y;
                double vny = velocity.y + angularVelocity.z * r.x - angularVelocity.x * r.z;
                double vnz = velocity.z + angularVelocity.x * r.y - angularVelocity.y * r.x;
                double vn0 = vnx * out[0] + vny * out[1] + vnz * out[2];
                ct[7] = (-vn0 > BOUNCE_THRESHOLD) ? -RESTITUTION * vn0 : 0;
                contacts.add(ct);
                if (out[3] > deepest) { deepest = out[3]; deepestN = new double[]{out[0], out[1], out[2]}; }
            }
        }
        if (contacts.isEmpty()) return;

        // 2. Velocity solve: sequential impulses with accumulated clamping.
        Vector3d rxn = new Vector3d(), t1 = new Vector3d(), t2 = new Vector3d(), tmp = new Vector3d(), imp = new Vector3d();
        for (int it = 0; it < SOLVER_ITERATIONS; it++) {
            for (double[] ct : contacts) {
                r.set(ct[0], ct[1], ct[2]);
                Vector3d n = new Vector3d(ct[3], ct[4], ct[5]);

                // normal
                double vn = contactVelocity(r).dot(n);
                double denom = invMass + angularTerm(r, n);
                double dj = (ct[7] - vn) / denom;
                double newJn = Math.max(0, ct[8] + dj);
                dj = newJn - ct[8];
                ct[8] = newJn;
                imp.set(n).mul(dj);
                applyImpulse(r, imp);

                // friction along two tangents (Coulomb: accumulated tangential impulse capped at mu * normal impulse)
                if (Math.abs(n.x) < 0.9) tmp.set(1, 0, 0); else tmp.set(0, 1, 0);
                t1.set(n).cross(tmp).normalize();
                t2.set(n).cross(t1).normalize();
                double limit = FRICTION * ct[8];
                for (int axis = 0; axis < 2; axis++) {
                    Vector3d t = axis == 0 ? t1 : t2;
                    double vt = contactVelocity(r).dot(t);
                    double dt = -vt / (invMass + angularTerm(r, t));
                    int slot = 9 + axis;
                    double newJt = Math.max(-limit, Math.min(limit, ct[slot] + dt));
                    dt = newJt - ct[slot];
                    ct[slot] = newJt;
                    imp.set(t).mul(dt);
                    applyImpulse(r, imp);
                }
            }
        }

        // 3. Position correction: push out along the deepest contact's normal.
        if (deepestN != null && deepest > SLOP) {
            double push = (deepest - SLOP);
            position.add(deepestN[0] * push, deepestN[1] * push, deepestN[2] * push);
        }
    }

    private Vector3d contactVelocity(Vector3d r) {
        return new Vector3d(
            velocity.x + angularVelocity.y * r.z - angularVelocity.z * r.y,
            velocity.y + angularVelocity.z * r.x - angularVelocity.x * r.z,
            velocity.z + angularVelocity.x * r.y - angularVelocity.y * r.x);
    }

    /** n . ((I^-1 (r x n)) x r) - the rotational part of the effective mass along n. */
    private double angularTerm(Vector3d r, Vector3d n) {
        Vector3d rn = new Vector3d(r).cross(n);
        Vector3d b = new Vector3d(rn);
        orientation.transformInverse(b);
        b.set(b.x * invIx, b.y * invIy, b.z * invIz);
        orientation.transform(b);
        b.cross(r);
        return b.dot(n);
    }

    private void applyImpulse(Vector3d r, Vector3d impulse) {
        velocity.fma(invMass, impulse);
        Vector3d torque = new Vector3d(r).cross(impulse);
        orientation.transformInverse(torque);
        torque.set(torque.x * invIx, torque.y * invIy, torque.z * invIz);
        orientation.transform(torque);
        angularVelocity.add(torque);
    }
}
