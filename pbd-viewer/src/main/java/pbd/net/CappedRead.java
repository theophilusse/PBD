package pbd.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reading a stream whose length nobody promised: a download, a decompressor. The memory a read may take is decided before it starts, not
 * discovered when it runs out - a server that never stops sending, or a small compressed file that expands to gigabytes, ends in a clear
 * message instead of the viewer going down.
 */
public final class CappedRead {

    private CappedRead() {}

    /**
     * Reads {@code in} to its end, but never more than {@code max} bytes: one byte over and it stops with an IOException that names
     * {@code what}.
     */
    public static byte[] readAll(InputStream in, long max, String what) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(max, 64 * 1024));
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > max) throw new IOException(what + " is larger than " + describe(max) + " - refused");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** "64 MB", "512 KB", "700 bytes", "1 byte". */
    public static String describe(long bytes) {
        if (bytes >= 1024L * 1024) return (bytes / (1024L * 1024)) + " MB";
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes == 1 ? "1 byte" : bytes + " bytes";
    }
}
