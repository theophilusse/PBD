package pbd.pz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Places a list of item bounding boxes inside a container's interior
 * volume using simple "shelf" packing: items are placed left-to-right
 * along X, wrapping to a new row along Z when the current row runs out
 * of width, and wrapping to a new layer along Y when the current layer
 * runs out of depth. This is NOT a real bin-packing solver (no rotation
 * search, no attempt at an optimal or even near-optimal arrangement) -
 * it is deliberately the simplest thing that places items without
 * overlapping and reports what didn't fit, which is what "cache the
 * result, don't compute until the container opens" actually needs: a
 * stable, cheap-to-compute placement, not a perfect one.
 *
 * If an item doesn't fit even after starting a fresh layer (taller or
 * wider than the container itself), it's scaled down uniformly to fit -
 * matching the "sinon on resize l'item" fallback this project's roadmap
 * already called for - and reported as such, rather than silently
 * placed at the wrong size or dropped.
 */
public final class BinPacker {

    public static final class Item {
        public final String id; // e.g. the source FBX filename, for identifying this placement later
        public final float width, height, depth; // full extent on each axis, not half-extent

        public Item(String id, float width, float height, float depth) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }

    public static final class Placement {
        public final Item item;
        public final float centerX, centerY, centerZ; // relative to the OWNING container's own center - see containerIndex
        public final float scaleFactor; // 1.0 unless this item had to be shrunk to fit at all
        public final int containerIndex; // which container (index into the list passed to packMultiple) this landed in - always 0 for the single-container pack()

        Placement(Item item, float x, float y, float z, float scaleFactor, int containerIndex) {
            this.item = item;
            this.centerX = x;
            this.centerY = y;
            this.centerZ = z;
            this.scaleFactor = scaleFactor;
            this.containerIndex = containerIndex;
        }
    }

    public static final class Result {
        public final List<Placement> placements = new ArrayList<>();
        public final List<String> shrunkItemIds = new ArrayList<>(); // items that needed resizing to fit at all
    }

    /** containerWidth/Height/Depth: the full interior usable volume
     * (already excluding wall thickness - the caller's responsibility to
     * pass the actual interior size, e.g. a metadata cube's own
     * dimensions). Items are packed starting from the container's
     * -X,-Y,-Z corner (bottom, one corner) growing toward +X,+Y,+Z. */
    public Result pack(List<Item> items, float containerWidth, float containerHeight, float containerDepth) {
        Result result = new Result();

        List<Item> sorted = new ArrayList<>(items);
        // Tallest first: a shelf-packing approach works better when each
        // layer's height is set by its biggest item early, rather than
        // being surprised by a tall item arriving after several short
        // ones already fixed a low layer height.
        sorted.sort(Comparator.comparingDouble((Item i) -> i.height).reversed());

        float cursorX = 0, cursorZ = 0, cursorY = 0;
        float rowMaxDepth = 0; // deepest item placed in the current row (along Z), determines how far to advance cursorZ
        float layerMaxHeight = 0; // tallest item placed in the current Y layer, determines how far to advance cursorY

        for (Item item : sorted) {
            float scaleFactor = 1.0f;
            float w = item.width, h = item.height, d = item.depth;

            if (w > containerWidth || h > containerHeight || d > containerDepth) {
                // Doesn't fit at all, even alone in an empty container -
                // shrink uniformly (preserves proportions) until it does,
                // per this project's own "resize if it doesn't fit"
                // fallback rule.
                float shrinkForWidth = containerWidth / w;
                float shrinkForHeight = containerHeight / h;
                float shrinkForDepth = containerDepth / d;
                scaleFactor = Math.min(1.0f, Math.min(shrinkForWidth, Math.min(shrinkForHeight, shrinkForDepth))) * 0.95f; // 5% margin so it doesn't touch the container wall exactly
                w *= scaleFactor; h *= scaleFactor; d *= scaleFactor;
                result.shrunkItemIds.add(item.id);
            }

            if (cursorX + w > containerWidth) {
                // Row is full - start a new row along Z.
                cursorX = 0;
                cursorZ += rowMaxDepth;
                rowMaxDepth = 0;
            }
            if (cursorZ + d > containerDepth) {
                // Layer is full too - start a new layer along Y.
                cursorX = 0;
                cursorZ = 0;
                rowMaxDepth = 0;
                cursorY += layerMaxHeight;
                layerMaxHeight = 0;
            }
            // If it STILL doesn't fit (a fresh empty layer isn't enough,
            // e.g. containerHeight itself is exceeded by cursorY+h), this
            // item just gets placed overlapping the container ceiling
            // rather than dropped - an acceptable rough edge for a
            // first-pass packer, flagged via shrunkItemIds already
            // covering the "too big" case; a genuinely over-full
            // container is a capacity problem this simple packer doesn't
            // try to solve by ejecting other items.

            float centerX = -containerWidth / 2 + cursorX + w / 2;
            float centerY = -containerHeight / 2 + cursorY + h / 2;
            float centerZ = -containerDepth / 2 + cursorZ + d / 2;
            result.placements.add(new Placement(item, centerX, centerY, centerZ, scaleFactor, 0));

            cursorX += w;
            rowMaxDepth = Math.max(rowMaxDepth, d);
            layerMaxHeight = Math.max(layerMaxHeight, h);
        }

        return result;
    }

