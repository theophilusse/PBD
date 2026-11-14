package pbd.render;

import pbd.audio.GameSounds;
import pbd.format.PbdScene;
import pbd.pz.ContainerContents;

import java.util.List;

/**
 * What one press of E does. The key has two jobs - take an item out of an open container, switch the light of a model - and it
 * is the thing the crosshair is on that tells which one:
 *
 * <ul>
 *   <li><b>The nearer wins.</b> The item of an open container the ray goes through is {@code pick.distance()} from the eye; the
 *       part of a model the ray reaches is {@code aim.distance()} from it. A lamp in front of a shelf is switched, an item on the
 *       shelf in front of a wall lamp is taken. At the same distance the item wins (it is what E did before it switched lights).</li>
 *   <li><b>What cannot act does not compete.</b> A part of a model that has no light to switch (a wall, a table, a locker) is
 *       not a candidate: the item of a container standing behind it, or inside it, is still taken - and with no item either, the
 *       press does nothing (and says why, in the console message).</li>
 *   <li><b>One press, one thing, one sound.</b> A taken item plays the pickup sound; a switched light plays the click that
 *       light names in Blender ({@code switchSound}) or the default one; a press that did nothing is silent.</li>
 * </ul>
 *
 * <p>Pure: the caller finds the item ({@link ContainerContents#nearestAmong}) and the part ({@link LightSwitch#aim}) - the two
 * things that need to know where everything stands - and hands them over; this decides, acts, and says which sounds to play. No
 * GL, no window, no clock, so tools/regression runs it, and the key handler in Main has nothing left to decide.
 */
public final class UseKey {
    private UseKey() {}

    /** What a press did. */
    public enum Kind { ITEM, LIGHT, NOTHING }

    /**
     * kind: what was done. what: the file of the item taken, or the name of the model whose lights were switched, or null.
     * on: for LIGHT, the state the lights are in now (false otherwise). count: how many lights were switched (0 otherwise).
     * sounds: the sounds to play, in order (empty for NOTHING). message: a line for the console.
     */
    public record Outcome(Kind kind, String what, boolean on, int count, List<String> sounds, String message) {}

    /**
     * Decides and acts. {@code pick} is the nearest item of an open container the ray goes through (null for none), {@code aim}
     * the nearest part of a model it reaches within {@link LightSwitch#REACH} (null for none). Takes the item, or switches the
     * lights, and reports what it did.
     */
    public static Outcome press(PbdScene scene, LightLinks links, ContainerContents.Pick pick, LightSwitch.Aim aim) {
        boolean hasLight = aim != null && LightSwitch.lightsOf(scene, aim.instance(), links).length > 0;
        if (pick != null && (!hasLight || pick.distance() <= aim.distance())) {
            String taken = pick.take();
            return new Outcome(Kind.ITEM, taken, false, 0, GameSounds.forPickup(taken), "[Container] Removed " + taken);
        }
        if (hasLight) {
            LightSwitch.Switched done = LightSwitch.press(scene, aim.instance(), links);
            String message = "[Light] " + done.lights().length + " light(s) of '" + done.model() + "' switched " + (done.on() ? "on" : "off");
            return new Outcome(Kind.LIGHT, done.model(), done.on(), done.lights().length,
                GameSounds.forLightSwitch(true, done.sound()), message);
        }
        String message = aim == null
            ? "[E-key] nothing to take or switch: no part of a model within " + LightSwitch.REACH + " m, no item under the crosshair"
            : "[E-key] nothing to take or switch: '" + scene.instances.get(aim.instance()).id
                + "' has no light of its model (parent the light to the lamp, or give both the same linkGroup)";
        return new Outcome(Kind.NOTHING, null, false, 0, List.of(), message);
    }
}
