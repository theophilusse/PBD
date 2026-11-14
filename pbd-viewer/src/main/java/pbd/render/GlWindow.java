package pbd.render;

import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Window + OpenGL context via GLFW. Targets GLSL 430 (SSBO + explicit
 * locations) - covers Windows/Linux and the macOS VM path identified
 * earlier, but NOT native macOS OpenGL (capped at 4.1, see README).
 *
 * On macOS, regardless of context version, GLFW/Cocoa requires the caller
 * to run on the JVM's first thread: launch with -XstartOnFirstThread
 * (already wired into build.gradle.kts for the `run` task). Without that
 * flag, glfwInit() or glfwCreateWindow() crash with an explicit error
 * message about it - this is not a silent failure.
 */
public final class GlWindow implements AutoCloseable {

    private final long handle;

    /**
     * The request that made the previous window of this program (a scene switch opens a new window each time): asked first, so that a
     * machine that needed its second or third request does not repeat the refusals of the first at every N / B press.
     */
    private static volatile GlContextPlan.Attempt lastRequest;

    /**
     * Opens the window with the context the engine's shaders need: OpenGL 4.3 core, forward-compatible - the request this class has
     * always made, and still the first one. When the machine refuses it, the neighbouring requests are tried (the other platform on a
     * Linux desktop that has two, no forward-compatible flag, a compatibility context, the other context API), and when none is
     * accepted the exception says what each one was answered, what the machine is and what to do - see {@link GlContextPlan} and
     * {@link GlContextNegotiator}. PBD_GL_PLATFORM (x11, wayland) and PBD_GL_API (native, egl) choose for oneself.
     */
    public GlWindow(int width, int height, String title) {
        LwjglGlfwDriver driver = new LwjglGlfwDriver();
        GlContextNegotiator.Opened opened;
        try {
            opened = GlContextNegotiator.open(driver, width, height, title, System.getenv(), System.getProperties(), lastRequest, System.out::println);
        } finally {
            driver.printErrorsAgain(); // GLFW's errors are printed on System.err from here on, as they always were
        }
        lastRequest = opened.attempt();
        handle = opened.handle();

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glfwShowWindow(handle);
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public long handle() {
        return handle;
    }

    @Override
    public void close() {
        glfwDestroyWindow(handle);
        glfwTerminate();
        var cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }
}
