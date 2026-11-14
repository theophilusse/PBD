package pbd.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Fetches a PBD/PBDMAT/PBDBIN asset from a URL, with local caching and a
 * fallback URL for a 404. Deliberately minimal for security: exactly one
 * HTTP method (GET, nothing that writes or deletes anything remote), and
 * redirects are NOT followed automatically (HttpClient.Redirect.NEVER) -
 * a server-controlled redirect silently retargeting a fetch is exactly
 * the kind of thing a "keep it simple" security posture should avoid
 * doing on a caller's behalf. A 3xx response is treated as a fetch
 * failure (falling through to the fallback URL, then the cache, same as
 * any other error), not resolved transparently.
 *
 * <p>What an address may be is {@link FetchPolicy}'s to say (https only; not the person's own network; no user name in
 * it); how much a download may weigh is {@link FetchPolicy#MAX_FETCH_BYTES}. A file is written to the cache whole or not at all: a
 * download cut short is never mistaken, on the next run, for the file.
 *
 * <p>User-Agent identifies this project ({@link FetchPolicy#SITE_HOST}) so server-side
 * metrics can distinguish traffic - this string differs from the
 * Blender add-on's own Python fetcher on purpose (see that module).
 */
public final class PbdFetcher {

    public static final String USER_AGENT = "PrimitiveBasedDescription (" + FetchPolicy.SITE_HOST + ")";

    /**
     * How a fetch behaves. The defaults are the rules; a test (or a person running an asset server on their own machine) replaces
     * them with {@link #configure}. Nothing here can make the engine fetch an address {@link FetchPolicy#refuse} refuses - it only
     * lets the loopback address through over plain http, and replaces the question put to the person.
     */
    public static final class Settings {
        /** A plain-http address whose host is the loopback address is fetched (tests; a server on this machine). */
        public boolean allowLoopbackHttp = false;
        /** Asked, with the address, before anything is fetched from a host other than the project's own; true = go ahead. */
        public Predicate<String> approver = PbdFetcher::askOnConsole;
        /** The most one download may weigh. */
        public long maxBytes = FetchPolicy.MAX_FETCH_BYTES;
        /** Whether the addresses a host name resolves to are checked (a name that only points into the person's own network is refused). */
        public boolean checkResolvedAddresses = true;
        /** How a host name is resolved for that check: the addresses it stands for (4 or 16 bytes each). Replaced by tests. */
        public java.util.function.Function<String, java.util.List<byte[]>> resolver = PbdFetcher::resolveWithDns;
        public Duration requestTimeout = Duration.ofSeconds(30);
    }

    private static volatile Settings settings = new Settings();

    // Approved for this JVM run only, per distinct host - so a scene
    // with many references to the SAME third-party server only prompts
    // once, not once per file, without persisting a "trust this
    // forever" decision anywhere on disk.
    private static final java.util.Set<String> approvedThisSession = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public static final class FetchException extends IOException {
        public FetchException(String message) {
            super(message);
        }
    }

    /** Replaces the settings (and forgets the hosts approved so far). */
    public static void configure(Settings newSettings) {
        settings = Objects.requireNonNull(newSettings);
        approvedThisSession.clear();
    }

    /** The default rules again. */
    public static void reset() {
        configure(new Settings());
    }

    /** True if the person running this actually agreed to fetch from
     * url's host - always true for the project's own host, otherwise prints a
     * clear warning naming the exact host and reads a y/n answer from
     * stdin (this is a GLFW/OpenGL application with no dialog system of
     * its own, and asset resolution happens before any window exists
     * anyway - the terminal this was launched from is the only UI
     * available at this point). Denying, an unparseable answer, or no
     * console/stdin available at all (e.g. running fully detached) all
     * mean no - refusing by default rather than assuming consent is the
     * only safe default for a network fetch the file itself asked for,
     * not the person. {@code host} is the host of {@code url}, which {@link FetchPolicy#refuse} has already required to exist. */
    private static boolean isApprovedToFetch(String url, String host) {
        if (FetchPolicy.isTrustedHost(host)) return true;
        String key = host.toLowerCase(java.util.Locale.ROOT);
        if (approvedThisSession.contains(key)) return true;
        boolean approved = settings.approver.test(url);
        if (approved) approvedThisSession.add(key);
        return approved;
    }

    /** The question put to the person at the console (see isApprovedToFetch). */
    static boolean askOnConsole(String url) {
        String host = FetchPolicy.hostOf(url);
        System.out.println();
        System.out.println("[PbdFetcher] This scene references a resource on '" + host + "',");
        System.out.println("             which is NOT " + FetchPolicy.SITE_HOST + ".");
        System.out.println("             Full URL: " + url);
        System.out.print("             Download from this host? [y/N]: ");
        System.out.flush();
        String answer;
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            answer = reader.readLine();
        } catch (IOException e) {
            answer = null;
        }
        boolean approved = answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"));
        if (!approved) System.out.println("             Denied - this resource will not be downloaded.");
        return approved;
    }

    /**
     * Fetches url, returning the local cached file path. Uses the cache
     * directly (no network request at all) if a cached copy already
     * exists and useCache is true - this format has no ETag/Last-
     * Modified negotiation, so "cached" means "never re-checked," not
     * "confirmed still current"; pass useCache=false to force a fresh
     * fetch when that matters.
     *
     * On a 404 (or any fetch failure) from url, tries fallbackUrl if one
     * is given, before finally falling back to an existing cached copy
     * if there is one - so a temporarily-unreachable primary URL doesn't
     * throw away a perfectly usable previous download.
     */
    public static Path fetch(String url, Path cacheDir, String fallbackUrl, boolean useCache) throws IOException {
        Files.createDirectories(cacheDir);
        Path cachePath = cachePathFor(url, cacheDir);

        if (useCache && Files.exists(cachePath)) {
            return cachePath;
        }

        try {
            return doFetch(url, cachePath);
        } catch (FetchException primaryFailure) {
            if (fallbackUrl != null) {
                try {
                    System.err.println("[PbdFetcher] '" + abbreviate(url) + "' failed (" + primaryFailure.getMessage()
                        + ") - trying fallback: " + abbreviate(fallbackUrl));
                    return doFetch(fallbackUrl, cachePath);
                } catch (FetchException fallbackFailure) {
                    System.err.println("[PbdFetcher] Fallback also failed: " + fallbackFailure.getMessage());
                }
            }
            if (Files.exists(cachePath)) {
                System.err.println("[PbdFetcher] Using stale cached copy of '" + abbreviate(url) + "' after fetch failure");
                return cachePath;
            }
            throw primaryFailure;
        }
    }

    /**
     * Why the host name of {@code url} must not be fetched from, or null: the addresses it resolves to are looked at, and a name that
     * points into the person's own network (a name of a private address, DNS made to answer 127.0.0.1) is refused. Nothing is looked
     * up for the project's own host, or for a host written as an address (FetchPolicy has already judged those).
     */
    private static String refuseResolved(String host) {
        if (!settings.checkResolvedAddresses) return null;
        if (FetchPolicy.isTrustedHost(host) || FetchPolicy.literalAddress(host) != null) return null;
        for (byte[] address : settings.resolver.apply(host)) {
            if (!FetchPolicy.isPublicAddress(address)) {
                return "'" + host + "' resolves to " + describeAddress(address) + ", an address of a private or local network";
            }
        }
        return null; // nothing resolved (or only public addresses): the request itself fails with a clear message if the host does not exist
    }

    private static String describeAddress(byte[] address) {
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException e) {
            return "an address";
        }
    }

    /** The addresses the system's name resolution gives a host; none when it gives none. */
    static java.util.List<byte[]> resolveWithDns(String host) {
        java.util.List<byte[]> addresses = new java.util.ArrayList<>();
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) addresses.add(address.getAddress());
        } catch (UnknownHostException e) {
            // not resolvable
        }
        return addresses;
    }

    private static Path doFetch(String url, Path cachePath) throws FetchException {
        Settings s = settings;
        String refusal = FetchPolicy.refuse(url, s.allowLoopbackHttp);
        if (refusal != null) {
            throw new FetchException("Refused to fetch '" + abbreviate(url) + "': " + refusal);
        }
        String host = FetchPolicy.hostOf(url); // not null: the policy refuses an address that has no host
        if (!isApprovedToFetch(url, host)) {
            throw new FetchException("Fetch of '" + abbreviate(url) + "' was not approved (untrusted host, denied or no answer given)");
        }
        String resolvedRefusal = refuseResolved(host);
        if (resolvedRefusal != null) {
            throw new FetchException("Refused to fetch '" + abbreviate(url) + "': " + resolvedRefusal);
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", USER_AGENT)
                .timeout(s.requestTimeout)
                .build();
        } catch (IllegalArgumentException e) {
            throw new FetchException("Malformed URL '" + abbreviate(url) + "': " + why(e));
        }

        HttpResponse<InputStream> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FetchException("Network error fetching '" + abbreviate(url) + "': " + why(e));
        }

        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 404) {
                throw new FetchException("404 Not Found: " + abbreviate(url));
            }
            if (status / 100 == 3) {
                throw new FetchException("Redirect (" + status + ") not followed for '" + abbreviate(url)
                    + "' - PbdFetcher never follows redirects automatically");
            }
            if (status != 200) {
                throw new FetchException("HTTP " + status + " fetching '" + abbreviate(url) + "'");
            }
            long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (announced > s.maxBytes) {
                throw new FetchException("'" + abbreviate(url) + "' announces " + CappedRead.describe(announced)
                    + ", more than the " + CappedRead.describe(s.maxBytes) + " a download may weigh - refused");
            }
            byte[] data;
            try {
                data = CappedRead.readAll(body, s.maxBytes, "'" + abbreviate(url) + "'");
            } catch (IOException e) {
                throw new FetchException(why(e));
            }
            try {
                writeWhole(cachePath, data);
            } catch (IOException e) {
                throw new FetchException("Fetched '" + abbreviate(url) + "' but failed to write cache file: " + why(e));
            }
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            throw new FetchException("Network error reading '" + abbreviate(url) + "': " + why(e));
        }
        return cachePath;
    }

    /** The cache file appears whole or not at all: written beside it, then moved into place. */
    private static void writeWhole(Path target, byte[] data) throws IOException {
        Path partial = Files.createTempFile(target.toAbsolutePath().getParent(), "download-", ".part");
        try {
            Files.write(partial, data);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** What went wrong, in words: the exception's own message - or its name, for the many that have none (a refused connection says nothing). */
    private static String why(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** An address as it is put in a message: a hostile one can be long. */
    private static String abbreviate(String url) {
        return url.length() <= 200 ? url : url.substring(0, 200) + "...";
    }

    /** Cache filename is a hash of the URL, not the URL's own path
     * segment - avoids collisions between two different URLs that
     * happen to share a filename, and sidesteps needing to sanitize
     * arbitrary URL characters into a valid filename. */
    private static Path cachePathFor(String url, Path cacheDir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            String hashHex = Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 32);
            return cacheDir.resolve(hashHex + ".cache");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable - should never happen on a real JVM", e);
        }
    }
}
