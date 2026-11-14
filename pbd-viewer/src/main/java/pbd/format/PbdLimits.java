package pbd.format;

import java.nio.file.Path;

/**
 * How much a file may ask of the engine - the same numbers for a file from the disk, one fetched from the network, one unpacked from a
 * bundle.
 *
 * <p>A scene is data from anywhere, and a small file can ask for a great deal: a compressed file that expands to gigabytes, a scene that
 * includes ten copies of a scene that includes ten copies of a scene... (a few lines for a billion instances), a bundle whose entries
 * are named {@code ../../..} to land outside the folder it is unpacked in. A limit is not a judgement on the file: every number here
 * is far above any real model, and a file that reaches one is refused with a message that names the limit.
 */
public final class PbdLimits {

    private PbdLimits() {}

    /** How deep {@code pbd_ref} may nest (a file that includes a file that includes a file...): the top-level file is depth 0. */
    public static final int MAX_INCLUDE_DEPTH = 8;

    /**
     * The most instances one scene may hold, a file included into another counting for what it brings. The scene description
     * format (and the stress tests) raise the limit of their own scene explicitly ({@code PbdScene.maxInstances}).
     */
    public static final int MAX_INSTANCES = 200_000;

    /** The longest text one scene or material file may be: 64 million characters. */
    public static final int MAX_TEXT_CHARS = 64 * 1024 * 1024;

    /** How far a compiled scene ({@code .pbdbin}) may expand when it is decompressed. */
    public static final long MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024;

    /** A bundled asset ({@code .pbdasset}): the most entries, the most one entry may weigh, the most all of them may weigh together. */
    public static final int MAX_ASSET_ENTRIES = 512;
    public static final long MAX_ASSET_ENTRY_BYTES = 64L * 1024 * 1024;
    public static final long MAX_ASSET_TOTAL_BYTES = 256L * 1024 * 1024;

    /**
     * Where the bundle entry {@code entryName} may be written below {@code root}, or null when its name is not a plain relative path
     * that stays inside: an absolute path, a drive, a backslash, a {@code ..} segment, an empty segment, a control character, a
     * name too long - and, whatever the name looks like, a path that resolves outside {@code root}.
     *
     * <p>Windows reads a name differently from the way Java writes it: it drops the dots and spaces that end a name ({@code ".. "} is
     * {@code ".."}, {@code "a."} is {@code "a"}) and answers to the names of its devices ({@code con}, {@code nul}, {@code com1}, with
     * or without an extension) instead of making a file. A bundle is made on one system and opened on another, so a name that means
     * something else somewhere is refused everywhere.
     */
    public static Path entryTarget(Path root, String entryName) {
        if (entryName == null || entryName.isEmpty() || entryName.length() > MAX_ENTRY_NAME_LENGTH) return null;
        if (entryName.startsWith("/") || entryName.indexOf('\\') >= 0 || entryName.indexOf(':') >= 0) return null;
        for (int i = 0; i < entryName.length(); i++) {
            char c = entryName.charAt(i);
            if (c < ' ' || c == 0x7f) return null;
        }
        for (String segment : entryName.split("/", -1)) {
            if (segment.isEmpty()) return null;                                   // "a//b", a name that ends with "/"
            if (segment.endsWith(".") || segment.endsWith(" ")) return null;      // ".", "..", "a.", ".. " - see above
            if (isWindowsDeviceName(segment)) return null;
        }
        try {
            Path base = root.toAbsolutePath().normalize();
            Path target = base.resolve(entryName).normalize();
            return target.startsWith(base) && !target.equals(base) ? target : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** The longest name an entry of a bundle may have. */
    public static final int MAX_ENTRY_NAME_LENGTH = 255;

    /** CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without an extension, in any case: names Windows gives to devices. */
    public static boolean isWindowsDeviceName(String segment) {
        int dot = segment.indexOf('.');
        String stem = (dot < 0 ? segment : segment.substring(0, dot)).toUpperCase(java.util.Locale.ROOT);
        switch (stem) {
            case "CON", "PRN", "AUX", "NUL":
                return true;
            default:
                return stem.length() == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) && stem.charAt(3) >= '1' && stem.charAt(3) <= '9';
        }
    }
}
