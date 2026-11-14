package pbd.voxel;

import pbd.format.ModifierParams;
import pbd.format.PbdInstance;
import pbd.format.PbdModifier;
import pbd.format.PrimitiveParts;

import java.util.ArrayList;
import java.util.List;

/**
 * The surface of a primitive exactly as the GPU draws it: a Java port of pbd.tese - the canonical evaluation of every primitive
 * (evalPlane ... evalCubeFaceSmooth) and of the modifiers that act on it (applyBend, applyShear, applyTaper), in the order the
 * file lists them. It is what lets the voxel conversion of a destroyed object keep EVERY parameter the object is drawn with: a
 * hollow cylinder stays hollow, a cube stays open where its {@code faces} say so, a bent pole stays bent, a sheared leaf stays
 * sheared, because the voxels are built from the very surface the shader would have produced.
 *
 * The shader is the specification; this port is checked against it two ways (tools/regression/RegressionShaderPort): numbers
 * computed independently from the GLSL text (an own port in another language, tools/regression/fixtures/shader_points.txt), and
 * the geometric invariants the shader's comments promise (a bend keeps the length of its centre line, a taper is linear in the
 * height, the six faces of a cube meet at their edges).
 *
 * {@code twist} and {@code curve} are no-ops on the GPU today ("MOD_TWIST / MOD_CURVE: not wired up here yet") and therefore
 * here too: an object drawn straight is voxelized straight.
 *
 * All in double precision. Positions and normals are in the primitive's canonical LOCAL space (the unit shape centred on the
 * origin, BEFORE the instance scale); only the bend looks at the scale (its aspect ratio), exactly like the shader, which reads
 * the world scale out of the world matrix.
 */
public final class ShapeEval {

    private static final double TAU = 2.0 * Math.PI;
    private static final double TORUS_MAJOR = 0.35;
    private static final double TORUS_MINOR = 0.15;

    private enum Kind { PLANE, SPHERE, CYLINDER, CONE, CUBE, DISC, TORUS }

    /** One modifier that does something on the GPU, with its numbers read once. */
    private record Mod(char kind, double a, double b, double c) {}

    private final Kind kind;
    private final double smoothX, smoothY, smoothZ;
    private final boolean anySmooth;
    /** Built by {@link #ofShell}: the surface is sampled for voxels, not drawn - the rounded cube's faces are regular where the shader's fold. */
    private final boolean regularized;
    private final List<Mod> mods = new ArrayList<>();
    private final double scaleX, scaleY, scaleZ;
    private final int[] parts;

    private ShapeEval(Kind kind, PbdInstance inst, double minRoundWorld) {
        this.kind = kind;
        float[] smooth = kind == Kind.CUBE ? PrimitiveParts.cubeSmoothness(inst) : new float[]{0f, 0f, 0f};
        this.anySmooth = smooth[0] > 0.001f || smooth[1] > 0.001f || smooth[2] > 0.001f;
        this.scaleX = inst.scale.x;
        this.scaleY = inst.scale.y;
        this.scaleZ = inst.scale.z;
        double sx = smooth[0], sy = smooth[1], sz = smooth[2];
        this.regularized = anySmooth && minRoundWorld > 0;
        if (regularized) {
            // see ofShell: a rounding smaller than a fraction of a voxel is made that big (an exact 0 stays 0 and is handled by the face itself)
            sx = widen(sx, minRoundWorld, scaleX);
            sy = widen(sy, minRoundWorld, scaleY);
            sz = widen(sz, minRoundWorld, scaleZ);
        }
        this.smoothX = sx;
        this.smoothY = sy;
        this.smoothZ = sz;
        this.parts = PrimitiveParts.of(inst);
        for (PbdModifier m : inst.modifiers) {
            float[] v = ModifierParams.vec4(m);       // the numbers the shader's buffer holds (the renderer uploads the very same)
            switch (m.type) {
                case "bend" -> mods.add(new Mod('b', v[0], (int) v[1], 0));
                case "shear" -> mods.add(new Mod('s', v[0], v[1], v[2]));
                case "taper" -> mods.add(new Mod('t', v[0], v[1], 0));
                default -> { } // twist, curve: not wired up on the GPU - nothing to port
            }
        }
    }

    /** The evaluator of an instance, or null for one with no analytic surface (mesh, group, light, ref, an unknown type). */
    public static ShapeEval of(PbdInstance inst) {
        return create(inst, 0.0);
    }

    /**
     * The evaluator to SAMPLE the surface of a hollow rounded cube with: {@link #of} made regular where the shader's own mapping is
     * not. Two corner cases of pbd.tese, both about a rounding that is (nearly) 0 on one axis and not on the others (a cushion:
     * {@code smoothY=0 smoothX=0.2}):
     * <ul>
     *   <li>an axis rounded by less than {@code minRoundWorld} metres is rounded by that much instead - a sliver of the (u,v) square
     *       would otherwise carry a whole quarter ellipse and a lattice over (u,v) could not resolve it; the shape changes by less
     *       than the width of a voxel;</li>
     *   <li>the faces perpendicular to an axis rounded by exactly 0 get a regular mapping of their own. The shader folds the band of
     *       such a face (the outer part of the flat square, beyond the inner box) onto the rim - every vertex of the band lands on the
     *       same line and the GPU fills the gap with the long triangles that join the band to the rim. On screen that is the whole flat
     *       top, up to the rounded outline; as a surface of (u,v) it has no area, which a lattice cannot sample. Here the band is
     *       stretched over the region between the inner box and the outline (a square onto a quarter ellipse, radially), which
     *       is the surface the GPU's triangles cover.</li>
     * </ul>
     */
    public static ShapeEval ofShell(PbdInstance inst, double minRoundWorld) {
        return create(inst, minRoundWorld);
    }

