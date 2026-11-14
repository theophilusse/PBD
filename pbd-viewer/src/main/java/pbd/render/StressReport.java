package pbd.render;

import pbd.format.PbdLimits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Writes the report of a stress run next to the scene file that asked for it ({@code show report=stress.txt}).
 *
 * <p>A scene file is something a person may be handed, and opening it must not be able to damage anything: the report goes in the
 * folder of the scene file, under a plain file name ending in {@code .txt}, and it never replaces a file that is not itself a stress
 * report (one that starts with {@link #HEADER}). A second run over the report of the first replaces it; a document that happens to
 * have the same name is left alone and the run says so.
 */
public final class StressReport {
    private StressReport() {}

    /** The first words of every report, which is how a report is told from any other file. */
    public static final String HEADER = "PBD stress report";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,59}\\.txt");

    /**
     * Writes {@code text} to {@code folder/name}.
     *
     * @return the file written
     * @throws IOException with the reason when the report is not written
     */
    public static Path write(Path folder, String name, String text) throws IOException {
        if (name == null || !NAME.matcher(name).matches() || PbdLimits.isWindowsDeviceName(name)) {
            throw new IOException("'" + name + "' is not a plain report file name (letters, digits, '.', '_', '-', ending in .txt; not the name of a device)");
        }
        if (!text.startsWith(HEADER)) throw new IOException("the text is not a stress report");
        Path target = folder.toAbsolutePath().normalize().resolve(name).normalize();
        if (!target.getParent().equals(folder.toAbsolutePath().normalize())) {
            throw new IOException("'" + name + "' is not in the folder of the scene file");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("'" + name + "' exists and is not a plain file - not replaced");
            }
            byte[] head = new byte[HEADER.length()];
            int read;
            try (InputStream in = Files.newInputStream(target)) {
                read = in.readNBytes(head, 0, head.length);
            }
            if (read < head.length || !new String(head, StandardCharsets.UTF_8).equals(HEADER)) {
                throw new IOException("'" + name + "' exists and is not a stress report - not replaced (choose another report= name)");
            }
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return target;
    }
}
