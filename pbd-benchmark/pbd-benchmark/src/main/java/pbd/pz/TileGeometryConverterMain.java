package pbd.pz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone entry point (separate from pbd.app.Main, the interactive
 * viewer) so `./gradlew run` keeps working exactly as before - see the
 * new convertTileGeometry Gradle task, which runs THIS class instead.
 *
 * Usage: TileGeometryConverterMain <tileGeometry.txt> <outputDir>
 */
public final class TileGeometryConverterMain {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: convertTileGeometry <tileGeometry.txt> <outputDir>");
            System.err.println("  (via Gradle: ./gradlew convertTileGeometry -Pinput=path/to/tileGeometry.txt -Poutput=path/to/dir)");
            System.exit(1);
        }

        Path inputPath = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        if (!Files.exists(inputPath)) {
            System.err.println("Input file not found: " + inputPath.toAbsolutePath());
            System.exit(1);
        }

        try {
            long inputBytes = Files.size(inputPath);
            System.out.println("Parsing " + inputPath.toAbsolutePath() + " (" + inputBytes + " bytes)...");

            TileGeometryParser parser = new TileGeometryParser();
            TileGeometryParser.TileGeometryFile file = parser.parseFile(inputPath);

            int totalTiles = file.tilesets.stream().mapToInt(t -> t.tiles.size()).sum();
            System.out.println("Parsed " + file.tilesets.size() + " tileset(s), " + totalTiles + " tile(s) total.");

            TileGeometryConverter converter = new TileGeometryConverter();
            TileGeometryConverter.Stats stats = converter.convertToDirectory(file, outputDir);

            System.out.println();
            System.out.println("=== Conversion summary ===");
            System.out.println("Tiles converted:        " + stats.tilesConverted);
            System.out.println("Tiles skipped (empty):  " + stats.tilesSkippedEmpty);
            System.out.println("Tiles skipped (nothing convertible): " + stats.tilesSkippedAllDegenerate);
            System.out.println("Boxes converted:        " + stats.boxesConverted);
            System.out.println("Boxes skipped (degenerate): " + stats.boxesSkippedDegenerate);
            System.out.println("Cylinders/cones converted: " + stats.cylindersConverted);
            System.out.println("Polygons -> exact plane: " + stats.polygonsConvertedExact);
            System.out.println("Polygons -> approximated plane: " + stats.polygonsConvertedApproximate);
            System.out.println("Unknown shape types seen: " + stats.unknownShapesSeen
                + (stats.unknownShapesSeen > 0 ? " (see [TileGeometry] lines above for details)" : ""));
            System.out.println("Output written to:      " + outputDir.toAbsolutePath());
            System.out.println("Total output size:      " + stats.outputBytes + " bytes ("
                + String.format("%.1f", stats.outputBytes / 1024.0) + " KB) across " + stats.tilesConverted + " file(s)");
            if (stats.tilesConverted > 0) {
                System.out.println("Average per converted tile: " + (stats.outputBytes / stats.tilesConverted) + " bytes");
            }

        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("Parse error: " + e.getMessage());
            System.exit(1);
        }
    }
}
