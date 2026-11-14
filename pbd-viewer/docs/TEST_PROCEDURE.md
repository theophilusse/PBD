# Test procedure

Run this whenever a change touches the format, the engine, the Blender add-on, the upload path or the
documentation - not just when a bug is reported. Almost all of it is one command:

```sh
JOML_CLASSES=/path/to/compiled/joml LWJGL_DIR=/path/to/lwjgl/jars sh tools/regression/run.sh
```

`run.sh` compiles the whole engine tree and every `tools/regression/*.java`, runs the Java suites (each under a
512 MB heap), then the PBD site's PHP suites and its end-to-end runs (section H), then the Blender add-on check, the add-on's
safety check and the add-on check on real Blender, then checks that the project names one host everywhere
(`check_site_host.py`, section D), that every mutant of every mutation spec still applies to the code it breaks (`check_mutant_specs.py`, section E), that nothing is taken from LWJGL's memory stack inside a loop (`check_gl_stack.py`, section I), runs the real
viewer on scenes of the size nobody tries by hand when the machine can open a window (`gl_runtime_check.py`, section I) and that the numbers the docs quote are the ones just
counted (section D), and ends with `ALL REGRESSION SUITES PASSED` or `SOME REGRESSION SUITES FAILED`. A `FAIL` line is a failing suite; so is a suite that does not end in `ALL PASS`.
What is counted needs no GPU, no display and no Blender: the one stage that opens a window (`gl_runtime`, section I) uses a virtual
display (Xvfb) and Mesa's software OpenGL, and prints `SKIPPED` - succeeding - on a machine without them. The only inputs are JOML (compiled from source - the GitHub sources
need their `//#ifdef __GWT__` blocks stripped and the `org/joml/jre` and `org/joml/experimental` packages left out)
and the LWJGL 3.3.3 jars (to compile against; the Java suites never load their natives, and `gl_runtime` finds them in the `natives-linux` jars of
`LWJGL_DIR`, or in the folder named by `PBD_GL_NATIVES`). The add-on check also needs
`pip install mathutils` (Blender's own maths library) and prints `SKIPPED` - succeeding - without it. The same check
can also run on **real Blender** (`pip install bpy`: Blender as a Python module, a wheel of about 400 MB); `run.sh` does it
when `bpy` is importable and prints `SKIPPED` - succeeding - when it is not. The same goes for the site: its suites need
`php` (with GD) and the folder `server/` (`PBD_SERVER_DIR`, else `server/` in the project, else the one beside it - it is
shipped as `pbd-server.zip`, not inside this project), the runs through Apache and nginx also need those installed and `root`
(they start workers as `www-data`); each prints `SKIPPED` - succeeding - when something is missing, and the numbers the docs
quote for it are then not compared.

Three words are used in the docs and mean what they say: **verified** (a suite below ran the real code and
passed), **reviewed** (read line by line, nothing could run it here) and **unknown** (not available here).

## 0. What is checked

| Suite | Checks | Guards |
|---|---:|---|
| `RegressionPrimitives` | 7 | voxel volumes of every primitive against the exact formulas |
| `RegressionTaper` | 3 | taper in voxelization against the exact frustum volume, narrowing |
| `RegressionTaperWide` | 8 | the same, widening |
| `RegressionPerVertexColor` | 2 | colour sampled per vertex of a voxel face |
| `RegressionCraterOOM` | 16 | crater cost and memory under `-Xmx512m`, three shapes, double radius |
| `RegressionVoxelCrater` | 26 | the crater is a solid, deterministic, irregular volume; its cells tile it exactly; the voxel under the impact is always taken (600 impacts at radii of 30 down to 2 voxels) |
| `RegressionVoxelConnectivity` | 17 | face-connected pieces after a cut |
| `RegressionVoxelMesh` | 10 | meshing of a voxel piece |
| `RegressionBreakProfile` | 76 | how a material breaks: the ten `break*` / `debris*` keys of a `.pbdmat` read into a `BreakProfile` - what each material name starts from, every range, a refused value and the warning that says so, a mistyped key - through the real `.pbdmat` parser and `PbdScene.materialOverrides` |
| `RegressionVoxelFracture` | 59 | the break: a connection thinner than the material's `breakWidth` shatters and the piece it held comes away - hand-made situations with exact voxel counts, a brute-force reference on random solids and craters, the cap of the analysis window |
| `RegressionRigidDebris` | 20 | a piece falls, lands on the floor / remains / boxes and sleeps |
| `RegressionVoxelDebris` | 44 | what a hit leaves: what stays (grounded, or the largest), what falls (at most 6 pieces of 400 voxels or more), what is dust (thrown as particles), the body a piece becomes and the kick it gets |
| `RegressionParticles` | 148 | the chips a hit throws: `DebrisEmitter` (how many, from where, how fast, in what colour, the same twice for the same seed) and `ParticleSystem` (gravity, air drag, bounce, friction, rest, the cap of 1500, no tunnelling) against the continuous equations; the drawing maths of the one shared cube |
| `RegressionShaderPort` | 85 | `ShapeEval`, the Java port of the tessellation shader, against the GLSL: 1700+ points of every primitive, with and without modifiers, computed from the shader's text by an independent port in Python (`fixtures/shader_points.txt`) and reproduced within 1e-9 in position AND normal; and the geometry the shader's own comments promise (a bend keeps the length of its centre line, a taper is linear in the height, the six faces of a cube meet along its edges, twist and curve change nothing) |
| `RegressionVoxelShapes` | 106 | every parameter a primitive is drawn with survives the conversion to voxels - `cap`, removed `faces`, `smooth`, taper, bend, shear: each point of the surface the shader draws is covered by a voxel, no voxel sticks out past it, a solid is filled behind it and a hollow one stays hollow, volumes against the geometry's own formula; an object larger than the octree is voxelized whole with larger voxels |
| `RegressionVoxelLattice` | 23 | WHERE the voxels of a hollow or modified shape fall: the lattice laid from the shape's own corner, the volume of a cell shared between the voxels it overlaps, walls that overlap taking the largest coverage and not the sum, a plane or a disc a whole number of layers thick on both paths - each expectation derived from the size in voxels, never from a recorded output |
| `RegressionVoxelLimits` | 16 | what the conversion must survive (about 20 s): a drum two metres across at 3 mm, objects from 1e-20 m to 3e38 m, voxel sizes from the smallest float to the largest, a bend of six radians, a shear of 30, a taper from 100 to 0.001 - larger voxels (`coarsened()`) or nothing, never an exception, a hang, a negative size or a cut object |
| `RegressionMeshVoxel` | 130 | a `mesh` instance as the target of G: a closed one filled and placed where it stands, one that encloses nothing (a triangle, a quad, an open box) voxelized as a thin wall, found by the shot, destroyed end to end; meshes whose arrays cannot be read refused |
| `RegressionTextureMapper` | 361 | the colour of a voxel face is the texel the shader would have drawn there: the shader's mapping run backwards, held to the same points as `ShapeEval` (a locker's handle stays on the left), the triangles' UVs for a mesh, a negative UV scale mirrors the picture, the modifiers carry it with them |
| `RegressionMirror` | 54 | an object scaled by a NEGATIVE number is its own mirror image: for thirteen shapes on the seven combinations of axes the grid is the reflection of the one of the positive scale; the world matrix of a destroyed instance cut into a rotation and a signed scale; a scale of zero, infinity or not a number refused; the texture of a mirrored object |
| `RegressionTextureRotation` | 286 | a picture turned by quarter turns (`uvRotation`): the turn against pictures worked out by hand and the laws of a rotation (four quarter turns are nothing, no pixel lost or copied, the input untouched); the turned picture read at (u, v) is the original read at the point (u, v) is turned to, for every size, turn and scale (mirrors and repeats included) - which is what Blender's Mapping node shows; the voxels of a box coloured from it; the key (what is read, what is refused, what is reported); the pictures a scene needs (a file decoded once however many turns of it, a file turned two ways is two pictures, nothing kept from one plan to the next); and the lines of the renderers that read the turns, as text |
| `RegressionSlabOpenings` | 39 | a shot door measured: the footprint of the slab, which columns are open, the integrity and the widest square of opening, against a brute-force implementation on random slabs and damage |
| `RegressionDestructionWorld` | 172 | the whole flow: G -> crater -> break -> what stays -> what falls -> chips -> rest, follow-up hits, caps, leaks, what a hit says of itself, the openings of a shot door, and an object that cannot be turned into cubes (an open triangle, a shape with every face removed) destroyed outright, with a burst of its own |
| `RegressionWorldDebris` | 60 | the world against a replay of its parts: what a hit makes is exactly what the crater, the break, the sorting and the emitter make when driven by hand from the same inputs |
| `RegressionHidingRules` | 189 | what hides someone: a door is shut when it is fully closed AND unaltered (a dent, a hole, a handle shot off or a door turned to voxels opens it), concealing and shut as "every door shut", the H verdicts, which instances belong to a door, the places of a scene, the player's state; the wiring in `Main` and `PbdRenderer`, read from the source |
| `RegressionContainerStock` | 33 | what a container remembers between two openings: loaded on the first opening only, put on a shelf when every door is shut, given back as it was left (an emptied container stays empty), forgotten on a new scene |
| `RegressionMinifiedMetadataQuoting` | 6 | minified text keeps free-text metadata apart from the rest of the file |
| `RegressionFormatRoundTrip` | 105 | one scene with EVERY field through pretty `.pbd`, minified `.pbd`, `.pbdbin`, `.pbdasset` (a material that sets all ten break keys, a lamp linked to a door, a light with its `switchSound`...); the one field the files leave out on purpose, the show of a scene description file, is proved to be written by none of three formats (pretty text, minified text, `.pbdbin`: the same bytes with it and without it) and given back by none |
| `RegressionPbdRefCopy` | 248 | a placed file (`pbd_ref`) brings everything over, prefixed and re-parented |
| `RegressionIncludeLimits` | 213 | how much a scene file may ask of the engine: a file that includes itself, two that include each other, a chain ten deep, ten copies of ten copies (a billion instances from a page of text), too many instances, a file cut off in the middle of a block, a compressed scene that expands to gigabytes, a bundle with entries named `../../..`, a scene that names three hundred files to fetch |
| `RegressionFetchPolicy` | 675 | what a scene may ask the network and the disk for, line by line, with no network: only https, no user name in an address, no address of the person's own network however it is written (dotted, one decimal number, octal, hexadecimal, short, IPv6, IPv6 around an IPv4), no network path on the disk, a reference of at most 2048 characters, a read that stops at its cap |
| `RegressionFetcher` | 229 | the fetcher against a real server on the loopback address: an address the policy refuses is never requested, a host that is not the project's own is put to the person first, a redirect is not followed, a download stops at its cap and leaves nothing in the cache, a host name that points into the person's own network is refused |
| `RegressionLeverArm` | 45 | the `leverArm` block: parse, round trip, interpolation, clamping |
| `RegressionLeverArmSystem` | 147 | the run-time system: value, lock, release, sounds, grab, drag; the `Main` facade |
| `RegressionLeverArmAttach` | 90 | every primitive type on / as an arm, nesting, placed files, four formats, lights, `setParent` |
| `RegressionLeverArmMigration` | 57 | keyframe doors -> lever arms (`PbdConvertMain lever`) |
| `RegressionLightLinks` | 84 | the toggleable light: a light that shares its `linkGroup` with a door, a drawer or a lever is on while any of them is open; edge-triggered; the link survives the four formats; the lights buffer notices a switch by hand; the wiring in `PbdRenderer` |
| `RegressionUseKey` | 158 | what one press of E does: what the crosshair is on (`LightSwitch.aim`), whose lights it switches (a model is what a `pbd_ref` placed; a light that follows a door is never switched by hand), the switch (all off if any is on, else all on) and its click (`switchSound`, else the default), the arbitration (the nearer of an item and a part with a light wins, a tie goes to the item), the items of the containers; the sounds and the handler in `Main`, read from the source |
| `RegressionMp3Decoder` | 224 | the MP3 decoder behind the pickup sound: recorded files, pure tones, random frames and crafted streams compared with the C original; tags, gapless trimming, refusals, corrupt data (section F) |
| `RegressionGameSounds` | 51 | the sounds the keys play: E plays `item-equip.mp3` when an item came out and the click of a light it switched, G the shot and the break sound of the material; the player behind them on a recording stand-in for the sound card; names that cannot leave the sounds folder; `Main`'s handlers read from the source |
| `RegressionGlContext` | 144 | the window: the plan of the requests for an OpenGL 4.3 core context (the old request first, then without the forward-compatible flag, then a compatibility context; on Linux GLX then EGL, and X11 after Wayland), the walk through it against a GLFW that follows a script (a machine that works at once, one that needs the second request, the other platform or EGL, one that refuses everything, one where GLFW cannot start, a later window, Windows), the words of the message that names every request and the cures, and the lines that connect the plan to LWJGL, read as text; the real GLFW is not in the run, so what a given machine opens is not what it checks |
| `RegressionSceneFormat` | 581 | the scene description file (`.pbdscene`) expanded into an ordinary scene: the line reader (comments, quotes, parentheses, line endings, every kind of mistake with the number of its line), what each directive writes - the text to the character for cases worked out by hand; the seeded scatter, the rings and the orbits against `fixtures/scene_golden.txt`, written by a second implementation in Python (`gen_scene_golden.py`) - the folder listing (order, filters, depth, links, names, the cap), the limits at the line that crosses them, that nothing a value says can leave its place in the text (every line of every expansion, fuzzed, keeps the shape the expander gives it), the show lines, and the result through the real engine and the `expand` / `check` commands |
| `RegressionSceneDirector` | 211 | what plays the show of a scene file, without a window: the camera path (a monotone cubic) against scipy's, the director's clock - which event fires on which frame, once, in order; the stall of a slow frame; the end; the person taking over - the sun, the camera the engine takes and gives back, where the thing a camera looks at is, the arithmetic of the frame statistics (every percentile at its exact rank, from 1,000 to 20,000 frames) and the rules for the one file a stress test may write, and the lines of `Main` that connect it all, read as text |
| `RegressionSpecExamples` | 183 | the examples of `docs/*.md` parse with the real parser, the table of what a material breaks like, the `crate` material (scale, mirror, turn of a picture) and the constants the text quotes are the code's (section D) |
| `server_sanitize_check.php` | 542 | the pictures and sounds of a submission are rewritten from the pieces the engine needs: every refusal for its reason, every piece of hidden data (a marker that must not be found in the result), every picture decoded by an independent decoder, a file cut at every length and corrupted at every byte (section H) |
| `server_lint_check.php` | 466 | the structure of a `.pbd` and a `.pbdmat` read the way the engine reads them (section H) |
| `server_lint_policy_check.php` | 295 | what a file of the public library may say (metadata, references), what a scene becomes once every `pbd_ref` is expanded (depth, size, cycles), replacing a reference by its position in the text (section H) |
| `server_http_check.php` | 458 | how the server names the client, what it writes into a page, a header or a log, the moderator's session and CSRF token, the counters that limit uploads and logins, the ban list (section H) |
| `server_upload_lib_check.php` | 442 | what becomes of a submission: the names, the files one by one, the submission as a whole, the references, what is stored and with which rights, two requests at once (section H) |
| `server_moderate_lib_check.php` | 243 | what a moderator's decision does to the files, and that the library stays CLOSED - everything a text file in it refers to is in it too (section H) |
| `server_e2e_php` | 417 | the whole site through PHP's built-in web server (section H) |
| `server_e2e_apache` | 522 | the whole site through Apache with `mod_php`, `.htaccess` in force (needs root; section H) |
| `server_e2e_nginx` | 522 | the whole site through nginx with `php-fpm`, `nginx.conf.example` in force (needs root; section H) |
| `blender_addon_check.py` | 891 | the Blender add-on on a fake `bpy`: export / import against the engine (poses, the File metadata box, names), its panels, menus, dialogs and buttons drawn and pressed on a recording layout, the ready-made door / drawer / lever and the lamp that follows them, the click of a light, the break fields of a material, the engine's texture coordinates on Blender's meshes, Mirror U / V and the UV rotation (UV Rotation, Rotate Left / Right: the Mapping node, the `.pbdmat` line, the engine's reading of it), the **Export Compiled Format** buttons (`.pbdbin` / `.pbdasset`: the file browser, a name that is nothing, the bundles and where they look for a material, references, awkward names, every way out, and no program started by either button) and the Java behind *Add to tileGeometry.txt*, hostile files and the upload operator, registration, sources that cannot be read, the zip people install (section C) |
| `addon_safety_check.py` | 417 | what the add-on refuses to read, fetch or upload, with no Blender: the numbers it repeats are the engine's (checked against `FetchPolicy`), a corpus of addresses and file names, the capped readers, the budget of one import, the fetcher against a real server on this machine, the rules of an upload (section C) |
| `addon_compiled_check.py` | 151 | the `.pbdbin` and `.pbdasset` the add-on writes itself, with no Blender and no Java: the bytes of a `.pbdbin` read back by the engine's three readers, the pictures put beside it, the entry names of a `.pbdasset` one by one against `PbdLimits`, file names with brackets, braces, commas and `=` written through the engine's own parser, what is refused before the first byte, and the search for a Java (made-up Javas and the real one) for the one button that still runs it (section C) |
| `blender_real_check.py` | 878 | the same check as `blender_addon_check.py` on real Blender (needs `bpy`; skipped without it; not part of the totals): icons, registered settings, every button pressed, a script's edits exported at once, the zip installed, enabled and disabled by Blender's own installer, a second copy installed over the first and the add-on switched off and on (section C) |

The counts are those of the last full run (`run.sh` ends by checking this table, section D); a suite prints one `PASS:` line per check.
In all: 44 Java suites (5441 checks) + the Blender add-on check (891 checks) + the add-on safety check (417 checks)
+ the compiled-formats check (151 checks) = 6900 checks, and on
the PBD site 6 PHP suites (2446 checks) and three end-to-end runs of the whole site.
The real Blender run repeats most of the add-on check's scenarios, so its checks are not added to the totals the docs quote
(a machine without `bpy` gets the same totals). Three kinds of suite are not unit-like and deserve a word.
`RegressionFormatRoundTrip` and `RegressionPbdRefCopy` compare objects field by field by reflection, and fail if a new
field is neither carried nor deliberately excluded, so a field added next month cannot be silently dropped by one
format (`PbdScene.show` is the single field left out of the files on purpose; the guard does not skip it, it proves that no format writes it). `RegressionSpecExamples` feeds the documentation to the code (section D). And `RegressionHidingRules`,
`RegressionLightLinks`, `RegressionContainerStock`, `RegressionUseKey`, `RegressionGameSounds` and `RegressionSceneDirector` also read a few statements of `Main.java`
and `PbdRenderer.java` through the small `Sources` helper (`read`, `between`, `code`: comments removed and whitespace squeezed out, so a
statement that was only commented out does not count): the glue that cannot run without a window is pinned by what it
has to say, and `mutate.py` can put a broken copy of those files behind the suite (section E).

Four stages of `run.sh` are not suites and print no `PASS:` line, so they are in none of these numbers: `check_site_host.py` (one domain,
named the same everywhere: section D), `check_mutant_specs.py` (every mutant of every spec still applies to the code it breaks: section E), `check_gl_stack.py` (nothing is taken from LWJGL's stack inside a loop) and `gl_runtime_check.py` (the
real viewer, run under a virtual display on scenes of the size nobody tries by hand; one `OK:` line per scenario, five of them). The last two
last two are section I; each is itself checked - the specs checker on a made-up project and by `mutants_mutant_specs.py`, the stack checker on made-up Java and by `mutants_gl_stack.py` and `mutants_gl_stack_sources.py`, the runtime check by
`mutants_gl_runtime.py`, which writes the defect it exists for back into the program (section E).

## A. The formats must agree

`RegressionFormatRoundTrip` builds one scene that uses every field of every class (instances, keyframes, lever
arms, modifiers, curves, mesh data with LODs, includes, lights, container triggers, `indestructible` /
`hardness`...) with awkward values in the free-form ones (a space, `#`, `=`, `,`, a bracket list, a quoted URL),
writes it four ways, reads each back and compares **every public field by reflection**. A completeness guard fails
if the scene leaves any field at its default - a field nobody sets would be "compared" while empty on both sides
and prove nothing. It also checks that re-writing what was read gives identical text, and that a bare
(unquoted) base64 `vertexData` - what a hand-made file or an older tool wrote - is read the same as a quoted one.

`RegressionMinifiedMetadataQuoting` pins the failure that started all this: a minified file has no line ends, and
an unquoted metadata value used to swallow the rest of the scene. There are two independent writers of that text
(`PbdSerializer` in Java and the add-on's `_minify_pbd_text`); both are covered (the add-on's by section C).

When a feature adds a field:

1. add it to the parser, the serializer and the add-on's exporter / importer **in the same change**;
2. set it (to a non-default value) in the kitchen-sink scene of `RegressionFormatRoundTrip` - the completeness
   guard fails until you do;
3. if it can hold awkward text, put an awkward value in it;
4. run `run.sh`.

## B. Engine logic without a window

Eleven files import `org.lwjgl` (`Main`, `ClassicMeshRenderer`, `GlWindow`, `LwjglGlfwDriver`, `MaterialTextureArray`,
`PbdMeshCache`, `PbdRenderer`, `ShaderProgram`, `SkydomeRenderer`, `TextRenderer`, `TextureLoader`). Everything else - `pbd.format`,
`pbd.voxel`, `pbd.pz`, `pbd.net`, `pbd.fbx`, `pbd.audio`, `pbd.sky`, `HierarchyResolver`, `LeverArmSystem`,
`LightTracker`, `LightLinks`, `PatchExpander` - runs with just JOML. The project's habit is **a pure class behind a thin GL
adapter**: `LeverArmSystem` holds all lever-arm behaviour and `PbdRenderer` only feeds it and draws;
`DestructionWorld` holds the whole G flow behind a host interface and `PbdRenderer.destroyAt` is a call into it;
`LightTracker` decides when the lights buffer needs a rebuild and `LightLinks` which lights follow which doors, drawers
and levers; which instance a shot reaches (`DestructionWorld.findFresh`, a mesh by its real vertex bounds) is decided on the
pure side too, and so is what a container remembers between two openings (`ContainerStock`: the frame loop only tells it
what the doors are doing).

The same goes for what a hit breaks and throws. How a material breaks is a value (`BreakProfile`, read from the `.pbdmat`);
which connection is too thin for it, and which piece that connection held, is the analysis of `VoxelFracture`; the chips are
made by `DebrisEmitter` and moved by `ParticleSystem`; `ParticleDraw` holds the one matrix a chip is drawn with, so that
only the GL call is left in the renderer. What hides someone is `HidingRules` (with `SlabOpenings`, which measures a shot
door): the getters of `Main`'s facade only pass its verdict on. The sounds: `GameSounds` says which sound goes with which key,
`SoundPlayer` plays the clip a `ClipSource` gives it (the sound card is the one thing a test replaces) and `Mp3Decoder`
turns the bytes of an MP3 into samples (section F). The window is the same habit: `GlContextPlan` (which OpenGL context to ask for, in
which order, and what to tell the person when the machine says no) and `GlContextNegotiator` (the walk through that plan) have no LWJGL in them,
and `LwjglGlfwDriver` is the one class that makes the GLFW calls; `RegressionGlContext` runs the two pure classes with a GLFW that follows a script
(a machine that works at once, one that needs the second request, the other platform or EGL, one that refuses everything, one where GLFW cannot
start, a later window, Windows) and reads the driver and `GlWindow` as text (what is set for each request, what is passed on). Keep new logic on the pure side, so it can be put
under a `Regression*.java` here. Most reported "engine" bugs live in these classes, not in `PbdRenderer`: write the
repro as a throw-away `PbdEngine engine = new PbdEngine(); engine.load(...)` first.

What stays **reviewed**, not verified: the GL calls themselves (each chip is one draw of a shared cube: the matrix it is
drawn with is checked, the picture is not), the mouse wiring and the feel of the drag in `Main.java`, shaders, frame times, what the
voxel remains look like, and the playing of a sound on a real sound card. The pictures in `docs/renders/g_*.png`
come from the real `DestructionWorld` and a software rasteriser (`VoxelFallDemo`), so they show what the engine
is *told to draw*. The checklist for the human with a GPU is `docs/ROADMAP.md`, section 5.

## C. The Blender add-on: on a fake `bpy`, and on real Blender

`tools/regression/blender_addon_check.py` runs the add-on's real exporter, importer and operators on a fake `bpy`
(`fake_bpy.py`) with Blender's real `mathutils`, writes files, and asks the **engine** - its real parser and
hierarchy resolver, through `BlenderAddonDump.java` - what it sees. The invariant: for every object, the world
matrix the engine computes from the exported file (arm closed / half / open, or a keyframe time) equals Blender's
own, through the Z-up -> Y-up change and the engine's factor of 2 for a canonical shape. Scenarios: a hinged door
on a pivot, a parent inverse (Ctrl+P), a child of a primitive, keyframes, warnings, embed vs reference, the round
trip of files the engine wrote through the add-on and back, the **File metadata box** (name, kind, authors, origin,
description - as plain words, with awkward text, with a line break, as one word with a `#`; pretty and minified;
each read back by the engine and by the importer) and **names the format cannot hold** (an object called
`front door`, `a=b`, `a{b}`, `porte d'entree`...: the exported ids are cleaned up, a clash is numbered, the export
lists what changed, and the engine opens the file with every part hanging where it should). Needs
`pip install mathutils`; to run it alone:

```sh
PBD_JAVA_CP="classes-of-the-tests:classes-of-the-engine:joml:lwjgl" python3 tools/regression/blender_addon_check.py
```

The same file also runs the add-on's **interface code**. The fake gives the panels a recording `UILayout` (Blender 4.0's
signatures) that refuses what Blender refuses - a setting, operator, list or choice that does not exist, a positional
label, a value of the wrong type - and the check then draws, for every kind of object (the eleven types, their variants,
lists empty and filled), the object panel, the sidebar and the Materials panel, the Add / Export / Import menus and both
import dialogs. It fails on an exception (in Blender a draw-time error fails silently into the console), on the red
"PBD panel error" box, on a sidebar whose first line is not `PBD Tools v` + the version in `bl_info`, on a lever arm that
does not show all of its settings when it is on (or shows more than its switch when it is off), and on a setting that
the add-on keeps but no panel draws (four named exceptions). It presses the buttons that carry logic (Capture Current
Pose as Open - and the engine then plays the pose that was captured -, the door list's + and -, the keyframe buttons),
looks for the shared materials where the add-on says it does, and registers, unregisters and registers the add-on
again, checking that nothing is left behind. Sources that cannot be read (no such file, no network, a 404, with the
network replaced by a stub that fails) must give an error message, or a warning and an empty placeholder for a
`pbd_ref` inside an imported scene - never a traceback. What the recording layout does not know: icons, and how
anything looks. The fake's properties are as strict about one more thing, because Blender is: a text property set to anything but text
(`None`, say, for a texture the add-on refused to take) is a `TypeError` there. The fake used to take it silently, and a mutant that set a
refused texture to `None` lived until it did not; one check pins the strictness.

**Doors, drawers, levers - and the lamps that follow them.** Five scenarios run the buttons that give an artist a lever
arm and a light that is on while it is open. *Add Lever Arm* (Door, Drawer or Lever) works the open pose out from where the
object stands now - under a turned frame, with a parent inverse - and the check then turns the object by hand to that pose
and asks the engine whether the open matrix it computes from the exported file is Blender's. *Remove Lever Arm* undoes it.
*Add Light Linked to This* adds a lamp that shares the link group of the pivot that opens (not of the panel that hangs on it,
nor of the frame) and starts off. The three ready-made pieces of the Add menu (`object.add_pbd_door`, `_drawer`,
`_light_switch`) are made on the 3D cursor, and the switch lights its lamp. `scenario_what_opens` compares the add-on's answer to
"does this open?" with the engine's, object by object, on every setup it builds (keyframes on a position, a rotation or a scale,
a delta transform that the exporter does not write, a lever arm, nested ones, a placed file), and `scenario_light_links_travel`
checks that the link survives embedding, import and export: two placements of one cabinet each keep their own lamp.

