package pbd.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
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
 * WAV AND MP3 ARE SUPPORTED. javax.sound.sampled reads WAV itself; it has
 * no .mp3 (or .ogg) decoder, so an .mp3 is decoded by this project's own
 * {@link Mp3Decoder} (pure Java, a port of the public-domain minimp3) into
 * 16-bit PCM and handed to the Clip as raw samples. A file with any other
 * extension logs a clear warning and is skipped rather than failing
 * silently or crashing playback.
 */
public final class SoundPlayer {

    /**
     * Where the player gets the Clips it opens a sound on: the audio system in the program ({@code AudioSystem::getClip}), a
     * stand-in that records what it is asked in a test - so that what the player does with a Clip (what it is opened with, that
     * it is started from its first frame, that it is decoded once and closed at the end) can be checked on a machine without a
     * sound card.
     */
    public interface ClipSource {
        Clip newClip() throws LineUnavailableException;
    }

    private final Path soundsDir;
    private final ClipSource clips;
    private final Map<String, Clip> cache = new HashMap<>();

    public SoundPlayer(Path soundsDir) {
        this(soundsDir, AudioSystem::getClip);
    }

    public SoundPlayer(Path soundsDir, ClipSource clips) {
        this.soundsDir = soundsDir;
        this.clips = clips;
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

    /** True for the file types this player can load (by extension, case-insensitive): .wav and .mp3. */
    public static boolean isSupported(String soundFileName) {
        String lower = soundFileName.toLowerCase();
        return lower.endsWith(".wav") || lower.endsWith(".mp3");
    }

    /** True for a file this player decodes itself (not through javax.sound.sampled). */
    public static boolean isMp3(String soundFileName) {
        return soundFileName.toLowerCase().endsWith(".mp3");
    }

    /** An MP3 decoded to what a Clip is opened with: the PCM format and the raw bytes. No audio device is involved, so it can be tested anywhere. */
    public static final class Decoded {
        public final AudioFormat format;
        public final byte[] data;

        Decoded(AudioFormat format, byte[] data) {
            this.format = format;
            this.data = data;
        }
    }

    /** Decodes an .mp3 file into 16-bit signed little-endian PCM (frames of {@code channels * 2} bytes). */
    public static Decoded decodeMp3(Path path) throws IOException {
        return decodeMp3(Files.readAllBytes(path));
    }

    public static Decoded decodeMp3(byte[] file) throws IOException {
        Mp3Decoder.Pcm pcm = Mp3Decoder.decode(file);
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, pcm.sampleRate, 16, pcm.channels,
            pcm.channels * 2, pcm.sampleRate, false);
        return new Decoded(format, pcm.toLittleEndianBytes());
    }

    /**
     * True when {@code soundFileName} names a file under {@code soundsDir}: a sub-folder is fine ("doors/creak.wav", even
     * "doors/../creak.wav"), a way out of the folder ("../x.wav", an absolute path, a name the file system cannot read) is not.
     * The names come from scenes and materials (sound=, openSound=, breakSound=), which may come from anywhere - they must not
     * make the viewer open anything else on the disk.
     */
    public static boolean staysInside(Path soundsDir, String soundFileName) {
        try {
            Path base = soundsDir.toAbsolutePath().normalize();
            Path file = base.resolve(soundFileName).normalize();
            return file.startsWith(base) && !file.equals(base);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private Clip load(String soundFileName) {
        if (!isSupported(soundFileName)) {
            System.err.println("[SoundPlayer] '" + soundFileName + "' isn't a .wav or .mp3 file - "
                + "javax.sound.sampled (this project's audio backend) has no built-in .ogg decoder");
            return null;
        }
        if (!staysInside(soundsDir, soundFileName)) {
            System.err.println("[SoundPlayer] '" + soundFileName + "' is not inside the sounds folder " + soundsDir.toAbsolutePath()
                + " - refused (a sound name is a file name under that folder, not a path out of it)");
            return null;
        }
        Path path = soundsDir.resolve(soundFileName);
        if (!Files.exists(path)) {
            System.err.println("[SoundPlayer] Sound file not found: " + path.toAbsolutePath());
            return null;
        }
        try {
            if (isMp3(soundFileName)) {
                Decoded d = decodeMp3(path);
                Clip clip = clips.newClip();
                clip.open(d.format, d.data, 0, d.data.length);
                return clip;
            }
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
                Clip clip = clips.newClip();
                clip.open(stream);
                return clip;
            }
        } catch (Exception e) {
            // Broad catch is deliberate here: javax.sound.sampled throws
            // several different checked exception types for "this audio
            // setup doesn't work" (unsupported format, no line
            // available, I/O error), the MP3 decoder reports a file it
            // cannot play with its own exception, and a missing sound
            // effect should never be fatal to the viewer regardless of
            // which one it is.
            System.err.println("[SoundPlayer] Failed to load '" + soundFileName + "': " + e.getMessage());
            return null;
        }
    }

    public void close() {
        for (Clip clip : cache.values()) clip.close();
        cache.clear();
    }
}
