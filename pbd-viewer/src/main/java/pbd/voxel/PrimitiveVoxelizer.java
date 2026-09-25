package pbd.voxel;

import pbd.format.PbdInstance;
import pbd.format.PbdMeshData;

/**
 * Converts a single PBD primitive instance's canonical shape into a
 * VoxelOctree - the runtime primitive->voxel transform the roadmap
 * asks for. Mesh-type instances are explicitly out of scope for now
 * (kept for a later pass, per the roadmap's own sequencing) -
 * voxelize() returns null for one, same as for an indestructible
 * instance, rather than guessing at a triangle-soup's interior.
 *
 * Shape definitions: every canonical primitive is a 1-unit shape
 * centered at the origin in its own LOCAL space (-0.5..+0.5 on each
 * axis - the same convention instanceWorldSize/BinPacker/every other
 * part of this codebase that reasons about primitive extent already
 * uses), tested here at each candidate voxel's LOCAL-space center
 * before mapping it into the octree's integer grid. These are
 * standard parametric definitions for each shape (see each voxelizeX
 * method's own comment for the exact formula) - written from the
 * established -0.5..0.5 convention rather than cross-checked line by
 * line against the tessellation shader's own exact implementation, so
 * a small mismatch at a shape's precise boundary (a cone's exact taper
 * curve, a torus's exact tube radius) is possible and worth
 * confirming against a real render before this is trusted for
 * anything visually exacting.
 */
public final class PrimitiveVoxelizer {

    private PrimitiveVoxelizer() {}

    // A zero-volume primitive (plane, disc) gets extruded to this
    // fraction of its own largest in-plane dimension as a MINIMUM
    // thickness along its flat axis, per the roadmap's own "epaisseur
    // minimale" requirement - without this, a plane's local Y extent is
    // 0, and 0 local units maps to 0 voxels no matter how fine the
    // voxel size is, leaving nothing to insert at all. 0.08 is
    // deliberately small (a thin slab, not a chunky block) while still
    // being comfortably more than one voxel thick at any reasonable
    // voxelSize, so the extruded slab doesn't degenerate back to
    // "sometimes zero voxels thick" purely from integer rounding.
    private static final float MIN_THICKNESS_FRACTION = 0.08f;

    public static final class Result {
        public final VoxelOctree octree;
        public final float voxelWorldSize; // world-space edge length of one voxel - needed by a caller placing/rendering the result back in the scene
        public final org.joml.Vector3f gridOriginLocal; // add this to the ORIGINAL instance's own world position to correctly place voxel mesh vertices (which start at the octree grid's own (0,0,0) corner) back where the original primitive actually was - verified against a real placement test, not just derived on paper (see TestVoxelPlacement)

        Result(VoxelOctree octree, float voxelWorldSize, org.joml.Vector3f gridOriginLocal) {
            this.octree = octree;
            this.voxelWorldSize = voxelWorldSize;
            this.gridOriginLocal = gridOriginLocal;
        }
    }

    /** Maximum octree depth regardless of how fine targetVoxelWorldSize
     * asks for - a safety cap, not a normal-case limit: depth 10 alone
     * already allows 1024 voxels along the octree's OWN addressable
     * grid axis (the actual per-shape-axis voxel counts, gridX/gridY/
     * gridZ below, are usually smaller still, sized to the primitive's
     * OWN scale, not this shared power-of-2 grid). Reachable only by a
     * pathological case - a huge instance combined with a tiny
     * targetVoxelWorldSize - and exists so that combination degrades to
     * "coarser than asked for" instead of an unbounded recursion depth
     * (and the collectFilledUnitVoxels() list size that would come with
     * it) from a single instance a player could just walk up to and
     * press G on. */
    private static final int MAX_OCTREE_DEPTH = 10;

