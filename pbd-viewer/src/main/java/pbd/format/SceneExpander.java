package pbd.format;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The scene description format: a {@code .pbdscene} file says WHICH models stand WHERE (a row of lockers, a ring of pillars, a field of
 * trees, every model of a folder) and WHAT HAPPENS while the scene plays (a camera path, keys pressed, doors opened - see
 * {@link ShowScript}), in a few lines instead of a few thousand.
 *
 * <p>It is a shorthand for what a {@code .pbd} can already say: every placement becomes a {@code pbd_ref} block, so a model placed ten
 * times is ten groups, each with the ids of the model's own instances prefixed by its name ({@code shelf_3.door}), exactly like
 * {@code pbd_ref} has always done. {@link #expand} returns that text; the engine parses it like any other, with the instance limit the
 * file asks for ({@code scene limit=...}), and nothing else in the engine knows the format exists.
 *
 * <pre>{@code
 * scene    name="Showroom" limit=300000            # optional: a name, and how many instances the scene may hold
 * materials file=materials/showroom.pbdmat          # include_material
 * ground   floor size=60 mat=concrete               # a slab to stand on (not destructible unless told)
 * place    desk  model=models/desk.pbd pos=(0,0,0) rot=(0,90,0) scale=1
 * row      locker model=models/locker.pbd count=10 step=(1.2,0,0) pos=(-6,0,5)       # locker_1 ... locker_10
 * grid     crate  model=props/crate.pbd count=(20,1,20) step=(1.1,0,1.1)             # x first, then y, then z
 * ring     pillar model=props/pillar.pbd count=16 radius=12 face=center
 * scatter  tree   model=nature/tree.pbd count=500 area=(-50,-50,50,50) seed=42 yaw=random scaleRange=(0.8,1.3)
 * folder   all    dir=models recursive=true layout=grid spacing=4                    # every model file of a folder
 * show     overlay=off
 * at 0     camera pos=(0,1.7,10) look=(0,1.2,0)
 * }</pre>
 *
 * <p>Everything is checked as it is read and a mistake says which line it is on. Everything is bounded: the length of a file, of a line,
 * of the text it expands to, the files a {@code folder} may list, the models a scene may place ({@link #HARD_MAX_INSTANCES}). Nothing a
 * value says can end up as anything but a value in the PBD text (see {@link SceneLine}), and the models are named, never read, here:
 * it is the {@code pbd_ref} parser, with all its own limits, that opens them.
 */
public final class SceneExpander {

    private SceneExpander() {}

    public static final String EXTENSION = ".pbdscene";

    /** The most instances a scene may ask for with {@code limit=}: past this the machine, not the format, is the limit. */
    public static final int HARD_MAX_INSTANCES = 5_000_000;
    /** The longest scene description file read. */
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_LINES = 100_000;
    /** The most placements one directive may write. */
    public static final int MAX_PLACEMENTS_PER_DIRECTIVE = 1_000_000;
    /** The most model files one {@code folder} line may find, and how deep below the folder it looks. */
    public static final int MAX_FOLDER_FILES = 10_000;
    public static final int MAX_FOLDER_DEPTH = 6;

    /** What a scene description file expands to. */
    public record Expansion(String pbdText, int maxInstances, int placements, ShowScript show) {}

    public static Expansion expandFile(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) throw new IOException("'" + file + "' is larger than " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB - refused");
        return expand(Files.readString(file), file.toAbsolutePath().normalize().getParent());
    }

    /**
     * Expands the text of a scene description file. {@code baseDir} is the folder the file lives in: the only thing it is used for is
     * to find the folder a {@code folder} line lists (the models themselves are opened later, by the parser, against the same folder);
     * it may be null for a text that is not a file, which then cannot use {@code folder}.
     *
     * @throws IllegalStateException with "scene line N: ..." for a mistake in the file
     */
    public static Expansion expand(String text, Path baseDir) {
        return new Run(baseDir).run(text);
    }

    // ------------------------------------------------------------------

    private static final Set<String> MODEL_EXTENSIONS = Set.of(".pbd", ".pbdbin", ".pbdasset");
    private static final Pattern GLOB = Pattern.compile("[A-Za-z0-9._*?-]{1,64}");

    /** One run over one file: the state of the expansion. */
    private static final class Run {
        private final Path baseDir;
        private final StringBuilder body = new StringBuilder();
        private final StringBuilder includes = new StringBuilder();
        private final Map<String, Integer> directiveIds = new HashMap<>();
        private final ShowParser show = new ShowParser();
        private String sceneName;
        private int maxInstances = PbdLimits.MAX_INSTANCES;
        private boolean sceneSeen;
        private long placements;

        Run(Path baseDir) {
            this.baseDir = baseDir;
        }

        Expansion run(String text) {
            if (text.startsWith("﻿")) text = text.substring(1);
            if (text.length() > MAX_FILE_BYTES) throw new IllegalStateException("scene: the text is longer than " + MAX_FILE_BYTES + " characters - refused");
            String[] lines = text.split("\r\n|\r|\n", -1);
            int lineCount = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length; // the end of the last line is not another line
            if (lineCount > MAX_LINES) throw new IllegalStateException("scene: more than " + MAX_LINES + " lines - refused");
            maxInstances = limitAsked(lines);
            for (int i = 0; i < lines.length; i++) {
                SceneLine line = SceneLine.parse(i + 1, lines[i]);
                if (line != null) directive(line);
            }
            StringBuilder out = new StringBuilder(body.length() + includes.length() + 256);
            out.append("# Generated from a scene description file (.pbdscene) - edit that file, not this one.\n");
            out.append("pbd_version 1\n");
            if (sceneName != null) out.append("name = \"").append(sceneName).append("\"\n");
            out.append(includes).append(body);
            return new Expansion(out.toString(), maxInstances, (int) placements, show.hasContent() ? show.build() : null);
        }

        /**
         * The instance limit the file asks for ({@code scene limit=}) - found BEFORE anything is expanded, wherever the line is, so that
         * a {@code row} of a million models is refused at its own line instead of after the text of all of them has been written. A line
         * that cannot be read is skipped here: it is reported, in its place and in its words, when the run reaches it.
         */
        private static int limitAsked(String[] lines) {
            for (int i = 0; i < lines.length; i++) {
                SceneLine line;
                try {
                    line = SceneLine.parse(i + 1, lines[i]);
                } catch (IllegalStateException unreadable) {
                    continue;
                }
                if (line == null || !line.directive.equals("scene") || !line.has("limit")) continue;
                try {
                    return line.requiredInteger("limit", 1, HARD_MAX_INSTANCES);
                } catch (IllegalStateException unreadable) {
                    // reported by scene() when the run gets there
                }
            }
            return PbdLimits.MAX_INSTANCES;
        }

        private void directive(SceneLine l) {
            switch (l.directive) {
                case "scene" -> scene(l);
                case "materials" -> materials(l);
                case "ground" -> ground(l);
                case "place" -> place(l);
                case "row" -> grid(l, true);
                case "grid" -> grid(l, false);
                case "ring" -> ring(l);
                case "scatter" -> scatter(l);
                case "folder" -> folder(l);
                case "show" -> show.header(l);
                case "at" -> show.at(l);
                default -> throw l.error("unknown directive '" + SceneLine.shorten(l.directive)
                    + "' - the directives are scene, materials, ground, place, row, grid, ring, scatter, folder, show and at");
            }
        }

        // ------------------------------------------------------------------ header

        private void scene(SceneLine l) {
            if (sceneSeen) throw l.error("'scene' is written twice");
            sceneSeen = true;
            l.expectPositional();
            l.allowOnly("name", "limit");
            String name = l.raw("name");
            if (name != null) {
                if (name.length() > 100) throw l.error("name is longer than 100 characters");
                for (int i = 0; i < name.length(); i++) {
                    if (name.charAt(i) < ' ' || name.charAt(i) == 0x7f) throw l.error("name holds a control character");
                }
                sceneName = name;
            }
            if (l.has("limit")) l.requiredInteger("limit", 1, HARD_MAX_INSTANCES); // read by limitAsked; checked here, in its place
        }

        private void materials(SceneLine l) {
            l.expectPositional();
            l.allowOnly("file");
            includes.append("include_material \"").append(l.requiredPath("file")).append("\"\n");
        }

        // ------------------------------------------------------------------ things that stand

        /** An id used by a directive may be used once. */
        private String claim(SceneLine l, String id) {
            Integer earlier = directiveIds.putIfAbsent(id, l.number);
            if (earlier != null) throw l.error("the id '" + id + "' is already used on line " + earlier);
            return id;
        }

        /**
         * Counts placements before they are written: every placement is at least one instance, so a line that takes the scene past the
         * instance limit stops here, with its own line number, not when the memory is gone.
         */
        private void countPlacements(SceneLine l, long more) {
            placements += more;
            if (placements > maxInstances) {
                throw l.error("this line brings the scene to " + placements + " placed models, but the scene may hold " + maxInstances
                    + " instances (every placement is at least one; the limit is set with: scene limit=<number>)");
            }
        }

        private void checkSize(SceneLine l) {
            if (body.length() + includes.length() > PbdLimits.MAX_TEXT_CHARS - 65_536) {
                throw l.error("the scene expands to more than " + (PbdLimits.MAX_TEXT_CHARS / (1024 * 1024)) + " million characters of PBD text - refused (split it)");
            }
        }

        private record Common(String mat, String parent, Integer lod, String category) {}

        private static Common common(SceneLine l) {
            Integer lod = l.has("lod") ? l.integer("lod", 0, 0, 100) : null;
            return new Common(l.name("mat"), l.instanceId("parent"), lod, l.name("category"));
        }

        private static double[] scaleOf(SceneLine l) {
            double[] scale = l.vec3OrScalar("scale");
            if (scale != null && (scale[0] == 0 || scale[1] == 0 || scale[2] == 0)) throw l.error("scale must not be 0 on any axis (a negative scale mirrors the model)");
            return scale;
        }

        /** A number as PBD text: to the micrometre (sin of 180 degrees is 1e-16, not a position), as the float the parser will read it as. */
        private static String num(double v) {
            float f = (float) (Math.rint(v * 1.0e6) / 1.0e6);
            if (f == 0f) f = 0f; // -0.0 becomes 0.0
            return Float.toString(f);
        }

        private static String vec(double x, double y, double z) {
            return "(" + num(x) + "," + num(y) + "," + num(z) + ")";
        }

        /** Whether all three numbers are {@code value} AS WRITTEN (to the micrometre): a position of 1e-9 is a zero, and is left out like one. */
        private static boolean isAll(double[] v, double value) {
            String written = num(value);
            return num(v[0]).equals(written) && num(v[1]).equals(written) && num(v[2]).equals(written);
        }

        /** One pbd_ref block: the placement of one model. */
        private void ref(SceneLine l, String id, String source, double[] pos, double[] rot, double[] scale, Common c) {
            body.append("pbd_ref ").append(id).append(" { source=\"").append(source).append('"');
            if (pos != null && !isAll(pos, 0)) body.append(" pos=").append(vec(pos[0], pos[1], pos[2]));
            if (rot != null && !isAll(rot, 0)) body.append(" rot=").append(vec(rot[0], rot[1], rot[2]));
            if (scale != null && !isAll(scale, 1)) body.append(" scale=").append(vec(scale[0], scale[1], scale[2]));
            if (c.mat() != null) body.append(" mat=").append(c.mat());
            if (c.parent() != null) body.append(" parent=").append(c.parent());
            if (c.lod() != null) body.append(" lod=").append(c.lod());
            if (c.category() != null) body.append(" category=").append(c.category());
            body.append(" }\n");
            checkSize(l);
        }

        private void ground(SceneLine l) {
            l.expectPositional("<id>");
            l.allowOnly("size", "pos", "thickness", "mat", "destructible");
            String id = claim(l, l.nameWord(0, "the id"));
            String sizeText = l.requiredRaw("size");
            double sx, sz;
            if (sizeText.trim().startsWith("(")) {
                double[] size = l.parseTuple("size", sizeText, 2);
                sx = size[0];
                sz = size[1];
            } else {
                sx = sz = l.parseNumber("size", sizeText);
            }
            if (sx <= 0 || sz <= 0) throw l.error("size must be above 0");
            double thickness = l.numberIn("thickness", 0.2, 0.001, 1000);
            double[] pos = l.vec3("pos");
            if (pos == null) pos = new double[]{0, 0, 0};
            boolean destructible = l.flag("destructible", false);
            String mat = l.name("mat");
            countPlacements(l, 1);
            body.append("cube ").append(id).append(" { pos=").append(vec(pos[0], pos[1] - thickness / 2, pos[2]))
                .append(" scale=").append(vec(sx, thickness, sz));
            if (mat != null) body.append(" mat=").append(mat);
            if (!destructible) body.append(" indestructible=true");
            body.append(" }\n");
            checkSize(l);
        }

        /** The file a placement names: a model - never another scene description file, so a scene cannot lead back to itself. */
        private static String modelPath(SceneLine l) {
            String model = l.requiredPath("model");
            if (model.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
                throw l.error("model '" + SceneLine.shorten(model) + "' is a scene description file: only models (.pbd, .pbdbin, .pbdasset) are placed - "
                    + "to build a scene out of several, list the models of all of them in one file");
            }
            return model;
        }

        private void place(SceneLine l) {
            l.expectPositional("<id>");
            l.allowOnly("model", "pos", "rot", "scale", "mat", "parent", "lod", "category");
            String id = claim(l, l.nameWord(0, "the id"));
            String model = modelPath(l);
            countPlacements(l, 1);
            ref(l, id, model, l.vec3("pos"), l.vec3("rot"), scaleOf(l), common(l));
        }

        /** {@code row}: count models along a step; {@code grid}: count (nx,ny,nz) models, x varying first, then y, then z. */
        private void grid(SceneLine l, boolean row) {
            l.expectPositional("<id>");
            l.allowOnly("model", "count", "step", "pos", "rot", "scale", "mat", "parent", "lod", "category");
            String id = claim(l, l.nameWord(0, "the id"));
            String model = modelPath(l);
            int[] counts = row ? new int[]{l.requiredInteger("count", 1, MAX_PLACEMENTS_PER_DIRECTIVE), 1, 1}
                               : l.requiredIntegers("count", 3, 1, MAX_PLACEMENTS_PER_DIRECTIVE);
            long total = (long) counts[0] * counts[1] * counts[2];
            if (total > MAX_PLACEMENTS_PER_DIRECTIVE) throw l.error("count asks for " + total + " placements; one line may write " + MAX_PLACEMENTS_PER_DIRECTIVE + " at most");
            double[] step = l.requiredVec3("step");
            double[] pos = l.vec3("pos");
            if (pos == null) pos = new double[]{0, 0, 0};
            double[] rot = l.vec3("rot");
            double[] scale = scaleOf(l);
            Common c = common(l);
            countPlacements(l, total);
            if (row) {
                // a row goes one whole step at a time, in any direction
                for (int i = 0; i < counts[0]; i++) {
                    ref(l, id + "_" + (i + 1), model, new double[]{pos[0] + i * step[0], pos[1] + i * step[1], pos[2] + i * step[2]}, rot, scale, c);
                }
                return;
            }
            int index = 0;
            for (int iz = 0; iz < counts[2]; iz++) {
                for (int iy = 0; iy < counts[1]; iy++) {
                    for (int ix = 0; ix < counts[0]; ix++) {
                        index++;
                        ref(l, id + "_" + index, model,
                            new double[]{pos[0] + ix * step[0], pos[1] + iy * step[1], pos[2] + iz * step[2]}, rot, scale, c);
                    }
                }
            }
        }

        /** The yaw, in degrees, that turns a model's +Z (its front) toward the centre of the ring, away from it, or leaves it alone. */
        private static double facingYaw(String face, double angleDeg) {
            return switch (face) {
                case "center" -> angleDeg + 180.0;
                case "out" -> angleDeg;
                default -> 0.0;
            };
        }

        /** An angle brought into (-180, 180]. */
        static double wrapDegrees(double degrees) {
            double d = degrees % 360.0;
            if (d > 180.0) d -= 360.0;
            if (d <= -180.0) d += 360.0;
            return d;
        }

        private void ring(SceneLine l) {
            l.expectPositional("<id>");
            l.allowOnly("model", "count", "radius", "center", "start", "face", "turn", "scale", "mat", "parent", "lod", "category");
            String id = claim(l, l.nameWord(0, "the id"));
            String model = modelPath(l);
            int n = l.requiredInteger("count", 1, MAX_PLACEMENTS_PER_DIRECTIVE);
            double radius = l.requiredNumber("radius");
            if (radius <= 0) throw l.error("radius must be above 0, got " + radius);
            double[] center = l.vec3("center");
            if (center == null) center = new double[]{0, 0, 0};
            double start = l.number("start", 0.0);
            String face = l.choice("face", "center", "center", "out", "none");
            double turn = l.number("turn", 0.0);
            double[] scale = scaleOf(l);
            Common c = common(l);
            countPlacements(l, n);
            for (int i = 0; i < n; i++) {
                double angle = start + 360.0 * i / n;
                double rad = Math.toRadians(angle);
                double yaw = wrapDegrees(facingYaw(face, angle) + turn);
                ref(l, id + "_" + (i + 1), model,
                    new double[]{center[0] + radius * Math.sin(rad), center[1], center[2] + radius * Math.cos(rad)},
                    new double[]{0, yaw, 0}, scale, c);
            }
        }

        private void scatter(SceneLine l) {
            l.expectPositional("<id>");
            l.allowOnly("model", "count", "area", "y", "seed", "yaw", "scale", "scaleRange", "mat", "parent", "lod", "category");
            String id = claim(l, l.nameWord(0, "the id"));
            String model = modelPath(l);
            int n = l.requiredInteger("count", 1, MAX_PLACEMENTS_PER_DIRECTIVE);
            double[] area = l.parseTuple("area", l.requiredRaw("area"), 4);
            if (!(area[0] < area[2]) || !(area[1] < area[3])) throw l.error("area is (x0, z0, x1, z1) with x0 < x1 and z0 < z1");
            double y = l.number("y", 0.0);
            long seed = l.requiredInteger("seed", 0, 999_999_999);
            String yawText = l.raw("yaw");
            boolean randomYaw = "random".equals(yawText);
            double fixedYaw = yawText == null || randomYaw ? 0.0 : l.parseNumber("yaw", yawText);
            if (l.has("scale") && l.has("scaleRange")) throw l.error("scale and scaleRange cannot both be given");
            double[] scale = scaleOf(l);
            double[] range = l.has("scaleRange") ? l.parseTuple("scaleRange", l.requiredRaw("scaleRange"), 2) : null;
            if (range != null && !(range[0] > 0 && range[1] >= range[0])) throw l.error("scaleRange is (min, max) with 0 < min <= max");
            Common c = common(l);
            countPlacements(l, n);
            SplitMix64 rng = new SplitMix64(seed);
            for (int i = 0; i < n; i++) {
                // four draws for every placement, used or not: tweaking yaw or scale does not move a single tree
                double u1 = rng.nextDouble(), u2 = rng.nextDouble(), u3 = rng.nextDouble(), u4 = rng.nextDouble();
                double[] pos = {area[0] + u1 * (area[2] - area[0]), y, area[1] + u2 * (area[3] - area[1])};
                double yaw = wrapDegrees(randomYaw ? u3 * 360.0 : fixedYaw);
                double[] placementScale = scale;
                if (range != null) {
                    double s = range[0] + u4 * (range[1] - range[0]);
                    placementScale = new double[]{s, s, s};
                }
                ref(l, id + "_" + (i + 1), model, pos, new double[]{0, yaw, 0}, placementScale, c);
            }
        }

        // ------------------------------------------------------------------ every model of a folder

        private record Found(String source, String stem) {}

        private void folder(SceneLine l) {
            l.expectPositional("<id>");
            l.allowOnly("dir", "match", "recursive", "layout", "spacing", "columns", "radius", "pos", "face", "scale", "mat", "parent", "lod", "category");
            String id = claim(l, l.nameWord(0, "the id"));
            String dirText = l.requiredPath("dir");
            if (baseDir == null) throw l.error("'folder' needs the scene to be a file on disk: the folder is found next to it");
            boolean recursive = l.flag("recursive", false);
            String glob = l.raw("match");
            if (glob != null && !GLOB.matcher(glob).matches()) throw l.error("match '" + SceneLine.shorten(glob) + "' is a file name pattern of letters, digits, '.', '_', '-', '*' and '?'");
            String layout = l.choice("layout", "grid", "row", "grid", "ring");
            double spacing = l.numberIn("spacing", 4.0, 0.01, 10_000);
            int columns = l.integer("columns", 0, 0, 100_000);
            double[] pos = l.vec3("pos");
            if (pos == null) pos = new double[]{0, 0, 0};
            String face = l.choice("face", "center", "center", "out", "none");
            double[] scale = scaleOf(l);
            Common c = common(l);

            List<Found> models = listModels(l, dirText, recursive, glob);
            int n = models.size();
            double radius = l.has("radius") ? l.requiredNumber("radius") : (n == 1 ? 0.0 : Math.max(spacing * n / (2 * Math.PI), spacing));
            if (l.has("radius") && radius <= 0) throw l.error("radius must be above 0, got " + radius);
            if (!layout.equals("ring") && (l.has("radius") || l.has("face"))) throw l.error("radius and face are for layout=ring");
            if (!layout.equals("grid") && columns != 0) throw l.error("columns is for layout=grid");
            int perRow = columns > 0 ? columns : (int) Math.ceil(Math.sqrt(n));
            countPlacements(l, n);

            Set<String> taken = new HashSet<>();
            for (int i = 0; i < n; i++) {
                double[] position;
                double[] rot = null;
                switch (layout) {
                    case "row" -> position = new double[]{pos[0] + i * spacing, pos[1], pos[2]};
                    case "grid" -> position = new double[]{pos[0] + (i % perRow) * spacing, pos[1], pos[2] + (i / perRow) * spacing};
                    default -> {
                        double angle = 360.0 * i / n;
                        double rad = Math.toRadians(angle);
                        position = new double[]{pos[0] + radius * Math.sin(rad), pos[1], pos[2] + radius * Math.cos(rad)};
                        if (radius > 0) rot = new double[]{0, wrapDegrees(facingYaw(face, angle)), 0}; // a ring of radius 0 has no centre to face
                    }
                }
                String placementId = uniqueId(id, models.get(i).stem(), taken);
                ref(l, placementId, models.get(i).source(), position, rot, scale, c);
            }
        }

        /** {@code <id>_<file name without extension>}, with every other character made '_', and a number if two files would share it. */
        private static String uniqueId(String id, String stem, Set<String> taken) {
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < stem.length(); i++) {
                char ch = stem.charAt(i);
                boolean plain = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
                clean.append(plain ? ch : '_');
            }
            if (clean.length() == 0) clean.append("model");
            String base = id + "_" + clean;
            if (base.length() > 56) base = base.substring(0, 56);
            String candidate = base;
            for (int n = 2; !taken.add(candidate); n++) candidate = base + "_" + n;
            return candidate;
        }

        private List<Found> listModels(SceneLine l, String dirText, boolean recursive, String glob) {
            Path root;
            try {
                root = baseDir.resolve(dirText).toAbsolutePath().normalize();
            } catch (java.nio.file.InvalidPathException e) {
                throw l.error("dir '" + SceneLine.shorten(dirText) + "' is not a valid folder name here");
            }
            if (!Files.isDirectory(root)) throw l.error("dir '" + SceneLine.shorten(dirText) + "' is not a folder (looked for " + root + ")");
            Pattern name = glob == null ? null : Pattern.compile(globToRegex(glob), Pattern.CASE_INSENSITIVE);
            List<Path> files = new ArrayList<>();
            boolean[] tooMany = {false};
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), recursive ? MAX_FOLDER_DEPTH : 1, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName().toString();
                        String lower = fileName.toLowerCase(Locale.ROOT);
                        boolean model = MODEL_EXTENSIONS.stream().anyMatch(lower::endsWith);
                        boolean regular = attrs.isRegularFile() || (attrs.isSymbolicLink() && Files.isRegularFile(file));
                        if (model && regular && (name == null || name.matcher(fileName).matches())) {
                            if (files.size() >= MAX_FOLDER_FILES) {
                                tooMany[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE; // unreadable: not a model we can place
                    }
                });
            } catch (IOException e) {
                throw l.error("the folder '" + SceneLine.shorten(dirText) + "' could not be read: " + e.getMessage());
            }
            if (tooMany[0]) throw l.error("the folder '" + SceneLine.shorten(dirText) + "' holds more than " + MAX_FOLDER_FILES + " model files - refused");
            if (files.isEmpty()) {
                throw l.error("the folder '" + SceneLine.shorten(dirText) + "' holds no .pbd, .pbdbin or .pbdasset file" + (glob != null ? " matching '" + glob + "'" : "")
                    + (recursive ? "" : " (recursive=true looks in the folders below it)"));
            }
            files.sort(Comparator.comparing((Path p) -> root.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT))
                .thenComparing(p -> root.relativize(p).toString().replace('\\', '/')));
            Path base = baseDir.toAbsolutePath().normalize();
            List<Found> found = new ArrayList<>(files.size());
            for (Path file : files) {
                String source;
                try {
                    source = base.relativize(file).toString();
                } catch (IllegalArgumentException differentRoots) {
                    source = file.toString();
                }
                source = l.checkPath("a file found in dir", source.replace('\\', '/'));
                String fileName = file.getFileName().toString();
                found.add(new Found(source, fileName.substring(0, fileName.lastIndexOf('.'))));
            }
            return found;
        }

        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                char ch = glob.charAt(i);
                switch (ch) {
                    case '*' -> regex.append(".*");
                    case '?' -> regex.append('.');
                    default -> regex.append(Pattern.quote(String.valueOf(ch)));
                }
            }
            return regex.toString();
        }
    }

    // ------------------------------------------------------------------

    /**
     * A small, fixed random number generator (SplitMix64, Vigna): a scatter must come out the same on every machine and every Java
     * version, so it is written here, not borrowed from {@code java.util.Random} (whose sequence the platform may change). Public only
     * so that the regression suite can hold it to the published numbers.
     */
    public static final class SplitMix64 {
        private long state;

        public SplitMix64(long seed) {
            this.state = seed;
        }

        public long nextLong() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** In [0, 1): the top 53 bits of the next number. */
        public double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }
    }
}
