package pbd.format;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open name <-> numeric id table for materials, same pattern as
 * PrimitiveRegistry/ModifierRegistry. Any material name found in a .pbd
 * file gets registered here, even if no visual definition exists for it
 * yet on the GPU side (see MaterialCatalog for the actual visual
 * properties of the three built-in test materials) - an unknown material
 * still gets a valid id and falls back to a default appearance rather
 * than failing to parse.
 */
public final class MaterialRegistry {

    private final Map<String, Integer> nameToId = new LinkedHashMap<>();
    private final Map<Integer, String> idToName = new LinkedHashMap<>();
    private int nextId = 0;

    public MaterialRegistry() {
        register("wood");
        register("metal");
        register("plastic");
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

    /** Registers the name if it hasn't been seen before, then returns its id. */
    public int idOf(String name) {
        return register(name);
    }

    public String nameOf(int id) {
        return idToName.get(id);
    }

    public int count() {
        return nextId;
    }
}
