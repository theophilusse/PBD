package pbd.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Walks {@link GlContextPlan} with a {@link Driver} until the machine gives a window, and says what happened when it never does. No
 * GLFW here either: the driver is the only thing that touches it, so every path of this class - a machine that works at once, one
 * that needs the other platform, one that refuses everything - is run by a test with a driver that follows a script.
 *
 * <p>What a machine that already worked sees: the first request is the one the viewer always made, it is answered, nothing is printed.
 * Only after a refusal does anything change - the next request is tried, and one line says which one finally worked (so that the
 * person knows why the program picked X11 on a Wayland desktop, say).
 */
public final class GlContextNegotiator {
    private GlContextNegotiator() {}

    /** The few things the negotiation needs from GLFW, in the order it needs them. Implemented by {@link LwjglGlfwDriver} (and by a script in the tests). */
    public interface Driver {
        /** Starts GLFW on the platform (DEFAULT = GLFW chooses). False when it cannot; the reasons are then in {@link #takeErrors}. */
        boolean init(GlContextPlan.Platform platform);

        /** The platform GLFW is running on now (DEFAULT when it is none of those the plan knows, or unknown). Only after a successful {@link #init}. */
        GlContextPlan.Platform platformInUse();

        /** GLFW's version string, which also lists the platforms and context APIs it was built with ("3.4.0 Wayland X11 GLX Null EGL OSMesa monotonic"). */
        String versionString();

        /** Forgets every window hint and sets those of the attempt. */
        void apply(GlContextPlan.Attempt attempt);

        /** The window - visible, with the hints just applied - or 0 when the machine refuses. */
        long createWindow(int width, int height, String title);

        /** Stops GLFW (nothing happens when it is not running). */
        void terminate();

        /** The errors GLFW reported since the last call, oldest first, each as "NAME: description"; the list is emptied. */
        List<String> takeErrors();

        /** A hidden window with no requirement at all: what OpenGL this machine gives and on what. GLFW is running when this is called. */
        GlContextPlan.Probe probe();
    }

    /** The window that was made, and the attempt that made it - which the next window of the same program asks first. */
    public record Opened(long handle, GlContextPlan.Attempt attempt, boolean firstRequest) {}

    /** One thing that was tried, and what GLFW answered; {@code window} is false for "GLFW could not be started on this platform" (no window was asked for). */
    public record Step(String label, List<String> errors, boolean window) {}

    /**
     * Opens the window. {@code preferred} is the attempt that made the previous window of this program (null for the first): it is asked
     * first, so that switching from one scene to the next does not repeat the refusals of the first one. {@code log} gets the lines that
     * are not errors (a request that was changed, a setting that could not be read).
     *
     * @throws IllegalStateException with the whole story when nothing worked (see {@link #failureMessage})
     */
    public static Opened open(Driver driver, int width, int height, String title, Map<String, String> env, Properties props,
                              GlContextPlan.Attempt preferred, Consumer<String> log) {
        GlContextPlan.Request request = GlContextPlan.requestFrom(env, props);
        for (String problem : request.problems()) log.accept("[GL] " + problem);
        boolean linux = GlContextPlan.isLinuxLike(props == null ? null : props.getProperty("os.name"));

        List<GlContextPlan.Platform> queue = new ArrayList<>();
        queue.add(preferred != null ? preferred.platform() : request.platform());
        // the platform that worked before is asked first; if it no longer does, the person's choice (or GLFW's own) comes next
        if (preferred != null && !queue.contains(request.platform())) queue.add(request.platform());

        List<Step> tried = new ArrayList<>();
        String glfwVersion = null;
        boolean alternatesAdded = false;
        for (int i = 0; i < queue.size(); i++) {
            GlContextPlan.Platform platform = queue.get(i);
            boolean started = driver.init(platform);
            List<String> startErrors = driver.takeErrors();
            if (!started) {
                tried.add(new Step("start GLFW on " + platform.label(), startErrors, false));
            } else {
                GlContextPlan.Platform inUse = driver.platformInUse();
                String version = driver.versionString();
                if (glfwVersion == null) glfwVersion = version;
                List<GlContextPlan.Attempt> attempts = GlContextPlan.variants(platform, GlContextPlan.apisFor(linux, inUse, request.api()));
                if (preferred != null && i == 0) attempts = preferredFirst(attempts, preferred);
                for (GlContextPlan.Attempt attempt : attempts) {
                    driver.apply(attempt);
                    long handle = driver.createWindow(width, height, title);
                    List<String> errors = driver.takeErrors();
                    if (handle != 0L) {
                        GlContextPlan.Attempt used = new GlContextPlan.Attempt(inUse != GlContextPlan.Platform.DEFAULT ? inUse : platform,
                            attempt.api(), attempt.profile(), attempt.forwardCompat());
                        boolean first = tried.isEmpty();
                        if (!first) log.accept(changedRequestNote(tried.get(0), used));
                        return new Opened(handle, used, first);
                    }
                    tried.add(new Step(attempt.describe() + (platform == GlContextPlan.Platform.DEFAULT && inUse != GlContextPlan.Platform.DEFAULT
                        ? " (GLFW chose " + inUse.label() + ")" : ""), errors, true));
                }
                driver.terminate();
                driver.takeErrors();
                if (!alternatesAdded && i == queue.size() - 1) {
                    alternatesAdded = true;
                    for (GlContextPlan.Platform other : GlContextPlan.alternatePlatforms(linux, version, inUse, request.platform())) {
                        if (!queue.contains(other)) queue.add(other);
                    }
                }
            }
            // (GLFW that could not start on a platform has nothing to stop; the platforms left in the queue are still tried)
        }

        // Nothing worked. Ask the machine what it does give (a hidden window with no requirement), for the message, and let go of GLFW.
        GlContextPlan.Probe probe;
        boolean up = driver.init(GlContextPlan.Platform.DEFAULT);
        if (up) {
            if (glfwVersion == null) glfwVersion = driver.versionString();
            probe = driver.probe();
        } else {
            probe = new GlContextPlan.Probe(null, null, "GLFW could not be started: " + String.join("; ", driver.takeErrors()));
        }
        driver.takeErrors();
        driver.terminate();
        throw new IllegalStateException(failureMessage(tried, glfwVersion, env, props, probe));
    }

