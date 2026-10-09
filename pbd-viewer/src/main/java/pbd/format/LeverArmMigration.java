package pbd.format;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the keyframed doors of existing assets into lever arms - the
 * migration the project owes before the keyframe doors go away.
 *
 * <h3>What converts</h3>
 * An instance whose keyframes are a plain two-pose swing: no channel, no
 * scale, no loop, at least two keyframes in increasing time order, and the
 * first and last pose differ. (Keyframes in between are fine if they lie
 * exactly on the straight line between the two ends and carry no sound -
 * the engine interpolates linearly, so they add nothing.) The result:
 * <ul>
 *   <li>the instance's own pos/rot becomes the FIRST keyframe's pose (the
 *       closed end - what the engine showed at animation time 0, whatever
 *       the instance's written pos/rot said, since keyframes overrode it);</li>
 *   <li>leverArm.openPos / openRot = the LAST keyframe's pose (omitted when
 *       it equals the closed one, i.e. "hold");</li>
 *   <li>leverArm.speed = 1 / (last time - first time): the swing takes the
 *       same time as the clip did (a clip plays at 1 s per second, an arm
 *       moves at `speed` arm units per second);</li>
 *   <li>the keyframes are removed. linkGroup, containerTrigger, children and
 *       every other field are untouched.</li>
 * </ul>
 * For every arm value f in [0,1] the converted instance (and everything
 * hanging on it) is exactly where the old clip had it at time
 * first + f * (last - first) - tools/regression/RegressionLeverArmMigration
 * checks that on whole scenes.
 *
 * <h3>Sounds</h3>
 * A keyframe sound played when the clip reached or crossed that keyframe; an
 * arm plays openSound when it leaves the closed end and closeSound when it
 * comes back. Mapped as: a sound on the LAST keyframe becomes both (the
 * creak when it moves, either way); a sound on the FIRST keyframe only
 * becomes openSound. Same files, a slightly different moment - the report
 * says so for every sound it moved.
 *
 * <h3>What is left alone, and why (each one is reported)</h3>
 * loop = true (a fan or wheel: a decorative ping-pong, not a two-state
 * door), a channel (a growth clip driven by a named value), a scale
 * keyframe (an arm moves position and rotation only), a path with a bend or
 * a sound in the middle (an arm is a straight two-pose swing), and an
 * instance that already has a leverArm. Nothing is guessed: a clip that is
 * not a two-pose swing stays a clip.
 */
public final class LeverArmMigration {

    /** A middle keyframe this close (world units) to the straight line is "on" it. */
    static final float POS_TOLERANCE = 1e-4f;
    /** ... and this close (degrees) in rotation. */
    static final float ROT_TOLERANCE = 1e-2f;

    private LeverArmMigration() {}

    /** What a run did - for the person who runs it, and for the tests. */
    public static final class Report {
        /** Ids of the instances that now have a lever arm instead of keyframes. */
        public final List<String> converted = new ArrayList<>();
        /** "id: why it was left as keyframes". */
        public final List<String> skipped = new ArrayList<>();
        /** Things worth knowing about what was converted (a sound moved, a base pose replaced). */
        public final List<String> notes = new ArrayList<>();

        public boolean changedAnything() { return !converted.isEmpty(); }
    }

    /** Converts every eligible instance of the scene in place. */
    public static Report convert(PbdScene scene) {
        Report report = new Report();
        for (PbdInstance inst : scene.instances) {
            if (inst.keyframes.isEmpty()) continue;
            String why = refusal(inst);
            if (why != null) {
                report.skipped.add(inst.id + ": " + why);
                continue;
            }
            convertOne(inst, report);
        }
        return report;
    }

    // ------------------------------------------------------------------ one instance

