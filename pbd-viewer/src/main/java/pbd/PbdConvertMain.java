package pbd;

import pbd.format.PbdAssetFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts an already-exported .pbd file into .pbdbin or .pbdasset - the
 * command-line tool for scripts and batch work (PbdBinFormat/PbdAssetFormat).
 * The Blender add-on no longer runs it: since v0.48.0 its .pbdbin / .pbdasset
 * buttons write both formats themselves (compiled_export.py) and the engine's
 * readers are what tools/regression/addon_compiled_check.py checks the files
 * against - so no Java, no Gradle project and no particular folder are needed
 * to export from Blender. (The add-on's "Add to tileGeometry.txt" button
 * still runs PbdToTileGeometryMain.)
 *
 * Usage:
 *   PbdConvertMain bin <input.pbd> <output.pbdbin>
 *   PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]
 *   PbdConvertMain lever <input.pbd|.pbdbin|folder> <output.pbd|.pbdbin|folder>
 *   PbdConvertMain expand <scene.pbdscene> <output.pbd>
 *   PbdConvertMain check <scene.pbdscene>
 *
 * `expand` writes the plain PBD text a scene description file stands for (pbd.format.SceneExpander): one pbd_ref per placement, so
 * what a row, a grid or a folder line really produces can be read, diffed, and opened by any tool that reads a .pbd. The paths in
 * it are those of the scene file, so save it next to the scene file. `check` loads the scene file completely (every model it places,
 * every id its show names), without a window, and says what it holds, how long the show lasts and how much memory the instances take
 * - or what is wrong, with the line.
 *
 * `lever` is the keyframe-door migration (pbd.format.LeverArmMigration):
 * every keyframed door that is a plain two-pose swing becomes a lever arm
 * (same closed pose, same open pose, same duration, sounds moved to the
 * arm); everything else stays as it is and is listed with the reason. A
 * folder is walked recursively and the converted files land at the same
 * relative paths under the output folder - the original is never touched.
 */
