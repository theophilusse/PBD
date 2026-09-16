package pbd.audio;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays a sound once when a keyframe carrying a `sound=` field is
 * crossed during playback - a door creak at the moment it starts
 * swinging, say.
 *
 * Uses javax.sound.sampled (the JDK's own built-in audio API, no new
 * dependency) rather than LWJGL's OpenAL bindings - this project has no
 * other audio need yet (no 3D positional audio, no mixing many
 * simultaneous sounds with real control over them), and pulling in a
 * whole audio engine for "play this clip once" would be a lot of new
 * surface for what's needed today. Revisit if positional/3D audio
 * becomes an actual requirement.
 *
 * ONLY WAV IS SUPPORTED - javax.sound.sampled has no built-in .ogg/.mp3
 * decoder. A file with any other extension logs a clear warning and is
 * skipped rather than failing silently or crashing playback.
 */
public final class SoundPlayer {

    private final Path soundsDir;
    private final Map<String, Clip> cache = new HashMap<>();

    public SoundPlayer(Path soundsDir) {
        this.soundsDir = soundsDir;
    }

    /** Plays soundFileName once, from the start, non-blocking (returns
     * immediately - playback happens on its own line via the JDK's own
     * audio thread). Clips are loaded once and cached by filename, since
     * the same door creak might play many times over a session. Logs a
     * warning and does nothing on any failure (missing file, unsupported
     * format, no audio device available) rather than throwing - a
     * missing sound effect shouldn't crash the viewer. */
    public void play(String soundFileName) {
        Clip clip = cache.get(soundFileName);
        if (clip == null) {
            clip = load(soundFileName);
            if (clip == null) return; // load() already logged why
            cache.put(soundFileName, clip);
        }
        clip.setFramePosition(0);
        clip.start();
    }

    /** Decodes and caches soundFileName WITHOUT playing it - call once
     * per distinct sound, for every sound a scene's keyframes reference,
     * right after construction and before the render loop starts.
     * play()'s own lazy load-on-first-use used to do this decode step
     * (AudioSystem.getAudioInputStream + Clip.open, both real, measured
     * disk/CPU work) synchronously on the FIRST click that needed a
     * given sound - on the very same thread driving animation and
     * rendering, so that frame ran long, and the NEXT frame's delta time
     * (measured against the wall clock, which kept moving during the
     * decode) came out oversized, making the door's swing visibly jump
     * ahead right as the now-loaded sound finally started - reported as
     * "not quite locked to the first frame." Preloading moves that same
     * decode cost to scene load, once, before anything is watching. */
    public void preload(String soundFileName) {
        if (!cache.containsKey(soundFileName)) {
            Clip clip = load(soundFileName);
            if (clip != null) cache.put(soundFileName, clip);
        }
    }

    private Clip load(String soundFileName) {
        if (!soundFileName.toLowerCase().endsWith(".wav")) {
            System.err.println("[SoundPlayer] '" + soundFileName + "' isn't a .wav file - "
                + "javax.sound.sampled (this project's audio backend) has no built-in .ogg/.mp3 decoder");
            return null;
        }
        Path path = soundsDir.resolve(soundFileName);
        if (!Files.exists(path)) {
            System.err.println("[SoundPlayer] Sound file not found: " + path.toAbsolutePath());
            return null;
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            Clip clip = AudioSystem.getClip();
            clip.open(stream);
            return clip;
        } catch (Exception e) {
            // Broad catch is deliberate here: javax.sound.sampled throws
            // several different checked exception types for "this audio
            // setup doesn't work" (unsupported format, no line
            // available, I/O error) and a missing sound effect should
            // never be fatal to the viewer regardless of which one it is.
            System.err.println("[SoundPlayer] Failed to load '" + soundFileName + "': " + e.getMessage());
            return null;
        }
    }

    public void close() {
        for (Clip clip : cache.values()) clip.close();
        cache.clear();
    }
}