**How a material breaks.** `scenario_break_material` checks the break fields of the Materials panel (*Custom break* and the
ten keys of a `BreakProfile`) against the engine itself, which `BlenderAddonDump` is asked (`--break-defaults`, `--break-limits`,
and what the engine makes of a `.pbdmat`): the numbers the add-on starts a wood, a metal, a plastic and any other material from
are the engine's, no field can leave the range the engine accepts (the engine REFUSES a value outside it), and a file the add-on
writes is read by the engine as the same profile. Two fuzzers with a fixed seed (so a failure repeats) back it:
`fuzz_parser` gives the add-on's `.pbdmat` reader and the engine's `PbdMatParser` 500 random files and a list of awkward ones
(every structural character against the word next to it, files cut off wherever a word was expected, control characters) and
requires the same authors, materials and values, or a refusal by both at the same line; `fuzz_round_trip` takes 300 random
blocks and the border cases, reads each with the add-on, writes it again, and requires that the engine makes of the second
file what it made of the first (to six digits) and that the add-on complains about exactly the keys the engine does.

**The click of a light, and texture coordinates.** `scenario_light_click` follows a light's *Switch sound* field (the click E makes
on its model) from the panel to the engine and back: it is exported as `switchSound`, by file name, read back by the importer, read by
the engine (`LightSwitch.soundOf`), and its file travels with **Upload**. `scenario_uv_layout` asks the engine (through the Java port of
the shader, `BlenderAddonDump`) which texture coordinates each point of each primitive gets and requires the add-on's preview meshes
(`uv_layout.py`) to carry the same numbers; `scenario_uv_previews` requires it whatever the size of the primitive and that *Refresh UV
Mapping* restores them; `scenario_import_smoothness` that the roundness of a cube in a file is read the way the engine reads it
(`smoothX/Y/Z`, each falling back to the uniform `smooth`). *Mirror U* and *Mirror V* (`scenario_mirror_texture`,
`scenario_mirror_files`) are a negative UV scale to the engine: written in the engine's own keys and read back by each of the three
readers of a `.pbdmat`.

