package pbd.format;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open name <-> numeric id table for modifier types. Same logic as
 * PrimitiveRegistry: adding a modifier only touches this table (and the
 * TES, later, on the GPU side), never the parser.
 */
public final class ModifierRegistry {

    private final Map<String, Integer> nameToId = new LinkedHashMap<>();
    private final Map<Integer, String> idToName = new LinkedHashMap<>();
    private int nextId = 0;

    public ModifierRegistry() {
        register("bend");
        register("taper");
        register("twist");
        register("curve");
        register("shear");
    }

    public int register(String name) {
        Integer existing = nameToId.get(name);
        if (existing != null) return existing;
        int id = nextId++;
        nameToId.put(name, id);
        idToName.put(id, name);
        return id;
    }

    public boolean isKnown(String name) {
        return nameToId.containsKey(name);
    }

    public int idOf(String name) {
        Integer id = nameToId.get(name);
        if (id == null) {
            throw new IllegalArgumentException("Unknown modifier type: " + name);
        }
        return id;
    }

    public String nameOf(int id) {
        return idToName.get(id);
    }
}
