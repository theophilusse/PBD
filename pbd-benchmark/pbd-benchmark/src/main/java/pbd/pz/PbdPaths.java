package pbd.pz;

import java.nio.file.Path;

/**
 * Every resource path this project touches, gathered in one place -
 * changing the directory layout for production (moving off
 * src/main/resources/ entirely, say, once this runs against the real
 * game rather than a dev checkout) should mean editing THIS file, not
 * hunting through PbdParser, the converters, and Main for scattered
 * Path.of(...) calls.
 *
 * Current layout (as of this file's writing):
 * <pre>
 * src/main/resources/
 * ├── fbx                      - Project Zomboid's native 3D models (future .fbx loader)
 * ├── materials                - shared, project-wide .pbdmat library (ambientcg-derived, hand-made)
 * ├── pz                       - tileGeometry.txt and other raw PZ data files
 * ├── scenes                   - .pbd scene files
 * │   └── handmade
 * │       ├── composition_example
 * │       └── zomboid          - tileGeometry-converted output lands here by default
 * ├── shaders
 * └── textures                 - material texture files (diffuse/normal/roughness/displacement)
 * </pre>
 *
 * NOT included here: the actual Project Zomboid game install (models,
 * textures under the game's own media/ folder) - see PzGamePaths, which
 * reads that from environment/.env instead, since it's a per-machine
 * external path, not something this project's own layout controls.
 */
public final class PbdPaths {
    private PbdPaths() {}

    public static final Path RESOURCES_ROOT = Path.of("src/main/resources");

    public static final Path FBX_DIR = RESOURCES_ROOT.resolve("fbx");
    public static final Path MATERIALS_DIR = RESOURCES_ROOT.resolve("materials");
    public static final Path PZ_DATA_DIR = RESOURCES_ROOT.resolve("pz");
    public static final Path SCENES_DIR = RESOURCES_ROOT.resolve("scenes");
    public static final Path SCENES_HANDMADE_DIR = SCENES_DIR.resolve("handmade");
    public static final Path SCENES_ZOMBOID_DIR = SCENES_HANDMADE_DIR.resolve("zomboid");
    public static final Path SHADERS_DIR = RESOURCES_ROOT.resolve("shaders");
    public static final Path TEXTURES_DIR = RESOURCES_ROOT.resolve("textures");

    /** Default input for the tileGeometry converter when no path is given on the command line. */
    public static final Path DEFAULT_TILE_GEOMETRY_INPUT = PZ_DATA_DIR.resolve("tileGeometry.txt");

    /** Default output directory for tileGeometry-converted .pbd files. */
    public static final Path DEFAULT_TILE_GEOMETRY_OUTPUT = SCENES_ZOMBOID_DIR;
}