    private static double widen(double s, double minRoundWorld, double scale) {
        if (s <= 0.0) return s;
        return Math.min(0.5, Math.max(s, minRoundWorld / Math.max(Math.abs(scale), 1e-9)));
    }

    private static ShapeEval create(PbdInstance inst, double minRoundWorld) {
        Kind k = switch (inst.type) {
            case "plane" -> Kind.PLANE;
            case "sphere" -> Kind.SPHERE;
            case "cylinder" -> Kind.CYLINDER;
            case "cone" -> Kind.CONE;
            case "cube" -> Kind.CUBE;
            case "disc" -> Kind.DISC;
            case "torus" -> Kind.TORUS;
            default -> null;
        };
        return k == null ? null : new ShapeEval(k, inst, minRoundWorld);
    }

    /** Whether this is a cube drawn with rounded edges (the shader's evalCubeFaceSmooth branch). */
    public boolean isRoundedCube() {
        return kind == Kind.CUBE && anySmooth;
    }

    /** The rounding radius per axis of a rounded cube, as fractions of the cube (0..0.5), after any {@link #ofShell} widening. */
    public double[] smoothness() {
        return new double[]{smoothX, smoothY, smoothZ};
    }

    /**
     * Which cube axes the u and v coordinates of a face run along: {u axis, v axis}, 0 = X, 1 = Y, 2 = Z (the faces of
     * evalCubeFaceFlat: +X and -X run u along Z and v along Y; +Y and -Y u along X and v along Z; +Z and -Z u along X and v along Y).
     */
    public static int[] cubeFaceAxes(int face) {
        return switch (face) {
            case 0, 1 -> new int[]{2, 1};
            case 2, 3 -> new int[]{0, 2};
            default -> new int[]{0, 1};
        };
    }

    /** The parts this instance is made of (PrimitiveParts.of): what {@link #eval} can be asked for. */
    public int[] parts() {
        return parts.clone();
    }

    /** Whether a bend or a shear acts on this shape - the two modifiers that curve space and so cannot be inverted by a simple scale. */
    public boolean hasBendOrShear() {
        for (Mod m : mods) {
            if (m.kind == 'b' && Math.abs(m.a) >= 1e-6) return true;
            if (m.kind == 's' && (Math.abs(m.a) > 1e-6 || Math.abs(m.b) > 1e-6 || Math.abs(m.c) > 1e-6)) return true;
        }
        return false;
    }

    /** Whether a modifier that changes the shape acts at all (a bend by zero, a shear by zero and a taper 1..1 do nothing). */
    public boolean hasEffectiveModifier() {
        for (Mod m : mods) {
            switch (m.kind) {
                case 'b' -> { if (Math.abs(m.a) >= 1e-6) return true; }
                case 's' -> { if (Math.abs(m.a) > 1e-6 || Math.abs(m.b) > 1e-6 || Math.abs(m.c) > 1e-6) return true; }
                case 't' -> { if (Math.abs(m.a - 1.0) > 1e-9 || Math.abs(m.b - 1.0) > 1e-9) return true; }
                default -> { }
            }
        }
        return false;
    }

    /** The point and normal of (part, u, v) after the instance's modifiers - what the GPU emits for that tessellation coordinate (local space). */
    public void eval(int part, double u, double v, double[] pos, double[] nrm) {
        evalCanonical(part, u, v, pos, nrm);
        applyModifiers(pos, nrm);
    }

    // ------------------------------------------------------------------
    // Canonical primitives (pbd.tese evalPlane ... evalCubeFaceSmooth)
    // ------------------------------------------------------------------

    /** The point and normal of (part, u, v) on the unmodified canonical shape. */
    public void evalCanonical(int part, double u, double v, double[] pos, double[] nrm) {
        switch (kind) {
            case PLANE -> {
                set(pos, u - 0.5, 0.0, v - 0.5);
                set(nrm, 0.0, 1.0, 0.0);
            }
            case SPHERE -> {
                double theta = u * TAU, phi = v * Math.PI;
                set(pos, 0.5 * Math.sin(phi) * Math.cos(theta), 0.5 * Math.cos(phi), 0.5 * Math.sin(phi) * Math.sin(theta));
                normalizeInto(nrm, pos[0], pos[1], pos[2]);
            }
            case CYLINDER -> {
                if (part == 0) {
                    double angle = u * TAU;
                    set(pos, 0.5 * Math.cos(angle), v - 0.5, 0.5 * Math.sin(angle));
                    normalizeInto(nrm, Math.cos(angle), 0.0, Math.sin(angle));
                } else if (part == 1) {
                    evalDisc(u, v, 0.5, 1.0, pos, nrm);
                } else {
                    evalDisc(u, v, -0.5, -1.0, pos, nrm);
                }
            }
            case CONE -> {
                if (part == 0) {
                    double angle = u * TAU;
                    double h = v - 0.5;
                    double radius = 0.5 * (0.5 - h);
                    set(pos, radius * Math.cos(angle), h, radius * Math.sin(angle));
                    normalizeInto(nrm, Math.cos(angle), 0.5, Math.sin(angle));
                } else {
                    evalDisc(u, v, -0.5, -1.0, pos, nrm);
                }
            }
            case DISC -> evalDisc(u, v, 0.0, 1.0, pos, nrm);
            case TORUS -> {
                double theta = u * TAU, phi = v * TAU;
                double ct = Math.cos(theta), st = Math.sin(theta);
                double cp = Math.cos(phi), sp = Math.sin(phi);
                double ringR = TORUS_MAJOR + TORUS_MINOR * cp;
                set(pos, ringR * ct, TORUS_MINOR * sp, ringR * st);
                set(nrm, cp * ct, sp, cp * st);
            }
            case CUBE -> {
                if (anySmooth) evalCubeFaceSmooth(u, v, part, pos, nrm);
                else evalCubeFaceFlat(u, v, part, pos, nrm);
            }
        }
    }