    /** Returns null for an indestructible instance (never voxelized, by
     * design - see the roadmap this implements) or a mesh-type one (not
     * supported yet). targetVoxelWorldSize is an ABSOLUTE size, in this
     * scene's own world units, NOT a voxel count - see this project's
     * own real-world scale reference (1cm is roughly a Blender scale of
     * 0.005), passed here directly rather than as a per-axis COUNT, so
     * a small prop and a large wall both get comparably fine surface
     * detail instead of the wall's own longest axis alone deciding a
     * single shared voxel count for both. Combined with
     * rasterizeAdaptive's own early return on a definitively-inside
     * region (insertFilledBox, no further recursion - see that
     * method's own doc) this is what actually produces "high-
     * definition surface, low-definition interior": asking for a much
     * finer voxelWorldSize doesn't multiply the LEAF count by the same
     * factor everywhere, only near the boundary, where classify()
     * keeps returning the ambiguous 0 that forces the recursion deeper
     * - a fully-interior region collapses into one leaf at whatever
     * depth it first tests as entirely inside, however fine the
     * requested surface resolution is. */
    public static Result voxelize(PbdInstance instance, float targetVoxelWorldSize) {
        if (instance.indestructible) return null;
        if ("mesh".equals(instance.type)) return voxelizeMesh(instance, targetVoxelWorldSize);

        float sx = instance.scale.x, sy = instance.scale.y, sz = instance.scale.z;
        boolean zeroVolume = "plane".equals(instance.type) || "disc".equals(instance.type);
        // Zero-volume primitives are flat in Y (see voxelizePlane/
        // voxelizeDisc below - both flat in the XZ plane, Y as their
        // normal) - extrude that axis to a minimum thickness, a
        // FRACTION of whichever in-plane axis is larger, so a huge flat
        // plane gets a proportionally (not just absolutely) thin slab
        // rather than either a needle-thin sliver or an unreasonably
        // thick one relative to its own footprint.
        float effectiveSy = sy;
        if (zeroVolume) {
            effectiveSy = Math.max(sx, sz) * MIN_THICKNESS_FRACTION;
        }

        float longestAxis = Math.max(effectiveSy, Math.max(sx, sz));
        if (longestAxis <= 1e-6f) return null; // degenerate (zero-scale) instance - nothing to voxelize
        float voxelWorldSize = targetVoxelWorldSize;

        int gridX = Math.max(1, Math.round(sx / voxelWorldSize));
        int gridY = Math.max(1, Math.round(effectiveSy / voxelWorldSize));
        int gridZ = Math.max(1, Math.round(sz / voxelWorldSize));
        int gridMax = Math.max(gridX, Math.max(gridY, gridZ));
        int depth = 1;
        while ((1 << depth) < gridMax && depth < MAX_OCTREE_DEPTH) depth++;
        VoxelOctree octree = new VoxelOctree(depth);
        int gridSize = octree.gridSize();

        // The primitive's shape is tested in LOCAL space; the grid's
        // extent on each axis only covers gridX/gridY/gridZ voxels out
        // of the full gridSize (a power of 2, generally larger than any
        // one axis actually needs) - centered within that so the
        // primitive sits in the middle of the addressable grid rather
        // than jammed into one corner. gridOriginLocal is this offset's
        // LOCAL-space equivalent, in case a caller wants to place voxel
        // (0,0,0) back into the scene correctly. offX/offY/offZ are
        // expressed in gridSize's OWN shared units (one octree unit,
        // uniformly, equals one real voxelWorldSize on ANY axis - see
        // this project's own history for why: it's what actually makes
        // the "high-definition surface, low-definition interior"
        // resolution scheme work at all) - so converting one of them to
        // real-world local-space units is a flat multiply by
        // voxelWorldSize, the SAME conversion factor regardless of
        // which axis or how thin the object is on it.
        int offX = (gridSize - gridX) / 2, offY = (gridSize - gridY) / 2, offZ = (gridSize - gridZ) / 2;

        switch (instance.type) {
            case "cube" -> voxelizeCube(octree, offX, offY, offZ, gridX, gridY, gridZ);
            case "cylinder" -> rasterizeAdaptive(octree, PrimitiveVoxelizer::classifyCylinder, offX, offY, offZ, gridX, gridY, gridZ, 0, 0, 0, gridSize);
            case "cone" -> rasterizeAdaptive(octree, PrimitiveVoxelizer::classifyCone, offX, offY, offZ, gridX, gridY, gridZ, 0, 0, 0, gridSize);
            case "disc" -> voxelizeDisc(octree, offX, offY, offZ, gridX, gridY, gridZ);
            case "plane" -> voxelizeCube(octree, offX, offY, offZ, gridX, gridY, gridZ); // a plane's already-extruded shape IS just a thin box - same fill as cube
            case "torus" -> rasterizeAdaptive(octree, PrimitiveVoxelizer::classifyTorus, offX, offY, offZ, gridX, gridY, gridZ, 0, 0, 0, gridSize);
            case "sphere" -> rasterizeAdaptive(octree, PrimitiveVoxelizer::classifySphere, offX, offY, offZ, gridX, gridY, gridZ, 0, 0, 0, gridSize);
            default -> {
                return null; // unknown/unsupported type - safer to produce nothing than a wrong guess
            }
        }

        // THE fix for a real, confirmed bug: this used to read
        // "-offX/(float)gridX*sx - sx/2f" - dividing offX (a gridSize-
        // scale quantity) by gridX (this axis's own, often much
        // smaller, real voxel count) and THEN scaling by sx - a unit
        // mismatch that only produced roughly-right numbers for a
        // roughly-cubic object (where gridX happens to be close to
        // gridSize, so dividing by either gives a similar answer) and
        // silently fell apart for anything with a strongly non-cubic
        // aspect ratio - confirmed by direct reproduction against a
        // real reported case (a door panel only 3 voxels thick on one
        // axis): the buggy formula placed that axis's own origin
        // offset by roughly 8% of the object's own total size on that
        // axis, which was enough to shift crater/voxel placement
        // clean off the object's own actual occupied range entirely
        // (a reported "no crater appears at all" turned out to be a
        // crater correctly computed, just centered outside the object).
        org.joml.Vector3f gridOriginLocal = new org.joml.Vector3f(
            -offX * voxelWorldSize - sx / 2f,
            -offY * voxelWorldSize - effectiveSy / 2f,
            -offZ * voxelWorldSize - sz / 2f);
        return new Result(octree, voxelWorldSize, gridOriginLocal);
    }

