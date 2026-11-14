package pbd.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * What a scene may ask the network (or the disk) for, decided without touching either - so that every rule can be tested line by line, and
 * so that the engine, whatever fetches, follows the same rules.
 *
 * <p>A scene file is data from anywhere. It names other files ({@code pbd_ref source}, {@code include_material}, a material's textures), and
 * the person who opened it did not write those names. This class is the list of what such a name may be:
 * <ul>
 *   <li>an <b>https</b> address, with no user name in it, a sane port and a host that is not the person's own network (a private, loopback
 *       or link-local address - the router's admin page, a database on the same machine, a cloud metadata service);</li>
 *   <li>a <b>file path</b> that is not a network path ({@code \\host\share} makes Windows hand the person's credentials to that host), not
 *       a {@code file:} / {@code jar:} / {@code ftp:} address and not an alternate data stream.</li>
 * </ul>
 * The one host the engine fetches from without asking is {@link #SITE_HOST}; every other host is a question put to the person
 * ({@link PbdFetcher}).
 */
public final class FetchPolicy {

    private FetchPolicy() {}

    /** This project's own asset host - the one place a reference may point without asking the person. */
    public static final String SITE_HOST = "pbd.aptheo.fr";

    /** The longest address, or reference, that is looked at: a longer one is not a file name. */
    public static final int MAX_REFERENCE_LENGTH = 2048;

    /** The most one fetched file may weigh (a texture, a scene, a compiled scene, a bundled asset): the download stops there. */
    public static final long MAX_FETCH_BYTES = 64L * 1024 * 1024;

    /** The most distinct addresses one load of a scene may fetch: a scene naming thousands of files is an attack on the person's time, not a model. */
    public static final int MAX_FETCHES_PER_LOAD = 256;

    // ---- the host ----------------------------------------------------------------------------------------------------------------

    /** True for {@link #SITE_HOST} (upper or lower case); not for a sub-domain of it, not for the same name with a dot added. */
    public static boolean isTrustedHost(String host) {
        return host != null && host.equalsIgnoreCase(SITE_HOST);
    }

    /** The host of an address, or null when it has none or cannot be read. */
    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Why {@code url} must not be fetched, or null when it may be. It looks at the address only (no network).
     *
     * @param allowLoopbackHttp tests, and a person running their own asset server on this machine: a plain {@code http} address whose host
     *                          is the loopback address is accepted (nothing else ever is)
     */
    public static String refuse(String url, boolean allowLoopbackHttp) {
        if (url == null || url.isEmpty()) return "the address is empty";
        if (url.length() > MAX_REFERENCE_LENGTH) return "the address is longer than " + MAX_REFERENCE_LENGTH + " characters";
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c >= 0x7f) return "the address contains a space, a control character or a non-ASCII character";
            if (c == '\\') return "the address contains a backslash";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "the address is not well formed (" + e.getReason() + ")";
        }
        String scheme = uri.getScheme();
        if (scheme == null) return "the address has no scheme";
        scheme = scheme.toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null) return "the address has no host";
        if (uri.getRawUserInfo() != null) return "the address carries a user name (user@host): it is how an address is made to look like another";
        int port = uri.getPort();
        if (port == 0 || port > 65535) return "the port is out of range";

        // "localhost." is "localhost" (a name written with its root dot is the same name to a resolver): the dot never changes what a
        // host is, so it never changes the verdict. java.net.URI allows a single one.
        String bare = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        boolean loopback = isLoopbackHost(bare);
        if (scheme.equals("http")) {
            if (!(allowLoopbackHttp && loopback)) return "only https addresses are fetched";
            return null;
        }
        if (!scheme.equals("https")) return "only https addresses are fetched (not " + scheme + ":)";

        if (isTrustedHost(host)) {
            if (port != -1 && port != 443) return "the project's own host is only reached on the standard port";
            return null;
        }
        // any other host: it can still be refused outright when it is only a way into the person's own network
        boolean bracketed = bare.startsWith("[");
        byte[] literal = literalAddress(bare);
        if (literal != null) {
            if (!isPublicAddress(literal)) return "the host is an address of a private or local network";
            // A public address written in a form that tools read differently: "0127.0.0.1" is 87.0.0.1 to inet_aton (octal) and 127.0.0.1 to
            // a reader of decimal numbers, "16909060" and "0x1020304" are 1.2.3.4 only to some. Nothing legitimate is written so.
            if (!bracketed && parseDottedDecimal(bare) == null) return "the host is an address written in an unusual form (only a.b.c.d is fetched)";
            return null;
        }
        if (bracketed) return "the host is a malformed IPv6 address";
        // "08.1.1.1", "0000000000127.0.0.1": the last part is a number, so no resolver takes this for a name - and no two of them agree on which address it is
        if (endsWithNumber(bare)) return "the host looks like an IP address but is not a valid one";
        if (isLocalName(bare)) return "the host is a name of the person's own machine or network";
        return null;
    }

    /** True when the last part of the host (after its last dot) is only digits, or {@code 0x} and hexadecimal digits: how every resolver tells an address from a name. */
    static boolean endsWithNumber(String host) {
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (last.isEmpty()) return false;
        boolean digits = true;
        for (int i = 0; i < last.length(); i++) if (last.charAt(i) < '0' || last.charAt(i) > '9') { digits = false; break; }
        if (digits) return true;
        if (last.length() < 2 || last.charAt(0) != '0' || (last.charAt(1) != 'x' && last.charAt(1) != 'X')) return false;
        for (int i = 2; i < last.length(); i++) {
            char c = last.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * The four bytes of {@code a.b.c.d} written the one way nobody reads two ways - four decimal numbers of one to three digits, from 0 to 255,
     * without a zero in front (a zero in front is octal to some tools and decimal to others) - or null for anything else.
     */
    static byte[] parseDottedDecimal(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) return null;
            int v = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') return null;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    /** localhost, and the names that only mean something inside one network (.local, .internal, .localhost, .lan, .home.arpa). */
    private static boolean isLocalName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
            || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.indexOf('.') < 0; // a single label ("localhost", "router") is never a public name
    }

    private static boolean isLoopbackHost(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        byte[] literal = literalAddress(host);
        if (literal == null) return false;
        if (literal.length == 4) return (literal[0] & 0xff) == 127;
        for (int i = 0; i < 15; i++) if (literal[i] != 0) return false;
        return literal[15] == 1;
    }

    // ---- addresses ---------------------------------------------------------------------------------------------------------------

    /**
     * The bytes of a host that is written as an IP address, in any of the forms an operating system reads (dotted quad, one decimal
     * number, octal or hexadecimal parts, an IPv6 literal in brackets); null when it is a name. Nothing is looked up.
     */
    public static byte[] literalAddress(String host) {
        if (host == null) return null;
        if (host.startsWith("[") && host.endsWith("]")) return parseIPv6(host.substring(1, host.length() - 1));
        if (host.indexOf(':') >= 0) return parseIPv6(host);
        return parseLooseIPv4(host);
    }

    /** inet_aton's reading: one to four parts, each decimal, 0-octal or 0x-hexadecimal; the last part fills the remaining bytes. Null for a name. */
    static byte[] parseLooseIPv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return null;
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) return null;
            int radix = 10;
            String digits = p;
            if (p.length() > 1 && (p.startsWith("0x") || p.startsWith("0X"))) {
                radix = 16;
                digits = p.substring(2);
                if (digits.isEmpty()) return null;
            } else if (p.length() > 1 && p.startsWith("0")) {
                radix = 8;
                digits = p.substring(1);
            }
            for (int k = 0; k < digits.length(); k++) {
                if (Character.digit(digits.charAt(k), radix) < 0) return null; // a letter or an out-of-radix digit: this is a name
            }
            if (digits.length() > 11) return null;
            values[i] = Long.parseLong(digits, radix);
        }
        long total;
        int last = parts.length - 1;
        long limitLast = 1L << (8 * (4 - last));
        for (int i = 0; i < last; i++) if (values[i] > 255) return null;
        if (values[last] >= limitLast) return null;
        total = values[last];
        for (int i = 0; i < last; i++) total |= values[i] << (8 * (3 - i));
        return new byte[]{(byte) (total >> 24), (byte) (total >> 16), (byte) (total >> 8), (byte) total};
    }

    /** An IPv6 literal (without brackets, may end in a dotted IPv4) as 16 bytes; null when it is not one. */
    static byte[] parseIPv6(String text) {
        int zone = text.indexOf('%');
        if (zone >= 0) text = text.substring(0, zone);
        // a trailing dotted quad stands for the last two groups
        int lastColon = text.lastIndexOf(':');
        String tail = text.substring(lastColon + 1);
        if (tail.indexOf('.') >= 0) {
            byte[] v4 = parseDottedDecimal(tail); // inside an IPv6 address the four bytes are decimal numbers, never octal or hexadecimal
            if (v4 == null) return null;
            text = text.substring(0, lastColon + 1)
                + Integer.toHexString(((v4[0] & 0xff) << 8) | (v4[1] & 0xff)) + ":"
                + Integer.toHexString(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
        }
        int dbl = text.indexOf("::"); // a second "::" leaves an empty group in the part after the first, which splitGroups refuses
        String[] head = dbl >= 0 ? splitGroups(text.substring(0, dbl)) : splitGroups(text);
        String[] rest = dbl >= 0 ? splitGroups(text.substring(dbl + 2)) : new String[0];
        if (head == null || rest == null) return null;
        int groups = head.length + rest.length;
        if (dbl < 0 ? groups != 8 : groups > 7) return null;
        int[] words = new int[8];
        for (int i = 0; i < head.length; i++) words[i] = hexWord(head[i]);
        for (int i = 0; i < rest.length; i++) words[8 - rest.length + i] = hexWord(rest[i]);
        for (int w : words) if (w < 0) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (words[i] >> 8);
            out[2 * i + 1] = (byte) words[i];
        }
        return out;
    }

    private static String[] splitGroups(String s) {
        if (s.isEmpty()) return new String[0];
        String[] g = s.split(":", -1);
        for (String x : g) if (x.isEmpty()) return null;
        return g;
    }

    private static int hexWord(String g) {
        if (g.length() > 4) return -1;
        int v = 0;
        for (int i = 0; i < g.length(); i++) {
            int d = Character.digit(g.charAt(i), 16);
            if (d < 0) return -1;
            v = v * 16 + d;
        }
        return v;
    }

    /**
     * True when {@code address} (4 bytes, IPv4, or 16 bytes, IPv6) belongs to the public internet; false for everything that is only
     * reachable from inside a network: this machine, a private range, link-local (169.254.x.x, which holds cloud metadata services), shared
     * address space, documentation, multicast, reserved ranges - and an IPv6 address that wraps one of those.
     */
    public static boolean isPublicAddress(byte[] address) {
        if (address == null) return false;
        if (address.length == 4) return isPublicV4(address);
        if (address.length != 16) return false;
        boolean zeroHead = true;
        for (int i = 0; i < 10; i++) if (address[i] != 0) { zeroHead = false; break; }
        if (zeroHead && (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff) {
            return isPublicV4(java.util.Arrays.copyOfRange(address, 12, 16));      // ::ffff:a.b.c.d is the IPv4 address a.b.c.d
        }
        int b0 = address[0] & 0xff, b1 = address[1] & 0xff, b2 = address[2] & 0xff, b3 = address[3] & 0xff;
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b) {                 // 64:ff9b::/96 (NAT64) carries an IPv4 address
            boolean zeros = true;
            for (int i = 4; i < 12; i++) if (address[i] != 0) { zeros = false; break; }
            return zeros && isPublicV4(java.util.Arrays.copyOfRange(address, 12, 16));
        }
        // Of the rest, only global unicast (2000::/3) is the public internet: ::/8 (this host, loopback), fc00::/7 (unique local),
        // fe80::/10 (link-local), fec0::/10, ff00::/8 (multicast) and every range nobody has been given are not.
        if ((b0 & 0xe0) != 0x20) return false;
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return false;     // 2001::/32 Teredo
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) return false;     // 2001:db8::/32 documentation
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x02 && address[4] == 0 && address[5] == 0) return false; // 2001:2::/48 benchmarking
        if (b0 == 0x3f && b1 == 0xff && (b2 & 0xf0) == 0) return false;             // 3fff::/20 documentation
        if (b0 == 0x20 && b1 == 0x02) return isPublicV4(java.util.Arrays.copyOfRange(address, 2, 6)); // 2002::/16 (6to4) carries an IPv4 address
        return true;
    }

    private static boolean isPublicV4(byte[] a) {
        int b0 = a[0] & 0xff, b1 = a[1] & 0xff, b2 = a[2] & 0xff;
        if (b0 == 0 || b0 == 10 || b0 == 127) return false;               // this network, private, loopback
        if (b0 >= 224) return false;                                       // multicast and everything reserved above it (240/4, 255.255.255.255)
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;            // 100.64/10 carrier-grade NAT
        if (b0 == 169 && b1 == 254) return false;                         // link-local, cloud metadata
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return false;             // private
        if (b0 == 192 && b1 == 168) return false;                         // private
        if (b0 == 192 && b1 == 0 && b2 == 0) return false;               // IETF protocol assignments
        if (b0 == 192 && b1 == 0 && b2 == 2) return false;               // documentation
        if (b0 == 192 && b1 == 88 && b2 == 99) return false;             // 6to4 relay anycast
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;           // benchmarking
        if (b0 == 198 && b1 == 51 && b2 == 100) return false;            // documentation
        if (b0 == 203 && b1 == 0 && b2 == 113) return false;             // documentation
        return true;
    }

    // ---- file references ---------------------------------------------------------------------------------------------------------

    /**
     * Why {@code reference} must not be read as a file on the disk, or null when it may be. A reference is the text after
     * {@code source =} or {@code include_material}: written by whoever wrote the scene. A relative path or an ordinary absolute one is the
     * person's own business; a network path, an address of another kind and an alternate data stream are not a file of theirs at all.
     */
    public static String refuseLocalReference(String reference) {
        if (reference == null || reference.isEmpty()) return "the reference is empty";
        if (reference.length() > MAX_REFERENCE_LENGTH) return "the reference is longer than " + MAX_REFERENCE_LENGTH + " characters";
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c < ' ' || c == 0x7f) return "the reference contains a control character";
        }
        char c0 = reference.charAt(0), c1 = reference.length() > 1 ? reference.charAt(1) : 0;
        if ((c0 == '\\' || c0 == '/') && (c1 == '\\' || c1 == '/')) return "the reference is a network path (\\\\host\\share or //host/share)";
        int start = (c1 == ':' && Character.isLetter(c0)) ? 2 : 0; // "C:" is a drive; any other ':' is not part of a file name
        int colon = reference.indexOf(':', start);
        if (colon >= 0) {
            if (reference.startsWith("//", colon + 1)) return "the reference is an address (" + reference.substring(0, colon + 1) + "), not a file";
            return "the reference contains a ':' (an address of another kind, or an alternate data stream)";
        }
        return null;
    }
}
