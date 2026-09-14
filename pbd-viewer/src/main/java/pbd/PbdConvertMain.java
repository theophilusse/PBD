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
 */
public final class PbdConvertMain {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage:");
            System.err.println("  PbdConvertMain bin <input.pbd> <output.pbdbin>");
            System.err.println("  PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]");
            System.exit(1);
        }

        String mode = args[0];
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);

        if (!Files.exists(input)) {
            System.err.println("Input file not found: " + input.toAbsolutePath());
            System.exit(1);
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
                    if (rawScene.includeMaterialPath != null) {
                        Path matSource = input.getParent().resolve(rawScene.includeMaterialPath).normalize();
                        String matArchiveKey = rawScene.includeMaterialPath.replace('\\', '/');
                        extraFiles.put(matArchiveKey, matSource);
                        String matArchiveDir = matArchiveKey.contains("/")
                            ? matArchiveKey.substring(0, matArchiveKey.lastIndexOf('/') + 1) : "";
                        for (var fields : rawScene.materialOverrides.values()) {
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
                    System.err.println("Unknown mode '" + mode + "' - expected 'bin' or 'asset'");
                    System.exit(1);
                }
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
