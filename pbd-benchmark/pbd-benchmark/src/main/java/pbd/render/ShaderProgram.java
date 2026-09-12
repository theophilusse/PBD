package pbd.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL43.*;

/**
 * Compiles and links a shader pipeline into one program - either the full
 * 4-stage PBD pipeline (vert/tesc/tese/frag) or a plain vertex+fragment
 * pipeline (used by the text overlay and the classic OBJ mesh path, both
 * of which need no tessellation). The same sequence (compile each stage,
 * attach, link, check GL_LINK_STATUS) was verified for real against a
 * live OpenGL context (Mesa/llvmpipe) via an equivalent small C program
 * before this class was written - see the README for details.
 */
public final class ShaderProgram implements AutoCloseable {

    private final int programId;

    /** Full pipeline with tessellation (vert/tesc/tese/frag) - used by the PBD renderer. */
    public ShaderProgram(Path vert, Path tesc, Path tese, Path frag) throws IOException {
        int vs = compile(GL_VERTEX_SHADER, vert);
        int tc = compile(GL_TESS_CONTROL_SHADER, tesc);
        int te = compile(GL_TESS_EVALUATION_SHADER, tese);
        int fs = compile(GL_FRAGMENT_SHADER, frag);
        programId = linkAndCleanup(vs, tc, te, fs);
    }

    /** Simple vertex+fragment pipeline (no tessellation) - used by the text overlay and the classic mesh path. */
    public ShaderProgram(Path vert, Path frag) throws IOException {
        int vs = compile(GL_VERTEX_SHADER, vert);
        int fs = compile(GL_FRAGMENT_SHADER, frag);
        programId = linkAndCleanup(vs, fs);
    }

    private static int linkAndCleanup(int... shaders) {
        int program = glCreateProgram();
        for (int shader : shaders) glAttachShader(program, shader);
        glLinkProgram(program);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            throw new IllegalStateException("Program link failed:\n" + log);
        }

        for (int shader : shaders) {
            glDetachShader(program, shader);
            glDeleteShader(shader);
        }
        return program;
    }

    private static int compile(int type, Path path) throws IOException {
        String source = Files.readString(path);
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            throw new IllegalStateException("Compilation failed (" + path + "):\n" + log);
        }
        return shader;
    }

    public void use() {
        glUseProgram(programId);
    }

    public int id() {
        return programId;
    }

    @Override
    public void close() {
        glDeleteProgram(programId);
    }
}
