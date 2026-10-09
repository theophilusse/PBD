package pbd.voxel;

/**
 * Minimal open-addressing long -> int hash map (linear probing), kept
 * package-private on purpose: VoxelConnectivity and VoxelSolid each need
 * to look up "which octree-aligned cube covers this cell" tens of
 * thousands of times per destruction hit, and a boxed HashMap&lt;Long,
 * Integer&gt; for that was exactly the kind of allocation-heavy structure
 * this project already paid for once (see the crater OutOfMemoryError
 * entry in docs/ROADMAP.md). Only put/get - there is no removal, every
 * caller builds the map once and then only reads it.
 *
 * Missing keys return {@link #MISSING} (-1), so values must be >= 0.
 */
final class LongIntMap {
    static final int MISSING = -1;

    private long[] keys;
    private int[] values;
    private boolean[] used;
    private int size;
    private int mask;

    LongIntMap(int expectedEntries) {
        int cap = 16;
        // Load factor kept under 0.5: probing stays short, memory is
        // still only ~13 bytes per slot (8 key + 4 value + 1 flag).
        while (cap < expectedEntries * 2L) cap <<= 1;
        keys = new long[cap];
        values = new int[cap];
        used = new boolean[cap];
        mask = cap - 1;
    }

    int size() {
        return size;
    }

    private static int hash(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= (k >>> 33);
        return (int) k;
    }

    void put(long key, int value) {
        if (value < 0) throw new IllegalArgumentException("values must be >= 0");
        if ((size + 1) * 2 > keys.length) grow();
        int i = hash(key) & mask;
        while (used[i]) {
            if (keys[i] == key) { values[i] = value; return; }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
    }

    int get(long key) {
        int i = hash(key) & mask;
        while (used[i]) {
            if (keys[i] == key) return values[i];
            i = (i + 1) & mask;
        }
        return MISSING;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        int cap = oldKeys.length << 1;
        keys = new long[cap];
        values = new int[cap];
        used = new boolean[cap];
        mask = cap - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) put(oldKeys[i], oldValues[i]);
        }
    }

    /** Packs (level, cx, cy, cz) - the cell coordinates at that octree
     * level, i.e. voxel coordinate shifted right by level - into one
     * long. 20 bits per axis (a 1,048,576-cell span, vastly more than
     * the 4096-voxel grids VoxelOctree allows) and 4 bits of level. */
    static long cellKey(int level, int cx, int cy, int cz) {
        return ((long) level << 60) | ((long) (cx & 0xFFFFF) << 40) | ((long) (cy & 0xFFFFF) << 20) | (long) (cz & 0xFFFFF);
    }

    /** Debug aid for tests. */
    @Override
    public String toString() {
        return "LongIntMap{size=" + size + ", capacity=" + keys.length + "}";
    }
}
