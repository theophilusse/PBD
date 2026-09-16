package pbd.format;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open name <-> numeric id table for primitive types. Adding a new type
 * is a call to register(), never an enum entry to touch throughout the
 * codebase (parser, intermediate representation, shaders).
 */
public final class PrimitiveRegistry {

    private final Map<String, Integer> nameToId = new LinkedHashMap<>();
    private final Map<Integer, String> idToName = new LinkedHashMap<>();
    private int nextId = 0;

    public PrimitiveRegistry() {
        register("plane");
        register("sphere");
        register("cylinder");
        register("cone");
        register("cube");
        register("disc");
        register("torus");
        register("mesh");   // traditional (non-procedural) triangulated geometry - see PbdMeshData for the raw vertex/index storage and PbdParser's "vertexData=" field for how it's embedded as base64 text
        register("light");  // a point or spot light source - no geometry of its own (same category as "group" below), see PbdInstance's own light* fields
        register("group"); // pbd_ref's zero-geometry anchor instance - see PbdParser.parsePbdRef / PatchExpander
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
            throw new IllegalArgumentException("Unknown primitive type: " + name);
        }
        return id;
    }

    public String nameOf(int id) {
        return idToName.get(id);
    }
}
