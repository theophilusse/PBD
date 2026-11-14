# Status summary (distilled from docs/ROADMAP.md)

A status-by-feature snapshot, rewritten on 2026-10-07 and brought up to date on 2026-10-08, 2026-10-12, 2026-10-20, 2026-10-25, 2026-11-09, 2026-11-10 and 2026-11-14. It is not a replacement for `docs/ROADMAP.md`: the
roadmap is the chronological record (self-corrections included - they are worth keeping) and holds the
evidence; this groups the current end-state by area so it can be read in one pass. Everything marked
**done-and-tested** names the suite that checks it - run them all with `tools/regression/run.sh`
(`docs/TEST_PROCEDURE.md`).

Four statuses, used deliberately narrowly:

- **not done** - not built, or explicitly rolled back/disabled.
- **pending** - partially done, or done but with an open, named gap (including "needs a GPU / a server / a
  file that is not in this checkout").
- **done** - built, and read against the code, but nothing here could execute it (it needs an OpenGL window,
  Blender itself or the PHP server) or no suite in `tools/regression/` covers it.
- **done-and-tested** - a suite in `tools/regression/` runs the real code and passes. Where it matters the suite
  was also shown to *fail* when the code under it is broken on purpose (`tools/regression/mutate.py`; which round ran when, on which
  code, and what it found, spec by spec: `docs/TEST_PROCEDURE.md`, section E - this file quotes the size of a spec, never a result).
  "I just watched it pass" and "the roadmap says so" are kept visibly separate; given this project's history
  (bend and taper were each marked fixed and then found wrong more than once), that separation is on purpose.

## Format layer (.pbd / .pbdbin / .pbdasset)

| Item | Status | Note |
|---|---|---|
| Every field through every format (pretty `.pbd`, minified `.pbd`, `.pbdbin`, `.pbdasset`) | **done-and-tested** | `RegressionFormatRoundTrip` (105): one scene with every field, compared by a reflection walk, plus a completeness guard so a field nobody sets cannot hide (the one field the files leave out on purpose, the show of a scene description file, is proved to be written by none). It found six data-loss bugs (several `include_material` lines, includes with a space, curves never written, unquoted values, materials of placed files, `.pbdasset` bundling only the last include) - all fixed. Mutation spec: `mutants_roundtrip.py` (33). A material that sets all ten break keys, a lamp linked to a door and a lamp with its click (`switchSound`) are in the scene. |
| Multi-word and awkward scene metadata (`kind = static prop container`, a `#`, `=`, `,`) | **done-and-tested** | Same suite; the writers quote exactly when a bare value would not read back. |
| Minified `.pbd` / `.pbdbin` metadata swallowing the rest of the file | **done-and-tested** | Fixed on **both** producers (`PbdSerializer.quoteMetadataValue`, the add-on's `_quote_metadata_value`). `RegressionMinifiedMetadataQuoting` (6), the round trip and the add-on check guard it. Was this file's former "single most severe open bug". |
| Unquoted, `=`-padded base64 `vertexData` (the project's own `mushroom.pbd`) | **done-and-tested** | The parser reads it bare now (`readEncodedValue`); 4 checks in the round trip, 2 in the add-on check; `mutants_bare_base64.py` (4). Writers still quote it. |
| `include_material` through every format | **done-and-tested** | The scene keeps the list of every include; `.pbdasset` bundles all of their materials and textures. |
| `pbd_ref` copies everything (keyframes, lever arms, lights, `indestructible`, meshes, container triggers, materials of the placed file) | **done-and-tested** | `RegressionPbdRefCopy` (248). |
| Files a `pbd_ref` points to inside a `.pbdasset` | **pending - known limit** (the engine's converter) / **done-and-tested** (the add-on's export) | `PbdConvertMain` does not bundle them: flatten or embed first. The add-on's **Export Compiled Format** copies the content of every model a `pbd_ref` names into the file (*Inline References*, on by default; off, the `pbd_ref` lines stay and the models must be found beside the file) - `blender_addon_check.py`, `scenario_compiled_references`. |
| **Export Compiled Format** (`.pbdbin`, `.pbdasset`), written by the add-on itself (v0.48.0, hardened in v0.48.1; v0.48.2 for an add-on updated in place) | **done-and-tested** (on the fake `bpy`, on real Blender 4.2 to 5.2, and by the engine reading every file) / **done** (Blender's file browser window) | The old button ran `pbd.PbdConvertMain` with the PATH's `java` and ended in `UnsupportedClassVersionError` on a machine whose `java` was older than the Java 21 the classes are compiled for. Neither format needs Java: `compiled_export.py` writes them (a `.pbdbin` is `PBDB`, a version byte, the gzip of the minified text; a `.pbdasset` is a zip), the engine's limits are applied before the first byte, the file is written whole or not at all, the pictures of the materials come with it (the old route left them out), a `.pbdbin` has its `.pbdmat` beside it under its own name, a name that is nothing is refused (the mutation round found that it used to write a hidden `.pbdbin` where Blender was running). The two formats are also in **File > Export** (*PBD Binary (.pbdbin)*, *PBD Asset bundle (.pbdasset)*), next to PBD (.pbd). The one button that still runs Java (*Add to tileGeometry.txt*) looks for a Java new enough (`java_runtime.py`) and says what it found. An add-on updated in place ran its **old** code (Blender keeps an add-on's modules in memory), which is how the button said `PbdConvertMain failed` again: since v0.48.2 `__init__.py` lets go of the old modules, a test installs two copies through Blender's installer and looks at what Python holds, and another that no program is started by either button (`ROADMAP.md`, item 35). `addon_compiled_check.py` (151), `blender_addon_check.py` (891), `mutants_addon_compiled.py` (349), `mutants_addon_compiled_ops.py` (114), `mutants_addon_update_real.py` (7). Not verified: the file browser's window; names with non-Latin characters through the Java button. |
| A value containing `"` | **pending - known limit** | The format has no escape; the writers cannot write it. Numbers are written with 6 significant digits; re-serializing drops comments and formatting. |
| LOD tier field (`vertexDataLod<N>`): Blender writes 0/1, Java "expects" powers of 2 | **pending** | The Java side takes any `<N>` as an ordering key, so the suspect is the add-on's LOD UI/export - still unread. |

## Lever arm (new since the previous revision of this file)

| Item | Status | Note |
|---|---|---|
| `leverArm { openPos openRot speed release locked lockAt openSound closeSound }` in every format | **done-and-tested** | `RegressionLeverArm` (45), the round trip, `RegressionLeverArmAttach`, the add-on check. Specification checked line by line: `docs/ROADMAP.md`, section 2. |
| Run-time system: value, lock, snap/free release, open/close sounds, grab, drag | **done-and-tested** | `RegressionLeverArmSystem` (147) - pure class, no GPU. The drag is checked against a hinge worked out by hand (worst error 0.0002 degrees). |
| Any primitive can hang on an arm, and can be an arm; lights follow; run-time `setParent` | **done-and-tested** | `RegressionLeverArmAttach` (90): all ten types, nesting, placed files, all four formats. |
| AI/modding accessor `Main.getArmValue / isArmLocked / setArmLocked` | **done-and-tested** (no scene) / **done** (live) | The "no scene loaded" contract is tested (`mutants_arm_facade.py`, 4); the live path needs a renderer. |
| Container contents follow a dragged door (facade link) | **done** | `PbdRenderer` reads the arm value; GL-bound, reviewed. |
| Mouse wiring and the feel of the drag in the viewer | **pending** | `Main.java` mouse handling is reviewed only. Grab a door in-engine before video 2. |
| Real physics behind the drag (inertia, collisions) | **not done** | The drag is geometric - the prototype you chose first. |
| Rattle sound when pulling a locked arm | **not done** | One new field across all formats; the round trip's completeness guard will insist on it. |
| Keyframed doors -> lever arms | **done-and-tested** (tool) / **pending** (your library) | `PbdConvertMain lever` (`RegressionLeverArmMigration`, 57; `mutants_migration.py`, 29). Running it over the asset library is a command for you to run. |
| Blender: Pivot (hinge), Lever Arm panel, "Capture Current Pose as Open", export/import | **done-and-tested** | Against the engine, on a fake `bpy` with Blender's real `mathutils` (`blender_addon_check.py`, 891; `mutants_addon.py`, 103): export, import, and the add-on's own panels, menus, dialogs and buttons drawn and pressed on a recording layout. Also on real Blender 4.2, 4.5, 5.0, 5.1 and 5.2 (`blender_real_check.py`, 878 on each; `mutants_addon_real.py`, 13, for what only it can see): every icon looked up, every button pressed once, the zip installed by Blender's own installer, a second copy installed over the first. **Not run**: the panels in a window (how they look), undo, Blender 4.0.2 itself (yours: only its icon list and API stubs were compared), 4.1, 4.3 and 4.4. |
| Blender: add a lever arm (**Door**, **Drawer**, **Lever**, **X** to take it off), **Add Light Linked to This**, ready-made **Door (hinge)**, **Drawer**, **Light Switch + Lamp** | **done-and-tested** | You could not find them; they are in add-on v0.45.0. Checked against the engine: the open pose it works out from where the object stands is the one the engine computes from the export, its answer to "does this open?" is the engine's object by object, and the engine says which lamp follows which door in an exported scene (`blender_addon_check.py`, `scenario_what_opens`, `scenario_light_links_travel`); every button pressed on real Blender; `mutants_addon_mechanisms.py` (182). How the buttons look in a Blender window: **reviewed**. |
| A light that shares its `linkGroup` with a door, a drawer or a lever is on while it is open | **done-and-tested** (the rules) / **done** (the wiring in `PbdRenderer`: read from the source by the suite, not run) | `RegressionLightLinks` (84; `mutants_light_links.py`, 57). Edge-triggered; keyframes or a lever arm, never a loop; the file's own `lightEnabled` is ignored for a linked light; no inverted polarity yet (backlog 24). |

## Scene description file, the show, and the viewer run for real (new since the previous revision of this file)

| Item | Status | Note |
|---|---|---|
| A scene description file (`.pbdscene`): `place`, `row`, `grid`, `ring`, `scatter`, `folder`, `ground` expanded to an ordinary `.pbd` of `pbd_ref` blocks | **done-and-tested** | `RegressionSceneFormat` (581; `mutants_scene_format.py`, 596): every directive's text to the character, the scatter, rings and orbits against a second implementation in Python, the folder listing, every refusal with its line number. `PBD_FORMAT_SPEC.md` ("Scene description files"); its three whole examples are loaded through the engine by `RegressionSpecExamples`. Conventions chosen where the request was silent: ids from 1, the front of a model is +Z, a ring starts on the +Z side and goes toward +X, a grid counts x, y, z. |
| What a scene file cannot do to the machine: bounded everywhere it is read, a value can only be a value, no scene in a scene, nothing fetched, nothing run, a report that cannot overwrite a document | **done-and-tested** | Same suite: hostile lines and tens of thousands of random combinations held to the few shapes of line the expansion writes; every limit refused at its own line. The site does not accept the extension. |
| The show: a camera path (a monotone cubic through the keys, an orbit), the keys E, G and H at set times, a keyframed door clicked, a lever arm opened or closed, the sun, `quit`; the person's W, A, S, D, Space or Left Shift takes it over | **done-and-tested** (the player: pure logic, the camera path against scipy's curves) / **done-and-tested** on a software renderer (the wiring in `Main`: keys delivered, the take-over, the end, the report) / **unknown** (a take on a GPU) | `RegressionSceneDirector` (211; `mutants_scene_director.py`, 395), `gl_runtime_check.py` (five scenarios; `mutants_gl_runtime.py`, 15). |
| The stress report (`show report=...`): counts, load times, heap, frame statistics (percentiles of nearest rank, lows, freezes) | **done-and-tested** (the arithmetic - the percentiles are checked at their exact rank from 1,000 to 20,000 frames, where the 99.9th was the worst frame until the mutation round of 2026-10-25 found it -, the layout, the file rules) / **unknown** (what a real card gives) | `RegressionSceneDirector`; the layout is held to what `Main` writes by `RegressionSpecExamples`. |
| A scene of thousands of models does not run the viewer out of memory (it once stopped at about 512 instances: two 64-byte matrices per model taken from LWJGL's 64 KB stack, given back only at the end of the frame) | **done-and-tested** | `check_gl_stack.py` reads every source for memory taken from that stack inside a loop (`mutants_gl_stack.py`, 204; `mutants_gl_stack_sources.py`, 16); `gl_runtime_check.py` runs the real viewer under a virtual screen on 2,601 instances. Neither prints `PASS:` lines: they are in no total. `TEST_PROCEDURE.md`, section I. |
| The viewer asks the machine for a window more than once before it gives up (the report: `GLFW_VERSION_UNAVAILABLE ... EGL: Failed to create context: Arguments are inconsistent`) | **done-and-tested** (the plan, the walk through it against a GLFW that follows a script, the words of the message) / **unknown** (what a given GPU, driver and desktop accepts) | `GlContextPlan`, `GlContextNegotiator`, `LwjglGlfwDriver`; `RegressionGlContext` (144; `mutants_gl_context.py`, 277). The old request first, then without the forward-compatible flag, then a compatibility context, on Linux each with GLX and EGL, and on X11 after Wayland; one line says which request was refused and which is used, and when none opens the exception lists them all with GLFW's own answers, what a window with no requirement gets, and the cures that fit the system. **Nothing here could open a window** (no display, no GPU): whether the second or third request opens one on your machine is the next piece of evidence. `ENGINE_DEV_GUIDE.md`, "The window does not open". |
| Drawing many models fast: one draw call per instance, every frame, no culling | **pending** | `ROADMAP.md`, backlog 32: the first stress run on a real card says what to do. |
| Many or large textures: the texture array does not ask OpenGL for its limits | **pending** | `ROADMAP.md`, backlog 33. No test can see it. |

## Voxel destruction (G key)

| Item | Status | Note |
|---|---|---|
| One G hit cuts a post or a plank in two; the severed piece falls and comes to rest on the floor | **done-and-tested** | Solid crater of 30 voxels (`VoxelCrater`), `VoxelConnectivity`, `VoxelDebris`, `RigidDebris`, `DestructionWorld` - `RegressionDestructionWorld` (172) and the suites under it. Pictures: `docs/renders/g_*.png` (software rasteriser, not OpenGL). |
| A `mesh` instance can be shot too: a closed one is solid, an open or flat one is a wall | **done-and-tested** | `RegressionMeshVoxel` (130): a closed mesh becomes a solid by an exact triangle / box test and ray parity (a unit cube mesh fills all of its voxels - it used to lose 21 %), placed where it stands whatever its scale, offset and rotation, found by the shot through its real vertex bounds (`DestructionWorld.findFresh`), cut and its pieces dropped. An open or flat mesh (a quad, a decal, **a single triangle**) used to be refused and left standing; it is voxelized as a wall of 3 voxels (`MeshVoxelizer`). A mesh with no area at all is destroyed by the hit. `mutants_mesh_voxel.py` (84). GL drawing of the remains of a mesh: **reviewed** only. |
| What cannot be turned into cubes is destroyed by the hit, not refused | **done-and-tested** | `DestructionWorld.destroyWhole`: a mesh with no readable triangle or only lines, a shape thinner than a voxel, a cube with every face removed; it stops being drawn, throws a burst of its own material, and counts as an open face for the hiding places. Only `indestructible` is refused. `RegressionDestructionWorld` (172). |
| The crater throws chips instead of deleting what it takes (the crater's volume and the crumbs too small to fall) | **done-and-tested** (the physics, the count, the colours) / **done** (drawing: one draw of a shared cube per chip, GL-bound) | `RegressionParticles` (148; `mutants_particles.py`, 273), `RegressionVoxelDebris` (44; `mutants_voxel_debris.py`, 102), `RegressionWorldDebris` (60): chips fly out of the hole along the line of fire, fall, bounce on the floor, the boxes and the remains, come to rest and shrink away; at most 1500 alive; the same hit throws the same chips. Seen in the pictures of the software rasteriser, never in OpenGL. |
| How a material breaks is the material's business: ten `break*` / `debris*` keys of a `.pbdmat`, never the primitive's metadata | **done-and-tested** | `RegressionBreakProfile` (76; `mutants_break_profile.py`, 115): defaults by material name (wood, metal, plastic, other), every range, a refused value (never clamped) with its warning, a mistyped key reported, the spec's table compared with the code cell by cell (`RegressionSpecExamples`); through all four formats (round trip) and the add-on's Materials panel (`mutants_addon_break_material.py`, 283). |
| A connection too thin for its material breaks and the whole piece it held falls, with a break sound (the "cassure") | **done-and-tested** | `RegressionVoxelFracture` (59; `mutants_voxel_fracture.py`, 168) against a brute-force reference, and the flow in `RegressionDestructionWorld` (172; `mutants_destruction_world.py`, 227). A window over 8,000,000 cells is not analysed and the hit says so. The break sound needs its file (not in the upload). |
| How much of a shot door is open (the widest square hole, not the percentage of damage) | **done-and-tested** | `RegressionSlabOpenings` (39; `mutants_slab_openings.py`, 86), against a brute-force version on random slabs. Reported, no longer decisive (see the hiding places below). |
| A shot primitive is voxelized as it is DRAWN: `cap`, `faces`, rounding, taper, bend, shear, scale (the shade of a lamp stays a tube) | **done-and-tested** | The report: the empty shade of `desklamp.pbd` came out solid. `ShapeEval` is a port of the tessellation shader, `ForwardVoxelizer` builds hollow and bent shapes from the surface it draws, `PrimitiveVoxelizer` keeps the analytic path for closed solids. `RegressionShaderPort` (85), `RegressionVoxelShapes` (106), `RegressionVoxelLattice` (23), `RegressionVoxelLimits` (16: the extremes under a time limit), `RegressionMirror` (54); `mutants_voxel_shapes.py` (331), `mutants_mirror.py` (77). `ENGINE_DEV_GUIDE.md`, "Voxelizing what is drawn". Chosen, not measured to be right: the wall of a hollow shape is 3 voxels (15 mm), a slab of a plane is 8 % of its larger side, a shape too big for the work gets larger voxels (`ROADMAP.md`, section 4). |
| Primitive voxelization formulas (cube/cylinder/cone/sphere/torus/disc/plane) | **done-and-tested** | `RegressionPrimitives`: ratios match pi/4, pi/12, pi/6, ~0.155. |
| Anisotropic octree subdivision for thin geometry | **done-and-tested** | True per-axis splitting (the old entry said "not a full fix"; the later non-cube entry did it). Thin panel: 3,916 regions. |
| Taper applied during voxelization | **done-and-tested** | `RegressionTaper` (3), `RegressionTaperWide` (8): a widening taper - four pairs of end sizes, as a cylinder and as a cube - voxelizes to within 2% of the exact frustum volume. |
| Bend / shear applied during voxelization (twist and curve do nothing on the GPU, so nothing here) | **done-and-tested** | Through `ForwardVoxelizer` (see above), which carries every cell of the surface through the same maps as the shader. The ray that picks the target of a hit still tests the unmodified box (`ROADMAP.md`, backlog item 12). **Question for you**: the older "Bend modifier - proper fix" entry asks for a Blender-style loop cut + Simple Deform for how a bent shape is *drawn*; the newer one solved how to *voxelize* it. Different problems - does the on-screen bend still need the rework? |
| Crater cost / out-of-memory at wide radius | **done-and-tested** | `RegressionCraterOOM` (16) under `-Xmx512m`. |
| Per-voxel texture colour, the right way round | **done-and-tested** (the mapping) / **done** (the drawing) | The report: the texture came out mirrored once the object was voxels. The voxel colouring chose its own axes while the shader runs the picture along different ones on different faces; `VoxelTextureMapper` now runs the shader backwards (`RegressionTextureMapper`, 361; `mutants_texture_mapper.py`, 148). A negative `uvScale` is a mirror of the picture. Colours are read per corner of a voxel face and interpolated, not per fragment. How it looks in the window: **reviewed**. |
| A picture turned by quarter turns: the material key `uvRotation` (degrees, multiples of 90) | **done-and-tested** (the picture every reader is given, the voxels of a shot object included) / **done** (the drawing on a GPU) | `RegressionTextureRotation` (286; `mutants_texture_rotation.py`, 99). The picture is turned **once, where it is loaded**, so the texture arrays of the tessellated shapes (the four maps alike), the classic mesh renderer and the voxels of a shot object read the same pixels, whatever the scale, the mirror and the file it comes from. The turn is counter-clockwise as seen on the surface and comes after the scale, like Blender's Mapping node. Only multiples of 90 (a free angle would be a job for the shader at every pixel); the vectors stored in a normal map are not turned (Blender does not either). `PBD_FORMAT_SPEC.md`, "Mapping a texture: scale, mirror, rotation". |
| Textured-primitive G native crash (`0xC0000374`) | **pending** | Mitigated several times, never reproduced here, never confirmed fixed. Needs the console output of a real press. |
| Shared/cached shader program; NaN hit distance on hidden instances; click-toggle of hidden voxel debris; `destroyedRenderers` cleared on scene switch | **done** | Re-read against the current code; GL-bound, not executed. |
| "Rays pass through voxels" | **pending** | Never reproduced; the one real bug found (fallback to the camera position) is fixed. |
| Known limits (what-stays-attached heuristic, pieces and chips don't collide with each other, landing surfaces, the break is a rule about width) | documented | `docs/ROADMAP.md`, section 4. |

## Blender add-on (Python)

| Item | Status | Note |
|---|---|---|
| Child of a canonical primitive exported at the right size/offset | **done-and-tested** | Was exported two times off; fixed. **Re-export old scenes that have parts parented to a primitive.** |
| Importer keeps `leverArm`; embed anchor is a `group`; guard rails (keyframes win, `indestructible` on a ref not exported) | **done-and-tested** | `blender_addon_check.py`. |
| Add-on's own minifier quotes metadata | **done-and-tested** | See the format table. |
| `upload_server.py` `ModuleNotFoundError` (import path) | **done** | The import is `from .. import bl_info` from the `operators` package; cannot run an upload here. |
| "No files submitted" on upload | **pending** | A server message; only a real upload to the server can test it. |
| `ui_panel.py` draw-time `AttributeError` on keyframe-sound creation | **done** | The write moved into an operator; reviewed, not run in Blender. |
| File metadata box: name, kind, authors, origin, **description** (export, import, pretty and minified) | **done-and-tested** | The description field is new (it had none). The add-on's own quoting did not quote a `#` or a tab as `PbdSerializer.quoteIfNeeded` does, so a one-word name like `C#` came back as `C`; fixed and checked against the engine (`blender_addon_check.py`, a File-box scenario of six texts, pretty and minified). |
| Object names the format cannot hold (`front door`, `a=b`, `a{b}`, a quote...) | **done-and-tested** | The exporter wrote `cube front door {` and the engine refused the whole file. Every id written is now cleaned up (`_` for each unusable character, a clash numbered `_2`) and the export lists what changed; the importer reads every id the engine reads (`wall-left`, `a:b`). A material name with a space cannot be written at all (the line `mat = oak dark` has no escape): it is reported, not changed. Re-export a scene whose parts have such names. |
| Texture coordinates as the engine's, **Refresh UV Mapping**, **Mirror U / Mirror V** | **done-and-tested** | v0.46.0. `uv_layout.py` gives each preview mesh the engine's coordinates, compared point by point with the engine's own evaluator of the shader; the mirror buttons write a negative UV scale. `mutants_addon_uv.py` (176), `mutants_addon_uv_real.py` (57, real Blender). The window itself: **reviewed**. |
| **UV Rotation**, **Rotate Left / Rotate Right** (v0.47.0), in one **Texture mapping** box with the Mirror buttons and a sentence that says how the picture lies (v0.48.0): a texture mapped the way the engine maps it | **done-and-tested** (on the fake `bpy` and on real Blender) / **done** (Blender's window) | The row turns the picture of a material by 90 degrees; the Mapping node's Rotation Z is *minus* the angle (Blender's node turns the coordinates, the engine the picture), and the Save button, the exporter and the importer carry `uvRotation`. The test renders a plane with the node in **Cycles** and compares 24 x 24 cells with the engine's own turned picture, for four angles and five scales (mirrors included). `blender_addon_check.py` (891), `mutants_addon_rotation.py` (72), `mutants_addon_rotation_real.py` (20), and, for the box, the sentence, the note about Solid shading and the marks of the list, `mutants_addon_mapping_ui.py` (90). Blender's Solid view shows none of it (it ignores the Mapping node): the box says so and offers *Preview Material*. |
| The click of a light (**Click Sound**) | **done-and-tested** | Exported as `switchSound` by file name, read back, read by the engine, sent with an upload. `mutants_addon_light_click.py` (21). |
| What the add-on will fetch, import and upload (addresses, bundles, budget, file names and sizes) | **done-and-tested** | `addon_safety_check.py` (417) and the hostile-file scenarios of `blender_addon_check.py`, the engine's rules compared with the add-on's on the same addresses; `mutants_addon_safety.py` (1907). `docs/ADDON_USER_GUIDE.md`, "Upload and import rules". |
| Export a `.pbdmat` on its own; show a remote `.pbdmat` in the Material panel | **not done** | Not attempted. |
| `.pbdasset` of ~29 MB | **pending** | Not investigated; needs a real asset. |

## Build / dependencies

| Item | Status | Note |
|---|---|---|
| JOML `Vector3fc` classpath error | **pending** | Reported fixed through the `printRuntimeClasspath` task; `build.gradle.kts` is not in the upload, so it cannot be re-checked. |

## Facade / modding API

| Item | Status | Note |
|---|---|---|
| `Main.availableVolume` / `Main.canHide` | **done**, with a known defect | Static methods next to `getArmValue`; no suite in `tools/regression/` calls them. Reading them on 2026-10-08 showed that `availableVolume` subtracts the items of **every** open container, not only of the one asked about (`docs/ROADMAP.md`, backlog item 21) - not fixed yet. |
| Is the door of a hiding place closed, and untouched, so that someone inside cannot be seen? (`Main.isHideoutConcealing(id)`, `getHideoutState(id)`, `isPlayerConcealed()`) | **done-and-tested** (the rules) / **done** (the three getters and the key in a running window: reviewed) | `RegressionHidingRules` (189; `mutants_hiding_rules.py`, 277) with `RegressionSlabOpenings` (39). Concealing: every door fully closed **and unaltered**; a door is the listed instance that can move together with everything hanging from it; a door a hit has reached at all is open, however small the damage (no threshold), so a player hiding behind it is seen from outside. |
| Nobody gets in or out of a hiding place through a closed door (H refused, with a message) | **done-and-tested** (the verdict) / **done** (the key handler: reviewed) | Shut: every door fully closed and unaltered; a closed door that is locked still keeps it shut. The player drags the door open first (the mouse still grabs while hiding: reviewed, not run). |
| An emptied container stays empty when its door is opened again | **done-and-tested** (the rules) / **done** (the wiring in `Main`'s frame loop: reviewed, not run) | `RegressionContainerStock` (33; `mutants_container_stock.py`, 20): `ContainerStock` loads a group's contents on its first opening only, puts them on a shelf when every door is shut and gives the same contents back, so what the player took stays gone. Until the scene changes or the viewer restarts - nothing is saved to disk. The renderer of a taken item is released as well (it used to leak). The other half of the old request, a better **distribution of items across linked containers**, is **not done**. |
| H-key exit (inverse of how the player got in) | **not done** | Needs reading the hide-transition code first. |

## Audio

| Item | Status | Note |
|---|---|---|
| E plays `resources/sounds/item-equip.mp3` when an item comes out; G plays the shot and the material's break sound | **done-and-tested** (what plays, when, and that a missing or undecodable file only logs) / **pending** (the files; a real sound card) | `RegressionGameSounds` (51; `mutants_game_sounds.py`, 92). **The sound files are not in the upload**; the real `item-equip.mp3` has never been through the decoder, and nothing has been played on a sound card. |
| E switches the lights of the model under the crosshair, with a click (`switchSound`, else `light-switch.wav`) | **done-and-tested** (the rules) / **done** (the handler in `Main`: reviewed) | `LightSwitch`, `UseKey`, `GameSounds.forLightSwitch`: `RegressionUseKey` (158; `mutants_use_key.py`, 151). The model rule, the link group, the room switch, the nearer-wins arbitration with an item, the click. `light-switch.wav` is written by `tools/scripts/make_light_switch_wav.py`: it decodes, nobody has listened to it. |
| MP3 decoder (`pbd.audio.Mp3Decoder`, a Java port of minimp3, Layer III only) | **done-and-tested** | `RegressionMp3Decoder` (224; `mutants_mp3_decoder.py`, 134): bit for bit with the C original on 25 recorded files, 35 of 36 derived streams, 26 crafted ones and 4244 of 4500 random streams (every difference is the one deliberate one); within one 16-bit step of the ISO conformance streams. The record is `TEST_PROCEDURE.md`, section F. |

## Server, domain and upload security

| Item | Status | Note |
|---|---|---|
| The site's code (`upload.php`, `moderator.php`, `search.php`, the libraries) | **done-and-tested** (run here on PHP, Apache and nginx) / **unknown** (the real host) | `TEST_PROCEDURE.md`, section H: 6 PHP suites (2446 checks), the whole site through a real web server (`server_e2e_*`), five mutation specs (`mutants_server_*.py`). Moderation accepts a `leverArm` block, `switchSound` and every other key of the format. |
| The domain is `pbd.aptheo.fr` | **done-and-tested** | One constant in each program (engine, add-on, site), compared with each other by `tools/regression/check_site_host.py` (a guard `run.sh` runs; it also fails when any other host of the kind `pbd.<name>.<tld>` is written anywhere in the project, and `mutants_site_host.py` (38) breaks it on purpose); the old name is trusted nowhere and has no alias; `server/migrate_domain.php` rewrites the references a library already holds (**reviewed**, not run on a real library). |
| What a file may ask of the program: addresses, redirects, sizes, include depth, instance count, bundles | **done-and-tested** | `RegressionFetchPolicy` (675), `RegressionFetcher` (229), `RegressionIncludeLimits` (213), `addon_safety_check.py` (417); `mutants_fetch_policy.py` (532), `mutants_fetcher.py` (247). `ROADMAP.md`, section 1 item 25. Not covered: DNS rebinding between the check and the connection. |
| The upload: names, kinds, sizes, hidden data in pictures and sounds, metadata, references, include chains, rate limits, the moderator's session | **done-and-tested** | Every picture and sound is rewritten without its metadata (EXIF, GPS, XMP, ICC, comments, text chunks, anything after the end): the server's six suites and the e2e runs, and a run on 2026-11-14 on 18 files made by other tools, none of which kept anything but its pixels (`ROADMAP.md`, item 36; not in `run.sh`); not removable: what is hidden in the pixel values; `mutants_server_http.py` (340), `mutants_server_metadata.py` (45), `mutants_server_sanitize.py` (854), `mutants_server_upload_lib.py` (242), `mutants_server_moderate_lib.py` (207). The `.pbd` lint is not yet compared with the engine's parser on thousands of generated files (backlog item 28). |
| `tutorial.php`: reuse, quality submission, lever arm, light switch, upload rules, "re-use existing resources with `include_material`" | **done** | Wording **reviewed**, not run through a person. |
| A viewer in the browser | **not done** | Planned for the next delivery (`ROADMAP.md`, backlog item 20). |

## Documentation

| Item | Status | Note |
|---|---|---|
| `PBD_FORMAT_SPEC.md` matches the parser | **done-and-tested** | `RegressionSpecExamples` (183) parses every `<!-- check: ... -->` example with the real parser, pins the lever-arm examples (the spec's and the add-on guide's) to the numbers in the text, compares the spec's table of how a material breaks and the constants it quotes with the code, runs its linked-lamp scene and asks the engine's `LightSwitch` which lights each part of the lamp example switches; `mutants_docs.py` (273) breaks them on purpose; it also loads the spec's three whole scene files through the engine and holds them, and the layout of the stress report, to the numbers the text states. Prose around the examples is reviewed, not tested. |
| `ROADMAP.md`, `ENGINE_DEV_GUIDE.md`, `ADDON_USER_GUIDE.md`, `TEST_PROCEDURE.md`, `tools/blender-addon/README.md` | **done** | Brought up to date on 2026-10-25 (the scene description file, the stack defect and its guards, the new counts; on 2026-10-20: voxelization as drawn, the E key, textures and mirrors, the altered door, the domain, the upload rules; the add-on guide has the lever-arm / pivot section and a draft of the tutorial text). |
| The numbers the docs quote (checks per suite, mutants per spec, the totals) | **done-and-tested** | `tools/regression/check_doc_counts.py`, the last step of `run.sh`, counts the `PASS:` lines and the mutants and fails on any document that quotes another number or a total nobody can match. It first checks itself on a made-up document (`--self-test`), and `mutants_doc_counts.py` (45) breaks the checker on purpose. |
| Web visualizer; rewrite of the site's index page | **not done** | The viewer is planned for the next delivery (see above). |

## Headline findings of the 2026-11-14 session

You asked three things, with the screenshot of the same Blender error. **The `.pbdbin` that still did not export**: the message `PbdConvertMain failed` is not
in any add-on from v0.48.0 on - the button writes the file itself and starts no program - so the Blender in the screenshot ran **older code**. Blender
keeps an add-on's modules in memory; a newer zip installed over a running add-on changes the files and leaves the old code running, and switching the
add-on off and on reloads only the package, so the panel's first line (read from `bl_info`) names the new version over the old buttons - which I had
told you to look at, and it proves nothing. Reproduced on real Blender 4.2 and 5.1. v0.48.2 lets go of the old modules when the add-on is loaded again, a test
installs two copies through Blender's own installer and looks at what Python holds (4.2, 4.5, 5.0, 5.1, 5.2), another checks that neither button
starts a program even with a `java` that fails first on the PATH. **To update, whatever the version**: install the zip, tick the add-on, quit Blender completely and
start it again; File > Export then lists *PBD Binary (.pbdbin)* and *PBD Asset bundle (.pbdasset)*. Not verified: that this is what happened on your machine (a
screenshot does not name the add-on that printed it; an older zip installed by mistake gives the same message). **The zips**: the delivery folder had
kept the add-on of every earlier round beside the new one, and the archive of `tools/` carries its own copy and its zip - one add-on zip per delivery from now
on. **The site and the metadata of pictures and sounds**: it rewrites every picture and sound, keeping only what draws or plays it (no EXIF, GPS, XMP, ICC,
comment, text chunk or trailing data); checked again on 18 files made by other tools, none kept anything but its pixels. It does not remove what is hidden in
the pixel values, and the files of a library that predate the sanitizer are found by `php check_library.php`. The site takes `.pbd`, `.pbdmat`, `.png`,
`.jpg`, `.jpeg` and `.wav` only - not `.pbdbin` or `.pbdasset`; the add-on's *Upload* button sends the `.pbd` text. Details: `docs/ROADMAP.md`, section 1 items 35 and 36.

## Headline findings of the 2026-11-10 session

You said two things. **The `.pbdbin` export** (the error was `UnsupportedClassVersionError` on `PbdConvertMain`): the button ran a Java found on the PATH, older than the Java 21 the engine is compiled for. It no longer runs Java - the add-on writes the `.pbdbin` and the `.pbdasset` itself, and the engine reads what it wrote; the limits are applied before the first byte, the pictures of the materials come with the file, a name that is nothing is refused (the first version of this fix wrote a hidden `.pbdbin` in the folder Blender was running in; a mutation round found it). **The UV rotation in the add-on**: it has been there since v0.47.0 (*UV Rotation*, *Rotate Left / Right*), but as a row among others and **invisible in Blender's Solid view**, which ignores the Mapping node; v0.48.0 puts rotation, mirror and a sentence (`Now: Turned 90 degrees counter-clockwise, mirrored left-right`) in one **Texture mapping** box and, when a viewport is in Solid, says so and offers *Preview Material*. Real Blender renders of the buttons, next to the engine's own picture, agree in every cell. Not verified: Blender's own windows (the file browser and the panel were never seen by me), and which add-on your report was made with (the next report showed that the panel's first line is not proof: see the findings of 2026-11-14). Details: `docs/ROADMAP.md`, section 1 items 33 and 34.

## Headline findings of the 2026-11-09 session

You asked three things. **The domain**: every source of the last delivery already named the new host (the add-on from 0.46.0, the server, the
engine) and `check_site_host.py` keeps it so; the former name is found only in older zips (the add-on 0.43.1 to 0.45.0, `pbd-benchmark.zip`),
and an add-on or a server folder copied from one of those still says it. The add-on's panel title (`PBD Tools v0.48.2` now - but see the findings of 2026-11-14: after an update in place the title is not proof, **File > Export** listing the two compiled formats is) and the server's
`current_version.txt` tell which one you have. **A texture mapped properly in Blender**: the material can now be turned by steps of 90 degrees
(**UV Rotation**, **Rotate Left / Rotate Right**, the key `uvRotation`), in Blender and in the engine alike, voxels of a shot object included; the
Blender node is not trusted - a render in Cycles is compared, cell by cell, with the engine's own picture. **The viewer that stopped on
`Could not create the GLFW window`**: the window used to make one request and stop; it now makes up to nine (six on a plain X11 desktop), in the order a machine is most
likely to accept, and says which one worked or why none did. Not verified: the window on a real machine (the cause on yours is a guess from the
log), and the drawing of a turned picture on a GPU. Two things the checking found on the way: a mistake of mine in `run.sh` (two suites' names
written together, so those two suites would not have run) and two mutants of the add-on's rotation that survived the first round, each a
check that was missing. Details: `docs/ROADMAP.md`, section 1 items 30 to 32.

## Headline findings of the 2026-10-25 session

You asked (the same request, sent again until 2026-10-25) for a scene-description format, so that all your models can be placed, the
presentation video shot in one take and stress tests run from the same file. It is in: a `.pbdscene` of a few lines expands to an
ordinary `.pbd`, with a show (camera path, keys, doors, the sun, `quit`) and a stress report; the viewer opens it like any other file. What
the checking found on the way: a scene of 600 models killed the viewer in its first frame (it took two 64-byte matrices per model from
LWJGL's 64 KB native stack, which gives memory back only at the end of the frame, so about 512 models filled it) - fixed, and kept fixed
by a reading of every source and a run of the real viewer on a software renderer, each broken on purpose by mutants of its own; the
mutation round on the format found what the checks could not see (a directive that took any key, a tuple read without its parentheses,
a pattern whose `?` meant "anything", folder entries that differ only by case ordered by the luck of the file system); the round trip's
completeness guard failed on the first field that is meant to be left out of the files (the show) and now proves that no format writes
it; and a test of the site failed one run in thirty because the page's random nonce holds the letters `OR` by chance - the site was right,
the test now looks for the name itself. Policies you may want the other way, in `docs/ROADMAP.md`, section 1 item 26 and the journal: ids
from 1, the front of a model is +Z, a ring starts on the +Z side and goes toward +X, a grid counts x, then y, then z, the show's clock is
capped per frame so a slow machine plays the same take more slowly. Not verified: a take on a GPU with your models and the project's own
shaders, how fast a real card draws a forest (backlog 32), a very large texture set (backlog 33), and the sound of the click.

Later the same day the mutation specs were read against the code for the first time (`check_mutant_specs.py`, now a step of `run.sh`): 27
mutants in five specs applied to nothing - among them the four that guarded the refusal of a private address and the four that guarded an
IPv4 address inside an IPv6 one -, they are rewritten, the rules against an address written in octal, in hexadecimal or as one number have
mutants for the first time, and what those mutants left alive showed fourteen checks missing in `RegressionFetchPolicy`. One survivor was
the fake `bpy` accepting a text property set to `None`, which Blender refuses: the fake refuses it now. Which specs have been run to the end,
and on which code, is `TEST_PROCEDURE.md`, section E.

## Headline findings of the 2026-10-20 session

You asked (2026-10-18, sent again three times) for: a hollow cylinder - the shade of `desklamp.pbd` - that stays hollow when shot, every
parameter of a primitive honoured by the voxel conversion, a way out for a triangle (`let_triangle` answered "can't be voxelized"), an
E key that switches the light of a model with a click you can set in Blender, a texture that is not reversed when an object becomes
voxels and a way to mirror it in the add-on (and textures that look right in Blender), a container whose door was altered counted as
open, the new domain everywhere, strict upload rules, a viewer in the browser and a scene-description format. All of it is in except
the viewer in the browser (**not done**: backlog 20, next delivery); the scene-description format came on 2026-10-25 (see above). What the checking found on the way: the voxelizer that existed never
looked at `cap`, `faces`, `bend` or `shear` (the lampshade was a full cylinder because its classifiers saw a box); the voxel colouring
chose its own texture axes, so a locker's handle changed sides when it was shot; a lattice laid from the object's origin lost the ends
of a tube, summing the coverage of the faces of a box filled a frame along every edge, and a slab's faces falling inside a layer left it
speckled (a leaf of 0.8 m: 264 voxels of 1264) - each pinned by a check that fails without the fix; the site answered `/search.php/x.png`
with the page on some set-ups, and its refusal announced the version of PHP; two pages of the site had inline styles its own content
policy blocks. And some of **my own earlier statements were wrong** and are corrected: the add-on guide claimed the add-on checks
more than it does (it does not read the text files before sending: backlog 31), advised an upload the site does not accept, and
called a material source "Shared" that the site refuses. Policies you may want the other way, all in `docs/ROADMAP.md`, section 1
(items 18-26) and the journal: a hollow shape is a wall of three voxels (never over 40 % of what it grows across), a plane a slab of
whole layers, half a voxel fills, a shape too big is voxelized with larger voxels rather than cut, an open mesh is a wall, what cannot
become cubes is destroyed rather than refused, E takes the nearer of an item and a model (the item on a tie) and switches a whole room,
a negative UV scale mirrors, an altered door is open however small the damage, and the former host name has no alias. Not verified: the
GL drawing of the new shapes, the E handler and the H key in a running window, the click of the lamp (a generated file nobody has
listened to), the real host, and DNS rebinding.

## Headline findings of the 2026-10-12 session

You asked for: a toggleable light and lever arms in the add-on, the pickup sound on `item-equip.mp3`, particles out of the crater
instead of deleting it, an effect defined by the material, a break when an attachment is too small, and a facade getter for the
door of a hiding place (and no coming in or out through a closed door). All of it is in; what the checking found on the way: three of
the new checks of the destruction world could not fail (a second shot at the middle of the fallen piece left no piece, so "it is
awake" and "it settles again" were `allMatch` over an empty list) - the shot now aims at one end and the voxels are added up; the add-on's
mutation spec had gone stale (two patterns no longer matched the panel code - reported, repaired, killed); a test
that quotes a `static final` constant cannot see it mutated (`javac` copies the number), so such tests ask the running program; a
test that looked for `case X ->` anywhere in `Main` was satisfied by any other `switch`; a field of a particle's sub-step that nothing
read was removed rather than tested; the MP3 decoder's mutation sweep left four real gaps, all at the end of the data, each now
closed by a crafted stream and compared with the C original; and real Blender showed that a reference into a collection goes stale
after the next `add()` (it read another entry's defaults on about one run in a few on 4.5), which the check now guards against by
finding its entry again by position. Policies you may want the other way, all in `docs/ROADMAP.md`, section 1 (items 12-17) and the
journal: a linked instance that cannot move is not a door; a closed door that is locked still keeps a place shut; a linked light
ignores its own `lightEnabled` and is on while the door is open (no inverted polarity); the player opens the door by hand before H
works; a chip count follows the volume the hit removed (a post shot through throws about twenty - `debrisCount` of the material tunes
it). Not verified: the GL drawing of the chips, the H refusal and the getters in a running window, any sound through a real sound card
and the real `item-equip.mp3`.

## Headline findings of the 2026-10-08 session

Shooting a **mesh** instance had never been tried, and was broken in four ways (found by a probe, then pinned by
`RegressionMeshVoxel`): a closed cube mesh lost 21 % of its volume to rays that crossed a face's diagonal; a
scaled mesh stood in the wrong place (the scale was left out of the grid origin); a flat or open mesh became a
block over a metre thick; and the shot's broad phase tested a mesh against the canonical unit box instead of
its own vertices, so a mesh far from its origin could not be hit. The documentation checker also had a bug of its
own (it took a closing code fence for an opening one and reported prose as an unchecked example) - fixed and
checked on a made-up document. Stale comments that contradicted the code in four places were corrected. In the
Blender add-on, a probe found that an object name with a space produced a file the engine refuses, and that a `#` or a
tab in the File box text was not quoted as the engine's writer does; both are fixed, with a Description field added to
the box, and the add-on's mutation run (74 mutants) found a gap of its own in the embed path - an embedded lever arm
that was `locked` was never compared - closed by a locked, two-sound arm in the fixture.
The numbers the docs quote are now compared with the run; its first pass over the finished state reported 24 that the day's
changes had made stale (`138` for a suite that has 147, `111` add-on checks where there are 208, ...). Last, a container the
player has emptied no longer refills itself when its door is opened again (`ContainerStock`), and reading the neighbouring
`availableVolume` turned up a separate defect that is listed, not fixed.

Later the same day the add-on was run on **real Blender** (Blender as a Python module, `pip install bpy`: 5.2.2 at first, then 4.2,
4.5, 5.0 and 5.1, 248 checks passing on each) for the first time, and the fake it had been checked on was extended to draw its panels. Findings, all fixed and pinned by a check that
fails without the fix: a script that edited objects and exported at once got the positions from before its edits (Blender
refreshes its matrices between two clicks, not between two lines of a script), and a scene imported then exported in
one script came out with a door panel three units off; an import with one unreachable `pbd_ref` (no network, or a 404)
stopped with a traceback - the failed fetch was a plain `Exception` where the importer catches `OSError` - instead of leaving an
empty placeholder and a warning; the sidebar's first line said `v0.41.0` whatever the version (it now reads `bl_info`); the fallback for the materials folder looked four folders up instead of five; the user guide described a
"PBD Project Root" field and an Export button that no longer exist, and said the add-on and the engine share one remote
cache (they keep two). Of the 37 buttons pressed once on real Blender, only Import PBD (the crash above) died with a Python exception; the other 36
pass on a plain scene and with a `mesh` primitive selected.

## Headline findings of the 2026-10-07 session

Real bugs the new checks found (all fixed; details in the roadmap journal): six data-loss bugs in the
writers (curves, includes, quoting, placed files' materials, `.pbdasset` bundling); a Blender export bug
(children of a primitive two times too large/far); the importer dropping `leverArm`; a lamp left behind by
the door it hung on; `setParent` not really re-parenting; `pbd_ref` copies silently dropping keyframes, arms,
lights and meshes; the parser rejecting bare base64 `vertexData`; the AI accessor missing from the facade
mods call; and two documentation errors (a curves example in a syntax the parser does not read, a
lever-arm section that knew none of its new fields).