    /** The attempts with the one that worked last (same API, profile and flag; the platform is the group's) first. */
    public static List<GlContextPlan.Attempt> preferredFirst(List<GlContextPlan.Attempt> attempts, GlContextPlan.Attempt preferred) {
        List<GlContextPlan.Attempt> out = new ArrayList<>(attempts.size());
        for (GlContextPlan.Attempt a : attempts) {
            if (a.api() == preferred.api() && a.profile() == preferred.profile() && a.forwardCompat() == preferred.forwardCompat()) out.add(a);
        }
        for (GlContextPlan.Attempt a : attempts) if (!out.contains(a)) out.add(a);
        return out;
    }

    /** The line printed when the window was made by a request other than the first: which one was refused, and which one is used. */
    static String changedRequestNote(Step firstRefusal, GlContextPlan.Attempt used) {
        String why = firstRefusal.errors().isEmpty() ? "no reason given" : firstRefusal.errors().get(0);
        return "[GL] The request '" + firstRefusal.label() + "' was refused (" + why + "); the window is made with '" + used.describe()
            + "' instead. (PBD_GL_PLATFORM / PBD_GL_API choose the platform and the API yourself; see docs/ENGINE_DEV_GUIDE.md)";
    }

    /**
     * The message of the exception when no request worked: the refusals one by one (a refusal that repeats the one before is said to),
     * what a window with no requirement gets, the facts about the session, and what to do. It begins as the message always did, so
     * that a program reading the output for it (gl_runtime_check.py does) still finds it.
     */
    public static String failureMessage(List<Step> tried, String glfwVersion, Map<String, String> env, Properties props, GlContextPlan.Probe probe) {
        StringBuilder sb = new StringBuilder();
        boolean asked = false;
        for (Step step : tried) asked |= step.window();
        if (asked) {
            sb.append("Could not create the GLFW window (check that the requested context - OpenGL ")
              .append(GlContextPlan.MAJOR).append('.').append(GlContextPlan.MINOR).append(" core - is available on this machine)\n");
        } else {
            sb.append("Could not initialize GLFW (no windowing system could be reached, so no window was even asked for)\n");
        }
        if (!tried.isEmpty()) {
            sb.append(asked ? "Every request the viewer can make was refused:\n" : "What GLFW said:\n");
            for (int i = 0; i < tried.size(); i++) {
                Step step = tried.get(i);
                sb.append("  ").append(i + 1).append(". ").append(step.label()).append('\n');
                int same = -1;
                for (int j = 0; j < i && same < 0; j++) if (tried.get(j).errors().equals(step.errors())) same = j;
                if (same >= 0) {
                    sb.append("       same answer as ").append(same + 1).append(".\n");
                } else if (step.errors().isEmpty()) {
                    sb.append("       (GLFW reported nothing)\n");
                } else {
                    for (String error : step.errors()) sb.append("       ").append(error).append('\n');
                }
            }
        }
        sb.append("What a window with no requirement gets on this machine: ").append(probe.describe()).append('\n');
        sb.append("This machine:\n");
        for (String fact : GlContextPlan.facts(env, props, glfwVersion)) sb.append("  ").append(fact).append('\n');
        sb.append("What to do:\n");
        for (String line : GlContextPlan.advice(props == null ? null : props.getProperty("os.name"), env, glfwVersion, probe)) {
            sb.append("  - ").append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
