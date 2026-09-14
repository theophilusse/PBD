package pbd.pz;

import pbd.format.ModifierRegistry;
import pbd.format.PbdParser;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adds a PBD scene's geometry to a tileGeometry.txt file, as a new tile
 * in an existing (or new) tileset - the reverse direction of
 * TileGeometryConverterMain.
 *
 * Usage: PbdToTileGeometryMain &lt;scene.pbd&gt; &lt;tileGeometry.txt&gt; &lt;tilesetName&gt; [outputPath]
 *
 * If tileGeometry.txt doesn't exist yet, starts a fresh one (VERSION 2,
 * one tileset). If it exists, the new tile is appended to the named
 * tileset (created if not already present) at the next open xy slot in
 * that tileset's grid. outputPath defaults to overwriting the input
 * tileGeometry.txt in place - pass a different path to write elsewhere
 * instead and leave the original untouched.
 */
public final class PbdToTileGeometryMain {

    /** Tiles-per-row assumption for auto-picking the next open xy slot -
     * not derived from anything PZ-authoritative (no sprite-sheet
     * dimension is available from tileGeometry.txt itself), just a
     * placeholder wide enough to be a reasonable default. Pass an
     * explicit xy via a future CLI flag if a specific slot matters. */
    private static final int ASSUMED_SHEET_WIDTH = 8;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: PbdToTileGeometryMain <scene.pbd> <tileGeometry.txt> <tilesetName> [outputPath]");
            System.exit(1);
        }

        Path scenePath = Path.of(args[0]);
        Path tileGeometryPath = Path.of(args[1]);
        String tilesetName = args[2];
        Path outputPath = args.length > 3 ? Path.of(args[3]) : tileGeometryPath;

        try {
            PrimitiveRegistry primitiveRegistry = new PrimitiveRegistry();
            ModifierRegistry modifierRegistry = new ModifierRegistry();
            PbdScene scene = new PbdParser(primitiveRegistry, modifierRegistry).parseFile(scenePath);
            System.out.println("Parsed " + scenePath + ": " + scene.instances.size() + " instance(s)");

            TileGeometryParser.TileGeometryFile file;
            if (Files.exists(tileGeometryPath)) {
                file = new TileGeometryParser().parseFile(tileGeometryPath);
                System.out.println("Loaded existing " + tileGeometryPath + " (" + file.tilesets.size() + " tileset(s))");
            } else {
                file = new TileGeometryParser.TileGeometryFile();
                file.version = 2;
                System.out.println(tileGeometryPath + " doesn't exist yet - starting a new file");
            }

            TileGeometryParser.Tileset tileset = findOrCreateTileset(file, tilesetName);
            int[] xy = nextOpenSlot(tileset);
            String comment = scenePath.getFileName().toString().replaceFirst("\\.pbd$", "");

            PbdToTileGeometryConverter converter = new PbdToTileGeometryConverter();
            PbdToTileGeometryConverter.Result result = converter.convert(scene, xy[0], xy[1], comment);
            tileset.tiles.add(result.tile);

            String serialized = new TileGeometrySerializer().serialize(file);
            Files.write(outputPath, serialized.getBytes(StandardCharsets.UTF_8));

            System.out.println();
            System.out.println("=== Added tile '" + comment + "' at xy=" + xy[0] + "x" + xy[1]
                + " in tileset '" + tilesetName + "' ===");
            System.out.println("Lossless shapes:    " + result.losslessCount);
            System.out.println("Approximated shapes: " + result.approximatedCount
                + (result.approximatedCount > 0 ? " (true PBD source recorded in this tile's PBD_SOURCE_* properties)" : ""));
            System.out.println("Written to: " + outputPath.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static TileGeometryParser.Tileset findOrCreateTileset(TileGeometryParser.TileGeometryFile file, String name) {
        for (TileGeometryParser.Tileset ts : file.tilesets) {
            if (ts.name.equals(name)) return ts;
        }
        TileGeometryParser.Tileset created = new TileGeometryParser.Tileset(name);
        file.tilesets.add(created);
        return created;
    }

    private static int[] nextOpenSlot(TileGeometryParser.Tileset tileset) {
        boolean[][] occupied = new boolean[1000][ASSUMED_SHEET_WIDTH]; // generous upper bound on rows
        for (TileGeometryParser.Tile t : tileset.tiles) {
            if (t.xyY < occupied.length && t.xyX < ASSUMED_SHEET_WIDTH) {
                occupied[t.xyY][t.xyX] = true;
            }
        }
        for (int y = 0; y < occupied.length; y++) {
            for (int x = 0; x < ASSUMED_SHEET_WIDTH; x++) {
                if (!occupied[y][x]) return new int[]{x, y};
            }
        }
        throw new IllegalStateException("No open xy slot found (tileset improbably full) - specify one manually");
    }
}