    private static void evalDisc(double u, double v, double y, double normalSign, double[] pos, double[] nrm) {
        double angle = u * TAU;
        double r = v * 0.5;
        set(pos, r * Math.cos(angle), y, r * Math.sin(angle));
        set(nrm, 0.0, normalSign, 0.0);
    }

    static void evalCubeFaceFlat(double u, double v, int face, double[] pos, double[] nrm) {
        double px = u - 0.5, py = v - 0.5;
        switch (face) {
            case 0 -> { set(pos, 0.5, py, -px); set(nrm, 1, 0, 0); }
            case 1 -> { set(pos, -0.5, py, px); set(nrm, -1, 0, 0); }
            case 2 -> { set(pos, px, 0.5, -py); set(nrm, 0, 1, 0); }
            case 3 -> { set(pos, px, -0.5, py); set(nrm, 0, -1, 0); }
            case 4 -> { set(pos, px, py, 0.5); set(nrm, 0, 0, 1); }
            default -> { set(pos, -px, py, -0.5); set(nrm, 0, 0, -1); }
        }
    }

    /** Rounded cube: the flat position clamped into the box shrunk by the per-axis roundness, the rest projected onto the ellipsoid with those radii (pbd.tese evalCubeFaceSmooth). */
    private void evalCubeFaceSmooth(double u, double v, int face, double[] pos, double[] nrm) {
        evalCubeFaceSmooth(u, v, face, pos, nrm, regularized);
    }

    /** As above; {@code regularize} stretches the band of a face of an unrounded axis over the region up to the rounded outline (see {@link #ofShell}). */
    private void evalCubeFaceSmooth(double u, double v, int face, double[] pos, double[] nrm, boolean regularize) {
        double[] flatPos = new double[3], flatNrm = new double[3];
        evalCubeFaceFlat(u, v, face, flatPos, flatNrm);
        double[] s = {smoothX, smoothY, smoothZ};
        double[] innerPos = new double[3], offset = new double[3];
        double lenSq = 0;
        for (int i = 0; i < 3; i++) {
            double inner = 0.5 - s[i];
            innerPos[i] = Math.max(-inner, Math.min(inner, flatPos[i]));
            offset[i] = flatPos[i] - innerPos[i];
            lenSq += offset[i] * offset[i];
        }
        if (Math.sqrt(lenSq) < 1e-6) {
            System.arraycopy(flatPos, 0, pos, 0, 3);
            System.arraycopy(flatNrm, 0, nrm, 0, 3);
            return;
        }
        double[] sSafe = new double[3];
        double[] scaled = new double[3];
        for (int i = 0; i < 3; i++) {
            sSafe[i] = Math.max(s[i], 1e-6);
            scaled[i] = offset[i] / sSafe[i];
        }
        double len = Math.sqrt(scaled[0] * scaled[0] + scaled[1] * scaled[1] + scaled[2] * scaled[2]);
        int ownAxis = face >> 1;
        if (regularize && s[ownAxis] <= 0.0) {
            // the face is perpendicular to an axis with no rounding: it stays flat, and its outer band fills the region up to the rounded outline
            double r = Math.max(Math.abs(scaled[0]), Math.max(Math.abs(scaled[1]), Math.abs(scaled[2])));
            for (int i = 0; i < 3; i++) pos[i] = innerPos[i] + (i == ownAxis ? 0.0 : scaled[i] / len * s[i] * r);
            System.arraycopy(flatNrm, 0, nrm, 0, 3);
            return;
        }
        double[] localOffset = new double[3];
        for (int i = 0; i < 3; i++) {
            localOffset[i] = scaled[i] / len * s[i];
            pos[i] = innerPos[i] + localOffset[i];
        }
        normalizeInto(nrm, localOffset[0] / (sSafe[0] * sSafe[0]), localOffset[1] / (sSafe[1] * sSafe[1]), localOffset[2] / (sSafe[2] * sSafe[2]));
    }

    // ------------------------------------------------------------------
    // Modifiers (pbd.tese applyBend / applyShear / applyTaper, in file order)
    // ------------------------------------------------------------------

    /** Where the modifier chain sends a point of space (see {@link #applyModifiers}); pos is updated in place. */
    public void map(double[] pos) {
        applyModifiers(pos, new double[]{0.0, 1.0, 0.0});
    }

    /**
     * The modifier chain on ANY point of space, not just on the surface (the volume of a solid is carried by the same bend, shear
     * and taper as its skin): pos is replaced by where the point ends up, nrm by the direction a normal there would take.
     */
    public void applyModifiers(double[] pos, double[] nrm) {
        for (Mod m : mods) {
            switch (m.kind) {
                case 'b' -> applyBend(m.a, (int) m.b, pos, nrm);
                case 's' -> applyShear(m.a, m.b, m.c, pos, nrm);
                case 't' -> applyTaper(m.a, m.b, pos, nrm);
                default -> { }
            }
        }
    }

