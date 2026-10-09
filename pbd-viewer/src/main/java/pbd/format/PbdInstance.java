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

    // Optional lever-arm open/closed endpoint pair - see LeverArm's own
    // doc below. Null means "this instance has no lever-arm motion",
    // same convention as meshData being null for a non-mesh instance.
    // Meant as an ALTERNATIVE to keyframes for authoring open/close
    // motion (a container door, say) rather than a timeline clip - see
    // LeverArm's doc for why. Nothing in the parser forbids an instance
    // from having both a non-empty keyframes list and a non-null
    // leverArm at once, but HierarchyResolver.resolve() gives keyframes
    // precedence when both are actively driven, so authoring both on
    // the same instance has no defined use today.
    public LeverArm leverArm;

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

    /** Authored open-pose endpoint for the lever-arm mechanism: a
     * simple, additive alternative to keyframe animation for things
     * like a container door swinging open, where a continuously-eased
     * 0..1 "arm value" interpolates between this instance's own base
     * position/rotationDeg (arm value 0, i.e. "closed") and
     * openPos/openRotDeg here (arm value 1, i.e. "open") - see
     * HierarchyResolver's own interpolateLeverArm. Chosen over
     * extending the keyframe system itself because a door's two
     * natural states (open/closed) aren't a timeline with a fixed
     * duration the way keyframes assume. These two endpoints and
     * `speed` only define the authored ENDS and a default scripted
     * pace between them (PbdRenderer.setOpen, eased at `speed` - see
     * LeverArmSystem.update) - they do not mean the arm value itself
     * has to move on a fixed clip. PbdRenderer.setArmValue sets the
     * live value directly instead, bypassing that scripted easing
     * entirely, which is the seam a continuous, non-scripted driver (a
     * mouse drag-and-drop, a physics step) plugs into: `speed` simply
     * doesn't apply to that caller, only openPos/openRotDeg (the two
     * ends it drags between) still do.
     *
     * At most one per instance - unlike keyframes, which are a list,
     * this is a single nullable field on PbdInstance (leverArm above).
     * openPos/openRotDeg are each independently nullable, same
     * fallback convention as Keyframe.pos/rotDeg: null = "hold this
     * instance's own base pos/rot at the open end too" - lets a
     * pure-rotation door (a hinge) write only openRot without
     * repeating its position.
     *
     * WHAT MOVES WITH THE ARM. Everything parented to the arm instance
     * (parent=, at any depth) follows it at every arm value, because a
     * child's world matrix is its parent's times its own local one
     * (HierarchyResolver). So a handle, a hinge pin, a lock plate, a
     * lamp or a whole pbd_ref'd sub-asset is "attached to the arm" just
     * by naming it as parent - and grabbing any of them with the mouse
     * drags the arm (LeverArmSystem.pick: the nearest ancestor-or-self
     * that has a leverArm is the one that moves). Mind the scale: a
     * child inherits its parent's scale too, so the arm is best put on
     * a scale-1 `group` pivot with the visible panel and its handle as
     * siblings under it, rather than on a flattened panel whose scale
     * squashes every child.
     *
     * The remaining fields are the "free rotation OR a lockable
     * position" and "SFX" parts of the original spec:
     *  - release: what happens when a held arm is let go. SNAP (the
     *    default, and what the engine always did) eases it to whichever
     *    end is nearer at `speed`; FREE leaves it exactly where the
     *    player left it (a swing door that stays ajar, a valve wheel).
     *  - locked / lockAt: the arm starts locked at arm value lockAt
     *    (default 0 = closed). A locked arm ignores drags, setOpen and
     *    setArmValue until PbdRenderer.setArmLocked(id, false); any arm
     *    can also be locked in place at runtime, wherever it stands.
     *  - openSound / closeSound: played once when the arm leaves its
     *    closed end / comes back to it - by a drag, a script or the
     *    easing alike (the arm counterpart of a keyframe's sound=).
     *
     * Still deferred (see docs/ROADMAP.md): a real physics engine
     * behind the drag (inertia, collisions) - the drag today is
     * geometric - and a dedicated rattle sound for a locked arm. */
    public static final class LeverArm {
        /** What letting go of a held arm does - see the class doc. */
        public enum Release {
            SNAP, FREE;

            /** The .pbd spelling ("snap" / "free"). */
            public String keyword() { return name().toLowerCase(java.util.Locale.ROOT); }

            /** null for anything that is not one of the two keywords. */
            public static Release fromKeyword(String s) {
                if (s == null) return null;
                return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "snap" -> SNAP;
                    case "free" -> FREE;
                    default -> null;
                };
            }
        }

        public final Vector3f openPos;    // nullable - null falls back to this instance's own base position
        public final Vector3f openRotDeg; // nullable - null falls back to this instance's own base rotationDeg
        public final float speed;         // arm-value units per second (1.0 = fully closed to fully open in 1 second); must be > 0
        public final Release release;     // never null; SNAP unless the file says free
        public final boolean locked;      // true = the arm starts locked at lockAt
        public final float lockAt;        // 0..1, the arm value a locked-at-load arm sits at (0 = closed)
        public final String openSound;    // nullable - audio file played when the arm leaves its closed end
        public final String closeSound;   // nullable - audio file played when the arm returns to its closed end

        /** The original three-field form - snap on release, unlocked, silent. */
        public LeverArm(Vector3f openPos, Vector3f openRotDeg, float speed) {
            this(openPos, openRotDeg, speed, Release.SNAP, false, 0f, null, null);
        }

        public LeverArm(Vector3f openPos, Vector3f openRotDeg, float speed,
                        Release release, boolean locked, float lockAt, String openSound, String closeSound) {
            this.openPos = openPos;
            this.openRotDeg = openRotDeg;
            this.speed = speed;
            this.release = release == null ? Release.SNAP : release;
            this.locked = locked;
            this.lockAt = lockAt;
            this.openSound = openSound;
            this.closeSound = closeSound;
        }

        /** An independent copy (the vectors are not shared with this one). */
        public LeverArm copy() {
            return new LeverArm(
                openPos == null ? null : new Vector3f(openPos),
                openRotDeg == null ? null : new Vector3f(openRotDeg),
                speed, release, locked, lockAt, openSound, closeSound);
        }
    }

    public PbdInstance(String id, String type) {
        this.id = id;
        this.type = type;
    }

    /**
     * A copy of this instance under another id, carrying EVERY field the
     * format knows - the one place that has to be kept in step with the
     * field list above. Exists because pbd_ref inlining
     * (PbdParser.parsePbdRef) used to copy a hand-picked subset
     * (id/type/pos/rot/scale/material/modifiers/params) and silently
     * dropped the rest: an asset included by reference lost its
     * keyframes, its leverArm, its lights' settings, indestructible,
     * lod/category, container triggers and embedded mesh geometry
     * (confirmed with a real cabinet asset: its door stopped opening
     * and its lamp lit with defaults the moment it was included). The
     * regression test RegressionPbdRefCopy walks this class's public
     * fields by reflection and fails when one is not copied, so a field
     * added later cannot be forgotten the same way.
     *
     * Mutable members (vectors, lists, maps) are copied, so editing the
     * copy never writes through to the original; immutable ones
     * (Keyframe aside, which is copied for its vectors, and the mesh
     * data, which nothing mutates after parsing) are shared. parentId is
     * copied verbatim and parentIndex is left unresolved (-1): the
     * caller re-parents the copy and the scene's resolveHierarchy()
     * fills the index in. Id-valued references (parentId,
     * containerTriggers, a linkGroup name) are copied as-is too -
     * renaming them consistently is the caller's job, since only the
     * caller knows what the new namespace is.
     */
    public PbdInstance copyAs(String newId) {
        PbdInstance c = new PbdInstance(newId, type);
        c.parentId = parentId;
        c.lod = lod;
        c.category = category;
        c.indestructible = indestructible;
        c.hardness = hardness;
        c.resistance = resistance;
        c.lightMode = lightMode;
        c.lightEnabled = lightEnabled;
        c.lightColor = lightColor == null ? null : new Vector3f(lightColor);
        c.lightIntensity = lightIntensity;
        c.lightRange = lightRange;
        c.lightSpotAngleDeg = lightSpotAngleDeg;
        c.position.set(position);
        c.rotation.set(rotation);
        c.rotationDeg.set(rotationDeg);
        c.scale.set(scale);
        c.material = material;
        c.modifiers.addAll(modifiers);
        c.meshData = meshData;
        c.meshDataByLod.putAll(meshDataByLod);
        c.params.putAll(params);
        c.containerTriggers.addAll(containerTriggers);
        for (Keyframe kf : keyframes) {
            c.keyframes.add(new Keyframe(kf.time,
                kf.pos == null ? null : new Vector3f(kf.pos),
                kf.rotDeg == null ? null : new Vector3f(kf.rotDeg),
                kf.sound, kf.channel,
                kf.scale == null ? null : new Vector3f(kf.scale)));
        }
        c.leverArm = leverArm == null ? null : leverArm.copy();
        return c;
    }
}