**Turning a texture, not only mirroring it - and being told so.** Until 0.47.0 the rotation was drawn under the two mirror buttons, with no
heading of its own, nothing said how the picture lay now, and nothing that Blender's Solid view shows none of it; a report said the add-on
"only mirrors". `scenario_rotation_panels` draws the panels on the recording layout and requires a box of its own, **Texture mapping**, that holds the rotation (*UV Rotation*, the
four angles side by side, then *Rotate Left* and *Rotate Right*, each naming the material it turns) BEFORE the mirror buttons, and one
line that says how the picture lies now (*As in the file*, or *Turned 90\u00b0 counter-clockwise, mirrored left-right*: every one of the
four angles with scales on both sides of 0, checked word for word); the same box once in the PBD Materials panel, inside the box of the
material; offered for a material with a texture or only a normal map (the Mapping node turns both) and not for one with no picture or only a
roughness map. Blender's **Solid** view ignores a Mapping node, so a viewport in Solid shading gets a warning in every panel that has the box
and the button *Show the Mapping (Material Preview)* (`pbd.preview_material`), which switches the 3D viewports to Material Preview and reports
it; the warning is not drawn for Material Preview, Rendered or Wireframe, for a screen with no 3D viewport, for a Properties editor in Solid
(an area AND its active space must both be a 3D viewport) or for a material with no picture, and a context that is handed over is the one
looked at. The list of materials marks the entries that turn or mirror a picture. That the buttons turn what the engine turns is not left
to the panels: `mutants_addon_rotation_real.py` has real Blender render a plane with the Mapping node the add-on made and compares
every cell with the engine's own turned picture.

**A scene is data from anywhere.** `scenario_hostile_files` gives the importer what a stranger could write: a scene that includes itself
(the import ends at its budget of objects and says why; the references that were refused stay as empty placeholders), a chain of
includes that is too deep, a scene that names more files than the budget allows, a compressed scene that expands past 64 MB, a text
of 64 MB and one byte (refused before a byte is read), a file that is not UTF-8, and a `.pbdasset` bundle with an entry that climbs
out of its folder, an absolute path, a drive letter or a device name (the import is cancelled, nothing is written outside, nothing is
left, and the folder is no trusted place), with no scene, or not a zip. A scene inside a bundle may reach the bundle and nothing
else (`../outside.pbd`, an absolute path, an `include_material` that leaves it - each refused with a warning that says so), while a
scene that is a file of the person's may still refer to a file beside its folder. A `.pbdmat` whose textures are a network path, a
stream (`a.png:stream`) or an address keeps none of them, with the same refusal from all three readers; and no network request is
made for what is not an address of the project's site or of a public host. `addon_safety_check.py` is the same wall without Blender
(see below).

**The upload.** `scenario_upload_operator` runs the **Upload to the server** button with the network replaced by a recording opener:
a scene with a texture and a click is sent as one version check (GET) and one upload (POST) to the project's address, with the add-on's
user agent and a multipart body that a strict reader finds well formed, whose parts are the scene, its materials, the picture and the
sound byte for byte (the materials name the picture by its file name alone), and the scene that travels is one the engine reads; the
copies made for the transfer are gone afterwards. What is NOT sent: two different pictures of one name, a scene with nothing in it or
with no author, a file the server would refuse (ten such reasons are eight lines and a count), and a newer server version only warns.

**What the add-on refuses, without Blender.** `tools/regression/addon_safety_check.py` runs the four modules of the add-on that do not
import `bpy` - `safety.py` (the engine's `FetchPolicy`, `PbdLimits` and `CappedRead` in Python), `fetcher.py` (the engine's `PbdFetcher`),
`upload_rules.py` (what the server takes of a submission and the framing of the request) - in six steps: **the numbers** the add-on
repeats are the engine's (read from the compiled classes); **the verdicts**: the same few thousand addresses, file references and
bundle entry names (hand-picked traps and random ones built from the pieces a resolver reads and then spoiled, with a fixed seed;
`PBD_FUZZ_SEED` and `PBD_FUZZ_COUNT` run another) go through the add-on and through the engine, and the two must answer alike for
every one. The verdict alone cannot tell a function that reads an address wrongly from one that reads it right when a later rule refuses
the address anyway, so the functions are also compared one by one: the groups between the brackets of an IPv6 address against
`java.net.URI` (about twelve hundred texts, built to count to 16 bytes and to miss by one group, one digit or one dot); nineteen thousand
texts taken for a host, read by `FetchPolicy` itself (through the engine's own non-public methods) and by the add-on - the bytes of the
address, whether the last part is a number, the three spellings, and whether the address is a public one; and thirty-two thousand
addresses in bytes (the edges of every private range, the IPv4 inside an IPv6, the lengths that are no address) for the rule applied to what
a name resolves to. Every printable ASCII character is also put in every place of an address (the scheme, the user name, the host, the
port, the path, the query, the fragment, an escape) and the two must still answer alike (about three thousand probes). The reasons a
person is told are checked for the traps that matter most (the add-on words them its own way, so a comparison with the engine's would
only count differences of wording). The request itself is built by `urllib`, which reads an address its own way, so for each address the
policy lets through (some three thousand, in both modes) the connection is built - nothing is sent - and its host and port must be the
ones the policy judged. The last test of a bundle entry, that the place it resolves to is inside the folder, cannot be reached by any
name once the earlier rules have passed it; the check makes the resolution go wrong (the parent, a sibling that starts like the root, the
root itself) and requires a refusal. A digit of another script is a letter to the add-on and a digit to
Java's `Character.digit`: the two never meet (a character beyond ASCII is refused first), and the one case where they would is tested by
hand; **the readers** (a stream, a file, a gzip stream, a bundle: one byte over the cap is refused, a bomb ends in a message, a
bundle with a bad name writes nothing); **the budget** of one import; **the fetcher**, against a recording fake and against a real
server on this machine that redirects, floods and lies (every refusal happens before a request leaves, a download is read under a
cap, the cache is written whole or not at all); and **the upload** (what a submission is held to before it is sent; a file name
cannot add a part to the request).

**The compiled formats, written by the add-on itself.** `tools/regression/addon_compiled_check.py` (no Blender; it needs the compiled engine)
runs `compiled_export.py` and `java_runtime.py`. The add-on used to write a `.pbdbin` or a `.pbdasset` by asking Gradle for a classpath and
running `pbd.PbdConvertMain` with the `java` of the PATH; on a machine whose `java` was older than the Java 21 the classes are compiled for,
Blender printed `UnsupportedClassVersionError` and nothing was written. Neither format needs Java, so the add-on now writes them, and the
checks ask THE ENGINE whether it can read what was written: the bytes of a `.pbdbin` (`PBDB`, a version byte, the gzip of the minified
text) are read by the three readers of the engine and give the scene the text gives, the same as the file the old route made from the same
text (accents, a thousand cubes, a megabyte that does not compress, the limits applied before anything is written, the same bytes twice); the
engine's `PbdAssetFormat.unpack` opens every `.pbdasset` and resolves the materials with each picture a FILE (the old route left them out
without a word), two materials from different folders keep their own pictures, and a name the engine would refuse, or a bundle over its
limits, is refused before the first byte; an entry name is accepted exactly when `PbdLimits.entryTarget` accepts it; the pictures found in a
`.pbdmat` are the ones `PbdMatParser` reads (a name in quotes, a comment, a value cut at a bracket); what goes beside the `.pbdbin` (the
`.pbdmat` and the pictures; a picture that is missing, two with one name, a file that must not be replaced) and a write that is whole or
does not happen. File names with brackets, braces, a comma, a `=` or a `#` go through the engine's own parser: the include line of a
bundle and the lines the exporter writes are quoted exactly when the engine's reader needs it (`quote_word`). The search for a Java is run
against Javas made up for the occasion (which release a class file and a `java -version` text say, where a Java is looked for, which one is
chosen, what is said when none is recent enough) and against the real one of the machine. The scenarios of `blender_addon_check.py` that
press **Export Compiled Format** and **Add to tileGeometry.txt** (`scenario_compiled_bin`, `scenario_compiled_asset`,
`scenario_compiled_lookups`, `scenario_compiled_dialog`, `scenario_compiled_references`, `scenario_compiled_awkward`,
`scenario_tilegeometry_java`; `scenario_registration` for what `unregister()` takes back) run the same on the fake and on real Blender 4.2, 4.5, 5.0, 5.1 and 5.2 (the parts that need a saved `.blend` or
the panel drawn are on the fake only). They cover the pictures of a material (all four kinds - texture, normal, roughness, displacement -
each a picture of its own, compared byte for byte), a path with no name in it (`""`, a folder, `//`, only the extension, blanks: refused, and
nothing written - not even into the folder Blender runs in, which is what the button did in the 0.48.0 that was first delivered: its check for an empty name could never fire), a path that starts with `//`,
an export that dies of something nobody expected and one the engine would refuse (said in the error's own words, no file, no stage folder),
two pictures with one name, where a bundle looks for a material and its pictures (beside the stage, where it is saved, in the `.blend`'s
folder, in the shared textures folder), the file browser's two functions called the way Blender calls them (the name offered, the extension kept,
whether anything changed, the name of the last `.pbdbin` coming back for a `.pbdasset`, `check_existing` on and hidden as in Blender's own exporters)
and the panel's two buttons - and the two entries of **File > Export** (`PBD Binary (.pbdbin)`, `PBD Asset bundle (.pbdasset)`, drawn on the
recording layout, each asking for its own format and opening the browser on a name with its own extension; on real Blender the functions the add-on
appended to that menu are looked up by name after `register()` and must be gone after `unregister()`) asking for their own format, a value with spaces, brackets, braces, a comma, an `=` and a
`#` in the category, the link group, the material, the source and the fallback of every kind of object (a pivot, a shape, a light, a reference, an
embed, an include typed by hand) written to a `.pbdbin` and a plain `.pbd` and read by the engine, and - for the one button that still runs
Java - which release is asked for (read from the class that starts the tool), which program runs (the one that was found), in which folder, what
it says, and what is said when it fails or cannot start - and that the two export buttons start **no** program at all: `subprocess.Popen` and
`os.system` are watched while they write, with a `java` that dies of `UnsupportedClassVersionError` first on the PATH (`scenario_compiled_starts_no_program`).

**On real Blender.** `tools/regression/blender_real_check.py` runs the same file with `PBD_REAL_BPY=1`: the add-on is
registered in a real Blender (the `bpy` module, `pip install bpy`; run here with 4.2 LTS, 4.5 LTS, 5.0, 5.1 and 5.2 LTS, each in an empty
factory-settings scene), and its operators run through `bpy.ops`. Called from a script, a real operator's WARNING and INFO reports are
only printed (only an ERROR comes back, as an exception); the check therefore reads them from what the process prints while the operator
runs (`captured_console`: the file descriptors are redirected for the length of the call, then the text is written out again), since
`self.report` cannot be wrapped - Blender looks its own function up before any attribute of the class. Nine checks of the scenarios above
failed on real Blender, and passed on the fake, until it did. A real Blender cannot draw a panel without a window, so instead of the
recording layout it asks Blender whether **every icon the add-on names exists**, whether the settings Blender registered
for an object, a material and each list row are **exactly the ones the add-on declares** (a setting written `x = ...`
instead of `x: ...` is dropped without a word), **presses every button of the add-on** once on a plain scene and once with
a `mesh` primitive selected (all but Upload and Export & Visualize, which need a server and a build) and fails on a Python
exception, and has a script **edit objects and export - or import - at once**, as a batch script does. Without a refresh,
Blender gives such a script the matrices from before its edits. Run alone:

