package pbd.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the window asks of the machine, in which order, and what it tells the person when the machine says no. There is no GLFW and no
 * OpenGL in this class (so a test can run it anywhere, on a machine that has neither): {@link GlContextNegotiator} walks the plan with
 * a driver, {@link LwjglGlfwDriver} is the driver that talks to LWJGL.
 *
 * <p><b>Why a plan.</b> The viewer used to ask for exactly one thing - an OpenGL 4.3 core, forward-compatible context, on whatever
 * platform GLFW picks - and, when the machine refused, stopped with "check that the requested context is available on this machine".
 * That is the right answer on a machine whose driver stops at OpenGL 4.2, but not on the others, and the message did not say which
 * one it was. The usual ways the same machine says no to that one request and yes to a neighbouring one:
 * <ul>
 *   <li><b>the platform</b>: since GLFW 3.4 (LWJGL 3.3.3 and later) a Linux desktop that offers both Wayland and X11 is opened on
 *       Wayland, whose contexts are always EGL contexts; the same session through XWayland is an X11/GLX window, which goes through
 *       another part of the driver ("EGL: Failed to create context: Arguments are inconsistent" is EGL_BAD_MATCH: the Wayland/EGL
 *       path was asked for something it cannot give);</li>
 *   <li><b>the flags</b>: the forward-compatible flag and the core profile are separate requests, and some drivers refuse the
 *       combination (or one of its two halves) and accept the others - the engine's shaders are #version 430 core and run in any
 *       of the three contexts below;</li>
 *   <li><b>the context API</b>: on X11, GLX or EGL.</li>
 * </ul>
 * Everything else (a driver that really stops below 4.3) fails every attempt, and then the message lists what each attempt was
 * answered, what the machine is, what a window with no requirement at all gets from it, and what to do about it.
 *
 * <p>The plan is only ever <i>longer</i> than before; the first attempt of a machine that worked is the request it always made, and
 * nothing is printed then.
 */
public final class GlContextPlan {
    private GlContextPlan() {}

    /** The OpenGL version the engine's shaders are written for (#version 430: shader storage buffers, explicit locations, tessellation). */
    public static final int MAJOR = 4, MINOR = 3;

    /** The windowing platform GLFW opens its window on (GLFW 3.4 and later): DEFAULT leaves the choice to GLFW. */
    public enum Platform {
        DEFAULT, X11, WAYLAND;

        /** "x11", "wayland", "auto"/"default"/empty (any case); null for anything else. */
        public static Platform parse(String text) {
            if (text == null) return DEFAULT;
            switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "": case "auto": case "default": case "any": return DEFAULT;
                case "x11": case "xorg": return X11;
                case "wayland": return WAYLAND;
                default: return null;
            }
        }

