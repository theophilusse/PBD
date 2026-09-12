package pbd.fbx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

/**
 * Reads the binary FBX file format's generic node tree - a proprietary
 * (Autodesk doesn't publish an official spec) but well enough understood
 * binary format: a fixed header, then a sequence of Nodes, each with a
 * name, zero or more typed Properties, and zero or more nested child
 * Nodes, terminated by an all-zero "NULL record".
 *
 * Verified against a real, specific file (BeerBottle.FBX, version 7300)
 * during development - not against the FBX spec directly (there isn't a
 * public one), and not against every FBX variant that exists (binary
 * vs ASCII, pre-7500 vs post-7500 offset widths, exotic property types).
 * This reads version &lt;7500 (32-bit offsets) correctly, matching every
 * real file seen so far; a version &gt;=7500 file would need 64-bit
 * offsets instead - not yet handled, and not silently guessed at either
 * (see the version check in read()).
 *
 * This class only reads the generic node tree - it has no idea what a
 * "Geometry" or "Model" node means. See FbxGeometryExtractor for turning
 * this generic tree into an actual renderable mesh.
 */
public final class FbxBinaryReader {

    public static final class Node {
        public final String name;
        public final List<Object> properties = new ArrayList<>(); // element type per FBX property type - see readProperty
        public final List<Node> children = new ArrayList<>();

        Node(String name) {
            this.name = name;
        }

        /** First child with this exact name, or null - FBX nodes are
         * commonly addressed this way (a Geometry node's "Vertices"
         * child, say), and most node types don't repeat a child name
         * more than once at the same level. */
        public Node child(String childName) {
            for (Node c : children) {
                if (c.name.equals(childName)) return c;
            }
            return null;
        }

        public List<Node> childrenNamed(String childName) {
            List<Node> result = new ArrayList<>();
            for (Node c : children) {
                if (c.name.equals(childName)) result.add(c);
            }
            return result;
        }

        /** Every descendant (depth-first) with this name, not just direct children. */
        public List<Node> findAll(String targetName) {
            List<Node> result = new ArrayList<>();
            findAllInto(targetName, result);
            return result;
        }

        private void findAllInto(String targetName, List<Node> out) {
            if (name.equals(targetName)) out.add(this);
            for (Node c : children) c.findAllInto(targetName, out);
        }
    }

    public static final class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    private final ByteBuffer buf;

    private FbxBinaryReader(byte[] data) {
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static List<Node> readFile(Path path) throws IOException {
        return new FbxBinaryReader(Files.readAllBytes(path)).read();
    }

    private List<Node> read() {
        byte[] magic = new byte[20];
        buf.get(magic);
        String magicStr = new String(magic, StandardCharsets.US_ASCII);
        if (!magicStr.equals("Kaydara FBX Binary  ")) {
            throw new ParseException("Not a binary FBX file (magic header mismatch) - "
                + "got: " + magicStr + " - this might be an ASCII FBX file instead, which needs a different reader");
        }
        buf.position(23); // skip 2 unknown bytes + 1 more, matching the 27-byte total header seen in every real file so far
        int version = buf.getInt();
        if (version >= 7500) {
            throw new ParseException("FBX version " + version + " uses 64-bit node offsets - "
                + "this reader only handles <7500 (32-bit offsets), matching every file actually seen during development. "
                + "Extending readNode() below to branch on version is the needed change, not yet done.");
        }

        List<Node> topLevel = new ArrayList<>();
        while (buf.remaining() > 4) {
            Node node = readNode();
            if (node == null) break; // NULL record - end of this level (top level, here)
            topLevel.add(node);
        }
        return topLevel;
    }

    /** Returns null for a NULL record (13 zero bytes + zero-length name) - FBX's own way of marking "no more children/nodes here". */
    private Node readNode() {
        long endOffset = Integer.toUnsignedLong(buf.getInt());
        long numProperties = Integer.toUnsignedLong(buf.getInt());
        long propertyListLen = Integer.toUnsignedLong(buf.getInt());
        int nameLen = buf.get() & 0xFF;

        if (endOffset == 0 && numProperties == 0 && propertyListLen == 0 && nameLen == 0) {
            return null;
        }

        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        Node node = new Node(new String(nameBytes, StandardCharsets.US_ASCII));

        for (long i = 0; i < numProperties; i++) {
            node.properties.add(readProperty());
        }

        while (buf.position() < endOffset) {
            Node child = readNode();
            if (child == null) break;
            node.children.add(child);
        }

        return node;
    }

    /** Returns a Float/Double/Integer/Long/Short/Boolean/String/byte[]
     * for scalar types, or a float[]/double[]/int[]/long[]/boolean[] for
     * array types - callers cast to whatever the specific field is
     * documented to be (see FbxGeometryExtractor for the fields this
     * project actually reads). */
    private Object readProperty() {
        char typeCode = (char) buf.get();
        return switch (typeCode) {
            case 'Y' -> buf.getShort();
            case 'C' -> buf.get() != 0;
            case 'I' -> buf.getInt();
            case 'F' -> buf.getFloat();
            case 'D' -> buf.getDouble();
            case 'L' -> buf.getLong();
            case 'f' -> readArray('f');
            case 'd' -> readArray('d');
            case 'l' -> readArray('l');
            case 'i' -> readArray('i');
            case 'b' -> readArray('b');
            case 'S' -> readRawBytesAsString();
            case 'R' -> readRawBytes();
            default -> throw new ParseException("Unknown FBX property type code: '" + typeCode + "' (0x"
                + Integer.toHexString(typeCode) + ") at buffer position " + (buf.position() - 1));
        };
    }

    private String readRawBytesAsString() {
        return new String(readRawBytes(), StandardCharsets.UTF_8);
    }

    private byte[] readRawBytes() {
        int length = buf.getInt();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return bytes;
    }

    private Object readArray(char elementType) {
        int arrayLength = buf.getInt();
        int encoding = buf.getInt(); // 0 = raw, 1 = zlib-deflate compressed
        int compressedLength = buf.getInt();

        byte[] raw = new byte[compressedLength];
        buf.get(raw);
        byte[] data = (encoding == 1) ? inflate(raw) : raw;

        ByteBuffer arrayBuf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return switch (elementType) {
            case 'f' -> {
                float[] out = new float[arrayLength];
                for (int i = 0; i < arrayLength; i++) out[i] = arrayBuf.getFloat();
                yield out;
            }
            case 'd' -> {
                double[] out = new double[arrayLength];
                for (int i = 0; i < arrayLength; i++) out[i] = arrayBuf.getDouble();
                yield out;
            }
            case 'l' -> {
                long[] out = new long[arrayLength];
                for (int i = 0; i < arrayLength; i++) out[i] = arrayBuf.getLong();
                yield out;
            }
            case 'i' -> {
                int[] out = new int[arrayLength];
                for (int i = 0; i < arrayLength; i++) out[i] = arrayBuf.getInt();
                yield out;
            }
            case 'b' -> {
                boolean[] out = new boolean[arrayLength];
                for (int i = 0; i < arrayLength; i++) out[i] = arrayBuf.get() != 0;
                yield out;
            }
            default -> throw new ParseException("Unknown array element type: " + elementType);
        };
    }

    private byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 3);
        byte[] chunk = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0 && inflater.needsInput()) break;
                out.write(chunk, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new ParseException("Failed to inflate zlib-compressed FBX array data: " + e.getMessage());
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