    /** -1 = the whole region is OUTSIDE the shape, +1 = the whole region
     * is INSIDE, 0 = uncertain (the boundary passes through it, or it's
     * too small a test to tell) - the three-way answer rasterizeAdaptive
     * below needs to decide whether to bulk-fill, skip, or recurse
     * further, without ever individually testing more voxels than the
     * shape's own boundary actually requires. Bounds are in the
     * primitive's own canonical -0.5..0.5 local space, same as every
     * other shape test in this class. */
    private interface RegionClassifier {
        int classify(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);
    }

    /** Recursively classifies successively smaller octree-grid regions
     * (mirroring VoxelOctree's OWN power-of-2 subdivision exactly, so
     * every insertFilledBox call below lands on a real node boundary)
     * against classifier, bulk-filling or skipping the INSTANT a whole
     * region is provably all-in or all-out rather than ever visiting
     * its individual voxels - this is the actual "use the octree for
     * the transformation itself, not just its storage" the roadmap
     * asked for: a cylinder's solid core gets bulk-filled in a handful
     * of large insertFilledBox calls, and empty space outside its
     * radius gets skipped in the same handful of calls, with real per-
     * voxel testing only ever happening in the thin shell of leaf
     * voxels actually straddling the curved boundary - not, as before,
     * for every single voxel in the whole bounding grid regardless of
     * whether it was anywhere near an edge.
     *
     * (gox,goy,goz,size): the CURRENT region, in absolute octree grid
     * coordinates (0..octree.gridSize()) - always a valid node's
     * extent, by construction (starts at the whole grid, halves on
     * every recursive step). (offX/offY/offZ, gx/gy/gz): how THIS
     * primitive's own grid sits within that full octree space (see
     * voxelize's own doc) - needed here to convert grid coordinates
     * into the -0.5..0.5 local space classify expects. */
    private static void rasterizeAdaptive(VoxelOctree octree, RegionClassifier classifier,
                                           int offX, int offY, int offZ, int gx, int gy, int gz,
                                           int gox, int goy, int goz, int size) {
        rasterizeAdaptive(octree, classifier, offX, offY, offZ, gx, gy, gz, gox, goy, goz, size, size, size,
            -0.5f, -0.5f, -0.5f, 1f, 1f, 1f);
    }

