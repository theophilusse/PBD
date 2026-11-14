package pbd.voxel;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import pbd.format.BreakProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * Turns the volume a hit took out of an object into the particles that fly out of it. Instead of the crater simply
 * vanishing, the very cubes it removed are sampled - a particle starts at a random point INSIDE the removed volume, so the
 * debris comes out of the hole, and a crater twice as large throws twice as much - and each one is thrown away from the
 * impact, pushed on along the line of fire, with a little lift.
 *
 * HOW MUCH, HOW BIG, HOW FAST, AND IN WHAT COLOUR is the material's business ({@link BreakProfile}), not the primitive's:
 * the count, the size, the speed, the life, the gravity and the bounce all come from the profile of the material the object
 * is made of, and the colour of a particle is, unless the material sets a tint, the colour of the surface where it broke
 * off (the same function that colours the voxel faces, so a textured wall sheds chips of its own texture).
 *
 * Everything is drawn from a generator seeded by the caller, so the same hit always throws the same particles; and nothing
 * here touches GL.
 */
public final class DebrisEmitter {

    private DebrisEmitter() {}

    /** Volume, in unit voxels, of a crater of the nominal radius: {@link BreakProfile#debrisCount} is the count for a crater this big. */
    public static final double REFERENCE_CRATER_VOXELS = 4.0 / 3.0 * Math.PI
        * DestructionWorld.CRATER_RADIUS_VOXELS * DestructionWorld.CRATER_RADIUS_VOXELS * DestructionWorld.CRATER_RADIUS_VOXELS;
    /** A hit that took very little still throws a handful; one that took a great deal does not throw without limit. */
    public static final double MIN_CRATER_FRACTION = 0.15, MAX_CRATER_FRACTION = 1.5;

    /** Spread of the particle size / speed / life around the profile's mean, as (low, high) multiples. */
    public static final double SIZE_LOW = 0.6, SIZE_SPAN = 0.8;
    public static final double SPEED_LOW = 0.5, SPEED_SPAN = 1.0;
    public static final double LIFE_LOW = 0.7, LIFE_SPAN = 0.6;
    /** Share of the speed pushed on along the line of fire (negative end: thrown back at the shooter), and of lift. */
    static final double SHOT_PUSH = 0.6, SHOT_BACK = 0.25, LIFT = 0.35;

    /** How many particles a crater that took {@code removedVoxels} voxels throws, for this material: proportional to the volume, between 15 % and 150 % of the profile's count, and at least one while there is a count at all. */
    public static int craterCount(BreakProfile p, long removedVoxels) {
        if (p.debrisCount <= 0 || removedVoxels <= 0) return 0;
        double fraction = Math.max(MIN_CRATER_FRACTION, Math.min(MAX_CRATER_FRACTION, removedVoxels / REFERENCE_CRATER_VOXELS));
        return Math.max(1, (int) Math.round(p.debrisCount * fraction));
    }

    /** How many particles a piece parting throws: the profile's break count whenever any thin connection was broken, none otherwise. */
    public static int breakCount(BreakProfile p, long splinterVoxels) {
        return splinterVoxels > 0 ? Math.max(0, p.breakCount) : 0;
    }

    /**
     * Where a hit happened, for the debris of it: the grid of the object (cell size, where its corner sits in the world, how it is
     * turned), the point of impact (world) and the direction of the shot (world, any length; zero = none).
     */
    public record Frame(double voxelWorldSize, Vector3d gridOrigin, Quaterniond gridRotation, Vector3d impact, Vector3d shot) {}

    /**
     * Everything one hit throws up, in two bursts. First what lay at rest near the impact is woken ({@code wakeRadius}): it may have
     * just lost what it lay on. Then the CRATER's burst: {@link #craterCount} particles out of the cubes the crater took out AND
     * the crumbs too small to fall ({@code craterVoxels} is the volume of both: the count follows it). Then the SPLINTERS' burst,
     * when a connection broke: the material's {@link #breakCount} particles out of the splinter cubes, drawn from a generator of
     * its own ({@code seed + 1}) so that it is not a copy of the crater's.
     *
     * @return {particles of the crater burst, particles of the splinter burst}
     */
    public static int[] throwHit(ParticleSystem system, List<int[]> removed, List<int[]> crumbs, long craterVoxels, VoxelFracture.Result fracture,
                                 BreakProfile p, BiFunction<int[], float[], float[]> colour, Frame frame, double wakeRadius, long seed) {
        system.wakeNear(frame.impact().x, frame.impact().y, frame.impact().z, wakeRadius); // what lay on the ground here may have just lost it
        List<int[]> source = removed;
        if (!crumbs.isEmpty()) {
            source = new ArrayList<>(removed.size() + crumbs.size());
            source.addAll(removed);
            source.addAll(crumbs);
        }
        int crater = burst(system, source, frame.voxelWorldSize(), frame.gridOrigin(), frame.gridRotation(), frame.impact(), frame.shot(),
            craterCount(p, craterVoxels), p, colour, seed);
        int splinters = burst(system, fracture.splinters, frame.voxelWorldSize(), frame.gridOrigin(), frame.gridRotation(), frame.impact(), frame.shot(),
            breakCount(p, fracture.splinterVoxels), p, colour, seed + 1);
        return new int[]{crater, splinters};
    }

