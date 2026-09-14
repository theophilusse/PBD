package pbd.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * PBDASSET (.pbdasset): a single self-contained file bundling everything
 * one asset needs - the .pbd scene, every .pbdmat/texture it references,
 * a manifest with metadata (author/date/version/description - on top of
 * whatever the .pbd's own scene-level name/kind/author/origin fields
 * already carry), and an optional rendered preview icon (PNG) - the
 * "one file to hand someone" format, versus a .pbd that assumes its
 * materials/textures live alongside it on disk.
 *
 * A plain zip archive (java.util.zip, no new dependency), not a novel
 * binary layout - the same reasoning as PbdBinFormat's own choice:
 * reuse a well-understood, well-tested container rather than invent one
 * under time pressure. Structure inside the zip:
 * <pre>
 * manifest.txt      - key=value metadata (see below)
 * scene.pbd         - the scene itself
 * icon.png          - optional preview render
 * materials/...     - every .pbdmat and texture file referenced
 * </pre>
 */
public final class PbdAssetFormat {

    public static final class Manifest {
        public String author;
        public String date;        // ISO 8601, e.g. "2026-09-12" - a plain string, not parsed/validated here
        public String version;
        public String description;

        public String toText() {
            StringBuilder sb = new StringBuilder();
            if (author != null) sb.append("author=").append(author).append('\n');
            if (date != null) sb.append("date=").append(date).append('\n');
            if (version != null) sb.append("version=").append(version).append('\n');
            if (description != null) sb.append("description=").append(description).append('\n');
            return sb.toString();
        }

        public static Manifest fromText(String text) {
            Manifest m = new Manifest();
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "author" -> m.author = value;
                    case "date" -> m.date = value;
                    case "version" -> m.version = value;
                    case "description" -> m.description = value;
                    default -> { /* unknown manifest field - ignored, so an older reader survives a newer writer */ }
                }
            }
            return m;
        }
    }

    /**
     * Packs a .pbd file plus a set of extra files (materials, textures -
     * caller-supplied, since which ones a scene actually references is
     * already the exporter's own job to know) into one .pbdasset.
     * extraFiles maps the archive-relative path (e.g.
     * "materials/wood.pbdmat") to its source location on disk.
     */
    public static void pack(Path pbdFile, Manifest manifest, Map<String, Path> extraFiles,
                             Path iconPng, Path outputAsset) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputAsset))) {
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(manifest.toText().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("scene.pbd"));
            zip.write(Files.readAllBytes(pbdFile));
            zip.closeEntry();

            if (iconPng != null && Files.exists(iconPng)) {
                zip.putNextEntry(new ZipEntry("icon.png"));
                zip.write(Files.readAllBytes(iconPng));
                zip.closeEntry();
            }

            for (var entry : extraFiles.entrySet()) {
                if (!Files.exists(entry.getValue())) {
                    System.err.println("[PbdAssetFormat] Skipping missing extra file: " + entry.getValue());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(Files.readAllBytes(entry.getValue()));
                zip.closeEntry();
            }
        }
    }

    public static final class UnpackResult {
        public Manifest manifest;
        public Path scenePbd;
        public Path iconPng; // null if the asset had none
        public final Map<String, Path> extraFiles = new LinkedHashMap<>();
    }

    /** Extracts every entry into outputDir, preserving the archive's own
     * relative paths (so "materials/wood.pbdmat" lands at
     * outputDir/materials/wood.pbdmat, ready for scene.pbd's own
     * include_material to find it there unchanged). */
    public static UnpackResult unpack(Path assetFile, Path outputDir) throws IOException {
        UnpackResult result = new UnpackResult();
        Files.createDirectories(outputDir);

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(assetFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = outputDir.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.write(target, zip.readAllBytes());

                switch (entry.getName()) {
                    case "manifest.txt" -> result.manifest = Manifest.fromText(Files.readString(target, StandardCharsets.UTF_8));
                    case "scene.pbd" -> result.scenePbd = target;
                    case "icon.png" -> result.iconPng = target;
                    default -> result.extraFiles.put(entry.getName(), target);
                }
            }
        }
        if (result.manifest == null) result.manifest = new Manifest();
        return result;
    }
}
