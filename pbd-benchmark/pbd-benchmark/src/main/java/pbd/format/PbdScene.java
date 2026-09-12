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