    /** Same recursive adaptive rasterization as the 11-arg overload
     * above (which is just this one with localOrigin=(-0.5,-0.5,-0.5)
     * and localExtent=(1,1,1) - the canonical primitive's own local
     * space, where every existing procedural-shape classifier already
     * expects to be tested), generalized to classify against ANY
     * local-space box, not just the canonical one. Needed for
     * voxelizeMesh below: an arbitrary mesh's own vertex positions are
     * NOT pre-normalized to -0.5..0.5 the way every procedural shape's
     * canonical definition is (see PBD_FORMAT_SPEC.md - "Canonical
     * cube ([-0.5, 0.5]^3)" and so on for every OTHER type), so
     * classifyMeshRegion needs to be tested against the mesh's own
     * actual bounding box instead.
     *
     * sizeX/sizeY/sizeZ: TRUE independent per-axis extent, not one
     * shared `size` the way an earlier version of this method had -
     * that version's own attempted fix for a real thin-object problem
     * (a door panel forcing needless fine subdivision on its OTHER,
     * much larger axes purely because they were coupled to the thin
     * axis's own shared size) used a "stop once size <= this shape's
     * smallest axis" heuristic instead of true per-axis splitting -
     * which, for anything WITHOUT one dramatically thin axis (a
     * cylinder, sphere, cone, torus - every ROUND shape this project
     * has, where minAxisVoxels sits close to gridMax), stopped
     * subdivision after essentially ONE level, confirmed directly: a
     * (0.5,0.5,0.5) cylinder produced ONE single 128^3 filled region -
     * its entire bounding cube, curvature never actually resolved at
     * all - and a cone/sphere/torus at the same scale produced ZERO
     * voxels, disappearing outright. insertFilledBox (VoxelOctree's
     * own) already accepts independent min/max per axis - always did -
     * so true anisotropic splitting needed no change to the octree
     * itself, only to how this method recurses: each axis now halves
     * and keeps recursing independently, stopping (staying fixed) once
     * ITS OWN sizeAxis reaches 1, not once EVERY axis collectively
     * reaches some shared threshold - a genuinely thin axis still
     * settles fast (little for it to resolve either way), the other
     * two keep refining as long as they still need to, and a region
     * entirely outside the shape's own footprint on any axis still
     * prunes immediately via the existing result<0 check below,
     * regardless of size - that pruning was never the bottleneck; the
     * shared-size coupling was. */
    private static void rasterizeAdaptive(VoxelOctree octree, RegionClassifier classifier,
                                           int offX, int offY, int offZ, int gx, int gy, int gz,
                                           int gox, int goy, int goz, int sizeX, int sizeY, int sizeZ,
                                           float localOriginX, float localOriginY, float localOriginZ,
                                           float localExtentX, float localExtentY, float localExtentZ) {
        float minX = (gox - offX) / (float) gx * localExtentX + localOriginX;
        float maxX = (gox + sizeX - offX) / (float) gx * localExtentX + localOriginX;
        float minY = (goy - offY) / (float) gy * localExtentY + localOriginY;
        float maxY = (goy + sizeY - offY) / (float) gy * localExtentY + localOriginY;
        float minZ = (goz - offZ) / (float) gz * localExtentZ + localOriginZ;
        float maxZ = (goz + sizeZ - offZ) / (float) gz * localExtentZ + localOriginZ;

        int result = classifier.classify(minX, minY, minZ, maxX, maxY, maxZ);
        if (result > 0) {
            octree.insertFilledBox(gox, goy, goz, gox + sizeX, goy + sizeY, goz + sizeZ);
            return;
        }
        if (result < 0) {
            return; // fully outside - skip this whole region, none of its voxels get visited at all
        }
        if (sizeX == 1 && sizeY == 1 && sizeZ == 1) {
            // Ambiguous even at the single finest voxel on every axis
            // means the boundary passes exactly through it - fall back
            // to a definitive point test at its center, the same
            // criterion the very first version of this method always
            // used for its own size==1 base case.
            float cx = (gox + 0.5f - offX) / gx * localExtentX + localOriginX;
            float cy = (goy + 0.5f - offY) / gy * localExtentY + localOriginY;
            float cz = (goz + 0.5f - offZ) / gz * localExtentZ + localOriginZ;
            if (classifier.classify(cx, cy, cz, cx, cy, cz) >= 0) {
                octree.insertFilledBox(gox, goy, goz, gox + 1, goy + 1, goz + 1);
            }
            return;
        }
        // Only split an axis that still has room to (sizeAxis > 1) -
        // an axis already at 1 stays fixed while the others keep
        // refining, the actual anisotropy this whole rewrite is for.
        boolean splitX = sizeX > 1, splitY = sizeY > 1, splitZ = sizeZ > 1;
        int halfX = splitX ? sizeX / 2 : sizeX;
        int halfY = splitY ? sizeY / 2 : sizeY;
        int halfZ = splitZ ? sizeZ / 2 : sizeZ;
        int countX = splitX ? 2 : 1, countY = splitY ? 2 : 1, countZ = splitZ ? 2 : 1;
        for (int ix = 0; ix < countX; ix++) {
            for (int iy = 0; iy < countY; iy++) {
                for (int iz = 0; iz < countZ; iz++) {
                    rasterizeAdaptive(octree, classifier, offX, offY, offZ, gx, gy, gz,
                        gox + ix * halfX, goy + iy * halfY, goz + iz * halfZ, halfX, halfY, halfZ,
                        localOriginX, localOriginY, localOriginZ, localExtentX, localExtentY, localExtentZ);
                }
            }
        }
    }

    // ============================================================
    // Closest/farthest-point-in-AABB-from-a-reference helpers - the
    // standard box/sphere intersection technique (the closest point in
    // an axis-aligned box to any reference point is each axis
    // independently clamped into the box's own range on that axis; the
    // farthest is always one of the box's corners, specifically the one
    // combining whichever of that axis's two extremes has the larger
    // magnitude, independently per axis) - reused across every radial
    // shape test below rather than re-derived per shape.
    // ============================================================

    private static float closestDistSqXZ(float minX, float minZ, float maxX, float maxZ) {
        float cx = Math.max(minX, Math.min(0f, maxX));
        float cz = Math.max(minZ, Math.min(0f, maxZ));
        return cx * cx + cz * cz;
    }

    private static float farthestDistSqXZ(float minX, float minZ, float maxX, float maxZ) {
        float fx = Math.max(Math.abs(minX), Math.abs(maxX));
        float fz = Math.max(Math.abs(minZ), Math.abs(maxZ));
        return fx * fx + fz * fz;
    }

    /** min/max of v^2 over the interval [lo,hi] - 0 if the interval
     * straddles zero (the minimum |v| achievable is then exactly 0,
     * not min(|lo|,|hi|) as it would be for an interval entirely on
     * one side), otherwise the smaller-magnitude endpoint squared. Used
     * for both the cylinder/cone/sphere's own Y-axis reasoning and the
     * torus's radial-distance reasoning, both of which need this same
     * "closest/farthest squared value over an interval" idea in 1D. */
    private static float closestSqOverInterval(float lo, float hi) {
        if (lo <= 0f && hi >= 0f) return 0f;
        return Math.min(lo * lo, hi * hi);
    }

    private static float farthestSqOverInterval(float lo, float hi) {
        return Math.max(lo * lo, hi * hi);
    }

    /** cylinder: axis along Y, radius 0.5 in XZ, height -0.5..0.5 in Y. */
    private static int classifyCylinder(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (maxY <= -0.5f || minY >= 0.5f) return -1;
        boolean yFullyInside = minY >= -0.5f && maxY <= 0.5f;
        float closestSq = closestDistSqXZ(minX, minZ, maxX, maxZ);
        float farthestSq = farthestDistSqXZ(minX, minZ, maxX, maxZ);
        if (yFullyInside && farthestSq <= 0.25f) return 1;
        if (closestSq > 0.25f) return -1;
        return 0;
    }

