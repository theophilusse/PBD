# PBD Engine — Developer / Architecture Guide

Java 21 + LWJGL 3.3.4 (OpenGL 4.3) + JOML. Gradle project.

Revised 2026-11-09. This guide is the map of the code and the rules that are easy to break. Its
neighbours: `docs/PBD_FORMAT_SPEC.md` (the format itself), `docs/ROADMAP.md` (what is done, pending and
decided, with the evidence), `docs/STATUS_SUMMARY.md` (the same, one pass), `docs/TEST_PROCEDURE.md` (how
to prove nothing regressed), `docs/ADDON_USER_GUIDE.md` (the Blender side).

## Running it

```
./gradlew run                                    # loads the default scene
./gradlew run --args="path/to/scene.pbd"         # loads a specific .pbd
./gradlew run --args="path/to/scene.pbdbin"      # loads a compiled .pbdbin (see docs/PBD_FORMAT_SPEC.md)
./gradlew run --args="path/to/scene.pbdasset"    # loads a bundled .pbdasset (extracted to a temp dir automatically)
./gradlew run --args="path/to/scene.pbdscene"    # plays a scene description file: many models placed by a few lines, and a show (see docs/PBD_FORMAT_SPEC.md)
./gradlew run --args="path/to/model.obj"         # classic OBJ viewer
./gradlew run --args="path/to/model.fbx"         # FBX viewer (binary FBX only, see pbd.fbx)
./gradlew convertTileGeometry -Pinput=... -Poutput=...   # tileGeometry.txt -> .pbd batch conversion
```

The format converters are one class, `pbd.PbdConvertMain` (for scripts and batch work: since add-on v0.48.0 the Blender add-on's
.pbdbin / .pbdasset buttons write both formats themselves and do not run it; only its tileGeometry button still runs Java, with the
classpath printed by the `printRuntimeClasspath` Gradle task):

```
PbdConvertMain bin   <input.pbd> <output.pbdbin>
PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]
PbdConvertMain lever <input.pbd|.pbdbin|folder> <output.pbd|.pbdbin|folder>     # keyframe doors -> lever arms
PbdConvertMain expand <scene.pbdscene> <output.pbd>     # the PBD text a scene description file expands to
PbdConvertMain check  <scene.pbdscene>                  # load it completely, say what it holds (instances, memory, the show) or what is wrong
```

`lever` is the migration the project owes before the keyframed doors go away
(`pbd.format.LeverArmMigration`, details under "Lever arm" below). A folder is walked recursively and the
results land at the same relative paths under the output folder; the original is never touched. `expand` and `check` belong to
the scene description files (below, "Scene description files and the show"): `expand` writes what the engine will parse, `check`
loads a scene the way the viewer does, without a window, and exits with status 1 and the reason when it cannot.

## The window does not open ("Could not create the GLFW window")

The viewer asks the machine for an OpenGL **4.3 core** context: its shaders are `#version 430` (shader storage buffers, tessellation). The
usual report is this one, from `./gradlew run` on a Linux desktop (or WSL):

```
[LWJGL] GLFW_VERSION_UNAVAILABLE error
Description : EGL: Failed to create context: Arguments are inconsistent
...
java.lang.IllegalStateException: Could not create the GLFW window (check that the requested context - OpenGL 4.3 core - is available on this machine)
```

`Arguments are inconsistent` is EGL's `EGL_BAD_MATCH`: the platform GLFW picked (since GLFW 3.4, LWJGL 3.3.3 and later, a Linux session that
has both Wayland and X11 is opened on **Wayland**, whose contexts are always EGL ones) cannot give what was asked, whereas the same machine
may well give it through X11/GLX, without the forward-compatible flag, or from a software renderer. The program used to make one request and stop;
now it walks a short plan and only gives up when the whole plan has failed (`pbd.render.GlContextPlan` decides what to ask and what to tell,
`GlContextNegotiator` walks the plan with a driver, and `LwjglGlfwDriver` is the only class that touches GLFW - the first two have no LWJGL in them,
so `RegressionGlContext` runs every path of them with a GLFW that follows a script):

1. the request it always made: 4.3 core, forward-compatible, on the platform GLFW picks;
2. the same without the forward-compatible flag, then a 4.3 **compatibility** context (the shaders compile in it too);
3. on Linux, each of those with **EGL** as well as the native API (GLX) - except on Wayland, where the native API already is EGL;
4. on Linux, when the window was opened on Wayland and this GLFW also has X11: GLFW is stopped and the same requests are made on **X11** (XWayland).

A machine that works sees no change: the first request is answered and nothing is printed. When a later request is the one that works, one
line says so (`[GL] The request '...' was refused (<GLFW's words>); the window is made with '...' instead.`), and the next window of the same run
(the N / B scene switches open a new one) asks for that request first instead of repeating the refusals.

When nothing works, the exception lists every request with GLFW's own answer (an answer that repeats is said to repeat), what a window with **no**
requirement gets from this machine (its OpenGL version and renderer, which tells a driver that stops at 4.2 from one that refuses the request), the
session variables that matter (`XDG_SESSION_TYPE`, `WAYLAND_DISPLAY`, `DISPLAY`, the Mesa overrides, ...), and the cures that apply to *this*
system. The first line is the old one, so a script that looks for it (`gl_runtime_check.py`) still finds it.