    /** null when the instance's keyframes are a two-pose swing a lever arm can stand in for. */
    private static String refusal(PbdInstance inst) {
        List<PbdInstance.Keyframe> kf = inst.keyframes;
        if (inst.leverArm != null) {
            return "already has a leverArm too (keyframes win at run time) - delete one of the two by hand";
        }
        if ("true".equals(inst.params.get("loop"))) {
            return "loop = true: a decorative ping-pong clip (fan, wheel), not a two-state door";
        }
        for (PbdInstance.Keyframe k : kf) {
            if (k.channel != null) return "driven by the channel '" + k.channel + "' (a growth clip, not a door)";
            if (k.scale != null) return "animates scale; a lever arm moves position and rotation only";
        }
        if (kf.size() < 2) return "a single keyframe: nothing to swing to";
        for (int i = 1; i < kf.size(); i++) {
            if (!(kf.get(i).time > kf.get(i - 1).time)) {
                return "keyframe times are not strictly increasing (t=" + kf.get(i - 1).time + " then t=" + kf.get(i).time + ")";
            }
        }
        PbdInstance.Keyframe first = kf.get(0);
        PbdInstance.Keyframe last = kf.get(kf.size() - 1);
        if (last.time - first.time < 1e-6f) return "zero duration";
        Vector3f p0 = pos(inst, first), p1 = pos(inst, last);
        Vector3f r0 = rot(inst, first), r1 = rot(inst, last);
        if (near(p0, p1, POS_TOLERANCE) && near(r0, r1, ROT_TOLERANCE)) {
            return "the first and the last keyframe are the same pose: nothing moves";
        }
        for (int i = 1; i < kf.size() - 1; i++) {
            PbdInstance.Keyframe mid = kf.get(i);
            float f = (mid.time - first.time) / (last.time - first.time);
            if (mid.sound != null) {
                return "the keyframe at t=" + mid.time + " carries a sound; an arm cannot play one halfway";
            }
            if (!near(pos(inst, mid), lerp(p0, p1, f), POS_TOLERANCE) || !near(rot(inst, mid), lerp(r0, r1, f), ROT_TOLERANCE)) {
                return "the motion bends at t=" + mid.time + " (more than two poses): a lever arm is a straight two-pose swing - keep the keyframes";
            }
        }
        return null;
    }

    private static void convertOne(PbdInstance inst, Report report) {
        List<PbdInstance.Keyframe> kf = inst.keyframes;
        PbdInstance.Keyframe first = kf.get(0);
        PbdInstance.Keyframe last = kf.get(kf.size() - 1);
        Vector3f closedPos = new Vector3f(pos(inst, first)), closedRot = new Vector3f(rot(inst, first));
        Vector3f openPos = new Vector3f(pos(inst, last)), openRot = new Vector3f(rot(inst, last));
        float duration = last.time - first.time;

        boolean basePoseChanged = !near(inst.position, closedPos, POS_TOLERANCE) || !near(inst.rotationDeg, closedRot, ROT_TOLERANCE);

        // Sounds: last -> both directions; first alone -> opening only (see the class doc).
        String openSound = first.sound != null ? first.sound : last.sound;
        String closeSound = last.sound;

        inst.position.set(closedPos);
        inst.rotationDeg.set(closedRot);
        inst.rotation.set(quaternion(closedRot));
        inst.keyframes.clear();
        inst.leverArm = new PbdInstance.LeverArm(
            near(openPos, closedPos, POS_TOLERANCE) ? null : openPos,
            near(openRot, closedRot, ROT_TOLERANCE) ? null : openRot,
            1f / duration,
            PbdInstance.LeverArm.Release.SNAP, false, 0f, openSound, closeSound);

        report.converted.add(inst.id);
        if (basePoseChanged) {
            report.notes.add(inst.id + ": its written pos/rot was not the closed pose (the first keyframe overrode it at run time); "
                + "pos/rot now say the closed pose, which is what the clip actually showed");
        }
        if (openSound != null || closeSound != null) {
            report.notes.add(inst.id + ": keyframe sound moved to the arm (openSound=" + openSound + ", closeSound=" + closeSound
                + ") - same file, played when the arm leaves the closed end / returns to it instead of when the clip crossed the keyframe");
        }
    }

    // ------------------------------------------------------------------ files

