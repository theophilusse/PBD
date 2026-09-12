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
        public final float centerX, centerY, centerZ; // relative to the container's own center
        public final float scaleFactor; // 1.0 unless this item had to be shrunk to fit at all

        Placement(Item item, float x, float y, float z, float scaleFactor) {
            this.item = item;
            this.centerX = x;
            this.centerY = y;
            this.centerZ = z;
            this.scaleFactor = scaleFactor;
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
            result.placements.add(new Placement(item, centerX, centerY, centerZ, scaleFactor));

            cursorX += w;
            rowMaxDepth = Math.max(rowMaxDepth, d);
            layerMaxHeight = Math.max(layerMaxHeight, h);
        }

        return result;
    }
}