    /** cone: axis along Y, apex at y=+0.5, base radius 0.5 at y=-0.5,
     * radius(y) = 0.5*(0.5-y) - linearly DECREASING in y, so its min
     * over an interval is at that interval's own max-y end, and vice
     * versa. */
    private static int classifyCone(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (maxY <= -0.5f || minY >= 0.5f) return -1;
        boolean yFullyInside = minY >= -0.5f && maxY <= 0.5f;
        float clampedMinY = Math.max(minY, -0.5f), clampedMaxY = Math.min(maxY, 0.5f);
        float minRadius = Math.max(0f, 0.5f * (0.5f - clampedMaxY));
        float maxRadius = Math.max(0f, 0.5f * (0.5f - clampedMinY));
        float closestSq = closestDistSqXZ(minX, minZ, maxX, maxZ);
        float farthestSq = farthestDistSqXZ(minX, minZ, maxX, maxZ);
        if (yFullyInside && farthestSq <= minRadius * minRadius) return 1;
        if (closestSq > maxRadius * maxRadius) return -1;
        return 0;
    }

    /** sphere: radius 0.5, centered at origin. */
    private static int classifySphere(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float closestSq = closestDistSqXZ(minX, minZ, maxX, maxZ) + closestSqOverInterval(minY, maxY);
        float farthestSq = farthestDistSqXZ(minX, minZ, maxX, maxZ) + farthestSqOverInterval(minY, maxY);
        if (farthestSq <= 0.25f) return 1;
        if (closestSq > 0.25f) return -1;
        return 0;
    }

    /** torus: (sqrt(x^2+z^2) - majorRadius)^2 + y^2 <= minorRadius^2 -
     * non-convex (hollow ring), handled by first bounding the RADIAL
     * distance from the Y axis (closest/farthest XZ point, same as
     * cylinder/cone), then bounding how far THAT radial value can be
     * from majorRadius (its own closest/farthest-over-an-interval
     * question, same helper as the Y-axis reasoning elsewhere in this
     * class), then combining with the Y-axis bound exactly like sphere
     * does - three applications of the same underlying idea nested
     * together, rather than one shape-specific derivation. */
    private static int classifyTorus(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        final float majorRadius = 0.35f, minorRadius = 0.15f;
        float closestR = (float) Math.sqrt(closestDistSqXZ(minX, minZ, maxX, maxZ));
        float farthestR = (float) Math.sqrt(farthestDistSqXZ(minX, minZ, maxX, maxZ));
        float minRadialDist = closestR - majorRadius;
        float maxRadialDist = farthestR - majorRadius;

        float closestRadialSq = closestSqOverInterval(minRadialDist, maxRadialDist);
        float farthestRadialSq = farthestSqOverInterval(minRadialDist, maxRadialDist);
        float closestYSq = closestSqOverInterval(minY, maxY);
        float farthestYSq = farthestSqOverInterval(minY, maxY);

        float closestTotalSq = closestRadialSq + closestYSq;
        float farthestTotalSq = farthestRadialSq + farthestYSq;

        if (farthestTotalSq <= minorRadius * minorRadius) return 1;
        if (closestTotalSq > minorRadius * minorRadius) return -1;
        return 0;
    }

    /** cube: filled everywhere - the trivial case, and the only one
     * that can go straight to a single bulk box-fill with no per-voxel
     * shape test needed at all. */
    private static void voxelizeCube(VoxelOctree octree, int offX, int offY, int offZ, int gx, int gy, int gz) {
        octree.insertFilledBox(offX, offY, offZ, offX + gx, offY + gy, offZ + gz);
    }

    /** disc: flat in XZ (Y is the normal axis, already extruded to
     * MIN_THICKNESS_FRACTION by the caller before this is reached) -
     * a filled circle of radius 0.5, full extruded thickness in Y. Left
     * as a direct per-column loop (not adaptive-rasterized like the 3D
     * shapes above) - it's already only O(gx*gz), a 2D sweep, not the
     * O(gx*gy*gz) a naive 3D shape loop would be, so the same
     * "meaningful savings" case the roadmap's octree note is really
     * about doesn't apply here the same way. */
    private static void voxelizeDisc(VoxelOctree octree, int offX, int offY, int offZ, int gx, int gy, int gz) {
        for (int ix = 0; ix < gx; ix++) {
            for (int iz = 0; iz < gz; iz++) {
                float lx = (ix + 0.5f) / gx - 0.5f;
                float lz = (iz + 0.5f) / gz - 0.5f;
                if (lx * lx + lz * lz <= 0.25f) {
                    octree.insertFilledBox(offX + ix, offY, offZ + iz, offX + ix + 1, offY + gy, offZ + iz + 1);
                }
            }
        }
    }