    /** Rotates one plane of two coordinates by an angle proportional to the third (three cyclic cases, each skipped when its factor is ~0). */
    private static void applyShear(double fx, double fy, double fz, double[] pos, double[] nrm) {
        if (Math.abs(fx) > 1e-6) {
            double angle = fx * pos[1];
            double c = Math.cos(angle), s = Math.sin(angle);
            double py = pos[1], pz = pos[2];
            pos[1] = py * c - pz * s; pos[2] = py * s + pz * c;
            double ny = nrm[1], nz = nrm[2];
            nrm[1] = ny * c - nz * s; nrm[2] = ny * s + nz * c;
        }
        if (Math.abs(fy) > 1e-6) {
            double angle = fy * pos[2];
            double c = Math.cos(angle), s = Math.sin(angle);
            double pz = pos[2], px = pos[0];
            pos[2] = pz * c - px * s; pos[0] = pz * s + px * c;
            double nz = nrm[2], nx = nrm[0];
            nrm[2] = nz * c - nx * s; nrm[0] = nz * s + nx * c;
        }
        if (Math.abs(fz) > 1e-6) {
            double angle = fz * pos[0];
            double c = Math.cos(angle), s = Math.sin(angle);
            double px = pos[0], py = pos[1];
            pos[0] = px * c - py * s; pos[1] = px * s + py * c;
            double nx = nrm[0], ny = nrm[1];
            nrm[0] = nx * c - ny * s; nrm[1] = nx * s + ny * c;
        }
    }

    /**
     * A constant-radius circular arc anchored at the -length end (phi = 0 there, the full angle at the +length end), scale-aware: the
     * arc is circular in WORLD space, so the aspect ratio between the length axis and the offset axis (from the instance scale) enters.
     * axis 0 bends Y into the Y/Z plane (X untouched), 1 bends Z into Z/X, 2 bends X into X/Y.
     */
    private void applyBend(double totalAngle, int axis, double[] pos, double[] nrm) {
        if (Math.abs(totalAngle) < 1e-6) return;
        double lengthScale = axis == 0 ? scaleY : axis == 1 ? scaleZ : scaleX;
        double offsetScale = axis == 0 ? scaleZ : axis == 1 ? scaleX : scaleY;
        double aspect = lengthScale / Math.max(offsetScale, 1e-6);

        int li = axis == 0 ? 1 : axis == 1 ? 2 : 0;   // the length coordinate
        int oi = axis == 0 ? 2 : axis == 1 ? 0 : 1;   // the offset coordinate
        double lengthCoord = pos[li];
        double offsetCoord = pos[oi];
        double phi = totalAngle * (lengthCoord + 0.5);
        double c = Math.cos(phi), s = Math.sin(phi);

        double rOff = aspect / totalAngle;
        double effForLength = 1.0 / totalAngle - offsetCoord / aspect;
        double effForOffset = rOff - offsetCoord;

        double newLength = effForLength * s;
        double newOffset = rOff - effForOffset * c;

        double nLength = nrm[li];
        double nOffset = nrm[oi];
        double newNLength = nLength * c - nOffset * s;
        double newNOffset = nLength * s + nOffset * c;

        pos[li] = newLength; pos[oi] = newOffset;
        nrm[li] = newNLength; nrm[oi] = newNOffset;
    }

    /** Scales x and z by a factor linear in the height (bottomScale at y = -0.5, topScale at y = +0.5); a wall's normal tilts with the slant, a cap's stays. */
    private static void applyTaper(double bottomScale, double topScale, double[] pos, double[] nrm) {
        double t = pos[1] + 0.5;
        double scale = bottomScale + (topScale - bottomScale) * t;
        boolean isWallNormal = Math.abs(nrm[1]) < 0.01;
        if (isWallNormal) {
            double dr = (topScale - bottomScale) * 0.5;
            double rx = nrm[0], rz = nrm[2];
            double rl = Math.sqrt(rx * rx + rz * rz);
            if (rl > 0) { rx /= rl; rz /= rl; }
            normalizeInto(nrm, rx, -dr, rz);
        }
        pos[0] *= scale;
        pos[2] *= scale;
    }

    // ------------------------------------------------------------------
    // The way back: from a point of space to the (part, u, v) the GPU drew there
    // ------------------------------------------------------------------

    /** A place on the surface in the shader's own parametrisation: the patch (part) and its tessellation coordinate (u, v), each 0..1. */
    public record Locus(int part, double u, double v) {}

    /**
     * The inverse of {@link #applyModifiers} on a point of space: {@code pos} is replaced by the canonical point the modifier chain
     * sends to it (the modifiers undone in the reverse of the file's order: a taper divided out, a bend unrolled, a shear unwound).
     * Returns false - pos untouched - for a point that cannot be undone: a taper whose factor is 0 at that height squashes a whole
     * disc onto one circle. A bend and a shear always have a nearest preimage; for a point past the end of a bend the preimage is
     * past the end of the shape, as the arc continues.
     */
    public boolean unmap(double[] pos) {
        double[] p = pos.clone();
        for (int i = mods.size() - 1; i >= 0; i--) {
            Mod m = mods.get(i);
            switch (m.kind) {
                case 'b' -> unbend(m.a, (int) m.b, p);
                case 's' -> unshear(m.a, m.b, m.c, p);
                case 't' -> { if (!untaper(m.a, m.b, p)) return false; }
                default -> { }
            }
        }
        System.arraycopy(p, 0, pos, 0, 3);
        return true;
    }