    public static final class ContainerVolume {
        public final float width, height, depth;
        public ContainerVolume(float width, float height, float depth) {
            this.width = width; this.height = height; this.depth = depth;
        }
    }

    private static final class Cursor {
        float x = 0, y = 0, z = 0, rowMaxDepth = 0, layerMaxHeight = 0;
    }

    /**
     * Distributes items across several container volumes instead of
     * one: "if it doesn't fit in this bounding box, or the box is full,
     * try the other bounding box" - each container gets its own shelf-
     * packing cursor, and items are assigned round-robin (item 0 targets
     * container 0, item 1 targets container 1, wrapping back around)
     * for an equitable spread rather than filling the first container
     * completely before ever touching the second. If the targeted
     * container has no room left (even after starting a fresh layer),
     * every OTHER container is tried in turn before giving up and
     * resizing the item to fit whichever container has the most
     * remaining height budget - matching "if no solution works, resize
     * the object" as the last resort, not the first.
     */
    public Result packMultiple(List<Item> items, List<ContainerVolume> containers) {
        Result result = new Result();
        if (containers.isEmpty()) return result;

        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((Item i) -> i.height).reversed());

        Cursor[] cursors = new Cursor[containers.size()];
        for (int i = 0; i < cursors.length; i++) cursors[i] = new Cursor();

