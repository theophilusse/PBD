package pbd.format;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PbdInstance {
    public final String id;
    public final String type;              // validated against PrimitiveRegistry, resolved to a numeric id later

    public String parentId;                // null if root; raw name as written in the file
    // Documented in the format spec's field table since early on (a
    // level-of-detail tier, for a future distance-based swap between
    // several representations of "the same" object) but never actually
    // wired into the parser until now - null means "no LOD tier set",
    // not "tier 0", so an untagged instance round-trips without gaining
    // a value it never had. Field-level support only: parsing,
    // round-tripping, and the Blender add-on property exist; the
    // DISTANCE-BASED SWAP behavior this exists for - actually choosing
    // which of several same-purpose instances to render based on camera
    // distance - does not. That needs its own grouping concept (which
    // instances are alternates OF each other, not just any instance
    // that happens to have a number here) and its own rendering-loop
    // logic, closer in scope to the cube edge-displacement tool than to
    // a field addition - not started.
    public Integer lod;
    // Optional free-form semantic tag ("chair", "outdoor-decor",
    // whatever a content pipeline finds useful) for telling instances
    // apart by PURPOSE rather than by guessing from mat= (their visual
    // material) alone - two chairs in different woods share no material
    // name, and two totally different props can share one ("wood" on
    // both a table and a picture frame). Purely descriptive: nothing in
    // the engine reads this today, it exists for external tooling/
    // content pipelines to filter or group instances by. Distinct from
    // `type` (the PRIMITIVE kind - cube/sphere/mesh/...), which is a
    // structural fact about HOW an instance is built, not what it
    // represents.
    public String category;
    // Voxel/destruction system fields (see docs/PBD_FORMAT_SPEC.md's
    // own "Voxel destruction" section). indestructible=true means this
    // instance is never converted to voxels by whatever runtime
    // primitive->voxel transform exists (see pbd.voxel.PrimitiveVoxelizer)
    // and never affected by alterations to nearby voxelized geometry -
    // a wall or floor an author wants to guarantee stays solid
    // regardless of what happens around it, not something dynamically
    // computed from geometry or material. hardness/resistance are
    // OPTIONAL physical parameters, meaningful only alongside
    // indestructible=true (see PbdParser's own validation) - nullable
    // rather than defaulted to some arbitrary number, since "no value
    // given" and "explicitly zero" are different things a destruction
    // system might reasonably want to distinguish between.
    public boolean indestructible = false;
    public Float hardness;   // nullable
    public Float resistance; // nullable
    // Light source parameters - meaningful only when type.equals("light")
    // (see PrimitiveRegistry/PbdParser for how "light" is recognized as
    // its own primitive type, same category as "ref": no geometry of
    // its own, purely a data-carrying instance that affects how OTHER
    // geometry gets lit). Nullable/null-default rather than defaulted
    // to some arbitrary "on" value, so a non-light instance's fields
    // stay visibly unset rather than carrying meaningless zeros.
    public String lightMode;      // "point" or "spot" - null if this instance isn't a light at all
    public boolean lightEnabled = true; // runtime on/off (a light switch, say) - kept separate from lightIntensity so toggling off and back on doesn't lose the authored brightness
    public Vector3f lightColor;   // rgb, 0..1 each - null if not a light
    public Float lightIntensity;  // arbitrary brightness scale, no fixed unit (matches shininess/specularStrength's own "authored, not physical" convention)
    public Float lightRange;      // world units - distance at which this light's contribution reaches zero
    public Float lightSpotAngleDeg; // half-angle of the cone, degrees - only meaningful for lightMode="spot"
    public int parentIndex = -1;           // filled in by PbdScene.resolveHierarchy(), -1 = root

    public final Vector3f position = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();  // identity by default when there is no rot=
    public final Vector3f rotationDeg = new Vector3f();     // the raw degrees rot= was set from, kept alongside the quaternion so keyframe interpolation can fall back to it without a quaternion-to-Euler conversion
    public final Vector3f scale = new Vector3f(1, 1, 1);

    public String material;                // raw name; resolved to a numeric id by MaterialRegistry at render time
    public final List<PbdModifier> modifiers = new ArrayList<>();
    public PbdMeshData meshData;            // non-null only for type "mesh" - raw vertex/index geometry, see PbdMeshData - this is the BASE/default variant (LOD 0's data, whether or not it was actually tagged with lod=0 explicitly)
    // Additional LOD variants beyond the base meshData above, keyed by
    // LOD tier (never containing 0 - that's always meshData itself).
    // vertexDataLod<N>= fields (N >= 1) populate this; TreeMap so
    // anything iterating it (export, a future distance-swap renderer)
    // sees tiers in ascending order for free. Field-level only, same
    // caveat as PbdInstance.lod's own doc: nothing renders picks between
    // these by distance yet, this just carries the data through parsing
    // and re-serialization.
    public final java.util.Map<Integer, PbdMeshData> meshDataByLod = new java.util.TreeMap<>();

    // Non-linear parameters (top radius of a frustum, rounding, thickness...),
    // interpretation depends on the type - linear scaling goes through `scale`.
    public final Map<String, String> params = new LinkedHashMap<>();
    public final List<String> containerTriggers = new ArrayList<>(); // every containerTrigger=X line, in file order - a plain params.put() would keep only the last of several, and a storage cube can now list several doors

    // Optional playback animation - empty list means "static", matching
    // every instance before this existed. When non-empty, rendering
    // interpolates position/rotation from these instead of using the
    // fields above directly - see HierarchyResolver.interpolate.
    public final List<Keyframe> keyframes = new ArrayList<>();

    /** One keyframe: time in seconds, pos/rot optional (null = "hold whatever this instance's own base value is" at this keyframe - lets a door's keyframes only ever mention rot without needing to repeat its position every time). rotDeg in degrees, matching rot= elsewhere in the format. sound (nullable) names an audio file to play once playback reaches this keyframe's time - a door creak at the moment it starts swinging, say. */
    /** One keyframe: time is a value along whichever axis this keyframe
     * is measured on - either elapsed animation-clock seconds (channel
     * == null, the ONLY behavior that existed before channels: every
     * door/lid keyframe already in this project keeps working exactly
     * as before) or the current value of a named channel (channel !=
     * null - see pbd.render.ChannelTracker), such as "humidity"
     * accumulating at its own, condition-dependent rate rather than
     * flowing at the fixed rate of real/simulated time. pos/rot/scale
     * are each independently optional (null = "hold whatever this
     * instance's own base value is" at this keyframe) - the same
     * convention already used for pos/rot, now extended to scale so a
     * channel-driven keyframe can express growth (small -> full size)
     * without needing a different mechanism from ordinary animation. */
    public static final class Keyframe {
        public final float time;
        public final String channel; // nullable - null = standard per-instance animation clock
        public final Vector3f pos;    // nullable
        public final Vector3f rotDeg; // nullable
        public final Vector3f scale;  // nullable
        public final String sound;    // nullable

        public Keyframe(float time, Vector3f pos, Vector3f rotDeg) {
            this(time, pos, rotDeg, null, null, null);
        }

        public Keyframe(float time, Vector3f pos, Vector3f rotDeg, String sound) {
            this(time, pos, rotDeg, sound, null, null);
        }

        public Keyframe(float time, Vector3f pos, Vector3f rotDeg, String sound, String channel, Vector3f scale) {
            this.time = time;
            this.pos = pos;
            this.rotDeg = rotDeg;
            this.sound = sound;
            this.channel = channel;
            this.scale = scale;
        }
    }

    public PbdInstance(String id, String type) {
        this.id = id;
        this.type = type;
    }
}