    private static boolean untaper(double bottomScale, double topScale, double[] pos) {
        double scale = bottomScale + (topScale - bottomScale) * (pos[1] + 0.5);
        if (Math.abs(scale) < 1e-9) return false;
        pos[0] /= scale;
        pos[2] /= scale;
        return true;
    }

    /**
     * The bend in closed form. With R the distance of the point from the bend's axis of curvature along the offset direction and phi the
     * angle swept: {@code R cos(phi) = rOff - newOffset} and {@code R sin(phi) = aspect * newLength}, hence phi by atan2 and R by hypot;
     * the angle is taken on the turn nearest to the span the bend sweeps (0 .. totalAngle), so a bend of more than half a turn is
     * read on the right branch.
     */
    private void unbend(double totalAngle, int axis, double[] pos) {
        if (Math.abs(totalAngle) < 1e-6) return;
        double lengthScale = axis == 0 ? scaleY : axis == 1 ? scaleZ : scaleX;
        double offsetScale = axis == 0 ? scaleZ : axis == 1 ? scaleX : scaleY;
        double aspect = lengthScale / Math.max(offsetScale, 1e-6);
        int li = axis == 0 ? 1 : axis == 1 ? 2 : 0;
        int oi = axis == 0 ? 2 : axis == 1 ? 0 : 1;
        double rOff = aspect / totalAngle;
        double sign = Math.signum(totalAngle);
        double along = aspect * pos[li];
        double across = rOff - pos[oi];
        double h = Math.hypot(across, along);
        double midAngle = totalAngle * 0.5;
        double phi;
        double radius;
        if (h < 1e-12) {
            phi = midAngle;
            radius = 0.0;
        } else {
            phi = Math.atan2(sign * along, sign * across);
            phi += TAU * Math.round((midAngle - phi) / TAU);
            radius = sign * h;
        }
        pos[li] = phi / totalAngle - 0.5;
        pos[oi] = rOff - radius;
    }

    /**
     * The shear, one rotation at a time from the last to the first. A rotation of the pair (a, b) by an angle proportional to a itself
     * turns the polar angle alpha of the pair into alpha + f * rho * cos(alpha) (rho, the radius, is kept), which is solved for alpha
     * by Newton's method started at the angle after the rotation - the root nearest to it, which is the one a shear that does not fold
     * has.
     */
    private static void unshear(double fx, double fy, double fz, double[] pos) {
        if (Math.abs(fz) > 1e-6) unrotate(fz, pos, 0, 1);
        if (Math.abs(fy) > 1e-6) unrotate(fy, pos, 2, 0);
        if (Math.abs(fx) > 1e-6) unrotate(fx, pos, 1, 2);
    }

    private static void unrotate(double factor, double[] pos, int ia, int ib) {
        double rho = Math.hypot(pos[ia], pos[ib]);
        if (rho < 1e-15) return;
        double after = Math.atan2(pos[ib], pos[ia]);
        double alpha = after;
        for (int i = 0; i < 80; i++) {
            double g = alpha + factor * rho * Math.cos(alpha) - after;
            double slope = 1.0 - factor * rho * Math.sin(alpha);
            if (Math.abs(slope) < 1e-9) slope = slope < 0 ? -1e-9 : 1e-9;
            double step = Math.max(-0.5, Math.min(0.5, g / slope));
            alpha -= step;
            if (Math.abs(step) < 1e-15) break;
        }
        pos[ia] = rho * Math.cos(alpha);
        pos[ib] = rho * Math.sin(alpha);
    }

    /**
     * Whether {@link #locate} already answers with the nearest point of the surface (in metres) for a point near it: true for a shape the
     * modifiers do not touch whose surface is a plane, a sphere, a cylinder, a torus, a disc or a plain cube - the nearest point is the
     * projection onto the plane or along the radius. A cone's wall leans, a rounded cube's edges are ellipsoids and a modifier stretches
     * space unevenly: for those the nearest point is not where the analytic inverse falls (a corner half a voxel off a flattened rounded
     * edge lands two voxels along it), and {@link #nearest} corrects it.
     */
    public boolean locateIsNearest() {
        return !hasEffectiveModifier() && kind != Kind.CONE && !(kind == Kind.CUBE && anySmooth);
    }