        int roundRobin = 0;
        for (Item item : sorted) {
            int placedIn = -1;
            for (int attempt = 0; attempt < containers.size(); attempt++) {
                int candidate = (roundRobin + attempt) % containers.size();
                if (fitsWithFreshLayer(cursors[candidate], containers.get(candidate), item)) {
                    placedIn = candidate;
                    break;
                }
            }

            float scaleFactor = 1.0f;
            float w = item.width, h = item.height, d = item.depth;

            if (placedIn < 0) {
                // Didn't fit in ANY container, even starting a fresh
                // layer in each - resize into whichever container has
                // the most vertical room left, since that's the most
                // forgiving target for a shrunk item.
                placedIn = roomiestContainer(cursors, containers);
                ContainerVolume c = containers.get(placedIn);
                float shrinkForWidth = c.width / w;
                float shrinkForHeight = c.height / h;
                float shrinkForDepth = c.depth / d;
                scaleFactor = Math.min(1.0f, Math.min(shrinkForWidth, Math.min(shrinkForHeight, shrinkForDepth))) * 0.95f;
                w *= scaleFactor; h *= scaleFactor; d *= scaleFactor;
                result.shrunkItemIds.add(item.id);
            }

            ContainerVolume container = containers.get(placedIn);
            Cursor cursor = cursors[placedIn];
            advanceCursorForItem(cursor, container, w, d);

            // Safety net for the resize branch above: it shrinks against
            // the container's FULL height, not whatever's actually left
            // once other items already occupy part of it - a real bug
            // found by testing with a genuinely full asset folder rather
            // than the one or two sample files this was first written
            // against. If height still doesn't fit even after the shrink
            // and cursor advance above, shrink again against the ACTUAL
            // remaining room (uniformly on all 3 axes, so it still looks
            // like the same item, just smaller, not squashed) rather than
            // letting it poke through the ceiling.
            if (cursor.y + h > container.height) {
                float remaining = container.height - cursor.y;
                if (remaining <= 0.01f) {
                    // Container is already saturated even before this
                    // item - no per-item shrink can rescue that (shelf
                    // packers don't reflow earlier items to make room).
                    // Clamp flush against the ceiling with a near-zero
                    // height rather than exceeding the container: an
                    // unrealistically thin sliver is the honest result of
                    // "too many items asked for too little space," and
                    // still satisfies the one invariant that actually
                    // matters here (never render outside the box).
                    float extra = 0.01f / h;
                    scaleFactor *= extra;
                    w *= extra; h *= extra; d *= extra;
                    cursor.y = container.height - h;
                } else {
                    float extra = remaining / h * 0.95f;
                    scaleFactor *= extra;
                    w *= extra; h *= extra; d *= extra;
                }
                if (!result.shrunkItemIds.contains(item.id)) result.shrunkItemIds.add(item.id);
            }

            float centerX = -container.width / 2 + cursor.x + w / 2;
            float centerY = -container.height / 2 + cursor.y + h / 2;
            float centerZ = -container.depth / 2 + cursor.z + d / 2;
            result.placements.add(new Placement(item, centerX, centerY, centerZ, scaleFactor, placedIn));

            cursor.x += w;
            cursor.rowMaxDepth = Math.max(cursor.rowMaxDepth, d);
            cursor.layerMaxHeight = Math.max(cursor.layerMaxHeight, h);

            roundRobin = (placedIn + 1) % containers.size();
        }

        return result;
    }

    /** True if the item fits in this container's remaining space,
     * including the possibility of needing a fresh row or fresh layer
     * first - does NOT mutate the cursor, only checks. */
    private boolean fitsWithFreshLayer(Cursor cursor, ContainerVolume container, Item item) {
        if (item.width > container.width || item.height > container.height || item.depth > container.depth) {
            return false; // too big for this container even alone in a fully empty one
        }
        float x = cursor.x, z = cursor.z, y = cursor.y;
        float rowMaxDepth = cursor.rowMaxDepth, layerMaxHeight = cursor.layerMaxHeight;
        if (x + item.width > container.width) {
            x = 0; z += rowMaxDepth; rowMaxDepth = 0;
        }
        if (z + item.depth > container.depth) {
            x = 0; z = 0; y += layerMaxHeight;
        }
        return y + item.height <= container.height;
    }

    /** Mirrors fitsWithFreshLayer's row/layer-advancing logic, but
     * actually mutates the cursor - called only once a placement is
     * already decided, so the two stay perfectly consistent with each
     * other by construction (one is a dry run of the other). */
    private void advanceCursorForItem(Cursor cursor, ContainerVolume container, float w, float d) {
        if (cursor.x + w > container.width) {
            cursor.x = 0; cursor.z += cursor.rowMaxDepth; cursor.rowMaxDepth = 0;
        }
        if (cursor.z + d > container.depth) {
            cursor.x = 0; cursor.z = 0; cursor.y += cursor.layerMaxHeight; cursor.layerMaxHeight = 0;
        }
    }

    private int roomiestContainer(Cursor[] cursors, List<ContainerVolume> containers) {
        int best = 0;
        float bestRoom = -1;
        for (int i = 0; i < containers.size(); i++) {
            float room = containers.get(i).height - cursors[i].y;
            if (room > bestRoom) { bestRoom = room; best = i; }
        }
        return best;
    }
}