**Choosing for yourself.** `PBD_GL_PLATFORM=x11|wayland|auto` and `PBD_GL_API=native|egl|auto` (environment variables, which reach the program
through Gradle's `run` task; the system properties `pbd.gl.platform` and `pbd.gl.api` do the same for a plain `java` command line). A platform
you choose is the only one tried. A word that is none of the choices is said and ignored, never obeyed.

**The cures**, on Linux, the first that fits:

- the software renderer (slow but complete, OpenGL 4.5 with Mesa's llvmpipe): `LIBGL_ALWAYS_SOFTWARE=1 ./gradlew run`;
- a driver that has the features but reports less (virtual machines, WSL's Direct3D 12 layer, old Mesa):
  `MESA_GL_VERSION_OVERRIDE=4.3 MESA_GLSL_VERSION_OVERRIDE=430 ./gradlew run`;
- the other platform: `PBD_GL_PLATFORM=x11 ./gradlew run` (or `wayland`);
- the driver itself: Mesa 22 or later, or the vendor's; `glxinfo -B` (package `mesa-utils`) says which OpenGL it offers.

On Windows the cure is the GPU maker's driver (the one Windows installs by itself and the remote-desktop and virtual-machine screens offer
OpenGL 1.1 only; on a laptop with two GPUs, set `java.exe` / `javaw.exe` to the high-performance one in Windows' Graphics settings). macOS stops at
OpenGL 4.1 and cannot run the engine natively.

**What is proven here and what is not.** The plan, its order, the words, the settings, the walk through a machine that works at once / needs the
second request / needs the other platform / needs EGL / refuses everything / cannot start, a later window, and the lines that connect them to
LWJGL are checked by `RegressionGlContext` and its mutants. That *GLFW itself* answers the way the script does, and which of the attempts a given
GPU, driver and desktop accepts, could not be checked in the sandbox this was written in (it has no display and no GPU): a machine that refuses every
request, a Mesa that says yes to the compatibility context and then fails to compile a shader, are for a real window to show.

## Controls

| Input | What it does |
|---|---|
| WASD + mouse, Space / Shift | Move and look, up / down. Esc closes the window. |
| Left mouse | On a **lever arm** (or anything attached to one): grab it and drag while the button is held; on release the arm's `release=` policy decides (`snap` finishes the swing to the nearer end, `free` stays where it was let go). A locked arm prints `[Lever] '<id>' is locked`. On a **keyframed** door or lid: toggles it (the old behaviour). |
| E | Two jobs, and the **nearer** thing under the crosshair says which: take the item of an open container (plays `item-equip.mp3`), or switch the light(s) of the model whose part is within 3.5 m (a click: the light's `switchSound`, else `light-switch.wav`). A press with nothing to take or switch is silent and says why on the console. |
| H | Hide inside the container under the crosshair; press again from inside to come out. Refused, with a message, while the container's door is closed (you cannot get in or out through a shut door) and when the container is under 0.4 on an axis. |
| G | Shoot what is under the crosshair: carve a crater and throw it up as particles, let what it cut off - and any piece held too thinly - fall (plays `doomshotgun.wav` on a hit, then the material's `breakSound` when a piece broke away). |
| `-` / `=` | Detail (LOD) down / up. |
| C | Toggle the Transform-Feedback mesh cache. |
| Up / Down, Left / Right | Time of day, day of year (held). Hold T for a fast multi-day time-lapse. |
| N / B | Next / previous `.pbd` / `.pbdbin` / `.pbdasset` in the same folder. |
| F3 / F5 | Hide or show the statistics overlay. |
| W A S D, Space, Left Shift (during a show) | A scene description file with a show plays on its own (camera, keys, doors, sun). Any of these keys takes the camera back **and ends the show**. |

Sounds live in `src/main/resources/sounds/` (`SoundPlayer` is built on that folder). It plays WAV through
`javax.sound.sampled` and **MP3 through the project's own decoder**, `pbd.audio.Mp3Decoder` (see "The MP3
decoder" below), and logs a warning - it does not fail - when a file is missing or cannot be decoded. The three
fixed sounds are named in one place, `pbd.audio.GameSounds`: `item-equip.mp3` for **E** on an item (`GameSounds.forPickup`: nothing
when the press took nothing), `light-switch.wav` for **E** on a light (`GameSounds.forLightSwitch`: the click the light names with
`switchSound`, else this default; nothing when nothing was switched) and `doomshotgun.wav` for **G** (`GameSounds.forShot` says which
sounds one shot plays: nothing for a miss, the shot for a hit, then the material's `breakSound` when a piece broke away). Every keyframe
`sound=`, every lever arm `openSound` / `closeSound`, every light's `switchSound` and every material `breakSound` of the scene
(`PbdScene.soundFiles()`) is preloaded at scene load, so the first click or shot does not stutter. The default click is a file you may
replace: `tools/scripts/make_light_switch_wav.py` writes the synthesized one that ships (two short ticks and a thunk, 0.14 s); drop
any `light-switch.wav` of your own into the folder to use that instead.

## Package map

```
pbd.format   - the .pbd TEXT FORMAT and its siblings: PbdParser (read), PbdSerializer (write, pretty or
               minified), PbdScene / PbdInstance (+ Keyframe, LeverArm) / PbdModifier / PbdCurve /
               PbdMeshData (the data model), PrimitiveRegistry, ModifierRegistry, MaterialRegistry,
               PbdMatParser (.pbdmat), BreakProfile (how a material breaks: the `break*` / `debris*` keys of
               a .pbdmat block, with the defaults by material name), MaterialUv (how a material's pictures sit on a surface: `uvScale*`
               and `uvRotation`), TextureTurn + MaterialPictures (a picture turned by quarter turns, and which pictures a scene
               needs), PbdBinFormat (.pbdbin = "PBDB" + version byte + gzip of the
               minified text), PbdAssetFormat (.pbdasset = zip), LeverArmMigration (keyframe door ->
               lever arm), and the scene description files: SceneExpander + SceneLine (a `.pbdscene` expanded to the text of
               a `.pbd`), ShowParser + ShowScript (its `show` and `at` lines: a camera path, keys, doors, the sun).
               No LWJGL dependency - pure data, runnable without a GPU.
pbd          - PbdEngine: a friendly facade over pbd.format for code that just wants to load / inspect /
               edit / save scenes (SceneHandle). PbdConvertMain: the command-line converters above.
pbd.render   - the GPU side and what it is built on:
               PbdRenderer (upload + tessellation-shader draw; the in-game facade for doors, arms,
               destruction), PbdMeshCache (Transform Feedback bake-to-mesh cache), MaterialTextureArray,
               SkydomeRenderer, FlyCamera / Camera, GlWindow, ShaderProgram, TextRenderer, BitmapFont,
               TextureLoader, MaterialCatalog, PatchExpander / GpuPatch.
               The GL-free logic that PbdRenderer is only the adapter of - all of it runs under
               tools/regression without a window: HierarchyResolver (local -> world transforms, keyframe
               and arm poses), LeverArmSystem (lever-arm state, drag, lock, sounds), LightTracker (did a
               light move?), LightLinks (which light follows which door: the toggleable light),
               LightSwitch (E on a part of a model: which lights it switches, and the switch itself), UseKey (what one
               press of E does: the nearer of an item and a part wins, and which sounds go with it),
               ChannelTracker (named channels that accumulate over time).
               The show of a scene description file, also GL-free: CameraPath (the monotone cubic through the camera
               keys, the look direction), SceneDirector (the show's clock, its events, the sun, the camera it hands
               back), ShowTargets (where an instance the camera looks at is), FrameStats (the numbers of a stress
               test), StressReport (the one file a scene can write).
               The window's negotiation, also GL-free: GlContextPlan (which OpenGL context to ask for and in which order, what to
               tell the person when the machine refuses) and GlContextNegotiator (the walk through that plan); LwjglGlfwDriver
               is the one class that makes the GLFW calls (see "The window does not open").
pbd.voxel    - destruction (the G key): VoxelOctree, PrimitiveVoxelizer, VoxelMeshBuilder, VoxelSolid +
               LongIntMap (queries on octree-aligned cubes), VoxelCrater (the hole), VoxelConnectivity
               (face-connected pieces), VoxelFracture (a connection too thin to hold breaks), VoxelDebris
               (what stays, what falls), RigidDebris (a falling body), ParticleSystem + DebrisEmitter +
               ParticleDraw (the chips a hit throws up: physics, emission from the removed volume, the model
               matrix of the shared cube), SlabOpenings (how much of a shot door can still be seen through),
               DestructionWorld (the whole chain behind one `destroyAt`). No GL either.
pbd.classicmesh - a second, simpler render path for pre-triangulated meshes (ObjMesh: interleaved
               position + normal, plain index buffer) - the .obj viewer, the .fbx viewer, "mesh"
               instances and container-content items. No tessellation, no LOD.
pbd.fbx      - binary FBX (Kaydara format, version <7500) node-tree reader (FbxBinaryReader) and geometry
               extractor (FbxGeometryExtractor) producing an ObjMesh. See that package's own doc comments
               for exactly what is handled (triangles only, one Geometry per file, ByPolygonVertex /
               Direct normals) versus not yet (n-gons, multi-mesh files, the full FBX transform-pivot stack).
pbd.pz       - Project Zomboid interop: TileGeometryParser / Serializer / Converter (tileGeometry.txt <->
               .pbd, both directions), PbdPaths (every project-relative resource path in one place),
               PzGamePaths (the game install path, from .env), MaterialLookup (drop-item -> material),
               BinPacker (shelf packing, single or multi-container), ContainerContents (random FBX
               selection + packing + simplified falling physics, deterministically seeded),
               ContainerStock (which containers have their contents out, and what a shut one remembers:
               pure, so the frame loop in Main only feeds it the doors' state), HidingRules (whether the doors
               of a hiding place conceal and block, and what H does: pure, shared by the facade getter and
               the key handler).
pbd.net      - PbdFetcher: GET-only download of a .pbd / .pbdmat / .pbdbin with a local cache and a
               fallback URL; redirects are deliberately not followed.
pbd.audio    - SoundPlayer: javax.sound.sampled for WAV, Mp3Decoder (+ the generated Mp3Tables) for MP3,
               preloaded per scene; GameSounds: the fixed sound names the key handlers play (E on an item, E on a light, G) and
               which sounds one shot plays.
pbd.sky      - SolarCalculator (sun position from latitude / day / hour, for the skydome and the lighting).
pbd.app      - Main: the runnable entry point, wiring everything above into one interactive viewer, and
               the static modding facade (see "Runtime facade").
```

Two rules the package map is built on:

- **Logic that can be pure is pure, and the GL class is its thin adapter.** `PbdRenderer` owns buffers,
  shaders and draw calls; the rules (lever-arm state and drag, light tracking, the destruction chain) live in
  classes with no `org.lwjgl` import. That is the only reason they have regression suites at all - nothing
  here can open an OpenGL window. Only eleven files import LWJGL (Main, ClassicMeshRenderer, GlWindow,
  LwjglGlfwDriver, MaterialTextureArray, PbdMeshCache, PbdRenderer, ShaderProgram, SkydomeRenderer,
  TextRenderer, TextureLoader); a new rule that does not need GL should not become the twelfth. They are also the only files that can take memory from
  LWJGL's stack, and `tools/regression/check_gl_stack.py` fails the run when one takes it inside a loop (see "Lessons").
- **Nothing is cached across a scene change or a hot-loaded asset.** `PbdRenderer.upload` builds a fresh
  `LeverArmSystem`, a fresh destruction world, fresh light tracking; assets are loaded on the fly, so a
  cache keyed on "the scene" would be stale the moment one is replaced. If you need to cache, key it on the
  data you read, not on the scene. (Two documented exceptions: the sound clips of `SoundPlayer`, see "Sounds" below, and the cache of downloaded files in `pbd.net`.)

## The .pbd format itself

See `docs/PBD_FORMAT_SPEC.md` for the full syntax reference (its examples are parsed by
`RegressionSpecExamples`, so they cannot drift). In short: a text format, one block per instance
(`cube name { pos=... scale=... }`), free-form fields for anything not universal (metadata, loop,
containerTrigger, LOD, ...), optional `keyframe` blocks, an optional `leverArm { ... }` block, optional
modifier blocks (bend / shear / taper ...), `curve` declarations, `pbd_ref` placements of other files, and
scene-level metadata (`name`, `kind`, `author`, `origin`, `description`) at the top.

The same scene exists in four forms that must stay interchangeable: pretty `.pbd`, minified `.pbd`,
`.pbdbin` and `.pbdasset`. That is checked by code, not by hope (see "Testing"), because it has gone wrong
before (six data-loss bugs found by the first systematic round trip).

### Rules that are easy to break when you touch the parser or the serializer

- **Values.** `PbdParser.readRawValue` reads a quoted string, or a balanced `(...)` / `[...]` group, or a bare
  token that ends at whitespace or one of `{ } ( ) [ ] = ,`. Scene metadata values (`name`, `kind`, `author`,
  `origin`, `description`) read to the end of the line unless quoted - which is why the **minified** form
  always quotes them, on **both** producers (`PbdSerializer.quoteMetadataValue` and the add-on's
  `_quote_metadata_value`). An unquoted `kind = static prop` in a minified file used to swallow the rest of
  it.
- **Writing.** `PbdSerializer` writes through `valueToken` / `quoteToken` / `quoteIfNeeded`: a value is
  quoted exactly when a bare one would not read back. The format has **no escape for `"`**, so a value that
  contains one cannot be written (a known limit). Numbers are written with 6 significant digits. Re-serializing
  a file drops its comments and formatting - never rewrite a hand-authored file in place.
- **Base64 payloads.** `vertexData` / `vertexDataLod<N>` are read bare or quoted (`readEncodedValue` - the
  project's own `mushroom.pbd` has them bare and `=`-padded); the writers still quote.
- **Includes.** `PbdScene` keeps the list of every `include_material` line (`addIncludeMaterialPath`;
  `includeMaterialPath` is only the last one). `.pbdasset` bundles the materials and textures of all of them.
  A `pbd_ref`'d file is **not** bundled: flatten it or embed it first.
- **`pbd_ref`.** `PbdParser` inlines a referenced file as a zero-geometry `group` plus
  `PbdInstance.copyAs(prefix + id)` of each of its instances (ids prefixed `NAME.`); the referenced file's
  own `include_material` lines are carried over. `copyAs` must copy **every** field of `PbdInstance`,
  `LeverArm` and `Keyframe` - it once silently dropped keyframes, arms, lights and meshes.
- **Adding a field** to `PbdInstance`, `PbdScene`, `LeverArm`, `Keyframe`, `PbdModifier`, `PbdCurve` or
  `PbdMeshData`: parser, serializer, `copyAs`, the Blender export / import, the spec - and the field in the
  "kitchen sink" scene of `RegressionFormatRoundTrip`. Two reflection guards enforce this
  (`completenessGuard` in the round trip, `reflectionGuard` in `RegressionPbdRefCopy`): a field nobody sets
  or nobody copies fails the suite instead of hiding.

## Adding a new primitive type

1. Add tessellation math to `pbd.tese` (the GPU evaluates the actual surface there) and LOD / bend-detection
   logic to `pbd.tesc` if it needs special handling.
2. Register the type + its canonical dimensions in `PrimitiveRegistry`.
3. Add patch-count logic to `PatchExpander` if it is not the default 1-patch-per-instance case. **A type that
   has no case here makes `PatchExpander.partsFor` throw** - by design ("a wrong guess is worse than a loud
   failure") - and a scene containing it then crashes on load. Zero-geometry types (`group`, `mesh`,
   `light`) return zero patches.
4. Decide what the other systems do with it: `PrimitiveVoxelizer` (can G destroy it? - a type that cannot be
   voxelized must be skipped by `DestructionWorld.findFresh`, as `ref` / `group` / `light` are, and its
   box there is the canonical -0.5..0.5 one unless the type carries its own vertices, as `mesh` does),
   `LeverArmSystem.hasGeometry` (can the player grab through it?), `DestructionWorld`'s landing surfaces
   (only cubes and cylinders are).
5. Blender side: add it to `add_primitive.py`'s enum and creation logic, and to the UI panel if it needs
   type-specific fields. `RegressionLeverArmAttach` attaches every type to an arm in all four formats - add
   the new type to its list.

## Adding a new modifier

1. Register it in `ModifierRegistry` (name + expected params).
2. Add the GPU-side math wherever the modifier actually applies (typically `pbd.tese` for a per-vertex
   deformation).
3. `PbdModifier` already carries arbitrary key=value params generically - no parser change needed for a new
   modifier's own fields.
4. Blender preview: native modifier if one matches exactly (see bend's `SimpleDeform` in `properties.py`),
   otherwise direct mesh-data editing (see smooth / shear / taper in `mesh_preview.py`) - Blender's own
   modifiers rarely match this project's exact math, so do not assume one does without checking by hand
   against known values first.
5. Decide whether destruction must see it. The voxel conversion draws the shape with the same maths as the shader, so a
   modifier has to be in three places that must agree to the last digit: `ModifierParams` (its parameter names, defaults and
   spelling - the one reader the renderer, the mesh cache and the voxel conversion all call), `ShapeEval` (the Java port of
   `pbd.tese`, which `RegressionShaderPort` checks against numbers computed from the GLSL text) and the shader itself. Without
   the second one an object is shot as its straight, unmodified self. `taper`, `bend` and `shear` are ported; `twist` and `curve`
   are no-ops on the GPU today and therefore in `ShapeEval` too ("voxelized as drawn"). See "Voxelizing what is drawn".

## Lever arm

A lever arm is the alternative to a keyframed animation for anything that swings or slides - a door, a
drawer, a lid, a lever, a gate. It is defined by two poses and driven by a single number, the **arm value**:
0 = closed (the instance's own `pos` / `rot`), 1 = open (`leverArm.openPos` / `openRot`). The authoritative
statement of what it must do is the quoted original specification at the top of section 2 of
`docs/ROADMAP.md`, checked there line by line. The format - every field of the `leverArm` block
(`openPos openRot speed release locked lockAt openSound closeSound`), its defaults and the examples, which
the code checks (`RegressionSpecExamples`) - is in `docs/PBD_FORMAT_SPEC.md`, section "Lever-arm".

How the pieces fit, from data to screen:

1. **Data.** `PbdInstance.leverArm` (`LeverArm`: `openPos`, `openRotDeg`, `speed`, `release` SNAP | FREE,
   `locked`, `lockAt`, `openSound`, `closeSound`). It travels through every format and through `pbd_ref`.
2. **Pose.** `HierarchyResolver.localTransform(inst, animTime, armValue)` lerps position and Euler degrees
   (angle by angle, not a quaternion slerp) between the closed and the open pose, with the value clamped to
   0..1; `resolve(scene, animTimes[], armValues[])` then composes `world = parent x T·R·S` in one pass. A
   child inherits its parent's **scale** too, which is why an arm belongs on a scale-1 `group` pivot with
   the visible panel as a child (the add-on's "Pivot (hinge)"). Rotation order is Z, Y, X. **Keyframes win**
   over an arm on the same instance - the two are never blended, and the add-on warns about it.
3. **State and rules - `LeverArmSystem` (pure).** The live value of every arm and who drives it, one at a
   time: scripted easing at `speed` (`setTarget` then `update`), an external caller (`setValue`: a drag, a
   physics step later), or a lock (nothing moves it). Also: `ownerOf` (the arm that moves when a given
   instance is grabbed = its nearest ancestor-or-self with an arm, so a handle, a latch or the panel itself
   drags the door; group pivots and lights have no geometry of their own and are only grabbed through what
   hangs on them - a lamp still *follows* the door), `pick` (nearest hit among an arm and everything
   attached to it), `dragToward` (the arm
   value that brings the grabbed point nearest the aim ray: 40 coarse samples, then 18 golden-section
   refinements; it works for rotation, translation or both, with no hinge-axis assumption), `release` (a
   `snap` arm eases to whichever end is nearer, a `free` one stays), `setLocked`, `linkGroup` (arms sharing a
   `linkGroup` move together - a wardrobe's two doors), and the sounds: `openSound` when the value leaves the
   closed end (past 0.02), `closeSound` when it is back within 0.01 of it, whoever moved it; a looping arm
   (`loop=true`, decorative) is silent and cannot be grabbed. Thresholds: `CLOSED_AT_MOST` 0.01,
   `OPEN_AT_LEAST` 0.99.
4. **Adapter - `PbdRenderer`.** Builds a `LeverArmSystem` in `upload`, feeds `arms.values()` to
   `HierarchyResolver` every frame in `updateAnimation(dt)`, plays the sounds, and exposes the arm API
   below. `LightTracker` notices when a light that hangs on a moving door or lever has moved and rebuilds
   only the lights buffer (`uploadLights`).
5. **Input - `Main`.** Left mouse down: `findLeverArmGrabPoint` (the arm that would move + the grabbed point
   in the arm's local space, fixed for the whole drag); every frame while held: `dragArmValueTowardRay`;
   on release: `releaseArm`. The drag is **geometric** (the prototype chosen first): no inertia, no
   collisions. Real physics behind the same `setValue` seam is a later step (ROADMAP section 2).

Runtime API (`PbdRenderer`; the last three also as `Main` statics, see "Runtime facade"):
`setOpen(id, open)` (scripted open / close; works for keyframed doors and arms, spans `linkGroup`),
`setArmValue(id, v)`, `getArmValue(id)` (NaN when the instance has no arm - the roadmap's long-flagged
`getArmRotationValue`, renamed because an arm need not rotate), `releaseArm(id)`, `isArmLocked(id)`,
`setArmLocked(id, locked)`, `findLeverArmGrabPoint`, `dragArmValueTowardRay`.

**The facade reads the value, not a flag.** `isInstanceOpen` (arm value above 0.01), `isInstanceFullyOpen`
(at least 0.99) and `isInstanceFullyClosed` (at most 0.01) answer from the arm's live value, because a
hand-dragged door has no discrete "toggled" moment. That is what makes the container contents follow the
door: they appear as soon as a linked door is open by any visible amount and are cleared once every linked
door is closed again.

**Migration.** `PbdConvertMain lever` turns a plain two-pose keyframed swing into an arm with the same closed
pose, the same open pose and the same duration (`speed = 1 / duration`); the sounds move onto the arm
(`closeSound` = the last keyframe's sound, `openSound` = the first's, else the last's). It converts only what
it can convert exactly, and lists every file it refuses with the reason (it already has an arm; loops; a
channel; scales; a single keyframe; times not increasing; zero duration; identical first and last pose; a
sound on a middle keyframe; a middle keyframe off the straight line; for a whole file: output = input, a
`.pbdasset`, a file with `pbd_ref`). `RegressionLeverArmMigration` checks that for every arm value the
converted door, and everything hanging on it, is exactly where the old clip had it.

**Known limits** (ROADMAP section 2 has the full list): an arm's pivot must keep scale 1; a lever arm on a
`ref` or a `light` is not exported by the add-on (use a pivot); Euler interpolation means mid-pose equals the
old animation's only for single-axis rotations; the Blender add-on has no drag and no physics; `setParent`
(below) does not compensate the pose.

## Voxel destruction (`pbd.voxel`) - the G key

The whole behaviour of one G hit lives behind `PbdRenderer.destroyAt(origin, dir)`, which is only the GL
adapter around `DestructionWorld` (it supplies a `Host`: build / free a mesh, place an instance). `Main`
does nothing but call it and play the sound. **What one hit does**, in order:

1. Pick what the ray reaches first: a never-destroyed instance (found with `DestructionWorld.findFresh`,
   a bounding-box ray test - the canonical -0.5..0.5 box for a primitive, the real vertex bounds for a
   `mesh` - that skips `indestructible`, `metadata`, `ref`, `group`, `light` and what is already hidden;
   the renderer's `findDestructibleAlongRay` just delegates to it), then the remains of an
   already-destroyed one, then a piece that already broke off.
   The last two are tested against their real voxels, so a shot through a hole carries on to what is behind.
2. Voxelize a fresh target (`PrimitiveVoxelizer` -> `VoxelOctree`; the original is hidden with
   `PbdRenderer.hideInstance`, which zeroes its transform and is remembered separately so the next frame's
   hierarchy rebuild does not undo it). **An instance that cannot be turned into cubes is destroyed outright**
   (`DestructionWorld.destroyWhole`): a mesh with no readable triangle or no volume (the open triangle `let_triangle` of a
   report answered "can't be voxelized" and could not be shot), a shape thinner than a voxel, a hollow with every face removed.
   It stops being drawn like every destroyed instance, its face counts as entirely open (`SlabOpenings.gone`, so a door removed
   this way does not keep a hiding place shut) and a burst of particles of its own material comes out of where the shot landed, as
   many as its size asks for (`WHOLE_BURST_MIN_VOXELS` .. `WHOLE_BURST_MAX_VOXELS`: a speck throws the least a hit ever throws, a
   wall the most). Only `indestructible` is still refused.
3. Carve a **solid, irregular crater** (`VoxelCrater`): 28 lobes with their own reach, blended, digging
   deeper along the shot than sideways, with a jittered rim. Deterministic per impact. A radius of 30 voxels
   of 5 mm (`CRATER_RADIUS_VOXELS`, `VOXEL_WORLD_SIZE`) cuts a hole about 0.30 world units across. Cubes
   wholly inside are dropped whole, wholly outside kept whole, only the cubes on the rim are split - the
   octree is never expanded to unit voxels (that was the out-of-memory crash of the old crater).
4. **Break what is held too thinly** (`VoxelFracture.shatter`, the material's `breakWidth` over the voxel size
   = `DestructionWorld.neckVoxels`): if the crater left a piece joined to the rest only by a connection narrower
   than that, the connection shatters - the whole run of thin voxels is taken out - and the piece it held is a
   separate one (the next step makes it fall). A cell is thick when a cube of `neckVoxels` on a side fits in the
   solid and covers it (the morphological opening); thin cells that were already thin before the hit, or that
   touch only one thick piece (the ragged rim of the crater), are left alone, and a thin run that joins two or
   more thick pieces is taken out whole. Only the window around the crater is analysed (`n - 1` voxels beyond
   its box, flat arrays and prefix sums, never the octree expanded), and a window over `MAX_WINDOW_CELLS`
   (8 000 000) is skipped - the hit stays as the crater made it and the log says so.
5. Split what is left into face-connected pieces (`VoxelConnectivity`, working on octree-aligned cubes
   through `VoxelSolid`, never per voxel) and decide what stays (`VoxelDebris`): an object standing on the
   floor keeps every piece that still touches its lowest layer; anything else (a door, a shelf) keeps its
   largest piece. The rest become rigid bodies (`RigidDebris`: gravity, floor and static colliders,
   friction, a little bounce, sleep), at most 6 per hit and at least 400 voxels each, with at most 24 live
   pieces in the scene. **Smaller crumbs are no longer dropped: they leave the object as particles.**
6. **Throw the particles** (`DestructionWorld.throwParticles` -> `DebrisEmitter.burst`, twice): the crater's
   burst, sampled inside the removed cubes plus the crumbs, and - when a connection broke - the break's burst,
   sampled inside the splinters. The count, size, speed, life, gravity, bounce and tint are the **material's**
   (`BreakProfile.of(name, PbdScene.materialOverrides.get(name))`, resolved by `PbdRenderer.breakProfileOf`,
   which prints the profile's warnings once); a particle without a tint takes the colour of the voxel face it
   came from (the same `colorFn` as the mesh, so a textured wall sheds chips of its texture). The generator is
   seeded from the hit: the same hit throws the same chips. `ParticleSystem` (pure, `MAX_PARTICLES` 1500, the
   oldest replaced) owns their physics - gravity times the particle's multiple, air drag, the floor / boxes /
   stumps as colliders with a bounce above `MIN_BOUNCE_SPEED`, Coulomb friction, rest on a surface that faces
   up (`wakeNear` wakes them when what they lie on is destroyed), a shrink over the last quarter of their
   life; `ParticleDraw` gives the model matrix of the one shared cube each is drawn with.
7. **The sound.** `DestructionWorld.Outcome` says whether a connection broke and the material's `breakSound`;
   `Main` plays the shot (`GameSounds.forShot`) and then the break sound.
8. Each frame `updateDebris(dt)` steps the pieces and the particles; `drawDestroyed` draws remains, pieces
   and chips; further hits keep carving remains or a piece lying on the floor; `clearDestroyed()` runs on a
   scene switch.

Landing surfaces are the floor, the remains, and the boxes of nearby cubes and cylinders. **To make a hit
stronger or weaker** change `CRATER_RADIUS_VOXELS`; the cost grows fast (measured at radius 30: 25-60 ms to
carve plus about 50 ms to mesh; radius 60 costs about three times that), which is why it is a constant and
not a per-shot parameter yet.

**Known limits** (ROADMAP section 4): "what stays attached" is a heuristic - the format has no support or
hinge data, so a door shot through the middle sheds its smaller half, not the half the hinges do not hold;
pieces do not collide with each other; a piece does not inherit an animated instance's motion; spheres,
cones, tori and meshes are not landing surfaces; `twist` and `curve` do nothing on the GPU and so nothing in the voxelizer
either (taper, bend and shear are applied); a panel narrower than the crater (a 0.45-wide door) gets a hole, not a cut; particles
do not collide with the falling pieces or with each other (only with the floor, the boxes and the stumps);
the break analysis is skipped for a window over 8 000 000 cells (the hit then stays as the crater made it);
the break looks at the crater's own neighbourhood, so a connection that was already thin and is not touched
by the hit never breaks.

**Verified by** `RegressionDestructionWorld` and the suites under it (`RegressionVoxelCrater`,
`RegressionVoxelConnectivity`, `RegressionVoxelMesh`, `RegressionRigidDebris`, `RegressionVoxelFracture`
for the break, `RegressionParticles` for the chips and their physics, `RegressionBreakProfile` for the
material's keys, plus `RegressionCraterOOM` under `-Xmx512m`). `tools/regression/VoxelFallDemo.java` renders the sequence with a
small software rasteriser (`VoxelFallDemo OUT_DIR post|plank|door|cube|crate|post2|slab|rubber [wide|close]`; it draws the chips
with `ParticleDraw.matrix`, the matrix the renderer hands to its cube, and `slab` / `rubber` are the same wooden slab with and without a
material that breaks); the pictures are in `docs/renders/g_*.png`. **Not verified here:** the OpenGL side (mesh upload, textured voxel
faces, the drawing of the particles with their shared cube, the native crash `0xC0000374` reported with a
textured primitive) and the playing of the break sound.

### The building blocks

`VoxelOctree`: sparse occupancy octree, 2^maxDepth grid per axis. The "optimization" value is
`insertFilledBox` collapsing a large uniform region (a solid cube's whole interior, say) to a single node
rather than one leaf per voxel - verified with a real test: a fully-filled 64^3 grid (262144 voxels)
collapses to exactly 1 region. `collectFilledRegions()` returns the compressed form; use
`collectFilledUnitVoxels()` only when individually-addressable 1x1x1 pieces are actually needed, since it
deliberately gives up the compression.

`PrimitiveVoxelizer`: converts one PbdInstance into a VoxelOctree, honouring **every parameter the primitive is drawn
with** (cap, faces, smooth, taper, bend, shear, the scale and its sign - see "Voxelizing what is drawn" below). It returns
null for `indestructible=true`, for a type it does not know, for an instance with no geometry (`ref`, `group`, `light`), for
a scale that is zero or not a number, for a shape with every face removed and for a mesh that encloses no volume (below);
`whyNot(instance, voxelSize)` says in a few words which, and a hit on such an instance is not refused: it is destroyed outright
(`DestructionWorld.destroyWhole`, step 2 of the G flow). Zero-volume primitives (plane, disc) get extruded to a thickness (a
fraction of their largest in-plane dimension, in whole layers of voxels) before voxelizing, rather than mapping to zero voxels.
The curved shapes are standard parametric definitions in the canonical -0.5..0.5 local space every primitive already uses -
verified quantitatively (`RegressionPrimitives`: cylinder / cube ratio pi/4, cone / cylinder 1/3, sphere pi/6, a torus's centre
voxel hollow) - and the hollow, bent and sheared ones are built from the surface the shader itself draws (`ShapeEval`,
checked against the GLSL text), so there is no second set of curves to drift from the shader's.

A **`mesh` instance is voxelized too** (`voxelizeMesh`; guarded by `RegressionMeshVoxel`): the surface exactly,
with a triangle / box separating-axis test per voxel, and the inside by casting a ray along +X and counting
crossings (parity), so **only a closed mesh gets a solid body**. A mesh with no triangle, with no thickness
on an axis (a flat quad, a decal) or with no inside at all is refused (null, and the G hit says so) - it
used to be rasterized into a block over a metre thick. Three things in there came out of the first real
test, and each has a test that fails without it: the ray is nudged off the faces' diagonals (a ray through
the diagonal of a cube's side crosses the two triangles once and a half, and a unit cube lost 21 % of its
volume); the grid starts at `meshMin * scale - offset` (the instance scale had been left out, so a scaled
mesh stood in the wrong place); and the broad phase uses the mesh's own `bounds()` rather than the canonical
box, since a mesh's vertices are not normalised. The what-stays rules and the limits of the whole chain
are the same as for a primitive and are listed in `docs/ROADMAP.md`, section 4.

`VoxelMeshBuilder` turns a VoxelOctree's regions into a PbdMeshData directly (one box per region, no
per-voxel duplication for a merged region) - it renders through the EXISTING "mesh" instance pipeline
(ClassicMeshRenderer). UV tiles by voxel COUNT, not world size (a 4-voxel merged region shows UV 0..4, not
0..2), so a compressed region looks like the same density of small voxels it represents. A real placement
bug was caught here: the original gridOriginLocal formula was missing a `-scale/2` term, producing a
world-space offset exactly `scale/2` too high on every axis - caught by comparing the voxelized mesh's
world bounding box against the original primitive's own, not by inspection; also verified with a 90-degree
Y rotation (the X / Z extents swap), using the same Z, Y, X rotation order as every other transform here.

### Octree-adaptive rasterization (not just octree storage)

The curved shapes (cylinder, cone, sphere, torus) used to test every individual voxel in their bounding grid
one at a time - correct, but not actually using the octree's own hierarchical structure beyond compressing
the FINAL result. Rewritten to recursively classify whole REGIONS first (RegionClassifier +
rasterizeAdaptive in PrimitiveVoxelizer): a region provably entirely inside the shape gets one
insertFilledBox call and stops recursing; entirely outside gets skipped; only a region straddling the curved
boundary recurses into its 8 children, down to individual leaf voxels only right at the boundary itself.

Verified two ways, not just "it runs": (1) exact equivalence - a temporary test compared the new adaptive
result against the old brute-force one, voxel-set-for-voxel-set, across cylinder / cone / sphere / torus at
three resolutions (16 / 32 / 64 per axis) plus a non-cubic grid case, all identical; (2) real cost
reduction - at a 128^3 grid, the adaptive cylinder used 235,721 classify() calls against 2,097,152
brute-force point tests (11.2%, roughly 9x fewer) - a cylinder's own boundary shell is a real fraction of
its volume at any finite resolution, so this ratio is shape-accurate, not an inflated best case.

Region containment for each shape uses the standard closest / farthest-point-in-an-axis-aligned-box
technique, the same tool sphere / box intersection tests use generally - torus nests it twice (radial
distance from the Y axis, then how far that sits from the ring's own major radius).

### Voxelizing what is drawn (every parameter of a primitive)

The question this section answers is "is every parameter of a primitive honoured by the conversion into voxels?". It was
not: the shade of a desk lamp (a cylinder with `cap=none`) came out as a solid cylinder, a bend or a shear was ignored, and
a cube with `faces=(...)` removed was whole again. The rule now: **the voxels are those of the shape the GPU draws.**

| The instance is | It goes through | Why |
|---|---|---|
| a closed solid (every part drawn: cube, cylinder `cap=both`, cone, sphere, torus), at most tapered, rounded or not | the analytic classifiers of `PrimitiveVoxelizer` | exact, and the whole interior is bulk-filled |
| a plane or disc under no effective modifier | the analytic slab | a thickness it does not have is given to it |
| hollow (a cylinder with `cap=top|bottom|none`, a cone with `cap=none`, a cube with `faces` removed) | `ForwardVoxelizer`, as a shell | a hollow shape has no "inside" to classify |
| a bend or a shear on anything, a modifier on a plane or disc | `ForwardVoxelizer`, as a solid (closed shapes) or a shell | bend and shear have no inverse in closed form; the forward map is exactly the shader |
| a `mesh` | `MeshVoxelizer` | see above |

*Effective* means that it changes the shape: a bend of 0 or a taper from 1 to 1 does nothing and stays analytic
(`ShapeEval.hasEffectiveModifier`). `twist` and `curve` do nothing on the GPU and so do nothing here.

**The parts.** `PrimitiveParts` says which parts a primitive is drawn with, for the renderer (`PatchExpander`) and the voxel
conversion alike: a cylinder has a wall (0), a top (1) and a bottom (2) and `cap=both|top|bottom|none` picks the discs, a cone a wall and a
base (`cap=none` drops it), a cube six faces (+x -x +y -y +z -z) and **`faces=(+x,-y,...)` lists the faces that are REMOVED**. A shape
drawn with all its parts is a solid; `cap=top` is a cup open at the bottom, `cap=none` a tube. A cube with every face removed has
nothing to voxelize.

**The surface.** `ShapeEval` is a port of `pbd.tese` (all seven primitives, the rounding of a cube, and `applyBend`,
`applyShear`, `applyTaper` in the order the file lists the modifiers). `ModifierParams` is the only place that says what a modifier's
parameters are called, what they default to and how an axis is spelt (`"y"`, `" Z "`; anything else, or nothing, is x): the
renderer's modifier buffer, the baked mesh cache and `ShapeEval` all call it, because a copy that drifts is an object drawn one way
and shot at another (`RegressionShaderPort` checks that nothing else in the engine spells a parameter name). A number that cannot
be read, or is infinite, counts as missing (the default applies): a hand-written `angle = ninety` used to throw in the middle of a frame.

**The lattice.** A solid is a 3-D lattice over the canonical cube, a shell a (u, v) lattice per drawn part grown inward along the
surface normal by the wall thickness, so the outside is exactly the drawn surface; each cell is carried through the bend, shear and
taper and then through the scale. The lattice is fine enough that no cell's image is longer than half a voxel (`STEP`) - it is
checked after the pass, and a lattice that turns out too coarse (a strong taper, a fold of the surface) is refined and the pass
repeated. Each cell then gives **its volume to the voxels its bounding box overlaps, each getting the share it covers** (exact for a
face parallel to an axis; a slanted face is smoothed by less than a cell). A voxel is filled when it is covered half or more
(`FILL_COVERAGE`; exactly half fills, as `Math.round` does on the analytic grid - `FILL_TOLERANCE` keeps that rule from depending on
the last digit of a float sum). Three details are there because the first version did without them and each has a test that fails
without it (`RegressionVoxelLattice`): the voxels are the cells of a lattice **laid from the shape's own lower corner**, so a tube
five voxels tall centred on its origin keeps all five (it lost both ends when the lattice was laid from the object's origin);
the parts of a shell overlap where they meet (the walls of a box in a corner, the wall and the cap of a cylinder at a rim) and
a voxel's coverage is **the largest of its parts', not their sum** (the sum left a frame of extra voxels along every edge); and a
cell is spread, not binned whole into the voxel of its centre (which made a flat face fill one row and skip the next).

**Walls and slabs.** A hollow shape has a wall of `SHELL_VOXELS` = 3 voxels (15 mm at the engine's 5 mm), but never more than
`SHELL_MAX_FRACTION` = 40 % of the extent it grows across: a thin tube stays a tube and does not fill up (below about 7 voxels
across it comes out nearly solid - a deliberate choice). A plane or disc is given a thickness of `SLAB_FRACTION` = 8 % of its larger
side, at most `SLAB_MAX` = 0.1 m, in **whole layers** of voxels (rounded half up, never fewer than `SLAB_MIN_VOXELS` = 2), grown to both
sides of the sheet; the analytic slab uses the same `ForwardVoxelizer.slabLayers`, so the same leaf is as thick with and without a
modifier that does nothing to it. Whole layers, because both faces then fall on voxel boundaries and every layer is full - a slab
whose faces fell inside a layer left that layer speckled.

**Sizes.** The work is proportional to the number of lattice cells, which `MAX_CELLS` = 4 000 000 caps (about half a microsecond a
cell on a slow machine, so two seconds at the very most; the slowest extreme case of `RegressionVoxelLimits` took 2-3 s on the 2-core
workspace it was written on), and the grid has to fit the octree (`MAX_GRID` = 1000 voxels a side). A shape that would need more is **voxelized whole with larger
voxels** - `Result.voxelWorldSize` says which, `requestedVoxelWorldSize` what was asked and `coarsened()` whether they differ - and
not cut or refused: a 2 m drum at 3 mm, a pipe 30 m long, a plane 1e20 m across all keep their whole shape. Nonsense gives
nothing, never an exception or a hang: a voxel size that is zero, negative, NaN or infinite; a scale that is zero or not a number
(a plane or a disc may have Y = 0, its Y is read only by the modifiers); a shape thinner than half a voxel; a bend of 1e30 rad
(`RegressionVoxelLimits` runs these, each under a time limit). **A negative scale is a mirror**: the object is voxelized with
the scale made positive and the grid is reflected afterwards (`GridMirror`) - a cone with a negative height stands on its apex,
and `DestructionWorld` and the texture mapping follow.

**Pinholes.** A solid has no room inside it, but a voxel covered 49 % where 50 % fills is left empty, and inside a bent, sheared or
rounded solid that leaves pinholes that a crater then opens onto. `VoxelCavities.enclosedEmpty` floods the empty voxels from outside the
box and returns every empty voxel the flood does not reach, and those are filled. Only solids are treated (a shell is a wall with air
behind it on purpose), and a box of more than `MAX_SEARCH_VOXELS` (64 M) voxels is left as it is.

**Verified by:** `RegressionShaderPort` (the port against numbers computed independently from the GLSL text, position and normal
within 1e-9, plus the geometry the shader's comments promise), `RegressionVoxelShapes` (every shape voxelized with every parameter:
the volume against the exact formula, a lampshade's empty axis, the open end of a cup, a box without its top, the bent pole, the sheared
leaf, a 20 m wall, a room inside a block, and "covered, nothing sticks out" against a brute-force comparison with the drawn surface),
`RegressionVoxelLattice` (which voxels, to the voxel, wherever the shape sits on the grid), `RegressionVoxelLimits` (the extremes
above), `RegressionMirror`, `RegressionTaper` / `RegressionTaperWide`, and the mutation spec `mutants_voxel_shapes.py`
(docs/TEST_PROCEDURE.md, section E). The hollow box is the one shape whose every voxel can be counted by hand (the box less its
cavity, a voxel filled when at least half of it is in the wall, exactly half included): `RegressionVoxelShapes` does that for seventeen
boxes and the voxelizer agrees on every one. **Labelled, not hidden:** a mutation round on that spec left mutants alive; the ones that
change the cost or the last voxel of a boundary and nothing a check could pin (how a cell is split, when a pass is repeated, a rounding
below a voxel) and the ones not yet analysed are listed as such, with their numbers, at the head of `mutants_voxel_shapes.py`; and a
cell whose image is longer than four voxels (a fold of the surface under a violent modifier) goes whole into the voxel of its middle,
which is an approximation, not a measured one.

## A material's pictures: scale, mirror and rotation (`MaterialUv`, `MaterialPictures`, `TextureTurn`)

The pictures of a material (`texture`, `normalMap`, `roughnessMap`, `displacementMap`) are read by **three** things that have to agree: the
tessellation shader (through the texture arrays of `MaterialTextureArray`), the classic mesh renderer (`ClassicMeshRenderer.setTexture`) and the
voxels of a shot object (`VoxelTextureMapper`, fed by `PbdRenderer.voxelTexturePixelsFor`). How a picture sits on a surface is three kinds of
material key, all read by `MaterialUv`: `uvScale` / `uvScaleU` / `uvScaleV` (the repeats; a negative number mirrors) and `uvRotation` (degrees,
multiples of 90).

- **The turn is done once, in plain Java, where the picture is loaded.** `TextureTurn.rotate` turns the pixels and nothing downstream knows that
  a turn exists: that is what lets the shader, the classic mesh renderer and the voxels show the same picture without three copies of the
  arithmetic - and why the angle cannot be free (45 degrees would be a job for the shader, at every pixel of every frame, for four maps, and again
  for the colour of the voxels).
- **A picture is a file and its turns.** `MaterialPictures.Request(path, quarterTurns)` is named by `keyOf` (`"<turns>:<path>"`); the layers of
  the arrays, the lookup of a material and the table the voxels read are all keyed by it, so two materials may turn one file differently, a
  file is decoded once however many turns of it are asked for (an image decoded twice in quick succession once corrupted the native heap), and
  nothing is kept from one call to the next (a hot-loaded change of `uvRotation` shows). `MaterialPictures.plan` is the half of building an array
  that is not OpenGL, so it runs - and is checked - without a window.
- **The order is the scale (and the mirror), then the turn**, like Blender's Mapping node of type *Point*; the turn is counter-clockwise as seen
  on the surface (u to the right, v up). Blender's node turns the *coordinates* and the engine the *picture*, so the add-on writes the node's
  Rotation Z as minus the angle.
- **A new reader of a material's pictures must ask for the turns** (`MaterialUv.quarterTurns(fields, problems)`) and pass them on:
  `voxelTexturePixelsFor` has no form without them, on purpose. `RegressionTextureRotation` reads the wiring of each reader as text and fails
  when one looks a picture up without them.
- **What is not turned**: the numbers stored in a normal map (which way each pixel's bump leans). Blender does not turn them either, so the
  lighting seen in Blender is the lighting the engine shows.

**Verified by:** `RegressionTextureRotation` (the turn against hand-made pictures and the laws of a rotation, the turned picture against the
coordinate rotation it stands for for every size, turn and scale, a box coloured from it, the key, the set of pictures, the wiring as text),
`RegressionTextureMapper` (the mapping that was already there), and in Blender a render in Cycles compared cell by cell with the engine's picture
(`blender_real_check.py`); the mutation specs `mutants_texture_rotation.py` and `mutants_addon_rotation*.py` (`TEST_PROCEDURE.md`, section E).
**Not verified**: the drawing of a turned picture on a GPU - what is checked is the picture the renderers are given.

## Runtime facade (what a mod or an AI script can call)

Offline (no window): `PbdEngine` / `PbdEngine.SceneHandle` - load any of the four forms, read and edit
instances by name, save pretty / minified / `.pbdbin`. See "Developer facade" below. `SceneHandle` has no
typed lever-arm setter; an arm is authored in the file or through `raw()`.

In the running game (needs the renderer, so it is GL-bound):

- `PbdRenderer` public methods: `setOpen`, `isInstanceOpen / FullyOpen / FullyClosed`, `isContainerOpen`,
  `areAllDoorsClosed`, the arm API above, `instanceWorldCenter / Size / Matrix / Rotation`,
  `findDestructibleAlongRay`, `findMetadataCubeAlongRay`, `destroyAt`, `hasDestroyed`, `clearDestroyed`,
  `assessHideout` and `hideKeyVerdict` (hiding places, below), `uploadLights`.
- `Main` statics, so code that does not hold the renderer can still reach the live scene:
  `getContainerItems`, `availableVolume(containerName)`, `canHide(containerName, playerHitboxVolume)`,
  `removeContainerItem(sourceFile)`, and the arm trio `getArmValue(id)` (NaN when there is no scene or no
  such arm), `isArmLocked(id)`, `setArmLocked(id, locked)` (false when there is no scene), and the hiding-place trio
  `isHideoutConcealing(id)` (is someone inside undetectable: every door fully closed, none shot through),
  `getHideoutState(id)` (what that is made of, and why) and `isPlayerConcealed()` (null / false when there is no scene or
  the id is unknown; see "Hiding places" below). The "no scene loaded" contract of the arm trio is tested; the live path is
  not (it needs a renderer).

## Known rough edges (worth knowing before you hit them)

- `pbd.fbx` only handles the FBX variants actually seen so far (see that package's own doc comments) - a
  file with n-gon polygons, multiple Geometry nodes, or an unusual normal / UV reference mode will throw a
  clear error rather than silently producing wrong geometry, but it WILL need new code, not just a config
  change.
- `ContainerContents`'s "physics" is a straight-down fall to the nearest support, not a real rigid-body
  simulation (no rotation, no sideways sliding, no simultaneous-fall collision between two items). The
  voxel debris has its own small rigid-body step (`RigidDebris`) - the two are unrelated and neither is a
  physics engine; choosing one is an open decision (ROADMAP section 2).
- `BinPacker` is a shelf-packer (left-to-right, wrap row, wrap layer), not an optimizer - it won't find a
  tighter packing a human could by rotating items or nesting irregular shapes. It DOES correctly shrink an
  item that fits nowhere at its real size (`scaleFactor`, applied as an actual `.scale()` in Main.java's
  item render transform - a shrunk item that still visually overflowed its box was a real, now-fixed bug:
  the packer computed the position assuming the shrink but nothing applied it to the rendered mesh).
- The multi-door / multi-box container grouping (`Main.java`'s `containerGroups`) groups by EXACT
  trigger-set equality - two boxes sharing only SOME of their doors won't merge into one group. No real
  scene has needed full connected-components graph grouping yet, but it's the corner actually being cut if
  one ever does.
- **Container contents follow the door's state, with one deliberate asymmetry for keyframed doors.** For a
  keyframed door, `isInstanceOpen` (toggled open, regardless of animation progress) decides SHOWING the
  contents and `isInstanceFullyClosed` (toggled closed AND the swing has finished) decides CLEARING them,
  so items do not vanish while still visible through a half-closed gap. For a lever-arm door both questions
  are answered from the arm's live value (above 0.01 = open, at most 0.01 = closed), so the contents
  appear the moment a dragged door opens a crack and disappear when it is back within 1 % of shut. An
  emptied container stays empty: when every door is shut the contents go on a shelf (`pbd.pz.ContainerStock`) and
  the next opening gives the same contents back, so what the player took stays gone until the scene changes (nothing is
  saved to disk, so restarting the viewer refills them).
- `_find_or_create_sound_entry` (Blender add-on) used to call `CollectionProperty.add()` directly from inside
  `draw()`, which Blender explicitly forbids ("Writing to ID classes in this context is not allowed") -
  reproducible every time a keyframe without a sound entry yet was drawn, not a version-specific or
  stale-install issue. Split into a read-only `_find_sound_entry` (safe in draw) plus
  `OBJECT_OT_pbd_add_keyframe_sound` / `OBJECT_OT_pbd_remove_keyframe_sound` operators that do the actual
  write from `execute()`, where it is allowed. Worth remembering for anything else that might want to lazily
  create data while drawing a panel: it can't, full stop - the write has to happen in an operator.
- Cube EDGE displacement (turning a cube into a non-axis-aligned hexahedron by moving one or more edges)
  doesn't exist - `faces=` (see the format spec) removes whole faces, which is a much smaller change than
  free-form corner positions would be. The tessellation shader's `evalCubeFaceFlat` would need generalizing
  from a fixed +-0.5 cube to bilinear interpolation between (possibly displaced) corner positions per face -
  a real chunk of work, not yet started.
- `ContainerContents.loadRandom` searches `pbd.pz.PbdPaths.FBX_DIR` RECURSIVELY (`Files.walk`, not
  `Files.list`) and picks 10-50 items (it was a flat, non-recursive listing picking 2-5, written back when
  the folder held one or two loose test files) - a real asset library organized into subfolders by category
  found zero files under the old listing, so every container came up empty. If you see that symptom again,
  check whether something is filtering `Files.walk`'s results more than intended, not whether items exist
  at all.
- `BinPacker.packMultiple`'s "doesn't fit anywhere, shrink into the roomiest container" fallback used to
  compute the shrink against that container's FULL height, not whatever room was actually left once other
  items already occupied part of it - correct for the first item to hit that fallback, increasingly wrong
  for every one after it in the same container. Found by testing with a genuinely large, varied item count
  (10-50) rather than the 2-5 the original bug report never exercised heavily enough to trigger it. Now
  re-validates against the actual remaining room after the initial shrink + placement, with an
  absolute-last-resort clamp (flush against the ceiling, near-zero height) for a container so
  oversubscribed that no amount of per-item shrinking can rescue it - an intentionally ugly result for a
  scenario that is already a real design problem (that container is too small for what is supposed to go in
  it), not something more packing cleverness fixes.
- Rotation composition is `rotateZ().rotateY().rotateX()` in that order (matching Blender's intrinsic XYZ
  Euler) - this bit a real bug once when composed in the naive left-to-right order instead. If you touch
  rotation math, check it against a known multi-axis example by hand, not just single-axis cases (which
  can't distinguish the two orders at all).
- `SceneHandle.setParent(child, parentOrNull)` really re-parents (it reorders the instances so a parent
  always precedes its children, and throws `IllegalArgumentException`, changing nothing, on an unknown name
  or a cycle) but does **not** compensate the pose in either direction: the instance keeps the
  `pos` / `rot` / `scale` it has, which are then read relative to the new parent (or as world values again
  after a detach). Set the position you want after the call.

## Testing

The checked-in tests are plain `main` programs in `tools/regression/` (no JUnit dependency; there is no
`src/test`): 44 Java suites plus the Blender add-on check (and the add-on's safety check, the PHP suites of the site and its runs
through a real web server, and two stages that read the program's sources and run the real viewer under a virtual screen for what
no test of a function can see: `check_gl_stack.py` and `gl_runtime_check.py`), all run by one command, and a mutation runner that proves
the suites fail when the code under them is broken (and `check_mutant_specs.py`, which fails the run when a mutant no longer applies to the code it breaks).

```
JOML_CLASSES=/path/to/compiled/joml LWJGL_DIR=/path/to/lwjgl/jars sh tools/regression/run.sh
```

`docs/TEST_PROCEDURE.md` is the procedure (what each suite guards, how to read a failure, the checklist
before a release, how to run the mutants). Everything in `pbd.format`, `pbd.pz`, `pbd.fbx`, `pbd.voxel` and
the GL-free classes of `pbd.render` runs without a GPU or a display. `pbd.render`'s GL classes,
`pbd.classicmesh` and `pbd.app` need a real (or Xvfb-virtualized) OpenGL context; there is no headless
mock for those, which is exactly why the logic was pulled out of them. One check does run them, on Mesa's software OpenGL under Xvfb:
`gl_runtime_check.py` starts the real viewer on scenes it writes itself (`docs/TEST_PROCEDURE.md`, section I). What the suites cannot reach is
listed in `docs/STATUS_SUMMARY.md` as **pending** or **done** (as opposed to **done-and-tested**).

Three habits that paid for themselves, and are worth keeping:

- **Assert the value that came back, not that the call did not throw.** Several bugs (the importer dropping
  `leverArm`, `pbd_ref` dropping fields, a re-export writing an empty `source=`) imported without error and
  were only caught by comparing the reimported value with the exported one.
- **A new field or a new format feature gets a reflection guard, not a checklist.** See "Rules that are easy
  to break" above.
- **Break it on purpose.** `tools/regression/mutate.py` applies one exact replacement at a time to a copy of
  the code (or of the docs) and runs the suite meant to guard it; a mutant that survives is a gap in the
  test. 53 spec files, 10383 mutants (`docs/TEST_PROCEDURE.md`, section E, says which round ran when, on which code, and what it found); the kinds - Java, add-on (on the fake `bpy`, or on real Blender
  when the spec says `REAL = True`), docs, script, lint, php and runtime (the real viewer under a virtual screen) - are described in the script's header.

## Lessons recorded from past bugs

- **LWJGL's memory stack is 64 KB, and it gives nothing back until its block ends.** `try (MemoryStack stack = stackPush())` hands out
  buffers (`stack.mallocFloat(16)` is 64 bytes) that are freed when the block closes, not when the buffer is dead. `renderCached` took two
  matrices per model drawn, one block per frame: at about 512 models the stack was full and the viewer died with `OutOfMemoryError: Out of
  stack space` - after every scene tried by hand (a few dozen models) had worked, and with every Java suite green, because none opens a
  window. Take what the loop needs once, before it, and refill it (JOML's `Matrix4f.get(FloatBuffer)` writes at the buffer's position and does
  not advance it), or open a stack of its own inside the loop. `check_gl_stack.py` fails the run on a take inside a loop (`// GL-STACK-OK: <why>`
  allows a case that provably runs once or twice; a comment with no reason allows nothing) and `gl_runtime_check.py` runs the viewer on 2,601
  instances. The general lesson: what no test of a function can see is a limit of the machine - a native stack, a buffer, a layer count
  (`MaterialTextureArray` never asks OpenGL for its own: backlog 33) - and the first scene bigger than anyone had tried finds it.

- **Blender does not refresh its matrices between two lines of a script.** `matrix_world`, `matrix_local` and
  `dimensions` come from the depsgraph, which the interface updates between two clicks and a Python assignment does not.
  Code that reads them right after changing objects (the exporter after a batch edit, anything after the importer) must
  call `context.view_layer.update()` first - the exporter now does at its start and the importer before it returns. A
  scene imported and exported in one script used to come out with a door panel three units off. Only a run on real
  Blender (`blender_real_check.py`, `pip install bpy`) can see this; the fake has no depsgraph.
- **An error type the callers do not catch is a crash.** `fetcher.FetchError` was a plain `Exception` while the importer
  documents and catches `OSError`/`ValueError` for "this source cannot be read", so one unreachable `pbd_ref` (no network,
  a 404) stopped a whole import with a traceback instead of leaving an empty placeholder and a warning. It is an
  `OSError` now. When you add a failure, raise the type the code around it already handles - and press the button with
  the failure present (`scenario_unreachable_sources` stubs the network; `scenario_every_button` presses everything).
- **Do not hard-code what another file already says.** The sidebar's first line read `PBD Tools v0.41.0` for three
  releases; it is now built from `bl_info`, and a check compares the two. The same family: a fallback path counted four
  folders up when the file is five deep, and a user guide that described a "Project Root" field the add-on no longer has.
- The Blender add-on's compiled-format buttons (.pbdbin / .pbdasset / tileGeometry) used to build a
  classpath by hand (just `build/classes/java/main`, or a packaged jar) - correct for the project's OWN
  classes, missing every dependency jar (JOML, LWJGL) entirely. Worked fine as long as nothing reachable
  from PbdConvertMain's main() touched a dependency class; threw `NoClassDefFoundError` /
  `ClassNotFoundException` on `org.joml.Vector3fc` the moment it did, which for a class that serializes 3D
  transforms is essentially always - confirmed via a real Blender error report, fixed by adding a
  `printRuntimeClasspath` Gradle task (delegates to `sourceSets.main.runtimeClasspath`, the same mechanism
  `run` and `convertTileGeometry` already used correctly) and having the add-on shell out to
  `gradlew printRuntimeClasspath` instead of guessing at Gradle's output layout.
- **A tool that shells out to another runtime fails in ways the person cannot fix.** The add-on's .pbdbin / .pbdasset buttons ran
  `PbdConvertMain` with the `java` of the PATH, and on a machine whose `java` was older than the Java 21 the project is built with, Blender
  printed `UnsupportedClassVersionError: pbd/PbdConvertMain` and nothing was exported - for an export that is a gzip and a zip. Both are
  now written by the add-on in Python (`compiled_export.py`, no bpy) and checked against the engine's own readers
  (`tools/regression/addon_compiled_check.py`, and the scenarios of the add-on check that press the button, on the fake `bpy` and on real Blender). The one button that still needs
  Java (tileGeometry) reads the release the classes were compiled for out of the class file and looks for a Java that can run it
  (`java_runtime.py`: `PBD_JAVA`, `JAVA_HOME`, the PATH, the Javas Gradle fetched, the usual install folders), and says what it found when there is none.
  What the move turned up, each a bug of the old route: a `.pbdasset` that left the pictures out, quietly; every `.pbdbin` export in a
  folder writing the same `scene.pbdmat`; a file name with a bracket or a comma written bare, which the engine's reader cuts short
  (the engine's own writer, `valueToken`, already quoted it); references that were not copied into the file.
- `ContainerContents.loadRandom` used to draw its 10-50 target count ONCE from the shuffled file pool and
  accept however many of THOSE happened to extract successfully - an unlucky draw landing disproportionately
  on FBX variants `pbd.fbx`'s simplified reader can't handle yet could leave a container with far fewer
  items than intended, occasionally zero, with the rest of a large real asset pool never tried. Now keeps
  drawing additional untried candidates from the remaining pool until the target is met or the whole corpus
  has had a chance - verified against a real exported door + storage-cube scene end to end (parse ->
  containerGroups -> load -> E-key removal -> physics settle), not just in isolation.
- `BinPacker`'s `scaleFactor` is structurally bounded to <= 0.95 at every assignment site (verified by
  inspection of all of them, not just the common case) - there is no code path that enlarges an item beyond
  its real extracted size, only shrinks one that doesn't fit.
- Container item positioning (both the render transform and removeNearestToRay's hit-test) used to add an
  item's LOCAL offset straight onto its container's world CENTER with no rotation applied - correct only for
  an axis-aligned storage cube. `PbdRenderer.instanceWorldRotation` plus `ContainerContents.itemWorldPosition`
  (shared by both call sites now, so they can't drift apart) fixed this - verified against a known
  90-degree rotation's expected transform, not just by inspection.
- "mesh" instances can carry additional LOD variants (`vertexDataLod<N>=`, see PBD_FORMAT_SPEC.md) that the
  engine swaps between on the existing global -/+ LOD keys (`desiredMeshLodTier` /
  `rebuildMeshRenderersForLod` in Main.java) - separate from and alongside the tessellation-detail LOD
  system those same keys also drive, since "mesh" geometry isn't tessellated at all. A mesh with N captured
  variants steps through them one at a time as global detail drops from maximum; one with more variants than
  the engine's LOD range has steps for never reaches its very lowest ones - a known limitation of this
  simple a mapping. (Open question: Blender writes tier numbers 0 / 1 where this side expects powers of 2;
  the Java side takes any `<N>` as an ordering key, so the suspect is the add-on's LOD UI / export.)
- `ClassicMeshRenderer` (the "mesh" instance / FBX / container-item render path) samples ONE picture, the material's `texture` (with its
  `uvScale` and, since 2026-11-09, its `uvRotation`: `setTexture(path, quarterTurns)`), on a mesh that has UVs; it has no normal, roughness or
  displacement map, which the tessellation pipeline's `MaterialTextureArray` does have. Without a texture, or on a mesh with no UVs, it draws the
  material's flat `color=` (it used to stay at a generic default whatever the instance's `mat=`, reported as "only black, dark"). (Voxel
  remains and debris, which are drawn by this same path, get the texture's look another way: each voxel face's colour is sampled from the
  texture per vertex - `RegressionPerVertexColor`.)
- `SoundPlayer.play()` used to decode (`AudioSystem.getAudioInputStream` + `Clip.open`, real disk / CPU
  work) the FIRST time each distinct sound was needed - synchronously, on the same thread driving animation
  and rendering, right at the moment of the click that needed it. That frame ran long; the NEXT frame's
  delta time (measured against the wall clock, which kept moving during the decode) came out oversized,
  making the door's swing visibly jump ahead right as the newly-loaded sound finally started - reported as
  sound "not quite locked to the first frame." `SoundPlayer.preload()`, called once per distinct sound a
  scene's keyframes and lever arms reference right after construction (Main.java), moves that cost to scene
  load instead.
- `ContainerContents.nearestAmong` (what the E key in Main.java used to do inline as `removeNearestToRay`) prints per-item diagnostics (world position,
  hit-sphere radius, distance from the ray, hit / miss) on every E press, plus how many container groups
  are even open to check - added because repeated reports that E finds nothing had exhausted what code-level
  inspection alone could resolve; every isolated and full-chain test built to reproduce it passed, so the
  next step is seeing the real numbers from an actual run rather than a further theory.
- Found while adding pbd_ref's mat= override: `_create_object_for_instance` had TWO checks comparing against
  the string 'ref' where the real parsed prim_type is 'pbd_ref' ('ref' is only ever obj.pbd.pbd_type's OWN
  value) - meaning source= / fallback= (and now mat=) never actually read back in for any REAL file, only
  for the in-memory 'group' shape the Java parser produces after resolving one, which no serializer ever
  writes to disk. The object still got created correctly (a separate, already-fixed check), so import didn't
  error - it just silently produced a ref object with empty source=, meaning a re-export of a reimported
  file with a ref would have written out an EMPTY source= and silently discarded the reference. Only caught
  by asserting the reimported VALUE matches what was exported, not just that import succeeds without error.

## Light rendering

`PbdRenderer.uploadLights` collects every `light`-type instance into a 64-byte-stride SSBO (binding 5 -
materials own 4, see that method's own alignment comment for the exact field packing), read by BOTH the main
tessellation fragment shader (`pbd.frag`) and the cached path (`cached.frag`) - point / spot lights need to
look the same regardless of which of the two is currently active (the C key toggles between them), so both
shaders carry an identical copy of the same accumulation loop (distance falloff to zero at the light's own
range, plus an angular falloff for a spot's cone edge) rather than only one of the two paths supporting
lights at all.

A light is an ordinary instance: parent it to a keyframed door or a lever arm and it travels with it.
`LightTracker` (pure, tested) notices when an enabled light's world position or aim has changed by more than
a rounding error, and `PbdRenderer` then rebuilds just the lights buffer - toggling or moving a light never
needs re-uploading every instance, patch and material of the scene (which is why `uploadLights` is public
and callable on its own).

Verified as thoroughly as possible without an actual GPU: the Java-side struct packing was cross-checked
field by field against the GLSL struct it is read as (order, type, and byte offset all confirmed to agree),
and the uniform locations used by the `lightCount` uniform (7 in the main pipeline, 5 in the cached one)
were confirmed free by inventorying every OTHER uniform location already in use across every shader stage
sharing that same program. Not run and visually confirmed on real hardware.

**A real crash this feature shipped with.** `PatchExpander.partsFor` throws for any type it doesn't have a
case for. The "mesh" case's own comment already warned that a new primitive type needs a case added here
even when its geometry comes from somewhere else entirely - "light" was added without one anyway, so any
scene containing a light instance crashed immediately on load, before a single frame rendered. Fixed
(light -> zero patches, same as "group" / "mesh"), after reproducing the exact reported stack trace against
a real scene first. Also found while looking for every other place a zero-geometry type needs the same
treatment: the G-hit target search (then `findDestructibleAlongRay` in the renderer, now
`DestructionWorld.findFresh`) excludes "ref" / "group" from being hit at all, since neither has
visible geometry to aim at - "light" was missing from that same exclusion.

**lightEnabled.** A light has a runtime on / off separate from its intensity (a light switch shouldn't need
to remember and restore the authored brightness by hand) - `PbdEngine.SceneHandle.setLightEnabled` /
`toggleLight` / `isLightEnabled`, a `lightEnabled=false` line only written when off (on is the default), and
`uploadLights` skips a disabled light entirely when building the GPU buffer.

**A light that switches with a door, a drawer or a lever (`LightLinks`).** No new format key: a light that
shares its `linkGroup` with something that opens - a "driver": an instance that is not a light, has keyframes
or a `leverArm`, and does not `loop` - is **on while any driver is open** and off when all are shut (the fridge
lamp, the wardrobe light, and, with a small lever arm as the opening object, a wall switch the player flips
with the mouse). `LightLinks` is pure (a scene and an `IntPredicate` "is instance i open" in, a count of lights
switched out; no GL, no clock); `PbdRenderer` builds one per uploaded scene, asks it once per frame in
`updateAnimation` with `isInstanceOpen` (a keyframed door: its toggled-open flag; a lever arm: value above
0.01, so a door being dragged counts) and rebuilds the lights buffer when it switched something or when a
light was switched by hand. The first call forces every linked light to the state of its group (doors load
shut: dark; the file's `lightEnabled` is ignored for a linked light, so "link it to the door" is all there is
to do); later calls are **edge-triggered** - a light changes when its group's state changes, not on every
frame - so the facade's `toggleLight` is not undone a frame later. A light whose group has no driver is not
linked and keeps its own `lightEnabled`. Verified by `RegressionLightLinks` (the rules and the real
`PbdRenderer` wiring read from the source) and, from the Blender side, by the add-on check that exports a
scene and asks `LightLinks` which lamp follows which door.

**The E key: an item, or the light of a model (`UseKey`, `LightSwitch`).** Main's E handler decides nothing: it calls
`PbdRenderer.pressUse(openContainers, eye, direction)`, prints the line of the outcome and plays its sounds. `pressUse` gathers the
two candidates - the nearest item of an open container the ray goes through (`ContainerContents.nearestAmong`) and the nearest part of a
model within `LightSwitch.REACH` = 3.5 m (`LightSwitch.aim`: a ray against each instance's local box, ignoring lights, groups,
references and `metadata=true` volumes, and a destroyed object unless cubes of it remain, in which case it is looked for where those
cubes stand now, `worldForUse`) - and hands both to `UseKey.press`, which is pure: **the nearer wins**, at the same distance the item
(the old behaviour), a part with no light to switch does not compete, and the outcome carries a kind (ITEM, LIGHT, NOTHING), the
console message and the sounds. Whose lights a part switches is `LightSwitch.lightsOf`: the lights of its *model* - the nearest group
above it whose children carry the group's id as a prefix (what a `pbd_ref` expansion makes: one group per placement; two placements of a
lamp are two models, and a light belongs to the innermost) or, where there is no such group, the top of its chain of parents - plus the
lights sharing its `linkGroup`; a light that follows a door (`LightLinks.driversOf != null`) is never switched by hand. `press` is a room
switch (any on -> all off, else all on) written to `PbdInstance.lightEnabled`; `LightTracker.switched(scene)` notices the change and the
lights buffer is rebuilt on the next frame - nothing else needs to know. The click is `switchSound` of the first light that names one,
else the default `light-switch.wav`; `switchSound` is a free-form instance key (it travels through `.pbd`, `.pbdbin` and `.pbdasset`,
and `pbd_ref` copies it) read with `LightSwitch.soundOf`. Verified by `RegressionUseKey` (every rule on in-memory scenes and on a real
`pbd_ref` room, the E handler and `pressUse` read from the source) with `mutants_use_key.py` (151 mutants), and by the add-on's own
check (`scenario_light_click`, `mutants_addon_light_click.py`).

## Hiding places (`pbd.pz.HidingRules`, `pbd.pz.DoorParts`, `pbd.voxel.SlabOpenings`)

A hiding place is a container (a `metadata` volume with `containerTrigger` lines naming its doors) at least
`MIN_HIDE_DIMENSION` (0.4) on every axis. Whether someone inside can be seen, and whether anyone can get in or
out, is decided in one place - `HidingRules`, pure - so the facade getter an AI reads and the H key handler can
never disagree. The game supplies the facts per door: **fully closed** (`PbdRenderer.isInstanceFullyClosed`: a
lever arm at 0.01 or less, a keyframed door back at its start; swinging or ajar is not closed) and **whether a hit has
altered it**, from the destruction world (`DestructionWorld.openingsOf(index)`; null for a door no hit reached = whole).

**A door is the instance the place names together with everything that hangs from it** (`DoorParts`: the usual door is a
group carrying the lever arm, with a panel and a handle as its children, and a shot lands on the panel - the group itself
is never hit, so looking only at the named instance made every door look whole). A door is *shut* when it is fully closed
**and unaltered**: no hit has turned the door, or any part hanging from it, into voxels or destroyed it. **An altered door
counts as open**, whatever its arm says and however small the damage (a dent, a bullet hole, a handle shot off): the place
is then neither *concealing* (a player behind it is seen from the outside, on the facade) nor *shut* (H is allowed through
it). A door that cannot be opened at all (no keyframes, no arm) is scenery, not a door: a place whose only "door" is a wall
would otherwise be shut for good. The old rules, a hole of 3 cm to look through and of 40 cm to walk through, are gone
(`SIGHT_GAP` and `PASSAGE_GAP` no longer exist): a door "almost whole" that lets a bullet's width of light through was
still a door that a player in the cupboard behind it could be seen through, and a rule with a threshold has to be tuned.

`SlabOpenings` still measures a shot door - a slab, looked at along its thinnest axis, its footprint taken once while it
is whole, every covered column with no voxel left an *opening* - and the numbers it gives, `integrity` (the share of the
face still in the way) and `widestGap` (the side in metres of the largest square of opening cells), are reported by
`HidingRules.Assessment` for a HUD or a smarter AI. They decide nothing: altered is altered. A crater that only dents the
slab leaves the back of every column it touches, so it opens nothing *in these numbers* - and still alters the door. A
reading that is not a number never makes a door look whole (unknown integrity 0, unknown gap infinite).

The facade (`Main`, static, for AI and modding code): `isHideoutConcealing(id)`, `getHideoutState(id)` (the
`HidingRules.Assessment`: door count, all closed, whether one was altered, lowest integrity, widest gap, shut, concealing,
and a reason in words) and `isPlayerConcealed()`; the renderer's `assessHideout(index | id)` and `hideKeyVerdict(hiding,
container)` are what they call. Verified by `RegressionSlabOpenings` (the measuring), `RegressionHidingRules` (the
rules, the H verdicts and the wiring) and `RegressionDestructionWorld` (a shot door reports its holes). **Not
verified here:** the H key and the facade in a running window (the glue in `Main` is reviewed only), and a limit:
a linked door that cannot move keeps the place shut for good.

## Sounds and the MP3 decoder (`pbd.audio`)

`SoundPlayer` plays a sound by file name from `src/main/resources/sounds/`: WAV through `javax.sound.sampled`, MP3
through `Mp3Decoder`, which turns the file into 16-bit PCM that the Clip is opened on directly (no file reader is
involved). A name is a file *under* that folder - `SoundPlayer.staysInside` refuses `../x.wav`, an absolute path and
the folder itself, because the names come from scenes and materials that may come from anywhere. A sound that cannot
be loaded is logged and skipped, never fatal, and **not remembered**: a file put in the folder while the program runs
is played the next time. Sounds are decoded once - `preload` at scene load (every keyframe `sound=`, lever arm
`openSound` / `closeSound`, material `breakSound`, and the fixed ones), `play` rewinds the cached Clip to frame 0 and
starts it. This cache outlives a scene change: a sound that has been decoded stays as it was until the viewer
restarts (replacing a file that already played is heard after a restart; a file that was missing is picked up the next time it
is needed). It is deliberate - a decode is real work, and done on the first click it made the frame run long - and cheap to
change if sounds must be hot-loaded too: key the clip on the file's modification time. Which sound goes with a key is decided in `GameSounds`, not in `Main`: `forPickup(item)` (E: the pickup
sound when an item came out, nothing when the press found none), `forLightSwitch(switched, lightSound)` (E on a light: the light's
own click, trimmed, or the default; nothing when nothing was switched) and `forShot(hit, breakSound)` (G); the handlers
play what these return. Where Clips come from is a seam (`SoundPlayer.ClipSource`, the audio system in the program,
a recording stand-in in the tests), so that what the player asks of a Clip is checked on a machine without a sound
card: `RegressionGameSounds` (51).

**Why a decoder of our own.** `javax.sound.sampled` has no MP3 reader, and a third-party jar would change the build.
`Mp3Decoder` is a port of minimp3 (CC0), Layer III only (MPEG-1, 2 and 2.5), pure Java, no native code. Its data
tables are not retyped: `Mp3Tables` is *generated* from minimp3's source by `tools/scripts/gen_mp3_tables.py`, and the
script run again on the same `minimp3.h` must give back that very file. The port keeps minimp3's structure and the
order of its floating-point operations (Java's `float` arithmetic is IEEE single, like that of a C build for plain
x86-64, which fuses no multiply-add), so its output can be compared with the C decoder bit for bit.

The whole-file entry point, `Mp3Decoder.decode(bytes)`, does what a player needs and a frame decoder does not: it
skips ID3v2 tags (and their footers, and several in a row) and the ID3v1 tag at the end; it does not play a Xing /
Info / VBRI frame; it trims the encoder delay and padding a LAME tag announces (plus the filter bank's own 529
samples), so a sound starts on its first sample, as ffmpeg's does; it refuses what it cannot play with a
`FormatException` (an `IOException`: no Layer III frame, Layer I or II, a sample rate or channel count that changes
in the file, a sound longer than `DEFAULT_MAX_SECONDS`, 120 - a sound effect, not a song) and never loops or reads
out of range whatever bytes it is given. `Mp3Decoder.FrameInfo`, `decodeFrame` and `skipId3v2` / `isId3v1` /
`parseTagFrame` are public only so that the suite can pin each corner of the parsing on its own.

**Where it differs from minimp3** (each is pinned by a test, and the C reference was patched the same way to compare):
the 16-bit conversion rounds half away from zero for every sample (minimp3 sends (-1.5, -0.5] to 0); the "private
bits" of the side information are ignored (minimp3 reads them as scale-factor-sharing flags of the first granule);
intensity stereo exists only in joint-stereo mode; the intensity-stereo positions of a frame start at zero (minimp3
leaves in them what the previous call wrote); mixed blocks at 8 kHz are refused (minimp3 writes 24 lines past the
channel); the bytes past the end of the main data are zero; Layer I and II are refused with a message.

**What stands behind it** is written down in `docs/TEST_PROCEDURE.md`, "MP3 decoder": what was compared with the C
original bit for bit, what was checked against the standard, what a mutation sweep of the whole file found, and what
nobody has run (the real `item-equip.mp3`, a sound card). Run `RegressionMp3Decoder` (224) before and after any change to
`Mp3Decoder.java`; `mutants_mp3_decoder.py` breaks the parts a reader of the file gets wrong. The fixtures and how they
were made: `tools/regression/fixtures/make_mp3_fixtures.sh` and `make_mp3_random_frames.py` (not run by `run.sh`: the
`.mp3` files next to them *are* the fixtures, and a different encoder version writes different bytes). The C original the
port is compared with is in the repository too: `tools/regression/fixtures/mp3_reference/` holds minimp3 with the five
corrections (as a patch and as the patched header), the C harness, and `compare_with_c.py`, which builds it and says, file by
file, whether the port and the C program produce the same bits.

## Scene description files and the show (`.pbdscene`)

A `.pbdscene` file says which models stand where, in a few lines (`row`, `grid`, `ring`, `scatter`, `folder`, `place`,
`ground`), and what happens while it plays (`show`, `at <time> camera|orbit|key|click|open|close|time|quit`). The format is in
`docs/PBD_FORMAT_SPEC.md` ("Scene description files"); this is what a developer needs to change it safely.

**It is a shorthand, not a second format.** `SceneExpander.expand(text, baseDir)` returns the text of an ordinary `.pbd`
(`Expansion(pbdText, maxInstances, placements, show)`): one `pbd_ref` block per placement, the instance limit the file asked
for (`scene limit=`) and the show. The engine parses that text like any other (`PbdEngine.loadAny` -> `loadSceneFile`);
nothing downstream knows the format exists. To look at what a scene turns into: `PbdConvertMain expand scene.pbdscene out.pbd`;
to load one the way the viewer does without a window: `PbdConvertMain check scene.pbdscene` (status 1 and the reason when it
cannot).

| class | package | does | knows nothing of |
|---|---|---|---|
| `SceneLine` | `pbd.format` | splits a line into words and `key=value`, typed getters (`number`, `integer`, `vec3`, `choice`, `flag`, `name`, `instanceId`, `path`, with a `required...` twin where a field has no default), `allowOnly(keys...)` | what a directive means |
| `SceneExpander` | `pbd.format` | one method per directive; counts before it writes; the `folder` listing; the SplitMix64 of `scatter` | the show's meaning, files other than the folder it lists |
| `ShowParser`, `ShowScript` | `pbd.format` | the `show` / `at` lines -> a `ShowScript` (camera keys, events, sun keys, overlay, vsync, report, warm-up) with the limits on each | the renderer, time |
| `CameraPath` | `pbd.render` | the monotone cubic of each of the six camera channels, the look-at -> yaw / pitch | windows, keys |
| `SceneDirector` | `pbd.render` | the show's clock, its events in order, the sun, the camera pose handed back (and the glide after the engine had the camera) | what a key does: it tells a `Host` |
| `ShowTargets` | `pbd.render` | where an instance the camera looks at is (the centre of its box and its children's) | |
| `FrameStats`, `StressReport` | `pbd.render` | the numbers of a stress test (percentiles, slow frames, freezes) and the one file a scene may write | |

`Main` only provides the `Host` (the same flags the keyboard callback sets, `renderer.setOpen`, the sun's hour and day), calls
`director.advance(dt, host)` and `steer(...)` once per frame, tells it with `userMoved` when W / A / S / D / Space / Left Shift is down, feeds
`FrameStats` and writes the report after the loop. A decision in `Main` that is not "pass this on" is a bug in the design.

**Rules that are easy to break:**

- **A value reaches the PBD text only through a typed getter.** Numbers are printed again from a `double` in closed form
  (`num`, `vec`), ids must match `INSTANCE_ID`, model paths pass `checkPath` and `FetchPolicy.refuseLocalReference` (no address,
  no network path, no control character, at most 2048). Concatenating a raw word of the file into the output is how a line of
  a scene file would become a line of the `.pbd` (a quote, a newline, an `=` that opens another field). The suite
  `RegressionSceneFormat` feeds 30,000 random lines made of pieces of the format and 40,000 scenes of valid lines, damaged at random, to catch this.
- **Count, then write.** `countPlacements` runs before a directive allocates anything (one directive writes at most
  1,000,000 placements, the scene at most `limit`, never more than `HARD_MAX_INSTANCES` = 5,000,000), and the text is cut
  at `MAX_TEXT_CHARS` minus 65,536. A new directive that writes placements must add its count to the same check.
- **A scene file cannot place a scene file.** A `model=` ending in `.pbdscene` is refused, `folder` never lists one, and a link
  to a folder is not entered: there is no way to write a loop. Do not add an `include`: recursion would need a depth and a visited
  set, and the three limits above would stop meaning what they say.
- **Every directive lists its fields (`allowOnly`).** A misspelled key (`cout=10`) is an error with the line number, never a
  default. A new field is added to that list and to the directive table of the spec in the same change.
- **The numbering is a promise.** Ids are `<id>_<n>`, 1-based; a grid runs x first, then y, then z; a ring's angle starts at
  +Z and grows toward +X and `face=center` turns the model's front (+Z) to the middle. Every `look=`, `open` and `close` of
  every existing scene names an instance this way: change it and each of them points somewhere else, with no error.
- **The show's clock is the sum of the frame times** (each already capped by `InputState.beginFrame`), never the wall clock, so
  that a take repeats and a slow machine plays the same show. Do not read the system time in `SceneDirector`.
- **A virtual key is the real key.** `press("E")` sets the flag the keyboard callback sets; the director never calls the
  light switch or the shot itself. If a key does something new, the show gets it for free; if the show needs its own path to
  it, the test of the key and the test of the show no longer cover the same code.
- **`open` / `close` set, they do not toggle.** A script that runs twice must give the same picture. `PbdRenderer.setOpen`
  refuses a locked arm and says so; the director does not look at the answer.
- **The report is the only file a scene can write**, and `StressReport.write` is the only place that writes it: a plain
  `[A-Za-z0-9][A-Za-z0-9._-]{0,59}\.txt` name, never a device name, never over a file that does not begin with "PBD stress report",
  never through a link or a folder.

**Adding a directive:** a method in `SceneExpander` with its `allowOnly` list, a `case` in the switch, its count in
`countPlacements`, the table row and an example in the spec (a `<!-- check: scene-file -->` block is expanded and loaded by
`RegressionSpecExamples`, so a wrong example fails), tests in `RegressionSceneFormat` and mutants in
`mutants_scene_format.py`. **Adding an action:** its grammar in `ShowParser`, its event in `ShowScript` (with the limit),
what it does in `SceneDirector.fire` through a new `Host` method, the `Host` in `Main` (pass-through only), the spec's table,
tests in `RegressionSceneFormat` (the line) and `RegressionSceneDirector` (the effect) and their mutants.

**What is verified, and what is not:** the expansion, the limits, the injection cases, the camera maths, the director's clock,
events and sun, the statistics and the report file are covered by `RegressionSceneFormat` (581 checks),
`RegressionSceneDirector` (211 checks) and the spec's own examples. The glue in `Main` is also run, on a software renderer, by
`gl_runtime_check.py` (the keys of a show reach the program, a W takes the show over, the quit ends it, the report is written). **Not
verified here:** a show on a GPU with the project's own shaders (the runtime check used stand-ins), `glfwSwapInterval` (a virtual screen
has no vertical blank), the glide while hiding, the speed of a scene of ten thousand models on a real card (one draw call per instance, no
culling: backlog 32), and the memory of a scene that large (about 2.6 KB per instance on the machine the tests run on).

## Developer facade (PbdEngine.SceneHandle)

Covers position, rotation, scale, material, category, parent (by name, not raw index), indestructible /
hardness / resistance, every light field (mode / color / intensity / range / spot angle, enabled), the
derived `dimensions` / `volume`, plus addInstance / removeInstance / hasInstance for actually building or
tearing down a scene rather than only editing an existing one, scene-level metadata, and saving
(`savePretty`, `saveMinified`, `saveBin`; `toPrettyText` / `toMinifiedText`). `raw()` remains available for
anything not covered (lever arms, keyframes, modifiers, meshes). It was verified with a standalone test of
all its behaviours - the round trip of every getter / setter, every documented exception (duplicate name,
unknown type, unknown parent, invalid lightMode) and a full save-to-text-and-reload cycle confirming these
aren't just in-memory Java state but actually reach the real parser and serializer; the formats themselves
are now covered by `RegressionFormatRoundTrip`.

## A note on stub compiles

A real, full compile against the actual LWJGL jars (not a hand-written stub, which only catches
missing-type errors involving pbd's own classes) once surfaced two genuine missing imports - java.io.IOException
in Main.java and java.util.ArrayList in PbdRenderer.java - that had been silently breaking the full build
since the turns that introduced them. A missing JDK-standard-library import specifically will NOT surface
through a stub compile if nothing else in the same file happens to need that stub - only a compile against
the genuine dependency catches that category of error. `tools/regression/run.sh` compiles the whole engine
against real LWJGL jars for this reason.
