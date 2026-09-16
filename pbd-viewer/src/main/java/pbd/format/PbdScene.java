package pbd.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsing result: every instance, every named curve, and the resolved
 * hierarchy (invariant guaranteed after resolveHierarchy(): for every
 * instance, parentIndex < its own index in the list).
 */
public final class PbdScene {

    // Scene-level metadata - all optional, all describing the FILE itself
    // rather than any one instance in it. Parsed once at the top of the
    // file (see PbdParser's top-level loop), null/empty when absent.
    public String name;                      // "Wardrobe", "Rain Barrel" - a human-readable label, not the same thing as any instance's own id
    public String kind;                      // a free-form category ("furniture", "container", "decoration") - not validated against a fixed list, since new kinds will keep appearing as the format's used for more asset types
    public final List<String> authors = new ArrayList<>(); // every author=X line, in file order - a plain single field would lose all but the last of several credited authors
    public String origin;                    // the URL this file was fetched from, if any - see PbdFetcher for how this drives caching (a file with no origin was authored locally, not downloaded)
    public String description;               // free-form, searchable text (see server/search.php) - documented in PBD_FORMAT_SPEC.md as optional scene metadata; added here alongside readMetadataValue's own fix so writing it doesn't crash the parser the same way an unquoted multi-word kind= did

    // The RAW (as-written, still relative) include_material= value, if
    // the file had one - kept alongside the already-parsed
    // materialOverrides below so a re-serialization (PbdSerializer,
    // used by both plain re-saves and .pbdbin) can write the reference
    // back out instead of silently dropping it. Not resolved to an
    // absolute Path here on purpose: what "relative to" means depends
    // entirely on where the SCENE gets serialized TO next, which this
    // class has no way to know at parse time - only the writer does.
    public String includeMaterialPath;

    public final List<PbdInstance> instances = new ArrayList<>();
    public final Map<String, PbdCurve> curves = new LinkedHashMap<>();

    // Raw field maps from every include_material'd .pbdmat file, keyed by
    // material name (e.g. "color" -> "(0.45, 0.28, 0.13)"). Kept as raw
    // strings rather than a resolved/typed material class so this stays
    // in pbd.format without depending on pbd.render (MaterialCatalog);
    // PbdRenderer parses these into real values at upload time, the same
    // place it already resolves material names to GPU data.
    public final Map<String, Map<String, String>> materialOverrides = new LinkedHashMap<>();

    public void addInstance(PbdInstance instance) {
        for (PbdInstance existing : instances) {
            if (existing.id.equals(instance.id)) {
                throw new IllegalArgumentException("Duplicate instance id: " + instance.id);
            }
        }
        instances.add(instance);
    }

    public void addCurve(PbdCurve curve) {
        curves.put(curve.id, curve);
    }

    /**
     * Reorders `instances` into topological order (parent before child)
     * and fills in parentIndex accordingly. Detects parenting cycles and
     * references to an unknown id. Multiple roots in the same file are
     * allowed (multiple independent rigs).
     */
    public void resolveHierarchy() {
        Map<String, PbdInstance> byId = new HashMap<>();
        for (PbdInstance inst : instances) byId.put(inst.id, inst);

        List<PbdInstance> ordered = new ArrayList<>(instances.size());
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (PbdInstance inst : instances) {
            visit(inst, byId, visited, visiting, ordered);
        }

        instances.clear();
        instances.addAll(ordered);

        Map<String, Integer> idToIndex = new HashMap<>();
        for (int i = 0; i < instances.size(); i++) {
            idToIndex.put(instances.get(i).id, i);
        }
        for (PbdInstance inst : instances) {
            inst.parentIndex = (inst.parentId == null) ? -1 : idToIndex.get(inst.parentId);
        }
    }

    private void visit(PbdInstance inst, Map<String, PbdInstance> byId,
                        Set<String> visited, Set<String> visiting, List<PbdInstance> ordered) {
        if (visited.contains(inst.id)) return;
        if (visiting.contains(inst.id)) {
            throw new IllegalStateException("Parenting cycle detected at: " + inst.id);
        }
        if (inst.parentId != null) {
            PbdInstance parent = byId.get(inst.parentId);
            if (parent == null) {
                throw new IllegalArgumentException(
                    "'" + inst.id + "' references an unknown parent: " + inst.parentId);
            }
            visiting.add(inst.id);
            visit(parent, byId, visited, visiting, ordered);
            visiting.remove(inst.id);
        }
        visited.add(inst.id);
        ordered.add(inst);
    }
}
