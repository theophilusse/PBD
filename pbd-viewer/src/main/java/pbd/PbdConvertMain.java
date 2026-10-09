package pbd;

import pbd.format.PbdAssetFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts an already-exported .pbd file into .pbdbin or .pbdasset -
 * the command-line tool the Blender add-on's own "Export as .pbdbin" /
 * "Export as .pbdasset" buttons shell out to (see ui_panel.py), since
 * those formats are implemented in Java (PbdBinFormat/PbdAssetFormat),
 * not duplicated in Python.
 *
 * Usage:
 *   PbdConvertMain bin <input.pbd> <output.pbdbin>
 *   PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]
 *   PbdConvertMain lever <input.pbd|.pbdbin|folder> <output.pbd|.pbdbin|folder>
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
        if (args.length < 3) {
            System.err.println("Usage:");
            System.err.println("  PbdConvertMain bin <input.pbd> <output.pbdbin>");
            System.err.println("  PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]");
            System.err.println("  PbdConvertMain lever <input.pbd|.pbdbin|folder> <output.pbd|.pbdbin|folder>   (keyframe doors -> lever arms)");
            System.exit(1);
        }

        String mode = args[0];
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);

        if (!Files.exists(input)) {
            System.err.println("Input file not found: " + input.toAbsolutePath());
            System.exit(1);
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
                    System.err.println("Unknown mode '" + mode + "' - expected 'bin', 'asset' or 'lever'");
                    System.exit(1);
                }
            }
        } catch (IOException e) {
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
