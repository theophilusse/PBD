package pbd.render;

import pbd.format.PbdInstance;
import pbd.format.PbdScene;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The toggleable light: a light that switches with a door, a drawer or a lever.
 *
 * A light is linked to whatever shares its {@code linkGroup} (the free-form instance field that already makes a wardrobe's
 * twin doors open together) and can open and close: a keyframed instance or one with a leverArm. While ANY of those is open
 * the light is on, and when they are all closed again it is off - the fridge lamp, the wardrobe light, and, with a small
 * lever as the opening object, a wall switch the player flips with the mouse. No format key is added for it: a light with a
 * linkGroup and a partner that can open simply behaves this way, in every file format that already carries linkGroup.
 *
 * <ul>
 *   <li><b>Who counts as a partner</b> (a "driver"): an instance of the same group that is not itself a light, has keyframes
 *       or a leverArm, and does not loop (a ceiling fan turning forever has no open or closed). A light whose group has no
 *       driver is not linked at all: its own {@code lightEnabled} stays what the file says.</li>
 *   <li><b>At load</b> every linked light takes the state of its group (doors load shut, so the light is off). The file's
 *       lightEnabled does not matter for a linked light - that is what makes "link it to the door" enough, with nothing
 *       else to remember to untick.</li>
 *   <li><b>Afterwards</b> it is edge-triggered: the light changes when its group's open state changes, not on every frame, so
 *       a script that switches the lamp by hand (PbdEngine.SceneHandle.toggleLight) is not undone a frame later; the next
 *       time the door opens or closes the light follows it again.</li>
 *   <li>The state is written to {@code PbdInstance.lightEnabled}, the one flag the lights buffer and the facade both read.</li>
 * </ul>
 *
 * Pure data and a predicate in, a count out: no GL, no clock, so it runs under tools/regression without a window.
 * PbdRenderer owns the one instance per uploaded scene and answers {@code isOpen} with its own isInstanceOpen.
 */
public final class LightLinks {

    private final PbdScene scene;
    private final int[] lights;     // instance index of every linked light
    private final int[][] drivers;  // per linked light: the instances that switch it
    private final boolean[] seen;   // per linked light: was its group open when it was last looked at
    private boolean primed;

    public LightLinks(PbdScene scene) {
        this.scene = scene;
        List<Integer> found = new ArrayList<>();
        List<int[]> partners = new ArrayList<>();
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance light = scene.instances.get(i);
            String group = groupOf(light);
            if (!"light".equals(light.type) || group == null) continue;
            List<Integer> mine = new ArrayList<>();
            for (int d = 0; d < scene.instances.size(); d++) {
                PbdInstance other = scene.instances.get(d);
                if (isDriver(other) && group.equals(groupOf(other))) mine.add(d);
            }
            if (mine.isEmpty()) continue;
            found.add(i);
            partners.add(mine.stream().mapToInt(Integer::intValue).toArray());
        }
        lights = found.stream().mapToInt(Integer::intValue).toArray();
        drivers = partners.toArray(new int[0][]);
        seen = new boolean[lights.length];
    }

    /** The link group an instance names, or null when it names none (a missing or empty linkGroup). */
    public static String groupOf(PbdInstance inst) {
        String group = inst.params.get("linkGroup");
        return group == null || group.isEmpty() ? null : group;
    }

    /** Whether an instance can switch a light of its group: it opens and closes (keyframes or a leverArm), is not a light itself, and does not loop. */
    public static boolean isDriver(PbdInstance inst) {
        if ("light".equals(inst.type)) return false;
        if ("true".equals(inst.params.get("loop"))) return false;
        return !inst.keyframes.isEmpty() || inst.leverArm != null;
    }

    /** How many lights follow a group. */
    public int linkedCount() { return lights.length; }

    /** The instance indices of the linked lights, in scene order. */
    public int[] linkedLights() { return lights.clone(); }

    /** The instances that switch the light at instance index {@code light}; null when that light is not linked. */
    public int[] driversOf(int light) {
        for (int k = 0; k < lights.length; k++) if (lights[k] == light) return drivers[k].clone();
        return null;
    }

    /**
     * Looks at every linked light's group and switches the light where it is due. {@code isOpen} answers whether instance i is
     * open right now. The first call puts every linked light in the state of its group; later calls only act on a CHANGE of a
     * group's open state. Returns how many lights actually changed (0 = the lights buffer is still right).
     */
    public int update(IntPredicate isOpen) {
        int switched = 0;
        for (int k = 0; k < lights.length; k++) {
            boolean open = false;
            for (int d : drivers[k]) {
                if (isOpen.test(d)) {
                    open = true;
                    break;
                }
            }
            if (primed && open == seen[k]) continue;
            seen[k] = open;
            PbdInstance light = scene.instances.get(lights[k]);
            if (light.lightEnabled != open) {
                light.lightEnabled = open;
                switched++;
            }
        }
        primed = true;
        return switched;
    }
}