    /**
     * The point of the patch of {@code start} that lies nearest the point {@code target} (canonical coordinates AFTER the modifiers, the
     * space {@link #eval} answers in), by metres - the instance scale weighs the axes: a Gauss-Newton descent on (u, v) from the
     * analytic guess, with a numerical Jacobian, kept inside the patch ({@link #EDGE}) and never accepting a step that does not bring it nearer.
     * The guess is returned as it is where it cannot be bettered (a pole, a fold, a point already nearest).
     */
    public Locus nearest(Locus start, double[] target) {
        double[] size = {Math.abs(scaleX), Math.abs(scaleY), Math.abs(scaleZ)};
        int part = start.part();
        double u = start.u(), v = start.v();
        double[] here = new double[3], nrm = new double[3];
        eval(part, u, v, here, nrm);
        double cost = metricSquared(here, target, size);
        double[] atU = new double[3], atV = new double[3];
        for (int iteration = 0; iteration < NEAREST_ITERATIONS && cost > 1e-18; iteration++) {
            // forward difference, turned backward at the far edge of the patch
            double du = u + NEAREST_STEP <= 1.0 - EDGE ? NEAREST_STEP : -NEAREST_STEP;
            double dv = v + NEAREST_STEP <= 1.0 - EDGE ? NEAREST_STEP : -NEAREST_STEP;
            eval(part, u + du, v, atU, nrm);
            eval(part, u, v + dv, atV, nrm);
            double[] jx = new double[3], jy = new double[3], r = new double[3];
            for (int a = 0; a < 3; a++) {
                jx[a] = (atU[a] - here[a]) * size[a] / du;
                jy[a] = (atV[a] - here[a]) * size[a] / dv;
                r[a] = (target[a] - here[a]) * size[a];
            }
            double a11 = dot(jx, jx), a12 = dot(jx, jy), a22 = dot(jy, jy);
            double b1 = dot(jx, r), b2 = dot(jy, r);
            double damping = 1e-9 * (a11 + a22) + 1e-18;
            a11 += damping;
            a22 += damping;
            double det = a11 * a22 - a12 * a12;
            if (!(det > 1e-30)) break;                 // a pole or a fold: the parametrisation has no second direction here
            double stepU = (b1 * a22 - b2 * a12) / det, stepV = (a11 * b2 - a12 * b1) / det;
            double reach = Math.max(Math.abs(stepU), Math.abs(stepV));
            if (reach > NEAREST_MAX_STEP) { stepU *= NEAREST_MAX_STEP / reach; stepV *= NEAREST_MAX_STEP / reach; }
            boolean moved = false;
            for (int halving = 0; halving < 5 && !moved; halving++) {
                double nu = clampInside(u + stepU), nv = clampInside(v + stepV);
                double[] trial = new double[3];
                eval(part, nu, nv, trial, nrm);
                double trialCost = metricSquared(trial, target, size);
                if (trialCost < cost) {
                    boolean negligible = cost - trialCost < 1e-14 * Math.max(1.0, cost);
                    u = nu; v = nv; cost = trialCost;
                    System.arraycopy(trial, 0, here, 0, 3);
                    moved = !negligible;
                    if (negligible) return new Locus(part, u, v);
                } else {
                    stepU *= 0.5;
                    stepV *= 0.5;
                }
            }
            if (!moved) break;
        }
        return new Locus(part, u, v);
    }

    /** The distance in metres (the instance scale weighs the axes) from {@code target} (as in {@link #nearest}) to the point of the surface at {@code at}. */
    public double distanceMetres(Locus at, double[] target) {
        double[] size = {Math.abs(scaleX), Math.abs(scaleY), Math.abs(scaleZ)};
        double[] pos = new double[3], nrm = new double[3];
        eval(at.part(), at.u(), at.v(), pos, nrm);
        return Math.sqrt(metricSquared(pos, target, size));
    }

    private static final int NEAREST_ITERATIONS = 10;
    private static final double NEAREST_STEP = 1e-5;
    private static final double NEAREST_MAX_STEP = 0.2;