    /**
     * Throws {@code count} particles out of the volume {@code cubes} (octree-aligned cubes in a voxel grid, as
     * {@code int[]{x, y, z, size}}). The grid sits in the world at {@code gridOrigin}, turned by {@code gridRotation}:
     * world = origin + rotation * (cell * voxelWorldSize).
     *
     * @param impact the point the debris is thrown away from (world)
     * @param shot   direction of the hit in the world (any length; zero = none), which pushes the debris on
     * @param colour what {@link DestructionWorld.Style#colorFn} is: (the cube, its position in mesh space) to an RGB triple; may be null
     * @return the number of particles actually added
     */
    public static int burst(ParticleSystem system, List<int[]> cubes, double voxelWorldSize, Vector3d gridOrigin, Quaterniond gridRotation,
                            Vector3d impact, Vector3d shot, int count, BreakProfile p, BiFunction<int[], float[], float[]> colour, long seed) {
        return burst(system, cubes, voxelWorldSize, gridOrigin, gridRotation, impact, shot, count, p, colour, new Random(seed));
    }

    /**
     * The same, drawing from {@code rng} instead of a generator of its own: what lets a test hand over a source that is not
     * random (every draw 0, or 0.5, or just under 1) and read every formula below at the ends and in the middle of its range.
     */
    public static int burst(ParticleSystem system, List<int[]> cubes, double voxelWorldSize, Vector3d gridOrigin, Quaterniond gridRotation,
                            Vector3d impact, Vector3d shot, int count, BreakProfile p, BiFunction<int[], float[], float[]> colour, Random rng) {
        if (count <= 0 || cubes.isEmpty()) return 0;
        double[] cumulative = new double[cubes.size()];
        double total = 0;
        for (int i = 0; i < cubes.size(); i++) {
            int[] c = cubes.get(i);
            double s = c.length > 3 ? c[3] : 1;
            total += s * s * s;
            cumulative[i] = total;
        }
        if (!(total > 0)) return 0;

        Vector3d shotDir = new Vector3d(shot);
        if (shotDir.lengthSquared() > 1e-12) shotDir.normalize(); else shotDir.set(0, 0, 0);
        int added = 0;
        ParticleSystem.Spec spec = new ParticleSystem.Spec();
        Vector3d pos = new Vector3d(), out = new Vector3d();
        float[] meshPos = new float[3];
        for (int k = 0; k < count; k++) {
            // a point uniformly inside the removed volume: a cube chosen by its volume, a point in it
            int idx = pick(cumulative, rng.nextDouble() * total);
            int[] cube = cubes.get(idx);
            double s = cube.length > 3 ? cube[3] : 1;
            double gx = cube[0] + rng.nextDouble() * s, gy = cube[1] + rng.nextDouble() * s, gz = cube[2] + rng.nextDouble() * s;
            pos.set(gx * voxelWorldSize, gy * voxelWorldSize, gz * voxelWorldSize);
            gridRotation.transform(pos).add(gridOrigin);

            out.set(pos).sub(impact);
            if (out.lengthSquared() < 1e-12) {
                out.set(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5, rng.nextDouble() - 0.5);
                if (out.lengthSquared() < 1e-12) out.set(0, 1, 0);
            }
            out.normalize();

            double speed = p.debrisSpeed * (SPEED_LOW + SPEED_SPAN * rng.nextDouble());
            double push = p.debrisSpeed * SHOT_PUSH * (rng.nextDouble() - SHOT_BACK);
            double lift = p.debrisSpeed * LIFT * rng.nextDouble();
            double jitter = 0.4 * speed;
            spec.x = pos.x; spec.y = pos.y; spec.z = pos.z;
            spec.vx = out.x * speed + shotDir.x * push + (rng.nextDouble() - 0.5) * jitter;
            spec.vy = out.y * speed + shotDir.y * push + lift + (rng.nextDouble() - 0.5) * jitter;
            spec.vz = out.z * speed + shotDir.z * push + (rng.nextDouble() - 0.5) * jitter;

            spec.size = (float) (p.debrisSize * (SIZE_LOW + SIZE_SPAN * rng.nextDouble()));
            spec.lifetime = (float) (p.debrisLifetime * (LIFE_LOW + LIFE_SPAN * rng.nextDouble()));
            spec.gravity = p.debrisGravity;
            spec.bounce = p.debrisBounce;

            // colour: the material's tint, or the surface the chip came off
            float[] rgb;
            if (p.debrisColor != null) {
                rgb = p.debrisColor;
            } else {
                meshPos[0] = (float) (gx * voxelWorldSize); meshPos[1] = (float) (gy * voxelWorldSize); meshPos[2] = (float) (gz * voxelWorldSize);
                float[] sampled = colour != null ? colour.apply(cube, meshPos) : null;
                rgb = sampled != null && sampled.length >= 3 ? sampled : new float[]{0.6f, 0.6f, 0.6f};
            }
            float shade = (float) (0.85 + 0.3 * rng.nextDouble());
            spec.r = clamp01(rgb[0] * shade); spec.g = clamp01(rgb[1] * shade); spec.b = clamp01(rgb[2] * shade);

            // a random turning axis, speed and starting angle
            double ax = rng.nextGaussian(), ay = rng.nextGaussian(), az = rng.nextGaussian();
            double al = Math.sqrt(ax * ax + ay * ay + az * az);
            if (al < 1e-9) { ax = 0; ay = 1; az = 0; al = 1; }
            spec.axisX = (float) (ax / al); spec.axisY = (float) (ay / al); spec.axisZ = (float) (az / al);
            spec.spin = (float) (2 + 10 * rng.nextDouble());
            spec.angle = (float) (rng.nextDouble() * Math.PI * 2);

            if (system.emit(spec)) added++;
        }
        return added;
    }

    /** First index whose running total exceeds {@code target} (the last one when rounding leaves nothing above it). */
    static int pick(double[] cumulative, double target) {
        int lo = 0, hi = cumulative.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative[mid] > target) hi = mid; else lo = mid + 1;
        }
        return lo;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
