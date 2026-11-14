package pbd.render;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * {@link GlContextNegotiator.Driver} for LWJGL's GLFW: each method is one or two GLFW calls, so that everything that decides
 * something (which request comes next, what is said when none works) is in {@link GlContextPlan} and {@link GlContextNegotiator},
 * which a test runs without a window. GLFW's errors are not printed while the negotiation is on (the first request of a machine that
 * needs the second would print a stack trace for a thing that is about to work); they are kept, and either shown by the message
 * when nothing works, or summed up in one line when something does.
 */
final class LwjglGlfwDriver implements GlContextNegotiator.Driver {

    private final List<String> errors = new ArrayList<>();
    /** GLFW keeps its initialisation hints across glfwTerminate: once the platform has been set, DEFAULT must say "any" again. */
    private static boolean platformHintSet;

    LwjglGlfwDriver() {
        GLFWErrorCallback old = glfwSetErrorCallback(GLFWErrorCallback.create((code, description) ->
            errors.add(GlContextPlan.errorName(code) + ": " + GLFWErrorCallback.getDescription(description))));
        if (old != null) old.free();
    }

    /** From now on GLFW's errors are printed on System.err with their stack trace, as they always were. */
    void printErrorsAgain() {
        GLFWErrorCallback old = glfwSetErrorCallback(GLFWErrorCallback.createPrint(System.err));
        if (old != null) old.free();
    }

    @Override
    public boolean init(GlContextPlan.Platform platform) {
        if (platform != GlContextPlan.Platform.DEFAULT || platformHintSet) {
            int hint;
            switch (platform) {
                case X11: hint = GLFW_PLATFORM_X11; break;
                case WAYLAND: hint = GLFW_PLATFORM_WAYLAND; break;
                default: hint = GLFW_ANY_PLATFORM; break;
            }
            glfwInitHint(GLFW_PLATFORM, hint);
            platformHintSet = true;
        }
        return glfwInit();
    }

    @Override
    public GlContextPlan.Platform platformInUse() {
        int platform = glfwGetPlatform();
        if (platform == GLFW_PLATFORM_X11) return GlContextPlan.Platform.X11;
        if (platform == GLFW_PLATFORM_WAYLAND) return GlContextPlan.Platform.WAYLAND;
        return GlContextPlan.Platform.DEFAULT;
    }

    @Override
    public String versionString() {
        return glfwGetVersionString();
    }

    @Override
    public void apply(GlContextPlan.Attempt attempt) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, GlContextPlan.MAJOR);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, GlContextPlan.MINOR);
        glfwWindowHint(GLFW_OPENGL_PROFILE, attempt.profile() == GlContextPlan.Profile.CORE ? GLFW_OPENGL_CORE_PROFILE : GLFW_OPENGL_COMPAT_PROFILE);
        if (attempt.forwardCompat()) glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, attempt.api() == GlContextPlan.Api.EGL ? GLFW_EGL_CONTEXT_API : GLFW_NATIVE_CONTEXT_API);
    }

    @Override
    public long createWindow(int width, int height, String title) {
        return glfwCreateWindow(width, height, title, 0L, 0L);
    }

    @Override
    public void terminate() {
        glfwTerminate();
    }

    @Override
    public List<String> takeErrors() {
        List<String> taken = new ArrayList<>(errors);
        errors.clear();
        return taken;
    }

    @Override
    public GlContextPlan.Probe probe() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(64, 64, "PBD probe", 0L, 0L);
        if (window == 0L) {
            return new GlContextPlan.Probe(null, null, String.join("; ", takeErrors()));
        }
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            return new GlContextPlan.Probe(GL11.glGetString(GL11.GL_VERSION), GL11.glGetString(GL11.GL_RENDERER), null);
        } catch (RuntimeException e) {
            return new GlContextPlan.Probe(null, null, "the probe window opened but could not be read: " + e);
        } finally {
            glfwMakeContextCurrent(0L);
            glfwDestroyWindow(window);
        }
    }
}