public final class PbdConvertMain {

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("check")) {
            runCheck(Path.of(args[1]));
            return;
        }
        if (args.length < 3) {
            System.err.println("Usage:");
            System.err.println("  PbdConvertMain bin <input.pbd> <output.pbdbin>");
            System.err.println("  PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]");
            System.err.println("  PbdConvertMain lever <input.pbd|.pbdbin|folder> <output.pbd|.pbdbin|folder>   (keyframe doors -> lever arms)");
            System.err.println("  PbdConvertMain expand <scene.pbdscene> <output.pbd>   (the PBD text a scene description file stands for)");
            System.err.println("  PbdConvertMain check <scene.pbdscene>                 (load it completely, say what it holds or what is wrong)");
            System.exit(1);
        }

        String mode = args[0];
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);

        if (!Files.exists(input)) {
            System.err.println("Input file not found: " + input.toAbsolutePath());
            System.exit(1);
        }

        if (mode.equals("expand")) {
            try {
                pbd.format.SceneExpander.Expansion expansion = pbd.format.SceneExpander.expandFile(input);
                Files.writeString(output, expansion.pbdText());
                System.out.println("Wrote " + output.toAbsolutePath() + " - " + expansion.placements() + " placement(s), instance limit "
                    + expansion.maxInstances() + ". The paths in it are relative to " + input.toAbsolutePath().getParent() + ": open it from there.");
            } catch (IOException | IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        if (mode.equals("lever")) {
            try {
                runLever(input, output);
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        try {
            PbdEngine engine = new PbdEngine();
            PbdEngine.SceneHandle scene = engine.load(input);

            switch (mode) {
                case "bin" -> {
                    scene.saveBin(output);
                    System.out.println("Wrote " + output.toAbsolutePath() + " (" + Files.size(output) + " bytes, from "
                        + Files.size(input) + " byte source)");
                }
                case "asset" -> {
                    PbdAssetFormat.Manifest manifest = new PbdAssetFormat.Manifest();
                    manifest.author = args.length > 3 ? args[3] : null;
                    manifest.version = args.length > 4 ? args[4] : null;
                    manifest.description = args.length > 5 ? args[5] : null;

                    // Auto-discover from the already-parsed scene rather
                    // than leaving this to a caller: the .pbd's own
                    // include_material names the .pbdmat (scene.raw()'s
                    // includeMaterialPath, as-written and still relative
                    // to input's own directory - see PbdScene's doc on
                    // that field for why it's kept unresolved), and each
                    // material's texture/normalMap/roughnessMap/
                    // displacementMap fields are already absolute paths
                    // by the time parsing resolved them (see
                    // PbdParser.parseIncludeMaterial) - both together are
                    // exactly what "self-contained" needs, so this is
                    // pack()'s extraFiles map built for real instead of
                    // handed an empty one, which is what actually made
                    // .pbdasset lose every material despite its own doc
                    // comment claiming otherwise.
                    Map<String, Path> extraFiles = new HashMap<>();
                    pbd.format.PbdScene rawScene = scene.raw();
                    // EVERY include_material of the file, not just the last
                    // one (PbdScene.includeMaterialPaths): a scene may list
                    // several, and the Blender add-on writes one per embedded
                    // asset that brought materials - the .pbdasset used to
                    // bundle only the last, so the others fell back to grey
                    // on the machine that opened it. A URL include stays a
                    // URL (it is fetched again when the asset is opened).
                    java.util.List<String> includes = rawScene.includeMaterialPaths.isEmpty() && rawScene.includeMaterialPath != null
                        ? java.util.List.of(rawScene.includeMaterialPath) : rawScene.includeMaterialPaths;
                    for (String includePath : includes) {
                        if (includePath.startsWith("http://") || includePath.startsWith("https://")) continue;
                        Path matSource = input.getParent().resolve(includePath).normalize();
                        String matArchiveKey = includePath.replace('\\', '/');
                        extraFiles.put(matArchiveKey, matSource);
                        String matArchiveDir = matArchiveKey.contains("/")
                            ? matArchiveKey.substring(0, matArchiveKey.lastIndexOf('/') + 1) : "";
                        // Textures: the already-parsed entries hold their
                        // resolved absolute paths; which include a material
                        // came from is looked up by the names THIS .pbdmat
                        // defines, so each texture lands next to its own
                        // .pbdmat in the archive (that is where the parser
                        // looks first when the asset is opened elsewhere).
                        for (String materialName : new pbd.format.PbdMatParser().parseFile(matSource).keySet()) {
                            Map<String, String> fields = rawScene.materialOverrides.get(materialName);
                            if (fields == null) continue;
                            for (String key : new String[]{"texture", "normalMap", "roughnessMap", "displacementMap"}) {
                                String abs = fields.get(key);
                                if (abs == null) continue;
                                Path texSource = Path.of(abs);
                                extraFiles.put(matArchiveDir + texSource.getFileName(), texSource);
                            }
                        }
                    }
                    PbdAssetFormat.pack(input, manifest, extraFiles, null, output);
                    System.out.println("Wrote " + output.toAbsolutePath() + " (" + Files.size(output) + " bytes) - "
                        + (extraFiles.isEmpty()
                            ? "no include_material in the source .pbd, nothing to bundle"
                            : "bundled " + extraFiles.size() + " material/texture file(s): " + extraFiles.keySet()));
                }
                default -> {
                    System.err.println("Unknown mode '" + mode + "' - expected 'bin', 'asset', 'lever', 'expand' or 'check'");
                    System.exit(1);
                }
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * The `check` mode: loads a scene description file the way the viewer does (without a window) and reports. Exit status 0 when it
     * loads and its show can play, 1 with the reason otherwise.
     */
    private static void runCheck(Path file) {
        if (!Files.exists(file)) {
            System.err.println("Input file not found: " + file.toAbsolutePath());
            System.exit(1);
        }
        try {
            Runtime runtime = Runtime.getRuntime();
            System.gc();
            long before = runtime.totalMemory() - runtime.freeMemory();
            PbdEngine.SceneHandle handle = new PbdEngine().loadAny(file);
            System.gc();
            long used = Math.max(0, runtime.totalMemory() - runtime.freeMemory() - before);
            pbd.format.PbdScene scene = handle.raw();
            System.out.println("OK: " + file.getFileName() + " holds " + scene.instances.size() + " instance(s) (it may hold up to " + scene.maxInstances() + ")");
            if (!scene.instances.isEmpty()) {
                System.out.println("    memory: about " + (used / (1024 * 1024)) + " MB for the instances, about " + (used / scene.instances.size()) + " bytes each");
            }
            pbd.format.ShowScript show = handle.show();
            if (show == null) {
                System.out.println("    no show: the camera is yours");
            } else {
                System.out.println("    show: " + String.format(java.util.Locale.ROOT, "%.1f", show.duration()) + " s, " + show.camera.size() + " camera key(s), "
                    + show.events.size() + " event(s), " + show.hour.size() + " hour key(s), " + show.day.size() + " day key(s); overlay "
                    + (show.overlay ? "on" : "off") + ", vsync " + (show.vsync ? "on" : "off")
                    + (show.report != null ? ", report " + show.report : ", no report file"));
                boolean ends = show.events.stream().anyMatch(e -> e.kind() == pbd.format.ShowScript.Kind.QUIT);
                if (!ends) System.out.println("    note: no 'at <time> quit' - the show holds its last pose until the window is closed");
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** The `lever` mode: one file, or every .pbd/.pbdbin under a folder. */
    private static void runLever(Path input, Path output) throws IOException {
        if (Files.isDirectory(input)) {
            int converted = 0, untouched = 0, refused = 0;
            for (pbd.format.LeverArmMigration.FileResult r : pbd.format.LeverArmMigration.convertFolder(input, output)) {
                String shown = input.relativize(r.file).toString();
                if (r.refusal != null) {
                    refused++;
                    System.out.println("REFUSED   " + shown + " - " + r.refusal);
                } else if (r.report.changedAnything()) {
                    converted++;
                    System.out.println("CONVERTED " + shown + ": " + String.join(", ", r.report.converted));
                    printDetails(r.report);
                } else {
                    untouched++;
                    if (!r.report.skipped.isEmpty()) {
                        System.out.println("UNCHANGED " + shown);
                        printDetails(r.report);
                    }
                }
            }
            System.out.println(converted + " file(s) converted into " + output.toAbsolutePath() + ", " + untouched
                + " had nothing to convert (not written), " + refused + " refused.");
            return;
        }
        try {
            pbd.format.LeverArmMigration.Report report = pbd.format.LeverArmMigration.convertFile(input, output);
            if (report.changedAnything()) {
                System.out.println("Wrote " + output.toAbsolutePath() + " - converted: " + String.join(", ", report.converted));
            } else {
                System.out.println("Nothing to convert in " + input + " - no file written.");
            }
            printDetails(report);
        } catch (pbd.format.LeverArmMigration.Refused refused) {
            System.err.println("Not converted: " + refused.getMessage());
            System.exit(1);
        }
    }

    private static void printDetails(pbd.format.LeverArmMigration.Report report) {
        for (String note : report.notes) System.out.println("    note: " + note);
        for (String skipped : report.skipped) System.out.println("    left as keyframes - " + skipped);
    }
}