```sh
PYTHONPATH=where-bpy-is-installed PBD_JAVA_CP="classes-of-the-tests:classes-of-the-engine:joml:lwjgl" \
  python3 tools/regression/blender_real_check.py
```

**Another Blender version.** The `bpy` wheels of Blender 4.2 to 5.0 are built for Python 3.11, those of 5.1 and 5.2 for Python 3.13
(`pip index versions bpy --python-version 3.11` lists them). Install one next to the others and point `PYTHONPATH` at it:

```sh
uv pip install --python /usr/bin/python3.11 --target /somewhere/bpy4_2 bpy==4.2.23
PYTHONPATH=/somewhere/bpy4_2 PBD_JAVA_CP="..." python3.11 tools/regression/blender_real_check.py
```

Run that way, the same 878 checks pass on 4.2.23, 4.5.15, 5.0.1, 5.1.2 and 5.2.3 (the sweep of 2026-11-14, one run on each; that of 2026-11-10 ran 870 and
that of 2026-10-20 ran 681 on 4.2.23, 4.5.14, 5.0.1, 5.1.2 and 5.2.2, with 4.5.14 five times in a row, for the reason given below). They cover Blender's three animation APIs, which
`anim_compat.py` has to read: 4.2 has no action slots (the legacy `action.fcurves`, which Blender 4.0 has too), 4.5 has slots and
the deprecated `action.fcurves` proxy, 5.0 and later have slots only. The run ends with `os._exit(status)` on purpose: Blender 4.2
as a Python module crashes at exit once any operator has been registered (a three-line operator of ours does it too), which would
otherwise replace the verdict by exit status 139 - `mutate.py` would count every mutant as killed.

Only the real run found these two (the fake lets both through, and `mutants_addon_real.py` puts each back):
**stale matrices** - the exporter read `matrix_world`, `matrix_local` and `dimensions` right after a script's edits, and a
scene imported and exported in the same script came out with a door panel three units off (the exporter now refreshes
the depsgraph first, and the importer before it returns); and **a failed fetch was a plain `Exception`** where the importer
catches `OSError` - one unreachable `pbd_ref` stopped a whole import with a traceback instead of leaving its placeholder
and a warning (`FetchError` is now an `OSError`; found by pressing every button). Two smaller ones came from the
fake side: the sidebar's first line said `v0.41.0` whatever the version, and the fallback for the materials folder
looked four folders up instead of five.

Two more things only real Blender showed, both about the **check** and not the add-on. A reference to an item of a collection
(`scene.pbd_materials[3]`) is only good until the collection changes: after the next `add()` it may point at freed memory and
read as whatever lives there now - a fresh entry's defaults - and only sometimes (about one run in a few on 4.5). So the check
holds an `EntryRef`, an entry found again by its position at every use, and the 4.5 run was repeated five times in a row after
the fix: a flaw that shows once in a few runs is not shown gone by one green run. And `bpy.types` has no attribute named after
a registered `PropertyGroup`, so the range of a field is asked of `Class.bl_rna` (the fake keeps its declarations apart).

**The zip people install.** `tools/blender-addon/pbd_tools.zip` is a copy of the add-on's folder, and a copy goes stale: the
one in the upload was version 0.43.1, ten of its twelve files differed from the sources, and it had no lever arm at all.
The check now compares it with the sources - exactly the add-on's files, byte for byte, in a folder of their own - and
fails when it is stale (it did, on the old one: rebuild it with the command in `tools/blender-addon/README.md`). On real
Blender it goes further: Blender's own installer (`Preferences > Add-ons > Install...`, with `BLENDER_USER_SCRIPTS` pointing
at a throw-away folder so that nothing lands among the user's add-ons) installs the zip; the check looks for one add-on in
a folder of its own whose `bl_info` is the sources' (name and version) and asks for Blender 4.0 or later, enables it (the
sidebar panel and the operators appear) and disables it (they go away). When a changed copy of the add-on is being checked
(`mutate.py`), the zip is built from that copy instead, so a mutant cannot test the committed one: the teeth of the
comparison are its failure on the old zip, not a mutant. The folder of the sources is on `sys.path` while the checks run, and
`addon_utils.enable` would import **it** instead of the copy Blender installed - which the zip scenario did until 2026-11-14 -, so the scenario takes
it off the path and requires the enabled module's `__file__` to be in the folder Blender installed into.

**An add-on updated in place** (real Blender only: the fake has no installer and no table of add-ons). Blender keeps an add-on's modules in memory, and an
add-on switched off and on again is reloaded as a *package* only: a newer zip installed over a running older one left the old panel and the old buttons
running (`ROADMAP.md`, item 35). `scenario_update_in_session` installs two copies of the add-on through Blender's installer - every `.py` file marked
with the number of its copy, the second installed over the first after a pause long enough for the file's time to differ -, switches the add-on
off and on, and looks at what Python then holds: every module of the add-on (the ones in `operators/` too) is the second copy; the package's own
names (`materials`, `properties`, `ui_panel`, `operators`) are the modules of `sys.modules`, not older ones (`from . import ui_panel` hands back whatever
the package already has under that name); **File > Export** lists *PBD Binary (.pbdbin)* and *PBD Asset bundle (.pbdasset)* once each, as the sources
do; and a `.pbdbin` exported with the updated add-on is written with no program started. It ends by taking the installed copy away and putting the
sources back, so the scenarios after it see the add-on as before. `mutants_addon_update_real.py` (7) breaks the lines of `pbd_tools/__init__.py`
that let go of the old modules, one way at a time.

What stays **unknown**: how the panels *look* in a window, undo, the viewport drawing of `mesh_preview.py` (it needs a
GPU), and the Blender versions that were not run: the project's own, 4.0.2 (no `bpy` module of it exists, and Blender's download server
is not reachable from the workspace), 4.1, 4.3 and 4.4. For 4.0, two one-off checks were made against its API stubs (the
`fake-bpy-module-4.0` package; neither is part of `run.sh`): every icon the add-on names (29) is in the 4.0 icon list, and a type
check of the add-on against the stubs finds nothing missing from 4.0 apart from what the add-on registers itself and the 4.4+ call
`anim_utils.action_get_channelbag_for_slot`, which `anim_compat.py` reaches only on an object with an action slot. Stubs cannot
see through untyped parameters (`obj`, `context`): a partial net, not a run. When a faked run and a real-Blender run could plausibly disagree, say so instead of reporting
only one.

## D. The documentation is checked by the code it describes

An example in `docs/*.md` that is marked on the line just before its opening fence

```
<!-- check: scene -->        (or: <!-- check: material -->)
```

is fed to the real parser by `RegressionSpecExamples` on every run. A scene example gets a `pbd_version 1` line in
front of it when it has none; a material example is read as a `.pbdmat`. Parsing alone would let an example drift
into meaning something else, so the suite also checks what each is *about* (a `leverArm` example must produce an
instance with a lever arm, a `keyframe` example one with keyframes, a `curve` example a curve with its points, a
`modifier` example modifiers, a `light` example a light) and pins the spec's three lever-arm examples to the numbers
the text states. Two guards keep the checking from shrinking: a minimum number of marked examples, and a rule that
any fenced block in the docs that shows a `leverArm` must be a marked one. The finder that tells fences apart
(an opening fence may carry a language, as in sh or java, and prose between two code blocks is not a block) is
itself checked on a made-up document: it once took a closing fence for an opening one and reported prose as an
example.

Two more kinds of mark exist, for text that is not a file. `<!-- check: breakdefaults -->` marks the spec's table of how a
material breaks (the ten numeric keys of `BreakProfile`, their ranges, and what each of wood, metal, plastic and any other
name starts from): every cell is compared with the code, and the table has to list exactly the keys the class reads - a key
added to the class without a row, or a row for a key that does not exist, fails. `<!-- check: constants -->` marks a block of
lines `package.Class.FIELD = number`: each names a static field of the engine and the value the text quotes for it (a
threshold, a cap, a ratio), read by reflection, package-private fields included. Besides these, the spec's `oak` material
(it sets every break key) is pinned to the numbers the text states, and its linked-lamp scene is run through the real
`LightLinks`: the lamp has to be off with the door shut and on with it open, as the text says. Because the table is typed in the
spec and the numbers in the code, the two can only agree by being the same.

Two more marks are for the scene description file. `<!-- check: scene-file -->` marks a whole `.pbdscene` example, which is set out in a
folder of its own (every model it names is a small stand-in with a body and a door; the files it lists in a `# files:` comment are created)
and **loaded through the engine** - expanded, parsed, its show read; the spec's three scene files are pinned to the numbers the text states
about them (how many placements, which ids, where the first of a ring stands, what the orbit's keys are, when the events of the take happen,
where a scatter of seed 42 puts its first tree - the numbers of an independent SplitMix64 written in Python). `<!-- check: report -->` marks
the layout of a stress report: its facts must be the ones `Main` writes, in its order, and each line of the statistics the shape of the line
`FrameStats` writes.

The **numbers the docs quote** are checked as well. `tools/regression/check_doc_counts.py`, the last step of
`run.sh`, counts the `PASS:` lines each suite just printed and the mutants in each `mutants_*.py`, and fails on
every document that says something else, in any of these forms:

```
`RegressionX` (N)        `RegressionX`, N        | `RegressionX` | N |        `mutants_x.py` (N)
S Java suites (J checks) + the Blender add-on check (A checks) = T checks
S Java suites plus the Blender add-on check         F spec files, M mutants
K/K mutants killed       K/K doc mutants killed
```

(letters stand for numbers here: a real number in this block would be checked like any other). The last form is not
tied to one spec by name, so the checker only requires that the total is the size of *some* spec or the sum of all of
them - a number nobody can match is a stale number. A count spelled out in letters before "spec files" or "Java
suites" cannot be compared, and is reported: write it in digits.
The script also fails on a suite or a spec that the table in section 0 / section E does not list, and on a document
that quotes a suite that does not exist. It reads the current-state documents only (ROADMAP.md above its journal; the
journal records what was true on the day it was written). Cite a count in one of those forms and it cannot go
stale unnoticed.

The checker is itself checked, twice. It runs a self-test first (`python3 tools/regression/check_doc_counts.py
--self-test`): on a made-up document it must accept the right numbers and report a wrong one of every kind above,
and it must find as many numbers as it should - so a pattern that silently stopped matching cannot make every run
green. And `mutants_doc_counts.py` (section E) breaks the checker 45 ways and requires the self-test to notice.

