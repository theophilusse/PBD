# Test procedure

Run this whenever a change touches the format, the engine, the Blender add-on, the upload path or the
documentation - not just when a bug is reported. Almost all of it is one command:

```sh
JOML_CLASSES=/path/to/compiled/joml LWJGL_DIR=/path/to/lwjgl/jars sh tools/regression/run.sh
```

`run.sh` compiles the whole engine tree and every `tools/regression/*.java`, runs the Java suites (each under a
512 MB heap), then the Blender add-on check, then checks that the numbers the docs quote are the ones just counted
(section D), and ends with `ALL REGRESSION SUITES PASSED` or `SOME REGRESSION SUITES FAILED`. A `FAIL` line is a failing suite; so is a suite that does not end in `ALL PASS`.
It needs no GPU, no display and no Blender. The only inputs are JOML (compiled from source - the GitHub sources
need their `//#ifdef __GWT__` blocks stripped and the `org/joml/jre` and `org/joml/experimental` packages left out)
and the LWJGL 3.3.3 jars (to compile against; their natives are never loaded). The add-on check also needs
`pip install mathutils` (Blender's own maths library) and prints `SKIPPED` - succeeding - without it. The same check
can also run on **real Blender** (`pip install bpy`: Blender as a Python module, a wheel of about 400 MB); `run.sh` does it
when `bpy` is importable and prints `SKIPPED` - succeeding - when it is not.

Three words are used in the docs and mean what they say: **verified** (a suite below ran the real code and
passed), **reviewed** (read line by line, nothing could run it here) and **unknown** (not available here).

## 0. What is checked

| Suite | Checks | Guards |
|---|---:|---|
| `RegressionPrimitives` | 7 | voxel volumes of every primitive against the exact formulas |
| `RegressionTaper` | 3 | taper in voxelization against the exact frustum volume, narrowing |
| `RegressionTaperWide` | 1 | the same, widening |
| `RegressionPerVertexColor` | 2 | colour sampled per vertex of a voxel face |
| `RegressionCraterOOM` | 16 | crater cost and memory under `-Xmx512m`, three shapes, double radius |
| `RegressionVoxelCrater` | 19 | the crater is a solid, deterministic, irregular volume |
| `RegressionVoxelConnectivity` | 17 | face-connected pieces after a cut |
| `RegressionVoxelMesh` | 10 | meshing of a voxel piece |
| `RegressionRigidDebris` | 20 | a piece falls, lands on the floor / remains / boxes and sleeps |
| `RegressionMeshVoxel` | 61 | a `mesh` instance as the target of G: filled solid (closed meshes only), placed where it stands, found by the shot, destroyed end to end |
| `RegressionDestructionWorld` | 56 | the whole flow: G -> cut -> what stays -> what falls -> rest, follow-up hits, caps, leaks |
| `RegressionContainerStock` | 30 | what a container remembers between two openings: loaded on the first opening only, put on a shelf when every door is shut, given back as it was left (an emptied container stays empty), forgotten on a new scene |
| `RegressionMinifiedMetadataQuoting` | 6 | minified text keeps free-text metadata apart from the rest of the file |
| `RegressionFormatRoundTrip` | 94 | one scene with EVERY field through pretty `.pbd`, minified `.pbd`, `.pbdbin`, `.pbdasset` |
| `RegressionPbdRefCopy` | 248 | a placed file (`pbd_ref`) brings everything over, prefixed and re-parented |
| `RegressionLeverArm` | 45 | the `leverArm` block: parse, round trip, interpolation, clamping |
| `RegressionLeverArmSystem` | 147 | the run-time system: value, lock, release, sounds, grab, drag; the `Main` facade |
| `RegressionLeverArmAttach` | 90 | every primitive type on / as an arm, nesting, placed files, four formats, lights, `setParent` |
| `RegressionLeverArmMigration` | 57 | keyframe doors -> lever arms (`PbdConvertMain lever`) |
| `RegressionSpecExamples` | 35 | the examples of `docs/*.md` parse with the real parser (section D) |
| `blender_addon_check.py` | 247 | the Blender add-on on a fake `bpy`: export / import against the engine (poses, the File metadata box, names), its panels, menus, dialogs and buttons drawn and pressed on a recording layout, registration, sources that cannot be read, the zip people install (section C) |
| `blender_real_check.py` | 248 | the same check on real Blender (needs `bpy`; skipped without it; not part of the totals): icons, registered settings, every button pressed, a script's edits exported at once, the zip installed, enabled and disabled by Blender's own installer (section C) |

The counts are those of the last full run (`run.sh` ends by checking this table, section D); a suite prints one `PASS:` line per check.
The real Blender run repeats most of the add-on check's scenarios, so its checks are not added to the totals the docs quote
(a machine without `bpy` gets the same totals). The three that are not
unit-like deserve a word: `RegressionFormatRoundTrip` and `RegressionPbdRefCopy` compare objects field by field
by reflection, and fail if a new field is neither carried nor deliberately excluded, so a field added next month
cannot be silently dropped by one format.

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

Ten files import `org.lwjgl` (`Main`, `ClassicMeshRenderer`, `GlWindow`, `MaterialTextureArray`, `PbdMeshCache`,
`PbdRenderer`, `ShaderProgram`, `SkydomeRenderer`, `TextRenderer`, `TextureLoader`). Everything else - `pbd.format`,
`pbd.voxel`, `pbd.pz`, `pbd.net`, `pbd.fbx`, `pbd.audio`, `pbd.sky`, `HierarchyResolver`, `LeverArmSystem`,
`LightTracker`, `PatchExpander` - runs with just JOML. The project's habit is **a pure class behind a thin GL
adapter**: `LeverArmSystem` holds all lever-arm behaviour and `PbdRenderer` only feeds it and draws;
`DestructionWorld` holds the whole G flow behind a host interface and `PbdRenderer.destroyAt` is a call into it;
`LightTracker` decides when the lights buffer needs a rebuild; which instance a shot reaches
(`DestructionWorld.findFresh`, a mesh by its real vertex bounds) is decided on the pure side too, and so is what
a container remembers between two openings (`ContainerStock`: the frame loop only tells it what the doors are doing). Keep new logic on the pure side, so it can be put
under a `Regression*.java` here. Most reported "engine" bugs live in these classes, not in `PbdRenderer`: write the
repro as a throw-away `PbdEngine engine = new PbdEngine(); engine.load(...)` first.

What stays **reviewed**, not verified: the GL calls themselves, the mouse wiring and the feel of the drag in
`Main.java`, shaders, frame times, and what the voxel remains look like. The pictures in `docs/renders/g_*.png`
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
anything looks.

**On real Blender.** `tools/regression/blender_real_check.py` runs the same file with `PBD_REAL_BPY=1`: the add-on is
registered in a real Blender (the `bpy` module, `pip install bpy`; run here with 4.2 LTS, 4.5 LTS, 5.0, 5.1 and 5.2 LTS, each in an empty
factory-settings scene), and its operators run through `bpy.ops`. A real Blender cannot draw a panel without a window, so instead of the
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

Run that way, the same 248 checks pass on 4.2.23, 4.5.14, 5.0.1, 5.1.2 and 5.2.2. They cover Blender's three animation APIs, which
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

**The zip people install.** `tools/blender-addon/pbd_tools.zip` is a copy of the add-on's folder, and a copy goes stale: the
one in the upload was version 0.43.1, ten of its twelve files differed from the sources, and it had no lever arm at all.
The check now compares it with the sources - exactly the add-on's files, byte for byte, in a folder of their own - and
fails when it is stale (it did, on the old one: rebuild it with the command in `tools/blender-addon/README.md`). On real
Blender it goes further: Blender's own installer (`Preferences > Add-ons > Install...`, with `BLENDER_USER_SCRIPTS` pointing
at a throw-away folder so that nothing lands among the user's add-ons) installs the zip; the check looks for one add-on in
a folder of its own whose `bl_info` is the sources' (name and version) and asks for Blender 4.0 or later, enables it (the
sidebar panel and the operators appear) and disables it (they go away). When a changed copy of the add-on is being checked
(`mutate.py`), the zip is built from that copy instead, so a mutant cannot test the committed one: the teeth of the
comparison are its failure on the old zip, not a mutant.

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
green. And `mutants_doc_counts.py` (section E) breaks the checker 20 ways and requires the self-test to notice.

