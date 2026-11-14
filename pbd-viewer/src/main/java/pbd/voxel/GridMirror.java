package pbd.voxel;

import org.joml.Vector3f;

import pbd.format.PbdInstance;

/**
 * An object with a negative scale on an axis is its own mirror image on that axis: the model matrix turns the shape's points into
 * their reflection AFTER the modifiers have acted (the shader bends, shears and tapers the canonical shape, then the matrix scales
 * it), so a cone scaled by (1, -1, 1) stands on its apex, a hollow cylinder with {@code cap=top} and a negative height is closed
 * at the bottom, a cube with {@code faces=(+x)} removed and a negative width is open on the -X side.
 *
 * <p>The voxelizers work with positive sizes only (a grid has a size, not a direction). So a mirrored object is voxelized with the
 * absolute value of its scale and the finished grid is reflected here: the octree is turned over along the axes concerned and its
 * corner moves to where the reflected grid begins. Exact - voxels map onto voxels, nothing is resampled - and one place for every
 * kind of object (primitives, bent or hollow shapes, meshes).
 *
 * <p>What the caller then has to do is place the grid with the object's rotation and its (negative) scale divided out: for a matrix
 * that mirrors an odd number of axes the rotation is the one of the matrix with its three axes turned over, and the scale
 * is negative on all three ({@link DestructionWorld#rotationOf}, {@link DestructionWorld#withWorldScale}).
 */
final class GridMirror {
    private GridMirror() {}

    /** Whether any of the scale's components is negative (NaN is not: it is refused elsewhere). */
    static boolean mirrors(Vector3f scale) {
        return scale.x < 0f || scale.y < 0f || scale.z < 0f;
    }

    /** A copy of the instance whose scale is the absolute value of its own: the object a voxelizer works on. */
    static PbdInstance unmirrored(PbdInstance instance) {
        PbdInstance copy = instance.copyAs(instance.id);
        copy.scale.set(Math.abs(instance.scale.x), Math.abs(instance.scale.y), Math.abs(instance.scale.z));
        return copy;
    }

    /**
     * The result for the mirrored object, from the one of the same object with positive scale: the grid reflected along the axes
     * whose scale of {@code signed} is negative. {@code signed} becomes the result's source (it is the instance the caller passed,
     * and the one whose scale the texture mapping divides by).
     */
    static PrimitiveVoxelizer.Result of(PrimitiveVoxelizer.Result unmirrored, PbdInstance signed) {
        boolean mx = signed.scale.x < 0f, my = signed.scale.y < 0f, mz = signed.scale.z < 0f;
        VoxelOctree grid = unmirrored.octree;
        int n = grid.gridSize();
        VoxelOctree flipped = new VoxelOctree(Integer.numberOfTrailingZeros(n));
        for (int[] cube : grid.collectFilledRegions()) {
            int size = cube[3];
            int x = mx ? n - cube[0] - size : cube[0];
            int y = my ? n - cube[1] - size : cube[1];
            int z = mz ? n - cube[2] - size : cube[2];
            flipped.insertFilledBox(x, y, z, x + size, y + size, z + size);
        }
        // the grid covers [origin, origin + n * voxel] on each axis; reflected through the origin of the object's frame it covers [-origin - n * voxel, -origin]
        double extent = (double) n * unmirrored.voxelWorldSize;
        Vector3f o = unmirrored.gridOriginLocal;
        Vector3f origin = new Vector3f(mx ? (float) (-o.x - extent) : o.x, my ? (float) (-o.y - extent) : o.y, mz ? (float) (-o.z - extent) : o.z);
        return new PrimitiveVoxelizer.Result(flipped, unmirrored.voxelWorldSize, origin, signed, unmirrored.requestedVoxelWorldSize);
    }
}
