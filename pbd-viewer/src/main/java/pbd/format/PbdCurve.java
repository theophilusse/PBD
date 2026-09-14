package pbd.format;

import org.joml.Vector3f;

import java.util.List;

/**
 * A named curve, declared once and referenceable by several `curve`
 * modifiers (e.g. several petals sharing the same curvature).
 */
public final class PbdCurve {
    public final String id;
    public final String kind;              // "bezier", "catmull-rom"...
    public final List<Vector3f> points;    // control points, in file order

    public PbdCurve(String id, String kind, List<Vector3f> points) {
        this.id = id;
        this.kind = kind;
        this.points = points;
    }
}