To document a new field: write the example, put the marker above it, run the suite. If the example is deliberately
not a complete valid file (a fragment), leave it unmarked - and do not put a `leverArm` in it.
Prose around the examples is reviewed, not tested; the suite found two real errors in the spec when first
written (a curves example in a syntax the parser does not read, a lever-arm section that knew none of its newer
fields).

## E. Can the tests fail? (mutation testing)

A test that passes proves little until it has been seen to fail on purpose. `tools/regression/mutate.py` breaks the
code - or the docs, the add-on, or the checker of the docs' numbers - in a throw-away copy, **one exact text replacement per "mutant"**, runs the
suite that is supposed to guard it, and reports KILLED (the suite noticed) or SURVIVED (it did not: a gap in the
test, or dead code). Nothing in the project is modified.

```sh
python3 tools/regression/mutate.py tools/regression/mutants_migration.py        # one spec file
python3 tools/regression/mutate.py tools/regression/mutants_migration.py 3 7    # only mutants 3 and 7
for s in tools/regression/mutants_*.py; do python3 tools/regression/mutate.py "$s" || break; done
```

It needs what `run.sh` needs (`JOML_CLASSES`, `LWJGL_DIR`; the add-on spec also `mathutils`, and `mutants_addon_real.py` also
`bpy` - it prints `SKIPPED` and succeeds without it) and exits 1 if any mutant survives or its pattern is no longer
found - so a spec that went stale cannot quietly stop guarding.

