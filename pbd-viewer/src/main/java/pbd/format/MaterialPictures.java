package pbd.format;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Which pictures a set of materials needs, and what each one is once the material's {@code uvRotation} has been applied
 * ({@link MaterialUv#quarterTurns}, {@link TextureTurn}) - the part of building a texture array that is not OpenGL, so that it can be
 * run, and checked, without a window.
 *
 * <p><b>A picture is a file and a number of quarter turns.</b> Two materials may use one file turned differently (a brick wall and its
 * side, say), so what the array holds, what a material looks up and what the voxels of a shot object are coloured from is the file
 * AS TURNED: its name is {@link #keyOf}. The file itself is read once however many turns of it are asked for - an image that was
 * decoded twice in quick succession once corrupted the native heap (see MaterialTextureArray), and the decoder here is the one place
 * that touches the disk.
 */
public final class MaterialPictures {
    private MaterialPictures() {}

    /**
     * One picture asked for: a file, and the number of quarter turns (counter-clockwise, any int, kept in 0..3) it is turned by once
     * loaded - what {@link MaterialUv#quarterTurns} read from the material.
     */
    public record Request(Path path, int quarterTurns) {
        public Request {
            quarterTurns = TextureTurn.normalize(quarterTurns);
        }

        /** What this picture is called: see {@link MaterialPictures#keyOf}. */
        public String key() {
            return keyOf(path, quarterTurns);
        }
    }

    /** The name of a picture: {@code "<quarter turns 0..3>:<path>"} - the turns, a colon, the path, so that no two pictures share a name. */
    public static String keyOf(Path path, int quarterTurns) {
        return TextureTurn.normalize(quarterTurns) + ":" + path;
    }

    /** What reads a file: its picture, RGBA with row 0 at the bottom, or null when it cannot be read (the decoder says why, itself). */
    public interface Decoder {
        TextureTurn.Image decode(Path path);
    }

    /** One picture of the set: what was asked for, and the file's picture turned as asked (the file's own arrays when it is not turned). */
    public record Picture(Request request, TextureTurn.Image image) {}

    /**
     * The pictures of a set of requests, in the order of their layers: one per distinct request whose file exists and could be decoded.
     * A request already seen (same file, same turns) is dropped, a file that is missing is reported once on {@code log}, a file is
     * decoded once for all the turns asked of it (and once only when it cannot be read: that failure is not tried again).
     */
    public static List<Picture> plan(Iterable<Request> requests, Predicate<Path> exists, Decoder decoder, Consumer<String> log) {
        List<Picture> pictures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> reportedMissing = new HashSet<>();
        Map<String, TextureTurn.Image> decodedByFile = new HashMap<>();   // null for a file that could not be read
        for (Request request : requests) {
            Path path = request.path();
            if (!exists.test(path)) {
                if (reportedMissing.add(path.toString())) log.accept("Not found, skipping: " + path);
                continue;
            }
            if (!seen.add(request.key())) continue;
            TextureTurn.Image file;
            if (decodedByFile.containsKey(path.toString())) {
                file = decodedByFile.get(path.toString());
            } else {
                file = decoder.decode(path);
                decodedByFile.put(path.toString(), file);
            }
            if (file == null) continue;
            pictures.add(new Picture(request, TextureTurn.rotate(file, request.quarterTurns())));
        }
        return pictures;
    }
}