    /** Voxelizes an arbitrary triangulated "mesh"-type instance - the
     * case every OTHER voxelize* method above doesn't need to handle,
     * since every procedural shape is defined analytically (a
     * classifyX closest/farthest-point test against the shape's own
     * equation) rather than needing to consult actual triangle data at
     * all. A mesh's own instance.meshData.positions are NOT pre-
     * normalized to the canonical -0.5..0.5 space every other type
     * uses (see PbdMeshData's own doc - a mesh is "deliberately
     * whatever geometry it is"), so this computes the mesh's OWN
     * bounding box first and sizes/positions the grid against THAT,
     * rather than assuming instance.scale alone determines extent the
     * way it does for a canonical shape.
     *
     * Classification per candidate region: any triangle whose own AABB
     * overlaps the region at all forces a BOUNDARY result (further
     * recursion) - deliberately conservative (a triangle merely near,
     * not necessarily crossing, the region can still trigger this),
     * trading a bit of extra recursion at the shell for never
     * mis-classifying a boundary region as solid interior. Only once
     * NO triangle's AABB overlaps a region at all is it safe to fall
     * back to the ray-parity inside/outside test - correct only for a
     * closed (watertight) mesh, which most real single-object game
     * props are, but not guaranteed by the format; an open/non-
     * manifold mesh can produce a wrong (typically: entirely empty,
     * since a ray from outside a leaky mesh tends to see an even
     * number of crossings) result rather than crashing. */
    private static Result voxelizeMesh(PbdInstance instance, float targetVoxelWorldSize) {
        PbdMeshData mesh = instance.meshData;
        if (mesh == null || mesh.indices.length < 3) return null; // no geometry to voxelize at all

        float[] pos = mesh.positions;
        float meshMinX = Float.MAX_VALUE, meshMinY = Float.MAX_VALUE, meshMinZ = Float.MAX_VALUE;
        float meshMaxX = -Float.MAX_VALUE, meshMaxY = -Float.MAX_VALUE, meshMaxZ = -Float.MAX_VALUE;
        for (int i = 0; i < pos.length; i += 3) {
            meshMinX = Math.min(meshMinX, pos[i]); meshMaxX = Math.max(meshMaxX, pos[i]);
            meshMinY = Math.min(meshMinY, pos[i + 1]); meshMaxY = Math.max(meshMaxY, pos[i + 1]);
            meshMinZ = Math.min(meshMinZ, pos[i + 2]); meshMaxZ = Math.max(meshMaxZ, pos[i + 2]);
        }
        // instance.scale still applies on top of the mesh's own local
        // units - same as every render path (buildMeshRenderer's own
        // world matrix) already scales a mesh instance by - so the
        // WORLD-space extent driving voxel resolution needs it too, not
        // just the mesh's own raw local-space bounding box.
        float sx = instance.scale.x, sy = instance.scale.y, sz = instance.scale.z;
        float extentX = (meshMaxX - meshMinX) * sx;
        float extentY = (meshMaxY - meshMinY) * sy;
        float extentZ = (meshMaxZ - meshMinZ) * sz;
        float longestAxis = Math.max(extentX, Math.max(extentY, extentZ));
        if (longestAxis <= 1e-6f) return null; // degenerate (zero-size) mesh

        float voxelWorldSize = targetVoxelWorldSize;
        // Local-space voxel size (before instance.scale) - the grid
        // itself is sized/walked in the mesh's own local units, same
        // as every other voxelize* method works in the primitive's own
        // local space before the caller (Main.java's G-key handler)
        // places the result back using the instance's actual world
        // transform.
        float localVoxelX = voxelWorldSize / Math.max(sx, 1e-6f);
        float localVoxelY = voxelWorldSize / Math.max(sy, 1e-6f);
        float localVoxelZ = voxelWorldSize / Math.max(sz, 1e-6f);

        int gridX = Math.max(1, Math.round((meshMaxX - meshMinX) / localVoxelX));
        int gridY = Math.max(1, Math.round((meshMaxY - meshMinY) / localVoxelY));
        int gridZ = Math.max(1, Math.round((meshMaxZ - meshMinZ) / localVoxelZ));
        int gridMax = Math.max(gridX, Math.max(gridY, gridZ));
        int depth = 1;
        while ((1 << depth) < gridMax && depth < MAX_OCTREE_DEPTH) depth++;
        VoxelOctree octree = new VoxelOctree(depth);
        int gridSize = octree.gridSize();
        int offX = (gridSize - gridX) / 2, offY = (gridSize - gridY) / 2, offZ = (gridSize - gridZ) / 2;

        final float fMeshMinX = meshMinX, fMeshMinY = meshMinY, fMeshMinZ = meshMinZ;
        final float fMeshMaxX = meshMaxX, fMeshMaxY = meshMaxY, fMeshMaxZ = meshMaxZ;
        RegionClassifier classifier = (minX, minY, minZ, maxX, maxY, maxZ) ->
            classifyMeshRegion(pos, mesh.indices, fMeshMinX, fMeshMinY, fMeshMinZ, fMeshMaxX, fMeshMaxY, fMeshMaxZ,
                minX, minY, minZ, maxX, maxY, maxZ);

        rasterizeAdaptive(octree, classifier, offX, offY, offZ, gridX, gridY, gridZ, 0, 0, 0, gridSize, gridSize, gridSize,
            meshMinX, meshMinY, meshMinZ,
            meshMaxX - meshMinX, meshMaxY - meshMinY, meshMaxZ - meshMinZ);

        // Same gridOriginLocal convention as every other voxelize*
        // result - where the octree's own (0,0,0) grid corner sits
        // relative to the INSTANCE's own local origin, so the caller
        // can place voxel (0,0,0) back where the mesh actually was.
        // Built from the mesh's own bounding-box min, not the "-scale/2"
        // canonical-shape formula every other type uses (a mesh's own
        // local origin isn't necessarily its bounding-box center the
        // way a canonical shape's is).
        // Same fix as the other gridOriginLocal computation above (see
        // that one's own doc for the full story: offX/offY/offZ are in
        // gridSize's OWN shared octree units, where one unit always
        // equals voxelWorldSize regardless of this axis's own gridX/
        // gridY/gridZ - dividing by gridX instead of just multiplying
        // by voxelWorldSize was the actual bug, confirmed by direct
        // reproduction against a real case).
        org.joml.Vector3f gridOriginLocal = new org.joml.Vector3f(
            meshMinX - offX * voxelWorldSize,
            meshMinY - offY * voxelWorldSize,
            meshMinZ - offZ * voxelWorldSize);
        return new Result(octree, voxelWorldSize, gridOriginLocal);
    }

