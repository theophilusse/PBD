package pbd.voxel;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decides what a destruction hit turns into - which cubes stay attached to
 * what is left of the object ("the base") and which become free-falling
 * chunks - and builds the rigid bodies for those chunks. Pure geometry and
 * physics set-up, no GL: PbdRenderer only has to turn the result into
 * meshes and draw them, which keeps the interesting rules testable.
 *
 * WHAT STAYS. After a crater the surviving cubes are split into
 * face-connected pieces (VoxelConnectivity). Then:
 * <ul>
 *   <li>If the object stood on the floor ("grounded": its lowest point
 *       was within GROUNDED_TOLERANCE of the scene's floor), every piece
 *       still touching the object's lowest layer stays - it is held up
 *       by the ground. Pieces that no longer touch it fall. If the whole
 *       bottom was blown away, nothing is anchored and everything falls.</li>
 *   <li>Otherwise (a hanging door, a wall shelf, a picture frame - there
 *       is no support information in the format to say what holds it) the
 *       largest piece stays and the rest fall.</li>
 * </ul>
 * This is a heuristic, not structural analysis: the .pbd format carries
 * no "what supports this" metadata, so a door shot through the middle
 * sheds its smaller half rather than keeping whichever half the hinges
 * hold. Recorded as a known limitation in docs/ROADMAP.md.
 *
 * WHAT IS DUST. A fallen piece smaller than MIN_CHUNK_VOXELS (a few
 * crumbs the crater's rim left behind) is dropped rather than simulated,
 * and at most MAX_CHUNKS_PER_HIT of the biggest are kept: each chunk
 * costs a GPU mesh, and a hit on a large object can orphan dozens of
 * specks.
 */
public final class VoxelDebris {

    private VoxelDebris() {}

    /** Smallest piece (in unit voxels) worth simulating: ~7.4^3 voxels, a ~3.7 cm cube at the default 5 mm voxel. */
    public static final long MIN_CHUNK_VOXELS = 400;
    public static final int MAX_CHUNKS_PER_HIT = 6;
    /** How close (world units) an object's lowest point must be to the floor to count as standing on it. */
    public static final double GROUNDED_TOLERANCE = 0.05;
    /** A piece "touches the bottom" if it reaches within this many voxels of the object's lowest reach. */
    public static final double ANCHOR_TOLERANCE_VOXELS = 2.0;

    public static final class Split {
        /** Cubes that stay with the original object (concatenated anchored pieces). May be empty. */
        public final List<int[]> base;
        /** Detached pieces worth simulating, biggest first. */
        public final List<VoxelConnectivity.Component> chunks;
        public final int dustPieces;
        public final long dustVoxels;

        Split(List<int[]> base, List<VoxelConnectivity.Component> chunks, int dustPieces, long dustVoxels) {
            this.base = base;
            this.chunks = chunks;
            this.dustPieces = dustPieces;
            this.dustVoxels = dustVoxels;
        }
    }

    /**
     * @param surviving cubes left after the crater (octree-aligned)
     * @param downX/Y/Z world "down" as a unit vector in the GRID frame
     * @param bottomProjection how far the object reached along down BEFORE this hit,
     *        in grid units (VoxelConnectivity.maxProjection over the previous cubes)
     * @param grounded see class doc
     */
    public static Split split(List<int[]> surviving, double downX, double downY, double downZ,
                              double bottomProjection, boolean grounded) {
        List<VoxelConnectivity.Component> comps = VoxelConnectivity.split(surviving);
        List<int[]> base = new ArrayList<>();
        List<VoxelConnectivity.Component> detached = new ArrayList<>();

        if (comps.isEmpty()) return new Split(base, detached, 0, 0);

        if (grounded) {
            for (VoxelConnectivity.Component c : comps) {
                boolean touchesBottom = c.maxProjection(downX, downY, downZ) >= bottomProjection - ANCHOR_TOLERANCE_VOXELS;
                if (touchesBottom) base.addAll(c.entries);
                else detached.add(c);
            }
        } else {
            base.addAll(comps.get(0).entries); // largest first
            for (int i = 1; i < comps.size(); i++) detached.add(comps.get(i));
        }

        List<VoxelConnectivity.Component> chunks = new ArrayList<>();
        int dust = 0;
        long dustVox = 0;
        for (VoxelConnectivity.Component c : detached) { // already biggest first
            if (c.volume >= MIN_CHUNK_VOXELS && chunks.size() < MAX_CHUNKS_PER_HIT) chunks.add(c);
            else { dust++; dustVox += c.volume; }
        }
        return new Split(base, chunks, dust, dustVox);
    }

    /**
     * Builds the rigid body for one detached piece, positioned exactly
     * where the piece already is (so it does not visibly jump) and given
     * the momentum of the hit.
     *
     * @param meshOriginWorld world position of mesh-space (0,0,0) - the grid's own corner
     * @param meshRotation world rotation of mesh space
     * @param parentCom/parentVelocity/parentAngularVelocity the body this piece came from
     *        (all zero when it came from a static instance - an instance's own animation is not
     *        carried over: a swinging door's pieces start at rest relative to the world)
     * @param shotDirWorld direction the hit travelled (any length; zero allowed)
     * @param seed makes the tumble deterministic
     */
    public static RigidDebris spawn(VoxelConnectivity.Component piece, double voxelWorldSize,
                                    Vector3d meshOriginWorld, Quaterniond meshRotation,
                                    Vector3d parentCom, Vector3d parentVelocity, Vector3d parentAngularVelocity,
                                    Vector3d shotDirWorld, long seed) {
        RigidDebris body = RigidDebris.fromEntries(piece.entries, voxelWorldSize, 1.0);
        Vector3d offset = new Vector3d(body.comMesh);
        meshRotation.transform(offset);
        body.position.set(meshOriginWorld).add(offset);
        body.orientation.set(meshRotation);

        // Inherit the parent's motion at this piece's own centre of mass.
        Vector3d r = new Vector3d(body.position).sub(parentCom);
        Vector3d v = new Vector3d(parentAngularVelocity).cross(r).add(parentVelocity);
        body.velocity.set(v);
        body.angularVelocity.set(parentAngularVelocity);

        // The hit's own kick: horizontal component of the shot (a hit
        // pushes the piece away from the shooter), plus a little lift, and a tumble about the
        // horizontal axis perpendicular to the push so a tall piece topples off its stump
        // instead of dropping straight onto it.
        Random rng = new Random(seed);
        Vector3d push = new Vector3d(shotDirWorld.x, 0, shotDirWorld.z);
        if (push.lengthSquared() < 1e-6) {
            // Shot straight up/down: push away from the piece's own side of the
            // object instead - any consistent horizontal direction will do.
            double a = rng.nextDouble() * Math.PI * 2;
            push.set(Math.cos(a), 0, Math.sin(a));
        }
        push.normalize();
        double speed = 1.2 * (0.8 + 0.4 * rng.nextDouble());
        body.velocity.add(push.x * speed, 0.25 + 0.2 * rng.nextDouble(), push.z * speed);
        Vector3d spinAxis = new Vector3d(0, 1, 0).cross(push); // rotating about this tips the top of the piece along push
        body.angularVelocity.add(spinAxis.mul(1.5 + rng.nextDouble()));
        return body;
    }
}
