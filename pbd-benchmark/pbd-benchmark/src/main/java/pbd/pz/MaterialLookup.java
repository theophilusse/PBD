package pbd.pz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks up a default material name from an asset's known drop items,
 * using the correspondence table at materials/drop_material_map.txt (see
 * that file for format and the "starting point, not real game data"
 * caveat). First matching keyword wins; returns null (not "default") if
 * nothing matches, so a caller can decide its own fallback rather than
 * this class silently picking one.
 */
public final class MaterialLookup {

    private record Mapping(String keyword, String material) {}

    private final List<Mapping> mappings = new ArrayList<>();

    public static MaterialLookup loadDefault() throws IOException {
        return load(PbdPaths.MATERIALS_DIR.resolve("drop_material_map.txt"));
    }

    public static MaterialLookup load(java.nio.file.Path path) throws IOException {
        MaterialLookup lookup = new MaterialLookup();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String keyword = trimmed.substring(0, eq).trim();
            String material = trimmed.substring(eq + 1).trim();
            lookup.mappings.add(new Mapping(keyword, material));
        }
        return lookup;
    }

    /** dropItems: whatever item names are known for this asset (from
     * game data, once that's available - see this project's roadmap for
     * where that data would come from). Returns null if none of the
     * table's keywords appear (as a case-insensitive substring) in any
     * of the given drop items. */
    public String materialFor(List<String> dropItems) {
        for (Mapping m : mappings) {
            for (String drop : dropItems) {
                if (drop.toLowerCase().contains(m.keyword.toLowerCase())) {
                    return m.material;
                }
            }
        }
        return null;
    }
}
