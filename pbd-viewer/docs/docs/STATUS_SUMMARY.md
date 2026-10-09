# Status summary (distilled from docs/ROADMAP.md)

A status-by-feature snapshot, rewritten on 2026-10-07 and brought up to date on 2026-10-08. It is not a replacement for `docs/ROADMAP.md`: the
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
  was also shown to *fail* when the code under it is broken on purpose (`tools/regression/mutate.py`).
  "I just watched it pass" and "the roadmap says so" are kept visibly separate; given this project's history
  (bend and taper were each marked fixed and then found wrong more than once), that separation is on purpose.

## Format layer (.pbd / .pbdbin / .pbdasset)

| Item | Status | Note |
|---|---|---|
| Every field through every format (pretty `.pbd`, minified `.pbd`, `.pbdbin`, `.pbdasset`) | **done-and-tested** | `RegressionFormatRoundTrip` (94): one scene with every field, compared by a reflection walk, plus a completeness guard so a field nobody sets cannot hide. It found six data-loss bugs (several `include_material` lines, includes with a space, curves never written, unquoted values, materials of placed files, `.pbdasset` bundling only the last include) - all fixed. 25/25 mutants killed. |
| Multi-word and awkward scene metadata (`kind = static prop container`, a `#`, `=`, `,`) | **done-and-tested** | Same suite; the writers quote exactly when a bare value would not read back. |
| Minified `.pbd` / `.pbdbin` metadata swallowing the rest of the file | **done-and-tested** | Fixed on **both** producers (`PbdSerializer.quoteMetadataValue`, the add-on's `_quote_metadata_value`). `RegressionMinifiedMetadataQuoting` (6), the round trip and the add-on check guard it. Was this file's former "single most severe open bug". |
| Unquoted, `=`-padded base64 `vertexData` (the project's own `mushroom.pbd`) | **done-and-tested** | The parser reads it bare now (`readEncodedValue`); 4 checks in the round trip, 2 in the add-on check; 4/4 mutants. Writers still quote it. |
| `include_material` through every format | **done-and-tested** | The scene keeps the list of every include; `.pbdasset` bundles all of their materials and textures. |
| `pbd_ref` copies everything (keyframes, lever arms, lights, `indestructible`, meshes, container triggers, materials of the placed file) | **done-and-tested** | `RegressionPbdRefCopy` (248). |
| Files a `pbd_ref` points to inside a `.pbdasset` | **pending - known limit** | Not bundled: flatten or embed first (`PbdConvertMain`, the add-on's "Embed contents"). |
| A value containing `"` | **pending - known limit** | The format has no escape; the writers cannot write it. Numbers are written with 6 significant digits; re-serializing drops comments and formatting. |
| LOD tier field (`vertexDataLod<N>`): Blender writes 0/1, Java "expects" powers of 2 | **pending** | The Java side takes any `<N>` as an ordering key, so the suspect is the add-on's LOD UI/export - still unread. |

## Lever arm (new since the previous revision of this file)

| Item | Status | Note |
|---|---|---|
| `leverArm { openPos openRot speed release locked lockAt openSound closeSound }` in every format | **done-and-tested** | `RegressionLeverArm` (45), the round trip, `RegressionLeverArmAttach`, the add-on check. Specification checked line by line: `docs/ROADMAP.md`, section 2. |
| Run-time system: value, lock, snap/free release, open/close sounds, grab, drag | **done-and-tested** | `RegressionLeverArmSystem` (147) - pure class, no GPU. The drag is checked against a hinge worked out by hand (worst error 0.0002 degrees). |
| Any primitive can hang on an arm, and can be an arm; lights follow; run-time `setParent` | **done-and-tested** | `RegressionLeverArmAttach` (90): all ten types, nesting, placed files, all four formats. |
| AI/modding accessor `Main.getArmValue / isArmLocked / setArmLocked` | **done-and-tested** (no scene) / **done** (live) | The "no scene loaded" contract is tested (4/4 mutants); the live path needs a renderer. |
| Container contents follow a dragged door (facade link) | **done** | `PbdRenderer` reads the arm value; GL-bound, reviewed. |
| Mouse wiring and the feel of the drag in the viewer | **pending** | `Main.java` mouse handling is reviewed only. Grab a door in-engine before video 2. |
| Real physics behind the drag (inertia, collisions) | **not done** | The drag is geometric - the prototype you chose first. |
| Rattle sound when pulling a locked arm | **not done** | One new field across all formats; the round trip's completeness guard will insist on it. |
| Keyframed doors -> lever arms | **done-and-tested** (tool) / **pending** (your library) | `PbdConvertMain lever` (`RegressionLeverArmMigration`, 57; 29/29 mutants). Running it over the asset library is a command for you to run. |
| Blender: Pivot (hinge), Lever Arm panel, "Capture Current Pose as Open", export/import | **done-and-tested** | Against the engine, on a fake `bpy` with Blender's real `mathutils` (`blender_addon_check.py`, 247; 102/102 mutants, `mutants_addon.py`): export, import, and the add-on's own panels, menus, dialogs and buttons drawn and pressed on a recording layout. Also on real Blender 4.2, 4.5, 5.0, 5.1 and 5.2 (`blender_real_check.py`, 248 on each; 6/6 mutants that only it can see, `mutants_addon_real.py`): every icon looked up, every button pressed once, the zip installed by Blender's own installer. **Not run**: the panels in a window (how they look), undo, Blender 4.0.2 itself (yours: only its icon list and API stubs were compared), 4.1, 4.3 and 4.4. |

## Voxel destruction (G key)

| Item | Status | Note |
|---|---|---|
| One G hit cuts a post or a plank in two; the severed piece falls and comes to rest on the floor | **done-and-tested** | Solid crater of 30 voxels (`VoxelCrater`), `VoxelConnectivity`, `VoxelDebris`, `RigidDebris`, `DestructionWorld` - `RegressionDestructionWorld` (56) and the suites under it. Pictures: `docs/renders/g_*.png` (software rasteriser, not OpenGL). |
| A `mesh` instance can be shot too, if it is a closed mesh | **done-and-tested** | `RegressionMeshVoxel` (61): the surface by an exact triangle / box test and the inside by ray parity, so a closed mesh becomes a solid (a unit cube mesh fills all of its voxels - it used to lose 21 %), placed where it stands whatever its scale, offset and rotation, found by the shot through its real vertex bounds (`DestructionWorld.findFresh`), cut and its pieces dropped. An open or flat mesh (a quad, a decal) is refused and the G hit says so. 27/27 mutants killed. GL drawing of the remains of a mesh: **reviewed** only. |
| Primitive voxelization formulas (cube/cylinder/cone/sphere/torus/disc/plane) | **done-and-tested** | `RegressionPrimitives`: ratios match pi/4, pi/12, pi/6, ~0.155. |
| Anisotropic octree subdivision for thin geometry | **done-and-tested** | True per-axis splitting (the old entry said "not a full fix"; the later non-cube entry did it). Thin panel: 3,916 regions. |
| Taper applied during voxelization | **done-and-tested** | `RegressionTaper` (3), `RegressionTaperWide` (1): a widening taper voxelizes to 1.00017 of the exact frustum volume. |
| Bend / twist / curve / shear applied during voxelization | **not done** | The bend maths is solved (closed-form inverse, 300k trials) but not wired in; G targeting still tests the unmodified box. **Question for you**: the older "Bend modifier - proper fix" entry asks for a Blender-style loop cut + Simple Deform for how a bent shape is *drawn*; the newer one solves how to *voxelize* it. Different problems - does the on-screen bend still need the rework? |
| Crater cost / out-of-memory at wide radius | **done-and-tested** | `RegressionCraterOOM` (16) under `-Xmx512m`. |
| Per-voxel texture colour | **done** | Per vertex of each voxel face, not per fragment; UV fixed for non-cubic objects; double decode removed. GL-bound. |
| Textured-primitive G native crash (`0xC0000374`) | **pending** | Mitigated several times, never reproduced here, never confirmed fixed. Needs the console output of a real press. |
| Shared/cached shader program; NaN hit distance on hidden instances; click-toggle of hidden voxel debris; `destroyedRenderers` cleared on scene switch | **done** | Re-read against the current code; GL-bound, not executed. |
| "Rays pass through voxels" | **pending** | Never reproduced; the one real bug found (fallback to the camera position) is fixed. |
| Known limits (what-stays-attached heuristic, pieces don't collide, landing surfaces, dust dropped) | documented | `docs/ROADMAP.md`, section 4. |

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
| Door-closed accessor for AI code | **pending** | By instance (`isInstanceFullyClosed`) and by arm value (`Main.getArmValue`) it exists; by container *name* it does not. |
| An emptied container stays empty when its door is opened again | **done-and-tested** (the rules) / **done** (the wiring in `Main`'s frame loop: reviewed, not run) | `RegressionContainerStock` (30; 16/16 mutants, `mutants_container_stock.py`): `ContainerStock` loads a group's contents on its first opening only, puts them on a shelf when every door is shut and gives the same contents back, so what the player took stays gone. Until the scene changes or the viewer restarts - nothing is saved to disk. The renderer of a taken item is released as well (it used to leak). The other half of the old request, a better **distribution of items across linked containers**, is **not done**. |
| H-key exit (inverse of how the player got in) | **not done** | Needs reading the hide-transition code first. |

## Audio

| Item | Status | Note |
|---|---|---|
| Sound on E pickup (`pickup.wav`), G shot (`doomshotgun.wav`), door sounds | **pending** | The triggers are wired and both fixed sounds are preloaded; **the `.wav` files are not in the upload**. `SoundPlayer` decodes `.wav` only (no MP3) and only logs a warning when a file is missing. |

## Server and tutorial

| Item | Status | Note |
|---|---|---|
| Moderation accepts a `leverArm` block | **pending (unknown)** | `server/upload.php` is not in the upload. The add-on's client does no block-level validation. |
| `tutorial.php`: reuse and quality-submission sections | **done** (per roadmap) | Not in the upload either. |
| `tutorial.php`: lever arm, pivot, embedding, "re-use existing resources with `include_material`" | **pending** | Draft text: `docs/ADDON_USER_GUIDE.md`, section "Draft text for the tutorial". |

## Documentation

| Item | Status | Note |
|---|---|---|
| `PBD_FORMAT_SPEC.md` matches the parser | **done-and-tested** | `RegressionSpecExamples` (35) parses every `<!-- check: ... -->` example with the real parser and pins the lever-arm examples (the spec's and the add-on guide's) to the numbers in the text; 31/31 doc mutants killed. Prose around the examples is reviewed, not tested. |
| `ROADMAP.md`, `ENGINE_DEV_GUIDE.md`, `ADDON_USER_GUIDE.md`, `TEST_PROCEDURE.md`, `tools/blender-addon/README.md` | **done** | Brought up to date on 2026-10-08 (the add-on guide gained the lever-arm / pivot section and a draft of the tutorial text). |
| The numbers the docs quote (checks per suite, mutants per spec, the totals) | **done-and-tested** | `tools/regression/check_doc_counts.py`, the last step of `run.sh`, counts the `PASS:` lines and the mutants and fails on any document that quotes another number or a total nobody can match. It first checks itself on a made-up document (`--self-test`), and 25/25 mutants of the checker are killed (`mutants_doc_counts.py`). |
| Web visualizer; rewrite of the site's index and tutorial pages | **not done** | "Eventually". |

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
empty placeholder and a warning; the sidebar's first line said `v0.41.0` whatever the version (it now reads `bl_info`,
version 0.44.0); the fallback for the materials folder looked four folders up instead of five; the user guide described a
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