    /** A line of text that says why a file is left untouched. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) { super(message); }
    }

    private static final Pattern PBD_REF_BLOCK = Pattern.compile("(?m)(^|[\\s{}])pbd_ref\\s+[^\\s{]+\\s*\\{");

    /**
     * Converts one file (.pbd or .pbdbin) into `out` (the same kind of file,
     * decided by out's extension). Writes nothing and returns an empty-handed
     * report when no instance qualifies. Refuses - throws {@link Refused} -
     * when writing the scene back would not be faithful: a file that places
     * other files with pbd_ref (the converted copy would contain them inlined;
     * convert the placed files themselves, where the doors are) or a
     * .pbdasset (unpack it, convert its scene.pbd, repack it).
     */
    public static Report convertFile(Path in, Path out) throws IOException {
        if (in.toAbsolutePath().normalize().equals(out.toAbsolutePath().normalize())) {
            throw new Refused("the output is the input - give another path (the original is never overwritten)");
        }
        String name = in.getFileName().toString().toLowerCase();
        if (name.endsWith(".pbdasset")) {
            throw new Refused(".pbdasset is a zip: unpack it, convert its scene.pbd, and pack it again (PbdConvertMain asset)");
        }
        PrimitiveRegistry primitives = new PrimitiveRegistry();
        ModifierRegistry modifiers = new ModifierRegistry();
        boolean binary = name.endsWith(".pbdbin");
        String text = binary ? PbdBinFormat.decompressToText(in) : Files.readString(in);
        if (PBD_REF_BLOCK.matcher(text).find()) {
            throw new Refused("it places other files with pbd_ref - convert those files instead (a converted copy of this one would have them inlined)");
        }
        PbdScene scene = binary ? PbdBinFormat.read(in, primitives, modifiers) : new PbdParser(primitives, modifiers).parseFile(in);
        Report report = convert(scene);
        if (!report.changedAnything()) return report;
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        if (out.getFileName().toString().toLowerCase().endsWith(".pbdbin")) {
            PbdBinFormat.write(scene, out);
        } else {
            Files.writeString(out, new PbdSerializer(true).serialize(scene));
        }
        return report;
    }

    /** One file of a folder run: what happened to it. */
    public static final class FileResult {
        public final Path file;
        public final Report report;     // null when refused
        public final String refusal;    // null unless refused
        FileResult(Path file, Report report, String refusal) { this.file = file; this.report = report; this.refusal = refusal; }
    }

    /**
     * Converts every .pbd / .pbdbin under `inDir` into the same relative path
     * under `outDir`; files with nothing to convert are not written.
     */
    public static List<FileResult> convertFolder(Path inDir, Path outDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(inDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".pbd") || n.endsWith(".pbdbin");
                })
                .sorted()
                .forEach(files::add);
        }
        List<FileResult> results = new ArrayList<>();
        for (Path file : files) {
            Path target = outDir.resolve(inDir.relativize(file).toString());
            try {
                results.add(new FileResult(file, convertFile(file, target), null));
            } catch (Refused r) {
                results.add(new FileResult(file, null, r.getMessage()));
            } catch (RuntimeException e) {
                results.add(new FileResult(file, null, "could not be read: " + e.getMessage()));
            }
        }
        return results;
    }

    // ------------------------------------------------------------------ small math

    private static Vector3f pos(PbdInstance inst, PbdInstance.Keyframe k) {
        return k.pos != null ? k.pos : inst.position;
    }

    private static Vector3f rot(PbdInstance inst, PbdInstance.Keyframe k) {
        return k.rotDeg != null ? k.rotDeg : inst.rotationDeg;
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float f) {
        return new Vector3f(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
    }

    private static boolean near(Vector3f a, Vector3f b, float tolerance) {
        return Math.abs(a.x - b.x) <= tolerance && Math.abs(a.y - b.y) <= tolerance && Math.abs(a.z - b.z) <= tolerance;
    }

    /** The composition PbdParser's rot= field and HierarchyResolver use: Z, then Y, then X. */
    private static Quaternionf quaternion(Vector3f deg) {
        return new Quaternionf().identity()
            .rotateZ((float) Math.toRadians(deg.z))
            .rotateY((float) Math.toRadians(deg.y))
            .rotateX((float) Math.toRadians(deg.x));
    }
}
