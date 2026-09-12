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
    public int parentIndex = -1;           // filled in by PbdScene.resolveHierarchy(), -1 = root

    public final Vector3f position = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();  // identity by default when there is no rot=
    public final Vector3f rotationDeg = new Vector3f();     // the raw degrees rot= was set from, kept alongside the quaternion so keyframe interpolation can fall back to it without a quaternion-to-Euler conversion
    public final Vector3f scale = new Vector3f(1, 1, 1);

    public String material;                // raw name; resolved to a numeric id by MaterialRegistry at render time
    public final List<PbdModifier> modifiers = new ArrayList<>();

    // Non-linear parameters (top radius of a frustum, rounding, thickness...),
    // interpretation depends on the type - linear scaling goes through `scale`.
    public final Map<String, String> params = new LinkedHashMap<>();

    // Optional playback animation - empty list means "static", matching
    // every instance before this existed. When non-empty, rendering
    // interpolates position/rotation from these instead of using the
    // fields above directly - see HierarchyResolver.interpolate.
    public final List<Keyframe> keyframes = new ArrayList<>();

    /** One keyframe: time in seconds, pos/rot optional (null = "hold whatever this instance's own base value is" at this keyframe - lets a door's keyframes only ever mention rot without needing to repeat its position every time). rotDeg in degrees, matching rot= elsewhere in the format. */
    public static final class Keyframe {
        public final float time;
        public final Vector3f pos;    // nullable
        public final Vector3f rotDeg; // nullable

        public Keyframe(float time, Vector3f pos, Vector3f rotDeg) {
            this.time = time;
            this.pos = pos;
            this.rotDeg = rotDeg;
        }
    }

    public PbdInstance(String id, String type) {
        this.id = id;
        this.type = type;
    }
}