**One domain, named the same everywhere.** `tools/regression/check_site_host.py`, a step of `run.sh` just before the numbers, reads the
host that the three programs declare - `FetchPolicy.SITE_HOST` in the engine, `PBD_SITE_HOST` in the add-on's `fetcher.py` (and in the
`fetcher.py` inside the committed add-on zip) and `PBD_SITE_HOST` in the site's `pbd_metadata.php` - and fails when they differ or when
one of them cannot be found. It then reads every text file of the project and of the site's folder, and every text file inside a zip,
and fails on any host of the kind `pbd.<name>.<tld>` (a common top-level name) that is not the declared one: a paragraph, a test or a
page left over from before a change of domain is found here, not by a reader. It does not need to be told the old name, and the old
name is written nowhere. The reserved `pbd.example.org` is the one other name allowed (a test needs a host that is not the project's).
The output is `OK:` / `FAIL:` / `NOTE:` lines and no `PASS:` line, so it is in none of the totals. It checks itself first on made-up
projects (`python3 tools/regression/check_site_host.py --self-test`: every mistake it exists to find, in each of the places it looks,
must be found, and a clean project must not be reported), and `mutants_site_host.py` (section E) breaks it 38 ways and requires the
self-test to notice. What it cannot know: a name of another kind (it would have to know every name the former domain ever had), and,
when no site folder is found, the site's constant and files (it says so in a `NOTE:` and compares the other two).

To document a new field: write the example, put the marker above it, run the suite. If the example is deliberately
not a complete valid file (a fragment), leave it unmarked - and do not put a `leverArm` in it.
Prose around the examples is reviewed, not tested; the suite found two real errors in the spec when first
written (a curves example in a syntax the parser does not read, a lever-arm section that knew none of its newer
fields).

## E. Can the tests fail? (mutation testing)

A test that passes proves little until it has been seen to fail on purpose. `tools/regression/mutate.py` breaks the
code - or the docs, the add-on, or the checker of the docs' numbers - in a throw-away copy, **one exact text replacement per "mutant"**, runs the
suite that is supposed to guard it, and reports KILLED (the suite noticed) or SURVIVED (it did not: a gap in the
test, or dead code). Nothing in the project is modified - but each mutant is made from the file as it is when the mutant starts,
so do not edit the project while a spec is running.

```sh
python3 tools/regression/mutate.py tools/regression/mutants_migration.py        # one spec file
python3 tools/regression/mutate.py tools/regression/mutants_migration.py 3 7    # only mutants 3 and 7
for s in tools/regression/mutants_*.py; do python3 tools/regression/mutate.py "$s" || break; done
```

A spec can also go stale between two runs: a function renamed, a loop folded into a table, a check moved into a library - and the mutants that
named the old text then apply to nothing, and guard nothing, while the number quoted for the spec is still right. `mutate.py` says so only when
its spec is run, and a round takes hours. `python3 tools/regression/check_mutant_specs.py` (a step of `run.sh`, about a second, no `PASS:`
line) reads every `mutants_*.py` and every file it breaks, as text, and fails when a mutant's `old` text is not found or is found twice, when it
does not differ from `new`, when the file is not there, when the spec is not a list of `(name, file, old, new)`, or when a suite it relies on
(`TEST`, `RUNNER`) does not exist; the PHP specs need the site's folder and are left out, with a line saying so, without one. It checks itself
first on a made-up project (each case is a mistake it must find at the right place, or a spec it must not report), and
`mutants_mutant_specs.py` breaks it 91 ways. The first time it read the specs it found 27 mutants in five of them that applied to nothing.

It needs what `run.sh` needs (`JOML_CLASSES`, `LWJGL_DIR`; the add-on specs also `mathutils`, and `mutants_addon_real.py` also
`bpy`, and `mutants_gl_runtime.py` also Xvfb, Mesa and LWJGL's natives - they print `SKIPPED` and succeed without them) and exits 1 if any mutant survives or its pattern is no longer
found - so a spec that went stale cannot quietly stop guarding. A run that takes longer than `TIMEOUT` seconds (1200, or what the
spec says with `TIMEOUT = n`) is stopped and counts as KILLED: a mutant that makes the suite loop for ever is noticed by the clock.

A spec of kind `runtime` (`mutants_gl_runtime.py`) goes one step further: it compiles the broken file over the real classes and starts the
real viewer on the scenes the mutant names (`ONLY`), under a virtual display, with `GL_TIMEOUT` seconds for each; a mutant that stops the
program from ending is the one the clock kills. Before the first mutant it asks `gl_runtime_check.py --probe` whether the machine can run
the viewer at all, and prints `SKIPPED` - which counts for nothing - when it cannot.

| Spec file | Mutants | Mutates | Guard |
|---|---:|---|---|
| `mutants_roundtrip.py` | 33 | the serializer, parser and converter fixes of the format-coherence pass, the `.pbdmat` reader (quotes, brackets, the end of the file), the sounds of a scene's materials, a show written into a text file or a `.pbdbin` | `RegressionFormatRoundTrip` |
| `mutants_bare_base64.py` | 4 | the parser's tolerance for a bare `vertexData` | `RegressionFormatRoundTrip` |
| `mutants_migration.py` | 29 | `LeverArmMigration` | `RegressionLeverArmMigration` |
| `mutants_arm_facade.py` | 4 | the `Main` arm accessors with no scene | `RegressionLeverArmSystem` |
| `mutants_lever_arm_system.py` | 61 | `LeverArmSystem`, the arm interpolation, `LightTracker`, `setParent` | `RegressionLeverArmSystem`, `RegressionLeverArmAttach`, `RegressionLeverArm` |
| `mutants_light_links.py` | 57 | the toggleable light: `LightLinks` (who is linked to whom, the state at load, switching only on a change), `LightTracker.switched`, the frame loop and the renderer wiring read from the source | `RegressionLightLinks` |
| `mutants_container_stock.py` | 20 | `ContainerStock`: when contents are loaded, shelved and given back, what `clear()` forgets; and the cases `Main`'s frame loop has for each outcome | `RegressionContainerStock` |
| `mutants_mesh_voxel.py` | 84 | the mesh path of the voxelizer (a closed mesh filled, a mesh that encloses nothing turned into a thin wall, the unreadable ones refused), `PbdMeshData.bounds`, the shot's broad phase (`DestructionWorld.findFresh`) | `RegressionMeshVoxel`, `RegressionDestructionWorld` |
| `mutants_break_profile.py` | 115 | how a material breaks (`BreakProfile`): a default that drifts by one step, a range that lets a value through, a number spelling wrongly accepted, a mistyped key no longer reported | `RegressionBreakProfile` |
| `mutants_voxel_fracture.py` | 168 | the break (`VoxelFracture`): a neck looked at one voxel too early or too late, a window that stops short, a thin run taken without joining two pieces, a piece counted twice | `RegressionVoxelFracture` |
| `mutants_voxel_debris.py` | 102 | what becomes of the pieces a hit leaves (`VoxelDebris`): what stays, what falls, what is dust, the body a piece becomes and the kick it gets | `RegressionVoxelDebris` |
| `mutants_particles.py` | 273 | the chips of a hit: `ParticleSystem` (gravity, drag, bounce, friction, rest, the cap, the fade), `DebrisEmitter` (how many, from where, how fast), `ParticleDraw` (the matrix of one chip) | `RegressionParticles` |
| `mutants_destruction_world.py` | 227 | what `DestructionWorld` does with a hit that breaks and throws things: the width of a material in voxels, what is given to the break analysis, to the physics and to the particles, the message, the openings of a shot door | `RegressionDestructionWorld`, `RegressionWorldDebris` |
| `mutants_slab_openings.py` | 86 | the measure of a shot door (`SlabOpenings`): the footprint, the open columns, the widest square, the integrity | `RegressionSlabOpenings` |
| `mutants_hiding_rules.py` | 277 | `HidingRules` (what a shut door is: fully closed and unaltered; concealing; which doors a place has), `DoorParts` (which instances hang from a door), the three facade getters, the H key | `RegressionHidingRules` |
| `mutants_game_sounds.py` | 92 | `GameSounds`, `SoundPlayer` (what a clip is opened with, started from, closed after; a name that leaves the sounds folder) and the handlers of E and G in `Main` | `RegressionGameSounds` |
| `mutants_gl_context.py` | 277 | the window: the order of the requests (the old one first, the flags dropped one at a time, GLX before EGL, the other platform after the first), what is remembered for the next window, the words of the message (every request, GLFW's own answer, the probe of a window with no requirement, the facts, the cures that fit the system), the environment variables and properties that choose a platform or an API, the driver's calls and `GlWindow`'s lines | `RegressionGlContext` |
| `mutants_mp3_decoder.py` | 134 | `Mp3Decoder` in what a reader of the file gets wrong: where the audio starts (ID3 tags), the Xing / Info / VBRI frame and the LAME tag, which frames are played, when a file is refused, the ends of the data, how a float becomes a 16-bit sample (the numbers of the decoding itself are section F's) | `RegressionMp3Decoder` |
| `mutants_addon.py` | 103 | the add-on: exporter and importer (poses, embed, the File metadata box, which characters an id may hold), its panels and buttons, registration, sources that cannot be read | `blender_addon_check.py` |
| `mutants_addon_break_material.py` | 283 | the add-on's break fields: `materials.py` (the fields, the `.pbdmat` reader that is the engine's grammar, the number rules, the writer) and the places that call it (three readers, the exporter, the panel) | `blender_addon_check.py` |
| `mutants_addon_mechanisms.py` | 182 | the add-on's doors, drawers, levers and lights: a hinge turned the wrong way, a drawer that slides by its whole depth, a light that follows what does not open, a group name somebody else already uses | `blender_addon_check.py` |
| `mutants_addon_real.py` | 13 | what only real Blender sees: no refresh before export / after import, an icon (five of them) or a panel space or a menu Blender does not have, a choice list whose default is not a choice, an attribute that does not exist, a setting declared with `=` instead of `:`, an operator id with a capital letter | `blender_real_check.py` |
| `mutants_docs.py` | 273 | the examples in the docs (the spec's and the add-on guide's), every cell of the break table, the `oak` material, the `crate` material (scale, mirror, turn), the constants block, the linked-lamp scene, the light that E switches, the three scene files of the scene description format (their directives, numbers and markers) and the layout of the stress report | `RegressionSpecExamples` |
| `mutants_doc_counts.py` | 45 | `check_doc_counts.py`: what counts as a quote, the totals (with the add-on safety check), "K/K mutants", the journal, the real Blender run, the runs of the PBD site | its own `--self-test` |
| `mutants_mutant_specs.py` | 91 | `check_mutant_specs.py`: the folder each kind of spec breaks files in, where the site's folder is looked for, the names of the suites a spec gives, each guard (Java, PHP, the whole site, the docs, the add-on, lint and runtime runners), what a good spec is, the text found once, the mutant that changes nothing, the count and the index said, the command line and the exit status | its own `--self-test` |
| `mutants_site_host.py` | 38 | `check_site_host.py`: which file each of the three declarations is read from, that a missing or implausible one is reported, that the hosts are compared without their case, what a host of the kind `pbd.<name>.<tld>` is (each top-level name, capitals, the tail of a longer name), the placeholder, which files and zips are read, where the site's folder is looked for | its own `--self-test` |
| `mutants_voxel_shapes.py` | 331 | the conversion of a primitive into voxels as it is DRAWN: `ShapeEval` (the port of the shader), `PrimitiveParts` (`cap`, `faces`, the rounding), `ForwardVoxelizer` (hollow, bent and sheared shapes built from the surface), `VoxelCavities`, `ShapeVolume`, `ModifierParams`, `PrimitiveVoxelizer` | `RegressionShaderPort`, `RegressionVoxelLattice`, `RegressionTaper`, `RegressionTaperWide`, `RegressionVoxelShapes`, then `RegressionMirror` and `RegressionVoxelLimits` for what the others let through |
| `mutants_mirror.py` | 77 | a negative scale is a mirror: `GridMirror`, the world matrix cut into a rotation and a signed scale (`DestructionWorld.rotationOf` / `mirrors` / `withWorldScale`), the texture read on the canonical shape, a zero or not-a-number scale refused (`degenerateScale`) | `RegressionMirror` |
| `mutants_texture_rotation.py` | 99 | a picture turned by quarter turns: `MaterialUv.quarterTurns` (the tolerance, the modulo, the refusals), `TextureTurn` (the direction of the turn, the size it gives, each corner), `MaterialPictures` (what makes a picture its own, the file decoded once, nothing kept between two calls), the voxels' pixels and the wiring in `PbdRenderer`, `MaterialTextureArray`, `ClassicMeshRenderer` and `Main` | `RegressionTextureRotation` |
| `mutants_texture_mapper.py` | 148 | the picture a shot object keeps: `MaterialUv`, the shader's mapping run backwards (`VoxelTextureMapper`, `ShapeEval.locate` / `unmap`), the triangles' UVs of a mesh (`MeshTextureMapper`), the cutting of a big face into voxel-sized cells, the wiring in `PbdRenderer`, `Main` and `DestructionWorld` | `RegressionTextureMapper` |
| `mutants_use_key.py` | 151 | the E key: what the crosshair is on (`LightSwitch.aim`), whose lights a press switches (`lightsOf`), the switch and its click, the arbitration with an item (`UseKey`), the items of the open containers (`ContainerContents`), the sounds, `DestructionWorld.hasRemains`, `Main`'s handler read from the source | `RegressionUseKey`, `RegressionDestructionWorld` |
| `mutants_fetch_policy.py` | 532 | what a scene may ask the network and the disk for (`FetchPolicy`: an address read from the wrong part, a private range one address short, an IPv4 wrapped in an IPv6, a network path through one separator, a limit off by one, a public address written in octal, in hexadecimal or as one number, a host that ends with a number) and the capped read | `RegressionFetchPolicy` |
| `mutants_fetcher.py` | 247 | `PbdFetcher`: a question asked once per file instead of once per host, a refused address requested anyway, a redirect followed, a cap checked against the wrong number, a download left half done in the cache | `RegressionFetcher` |
| `mutants_scene_format.py` | 596 | the scene description format: the line reader (`SceneLine`), each directive of `SceneExpander` (`scene`, `materials`, `ground`, `place`, `row`, `grid`, `ring`, `scatter`, `folder`, the generator of the scatter, the limits and the cap on the text), the show lines (`ShowParser`, `ShowScript`), the names of Windows devices a report may not take, the way the engine loads a `.pbdscene` and the `expand` / `check` commands | `RegressionSceneFormat` |
| `mutants_scene_director.py` | 395 | the show's player: the camera path (`CameraPath`: the monotone cubic, the look direction and its 89 degrees), the director (`SceneDirector`: the show's clock, the events in order, the sun, the camera the engine takes and gives back), what the camera looks at (`ShowTargets`), the frame statistics (`FrameStats`) and the report file (`StressReport`), and the lines of `Main` that connect them, read as text | `RegressionSceneDirector` |
| `mutants_gl_stack.py` | 204 | `check_gl_stack.py` itself, the script that reads the sources for memory taken from LWJGL's 64 KB stack inside a loop: one alternative of a pattern, one bound of a range, one rule at a time | its own `--self-test` |
| `mutants_gl_stack_sources.py` | 16 | the engine's sources, with the defect written back in each shape the checker must know (an allocation in a loop, the stack handed to a method in a loop, a static allocation in a loop, a `stackPush` with no `try`) - each still Java that compiles | `check_gl_stack.py`, run on the broken copy of `src/main/java` |
| `mutants_gl_runtime.py` | 15 | the program, run for real: the matrices of the draw loop taken from the stack again (three ways), the keys of a show delivered as other keys, E and G read as another key, a mesh that cannot be turned into cubes refused instead of destroyed, a W that does not take the show over, a show that goes on after it, a quit that closes nothing, a report that is not written, frames that are not counted | `gl_runtime_check.py` (needs Xvfb; `SKIPPED` without) |
| `mutants_addon_light_click.py` | 21 | the click of a light in the add-on: the exporter (`switchSound`, the file name alone, quoted), the importer, the upload, the Click Sound row | `blender_addon_check.py` (the scenarios that read or draw the click) |
| `mutants_addon_uv.py` | 176 | the texture side of the add-on: `uv_layout.py` (the engine's layout of a texture over each primitive), the Refresh UV Mapping button, Mirror U / Mirror V and the sign of a UV scale, the `.pbdmat` lines and readers of it, the roundness of a cube read from a file | `blender_addon_check.py` (the engine's own evaluator judges the layout) |
| `mutants_addon_uv_real.py` | 57 | what only real Blender runs of it: the meshes built with `bmesh` (the caps, the poke, the rounded and open cube with its UV layer), Refresh UV's choice, the Mapping node, the limits of a UV scale property | `blender_real_check.py` |
| `mutants_addon_rotation.py` | 72 | the UV rotation of the add-on: the property and the angles it offers, the Mapping node's Rotation Z (minus the angle), the `.pbdmat` line and its reader (what is not a multiple of 90 is left out and said), the Rotate Left / Right operator and its buttons, the exporter and the importer | `blender_addon_check.py` (the scenarios that read or draw the rotation) |
| `mutants_addon_rotation_real.py` | 20 | what only real Blender runs of it: the Mapping node turned about the wrong axis, by the angle and not minus it, not updated when the angle changes, and Blender's own render of a plane (Cycles, 24 x 24 cells) compared with the engine's turned picture | `blender_real_check.py` |
| `mutants_addon_mapping_ui.py` | 90 | the **Texture mapping** box of the add-on: the UV Rotation row before the Mirror buttons, the words that say how the picture lies (a turn said the wrong way round or not said, a mirror said for the wrong axis), the note that Solid shading does not show a mapping and the button that switches to Material Preview (it must not touch a viewport that is not in Solid), the marks in the list of materials, which entries get the box (`has_picture`: a texture or a normal map, not a roughness map) | `blender_addon_check.py` (`scenario_rotation_panels`) |
| `mutants_addon_compiled.py` | 349 | made by `py_mutants.py` from `compiled_export.py` and `java_runtime.py` (an operator turned over, a bound off by one, a guard removed, a call left out, a character left out of a set, an element left out of a list): the bytes of a `.pbdbin`, the file written whole or not at all, the pictures put beside it, the manifest, the include lines of a bundle, the entries of a `.pbdasset` and what is refused before the first byte; the release a class file or a `java -version` text says, where a Java is looked for, which one is chosen, what is said when none is new enough (12 more are listed in the spec as equivalent, with the reason) | `addon_compiled_check.py` |
| `mutants_addon_compiled_ops.py` | 114 | the operators that write the compiled formats and the words the exporter writes for the scene reader: the file browser's name and extension, the entries of **File > Export** (their modes, their words, added and taken back), `check_existing`, the stage, which text goes into which format, where references are written for, what is collected for the pictures, what goes beside a `.pbdbin` and into a bundle, every way out of the button, a button that starts a program before it writes (either of the two, or the one that asks the system for a Java), the Java that *Add to tileGeometry.txt* runs (the one that was found, not the PATH's), and every place the exporter quotes a name, a path or an address | `blender_addon_check.py` (the scenarios that press the buttons) |
| `mutants_addon_update_real.py` | 7 | what only real Blender can show of an update: a newer copy installed over a running add-on and the add-on switched off and on - the modules of the old copy never let go of, let go of too late, of the table of modules only or of the package's names only, left in `operators/`, the first one left, the package itself let go of too (`pbd_tools/__init__.py`) | `blender_real_check.py` (`scenario_update_in_session`) |
| `mutants_addon_safety.py` | 1907 | made by `py_mutants.py` from `safety.py`, `fetcher.py` and `upload_rules.py` (an operator turned over, a bound off by one, a guard removed, a character left out of a set, an element left out of a list): the address, reference and bundle-entry rules, the readers under a cap, the budget of an import, the fetch, what the server takes of a submission | `addon_safety_check.py` |
| `mutants_server_http.py` | 340 | made by `php_mutants.py` from `pbd_http.php`: who is asking, the headers of a page, the moderator's session, the counters that limit uploads and logins, the private folders, the logs and the ban list | `server_http_check.php` and the two libraries' suites, then the section "upload" of the e2e run on PHP's built-in server |
| `mutants_server_metadata.py` | 45 | `pbd_metadata.php`: which group a file belongs to in a listing, how a size is said, the readers that give a page the metadata of a scene | `server_lint_check.php`, `server_upload_lib_check.php` |
| `mutants_server_sanitize.py` | 854 | `pbd_sanitize.php`: the code that reads an uploaded picture or sound byte by byte, keeps what a decoder needs and drops the rest | `server_sanitize_check.php`, `server_upload_lib_check.php` |
| `mutants_server_upload_lib.py` | 242 | `pbd_upload_lib.php`: the names, the files one by one, the submission as a whole, the references, what is stored and with which rights, two requests at once | `server_upload_lib_check.php`, `server_moderate_lib_check.php` |
| `mutants_server_moderate_lib.py` | 207 | `pbd_moderate_lib.php`: what is pending and how it is grouped, what Accept does with a selection (the duplicates, the references rewritten, the order of the moves), what Reject does | `server_moderate_lib_check.php`, `server_upload_lib_check.php` |

That is 53 spec files, 10383 mutants. **What has been run to the end, and when.** A spec is a list of questions, and an answer is only as
good as the code and the checks it was asked of, so this is the state of 2026-11-14. The date of each round is compared with the date of
every file the spec breaks, of every suite that guards it and, for the Java ones, of every production class those suites can reach (read in
the compiled classes with the JDK's `jdeps`): a file changed after its round means the answer may be stale. The rounds of 2026-10-25 ran on
copies of the tree made during that day, those of 2026-11-09, 2026-11-10 and 2026-11-14 on the tree this file comes with (the add-on of 2026-11-14 is v0.48.2);
what changed in the files they read after a round is named below, spec by spec.

- *Run to the end on the present code, every mutant killed, on 2026-11-14* - 2 specs, 121 mutants: `mutants_addon_compiled_ops.py` (114: the three
  mutants added for a button that starts a program, and every older one again - the scenarios it runs gained one) and
  `mutants_addon_update_real.py` (7, on Blender 4.2.23; the spec is new). One of the seven first written died of a `NameError`, which proves nothing; it
  was replaced by one that moves the purge after the imports, and the seven were run again to the end. What changed since the rounds of 2026-11-10 in
  what the add-on specs below read: `pbd_tools/__init__.py` (the lines that let go of the old modules, and the version), the docstring of
  `panel_title()` and, in `blender_addon_check.py`, two scenarios added and a stricter `scenario_zip`; the specs below run named scenarios, none of
  which changed, and none of their mutants is in `__init__.py` - their rounds stand, and were not run again.
- *Run to the end on the present code, every mutant killed, on 2026-11-10* - 8 specs, 881 mutants:
  `mutants_addon_compiled.py` (349: the two modules that write the compiled formats and look for a Java; 12 more mutants are listed in the spec as
  equivalent, each with its reason), `mutants_addon_mapping_ui.py` (90), `mutants_addon_rotation.py` (72),
  `mutants_addon_uv.py` (176), `mutants_addon_rotation_real.py` (20, on Blender 4.2.23), `mutants_doc_counts.py` (45), `mutants_mutant_specs.py`
  (91) and `mutants_site_host.py` (38). The first rounds of the two new compiled-format specs left mutants alive; each survivor was a check that was
  missing, or a defect of the first v0.48.0 (a name that is nothing wrote a hidden `.pbdbin` where Blender was running: `ROADMAP.md`, journal of
  2026-11-10), and the rounds named here ran after the checks were written and the defects fixed. The seven mutants of `mutants_addon_uv.py` that
  the fake `bpy` can never kill (they change what is written through `bmesh`, which the fake does not emulate) are in `mutants_addon_uv_real.py`
  now - six were there already, one was added and killed on Blender 4.2.23. The rounds of 2026-11-10 ran in two stretches: the code of the compiled
  formats and its check did not change between them; the add-on's panel gained the entries of File > Export and the file browser's
  `check_existing` after the first stretch, and the specs that read `ui_panel.py` or run its scenarios (`mutants_addon_compiled_ops.py`,
  `mutants_addon_mapping_ui.py`, `mutants_addon_rotation.py`, `mutants_addon_uv.py`, `mutants_addon_rotation_real.py`) ran again afterwards.
- *Run to the end on 2026-11-09, every mutant killed, and what they read has not changed in its code* - 4 specs, 665 mutants:
  `mutants_texture_rotation.py` (99), `mutants_gl_context.py` (277), `mutants_docs.py` (273: the add-on guide's prose grew since, its examples did
  not) and `mutants_gl_stack_sources.py` (16). The first rounds of the engine's rotation and of the window left 13 and 5 mutants alive; each survivor
  was a check that was missing (`ROADMAP.md`, journal of 2026-11-09) and the rounds named here ran after the checks were written. `mutants_docs.py` ran with 271 of its 273 killed; the 2 that lived
  (a new example had raised the number of checked examples by one, and the lower bound had been exactly the old number) were killed once
  the bound was raised, which can only kill more.
- *Run to the end on 2026-10-25, every mutant killed, and nothing it reads has changed since* - 12 specs, 2464 mutants:
  `mutants_slab_openings.py` (86), `mutants_break_profile.py` (115), `mutants_fetch_policy.py` (532), `mutants_destruction_world.py` (227),
  `mutants_mesh_voxel.py` (84), `mutants_bare_base64.py` (4), `mutants_mp3_decoder.py` (134), `mutants_roundtrip.py` (33),
  `mutants_scene_format.py` (596), `mutants_gl_stack.py` (204), `mutants_server_moderate_lib.py` (207) and
  `mutants_server_upload_lib.py` (242).
- *Run to the end on 2026-10-25, every mutant killed, and since then the engine gained the turns of a picture and a new window - in files
  they break or that their suites reach, but in no place where one of their mutants is* - 9 specs, 1134 mutants: `mutants_light_links.py`
  (57), `mutants_use_key.py` (151), `mutants_container_stock.py` (20), `mutants_game_sounds.py` (92), `mutants_mirror.py` (77),
  `mutants_hiding_rules.py` (277), `mutants_arm_facade.py` (4), `mutants_lever_arm_system.py` (61) and `mutants_scene_director.py` (395).
  What changed: `Main.java` (5 lines, the turns of a mesh's picture), `PbdRenderer.java` (73, the same for the arrays and for the voxels'
  pixels), `ClassicMeshRenderer.java`, `MaterialTextureArray.java`, `GlWindow.java`, and `MaterialUv.java`, which only gained methods. The
  position of the mutants of ten of these specs (those above, and `mutants_destruction_world.py` and `mutants_mesh_voxel.py`) was compared
  with the lines that changed, and none is in one; the suites of `mutants_hiding_rules.py`, `mutants_arm_facade.py` and
  `mutants_lever_arm_system.py` reach `Main` through the facade, and so reach those lines. Not run again.
- *Run to the end earlier, and nothing it reads has changed since:* `mutants_voxel_debris.py` (102) and `mutants_voxel_fracture.py` (168),
  2026-10-12.
- *Run to the end, then something it reads was changed:* `mutants_server_http.py` (340) (the round of 2026-10-19 left 13 of the 353 mutants
  of that day alive; they were dealt with afterwards, in the checks or in the spec, and the 340 now in the spec have not been run again to
  the end).
- *`mutants_voxel_shapes.py` (331): 262 killed, 10 alive, 59 not yet run against the final checks.* The first round killed 240 and left 91;
  each survivor was read and the checks that were missing were written (the shear of every closed solid, a hollow box counted voxel by
  voxel, pipes, the tie of a slab, a bar longer than the octree, a taper wider at the bottom: the journal of 2026-10-18 to 20, item 10). The
  10 that live are listed at the head of the spec with what is known of each: two are equivalent (the object's place in the octree changes
  no voxel; two ways of building a flat shape give the same voxels), three change a few voxels of a few shapes (a cell a voxel long instead
  of half, a rounding of at least a voxel, a lattice never repeated finer), and five have not been analysed.
- *`mutants_addon_safety.py` (1907): 700 run, 1207 not.* The list has two families, in this order: every operator, constant and bound of
  `safety.py` turned over (542 mutants), then, in the order of the file, a condition negated, a guard removed, a character left out of a
  set, a call left out (the other 1142 of `safety.py`, then 70 of `fetcher.py` and 153 of `upload_rules.py`). The run is the whole of the
  first family and the first 158 of the second (`describe`, the sets of characters of the module up to `_HEX`, the first conditions of the
  address reader): 700 mutants, every one killed or listed at the head of the spec as one that no input can tell from the original, with the
  reason (25). The run was made in two stretches: the first 360 left 70 alive and the second (365, with the last 24 of the first run again)
  20 more; none was a defect of the add-on's rules - each was a check that was missing (the journal of 2026-10-24 to 25, item 10). Each
  survivor was closed with a check (or listed as equivalent) and run again on a copy of the tree made after its check was written; the 610
  that were killed at the first run were not run again on the final checks, which were only added to. The rest - 1207 mutants, all of the
  second family from the middle of the address reader on - **has not been run**, and nothing is claimed for it.
- *The other 13 specs* (`mutants_addon.py` (103), `mutants_addon_break_material.py` (283), `mutants_addon_mechanisms.py` (182),
  `mutants_addon_real.py` (13), `mutants_addon_uv_real.py` (57), `mutants_server_metadata.py` (45),
  `mutants_server_sanitize.py` (854), `mutants_fetcher.py` (247), `mutants_particles.py` (273), `mutants_migration.py` (29),
  `mutants_texture_mapper.py` (148), `mutants_addon_light_click.py` (21) and `mutants_gl_runtime.py` (15)): either what they read or what guards them
  changed after their last round, or there is no round to the end on their present inputs on record. The last two changed on 2026-11-09: the
  exporter and the panel gained the rotation and the add-on check that guards the first grew; the window was rebuilt and the second runs the real
  viewer, which needs LWJGL's Linux natives and a virtual screen that the machine of 2026-11-09 did not have - **the real viewer has not been run
  on the new window code at all** (`gl_runtime_check.py` printed SKIPPED). **No claim is made for them.** Some of them were run in part on 2026-10-25, and every mutant run
  was killed: `mutants_fetcher.py` 29 of 247 (the defaults of the policy and the request's user agent, then the seventeen of the cache's
  file names), `mutants_addon_break_material.py` 16 of 283 and `mutants_addon.py` 1 of 103 (the reading of a texture's file name, and the
  texture that is refused and set all the same); and on 2026-11-10, every mutant killed, `mutants_addon.py` 6 more of 103 (the first two, and the
  four whose text had to be rewritten for the exporter's new quoting function), `mutants_addon_mechanisms.py` 1 of 182 (a light's Link Group) and
  `mutants_addon_uv_real.py` 1 of 57 (the one added, on Blender 4.2.23). The whole of `mutants_addon.py` and of `mutants_addon_mechanisms.py` was
  started and stopped to leave the machine to the final run: nothing is claimed for the rest.

`tools/regression/run.sh` does not run any of them (a round takes hours); it runs the suites they guard, and `check_doc_counts.py`
compares the sizes quoted here with the specs.

Mutation runs keep finding real gaps, which is their point. The first round on the format round trip left
mutants alive (modifier parameters, `category`, `channel`, a `#` in metadata) - closed by putting awkward data of
exactly those kinds into the kitchen-sink scene. The migration tool's first run let "base rotation not updated"
survive - closed by adding a rotated drawer and a tilted door. The first run of the lever-arm spec left seven
alive: nothing checked where "fully open" begins, that `setValue` clamps, that a silent update still records the
arm's state, a grabbed point swinging behind the camera, a lamp that only slides or only turns, or the tolerance
of the light tracker. Each now has a check (12 in all). The mesh spec was written the other way round: a probe
of a shot at a mesh found three real bugs first (a unit cube lost 21 % of its volume, a scaled mesh stood in the
wrong place, a flat quad became a block over a metre thick) and the broad phase tested the wrong box; the suite
was written against them, and the 27 mutants put each one back. The add-on spec's round on the File metadata box
and on names left three alive, all real gaps in the check: every description in it had several words, so a writer
that quoted a minified value only when it held a space went unnoticed (a one-word value swallows the rest of the
file); a tab in a value was masked by the spaces beside it; and a mutant meant for the author split matched the wrong
line, which the rule "`old` must be unique in the file" now makes easy to see. Mutants that cannot change any behaviour (a guard
backed up by two more behind it; nudging one coordinate of the inside-test ray instead of two) are left out, with
the reason in the spec file.

Seven things the specs of the destruction chain, of the show's player and of the rules about what a scene may fetch taught, which are worth knowing before writing the next one:

- **A constant the test quotes is a constant the mutant cannot change.** `javac` copies the value of a `static final` number into
  every class that mentions it, so a test that says `assert n == Mp3Decoder.SOME_LIMIT` compares the old number with itself
  after the engine's constant has been mutated, and passes. The tests that need such a value ask the running program (a
  reflection read, `liveInt` in `RegressionMp3Decoder`; the same in `RegressionSpecExamples` for the constants the docs quote).
- **A statement of `Main` is found where it is, not anywhere.** The first tests of the key handlers looked for `case X ->` in
  all of `Main`, which any other `switch` of that size also contains; `Sources.block` now cuts the one handler out first
  (by its opening, with the braces counted), and the mutants of `mutants_game_sounds.py`, `mutants_hiding_rules.py`,
  `mutants_light_links.py` and `mutants_container_stock.py` break exactly the statement they name.
- **A survivor can be dead code, and then the code goes.** A mutant that cannot be killed because nothing reads the value is
  not a gap in the test, it is a field to delete: the `contact` flag of a particle's sub-step, which nothing read, was one (it is gone,
  and `mutants_particles.py` says so). What is left out of a spec as equivalent is listed in its docstring with the reason, and
  `mutate.py` does not count it; the reasons were read against the code, not assumed.
