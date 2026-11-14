package pbd.voxel;

/**
 * The rooms a voxelized SOLID has inside it, which it must not have. A solid's interior is filled by the lattice of
 * {@link ForwardVoxelizer} from the coverage each voxel received, and a voxel covered 49% where 50% fills is left empty: inside a bent,
 * sheared or rounded solid that leaves pinholes - empty voxels surrounded by filled ones - which a crater then opens onto, showing as
 * holes in the wall of the hole. Nothing there is a room: they are found by a flood fill of the empty voxels from outside the box, and
 * every empty voxel the flood does not reach is returned to be filled.
 *
 * <p>Pure: bits in, indices out. Verified by RegressionVoxelShapes (rooms made by hand: a block with a room, a tunnel, a corner link, a
 * hollow ball, a box too big to search) and on every kind of solid with its modifiers (no enclosed voxel after voxelization).
 */
public final class VoxelCavities {
    private VoxelCavities() {}

    /** The largest box (in voxels) whose enclosed cavities are looked for: two bits a voxel, 64 M voxels = 16 MB. */
    public static final long MAX_SEARCH_VOXELS = 64L * 1024 * 1024;

    /**
     * The voxels of a SOLID that were left empty but are not outside it: those no path of empty voxels leads out of. A solid has no
     * cavity - what the lattice leaves empty in there is its own rounding (a voxel covered 49% instead of 50%) - so they are filled, and
     * a crater in the object does not open onto a pinhole. Found by flooding the empty voxels from outside the box (one layer of margin
     * all round), breadth first. Returns the indices ((x * gy + y) * gz + z, box coordinates) of the voxels to add, or null when there is
     * none or the box is too big to search (the object is then left as it is).
     */
    public static int[] enclosedEmpty(java.util.BitSet filled, int gx, int gy, int gz) {
        long total = (long) (gx + 2) * (gy + 2) * (gz + 2);
        if (total > MAX_SEARCH_VOXELS) return null;
        int nx = gx + 2, ny = gy + 2, nz = gz + 2;      // the box with a margin of one voxel
        java.util.BitSet outside = new java.util.BitSet((int) total);
        // a breadth-first frontier is never larger than a few layers of the box's surface
        int capacity = (int) Math.min(Integer.MAX_VALUE - 8, 8L * (nx * (long) ny + ny * (long) nz + nx * (long) nz) + 1024);
        int[] ring = new int[capacity];
        int head = 0, count = 0;
        ring[0] = 0;
        count = 1;
        outside.set(0);
        while (count > 0) {
            int cell = ring[head];
            head = head + 1 == capacity ? 0 : head + 1;
            count--;
            int z = cell % nz, y = (cell / nz) % ny, x = cell / (nz * ny);
            for (int d = 0; d < 6; d++) {
                int px = x + (d == 0 ? 1 : d == 1 ? -1 : 0), py = y + (d == 2 ? 1 : d == 3 ? -1 : 0), pz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                if (px < 0 || py < 0 || pz < 0 || px >= nx || py >= ny || pz >= nz) continue;
                int next = (px * ny + py) * nz + pz;
                if (outside.get(next)) continue;
                // box coordinates without the margin
                int bx = px - 1, by = py - 1, bz = pz - 1;
                if (bx >= 0 && by >= 0 && bz >= 0 && bx < gx && by < gy && bz < gz && filled.get((bx * gy + by) * gz + bz)) continue;
                outside.set(next);
                if (count == capacity) return null;      // cannot happen with the capacity above; refuse rather than corrupt
                int tail = head + count;
                if (tail >= capacity) tail -= capacity;
                ring[tail] = next;
                count++;
            }
        }
        java.util.ArrayList<Integer> found = new java.util.ArrayList<>();
        for (int bx = 0; bx < gx; bx++) {
            for (int by = 0; by < gy; by++) {
                for (int bz = 0; bz < gz; bz++) {
                    int index = (bx * gy + by) * gz + bz;
                    if (filled.get(index)) continue;
                    if (!outside.get(((bx + 1) * ny + (by + 1)) * nz + (bz + 1))) found.add(index);
                }
            }
        }
        if (found.isEmpty()) return null;
        int[] result = new int[found.size()];
        for (int i = 0; i < result.length; i++) result[i] = found.get(i);
        return result;
    }
}
