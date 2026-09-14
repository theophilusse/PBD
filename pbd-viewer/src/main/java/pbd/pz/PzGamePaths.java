package pbd.pz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the actual Project Zomboid install directory, for the future
 * .fbx/texture loaders and the PZ live-visualizer (see this project's
 * roadmap - both still to come). Read from a .env file in the project
 * root (PZ_GAME_PATH=...), falling back to the PZ_GAME_PATH environment
 * variable, since the real install path is a per-machine detail that
 * has no business being hardcoded or checked into version control.
 *
 * No external dependency for this (no dotenv library) - the format
 * needed is trivial (one KEY=VALUE per line, # comments, blank lines
 * ignored) and not worth pulling in a whole library for.
 *
 * .env example:
 * <pre>
 * PZ_GAME_PATH=C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid
 * </pre>
 */
public final class PzGamePaths {
    private PzGamePaths() {}

    private static final String ENV_VAR_NAME = "PZ_GAME_PATH";
    private static final Path DOTENV_FILE = Path.of(".env");

    /** Null if not configured anywhere - callers should give a clear
     * error pointing at .env rather than a confusing FileNotFoundException
     * three calls later. */
    public static Path gameRoot() {
        String raw = readDotEnv().get(ENV_VAR_NAME);
        if (raw == null) raw = System.getenv(ENV_VAR_NAME);
        return raw != null ? Path.of(raw) : null;
    }

    public static Path modelsDir(String modelsSubfolder) {
        // Project Zomboid ships several media/models_X folders (base
        // game, DLC-ish content packs) rather than one - which ones
        // exist, and how they're numbered, is worth confirming once the
        // .fbx loader is actually being built rather than guessing here.
        Path root = requireGameRoot();
        return root.resolve("media").resolve(modelsSubfolder);
    }

    public static Path texturesDir() {
        Path root = requireGameRoot();
        return root.resolve("media").resolve("textures");
    }

    private static Path requireGameRoot() {
        Path root = gameRoot();
        if (root == null) {
            throw new IllegalStateException(
                "Project Zomboid install path not configured - set " + ENV_VAR_NAME
                + " in a .env file at the project root, or as an environment variable. Example:\n"
                + ENV_VAR_NAME + "=C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid");
        }
        return root;
    }

    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(DOTENV_FILE)) return values;
        try {
            for (String line : Files.readAllLines(DOTENV_FILE, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            System.err.println("[PzGamePaths] Failed to read .env: " + e.getMessage());
        }
        return values;
    }
}