- **"Every one of them" is true of none.** `allMatch` over an empty list is true, so a check that says every piece is awake, or has
  settled, passes when the hit left no piece at all. Three checks of `RegressionDestructionWorld` did that - the second shot of the
  test went through the middle of the fallen piece and cut all of it away - until a probe printed what the shot really leaves. A
  check of that kind first requires that there is something to check, and a hit is accounted for voxel by voxel (what was there =
  what was removed + the splinters + the crumbs + what is left).
- **A survivor can be a bug in the code, not a gap in the test.** The mutant "the 99.9th percentile is the 100th" lived in the first round
  on `FrameStats`, because no check had a run whose length makes the two ranks differ (1,000 frames: the 999th and the 1000th). Working out
  what such a check should expect showed that the code itself was wrong there: `ceil(99.9 / 100 * 1000)` is 1000 in floating point (a probe of
  every run length up to 20,000 frames found 20 such lengths, all of them for the 99.9th), so the "0.1 % low" of every run of a thousand
  frames was the worst frame's. The rank is worked out in whole numbers now (`percentile(sorted, 999)`, in thousandths), a check compares
  every percentile with its exact rank from 1,000 to 20,000 frames, and a mutant puts the floating-point rank back (it is killed). A
  survivor is worth reading for what the expected value *should* be before the check is written.
