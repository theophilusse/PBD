package pbd.render;

import org.lwjgl.glfw.GLFWErrorCallback;
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

    public GlWindow(int width, int height, String title) {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Could not initialize GLFW");
        }

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        handle = glfwCreateWindow(width, height, title, 0L, 0L);
        if (handle == 0L) {
            throw new IllegalStateException(
                "Could not create the GLFW window (check that the requested "
                + "context - OpenGL 4.3 core - is available on this machine)");
        }

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
