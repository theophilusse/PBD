package pbd.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

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
 * User-Agent identifies this project (pbd.mazetrojan.fr) so server-side
 * metrics can distinguish traffic - this string differs from the
 * Blender add-on's own Python fetcher on purpose (see that module).
 */
public final class PbdFetcher {

    public static final String USER_AGENT = "PrimitiveBasedDescription (pbd.mazetrojan.fr)";

    // Hosts that don't need the Yes/No prompt below - the project's own
    // asset database. Anything else triggers it: a scene composed from
    // pbd_ref/include_material entries can point at ANY server a file's
    // author chose to write in, and nothing stops a hostile or just
    // careless file from naming somewhere that serves malware under a
    // filename this project would happily cache and load - this is a
    // consent gate on the network request that names WHERE the file is
    // actually about to go fetch from, in front of the person actually
    // running it, not a content scan (which this project makes no
    // attempt to do at all).
    private static final java.util.Set<String> TRUSTED_HOSTS = java.util.Set.of("pbd.mazetrojan.fr");
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

    /** True if the person running this actually agreed to fetch from
     * url's host - always true for TRUSTED_HOSTS, otherwise prints a
     * clear warning naming the exact host and reads a y/n answer from
     * stdin (this is a GLFW/OpenGL application with no dialog system of
     * its own, and asset resolution happens before any window exists
     * anyway - the terminal this was launched from is the only UI
     * available at this point). Denying, an unparseable answer, or no
     * console/stdin available at all (e.g. running fully detached) all
     * mean no - refusing by default rather than assuming consent is the
     * only safe default for a network fetch the file itself asked for,
     * not the person. */
    private static boolean isApprovedToFetch(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false; // can't even parse a host out of it - definitely not proceeding
        }
        if (host == null || TRUSTED_HOSTS.contains(host)) return true;
        if (approvedThisSession.contains(host)) return true;

        System.out.println();
        System.out.println("[PbdFetcher] This scene references a resource on '" + host + "',");
        System.out.println("             which is NOT " + String.join(", ", TRUSTED_HOSTS) + ".");
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
        if (approved) {
            approvedThisSession.add(host);
        } else {
            System.out.println("             Denied - this resource will not be downloaded.");
        }
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
                    System.err.println("[PbdFetcher] '" + url + "' failed (" + primaryFailure.getMessage()
                        + ") - trying fallback: " + fallbackUrl);
                    return doFetch(fallbackUrl, cachePath);
                } catch (FetchException fallbackFailure) {
                    System.err.println("[PbdFetcher] Fallback also failed: " + fallbackFailure.getMessage());
                }
            }
            if (Files.exists(cachePath)) {
                System.err.println("[PbdFetcher] Using stale cached copy of '" + url + "' after fetch failure");
                return cachePath;
            }
            throw primaryFailure;
        }
    }

    private static Path doFetch(String url, Path cachePath) throws FetchException {
        if (!isApprovedToFetch(url)) {
            throw new FetchException("Fetch of '" + url + "' was not approved (untrusted host, denied or no answer given)");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .build();
        } catch (IllegalArgumentException e) {
            throw new FetchException("Malformed URL '" + url + "': " + e.getMessage());
        }

        HttpResponse<byte[]> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new FetchException("Network error fetching '" + url + "': " + e.getMessage());
        }

        if (response.statusCode() == 404) {
            throw new FetchException("404 Not Found: " + url);
        }
        if (response.statusCode() / 100 == 3) {
            throw new FetchException("Redirect (" + response.statusCode() + ") not followed for '" + url
                + "' - PbdFetcher never follows redirects automatically");
        }
        if (response.statusCode() != 200) {
            throw new FetchException("HTTP " + response.statusCode() + " fetching '" + url + "'");
        }

        try {
            Files.write(cachePath, response.body());
        } catch (IOException e) {
            throw new FetchException("Fetched '" + url + "' but failed to write cache file: " + e.getMessage());
        }
        return cachePath;
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
