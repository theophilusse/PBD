package pbd.audio;

/**
 * The sound files that key handlers play directly (they are not authored on any keyframe, so the scene cannot list them).
 * Kept in one place so that the handler that plays a sound and the preload list that decodes it ahead of time can never
 * name different files - and so that a test can check the wiring without opening a window.
 *
 * <p>The files live in {@code src/main/resources/sounds/} (the directory {@link SoundPlayer} is created with).
 */
public final class GameSounds {
    private GameSounds() {}

    /** E on a container item. An MP3 - decoded by {@link Mp3Decoder}. */
    public static final String PICKUP = "item-equip.mp3";

    /** G: the shot that destroys voxels. */
    public static final String SHOT = "doomshotgun.wav";

    /**
     * E on a part of a model with a light: the click of the switch, when the light names none of its own ({@code switchSound} on
     * the light, set in Blender). A WAV, played by the JDK's own audio system.
     */
    public static final String LIGHT_SWITCH = "light-switch.wav";

    /** Every fixed sound, in the order the preload decodes them. */
    public static final String[] FIXED = {SHOT, PICKUP, LIGHT_SWITCH};

    /**
     * The sounds one press of E plays: the pickup sound when an item was taken out of a container ({@code takenItem} is the name
     * of what came out), nothing when the press found nothing ({@code takenItem} is null). The key handler plays what this returns,
     * so that "an empty press is silent" is decided here, where a test can see it, and not by where a call sits in a handler.
     */
    public static java.util.List<String> forPickup(String takenItem) {
        if (takenItem == null) return java.util.List.of();
        return java.util.List.of(PICKUP);
    }

    /**
     * The sounds one press of E plays when it switched a light: the click the light names ({@code switchSound}, a file of the
     * sounds folder) or, when it names none (null, empty, blank), the default click {@link #LIGHT_SWITCH}. One click per press,
     * however many lights it switched. When nothing was switched ({@code switched} is false) it is silent, whatever the first
     * argument says.
     */
    public static java.util.List<String> forLightSwitch(boolean switched, String lightSound) {
        if (!switched) return java.util.List.of();
        if (lightSound == null || lightSound.isBlank()) return java.util.List.of(LIGHT_SWITCH);
        return java.util.List.of(lightSound.trim());
    }

    /**
     * The sounds one G shot plays, in the order they start: the shot itself when it hit something, then - when a piece broke
     * away with a break - the sound the MATERIAL of that piece names (breakSound= in its .pbdmat; null when it names none, or
     * when nothing broke). A shot that hit nothing is silent, whatever the second argument says. The key handler plays what
     * this returns, so which sound goes with which outcome is decided here, where a test can see it.
     */
    public static java.util.List<String> forShot(boolean hit, String breakSound) {
        if (!hit) return java.util.List.of();
        if (breakSound == null || breakSound.isBlank()) return java.util.List.of(SHOT);
        return java.util.List.of(SHOT, breakSound);
    }
}