        public String label() { return this == DEFAULT ? "the platform GLFW picks" : name().toLowerCase(Locale.ROOT); }
    }

    /** The API the context is made with: NATIVE (WGL, GLX, Cocoa) or EGL. */
    public enum Api {
        NATIVE, EGL;

        /** "native" or "egl" (any case); null for "auto", for nothing, and for any other word (the plan then chooses the APIs itself). */
        public static Api parse(String text) {
            if (text == null) return null;
            switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "native": return NATIVE;
                case "egl": return EGL;
                default: return null;
            }
        }
    }

    public enum Profile { CORE, COMPAT }

    /** One request to the machine: which platform and API make the context, and which kind of OpenGL 4.3 context it is. */
    public record Attempt(Platform platform, Api api, Profile profile, boolean forwardCompat) {
        /** The words that name this attempt in a message: "x11, GLX/native API, 4.3 core, forward-compatible". */
        public String describe() {
            return (platform == Platform.DEFAULT ? "default platform" : platform.label()) + ", "
                + (api == Api.EGL ? "EGL" : "native API (WGL/GLX/Cocoa)") + ", "
                + MAJOR + "." + MINOR + (profile == Profile.CORE ? " core" : " compatibility")
                + (forwardCompat ? ", forward-compatible" : "");
        }
    }

    /** What the person asked for with PBD_GL_PLATFORM / PBD_GL_API (or -Dpbd.gl.platform / -Dpbd.gl.api), and what could not be read. */
    public record Request(Platform platform, Api api, List<String> problems) {}

    /** The settings the person may give: environment variable first (it reaches the program through Gradle's run task), then system property. */
    public static Request requestFrom(Map<String, String> env, Properties props) {
        List<String> problems = new ArrayList<>();
        String platformText = firstNonNull(env == null ? null : env.get("PBD_GL_PLATFORM"), props == null ? null : props.getProperty("pbd.gl.platform"));
        Platform platform = Platform.parse(platformText);
        if (platform == null) {
            problems.add("PBD_GL_PLATFORM = '" + platformText + "' is not x11, wayland or auto: ignored");
            platform = Platform.DEFAULT;
        }
        String apiText = firstNonNull(env == null ? null : env.get("PBD_GL_API"), props == null ? null : props.getProperty("pbd.gl.api"));
        Api api = Api.parse(apiText);
        if (apiText != null && api == null && !isAuto(apiText)) {
            problems.add("PBD_GL_API = '" + apiText + "' is not native, egl or auto: ignored");
        }
        return new Request(platform, api, List.copyOf(problems));
    }

    private static boolean isAuto(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.equals("auto") || t.equals("default");
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /**
     * The contexts to ask for on one platform, most likely first: the request the engine always made (4.3 core, forward-compatible),
     * the same without the forward-compatible flag, and a compatibility context (which the engine's #version 430 core shaders also
     * compile in). Each of them for every API in {@code apis}.
     */
    public static List<Attempt> variants(Platform platform, List<Api> apis) {
        List<Attempt> out = new ArrayList<>();
        for (Api api : apis) {
            out.add(new Attempt(platform, api, Profile.CORE, true));
            out.add(new Attempt(platform, api, Profile.CORE, false));
            out.add(new Attempt(platform, api, Profile.COMPAT, false));
        }
        return out;
    }

    /**
     * The APIs worth asking on a platform: the one the person forced, else the platform's own, then EGL where there is a choice (an
     * X11 window may be made with GLX or with EGL; a Wayland window can only be made with EGL, so asking again would be the same
     * request).
     */
    public static List<Api> apisFor(boolean linuxLike, Platform inUse, Api forced) {
        if (forced != null) return List.of(forced);
        if (linuxLike && inUse != Platform.WAYLAND) return List.of(Api.NATIVE, Api.EGL);
        return List.of(Api.NATIVE);
    }

    /**
     * The platforms to try after the first one has refused every request: on Linux, X11 when the window was opened on Wayland and
     * this GLFW has X11 as well ("3.4.0 Wayland X11 GLX Null EGL OSMesa monotonic" - GLFW 3.3 and earlier list no Wayland and have no
     * choice to make). Nothing when the person chose a platform, and nothing on the other systems.
     */
    public static List<Platform> alternatePlatforms(boolean linuxLike, String glfwVersion, Platform inUse, Platform forced) {
        if (forced != Platform.DEFAULT || !linuxLike) return List.of();
        if (inUse == Platform.WAYLAND && glfwVersion != null && glfwVersion.contains("X11")) return List.of(Platform.X11);
        return List.of();
    }

    public static boolean isLinuxLike(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("linux") || os.contains("bsd") || os.contains("sunos");
    }

    // ---- what GLFW says --------------------------------------------------------------------------------------------------------

    /** GLFW's name for an error code (GLFW_VERSION_UNAVAILABLE is 0x10007); the number in hex when it is none the engine knows. */
    public static String errorName(int code) {
        switch (code) {
            case 0x00010001: return "NOT_INITIALIZED";
            case 0x00010002: return "NO_CURRENT_CONTEXT";
            case 0x00010003: return "INVALID_ENUM";
            case 0x00010004: return "INVALID_VALUE";
            case 0x00010005: return "OUT_OF_MEMORY";
            case 0x00010006: return "API_UNAVAILABLE";
            case 0x00010007: return "VERSION_UNAVAILABLE";
            case 0x00010008: return "PLATFORM_ERROR";
            case 0x00010009: return "FORMAT_UNAVAILABLE";
            case 0x0001000A: return "NO_WINDOW_CONTEXT";
            case 0x0001000B: return "CURSOR_UNAVAILABLE";
            case 0x0001000C: return "FEATURE_UNAVAILABLE";
            case 0x0001000D: return "FEATURE_UNIMPLEMENTED";
            case 0x0001000E: return "PLATFORM_UNAVAILABLE";
            default: return "0x" + Integer.toHexString(code).toUpperCase(Locale.ROOT);
        }
    }

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    /** {major, minor} of the first "N.M" in a GL_VERSION string ("4.2 (Core Profile) Mesa 24.0.9", "OpenGL ES 3.2 Mesa..."); null when there is none. */
    public static int[] parseVersion(String glVersion) {
        if (glVersion == null) return null;
        Matcher m = VERSION.matcher(glVersion);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- what the person is told -----------------------------------------------------------------------------------------------

    /**
     * What a window with no requirement at all got from the machine (a hidden one, made after every request failed): the OpenGL it
     * offers and the renderer behind it - or, when even that failed, GLFW's reasons.
     */
    public record Probe(String version, String renderer, String error) {
        public boolean opened() { return version != null; }

        public String describe() {
            if (opened()) return "OpenGL " + version + (renderer == null || renderer.isBlank() ? "" : " on " + renderer);
            return "no window at all could be made (" + (error == null || error.isBlank() ? "no reason given" : error) + ")";
        }
    }

    /** The facts about the session that decide which of the remedies applies, one per line: only what is set. */
    public static List<String> facts(Map<String, String> env, Properties props, String glfwVersion) {
        List<String> lines = new ArrayList<>();
        String os = props == null ? null : props.getProperty("os.name");
        String arch = props == null ? null : props.getProperty("os.arch");
        String osVersion = props == null ? null : props.getProperty("os.version");
        lines.add("system: " + (os == null ? "?" : os) + (osVersion == null ? "" : " " + osVersion) + (arch == null ? "" : " (" + arch + ")")
            + ", Java " + (props == null || props.getProperty("java.version") == null ? "?" : props.getProperty("java.version")));
        lines.add("GLFW: " + (glfwVersion == null ? "could not be started" : glfwVersion));
        if (os != null && !isLinuxLike(os)) return lines; // the display variables below are a Linux desktop's: Windows and macOS have none to tell
        Map<String, String> set = new LinkedHashMap<>();
        for (String key : new String[]{"XDG_SESSION_TYPE", "WAYLAND_DISPLAY", "DISPLAY", "WSL_DISTRO_NAME", "LIBGL_ALWAYS_SOFTWARE",
            "GALLIUM_DRIVER", "MESA_GL_VERSION_OVERRIDE", "MESA_GLSL_VERSION_OVERRIDE", "__GLX_VENDOR_LIBRARY_NAME", "PBD_GL_PLATFORM", "PBD_GL_API"}) {
            String value = env == null ? null : env.get(key);
            if (value != null && !value.isEmpty()) set.put(key, value);
        }
        if (set.isEmpty()) {
            lines.add("session: none of XDG_SESSION_TYPE, WAYLAND_DISPLAY, DISPLAY, LIBGL_ALWAYS_SOFTWARE, MESA_GL_VERSION_OVERRIDE is set");
        } else {
            StringBuilder sb = new StringBuilder("session:");
            for (Map.Entry<String, String> e : set.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            lines.add(sb.toString());
        }
        return lines;
    }

    /**
     * What to do about it, from what is known: a few lines, the most likely cure first, and only the ones that can apply to this system
     * (a Windows machine is not told about Mesa's variables; a driver that offers 4.2 is told so).
     */
    public static List<String> advice(String osName, Map<String, String> env, String glfwVersion, Probe probe) {
        List<String> lines = new ArrayList<>();
        int[] offered = probe != null && probe.opened() ? parseVersion(probe.version()) : null;
        boolean es = probe != null && probe.opened() && probe.version().toUpperCase(Locale.ROOT).contains("OPENGL ES");
        if (es) {
            lines.add("The window got an OpenGL ES context, not desktop OpenGL: the driver on this path does not provide desktop OpenGL.");
        } else if (offered != null) {
            boolean enough = offered[0] > MAJOR || (offered[0] == MAJOR && offered[1] >= MINOR);
            if (enough) {
                lines.add("A window with no requirement gets OpenGL " + offered[0] + "." + offered[1] + ", which is enough: it is the 4.3 core request itself that was refused. "
                    + "Report this message - the attempts above say which request failed and how.");
            } else {
                lines.add("This machine's driver offers OpenGL " + offered[0] + "." + offered[1] + " on this path; the engine's shaders (#version 430: shader storage buffers, "
                    + "tessellation) need " + MAJOR + "." + MINOR + ".");
            }
        } else {
            lines.add("The engine needs an OpenGL " + MAJOR + "." + MINOR + " core context (#version 430 shaders: shader storage buffers, tessellation).");
        }
        boolean linux = isLinuxLike(osName);
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        boolean wsl = env != null && (env.get("WSL_DISTRO_NAME") != null || env.get("WSL_INTEROP") != null);
        if (linux) {
            if (wsl) {
                lines.add("This is WSL: its graphics go through Mesa's Direct3D 12 layer, which often stops at OpenGL 4.2 or below. Mesa's software renderer gives 4.5: "
                    + "LIBGL_ALWAYS_SOFTWARE=1 ./gradlew run - or run the viewer on Windows itself.");
            }
            lines.add("Linux, software renderer (slow but complete, OpenGL 4.5 with Mesa's llvmpipe):   LIBGL_ALWAYS_SOFTWARE=1 ./gradlew run");
            lines.add("Linux, a driver that has the features but reports less (some virtual machines, Intel/AMD on old Mesa):   "
                + "MESA_GL_VERSION_OVERRIDE=4.3 MESA_GLSL_VERSION_OVERRIDE=430 ./gradlew run");
            boolean bothPlatforms = glfwVersion != null && glfwVersion.contains("Wayland") && glfwVersion.contains("X11");
            if (bothPlatforms) {
                lines.add("Linux, Wayland against X11 (XWayland): the program already tried the other one when it could; to choose one yourself:   "
                    + "PBD_GL_PLATFORM=x11 ./gradlew run   or   PBD_GL_PLATFORM=wayland ./gradlew run");
            }
            lines.add("Linux, then: install or update the GPU driver (Mesa 22 or later, or the vendor's), and check with   glxinfo -B   (mesa-utils)   which OpenGL it offers.");
        } else if (os.contains("windows")) {
            lines.add("Windows: update the graphics driver from the GPU maker (NVIDIA, AMD, Intel) - the one Windows installs by itself, and the remote-desktop and "
                + "virtual-machine screens, offer OpenGL 1.1 only. Laptops with two GPUs: set javaw.exe / java.exe to the high-performance one in Windows' Graphics settings.");
        } else if (os.contains("mac")) {
            lines.add("macOS stops at OpenGL 4.1, so the engine cannot run natively there: use a virtual machine or a Linux/Windows computer (see the README).");
        }
        return lines;
    }
}
