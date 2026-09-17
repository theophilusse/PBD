package pbd.format;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * PBDBIN: a "compiled" .pbd file - gzip-compressed minified text, not a
 * hand-rolled binary schema. This is a deliberate design choice, not a
 * shortcut: a real from-scratch binary format (explicit byte layout per
 * field, versioned struct-by-struct) would need its own encoder AND
 * decoder for every field this format has - name/kind/authors/origin,
 * every instance field, every modifier's arbitrary params, every
 * keyframe, mesh vertex data - each a fresh chance to get the byte
 * layout wrong in a way a compile-time check can't catch. Compressing
 * the ALREADY-TESTED minified-text serializer instead reuses every bit
 * of that fidelity for free: PbdSerializer's round-trip correctness
 * (verified against pretty/minified text) applies here unchanged, and
 * .pbdbin's compression ratio is genuinely good (repetitive keyword-
 * heavy text compresses well) - this reaches the actual goals of a
 * "compiled" format (small file, fast to load, not meant for hand-
 * editing) without the risk surface of inventing a parallel format.
 *
 * If a true fixed-layout binary format (for streaming partial reads, or
 * for a non-Java consumer that can't easily gunzip+reparse text) is
 * ever needed, this file is the place to add it - PbdBinFormat's own
 * public read/write methods are the seam a second implementation would
 * slot into without callers needing to change.
 */
public final class PbdBinFormat {

    private static final byte[] MAGIC = {'P', 'B', 'D', 'B'};

    public static void write(PbdScene scene, Path outputPath) throws IOException {
        String minified = new PbdSerializer(false).serialize(scene);
        byte[] textBytes = minified.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(textBytes);
        }

        try (var out = Files.newOutputStream(outputPath)) {
            out.write(MAGIC);
            out.write(1); // format version - bump if the header itself ever needs to change shape
            out.write(buffer.toByteArray());
        }
    }

    public static PbdScene read(Path inputPath, PrimitiveRegistry primitiveRegistry, ModifierRegistry modifierRegistry) throws IOException {
        String text = decompressToText(inputPath);
        // baseDir = the .pbdbin's OWN directory, not null - a relative
        // include_material now round-trips through PbdSerializer (see
        // its own comment), so it needs somewhere real to resolve
        // against, exactly like a plain .pbd loaded via parseFile
        // would use ITS OWN directory. Keep the referenced .pbdmat
        // alongside the .pbdbin (same relative layout the original .pbd
        // had it in) for materials to actually load - .pbdbin was never
        // meant to be self-contained regarding materials the way
        // .pbdasset is (see that format's own doc comment); it's a
        // compressed TEXT alternative to .pbd, with the same external-
        // reference behavior .pbd itself has, not a bundle.
        return new PbdParser(primitiveRegistry, modifierRegistry).parse(text, inputPath.toAbsolutePath().getParent());
    }

    /** Just the magic-header check + gzip decompression, no parsing -
     * split out from read() above so PbdParser.parsePbdRef can resolve
     * a source= pointing at a .pbdbin the exact same way it already
     * resolves a plain .pbd (both end up as a text string fed through
     * the same parseInternal), rather than pbd_ref being limited to
     * plain-.pbd sources only while every OTHER entry point
     * (PbdEngine.loadAny) already understands all three formats. read()
     * itself is unchanged in behavior - it's this same decompression,
     * just now followed by the parse step separately instead of
     * inline. */
    public static String decompressToText(Path inputPath) throws IOException {
        byte[] all = Files.readAllBytes(inputPath);
        if (all.length < 5 || all[0] != MAGIC[0] || all[1] != MAGIC[1] || all[2] != MAGIC[2] || all[3] != MAGIC[3]) {
            throw new IOException("Not a .pbdbin file (missing PBDB magic header): " + inputPath);
        }
        int version = all[4] & 0xFF;
        if (version != 1) {
            throw new IOException(".pbdbin version " + version + " is not supported (this reader only knows version 1)");
        }

        byte[] gzipped = java.util.Arrays.copyOfRange(all, 5, all.length);
        ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipped))) {
            gzip.transferTo(decompressed);
        }
        return decompressed.toString(StandardCharsets.UTF_8);
    }
}