- **The number of checks must not depend on chance.** The documents quote how many checks a suite makes, and `check_doc_counts.py`
  compares the figure with the run. `RegressionFetcher` looked, among addresses that hold the port of the run, for the names of cache
  files with a `-` and with a `_`, and checked each as it found it: in about one run in four one name held both (228 checks), in the
  others two names did (229). The comparison found it in the first full run where chance chose the rare case. A loop that checks
  "whatever turns up" is a loop that makes a variable number of checks: the suite now finds the cases first and then makes a fixed
  number of checks about them (nine runs in a row gave 229). When a count differs between two runs of unchanged code, diff the two logs
  before editing the document.

- **A mutant that applies to nothing guards nothing - and nothing says so until its spec is run.** The code moves on (a function renamed, a loop folded
  into a table, a check moved into a library) and a mutant that named the old text applies to nothing, while the document still quotes the spec's
  size. 27 mutants in five specs had gone that way when the specs were first read against the code, among them the four that guarded the
  refusal of a private address and the four that guarded an IPv4 address inside an IPv6 one - and the rules against an address written in an
  unusual form had never had a mutant. Writing them showed what the checks lacked (an IPv4 tail that is empty, that wraps an `int`, that begins
  with a sign; a single word that looks like a hexadecimal number), and one survivor was the fake `bpy` accepting what Blender refuses.
  `check_mutant_specs.py` now reads the specs against the code at every run of `run.sh`. It can only say that a mutant *applies*; that it is
  *killed* is still a run of the spec.

To add a mutant: copy a line of a spec file, name what the bug would be, give an `old` text that occurs in the
file exactly as written and the `new` text that breaks it. Write it for a behaviour you rely on; if it survives,
add the check that kills it - the survivor is the finding.

Not yet covered by a spec file: `VoxelCrater`, `VoxelConnectivity`, `VoxelMeshBuilder` and `RigidDebris` (each has its suite, and
`mutants_destruction_world.py` breaks what `DestructionWorld` does with them, not them), `PbdRefCopy`'s copy logic, the binary
and asset formats' own readers and writers, `RegressionLeverArm`'s parser tests, `RegressionIncludeLimits` (what a file may ask of the
engine: depth, instances, size), the extremes `RegressionVoxelLimits` is about (it is the last guard of `mutants_voxel_shapes.py`, which
mutates the caps it checks, but no mutant is aimed at its own cases), and, on the site, `pbd_lint.php`. Some were exercised by hand-made
mutants in earlier sessions, but those were not kept. Add them when you next touch those classes.

## F. The MP3 decoder behind the pickup sound

Pressing E on an item of a container plays `resources/sounds/item-equip.mp3`. The JDK's `javax.sound.sampled` reads WAV, AU and
AIFF but has no MP3 decoder, and a third-party jar would change the build, so `pbd.audio.Mp3Decoder` is a Java port of
**minimp3** (public domain, CC0; Layer III only - MPEG-1, -2 and -2.5, mono, stereo, joint and intensity stereo, free format, the
bit reservoir). A decoder is the kind of code where "it played a file and it sounded right" proves almost nothing, so this section
is the record of what was done instead. The player that hands the samples to the sound card, and the keys that call it, are
`RegressionGameSounds`' (section B); everything below is `RegressionMp3Decoder`'s 224 checks, `mutants_mp3_decoder.py` (134), the
folder `tools/regression/fixtures/` and a few one-off runs, each said to be one when it is.

**What can be known, and how.**

1. *The numbers of the decoding are those of the C original, bit for bit.* The reference is minimp3 itself, built as scalar float code
   (`tools/regression/fixtures/mp3_reference/`: `minimp3_fix.h`, `ref_dec.c`). It has five corrections (`minimp3_fix.patch`, which
   applied to the upstream header gives `minimp3_fix.h` byte for byte), each a place where minimp3 reads bytes nobody wrote or
   switches on a feature the standard does not allow in that mode - so "the same as C" would otherwise mean "the same as whatever was on
   the stack": intensity stereo is tested for in joint-stereo mode only; the scale-factor reuse flags count for the second granule of an
   MPEG-1 frame only (minimp3 reads the "private bits" as them for the first); the array the scale factors are read into starts as
   zeros; intensity stereo needs two channels; and the scratch area of a frame starts as zeros, as the port pads the end of the main
   data. The harness clears the info structure before each call, because the port does (minimp3 leaves in it what the call before
   left when it finds no frame). `python3 tools/regression/fixtures/mp3_reference/compare_with_c.py CLASSPATH FILE.mp3...` builds the
   reference, runs the port on the same files and prints `same` or `DIFFERENT` for each; with `--crafted` instead of files it
   compares the streams the suite builds for itself. The figure compared is what the suite pins: decoded frames / frames refused /
   checksum of the raw bits of all the samples (the C floats are in [-1, 1], the port's in 16-bit units, a factor of 2^15 that changes
   no bit but the exponent), and where every call began and what it consumed. What was compared:
   - the 25 files recorded with ffmpeg from synthetic signals - LAME (constant bit rate from 8 to 320 kbit/s, average, variable, no bit
     reservoir, joint stereo off, no ID3 tag, no Xing frame) and shine (64 and 128 kbit/s), 8 to 48 kHz, mono and stereo: **25 of 25
     the same** (14 of them are fixtures of the repository: `mp3_tone_*` and `mp3_rich_*`);
   - 36 streams derived from them to reach what no encoder writes (free format, intensity stereo, mixed blocks, the "original" and
     "private" header bits flipped): **35 of 36 the same**;
   - the streams the suite builds for the corners of the frame loop and of the file reader (a frame cut short, a reservoir that points
     past the data, a header in the last three bytes, scale factors that end where the data ends...): **26 of 26 the same**;
   - 4500 random streams of eight frames each, header valid and side information and data random bytes, made like the fixtures
     `mp3_rand_*` (two batches, 1500 and 3000): **4244 the same, 256 different**, and all 256 are a frame of an MPEG-2.5 stream at
     8 kHz that the port refuses and minimp3 decodes - the one deliberate difference below. Fourteen of the 4500, chosen to reach as many
     of the decoder's branches as possible (a greedy cover), are the fixtures `mp3_rand_*`; three of them (0212, 0349, 3393) are of the
     256, and the suite pins the port's answer for those and says so.
   Result: **every difference found between the port and the C original is that one**. All but the 4500 were run again with
   `compare_with_c.py` as it is in the repository; the 4500 were not repeated, and are not in the repository (the generator that
   made them also made the 14 kept ones: `fixtures/make_mp3_random_frames.py`).
2. *The decoder agrees with the standard, within one step of 16 bits.* 14 Layer III conformance streams - the ISO/IEC 11172-4 ones
   and their MPEG-2 counterparts `M2L3_*`, with the reference decoder's output, as the `vectors/` folder of the minimp3 repository
   carries them (the three Layer I and II streams there are not played) - decode to samples that are exact in 98.88 to 100 % of the
   cases and never more than 1 away (rms at most 0.106), over 67,000 to 726,000 samples each (the reference output of eight streams is one
   frame shorter than ours; the samples it has are compared). One-off run, by hand: the vectors are 8 MB and not in the repository.
   In the suite the same idea is pinned on what a file SAYS: the pure tones decode to the sines that went into the encoder
   (frequency, level, channel, phase - hence the exact length), a LAME file to exactly the number of samples that went in (the gapless
   trimming by the encoder delay and padding the LAME tag announces), and silent frames of every version, sample rate and bit rate to
   the size and the sample count the standard gives, typed in the test from the standard and not from the decoder's tables.
3. *Properties that hold by construction*: a frame that lives on the bit reservoir decodes like the same frame with its data in place;
   a corrupt file never throws anything but `FormatException` and never loops (the suite runs under a two-minute watchdog, and a
   fuzz test feeds it damaged files); the same bytes always give the same samples.
4. *The tests can fail* (section E). `mutants_mp3_decoder.py` (134) breaks what the file reader does: where the audio starts, the tags,
   the Xing / Info / VBRI frame and the LAME tag, which frames play, when a file is refused, the ends of the data, the conversion to
   16 bits. The numbers of the decoding itself are covered by the comparison above, and a one-off sweep put that to the test: a
   throw-away tool (not in the repository) replaced every operator, comparison, boolean, negation and integer literal of
   `Mp3Decoder.java` by an alternative - 3318 mutants - compiled each in memory and ran the whole suite against it. 2087 were run (the
   alias reduction and IMDCT and the synthesis filter bank were sampled one in seven: any change in their arithmetic changes the
   output, which is compared bit for bit); 8 did not compile, so 2079 are valid. The sweep first left
   126 alive (1950 killed, and 3 that made the decoder loop and were stopped by the time limit), and six of those 126, four gaps, were
   real - all at the end of the data, and first mistaken for dead guards: a stream that ends right after a header (the last four bytes
   taken for the start of one), the guard that looks for the third header of a free-format frame, a read that ends exactly at the end
   of the data, and bits past the end (ones instead of zeros). Each now has a crafted stream in the suite (`endsOfTheData`, and
   entries 128 to 133 of the spec), checked against the C original like the others. **With them, 1956 are killed, 3 are stopped by
   the time limit and 120 survive.** The 120 left were each read against the code and are of these kinds: the
   size or margin of an array that is only ever read inside its used part, an identity or a no-op (`x + 0`, a mask applied twice), an
   initial value that is always overwritten before it is read, a guard that a second guard behind it makes redundant, the half-point
   of a rounding that no 16-bit conversion can reach, the edges of the tags that the docstring of the spec lists, the moment the
   reservoir is refilled, variants of the same header invalidation, and a write that nothing reads. That is a judgement, not a proof, and
   it is the part of this section to doubt first.

