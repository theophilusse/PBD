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

    /**
     * EVERY include_material path the file lists, in file order, as written
     * (a file may list several: the Blender add-on writes one per embedded
     * asset that brought its own materials, and a hand-written scene may
     * split its materials over several files). includeMaterialPath above is
     * only the LAST of them, kept for callers written before this list
     * existed; anything that writes a scene back (PbdSerializer, so .pbdbin
     * and minify, and PbdConvertMain's .pbdasset bundling) must use this
     * list, or every include but the last silently disappears from the
     * converted file and its materials fall back to grey.
     */
    public final List<String> includeMaterialPaths = new ArrayList<>();

    /**
     * Records one include_material path: appended to includeMaterialPaths
     * unless the scene already lists it, and includeMaterialPath is kept as
     * the list's last entry (what it always meant: "the last include").
     * The parser calls this for the file's own include lines and for the
     * ones it carries up from a pbd_ref'd file.
     */
    public void addIncludeMaterialPath(String path) {
        if (!includeMaterialPaths.contains(path)) includeMaterialPaths.add(path);
        includeMaterialPath = includeMaterialPaths.get(includeMaterialPaths.size() - 1);
    }

    public final List<PbdInstance> instances = new ArrayList<>();
    public final Map<String, PbdCurve> curves = new LinkedHashMap<>();

    /**
     * What plays on its own in this scene - a camera path, keys pressed at set times, doors opened, the sun's clock - when the scene was
     * read from a scene description file ({@code .pbdscene}, see {@link SceneExpander}); null for every other scene. It is not part of
     * what a {@code .pbd} or a {@code .pbdbin} says, so the serializers leave it out.
     */
    public ShowScript show;

    // Raw field maps from every include_material'd .pbdmat file, keyed by
    // material name (e.g. "color" -> "(0.45, 0.28, 0.13)"). Kept as raw
    // strings rather than a resolved/typed material class so this stays
    // in pbd.format without depending on pbd.render (MaterialCatalog);
    // PbdRenderer parses these into real values at upload time, the same
    // place it already resolves material names to GPU data.
    public final Map<String, Map<String, String>> materialOverrides = new LinkedHashMap<>();

    /**
     * The most instances this scene accepts: {@link PbdLimits#MAX_INSTANCES}, unless whoever builds the scene knows better and says
     * so (the scene description format, for a stress test of a million boxes). A file that includes files that include files cannot
     * make a small text expand into a scene no machine can hold: past the limit, adding an instance is refused with a message.
     */
    private int maxInstances = PbdLimits.MAX_INSTANCES;

    /** The most instances this scene accepts (see above). It is a setting of whoever builds the scene, not part of what a file says. */
    public int maxInstances() {
        return maxInstances;
    }

    public void setMaxInstances(int limit) {
        if (limit < 1) throw new IllegalArgumentException("a scene holds at least one instance, got a limit of " + limit);
        this.maxInstances = limit;
    }

    public void addInstance(PbdInstance instance) {
        for (PbdInstance existing : instances) {
            if (existing.id.equals(instance.id)) {
                throw new IllegalArgumentException("Duplicate instance id: " + instance.id);
            }
        }
        addVerified(instance);
    }

    /**
     * Adds an instance whose id the caller has already made sure is not in the scene (a parser with its own hash set of ids): the
     * search of {@link #addInstance} reads every instance already there, which is what turns 100,000 instances into minutes. The size
     * limit still applies.
     */
    public void addVerified(PbdInstance instance) {
        if (instances.size() >= maxInstances) {
            throw new IllegalStateException("A scene holds at most " + maxInstances
                + " instances - refused (a file that includes files that include files can expand without limit)");
        }
        instances.add(instance);
    }

    public void addCurve(PbdCurve curve) {
        curves.put(curve.id, curve);
    }

    /**
     * Every distinct sound file this scene can play on its own - the
     * `sound=` of each keyframe plus each lever arm's openSound and
     * closeSound and each light's switchSound (the click of E), in first-use
     * order, then the `breakSound` of each material (BreakProfile: what plays
     * when a piece of a thing made of it comes away). What a viewer decodes up front (SoundPlayer.preload) so
     * the first door or the first break that needs a sound does not stall a
     * frame decoding it. Read live from the instances and the materials, so
     * a scene loaded on the fly gets its own list with nothing cached in
     * between.
     */
    public Set<String> soundFiles() {
        Set<String> files = new java.util.LinkedHashSet<>();
        for (PbdInstance inst : instances) {
            for (PbdInstance.Keyframe kf : inst.keyframes) {
                if (kf.sound != null) files.add(kf.sound);
            }
            if (inst.leverArm != null) {
                if (inst.leverArm.openSound != null) files.add(inst.leverArm.openSound);
                if (inst.leverArm.closeSound != null) files.add(inst.leverArm.closeSound);
            }
            if ("light".equals(inst.type)) { // the click of a light switched by hand (E): switchSound, see pbd.render.LightSwitch
                String click = inst.params.get(PbdInstance.SWITCH_SOUND_KEY);
                if (click != null && !click.isBlank()) files.add(click.trim());
            }
        }
        for (Map<String, String> material : materialOverrides.values()) {
            String breakSound = BreakProfile.breakSoundOf(material);
            if (breakSound != null) files.add(breakSound);
        }
        return files;
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