    /** -1/0/+1, same contract as every other classifyX above, but
     * against real triangle data instead of a shape equation - see
     * voxelizeMesh's own doc for the full reasoning (conservative
     * AABB-overlap boundary test, ray-parity fallback for a region no
     * triangle's AABB touches at all). */
    private static int classifyMeshRegion(float[] pos, int[] indices,
                                           float meshMinX, float meshMinY, float meshMinZ,
                                           float meshMaxX, float meshMaxY, float meshMaxZ,
                                           float minX, float minY, float minZ,
                                           float maxX, float maxY, float maxZ) {
        for (int i = 0; i < indices.length; i += 3) {
            int i0 = indices[i] * 3, i1 = indices[i + 1] * 3, i2 = indices[i + 2] * 3;
            if (triangleIntersectsAABB(
                    pos[i0], pos[i0 + 1], pos[i0 + 2],
                    pos[i1], pos[i1 + 1], pos[i1 + 2],
                    pos[i2], pos[i2 + 1], pos[i2 + 2],
                    minX, minY, minZ, maxX, maxY, maxZ)) {
                return 0; // boundary - recurse further, or (at size==1) the caller's point-test fallback handles it
            }
        }
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, cz = (minZ + maxZ) / 2f;
        return isPointInsideMesh(pos, indices, meshMinX, meshMinY, meshMinZ, meshMaxX, meshMaxY, meshMaxZ, cx, cy, cz) ? 1 : -1;
    }

    /** Parity (odd/even crossing count) test along a fixed +X ray from
     * (px,py,pz) out past the mesh's own meshMaxX - correct for a
     * closed/watertight mesh, the assumption voxelizeMesh's own doc
     * names explicitly. Using the mesh's OWN bounding max (not an
     * arbitrary large number) as the ray's far end keeps every
     * intersection test's own numeric range close to the mesh's
     * actual scale rather than needlessly spanning to some unrelated
     * "far enough" constant. */
    private static boolean isPointInsideMesh(float[] pos, int[] indices,
                                              float meshMinX, float meshMinY, float meshMinZ,
                                              float meshMaxX, float meshMaxY, float meshMaxZ,
                                              float px, float py, float pz) {
        float rayEndX = meshMaxX + 1f; // 1 unit past the mesh's own bounds - comfortably outside, still close to the mesh's own scale
        int crossings = 0;
        for (int i = 0; i < indices.length; i += 3) {
            int i0 = indices[i] * 3, i1 = indices[i + 1] * 3, i2 = indices[i + 2] * 3;
            if (rayTriangleCrossesPositiveX(px, py, pz, rayEndX,
                    pos[i0], pos[i0 + 1], pos[i0 + 2],
                    pos[i1], pos[i1 + 1], pos[i1 + 2],
                    pos[i2], pos[i2 + 1], pos[i2 + 2])) {
                crossings++;
            }
        }
        return (crossings % 2) == 1;
    }

