package pbd.pz;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import pbd.classicmesh.ObjMesh;
import pbd.fbx.FbxBinaryReader;
import pbd.fbx.FbxGeometryExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One container's bin-packed contents - computed once, lazily, the
 * first time the container is seen open (see PbdRenderer's
 * isInstanceOpen - triggers the instant a linked door is toggled open,
 * not once its swing animation finishes), and cached until every linked
 * door is closed again (see Main.java's eviction step next to where
 * this is loaded), matching this project's own "no computation or
 * display before the container opens, cache the result, clear it once
 * closed" rule.
 *
 * The falling simulation here is deliberately simple, not a real rigid-
 * body physics engine: each item just tracks a fall speed and drops
 * straight down (no rotation, no side-to-side sliding, no collision
 * between two simultaneously-falling items) until it lands on the
 * container floor or on top of another item whose own horizontal
 * footprint it overlaps. That is enough to produce the requested effect
 * (removing a supporting item makes what's above it fall) without
 * building an actual physics engine, which this project's own roadmap
 * treats as separate, larger future work.
 */
public final class ContainerContents {

    private static final float GRAVITY = 2.0f; // world units/s^2 - tuned by feel, not physically calibrated to real gravity's 9.8, since these are small (~0.1-0.5 unit) items and real-world gravity would make them fall unreadably fast on screen

    public static final class PlacedItem {
        public final String sourceFile;
        public final ObjMesh mesh;
        public final BinPacker.Item bounds;
        public final int containerIndex; // which of this group's several boxes (index into ContainerContents.metaIndices) this item is in
        public final float localCenterX, localCenterY, localCenterZ; // the mesh's own bounding-box center, in its own RAW/unscaled local space - subtract this (after scaling by scaleFactor) only when building a render transform, never used by the physics below, which treats currentX/Y/Z as the object's actual logical center throughout
        public final float scaleFactor; // 1.0 unless BinPacker had to shrink this item to fit its container - MUST be applied as an actual render-time scale (see Main.java's itemWorld), the packer only bakes it into the PLACEMENT position, never into the mesh's own geometry
        public float targetX, targetY, targetZ; // bin-packer's assigned position, relative to ITS OWN container's center (see containerIndex)
        public float currentX, currentY, currentZ; // where it actually is right now - equals target until something makes it fall
        public float fallVelocity = 0f;
        public boolean falling = false;

        PlacedItem(String sourceFile, ObjMesh mesh, BinPacker.Item bounds, int containerIndex, float x, float y, float z,
                   float localCenterX, float localCenterY, float localCenterZ, float scaleFactor) {
            this.sourceFile = sourceFile;
            this.mesh = mesh;
            this.bounds = bounds;
            this.containerIndex = containerIndex;
            this.targetX = x; this.targetY = y; this.targetZ = z;
            this.currentX = x; this.currentY = y; this.currentZ = z;
            this.localCenterX = localCenterX; this.localCenterY = localCenterY; this.localCenterZ = localCenterZ;
            this.scaleFactor = scaleFactor;
        }
    }

    public final List<Integer> metaIndices; // this group's box instance indices, in the SAME order as containerVolumes below - PlacedItem.containerIndex refers to a position in THIS list

    public final List<PlacedItem> items = new ArrayList<>();
    private final List<BinPacker.ContainerVolume> containerVolumes;

    private ContainerContents(List<Integer> metaIndices, List<BinPacker.ContainerVolume> containerVolumes) {
        this.metaIndices = metaIndices;
        this.containerVolumes = containerVolumes;
    }

    /**
     * Loads a random-but-DETERMINISTIC number (10..50, inclusive - see
     * count below) of .fbx files from anywhere under fbxDir (searched
     * recursively, see the Files.walk note below), extracts their
     * geometry, bin-packs them across one or more container volumes
     * (metaIndices/containerVolumes - several doors can share one box and
     * one door can open several, so a "container" here is really a GROUP
     * of boxes controlled by the same door(s)), and returns the
     * ready-to-render result.
     *
     * maxCount is now a soft cap only (via Math.min against however many
     * .fbx files actually exist) - the 10..50 range itself is fixed,
     * matching a real, fully-populated asset folder rather than the
     * small 2..5 range that made sense back when fbxDir held only a
     * couple of test files.
     *
     * seedKey drives which files get chosen and how they're shuffled -
     * it MUST be something stable across runs (this project passes the
     * sorted, joined names of the group's own box instances), not a
     * fresh unseeded Random(), which is what "predictable, and keeps its
     * arrangement after the cache is rewarmed" actually requires: the
     * same container reliably picks the same items in the same order
     * every time the scene loads fresh, rather than re-rolling on every
     * run. This only covers "the same seed always produces the same
     * initial pack" - it does NOT persist to disk what's since been
     * individually removed or moved (see this method's caller in
     * Main.java for where that gap is flagged, since it's a real,
     * separate piece of unfinished work: an in-memory cache reset when
     * the whole app restarts, however deterministic, is not the same as
     * "coming back and finding a removed item still gone").
     *
     * Any individual FBX file that fails to load is skipped with a
     * console warning rather than aborting the whole group - one bad
     * file shouldn't make an entire container's contents disappear.
     */
    public static ContainerContents loadRandom(Path fbxDir, int maxCount, List<Integer> metaIndices,
                                                 List<BinPacker.ContainerVolume> containerVolumes, long seedKey) {
        ContainerContents contents = new ContainerContents(metaIndices, containerVolumes);
        List<Path> allFbx;
        // Files.walk (recursive), not Files.list (immediate children only)
        // - a real game's asset folder is organized into subfolders by
        // category (furniture/, appliances/, ...), so a flat listing
        // silently found zero files and every container came up empty
        // the moment the fbx directory stopped being just a handful of
        // loose test files at its top level. maxDepth left at the
        // default (effectively unbounded) since asset trees can nest
        // more than one level deep.
        try (var stream = Files.walk(fbxDir)) {
            allFbx = stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().toLowerCase().endsWith(".fbx")).sorted().toList();
        } catch (IOException e) {
            System.err.println("[ContainerContents] Could not list " + fbxDir + ": " + e.getMessage());
            return contents;
        }
        if (allFbx.isEmpty()) {
            System.err.println("[ContainerContents] No .fbx files found under " + fbxDir + " (searched recursively)");
            return contents;
        }

        // Sorted first (above), THEN shuffled with a seeded Random - both
        // steps matter for determinism: without the sort, the order
        // Files.walk happens to return (filesystem-dependent, not
        // guaranteed stable) would already inject randomness no seed
        // downstream can fix.
        Random random = new Random(seedKey);
        // 10..50 items (inclusive) is the TARGET, not a guarantee - a
        // real, varied game asset library will have some files
        // pbd.fbx's simplified reader can't handle yet (see its own
        // "known rough edges" - n-gons, multiple Geometry nodes, unusual
        // UV/normal reference modes). Drawing exactly `count` candidates
        // ONCE and accepting however many of THOSE happened to extract
        // successfully - the previous version of this method - meant an
        // unlucky draw could leave a container showing far fewer items
        // than intended, occasionally zero, with nothing else in
        // allFbx ever tried. Now keeps drawing additional untried
        // candidates from the remaining pool until either the target is
        // met or the whole corpus has been offered a chance - a real
        // shortage of usable files still ends with fewer items (nothing
        // can manufacture geometry that isn't there), but an unlucky
        // shuffle landing on a few unsupported files no longer empties
        // the container when plenty of OTHER usable files exist.
        int targetCount = Math.min(allFbx.size(), 10 + random.nextInt(41));
        List<Path> shuffledPool = new ArrayList<>(allFbx);
        java.util.Collections.shuffle(shuffledPool, random);

        List<BinPacker.Item> packerItems = new ArrayList<>();
        var meshByName = new java.util.HashMap<String, ObjMesh>();
        var extractionByName = new java.util.HashMap<String, FbxGeometryExtractor.ExtractionResult>();
        int skipped = 0;

        for (Path fbxPath : shuffledPool) {
            if (packerItems.size() >= targetCount) break;
            try {
                List<FbxBinaryReader.Node> nodes = FbxBinaryReader.readFile(fbxPath);
                FbxGeometryExtractor.ExtractionResult extraction = new FbxGeometryExtractor().extract(nodes);
                String name = fbxPath.getFileName().toString();
                float w = extraction.boundingBoxMax[0] - extraction.boundingBoxMin[0];
                float h = extraction.boundingBoxMax[1] - extraction.boundingBoxMin[1];
                float d = extraction.boundingBoxMax[2] - extraction.boundingBoxMin[2];
                packerItems.add(new BinPacker.Item(name, w, h, d));
                meshByName.put(name, extraction.mesh);
                extractionByName.put(name, extraction);
            } catch (IOException | RuntimeException e) {
                skipped++;
                System.err.println("[ContainerContents] Skipping " + fbxPath.getFileName() + ": " + e.getMessage());
            }
        }
        if (skipped > 0) {
            System.out.println("[ContainerContents] " + skipped + " file(s) couldn't be read (see skip messages "
                + "above) - drew from the rest of the " + allFbx.size() + "-file pool to still reach "
                + packerItems.size() + "/" + targetCount + " item(s)");
        }

        System.out.println("[ContainerContents] Bin-packing " + packerItems.size() + " item(s) across "
            + containerVolumes.size() + " container(s), seed=" + seedKey);

        BinPacker.Result packResult = new BinPacker().packMultiple(packerItems, containerVolumes);
        if (!packResult.shrunkItemIds.isEmpty()) {
            System.out.println("[ContainerContents] Resized to fit (too big for every linked box otherwise): " + packResult.shrunkItemIds);
        }

        for (BinPacker.Placement placement : packResult.placements) {
            ObjMesh mesh = meshByName.get(placement.item.id);
            // Bounding-box mins aren't necessarily (0,0,0) in the mesh's
            // own local space (BeerBottle.FBX's own min was (-0.072,0,-0.083),
            // say) - the placement's center is in CONTAINER space, so the
            // item's geometry needs shifting by its own bounding-box
            // center first to actually land centered at that placement,
            // not offset by whatever its raw local origin happened to be.
            FbxGeometryExtractor.ExtractionResult extraction = extractionByName.get(placement.item.id);
            float localCenterX = (extraction.boundingBoxMin[0] + extraction.boundingBoxMax[0]) / 2f;
            float localCenterY = (extraction.boundingBoxMin[1] + extraction.boundingBoxMax[1]) / 2f;
            float localCenterZ = (extraction.boundingBoxMin[2] + extraction.boundingBoxMax[2]) / 2f;

            // localCenter passed RAW (unscaled) - Main.java applies
            // scaleFactor as an actual render-time .scale() around this
            // same anchor, so pre-multiplying it in here too would square
            // it for the centering offset specifically (see PlacedItem's
            // scaleFactor field doc).
            PlacedItem item = new PlacedItem(placement.item.id, mesh, placement.item, placement.containerIndex,
                placement.centerX, placement.centerY, placement.centerZ,
                localCenterX, localCenterY, localCenterZ, placement.scaleFactor);
            contents.items.add(item);
        }

        return contents;
    }

    /** Advances the falling simulation by dt seconds - call once per
     * frame regardless of whether anything is currently falling (cheap
     * no-op when nothing is). */
    public void update(float dt) {
        for (PlacedItem item : items) {
            if (!item.falling) continue;
            item.fallVelocity += GRAVITY * dt;
            item.currentY -= item.fallVelocity * dt;

            float restY = floorOrSupportY(item);
            if (item.currentY <= restY) {
                item.currentY = restY;
                item.falling = false;
                item.fallVelocity = 0f;
            }
        }
    }

    /** The Y this item should land on: the container floor, or the top
     * of whatever non-falling item's horizontal footprint it overlaps
     * and which currently sits below it - whichever is higher. Only
     * ever compares against items in the SAME container (containerIndex
     * match) - two items in different boxes never physically interact,
     * even if those boxes happen to share a door. */
    private float floorOrSupportY(PlacedItem item) {
        float itemHeight = item.bounds.height * item.scaleFactor;
        float floorY = -containerVolumes.get(item.containerIndex).height / 2f + itemHeight / 2f;
        float highestSupport = floorY;
        for (PlacedItem other : items) {
            if (other == item || other.falling || other.containerIndex != item.containerIndex) continue;
            float otherWidth = other.bounds.width * other.scaleFactor;
            float otherDepth = other.bounds.depth * other.scaleFactor;
            float itemWidth = item.bounds.width * item.scaleFactor;
            float itemDepth = item.bounds.depth * item.scaleFactor;
            boolean overlapsX = Math.abs(item.currentX - other.currentX) < (itemWidth + otherWidth) / 2f;
            boolean overlapsZ = Math.abs(item.currentZ - other.currentZ) < (itemDepth + otherDepth) / 2f;
            if (overlapsX && overlapsZ) {
                float otherHeight = other.bounds.height * other.scaleFactor;
                float supportTopY = other.currentY + otherHeight / 2f + itemHeight / 2f;
                if (supportTopY > highestSupport && supportTopY <= item.targetY + 0.5f) {
                    highestSupport = supportTopY;
                }
            }
        }
        return highestSupport;
    }

    /** Removes the item whose current world-space position is closest to
     * the ray, if the ray actually passes near it - marks every
     * remaining item in the SAME container currently resting above the
     * removed one's footprint as falling. containerWorldCenters must be
     * indexed the same way as metaIndices/containerVolumes (i.e.
     * containerWorldCenters.get(i) is the world center of the box at
     * metaIndices.get(i)) - Main.java builds this list that way already,
     * from renderer.instanceWorldCenter(metaIndices.get(i)) for each i.
     * Returns the removed item's source filename, or null if the ray hit
     * nothing. */
    /** World position of a placed item - the container's own world
     * center PLUS the item's local offset, with that offset ROTATED by
     * the container's own world rotation first. Shared by
     * removeNearestToRay below and Main.java's render loop so both
     * agree on where an item actually is - they used to each do this
     * addition WITHOUT the rotation step, correct only for an
     * axis-aligned container (see instanceWorldRotation's own doc on
     * PbdRenderer for the full story). containerWorldRotations indexed
     * the same way as containerWorldCenters (by containerIndex). */
    public static Vector3f itemWorldPosition(PlacedItem item, List<Vector3f> containerWorldCenters,
                                              List<Quaternionf> containerWorldRotations) {
        Vector3f localOffset = new Vector3f(item.currentX, item.currentY, item.currentZ);
        Quaternionf rot = containerWorldRotations.get(item.containerIndex);
        Vector3f rotatedOffset = rot.transform(localOffset, new Vector3f());
        return new Vector3f(containerWorldCenters.get(item.containerIndex)).add(rotatedOffset);
    }

    private static String fmt(Vector3f v) {
        return String.format("(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }

    public String removeNearestToRay(List<Vector3f> containerWorldCenters, List<Quaternionf> containerWorldRotations,
                                      Vector3f rayOrigin, Vector3f rayDir) {
        PlacedItem closest = null;
        float closestDist = Float.MAX_VALUE;
        // Diagnostic logging: repeated, specific reports that E never
        // hits anything, with no crash and no other lead found across
        // several rounds of code-level verification (isolated tests,
        // full real-scene chains, all passing), point at needing to see
        // this system's ACTUAL behavior in the real environment rather
        // than continuing to guess blindly against test data that keeps
        // passing. Printed on every E press specifically so the exact
        // numbers involved (positions, radii, distances) are visible
        // instead of just a final "hit" or "miss" - console output from
        // an actual attempt is worth more here than another theory.
        System.out.println("[E-key] ray origin=" + fmt(rayOrigin) + " dir=" + fmt(rayDir)
            + " checking " + items.size() + " item(s)");
        for (PlacedItem item : items) {
            Vector3f worldPos = itemWorldPosition(item, containerWorldCenters, containerWorldRotations);
            Vector3f toItem = new Vector3f(worldPos).sub(rayOrigin);
            float t = toItem.dot(rayDir);
            float itemRadius = Math.max(0.08f, Math.max(item.bounds.width, Math.max(item.bounds.height, item.bounds.depth)) * item.scaleFactor * 0.6f);
            if (t < 0) {
                System.out.println("  " + item.sourceFile + " world=" + fmt(worldPos)
                    + " BEHIND camera (t=" + t + ") - skipped");
                continue;
            }
            Vector3f closestPoint = new Vector3f(rayDir).mul(t).add(rayOrigin);
            float dist = closestPoint.distance(worldPos);
            boolean hit = dist <= itemRadius;
            System.out.println("  " + item.sourceFile + " world=" + fmt(worldPos) + " radius=" + itemRadius
                + " dist-from-ray=" + dist + " t=" + t + (hit ? " HIT" : " miss (too far from ray)"));
            if (hit && t < closestDist) {
                closestDist = t;
                closest = item;
            }
        }
        if (closest == null) {
            System.out.println("[E-key] nothing hit");
            return null;
        }
        System.out.println("[E-key] removing " + closest.sourceFile);

        items.remove(closest);
        for (PlacedItem other : items) {
            if (other.containerIndex != closest.containerIndex) continue;
            boolean overlapsX = Math.abs(other.currentX - closest.currentX)
                < (other.bounds.width * other.scaleFactor + closest.bounds.width * closest.scaleFactor) / 2f;
            boolean overlapsZ = Math.abs(other.currentZ - closest.currentZ)
                < (other.bounds.depth * other.scaleFactor + closest.bounds.depth * closest.scaleFactor) / 2f;
            if (overlapsX && overlapsZ && other.currentY > closest.currentY) {
                other.falling = true;
            }
        }
        return closest.sourceFile;
    }
}