**What differs from minimp3 on purpose** (the class comment of `Mp3Decoder` lists it, and each one is pinned by a check): Layer I and II
are refused with a message that says so; the 16-bit rounding is half away from zero for every sample (minimp3's own sends (-1.5, -0.5]
to 0); mixed blocks at 8 kHz are refused (minimp3 writes 24 lines past the channel for them); the intensity-stereo positions start at
zero for every frame (minimp3 keeps what the call before left, so a frame decoded differently depending on its neighbour); the
whole-file entry point skips ID3v2 and ID3v1 tags, does not play the Xing / Info / VBRI frame, trims the encoder delay and padding,
refuses a file whose sample rate or channel count changes, and stops at 120 seconds (a sound effect, not a song). The tag readers
(`skipId3v2`, `isId3v1`, `TagFrame`, `parseTagFrame`) are public for no other reason than that the suite lives in the default package.

**Making new fixtures.** `fixtures/make_mp3_fixtures.sh` (python3 with numpy, ffmpeg with libmp3lame and libshine) writes the
recorded ones and `make_mp3_random_frames.py` the random ones; neither is run by `run.sh`, the `.mp3` files next to them are the
fixtures. A different LAME writes different bytes, so after a regeneration the pinned values of the suite have to be recomputed, and
`compare_with_c.py` is how: run it on the new files, and put into the suite what the C original says.

**What stays unknown.** The real `resources/sounds/item-equip.mp3` was never seen here (the sounds folder is not part of the
upload): the decoder is verified on files LAME and shine wrote and on the ISO streams, not on that file. If it is something else - Layer
II, a different tag layout - the player logs why and plays nothing (`RegressionGameSounds` pins that it never throws); a decode of the
real file with `Mp3Decoder.decode(bytes)` is the check to make first. Playing the samples through a real sound card, and how the
pickup sound sounds, are **reviewed**, not run. What happens on corrupt data is "no exception, no loop", not "plays well".

## G. What only a person with the right machine can check

- **The viewer on a GPU**: `docs/ROADMAP.md`, section 5, lists what to look at, each with what "correct" looks like (G on a post
  and on a textured wall, the chips, a thin connection that breaks, a dragged door, H on a closed door, a lamp that follows a
  door, the pickup sound, scene switching mid-drag, hot loading...). Section I runs the real viewer on a software renderer and tells
  whether the *program* survives a scene (a window, a driver, 2,601 models, the keys of a show); what the shaders draw, how fast a
  real card draws it, the sounds and the mouse stay on this list.
- **Blender itself**: section C above.
- **The server as the host runs it.** Section H runs the site's code on this machine's PHP, Apache and nginx; what it cannot show
  is the host's own configuration (the virtual host, whether `.htaccess` is read at all - `AllowOverride` -, the PHP-FPM pool, TLS, what
  the web server announces about itself), the real library's content and the habits of the real moderators. Say that boundary out
  loud in a bug report instead of guessing at the host.

## H. The PBD site (the `server/` folder)

The site - `upload.php`, `moderator.php`, `search.php`, `index.php`, `tutorial.php` and the libraries they share (`pbd_http.php`,
`pbd_sanitize.php`, `pbd_lint.php`, `pbd_metadata.php`, `pbd_upload_lib.php`, `pbd_moderate_lib.php`) - is PHP, shipped as
`pbd-server.zip`, and is not inside this project. `run.sh` takes it from `PBD_SERVER_DIR`, else `server/` in the project, else
`server/` beside it; without `php` or without the folder every suite below prints `SKIPPED` and the run still succeeds (the numbers the
docs quote for a skipped one are then not compared). Three layers, each with its own mutants (section E):

1. **Six PHP suites** (`tools/regression/server/php/`), each run with the command-line `php`; the harness turns every PHP warning, notice
   and deprecation into a failure, so a library that complains is a library that fails. `server_sanitize_check.php` - the pictures and
   sounds of a submission are rewritten from the pieces the engine needs: every refusal is checked for its reason, every piece of hidden
   data is a marker (`SECRET...`) that must not be found in the result, every picture is decoded again by an independent decoder (GD:
   libpng, libjpeg) and must show the same pixels, and a file cut at every length and corrupted at every byte may only produce a refusal -
   never another exception. `server_lint_check.php` - the structure of a `.pbd` and a `.pbdmat` read the way the engine reads them (what ends
   a bare word, what a comment is, how far a metadata value runs): the expectations are written from the engine's rules, **not yet compared
   with the engine's parser on thousands of generated and damaged files** (that differential check is in the backlog of
   `docs/ROADMAP.md`). `server_lint_policy_check.php` - what a file of the public library may say (the metadata; the references - the plain name of a file
   of the same submission, the address `https://pbd.aptheo.fr/db/<file>` of a file already in the library, or the name of one of the engine's
   own sounds, and nothing else), what a scene becomes once every `pbd_ref` is expanded (depth, size, cycles, the
   files it reaches) and the rewriting of a reference by its position in the text. `server_http_check.php` - who is asking (the proxies
   that are trusted), what is written into a page, a header, a log, the moderator's session and its CSRF token, the counters that limit
   uploads and logins, the ban list. `server_upload_lib_check.php` - what becomes of a submission, every rule as a function: the names, the
   files one by one (a total of exactly 120 MiB is accepted, a byte more is not), the submission as a whole, the references, the pictures
   and sounds the library already has, what is stored and with which rights, two requests at once. `server_moderate_lib_check.php` - what a
   moderator's decision does to the files, and the one property that makes the library a library: it is CLOSED - everything a text file in
   it refers to is in it too (or is one of the engine's own sounds), whatever was selected, whatever failed, whatever was a duplicate.
2. **The whole site through a real web server** (`tools/regression/server/server_check.py BACKEND`, backends `php`, `apache`, `nginx`):
   real HTTP requests to the real pages. `upload.php`: the method, the user agent (exactly the add-on's), the ban list, the rate limits, a
   submission that is fine and a good many that are not, a 97 MB submission under PHP's default memory limit, eight uploads at the same
   moment; `moderator.php`: the login and what protects it (CSRF, throttle, session fixation, cookie flags, expiry), accepting and
   rejecting; `search.php`: what it shows of the library and what it must not believe; a submission that goes through upload, moderator and
   search and is then fetched from `/db/`; and the web server itself - what is never served (the include-only scripts, the configuration,
   the private folders), what the library folder serves and how, what a hostile file placed in it would do, and that a page answers to its
   own name and no other (`/search.php/x.png` is a 404: Apache's own page where `.htaccess` is read, nginx's where `nginx.conf.example` is
   in force, the script's plain-text refusal where nothing else is - and in none of them does the answer announce PHP's version). The PHP
   error log of every instance is read at the end of each section: a warning or a notice on any request fails it. Apache and nginx start
   workers as `www-data`, so those two runs need `root`, and print `SKIPPED` otherwise.
3. **Mutants** (`mutants_server_*.py`): `php_mutants.py` makes them from each library as it is - a comparison turned over, a bound one too
   many or too few, a test negated, a guard `return` or `throw` removed, a side-effect call left out - so that a new line of a library is
   mutated without anyone remembering to add it; each must make a suite fail. What is left over is listed in the spec's docstring with the
   reason no test can tell it from the original.

What is **reviewed**, not run: `tutorial.php`'s and `index.php`'s wording, and the maintenance scripts that run from a shell
(`migrate_domain.php`, `check_library.php`, `repair_texture_refs.php`) - they have no test of their own yet. What is **unknown**: the real
host (section G).

## I. The viewer, run for real, and the memory LWJGL lends

Everything above runs without a window, which is what makes it repeatable - and what hid the defect this section is about. **The
renderer took two 64-byte matrices from LWJGL's memory stack for every model it drew.** That stack is 64 KB per thread, and what is
taken from it is given back only when the `try (MemoryStack stack = stackPush())` block that owns it ends, not when the buffer is no
longer used. One frame was one such block, so a scene of about 500 models filled the stack inside it and the viewer died in the
middle of its first frame with `OutOfMemoryError: Out of stack space` - after every scene anyone had tried by hand (a few dozen
models) had worked. Nothing in the code looks wrong when read, and no Java suite opens a window: it is a limit of the machine, not
of a function. It was found with a scene of 600 models, the size that the scene description format makes easy to write.

The renderer now takes its two matrices once, before the loop, and fills them again for each model (`PbdRenderer.renderCached`). Three
things keep it that way, and each is itself checked by mutation (section E):

- **`check_gl_stack.py`** reads every `.java` file as text (comments, strings and text blocks blanked first, as `Sources` does) and
  reports memory taken from a `MemoryStack` inside a `for`, `while`, `do` or `forEach`-like loop that lies inside the block of that
  stack; the stack itself handed to another method inside such a loop (that method may take from it); the static `stackMalloc...`
  family inside any loop; and a `stackPush()` that is not the resource of a `try`. A case that is really fine is allowed by a comment,
  `// GL-STACK-OK: <why>`; a comment without the reason allows nothing. What it cannot see is listed at the head of the script: a stack
  handed down through a field or a lambda, recursion, loops of other kinds. It checks itself first on 138 made-up pieces of Java
  (`--self-test`), `mutants_gl_stack.py` breaks it 204 ways and requires the self-test to notice, and `mutants_gl_stack_sources.py`
  writes the defect back into 16 places of the engine and requires the checker to report each.
- **`gl_runtime_check.py`** runs the real viewer (`pbd.app.Main`) under Xvfb, a virtual screen, on `.pbdscene` files it writes itself -
  so the show, the keys and the report are part of what runs - and reads the exit status, what the program prints and the report it
  writes. Five scenarios, one `OK:` line each: `show` (a row of lockers, the E key, a door opened by the show, a camera looking at a
  model: the program ends by itself with status 0, writes a report that tells the numbers of the scene and prints no exception), `many`
  (1,300 models, more than the 512 the defect allowed and than the 1,024 a single matrix per model would still allow; 2,601 instances in
  all), `control` and `takeover` (a show that presses E twice; and the same show with a real W key pressed in the window after the
  first E: the show stops, the second E never happens, the program goes on until its window is closed), and `shoot` (the G key at a
  hollow cylinder, a flat triangle and a cube, each destroyed with a `[Destroy]` line and no exception - and at a mesh that cannot be
  turned into cubes, destroyed outright). It needs Linux, `Xvfb`, Mesa's software OpenGL (an OpenGL 4.3 core context), LWJGL's Linux
  natives and, for `takeover`, `python-xlib`; it prints `SKIPPED`, with the reason, and succeeds when one is missing, and the numbers
  of the docs do not include it (`--probe` is the question `mutate.py` asks first). The five scenarios take about a minute together.
- **`mutants_gl_runtime.py`** writes the defect back (three ways) and breaks the places where a show meets the program - the key a show
  presses, the key that takes it over, the quit that ends it, the report - and requires one of those scenarios to fail for each; a
  program that never ends is killed by the time-out. The vsync switch is not mutated on purpose: a virtual screen has no vertical blank
  to wait for, so the run cannot tell the two settings apart.

**What this does not show.** Which shaders it ran with: the project's own (`src/main/resources/shaders`) when the check runs in a
project that has them - it then also shows that they compile and link under Mesa - and small stand-ins written by the check when
the upload has none, and it says which; with stand-ins, what the real shaders compute is *not* checked. How fast a real card runs a
scene: Mesa runs on the processor and the report's frame times are its own. The picture (nothing compares pixels), the sounds and the
mouse. Two limits of the renderer that the run does not test either, tracked in the roadmap: the cached path makes one draw call per
instance every frame and does not cull what is out of view (the report's `draw calls per frame` shows it), and the texture array
(`MaterialTextureArray`) holds one layer per distinct texture, all of the size of the first, without asking OpenGL how many layers or how
much memory it may use. A scene of tens of thousands of models will be slow before it is wrong; a scene with hundreds of distinct large
textures is not guarded.

## Adding a feature: the recurring checklist

1. Put the logic in a pure class (section B); write the `Regression*.java` first or beside it; add it to the list
   in `run.sh`.
2. If it adds a field to the format: section A's four steps, and a `<!-- check: scene -->` example in
   `docs/PBD_FORMAT_SPEC.md` (section D).
3. If it touches the add-on: extend `blender_addon_check.py` with a scenario (it runs on the fake and, with `bpy`, on real
   Blender - a button gets pressed there for free), and run `blender_real_check.py` if you have `bpy`; for what the
   panels look like, open the file in Blender (section C).
4. Break it on purpose (section E) before you trust the green. Anything that takes memory from LWJGL's stack takes it once, before a
   loop (section I: `check_gl_stack.py` fails otherwise); anything that changes how the viewer starts, ends or reads a key is worth a
   scenario of `gl_runtime_check.py`.
5. Update `docs/ROADMAP.md` (state, evidence, what is NOT verified) and `docs/STATUS_SUMMARY.md` in the project's
   existing style - including self-corrections when a first attempt turns out wrong; that history is a feature.
   Cite counts from the last full run, not from memory (and in the form section D describes, so
   `check_doc_counts.py` keeps them honest).