| Spec file | Mutants | Mutates | Guard |
|---|---:|---|---|
| `mutants_roundtrip.py` | 25 | the serializer, parser and converter fixes of the format-coherence pass | `RegressionFormatRoundTrip` |
| `mutants_bare_base64.py` | 4 | the parser's tolerance for a bare `vertexData` | `RegressionFormatRoundTrip` |
| `mutants_migration.py` | 29 | `LeverArmMigration` | `RegressionLeverArmMigration` |
| `mutants_arm_facade.py` | 4 | the `Main` arm accessors with no scene | `RegressionLeverArmSystem` |
| `mutants_lever_arm_system.py` | 61 | `LeverArmSystem`, the arm interpolation, `LightTracker`, `setParent` | `RegressionLeverArmSystem`, `RegressionLeverArmAttach`, `RegressionLeverArm` |
| `mutants_container_stock.py` | 16 | `ContainerStock`: when contents are loaded, shelved and given back, what `clear()` forgets | `RegressionContainerStock` |
| `mutants_mesh_voxel.py` | 27 | the mesh path of the voxelizer, `PbdMeshData.bounds`, the shot's broad phase (`DestructionWorld.findFresh`) | `RegressionMeshVoxel`, `RegressionDestructionWorld` |
| `mutants_addon.py` | 102 | the add-on: exporter and importer (poses, embed, the File metadata box, which characters an id may hold), its panels and buttons, registration, sources that cannot be read | `blender_addon_check.py` |
| `mutants_addon_real.py` | 6 | what only real Blender sees: no refresh before export / after import, an icon or a panel space Blender does not have, a choice list whose default is not a choice, an attribute that does not exist | `blender_real_check.py` |
| `mutants_docs.py` | 31 | the examples in the docs (the spec's and the add-on guide's) | `RegressionSpecExamples` |
| `mutants_doc_counts.py` | 25 | `check_doc_counts.py`: what counts as a quote, the totals, "K/K mutants", the journal, the real Blender run | its own `--self-test` |

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

To add a mutant: copy a line of a spec file, name what the bug would be, give an `old` text that occurs in the
file exactly as written and the `new` text that breaks it. Write it for a behaviour you rely on; if it survives,
add the check that kills it - the survivor is the finding.

Not yet covered by a spec file: the rest of the voxel chain (`VoxelCrater` ... `RigidDebris`, the flow inside
`DestructionWorld`), `PbdRefCopy`'s copy logic and
`RegressionLeverArm`'s parser tests were exercised by hand-made mutants in earlier sessions, but those were not
kept. Add them when you next touch those classes.

## F. What only a person with the right machine can check

- **The viewer on a GPU**: `docs/ROADMAP.md`, section 5, lists ten things with what "correct" looks like (G on a post
  and on a textured wall, a dragged door, the pickup sound, scene switching mid-drag, hot loading...).
- **Blender itself**: section C above.
- **The server.** `server/upload.php` and `tutorial.php` are not part of this repository, so the contract can only be
  tested from the client side: the request shape `upload_server.py` builds (fields, multipart layout, status codes)
  and its error handling. Whether moderation accepts a `leverArm` block is **unknown** until a file is uploaded to
  the real server. Say that boundary out loud in a bug report instead of guessing at server code.

## Adding a feature: the recurring checklist

1. Put the logic in a pure class (section B); write the `Regression*.java` first or beside it; add it to the list
   in `run.sh`.
2. If it adds a field to the format: section A's four steps, and a `<!-- check: scene -->` example in
   `docs/PBD_FORMAT_SPEC.md` (section D).
3. If it touches the add-on: extend `blender_addon_check.py` with a scenario (it runs on the fake and, with `bpy`, on real
   Blender - a button gets pressed there for free), and run `blender_real_check.py` if you have `bpy`; for what the
   panels look like, open the file in Blender (section C).
4. Break it on purpose (section E) before you trust the green.
5. Update `docs/ROADMAP.md` (state, evidence, what is NOT verified) and `docs/STATUS_SUMMARY.md` in the project's
   existing style - including self-corrections when a first attempt turns out wrong; that history is a feature.
   Cite counts from the last full run, not from memory (and in the form section D describes, so
   `check_doc_counts.py` keeps them honest).
