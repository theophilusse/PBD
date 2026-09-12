package pbd.pz;

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
 * first time the container is seen fully open (see PbdRenderer's
 * isInstanceFullyOpen), and cached from then on for that container's
 * lifetime, matching this project's own "no computation or display
 * before the container opens, cache the result" rule.
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
        public final float localCenterX, localCenterY, localCenterZ; // the mesh's own bounding-box center, in its own local space - subtract this from currentX/Y/Z only when building a render transform, never used by the physics below, which treats currentX/Y/Z as the object's actual logical center throughout
        public float targetX, targetY, targetZ; // bin-packer's assigned position, relative to container center
        public float currentX, currentY, currentZ; // where it actually is right now - equals target until something makes it fall
        public float fallVelocity = 0f;
        public boolean falling = false;

        PlacedItem(String sourceFile, ObjMesh mesh, BinPacker.Item bounds, float x, float y, float z,
                   float localCenterX, float localCenterY, float localCenterZ) {
            this.sourceFile = sourceFile;
            this.mesh = mesh;
            this.bounds = bounds;
            this.targetX = x; this.targetY = y; this.targetZ = z;
            this.currentX = x; this.currentY = y; this.currentZ = z;
            this.localCenterX = localCenterX; this.localCenterY = localCenterY; this.localCenterZ = localCenterZ;
        }
    }

    public final List<PlacedItem> items = new ArrayList<>();
    private final float containerWidth, containerHeight, containerDepth;

    private ContainerContents(float w, float h, float d) {
        this.containerWidth = w;
        this.containerHeight = h;
        this.containerDepth = d;
    }

    /**
     * Loads a random number (2..maxCount) of .fbx files from fbxDir,
     * extracts their geometry, bin-packs them into a container of the
     * given interior size, and returns the ready-to-render result. Any
     * individual file that fails to load is skipped with a console
     * warning rather than aborting the whole container - one bad FBX
     * shouldn't make an entire container's contents disappear.
     */
    public static ContainerContents loadRandom(Path fbxDir, int maxCount, float containerWidth,
                                                 float containerHeight, float containerDepth) {
        ContainerContents contents = new ContainerContents(containerWidth, containerHeight, containerDepth);
        List<Path> allFbx;
        try (var stream = Files.list(fbxDir)) {
            allFbx = stream.filter(p -> p.toString().toLowerCase().endsWith(".fbx")).toList();
        } catch (IOException e) {
            System.err.println("[ContainerContents] Could not list " + fbxDir + ": " + e.getMessage());
            return contents;
        }
        if (allFbx.isEmpty()) {
            System.err.println("[ContainerContents] No .fbx files found in " + fbxDir);
            return contents;
        }

        Random random = new Random();
        int count = Math.min(allFbx.size(), 2 + random.nextInt(Math.max(1, maxCount - 1)));
        List<Path> chosen = new ArrayList<>(allFbx);
        java.util.Collections.shuffle(chosen, random);
        chosen = chosen.subList(0, count);

        List<BinPacker.Item> packerItems = new ArrayList<>();
        var meshByName = new java.util.HashMap<String, ObjMesh>();
        var extractionByName = new java.util.HashMap<String, FbxGeometryExtractor.ExtractionResult>();

        for (Path fbxPath : chosen) {
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
                System.err.println("[ContainerContents] Skipping " + fbxPath.getFileName() + ": " + e.getMessage());
            }
        }

        System.out.println("[ContainerContents] Bin-packing " + packerItems.size() + " item(s) into a "
            + containerWidth + "x" + containerHeight + "x" + containerDepth + " container");

        BinPacker.Result packResult = new BinPacker().pack(packerItems, containerWidth, containerHeight, containerDepth);
        if (!packResult.shrunkItemIds.isEmpty()) {
            System.out.println("[ContainerContents] Resized to fit (too big otherwise): " + packResult.shrunkItemIds);
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

            PlacedItem item = new PlacedItem(placement.item.id, mesh, placement.item,
                placement.centerX, placement.centerY, placement.centerZ,
                localCenterX * placement.scaleFactor, localCenterY * placement.scaleFactor, localCenterZ * placement.scaleFactor);
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
     * and which currently sits below it - whichever is higher. */
    private float floorOrSupportY(PlacedItem item) {
        float floorY = -containerHeight / 2f + item.bounds.height / 2f;
        float highestSupport = floorY;
        for (PlacedItem other : items) {
            if (other == item || other.falling) continue;
            boolean overlapsX = Math.abs(item.currentX - other.currentX) < (item.bounds.width + other.bounds.width) / 2f;
            boolean overlapsZ = Math.abs(item.currentZ - other.currentZ) < (item.bounds.depth + other.bounds.depth) / 2f;
            if (overlapsX && overlapsZ) {
                float supportTopY = other.currentY + other.bounds.height / 2f + item.bounds.height / 2f;
                if (supportTopY > highestSupport && supportTopY <= item.targetY + 0.5f) {
                    highestSupport = supportTopY;
                }
            }
        }
        return highestSupport;
    }

    /** Removes the item whose current world-space position (relative to
     * containerWorldCenter) is closest to the ray, if the ray actually
     * passes near it - marks every remaining item currently resting
     * above the removed one's footprint as falling. Returns the removed
     * item's source filename, or null if the ray hit nothing. */
    public String removeNearestToRay(Vector3f containerWorldCenter, Vector3f rayOrigin, Vector3f rayDir) {
        PlacedItem closest = null;
        float closestDist = Float.MAX_VALUE;
        for (PlacedItem item : items) {
            Vector3f worldPos = new Vector3f(containerWorldCenter).add(item.currentX, item.currentY, item.currentZ);
            Vector3f toItem = new Vector3f(worldPos).sub(rayOrigin);
            float t = toItem.dot(rayDir);
            if (t < 0) continue;
            Vector3f closestPoint = new Vector3f(rayDir).mul(t).add(rayOrigin);
            float dist = closestPoint.distance(worldPos);
            float itemRadius = Math.max(item.bounds.width, Math.max(item.bounds.height, item.bounds.depth)) * 0.6f;
            if (dist <= itemRadius && t < closestDist) {
                closestDist = t;
                closest = item;
            }
        }
        if (closest == null) return null;

        items.remove(closest);
        for (PlacedItem other : items) {
            boolean overlapsX = Math.abs(other.currentX - closest.currentX) < (other.bounds.width + closest.bounds.width) / 2f;
            boolean overlapsZ = Math.abs(other.currentZ - closest.currentZ) < (other.bounds.depth + closest.bounds.depth) / 2f;
            if (overlapsX && overlapsZ && other.currentY > closest.currentY) {
                other.falling = true;
            }
        }
        return closest.sourceFile;
    }
}