    private static double metricSquared(double[] a, double[] b, double[] size) {
        double dx = (a[0] - b[0]) * size[0], dy = (a[1] - b[1]) * size[1], dz = (a[2] - b[2]) * size[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private boolean hasPart(int part) {
        for (int p : parts) {
            if (p == part) return true;
        }
        return false;
    }

    /**
     * Where on the surface of this shape a canonical point {@code q} (the unit shape, before the instance scale, modifiers already
     * undone - see {@link #unmap}) is, in the parametrisation the shader draws with: the patch and (u, v) of the surface point nearest to
     * it. The inverse of {@link #evalCanonical} for a point on the surface, and a sensible nearest-surface answer for a point off it
     * (the wall of a crater inside a solid takes the colour of the skin nearest to it).
     *
     * <p>"Nearest" is measured in metres, with the instance scale (a 4 cm door is nearest its big face, not its edge), and only among
     * the parts that are drawn (a face removed with {@code faces=}, a cap left off with {@code cap=} is never answered). The hint
     * (nx, ny, nz) is the direction a voxel face looks (0, 0, 0 for none): among the parts within {@code tieMetres} of the nearest, the
     * one that faces that way wins - the corner of a face on the +X side of a cube belongs to +X even where it touches the top.
     * Returns null for a shape with no part (nothing is drawn).
     */
    public Locus locate(double[] q, int nx, int ny, int nz, double tieMetres) {
        if (parts.length == 0) return null;
        double sx = Math.abs(scaleX), sy = Math.abs(scaleY), sz = Math.abs(scaleZ);
        switch (kind) {
            case PLANE:
                return new Locus(0, clampInside(q[0] + 0.5), clampInside(q[2] + 0.5));
            case SPHERE: {
                double r = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
                double v = r < 1e-12 ? 0.5 : Math.acos(Math.max(-1.0, Math.min(1.0, q[1] / r))) / Math.PI;
                return new Locus(0, turns(q[2], q[0]), clampInside(v));
            }
            case DISC:
                return new Locus(0, turns(q[2], q[0]), clampInside(2.0 * Math.hypot(q[0], q[2])));
            case TORUS: {
                double ring = Math.hypot(q[0], q[2]) - TORUS_MAJOR;
                return new Locus(0, turns(q[2], q[0]), turns(q[1], ring));
            }
            case CYLINDER: {
                double angle = Math.atan2(q[2], q[0]);
                double r = Math.hypot(q[0], q[2]);
                double radial = Math.hypot(sx * Math.cos(angle), sz * Math.sin(angle));
                double[] distance = {(0.5 - r) * radial, (0.5 - q[1]) * sy, (0.5 + q[1]) * sy};
                boolean[] wanted = {nx != 0 || nz != 0, ny > 0, ny < 0};
                int part = nearestPart(distance, new int[]{0, 1, 2}, wanted, tieMetres);
                double u = wrap01(angle / TAU);
                return part == 0 ? new Locus(0, u, clampInside(q[1] + 0.5)) : new Locus(part, u, clampInside(2.0 * r));
            }
            case CONE: {
                double angle = Math.atan2(q[2], q[0]);
                double r = Math.hypot(q[0], q[2]);
                double radial = Math.hypot(sx * Math.cos(angle), sz * Math.sin(angle));
                double slope = 0.5 * radial / Math.max(sy, 1e-9);                       // how far the wall leans, in metres per metre
                double wall = (0.5 * (0.5 - q[1]) - r) * radial / Math.sqrt(1.0 + slope * slope);
                double[] distance = {wall, (q[1] + 0.5) * sy};
                boolean[] wanted = {nx != 0 || nz != 0 || ny > 0, ny < 0};
                int part = nearestPart(distance, new int[]{0, 1}, wanted, tieMetres);
                double u = wrap01(angle / TAU);
                return part == 0 ? new Locus(0, u, clampInside(q[1] + 0.5)) : new Locus(1, u, clampInside(2.0 * r));
            }
            default:
                return locateOnCube(q, nx, ny, nz, tieMetres);
        }
    }

    /** The index (a part number) of the nearest drawn part, the one that faces the hint among those within the tie. */
    private int nearestPart(double[] distance, int[] partNumbers, boolean[] wanted, double tieMetres) {
        int best = -1;
        for (int i = 0; i < partNumbers.length; i++) {
            if (!hasPart(partNumbers[i])) continue;
            if (best < 0 || distance[i] < distance[best]) best = i;
        }
        if (best < 0) return partNumbers[0];
        int chosen = best;
        for (int i = 0; i < partNumbers.length; i++) {
            if (!hasPart(partNumbers[i]) || !wanted[i]) continue;
            if (distance[i] <= distance[best] + tieMetres) {
                if (!wanted[chosen] || distance[i] < distance[chosen]) chosen = i;
            }
        }
        return partNumbers[chosen];
    }

    /**
     * The face of a cube a point belongs to, and its (u, v) there. For a plain cube that is the face whose plane is nearest (metres,
     * with the instance scale); for a rounded cube see {@link #locateOnRoundedCube}. Among the faces within {@code tieMetres} of the
     * nearest, the one that looks the way the voxel face does wins (the nearest of those, if several).
     */
    private Locus locateOnCube(double[] q, int nx, int ny, int nz, double tieMetres) {
        if (anySmooth) return locateOnRoundedCube(q, nx, ny, nz, tieMetres);
        double[] size = {Math.abs(scaleX), Math.abs(scaleY), Math.abs(scaleZ)};
        double[] distance = new double[6];
        int face = -1;
        for (int f = 0; f < 6; f++) {
            // metres from the point to the plane of this face, positive inside the cube
            distance[f] = hasPart(f) ? (0.5 - ((f & 1) == 0 ? 1.0 : -1.0) * q[f >> 1]) * size[f >> 1] : Double.NaN;
            if (hasPart(f) && (face < 0 || distance[f] < distance[face])) face = f;
        }
        if (face < 0) return null;
        face = lookingThatWay(distance, face, new int[]{nx, ny, nz}, tieMetres);
        return faceLocus(face, q);
    }

    /**
     * A rounded cube shares its surface between the faces differently from a plain one. The shader sends the flat point of a face to the
     * ellipsoid around the inner box, in the direction of its offset in units of the radii: a face owns the part of each rounded edge
     * where ITS axis has the largest offset in radii - the boundary between two faces is the diagonal in radii, not the line
     * equidistant in metres (the two differ as soon as the radii in metres do, which is every anisotropic rounding) - and a face of an
     * axis with no rounding is flat up to the outline. So each drawn face is asked for the flat point it would have to have to give this
     * point, that point is run through the shader's own function ({@link #evalCubeFaceSmooth}, band regularised), and the face whose
     * result lies nearest the point is the face it belongs to: exact (distance 0) on the surface, the nearest patch off it, and the
     * look of the voxel face breaks a tie.
     */
    private Locus locateOnRoundedCube(double[] q, int nx, int ny, int nz, double tieMetres) {
        double[] size = {Math.abs(scaleX), Math.abs(scaleY), Math.abs(scaleZ)};
        double[] distance = new double[6];
        int face = -1;
        double[] pos = new double[3], nrm = new double[3];
        for (int f = 0; f < 6; f++) {
            if (!hasPart(f)) {
                distance[f] = Double.NaN;
                continue;
            }
            Locus candidate = faceLocus(f, flatPositionOfRounded(q, f));
            evalCubeFaceSmooth(candidate.u(), candidate.v(), f, pos, nrm, true);
            double dx = (pos[0] - q[0]) * size[0], dy = (pos[1] - q[1]) * size[1], dz = (pos[2] - q[2]) * size[2];
            distance[f] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (face < 0 || distance[f] < distance[face]) face = f;
        }
        if (face < 0) return null;
        face = lookingThatWay(distance, face, new int[]{nx, ny, nz}, tieMetres);
        return faceLocus(face, flatPositionOfRounded(q, face));
    }

    /** Among the faces within {@code tieMetres} of the nearest one ({@code nearest}), the one the voxel face looks toward (the nearest of those if several), else the nearest. */
    private static int lookingThatWay(double[] distance, int nearest, int[] hint, double tieMetres) {
        int chosen = nearest;
        for (int f = 0; f < 6; f++) {
            if (Double.isNaN(distance[f]) || hint[f >> 1] != ((f & 1) == 0 ? 1 : -1)) continue;
            if (distance[f] > distance[nearest] + tieMetres) continue;
            boolean chosenLooksThatWay = hint[chosen >> 1] == ((chosen & 1) == 0 ? 1 : -1);
            if (!chosenLooksThatWay || distance[f] < distance[chosen]) chosen = f;
        }
        return chosen;
    }

    /** The tessellation coordinate on {@code face} of a point of its flat square (the point clamped into the square), by the table of {@link #evalCubeFaceFlat}. */
    private static Locus faceLocus(int face, double[] flat) {
        double fx = flat[0], fy = flat[1], fz = flat[2];
        return switch (face) {
            case 0 -> new Locus(0, clampInside(0.5 - fz), clampInside(fy + 0.5));
            case 1 -> new Locus(1, clampInside(fz + 0.5), clampInside(fy + 0.5));
            case 2 -> new Locus(2, clampInside(fx + 0.5), clampInside(0.5 - fz));
            case 3 -> new Locus(3, clampInside(fx + 0.5), clampInside(fz + 0.5));
            case 4 -> new Locus(4, clampInside(fx + 0.5), clampInside(fy + 0.5));
            default -> new Locus(5, clampInside(0.5 - fx), clampInside(fy + 0.5));
        };
    }

    /**
     * The point of the flat square of {@code face} that the shader's evalCubeFaceSmooth sends to (or toward) the point q of the rounded
     * surface. The shader clamps the flat point into the box shrunk by the roundness, and puts the rest on the ellipsoid in the same
     * direction: with o = q - clamp(q) the offset on the rounded surface, the flat offset has the same direction in units of the
     * radii and is as long as it takes to reach the face plane - so it is {@code o * s(own) / |o(own)|}. A point on the flat part of the
     * face (o = 0), or one with no way to reach the plane (a point turned away from it), is its own flat point.
     *
     * <p>A face of an axis rounded by exactly 0 is flat: the shader folds its outer band onto the rim (no area to find a point in), and
     * the surface the GPU's long triangles actually cover is the band stretched over the region up to the rounded outline - the
     * regular mapping {@link #ofShell} samples (radially: the flat offset is {@code o * |o/s|_2 / |o/s|_inf}, the same direction in
     * radii, as far out as the largest of its components). That is what this inverts for such a face.
     */
    private double[] flatPositionOfRounded(double[] q, int face) {
        double[] s = {smoothX, smoothY, smoothZ};
        double[] inner = new double[3];
        double[] offset = new double[3];
        for (int i = 0; i < 3; i++) {
            double limit = 0.5 - s[i];
            inner[i] = Math.max(-limit, Math.min(limit, q[i]));
            offset[i] = q[i] - inner[i];
        }
        int own = face >> 1;
        double side = (face & 1) == 0 ? 1.0 : -1.0;
        if (s[own] <= 1e-9) {
            double sumSquares = 0.0, largest = 0.0;
            for (int i = 0; i < 3; i++) {
                if (i == own || s[i] <= 1e-9) continue;
                double w = offset[i] / s[i];
                sumSquares += w * w;
                largest = Math.max(largest, Math.abs(w));
            }
            if (largest <= 1e-12) return new double[]{q[0], q[1], q[2]};
            double stretch = Math.sqrt(sumSquares) / largest;
            double[] flat = new double[3];
            for (int i = 0; i < 3; i++) flat[i] = i == own ? side * 0.5 : inner[i] + offset[i] * stretch;
            return flat;
        }
        double along = offset[own] * side;
        if (along <= 1e-12) return new double[]{q[0], q[1], q[2]};
        double length = s[own] / along;
        double[] flat = new double[3];
        for (int i = 0; i < 3; i++) flat[i] = i == own ? side * 0.5 : inner[i] + offset[i] * length;
        return flat;
    }

    /**
     * How far inside the edge of its patch a coordinate is kept ({@link #clampInside}). A point past the end of a patch (the corner of a
     * voxel that stands out of the surface by a fraction of a voxel) is no further than the edge - and on the edge a repeating texture is
     * ambiguous: u = 1 is the last texel of the tile for a pixel that is inside the patch and the first of the next one for the wrap.
     * A billionth of the patch is far less than any texel and far more than the rounding of a double.
     */
    static final double EDGE = 1e-9;

    /** A coordinate of a patch, kept inside it: 0..1 less {@link #EDGE} at both ends. */
    private static double clampInside(double x) {
        return Math.max(EDGE, Math.min(1.0 - EDGE, x));
    }

    /** A fraction of a turn, 0 .. 1: the polar angle of the point (a, b) as the shader's {@code uv.x * TAU}. */
    private static double turns(double b, double a) {
        return wrap01(Math.atan2(b, a) / TAU);
    }

    private static double wrap01(double x) {
        double f = x - Math.floor(x);
        return f >= 1.0 ? 0.0 : f;
    }

    // ------------------------------------------------------------------

    private static void set(double[] v, double x, double y, double z) {
        v[0] = x; v[1] = y; v[2] = z;
    }

    private static void normalizeInto(double[] out, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len > 0) { out[0] = x / len; out[1] = y / len; out[2] = z / len; }
        else { out[0] = x; out[1] = y; out[2] = z; }
    }
}