    /** True if the segment from (px,py,pz) to (rayEndX,py,pz) - a ray
     * along +X at a fixed Y/Z - crosses triangle (v0,v1,v2). Standard
     * Möller-Trumbore ray-triangle intersection, specialized for a
     * fixed axis-aligned direction (1,0,0) rather than an arbitrary
     * one, since that's all isPointInsideMesh above ever needs. */
    private static boolean rayTriangleCrossesPositiveX(float px, float py, float pz, float rayEndX,
                                                         float v0x, float v0y, float v0z,
                                                         float v1x, float v1y, float v1z,
                                                         float v2x, float v2y, float v2z) {
        float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
        float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;
        // dir = (1,0,0): cross(dir, e2) simplifies to (0, dir.z*e2... )
        // - expanded directly rather than via a general cross-product
        // helper, since the fixed (1,0,0) direction cancels most terms.
        float px_ = 0 * e2z - 0 * e2y;      // = 0
        float py_ = 0 * e2x - 1 * e2z;      // = -e2z
        float pz_ = 1 * e2y - 0 * e2x;      // = e2y
        float det = e1x * px_ + e1y * py_ + e1z * pz_;
        if (Math.abs(det) < 1e-9f) return false; // ray parallel to the triangle's plane
        float invDet = 1f / det;

        float tx = px - v0x, ty = py - v0y, tz = pz - v0z;
        float u = (tx * px_ + ty * py_ + tz * pz_) * invDet;
        if (u < 0f || u > 1f) return false;

        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (1 * qx + 0 * qy + 0 * qz) * invDet; // dot(dir, q), dir=(1,0,0)
        if (v < 0f || u + v > 1f) return false;

        float t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        float maxT = rayEndX - px;
        return t > 1e-6f && t < maxT;
    }

    /** Standard triangle/AABB overlap test (Akenine-Möller): bounding-
     * box reject, triangle-plane/box reject, then the 9 cross-product
     * (edge x axis) separating-axis tests a bounding-box-only check
     * would miss - a thin triangle can pass both faster checks above
     * while still not actually touching the box, or vice versa near an
     * edge, which is exactly what these 9 catch. */
    private static boolean triangleIntersectsAABB(float v0x, float v0y, float v0z,
                                                    float v1x, float v1y, float v1z,
                                                    float v2x, float v2y, float v2z,
                                                    float boxMinX, float boxMinY, float boxMinZ,
                                                    float boxMaxX, float boxMaxY, float boxMaxZ) {
        // Bounding-box reject.
        if (Math.max(v0x, Math.max(v1x, v2x)) < boxMinX || Math.min(v0x, Math.min(v1x, v2x)) > boxMaxX) return false;
        if (Math.max(v0y, Math.max(v1y, v2y)) < boxMinY || Math.min(v0y, Math.min(v1y, v2y)) > boxMaxY) return false;
        if (Math.max(v0z, Math.max(v1z, v2z)) < boxMinZ || Math.min(v0z, Math.min(v1z, v2z)) > boxMaxZ) return false;

        float cx = (boxMinX + boxMaxX) / 2f, cy = (boxMinY + boxMaxY) / 2f, cz = (boxMinZ + boxMaxZ) / 2f;
        float ex = (boxMaxX - boxMinX) / 2f, ey = (boxMaxY - boxMinY) / 2f, ez = (boxMaxZ - boxMinZ) / 2f;
        // Triangle verts relative to the box center.
        float t0x = v0x - cx, t0y = v0y - cy, t0z = v0z - cz;
        float t1x = v1x - cx, t1y = v1y - cy, t1z = v1z - cz;
        float t2x = v2x - cx, t2y = v2y - cy, t2z = v2z - cz;

        // Triangle-plane/box reject.
        float nx = (t1y - t0y) * (t2z - t0z) - (t1z - t0z) * (t2y - t0y);
        float ny = (t1z - t0z) * (t2x - t0x) - (t1x - t0x) * (t2z - t0z);
        float nz = (t1x - t0x) * (t2y - t0y) - (t1y - t0y) * (t2x - t0x);
        float planeDist = nx * t0x + ny * t0y + nz * t0z;
        float radius = ex * Math.abs(nx) + ey * Math.abs(ny) + ez * Math.abs(nz);
        if (Math.abs(planeDist) > radius) return false;

        // The 9 edge-cross-axis separating-axis tests.
        float[][] edges = {{t1x - t0x, t1y - t0y, t1z - t0z}, {t2x - t1x, t2y - t1y, t2z - t1z}, {t0x - t2x, t0y - t2y, t0z - t2z}};
        float[][] verts = {{t0x, t0y, t0z}, {t1x, t1y, t1z}, {t2x, t2y, t2z}};
        float[][] axes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (float[] edge : edges) {
            for (float[] axis : axes) {
                float ax = edge[1] * axis[2] - edge[2] * axis[1];
                float ay = edge[2] * axis[0] - edge[0] * axis[2];
                float az = edge[0] * axis[1] - edge[1] * axis[0];
                if (ax == 0 && ay == 0 && az == 0) continue; // edge parallel to this axis - degenerate cross product, no separating axis here
                float p0 = verts[0][0] * ax + verts[0][1] * ay + verts[0][2] * az;
                float p1 = verts[1][0] * ax + verts[1][1] * ay + verts[1][2] * az;
                float p2 = verts[2][0] * ax + verts[2][1] * ay + verts[2][2] * az;
                float triMin = Math.min(p0, Math.min(p1, p2)), triMax = Math.max(p0, Math.max(p1, p2));
                float boxRadius = ex * Math.abs(ax) + ey * Math.abs(ay) + ez * Math.abs(az);
                if (triMin > boxRadius || triMax < -boxRadius) return false;
            }
        }
        return true;
    }
}
