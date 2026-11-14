package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import pbd.format.PbdInstance;
import pbd.format.PbdScene;
import pbd.voxel.DestructionWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * The light switch of a model: E on any part of a model flips the lights of that model, with a click.
 *
 * <p><b>What the crosshair is on</b> ({@link #aim}): the nearest instance with geometry whose box the ray goes through, within
 * {@link #REACH}. Lights, groups, references and the invisible metadata volumes have no geometry to be aimed at; a destroyed
 * instance is aimed at only while something of it still stands.
 *
 * <p><b>Whose lights</b> ({@link #lightsOf}): a light belongs to ONE model, and E on a part of a model switches that model's lights.
 * <ul>
 *   <li>A <i>model</i> is the nearest group above a part whose children carry its name as a prefix ({@code lamp1} with children
 *       {@code lamp1.shade}, {@code lamp1.bulb}): that is exactly what a {@code pbd_ref} makes of the file it places - one
 *       group per placement, every id of the file prefixed with it. Two placements of one lamp are two models, a lamp placed on a
 *       desk placed in an office is a model inside a model, and a light belongs to the innermost one: E on the desk does not
 *       switch the lamp on it, E on the lamp does not switch the ceiling light of the office.</li>
 *   <li>Where no group above a part is a model (a lamp built by hand, or the file opened on its own), the model is the whole
 *       tree the part hangs from: the top of its chain of parents.</li>
 *   <li>Lights that share a {@code linkGroup} with the part aimed at are switched with it - a wall plate and the hall lamp
 *       it is meant for, wherever they stand.</li>
 *   <li>A light that follows a door, a drawer or a lever ({@link LightLinks}) is never switched by hand: that thing decides.</li>
 * </ul>
 *
 * <p><b>The switch</b> ({@link #press}): if any of the lights is on they all go off, otherwise they all go on (a room switch). The
 * click is the {@code switchSound} named by the first of them that names one - a file of the sounds folder, set in Blender - or
 * the default click of {@link pbd.audio.GameSounds}; once, however many lights there are. The state is written to
 * {@code PbdInstance.lightEnabled}, the flag the lights buffer reads: {@link LightTracker} notices the change on its own.
 *
 * <p>Pure data in, answers out: no GL, no clock, no window, so it runs under tools/regression.
 */
public final class LightSwitch {
    private LightSwitch() {}

    /** How far from the eye a press of E reaches, in metres: a little more than an arm and a lamp's length. */
    public static final double REACH = 3.5;

    /** The instance field that names the click of a light (a file of the sounds folder); absent or empty = the default click. */
    public static final String SOUND_KEY = PbdInstance.SWITCH_SOUND_KEY;

    /** The instance the crosshair is on and how far it is. */
    public record Aim(int instance, double distance) {}

    /** What one press switched: the lights (instance indices, in scene order), the state they are in now, the model, and the click (null = the default one). */
    public record Switched(int[] lights, boolean on, String model, String sound) {}

    // ------------------------------------------------------------------
    // What the crosshair is on
    // ------------------------------------------------------------------

    /**
     * The nearest instance with geometry that the ray goes through, within {@code reach} metres - or null. {@code world} is where
     * every instance stands now; {@code eligible} lets the caller leave out what is not there to be looked at (a destroyed instance
     * of which nothing remains). Nothing here changes anything.
     */
    public static Aim aim(PbdScene scene, Matrix4f[] world, IntPredicate eligible, Vector3f origin, Vector3f dir, double reach) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < scene.instances.size() && i < world.length; i++) {
            PbdInstance inst = scene.instances.get(i);
            if ("ref".equals(inst.type) || "group".equals(inst.type) || "light".equals(inst.type)) continue;
            if ("true".equals(inst.params.get("metadata"))) continue;
            if (!eligible.test(i)) continue;
            Matrix4f inverse = new Matrix4f(world[i]).invert();
            Vector3f localOrigin = inverse.transformPosition(new Vector3f(origin));
            Vector3f localDir = inverse.transformDirection(new Vector3f(dir));
            float t = DestructionWorld.rayBox(localOrigin, localDir, DestructionWorld.localBox(inst));
            if (t < 0) continue;
            Vector3f localHit = new Vector3f(localDir).mul(t).add(localOrigin);
            double distance = world[i].transformPosition(new Vector3f(localHit)).distance(origin);
            if (distance <= reach && distance < bestDistance) { // a NaN distance can never win this comparison
                bestDistance = distance;
                best = i;
            }
        }
        return best < 0 ? null : new Aim(best, bestDistance);
    }

    // ------------------------------------------------------------------
    // Whose lights
    // ------------------------------------------------------------------

    /** The index of every instance's parent (by name), -1 for a root or for a parent that is not in the scene. */
    private static int[] parentsOf(PbdScene scene) {
        int n = scene.instances.size();
        Map<String, Integer> byId = new HashMap<>();
        for (int i = 0; i < n; i++) byId.put(scene.instances.get(i).id, i);
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            String name = scene.instances.get(i).parentId;
            Integer p = name == null ? null : byId.get(name);
            parent[i] = p == null || p == i ? -1 : p;
        }
        return parent;
    }

    /** Which groups are models: a group with a child whose id starts with the group's own id and a dot. */
    private static boolean[] modelGroups(PbdScene scene, int[] parent) {
        boolean[] model = new boolean[parent.length];
        for (int i = 0; i < parent.length; i++) {
            int p = parent[i];
            if (p < 0 || !"group".equals(scene.instances.get(p).type)) continue;
            if (scene.instances.get(i).id.startsWith(scene.instances.get(p).id + ".")) model[p] = true;
        }
        return model;
    }

    /** The model an instance belongs to: the nearest group above it that is a model, else the top of its chain of parents (itself, if it has none). */
    private static int modelOf(int i, int[] parent, boolean[] model) {
        int top = i;
        int p = i;
        for (int hops = 0; hops <= parent.length && p >= 0; hops++) { // bounded: a cycle cannot loop for ever
            if (model[p]) return p;
            top = p;
            p = parent[p];
        }
        return top;
    }

    /** Whether the instance is a light that a hand may switch: a light, and not one that follows a door or a lever. */
    private static boolean isSwitchable(PbdScene scene, LightLinks links, int i) {
        return "light".equals(scene.instances.get(i).type) && (links == null || links.driversOf(i) == null);
    }

    /**
     * The lights E on instance {@code aimed} switches, in scene order: the switchable lights of its model, and those that share
     * its {@code linkGroup}. Empty when there are none (or when {@code aimed} is not an instance of the scene).
     */
    public static int[] lightsOf(PbdScene scene, int aimed, LightLinks links) {
        int n = scene.instances.size();
        if (aimed < 0 || aimed >= n) return new int[0];
        int[] parent = parentsOf(scene);
        boolean[] model = modelGroups(scene, parent);
        int home = modelOf(aimed, parent, model);
        String group = LightLinks.groupOf(scene.instances.get(aimed));
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!isSwitchable(scene, links, i)) continue;
            boolean sameModel = modelOf(i, parent, model) == home;
            boolean sameGroup = group != null && group.equals(LightLinks.groupOf(scene.instances.get(i)));
            if (sameModel || sameGroup) found.add(i);
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The name of the model instance {@code aimed} belongs to, for a message (the id of its group, or of the top of its tree). */
    public static String modelName(PbdScene scene, int aimed) {
        if (aimed < 0 || aimed >= scene.instances.size()) return "";
        int[] parent = parentsOf(scene);
        return scene.instances.get(modelOf(aimed, parent, modelGroups(scene, parent))).id;
    }

    /** The click a light names (its {@code switchSound}, trimmed), or null when it names none. */
    public static String soundOf(PbdInstance light) {
        String sound = light.params.get(SOUND_KEY);
        return sound == null || sound.isBlank() ? null : sound.trim();
    }

    // ------------------------------------------------------------------
    // The switch
    // ------------------------------------------------------------------

    /**
     * Switches the lights E on instance {@code aimed} reaches: all off if any is on, else all on. Returns what was done, or null
     * when there is no light to switch (nothing is changed then).
     */
    public static Switched press(PbdScene scene, int aimed, LightLinks links) {
        int[] lights = lightsOf(scene, aimed, links);
        if (lights.length == 0) return null;
        boolean anyOn = false;
        for (int light : lights) anyOn |= scene.instances.get(light).lightEnabled;
        boolean on = !anyOn;
        String sound = null;
        for (int light : lights) {
            PbdInstance inst = scene.instances.get(light);
            inst.lightEnabled = on;
            if (sound == null) sound = soundOf(inst);
        }
        return new Switched(lights, on, modelName(scene, aimed), sound);
    }
}
