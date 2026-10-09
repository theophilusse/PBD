# Roadmap

How this file is organised. Sections 1-6 are the **current state**, rewritten on 2026-10-07 and brought up to date on
2026-10-08 - read these first. Section 7 is the **journal**: every entry the project ever wrote, newest first (the oldest are undated), kept as it was
(self-corrections included - a wrong conclusion that was later fixed is worth keeping) with a short status
note added wherever a later entry changed the picture. Other docs: `STATUS_SUMMARY.md` (one table per area),
`TEST_PROCEDURE.md` (how to check everything), `PBD_FORMAT_SPEC.md`, `ENGINE_DEV_GUIDE.md`,
`ADDON_USER_GUIDE.md`.

Three words are used on purpose, and never loosely:

- **verified** - a test in `tools/regression/` ran the real code and passed. Where it matters the test was
  also shown to *fail* when the code under it is deliberately broken (mutation testing, see
  `TEST_PROCEDURE.md`), so "passes" is not the same as "cannot fail".
- **reviewed** - the code was read line by line but nothing could run it here (everything that needs an
  OpenGL window, Blender itself or the PHP server).
- **unknown** - not available here at all.

## 1. State of the project (2026-10-08)

**What exists.** A scene description format (`.pbd` text, `.pbdbin` = gzip of the minified text,
`.pbdasset` = zip with its materials/textures), a Java engine (LWJGL/OpenGL viewer: GPU tessellation of
primitives, materials, keyframe animation, lights, containers and the facade for AI/modding code, voxel
destruction on **G**, item pickup on **E**, lever arms you can grab with the mouse), a Blender add-on that
reads and writes the format, the Project Zomboid converters, and a moderation/upload server
(`pbd.mazetrojan.fr`, **not part of this repository** - see section 6).

**What this iteration did** (details in the journal):

1. **Lever arm, finished against its original specification** - see section 2, line by line. Free or locked,
   open/close sounds, anything attachable, the value readable by AI code, a smooth mouse drag, in every
   format and in the Blender add-on.
2. **The two checks you asked for** - section 3: every primitive type can hang on an arm (and be the arm),
   and one G hit now cuts a post or a plank in two and the loose piece falls and settles.
3. **A format-coherence test that does not trust anyone to remember anything** - one scene with every field
   the format has goes through pretty `.pbd`, minified `.pbd`, `.pbdbin` and `.pbdasset`, and what comes back
   is compared field by field by a reflection walk. It found six real data-loss bugs (journal, 2026-10-07).
4. **The Blender add-on checked against the engine's own parser** - without Blender (fake `bpy`, real
   `mathutils`, the engine's real parser and hierarchy resolver as the judge). It found a real export bug
   (children of a primitive were exported at twice the size/offset).
5. **A migration tool** for the keyframe doors you wanted replaced before video 2:
   `PbdConvertMain lever <file|folder> <output>`.
6. **Documentation checked by the code it describes.** `PBD_FORMAT_SPEC.md` was rewritten against the code (its
   lever-arm section knew none of `release` / `locked` / `lockAt` / the sounds, and its curves example used a
   syntax the parser does not read); every marked example in it is now parsed by the real parser on each run
   (`RegressionSpecExamples`), so a doc cannot drift quietly. The mutation tooling that backs the "tests can fail"
   claims lives in the repository instead of a scratch folder.
7. **A shot at a `mesh` instance** (section 3.3). It had never been tried and was broken four ways - a closed cube
   mesh lost 21 % of its volume, a scaled mesh stood in the wrong place, a flat mesh became a block over a metre
   thick, and the broad phase tested the wrong box. Fixed, with `RegressionMeshVoxel` and 27 mutants.
8. **The Blender add-on can no longer write a file the engine refuses because of a name.** An object called
   `front door` was exported as `cube front door {` (found by a probe while adding the Description field); ids are
   now cleaned up and reported, the importer reads every id the engine reads, a `#` or a tab in free text is quoted
   as the Java writer does, and the File metadata box has a **Description** field (backlog item 14).
9. **The numbers in the docs are checked by code** (`tools/regression/check_doc_counts.py`, the last step of
   `run.sh`; it checks itself first and has 25 mutants of its own): on its first pass over the finished state it
   reported 24 numbers that the day's changes had made stale.
10. **An emptied container stays empty** (backlog item 7): what the player takes with **E** is still gone when the
   door is closed and opened again. The rules moved out of `Main` into the pure `pbd.pz.ContainerStock`, with
   `RegressionContainerStock` and a mutant spec of its own.
11. **The Blender add-on run on real Blender** (Blender as a Python module: 5.2.2 first, then 4.2, 4.5, 5.0 and 5.1), and its interface code drawn on a
   recording layout on the fake. Findings, all fixed and pinned: a script that edits objects and exports at once got the
   positions from before its edits (Blender refreshes its matrices between two clicks, not two lines), so an imported then
   exported scene came out with a door panel three units off; an import with one unreachable `pbd_ref` stopped with a
   traceback instead of leaving a placeholder and a warning (a failed fetch was a plain `Exception`, the importer catches
   `OSError`); the sidebar said `v0.41.0` whatever the version (now read from `bl_info`: 0.44.0); the materials-folder
   fallback was one folder short; the user guide described a "Project Root" field that no longer exists; and the
   `pbd_tools.zip` people install was a copy of the sources from before all of this (0.43.1, ten of its twelve files different, no lever arm in it at all) - the add-on
   check now compares it with the sources, and a real Blender installs, enables and disables it. Pressing every
   button once found nothing else.

**How much is checked.** 20 Java suites (964 checks) + the Blender add-on check (247 checks) = 1211 checks,
all green; run them all with `tools/regression/run.sh` (`TEST_PROCEDURE.md`), which ends by comparing the numbers the
docs quote with the ones it has just counted. The newest suites were also shown to **fail when the code under them is
broken on purpose** (mutation testing: `tools/regression/mutate.py` and the `mutants_*.py` files next to it re-run it):
11 spec files, 330 mutants, all killed: `mutants_addon.py` (102), `mutants_addon_real.py` (6), `mutants_roundtrip.py` (25), `mutants_bare_base64.py` (4), `mutants_migration.py` (29),
`mutants_arm_facade.py` (4), `mutants_lever_arm_system.py` (61), `mutants_mesh_voxel.py` (27),
`mutants_container_stock.py` (16), `mutants_docs.py` (31), `mutants_doc_counts.py` (25).

| Area | Suite (checks) | Covers |
|---|---|---|
| Primitives / taper / colour | `RegressionPrimitives` (7), `RegressionTaper` (3), `RegressionTaperWide` (1), `RegressionPerVertexColor` (2) | voxel volumes vs. the exact formulas, taper vs. frustum volume, colour per vertex |
| Voxel destruction chain | `RegressionCraterOOM` (16), `RegressionVoxelCrater` (19), `RegressionVoxelConnectivity` (17), `RegressionVoxelMesh` (10), `RegressionRigidDebris` (20), `RegressionMeshVoxel` (61), `RegressionDestructionWorld` (56) | crater cost under a 512 MB heap, crater shape, split into pieces, meshing, falling bodies, a `mesh` instance as the target, the whole world (G -> cut -> fall -> rest) |
| Formats | `RegressionMinifiedMetadataQuoting` (6), `RegressionFormatRoundTrip` (94), `RegressionPbdRefCopy` (248) | minified files, every field through every format, `pbd_ref` copies everything |
| Lever arm | `RegressionLeverArm` (45), `RegressionLeverArmSystem` (147), `RegressionLeverArmAttach` (90), `RegressionLeverArmMigration` (57) | the format block, the run-time system (value, lock, release, sounds, grab, drag), anything attachable, the keyframe -> arm tool |
| Containers (E pickup) | `RegressionContainerStock` (30) | what a container remembers between two openings: loaded on the first opening only, shelved when every door is shut, given back as it was left (an emptied container stays empty), forgotten on a new scene |
| Documentation | `RegressionSpecExamples` (35), `check_doc_counts.py` | every example marked `<!-- check: scene -->` / `<!-- check: material -->` in `docs/*.md` is parsed by the real parser; the spec's and the add-on guide's lever-arm examples are pinned to the numbers the text states; an unmarked example that shows a `leverArm` fails; the numbers the docs quote (checks per suite, mutants per spec, totals) are compared with the run |
| Blender add-on | `blender_addon_check.py` (247) | the add-on's export/import against the engine, closed/half/open/keyframe poses, the File metadata box, names the format cannot hold; its panels, menus, dialogs and buttons drawn and pressed on a recording layout; registration; sources that cannot be read; the committed `pbd_tools.zip` is the sources (it was not: it still held 0.43.1) |
| Blender add-on, on real Blender | `blender_real_check.py` (248) | the same scenarios with the add-on registered in Blender 4.2, 4.5, 5.0, 5.1 and 5.2 (248 on each): every icon exists, the registered settings are the declared ones, every button pressed once (none dies with an exception), a script's edits exported and imported at once, the zip installed, enabled and disabled by Blender's own installer. Needs `pip install bpy`; not part of the totals above |

**What is NOT verified, and why** (the honest list - none of it is hidden in a test that passes):

- Everything that needs a GPU window: the real GL drawing of voxel remains and flying pieces, the mouse
  wiring in `Main.java`, how a drag *feels*, shader paths, frame times. The pure logic underneath is
  verified; the glue is **reviewed** only. The pictures in `docs/renders/g_*.png` are produced by the real
  `DestructionWorld` and a small software rasteriser (`tools/regression/VoxelFallDemo.java`) - they show
  what the engine is *told to draw*, not what OpenGL draws.
- Blender's **window**: how the panels look, undo, the viewport drawing of `mesh_preview.py`. The add-on is run on a
  fake `bpy` with Blender's real `mathutils` and - with `pip install bpy` - on **real Blender 4.2, 4.5, 5.0, 5.1 and 5.2** (operators,
  registration, icons, depsgraph), but never drawn in a window. Your Blender (4.0.2), 4.1, 4.3 and 4.4 were not run (backlog item 23 says what was done for 4.0).
- The PHP server (`upload.php`, `tutorial.php`): not in the upload. Whether moderation chokes on a
  `leverArm` block is **unknown**.
- The `.wav` files (`doomshotgun.wav`, `pickup.wav`, any door sound): not in the upload. `SoundPlayer` logs a
  warning and carries on, so nothing crashes, but nothing is heard until the files are there.
- The native crash on G over a *textured* primitive (Windows `0xC0000374`): mitigated several times (bounds checks, a rollback, a shader cache, decoding at scene load, the double-decode fix),
  never reproduced here, never confirmed fixed.

**Ground rules in force.** The real folder layout is never changed (new files go inside existing folders).
Nothing is cached in the destruction path (assets can be hot-loaded). No claim of "fixed" without a test
that failed before and passes after.

**Behaviour changes you will notice** (all intended):

- **G is much stronger**: the crater is a solid irregular volume of radius 30 voxels (a hole about 0.30
  world units across) instead of a thin ray tunnel - see section 3.2.
- **Blender export of a child of a primitive changed**: the child is exported with `pos/parent_factor` and
  `scale x own/parent` (a Blender primitive is 2x2x2, the engine's 1x1x1). Earlier exports of such children
  were two times off; **re-export any scene that has parts parented to a primitive**.
- **An embedded `pbd_ref`** (the add-on's "Embed contents" box) is now anchored on a `group`, not on a
  `metadata` cube. Old embedded files still load.
- **The writer keeps more**: curves, every `include_material`, awkward values (spaces, `#`, `=`, `,`) and the
  materials of placed (`pbd_ref`) files are now written back by every format.
- **Blender object names**: a name the format cannot hold is written cleaned up (`front door` -> `front_door`,
  a clash gets `_2`) and the export lists what changed; before, such a scene could not be loaded at all. A material
  name with a space is only reported. The importer now accepts every id the engine does (`wall-left`, `a:b`).
- **A `mesh` instance can be shot** if it is a closed mesh; an open or flat one is refused and the G hit says so.
- **A container remembers what you took**: close its door and open it again and the item is still gone (and what
  rested on it has fallen); before, the whole content was rolled again. Until the scene changes or the viewer
  restarts - nothing is saved to disk.
- **Left click**: a lever-arm instance (or anything attached to one) is grabbed and dragged; a keyframed
  door still toggles on click. A locked arm reports "locked" and does not move.

## 2. Lever-arm specification, checked line by line

The original specification is the roadmap entry "Lever-arm system (major feature, format-wide)". An earlier
revision of this file had cut it down to "what is still open", so here is its **first wording in full** - the
reference the table below is judged against:

> A new primitive/mechanism, reflected across EVERY format (`.pbd`, `.pbdbin`, `.pbdasset`, the Blender addon, the Java engine):
> - Free rotation OR a lockable position.
> - Other primitives can be linked/attached to it (same idea as `linkGroup` for synchronized doors, but a real parent-like
>   attachment, not just synchronized open state).
> - Two defined endpoints: 0 = closed, up to 1 = fully open - meant to let PHYSICS drive a door's position continuously
>   (0..1) instead of the current keyframe-interpolated swing, which is reported to produce visual glitches.
> - `getArmRotationValue()` (name to be finalized) - lets AI/modding code read how open a door-like arm currently is, e.g. to
>   decide whether a zombie can see through a door that's ajar rather than only checking a binary open/closed state, and to
>   gate whether a container's own contents should be shown/hidden.
> - SFX support the same way a keyframe can carry a `sound=` - a lever arm should be able to sonify its own opening/closing motion.
>
> This is the largest item here and likely needs its own dedicated design pass (format spec changes, parser/serializer support
> in both Java and the Python addon, PHP server handling if it affects upload/moderation, renderer support) before
> implementation starts, not a single-turn addition.

Three clarifications came later and are included in the table: the arm needs an **open angle and a closed angle** (to
link with the other modules - the facade, e.g. what is displayed inside a container); there need **not be any baked
animation**; and the door should open with a **mouse drag and drop with physics** (you chose a geometric prototype first,
real physics later).

| # | What was asked | State | Where it lives | Evidence |
|---|---|---|---|---|
| 1 | "reflected across EVERY format (`.pbd`, `.pbdbin`, `.pbdasset`, the Blender addon, the Java engine)" | **verified** (server: unknown, row 9) | `.pbd`: `PbdInstance.LeverArm`, `PbdParser.parseLeverArm`, `PbdSerializer` (pretty and minified). `.pbdbin` is gzip of the minified text, `.pbdasset` zips `scene.pbd` - neither re-implements the schema, so both carry it. Blender: `properties.py`, `ui_panel.py`, `operators/export_pbd.py`, `operators/import_pbd.py`. Engine: `HierarchyResolver`, `LeverArmSystem`, `PbdRenderer` | `RegressionLeverArm` (45): parse + pretty/minified round trip, interpolation, clamping. `RegressionFormatRoundTrip` (94): a door whose arm has every option set (`release = free`, `locked`, `lockAt`, both sounds - one with a space in its name) goes through all four formats and is compared field by field. `RegressionLeverArmAttach`: attachments survive all four. `blender_addon_check.py` (247): the pose the engine computes from the add-on's export equals Blender's at arm 0 / 0.5 / 1 |
| 2 | "Free rotation OR a lockable position" | **verified** | `release = snap` (default: eases to the nearer end) `\| free` (stays where it was left); `locked = true`, `lockAt = 0..1`; run-time `setArmLocked` / `isArmLocked` (`PbdRenderer`, and `Main` for mods) | `RegressionLeverArmSystem`: a locked arm ignores easing, drag, `setValue`, `setTarget` and release; can still be *picked* so the game can say "it's locked"; unlocking applies the release policy; locking mid-swing freezes it; linked twins; format: `RegressionLeverArm`; add-on fields: add-on check |
| 3 | "Other primitives can be linked/attached to it ... a real parent-like attachment, not just synchronized open state" | **verified for every primitive type** | `parent = <arm>` (also on a `pbd_ref ... parent=`), run-time `PbdEngine.SceneHandle.setParent(child, parent)` | section 3.1: `RegressionLeverArmAttach` (90) |
| 4 | "Two defined endpoints: 0 = closed, up to 1 = fully open - meant to let PHYSICS drive a door's position continuously (0..1) instead of the current keyframe-interpolated swing" - and later "an open angle AND a closed angle, to link with the other modules (facade)" | **verified** (geometry + value); the "physics" is a **geometric drag** - your choice ("Prototype simple maintenant"); a physics engine is still open | the instance's own `pos`/`rot` = closed (0); `leverArm { openPos openRot }` = open (1); `HierarchyResolver.resolve(scene, animTimes, armValues)`; `LeverArmSystem.setValue/dragToward` | `RegressionLeverArm` (lerp at several values, clamping, NaN -> closed); `RegressionLeverArmSystem`: the drag is checked against a hinge worked out by hand - worst error 2.3e-6 of the 0..1 range (0.0002 degrees), 400 random cameras/values, a sliding arm, an arm under a moved and turned parent, exact ends, no jump over 1 % per 0.5 % of aim, about 0.015 ms per frame |
| 5 | "`getArmRotationValue()` (name to be finalized) - lets AI/modding code read how open a door-like arm currently is ... and gate whether a container's own contents should be shown/hidden" | **verified** (value) / **reviewed** (GL wiring); named `getArmValue` - the value is not always a rotation (a drawer slides) | `PbdRenderer.getArmValue(id)`, **`Main.getArmValue(id)`** (static facade next to `availableVolume`/`canHide`, added today - before, only `PbdRenderer` had it), `LeverArmSystem.value`. Facade link: `isInstanceOpen/FullyOpen/FullyClosed` read the arm value for lever-arm instances, so contents appear when a *dragged* door is open | `RegressionLeverArmSystem`: value contract, "no arm -> NaN", facade-without-a-scene answers NaN/false instead of throwing (3 checks, 4/4 mutants). The container wiring in `PbdRenderer` is **reviewed** |
| 6 | "SFX support the same way a keyframe can carry a `sound=`" | **verified** | `openSound` / `closeSound`: played once when the arm leaves / returns to the closed end, whoever moved it (drag, script, easing); hysteresis (closed at 1 % or less, "left the closed end" only above 2 %) so aim jitter cannot chatter; a looping arm never clicks | `RegressionLeverArmSystem` (nine checks: one sound per crossing, none while it keeps moving, jitter inside the dead band is silent, a file name with a space survives, silence when settled, none for a looping arm); round trip keeps both names; add-on fields. Actual audio output: needs the `.wav` files |
| 7 | "needs a dedicated design pass: format spec, parser/serializer in both Java and the Python addon, PHP server if it affects upload/moderation, renderer support" | **done** except the server | `PBD_FORMAT_SPEC.md` (`## Lever-arm`, rewritten with every field), `ADDON_USER_GUIDE.md`, `ENGINE_DEV_GUIDE.md`, `tools/blender-addon/README.md` | `RegressionSpecExamples` (35): the spec's three lever-arm examples (a door on a pivot, a drawer, a locked lid) and the add-on guide's door are parsed by the real parser on every run and pinned to what the text says (90 degrees about Y, speed 2, snap, two sounds one of them with a space in its name; a pure slide that stays where it is left; locked at 0 and opening -100 degrees about X); 31/31 doc mutants killed |
| 8 | later: "no mandatory baked animation - I want to open the door with a mouse drag and drop with physics" | **prototype done**: geometric drag-and-drop, no physics engine | `LeverArmSystem.pick` (what a click grabs - through any attached primitive), `dragToward` (coarse sampling + golden-section refinement, no hinge assumed), `PbdRenderer.findLeverArmGrabPoint/dragArmValueTowardRay`, `Main` mouse handling | the maths: `RegressionLeverArmSystem`. The mouse wiring and the feel: **reviewed only** - grab a door in-engine before video 2 (section 5) |
| 9 | PHP server (`upload.php` moderation, `tutorial.php`) | **unknown** | not in the upload; `upload_server.py` (the add-on's client) does no block-level validation, so it will not block a lever-arm submission | `tutorial.php` needs sections for the lever arm, the pivot and embedding (section 6) |
| 10 | later: "the keyframes on the doors have to be replaced by lever arms before video 2" | **tool done**; running it over the asset library is yours (the library is not in the upload) | `LeverArmMigration`, `java pbd.PbdConvertMain lever <file.pbd\|.pbdbin\|folder> <output>` | `RegressionLeverArmMigration` (57), 29/29 mutants. Converts a door's rotate/slide keyframes to an arm (and its sound to `openSound`/`closeSound`), leaves anything it cannot express alone and says why (journal, "keyframe -> lever-arm migration tool"). It never overwrites its input |

Extras that were built because a check of the above showed they were needed: **lights follow arms**
(`LightTracker`: a lamp hung on a door used to stay behind in the buffer while its mesh moved), **run-time
`setParent`** (it only changed an index before, so a save lost the attachment), **`pbd_ref` copies everything**
(keyframes, arms, lights, `indestructible`, meshes, container triggers were being dropped - `RegressionPbdRefCopy`,
248 checks), and **a Pivot in Blender** (Create > Pivot (hinge): the engine's `group`) because an arm is best put on a
scale-1 pivot.

**Known limits of the lever arm** (all documented in `PBD_FORMAT_SPEC.md`):
a pivot's scale must stay 1 (children inherit their parent's *scale* as well as its pose); an arm on a `ref` or
`light` object is not exported from Blender (use a pivot); poses are interpolated as Euler angles, angle by angle
(the format can express a swing of more than 180 degrees about one axis, but the Blender add-on writes the Euler
triple nearest the closed pose, so it exports such a swing as its shorter equivalent - hand-write `openRot` for
such a door); keyframes win over an arm if an instance has both; Blender shows the arm poses but has no physics or
drag; mid-pose agreement between Blender and the engine is only guaranteed for single-axis rotations.

## 3. The two checks asked for on 2026-10-07

### 3.1 Can other primitives be attached to a lever arm? - yes, every one of them

`RegressionLeverArmAttach` (90 checks) was written against the primitive registry, so a primitive added
tomorrow is covered without touching the test (the first check fails loudly if the list changes):

- All ten types - `plane, sphere, cylinder, cone, cube, disc, torus, mesh, light, group` - attached to a pivot
  move **and turn** with it at arm 0, 0.37 and 1; the expected poses are computed by hand, not by the engine's
  own matrix code.
- Each of the ten can also **be** the arm (a position-plus-rotation arm), and what rides on it follows.
- Clicking any attached primitive that has geometry grabs the **arm** (and reports the point in the arm's own
  frame, which is what a drag keeps fixed). A `light` or a `group` has nothing to click and is only reachable
  through what hangs on it. A mesh far from its origin is grabbed where its triangles are.
- **Nesting**: a drawer that is its own arm, inside a door that is an arm, with a knob and a tag on the
  drawer - 6 combinations of door x drawer positions; the drawer slides along the *door's* own axis; a click on
  the knob grabs the drawer (nearest arm), not the door.
- **A placed file hangs on it too**: a `pbd_ref ... parent=hinge` of a `.pbd`, a `.pbdbin` and a `.pbdasset`
  - the referenced file's group and both its parts sit where the hinge puts them at 0, 0.6 and 1, and grabbing
  the handle that came from the file grabs the door.
- Attachments survive **all four formats**, and a light on the arm moves its light (`LightTracker`, 12 checks).
- **Run-time attach/detach** (`setParent`): the saved text carries `parent = hinge` (pretty and minified),
  parents are kept before children, an unknown name / a cycle / "own parent" are refused and change nothing,
  detaching makes the part a root again. The pose is not compensated in either direction (its `pos`/`rot`/`scale`
  are kept as written and read relative to the new parent, or as world values after a detach), which the
  `setParent` doc now says.
- Scale: a handle under a scale-1 pivot keeps its size; under a scaled panel it inherits the scale (expected
  values worked out in the test). That is why the add-on recommends a scale-1 pivot as the arm.

In Blender the same is true by construction - anything parented to the pivot is exported with its `parent`
- and `blender_addon_check.py` compares every object's world matrix (door panel, handle, nested parts) between
Blender and what the engine computes from the export, at arm closed / half / open.

### 3.2 Is G powerful enough to cut a primitive, and does the severed piece fall? - yes

**Why it could not before.** The old crater was about forty one-voxel-wide ray tunnels - roughly 500 voxels
whatever the radius - which dented a thing but could never cut it. The crater is now a **solid, irregular
volume** (`VoxelCrater`) of `CRATER_RADIUS_VOXELS = 30` voxels of 5 mm: a hole about 0.30 world units across,
digging up to half again as deep along the line of fire, with a rough rim. Rule of thumb: **one hit severs
anything up to about 2 x radius (0.30) across**; a wider panel gets a hole and needs a second hit near its edge.

The whole chain is run without a GPU by `RegressionDestructionWorld` (56 checks) - the same `DestructionWorld`
object `PbdRenderer` drives, with the floor and the other boxes of the scene as landing surfaces:

| Shot (one hit at the middle unless stated) | What the run measured |
|---|---|
| Post 0.1 x 1.0 x 0.1, hit at 0.55 height | 21,859 voxels removed; the stump keeps 34,234 (1,964 geometry pieces); **1 piece breaks off (23,907 voxels)**; falls and **comes to rest asleep, lying on the floor beside the stump** (lowest point -0.5 mm, inside the allowed -4 mm..+1.2 cm), away from the shooter's side. The hit itself takes 60-95 ms |
| Same shot twice | bit-identical debris (the crater is derived from the rounded impact point) |
| Plank 0.05 x 1.0 x 0.25 | cut; standing plank cut low: the small bottom stump stays, the big top falls. The same cut on a *lever-arm* plank (not on the floor): not grounded, so the **largest** piece stays and the small one falls |
| Plank turned about Y, shot through its thin side | cut; the piece starts inside the turned plank's world box (frames agree under rotation); a second shot down the same line goes through the hole |
| Post standing on a table box | grounded by the table's top; the piece lands on the table or the floor, never through either |
| Door panel 0.04 x 1.0 x 0.45 | **not** cut (wider than the crater): a big round hole, one piece. The second shot goes through the hole and hits the wall behind; a shot near the edge removes more of the remains |
| Second shot at the piece lying on the floor | hit from above: 21,862 of its 23,907 voxels are removed; what is left (one piece of 1,753 voxels, 6 crumbs discarded as dust) has a new mesh (the old one is freed), is awake and settles again on the floor |
| Shot at the stump | removes more of it; the piece lying nearby is woken (its support may have changed) and settles where it was |
| A shot at the foot of the post | whole bottom layer gone: nothing is anchored, the big piece falls |
| An object smaller than the crater (a pebble) | fully destroyed, no mesh left open |
| 30 posts cut in a row | at most 24 live chunks (oldest dropped and its mesh closed), no mesh leaked, `clear()` frees everything |
| An indestructible wall, the sky, a zero-length aim | nothing happens, no exception |

Supporting suites: `RegressionVoxelCrater` (19: the crater is a solid volume, deterministic, rough rim),
`RegressionVoxelConnectivity` (17: face-connected pieces), `RegressionVoxelMesh` (10), `RegressionRigidDebris`
(20: the fall - the piece rests within a few millimetres of the floor, then sleeps), `RegressionCraterOOM` (16:
cost under `-Xmx512m`).

**See it:** `docs/renders/g_cut_post.png` (the post is cut, the top falls and settles), `g_cut_plank.png`,
`g_hole_door.png` (a round hole, still one piece), `g_piece_over_crate.png` (the piece lands on a crate),
`g_hit_the_piece.png` (the fallen piece shot a second time). Eight frames each; they are produced by
`java VoxelFallDemo OUT_DIR post|plank|door|cube|crate|post2 [wide|close]` (compile `tools/regression/*.java`
as `run.sh` does) - the real `DestructionWorld`, a tiny software rasteriser instead of OpenGL.

**What is not covered:** how it looks and runs in the real viewer (texture colours on the cut faces, frame time
with 24 chunks, the unconfirmed textured-primitive crash) - section 5.

### 3.3 A `mesh` instance can be shot too (closed meshes)

Shooting a mesh had never been tried. A probe that fired at a cube mesh and measured what came out found it broken
four ways, each now pinned by a test that fails without the fix (`RegressionMeshVoxel`, 61 checks, 27/27 mutants):

| What was wrong | What the test pins now |
|---|---|
| The inside of a mesh is found by casting a ray along +X from each region and counting crossings; a ray that runs exactly along a cube side's diagonal passes through two triangles and counts "one and a half" crossings | a closed unit cube mesh fills **all** of its 100^3 voxels (it lost 21 %); a tetrahedron and an octahedron (rays through vertices and edges) come out at a sixth of their box, a concave L of three cubes at exactly three cubes |
| The grid origin forgot the instance **scale** | the same mesh at scale 2 fills 8 times the voxels and stands at -1..1, not shifted by half its size; scaled differently on every axis it is 200 x 100 x 50 voxels; off-centre and scaled, it sits at 4..6 |
| A flat or open mesh (a quad, a decal, a triangle) was rasterised into a block over a metre thick | refused: null from the voxelizer, and the G hit says "can't be voxelized (... a mesh that encloses no volume)"; nothing is hidden, nothing is left behind |
| The shot's broad phase tested a mesh against the canonical unit box, not its own vertices | `DestructionWorld.findFresh` (new, pure; the renderer delegates to it) uses `PbdMeshData.bounds()`: a ray at the mesh's real position hits it with the hit point in the mesh's own units; a ray where the canonical box would be, but no mesh, hits nothing |

End to end, a mesh post 0.1 x 1.0 x 0.1 shot at mid-height behaves exactly like the primitive post of section 3.2
(21,859 voxels removed, 34,234 kept, **one piece of 23,907 falls and comes to rest beside the stump**), and so do a
scaled and shifted slab and a mesh turned about Y (the remains stand where the mesh stood, at its scaled size).
**Needs a closed mesh**: an almost-closed one (a missing triangle, a duplicated face) fills wrongly along the rays
that pass through the flaw. What is not covered: how the remains of a mesh instance look in the real viewer (the
original is hidden by zeroing its transform and the remains are drawn by `ClassicMeshRenderer`) - section 5, item 11.

## 4. Known limits of the destruction world

Referenced from `DestructionWorld.java`, `VoxelDebris.java` and `VoxelCrater.java`. None is a bug being
chased; each is a decision, with the case where it will look wrong.

- **"What stays attached" is a heuristic.** The format has no support/hinge data. If the object stood on the
  floor (its lowest point within 5 cm of it) every piece still touching its lowest layer (within 2 voxels) stays
  - the ground holds it up; pieces no longer touching it fall; if the whole bottom was blown away, everything
  falls. Otherwise (a hanging door, a wall shelf, a frame) the **largest** piece stays and the rest fall - so a
  door shot through the middle sheds its smaller half, not "whichever half the hinges hold".
- **Pieces do not collide with each other** (each falls independently onto the floor, the remains and nearby
  boxes), so two pieces can overlap.
- **A piece does not carry the motion of an animated instance**: a piece broken off a swinging door starts at
  rest. (The remains follow the door's live transform.)
- **Landing surfaces** are the scene's floor, the remains, and the boxes of nearby `cube` and `cylinder`
  instances. `sphere`, `cone`, `torus` and `mesh` instances are not landing surfaces - a piece falls through them.
- **Dust is dropped, not simulated**: a piece smaller than 400 voxels (about a 3.7 cm cube) is discarded; at most
  6 pieces per hit (the biggest); at most 24 live chunks (the oldest is dropped and its mesh freed).
- **Crater size**: one hit severs up to about 0.30 across; wider things get a hole. Measured by
  `RegressionCraterOOM` inside a 512 MB heap: at radius 30, carving takes 25-60 ms and meshing about 50 ms per hit
  (100-150 thousand vertices); doubling the radius to 60 costs about three times as much (173 + 132 ms) - faster
  than linear, far below the cube of the radius, because only the crater's boundary is refined.
- **Meshes** are voxelized by exact surface tests plus a ray-parity inside test, so only a **closed** mesh gets a
  solid body (section 3.3). A flat or open one cannot be destroyed (the G hit says so); an almost-closed one
  comes out streaky along the rays through its flaw. Imported "soup" meshes are the likely trouble.
- **Modifiers**: `taper` is applied when voxelizing (verified against the exact frustum volume); `bend`,
  `twist`, `curve` and `shear` are **not** - a bent instance is voxelized as its straight shape. The bend maths
  is solved (journal) but not wired in.
- **Colour** is sampled per vertex of each voxel face, not per fragment - a large flat face shows a smooth
  gradient of the texture rather than crisp texels.
- **No caching** between hits beyond the live state itself (the voxel grid is rebuilt on the first hit of an
  instance), so assets loaded or replaced on the fly are always seen as they are now.

## 5. Before recording: what only a GPU can confirm

Everything below needs the real viewer (`./gradlew run --args="path/to/scene.pbd"`). None of it can be checked
from here; each item says what correct looks like. Do it once, on a scene with a door, a post and a textured
wall, before video 1 (items 1-5, 8, 13) and again before video 2 (all).

1. **G on a post / plank** (untextured): the hole is visible, the top piece falls, tumbles and lies on the
   floor; nothing flickers; the sound plays (needs `doomshotgun.wav`).
2. **G on a textured primitive**: no crash (the long-standing `0xC0000374`). If it crashes, send the console
   output of that press - it has the position/direction lines and the texture name.
3. **G twice in the same place / on the fallen piece**: a second shot goes through the first hole; the lying
   piece shrinks. No stale piece left hanging in the air.
4. **Frame time while 6-24 pieces are alive**: stays smooth (the overlay shows FRAME ms).
5. **E on a container item**: the pickup sound plays (needs `pickup.wav`); the item leaves the container.
6. **Drag a lever-arm door** with the left mouse button: the door follows the cursor smoothly, is not
   "sticky", snaps to the nearer end on release (or stays where it was left if `release = free`); a locked arm
   says "locked" in the console and does not move; the open/close sounds play once each way; the contents of a
   container appear when the door is open and vanish when it is closed; a lamp on the door moves its light.
7. **Left click on a keyframed door** (while any are left): the old scripted toggle still works.
8. **N / B** (switch scene) while a drag is in progress or after a destruction: no exception, nothing stale.
9. **Hot loading**: replace a `.pbd`/`.pbdmat` on disk while the viewer runs and trigger G on it - the new
   asset is the one that breaks.
10. **Upload** a `.pbdasset` with the add-on to the real server (once the server side is looked at, section 6).
11. **G on a `mesh` instance** (a closed model - the add-on's *Convert to PBD Mesh*): the original disappears, the
    remains appear exactly where it stood (scale and rotation included), the piece falls and rests, and a second
    shot goes through the hole.
12. **A Blender scene with spaces in the object names**, exported with the new add-on, opens in the viewer and
    the parts hang where they should.
13. **E on a container item, then close the door and open it again**: the item is still gone, the others are where
    they were left, nothing flickers, the pickup sound played once, and a second container opened at the same time is
    not affected.
14. **The add-on's panels in a Blender window** (not the viewer): press N, open the PBD tab. The first line reads
    `PBD Tools v0.44.0`; a Pivot with *Lever arm* ticked shows open position / rotation, speed, release, lock, lock at,
    both sounds and the Capture button; a Reference shows Test Reference; the Materials panel lists the `.pbdmat` of its
    folder; every icon is drawn. The checks run the panel code on a recording layout and ask real Blender whether the
    icons exist - nothing has looked at the result.

## 6. Open backlog

Ordered by what blocks the two videos first. "Blocked" says what is missing, not who is slow.

| # | Item | Why it matters | Blocked / size |
|---|---|---|---|
| 1 | **The GPU smoke test** (section 5) | every claim about *how it looks* rests on it | needs a machine with a GPU - 20 minutes |
| 2 | **Sound files**: `doomshotgun.wav`, `pickup.wav` (+ door sounds) in `src/main/resources/sounds/` | video 1 asks for the pickup sound; `SoundPlayer` decodes `.wav` only (no MP3) | the files are not in the upload |
| 3 | **Run the migration tool over the asset library** (`PbdConvertMain lever <folder> <out>`) | keyframe doors must go before video 2 | the library is not in the upload - 1 command, then read its report |
| 4 | **Server**: `tutorial.php` sections for the lever arm, the pivot, embedding and "re-use existing resources with `include_material`" (draft text: `ADDON_USER_GUIDE.md`); check moderation tolerates `leverArm` | video 2 opens the site; the tutorial must push people to upload assets with includes | `server/` is not in the upload |
| 5 | Textured-primitive G crash (`0xC0000374`) | would kill a recording | needs a real run; send the console output |
| 6 | "Rays pass through voxels" | reported once, never reproduced; the one real bug found (fallback to the camera position) is fixed | needs a console log of the exact press |
| 7 | ~~Container refill on reopen~~ - **the half about re-rolling is done 2026-10-08** (`ContainerStock`, section 1 item 10). **Still open: a better distribution of items across linked containers** (the old request also asked for it) | the re-roll was visible on camera | the rules are verified, the wiring in `Main` is reviewed (section 5, item 13); the distribution is a design question - by volume share, at random per box? Nothing is saved to disk: a restart of the viewer refills the containers |
| 8 | **H-key exit**: leave a hiding spot with the *inverse* of how the player got in | polish | needs reading `startHiding`/`startExitingHide`; GL-bound |
| 9 | **Real physics behind the drag** (inertia, collisions) - the drag is geometric today | you chose the prototype first | which library, real constraints - a design decision |
| 10 | **Rattle sound for a locked arm** (when you pull a locked door) | small polish | one new format field across every format (the round-trip test's completeness guard will insist it is carried everywhere) |
| 11 | **Door-closed accessor by container name** for AI code (`PbdRenderer.isContainerOpen/areAllDoorsClosed` exist by index; `Main` has `availableVolume/canHide`/`getArmValue` by name) | modders think in names | small, GL-bound |
| 12 | **Modifiers in voxelization**: `bend`, `twist`, `curve`, `shear` (taper is done) - and the G *targeting* ray still tests the unmodified box, so a bent instance can be "hit" on empty space | a bent object breaks as its straight self | bend maths solved, not wired; and a **question for you**: two bend entries exist - the older "Bend modifier - proper fix" asks for Blender's own technique (loop cut, then Simple Deform) for how a bent shape is drawn; the newer one solves how to *voxelize* a bent shape (polar-geometry inverse). They are different problems - does the on-screen bend still need the Blender-style rework? |
| 13 | **LOD tiers**: the Java side takes any `vertexDataLod<N>` as an ordering key; the report that 0/1 "don't work" points at the add-on's LOD UI/export, not yet read | tiers set in Blender | read `properties.py`/`export_pbd.py` LOD code |
| 14 | ~~`description =` has no field in the add-on's File box~~ | **Done 2026-10-08**: a Description field in the File metadata box (export, import, File-box scenario in the add-on check) | closed |
| 15 | Add-on: export a `.pbdmat` on its own; show a remote `.pbdmat` in the Material panel | convenience | small each |
| 16 | `.pbdasset` of ~29 MB (bloat) | upload size | verify against a real asset |
| 17 | JOML `Vector3fc` classpath error from an older build | build | reported fixed through the `printRuntimeClasspath` task; cannot be re-checked here - `build.gradle.kts` is not in the upload |
| 18 | The grill/BBQ scene with an oddly thin, mis-oriented panel (two screenshots, no file) | a visible modelling bug in a reported scene | never diagnosed. **Possible lead, unconfirmed**: the child-of-a-primitive export bug fixed on 2026-10-07 (journal) - re-export that scene with the fixed add-on and look again |
| 19 | The tutorial's domain-specific ("metier") requirements | raised once, never specified | needs your list of what is missing |
| 20 | Web visualizer + a rewrite of the site's index/tutorial | "eventually" (the documentation refresh part of that old item was done on 2026-10-07) | large |
| 21 | `Main.availableVolume(name)` (hence `canHide`) subtracts the volume of **every item of every open container** from the asked container's volume, not only the items in that box; and it takes the instance's `scale`, not its world size | `canHide` can answer "no room" because another container happens to be open | found by reading on 2026-10-08, no suite calls it. Fix: keep only the items whose box is the asked instance (`owner.metaIndices.get(item.containerIndex)`), through a pure helper that a test can feed - small |
| 22 | **Two remote caches, not one.** The engine caches fetched URLs under `src/main/resources/cache/remote` (`PbdPaths.REMOTE_CACHE_DIR`); the add-on under `pbd_cache/remote` next to the `.blend` (the system temp folder when it was never saved). Code comments in the add-on said they were one - the user guide and the format spec now say they are two | a file fetched in Blender is fetched again by the viewer | decide: unify (the add-on would need to know the project folder, which it no longer asks for) or keep two. Small either way |
| 23 | **Blender versions**: `blender_real_check.py` was run on 4.2.23 LTS, 4.5.14 LTS, 5.0.1, 5.1.2 and 5.2.2 LTS (248 checks pass on each), which covers the three animation APIs: no action slots (4.2: the legacy path of `anim_compat.py`, the one 4.0 has), slots with the deprecated `action.fcurves` still there (4.5), slots only (5.0 and later). **Not run: yours, 4.0.2** (`bpy` modules on PyPI start at 4.2; Blender's download server is not reachable from the workspace), 4.1, 4.3, 4.4. For 4.0, two one-off checks against its API stubs (`fake-bpy-module-4.0`, not part of `run.sh`): the 29 icons the add-on names are all in the 4.0 icon list, and a type check of the add-on against the stubs finds nothing missing from 4.0 apart from what the add-on registers itself and the 4.4+ call `anim_utils.action_get_channelbag_for_slot`, which `anim_compat.py` reaches only on an object with an action slot (the stubs cannot see through untyped `obj` / `context` parameters: a partial net, not a run) | an upgrade or your 4.0.2 could break something no run here has seen | run `blender_real_check.py` on a 4.0.2 binary, or try the add-on by hand once on it and send the console output if anything is red |

Closed since the last revision of this list (the journal entries below were left as written; status notes were
added to them): availableVolume / canHide (`Main`), **minified `.pbd` not loading** (fixed on both producers),
crater OOM, texture double decode, non-cube primitives, NaN ray distance, draw-time write in `ui_panel.py`
(moved into an operator), `upload_server.py` imports, the add-on's `description` field (item 14), a file refused because an object name held a space,
an import stopped by one unreachable reference, stale matrices read by a script, a sidebar title that ignored the version. Re-read against the current code on 2026-10-08.

**Not worth doing now, and why**: moving individual cube *edges* (needs the tessellation shader generalised);
MP3 decoding (a new dependency that cannot be tested here - convert to `.wav`).

---

## 7. Journal (newest first)
### 2026-10-08 (later) - the add-on on real Blender: stale matrices, an import that crashed on one dead reference, and a sidebar that lied about its version

Started as the packaging step: the add-on zip was stale, so it was to be rebuilt from the sources. Before shipping a
zip, the add-on was run on something other than the fake it had been checked on.

- **`pip install bpy`** gives Blender as a Python module (5.2.2 LTS first, about 400 MB each; 4.2, 4.5, 5.0 and 5.1 later, see "Other Blender versions" below). `blender_addon_check.py` now has a
  second mode (`PBD_REAL_BPY=1`, wrapped as `blender_real_check.py`) that registers the add-on in it and runs the same
  scenarios; `run.sh` runs it when `bpy` is importable. Its checks are not added to the totals (they repeat the fake's).
- **The fake grew a user interface.** `fake_bpy.py` now has a recording `UILayout` with Blender 4.0's signatures that
  refuses an unknown setting, operator, list, choice or keyword, a positional label and a wrong type, and a registry
  that records `register_class` / `unregister_class`. The check draws the object panel, the sidebar, the Materials panel,
  the three menus and both import dialogs for every kind of object and variant (lists empty and filled); a draw-time
  error used to fail silently into Blender's console (`draw_pbd_properties_safe` turns it into a red box, which the check
  now looks for). 25 mutants (sidebar title, lever-arm fields, misnamed operators, unregister leaving traces...) were
  all killed.
- **Findings** (each pinned by a check that fails without the fix, and by a mutant):
  1. *Stale matrices.* Real Blender computes `matrix_world`, `matrix_local` and `dimensions` in a depsgraph that a Python
     assignment does not refresh. A scene imported then exported in one script had a door panel three units off, and a
     script that moved objects and exported at once got the old positions. The exporter now calls
     `context.view_layer.update()` first, the importer before it returns. Only real Blender can see this.
  2. *One dead reference stopped a whole import.* `fetcher.FetchError` was a plain `Exception`; the importer documents and
     catches `OSError`/`ValueError`. With no network or a 404, `Import PBD` printed a traceback, and a scene with one
     unreachable `pbd_ref` was not imported at all instead of leaving an empty placeholder and a warning. Found by pressing
     every button of the add-on once on real Blender. `FetchError` is an `OSError` now; the check replaces the network
     by a stub that fails.
  3. *The sidebar's first line* read `PBD Tools v0.41.0` whatever the version, while the user guide tells people to compare
     it with `bl_info`. It is built from `bl_info` now, and the version is 0.44.0.
  4. *The materials-folder fallback* counted four folders up from the add-on's file in a checkout, where it is five.
  5. *The user guide* described a "PBD Project Root" field and a one-click Export that the add-on no longer has, and
     said the add-on and the engine share one remote cache (they keep two: backlog item 22). Corrected.
  6. *The zip people install was stale.* `tools/blender-addon/pbd_tools.zip` still held version 0.43.1: ten of the twelve
     files differed from the sources, so a user who installed it got none of the fixes above - and no lever arm at all (not a
     word of `lever_arm` or `leverArm` in its sources: no panel, no block in what it exported). It is a copy, not a link (the README says so), and nothing compared the two. The add-on check
     now does (the zip holds exactly the sources' files, byte for byte, in a folder of its own; the check was seen to FAIL on the
     old zip before the zip was rebuilt), and on real Blender it hands the zip to Blender's own installer
     (`Preferences > Add-ons > Install...`), checks that exactly one add-on lands among the user's add-ons with the
     sources' name and version, enables it (sidebar panel and operators appear) and disables it (they go away).
- **What the real run checks**: every icon the add-on names exists in this Blender, the settings Blender registered are
  exactly the ones the add-on declares, every button pressed once on a plain scene and once with a `mesh` primitive
  selected does not die with a Python exception, a script's edits are exported and imported at once, registration is
  complete and leaves nothing behind. `mutants_addon_real.py` (6 mutants, all killed on real Blender, all surviving on the
  fake) puts each class of mistake back: no refresh before export, none after import, an icon Blender does not have, a
  panel in a space it does not have, a choice list whose default is not a choice, an attribute that does not exist.
  (The zip scenario has no mutant of its own: a mutant changes the add-on, and the zip is rebuilt from the changed add-on in that
  mode; what guards the committed zip is the comparison above, seen to fail on the old one.)
- **Not found**: nothing else crashed - of the 37 buttons pressed, only Import PBD died (finding 2); the other 36 pass on a plain
  scene and with a `mesh` primitive.
  **Not run**: the panels in a window, undo, the viewport drawing, Blender 4.0.2 (yours), 4.1, 4.3 and 4.4.
- **Other Blender versions** (backlog item 23). The same check, unchanged, was run on Blender 4.2.23 LTS, 4.5.14 LTS, 5.0.1,
  5.1.2 and 5.2.2 LTS: 248 checks pass on each, none fails. They are the three animation APIs `anim_compat.py` has to
  know: 4.2 has no action slots (so the export reads `action.fcurves`, the path Blender 4.0 takes too), 4.5 has slots and
  the deprecated `action.fcurves` proxy, 5.0 and later have slots only. Two things came with it. (1) **Blender 4.2 as a Python
  module crashes at exit as soon as any operator has been registered** - a three-line operator of our own in a scratch file
  does it, nothing of the add-on - so a passing run ended with exit status 139; the real run now leaves with `os._exit` and
  its exit status is the check's verdict (otherwise `mutate.py` would have counted every mutant as killed). (2) **Blender 4.0
  itself could not be run** (no `bpy` wheel before 4.2, and Blender's download server is not reachable from the workspace),
  so for 4.0 only two static checks exist, both against its API stubs (the `fake-bpy-module-4.0` package) and neither is part of
  `run.sh`: every icon the add-on names (29) is in the 4.0 icon list - a name Blender does not know is a `TypeError` that blanks
  a panel - and a type check finds no call, keyword or module attribute missing from 4.0 beyond what the add-on registers
  itself and the guarded 4.4+ channelbag call. The stubs cannot see through the untyped parameters (`obj`, `context`), so this
  is a partial net. Your 4.0.2 stays the one version nobody here has run.


### 2026-10-08 - a shot at a mesh, a Description field, names the format cannot hold, and the docs' own numbers

The request of 2026-10-07 (polish the roadmap, check the specification, keep developing, check lever-arm attachments
and the G cut) was sent again this morning. What came after the two checks of section 3:

- **A shot at a `mesh` instance was broken four ways** (section 3.3): found by a probe, then pinned by
  `RegressionMeshVoxel` (61 checks) and its 27 mutants - 27/27 killed; the one mutant that survived ("nudge only
  one of the two ray coordinates") was shown equivalent for every mesh that can be built by hand and was dropped,
  with the reason written in the spec file. The target search of `PbdRenderer` moved into the pure
  `DestructionWorld.findFresh` so the broad phase has tests; `PbdMeshData.bounds()` is new. Four comments that
  contradicted the code were corrected (`PrimitiveVoxelizer` said meshes return null; `DestructionWorld` said
  the crater cost grows with the cube of the radius and "a few MB", and its refusal message said "mesh type";
  `Main` had a mangled block about the G flow).
- **The first mutation round on the lever-arm run-time chain** (61 mutants) left seven alive, each a real gap in
  the tests: nothing checked where "fully open" begins, that `setValue` clamps, that a silent update still records
  the arm's state, a grabbed point swinging behind the camera, a lamp that only slides or only turns, or the
  tolerance of the light tracker. Twelve checks were added; two equivalent mutants are documented in the spec file.
  `SceneHandle.setParent`'s Javadoc was corrected to say what the code does: the pose is **not** compensated.
- **The documentation checker had a bug of its own.** It only took a bare fence line for an opening fence, so a
  fence that carries a language (an `sh` block) threw the pairing off and the prose between two code blocks was
  reported as an unchecked lever-arm example. Fixed, with a self-check on a made-up document. The add-on guide's door example is now a checked and
  pinned example (7 more doc mutants, 31/31 killed in all).
- **`check_doc_counts.py`** compares the numbers the docs quote (checks per suite, mutants per spec, suite and
  mutant totals, "K/K mutants" claims) with what `run.sh` has just counted. Its first pass over the finished state
  reported 24 numbers that the day's changes had made stale (`138` for `RegressionLeverArmSystem`, which has 147;
  `87` for `RegressionLeverArmAttach`, which has 90; `111` add-on checks where there are 208; an `18 Java suites`
  total that is 19; `23/23` and `24/24` mutants, sizes no spec has any more; a "Seven spec files" that cannot be
  compared). It checks itself first on a made-up document, and `mutants_doc_counts.py` breaks it 20 ways (20/20
  killed) - a pattern that silently stopped matching would otherwise make every run green.
- **Add-on: Description field and a File-box scenario** (5 text fields x pretty / minified x awkward text: spaces, `#`,
  `=`, commas, brackets, a tab, a line break, padding, a double quote). It found a real divergence: the Python
  `_quote_if_needed` did not quote a `#` or a tab as `PbdSerializer.quoteIfNeeded` does, so a one-word name like
  `C#` came back as `C`. Text is now written as typed, with two exceptions the format forces (a double quote
  becomes an apostrophe, a line break a space).
- **Add-on: object names.** The same probe style, pointed at names, found that an object called `front door` was
  exported as `cube front door {` and the engine refused the file (and so did `a=b`, `a{b}`, `porte d'entrée`...).
  Every id the exporter writes (the instance's own, `parent`, `containerTrigger`, an embed's anchor and the prefix
  of its copies) now goes through one function that writes each run of unusable characters as `_`, keeps a name that
  was already fine, numbers a clash (`_2`) and lists what changed in the export's warnings. The importer's identifier
  rule was narrower than the engine's (letters, digits, `_` and `.` only, so `wall-left` could not be opened); it
  now follows the engine's. A material name with a space is reported, not rewritten. The check covers 13 awkward
  names, a pivot, a reference, an embed and a light hanging on one, a name clash, a container trigger and ids the
  engine accepts; 74 add-on mutants (all killed) pin each character of the rule, each place an id is written and
  each field of an embedded lever arm. That last group found one more gap: no arm in the embed fixture was locked,
  so the embed writer could drop `locked` without any check failing; the fixture's hinge is now locked and has both
  sounds, and every field of the arm is compared with the engine's own inlining.
- **An emptied container stays empty** (backlog item 7, the half about re-rolling). The frame loop threw a container's
  contents away when its doors closed and loaded them again with the same seed, so everything the player had taken
  came back. The decision moved into the pure `pbd.pz.ContainerStock`: the contents are loaded on the first
  opening, put on a shelf when every door is shut and given back as they were left on the next opening; a new scene
  forgets them. `RegressionContainerStock` (30 checks) runs the rules on stand-in contents (the real ones need the
  game's FBX files), `mutants_container_stock.py` breaks them 16 ways (all killed), and the suite reads `Main`'s
  source to check that every outcome has a case there. The wiring in the frame loop is **reviewed**, not run. While
  in there: the renderer of an item taken with E was never closed (a shelved group only releases the renderers of
  the items still inside) - it now is. Not done: distributing items across linked containers, and anything saved to
  disk. Reading `Main.availableVolume` turned up a separate defect (backlog item 21), listed, not fixed.
- Docs: `ADDON_USER_GUIDE.md` gained the File metadata box, the lever-arm / pivot recipe with a checked example,
  the names rules, the limits, and a **draft of the tutorial text** (backlog item 4); `tools/blender-addon/README.md`,
  `ENGINE_DEV_GUIDE.md`, `TEST_PROCEDURE.md`, `STATUS_SUMMARY.md` brought up to date; the add-on zip regenerated
  from the current sources.

### 2026-10-07 - documentation pass, a parser tolerance and the AI facade

Rewrote this file's state sections; brought `PBD_FORMAT_SPEC.md`, `ADDON_USER_GUIDE.md`, `TEST_PROCEDURE.md`,
`STATUS_SUMMARY.md`, `ENGINE_DEV_GUIDE.md` and the add-on `README.md` up to date (the spec's `## Lever-arm`
section had no `release`/`locked`/`lockAt`/sounds; its `## Curves` example used a syntax the parser does not
read - `point = ...` lines instead of `kind =` and `points = [...]`). The new `RegressionSpecExamples` makes that
kind of drift fail instead of go unnoticed: nine spec examples carry a `<!-- check: scene -->` (or `material`)
marker and are parsed by the real parser on each run; the lever-arm ones are pinned to the numbers in the text; a doc
example that shows a `leverArm` without the marker fails; the number of checked examples cannot shrink. It was
mutation-tested by breaking the docs in a scratch copy (24/24 then, 31/31 since). The mutation scripts themselves (`mutate.py`,
`mutants_*.py`) are now in `tools/regression/` - before, they lived in a scratch folder, which made every "N/N mutants
killed" claim unrepeatable. Two small code changes came out of checking the spec against the code:

- `Main.getArmValue / isArmLocked / setArmLocked`: the lever-arm specification asked for an accessor for
  AI/modding code; it existed only on `PbdRenderer`, not on the static facade modders actually call
  (`availableVolume`, `canHide`, ...). Added next to them; with no scene loaded they answer NaN / false instead
  of throwing (3 checks in `RegressionLeverArmSystem`, 4/4 mutants killed). The live case needs a renderer.
- **A bare (unquoted) base64 `vertexData=`/`vertexDataLod<N>=` is now read** by the engine. Its padding `=` is
  structural to `readRawValue`, so a hand-written file (or an older tool's, such as the project's own
  `mushroom.pbd`) failed with "Expected an identifier" a few tokens later; the Blender add-on's reader had always
  taken it. Every writer here quotes it, so only hand-made files could hit this. 4 checks in
  `RegressionFormatRoundTrip` and 2 in the add-on check (engine and add-on agree on the same text); 4/4 mutants.
  Quoting stays the recommendation.

### 2026-10-07 - keyframe -> lever-arm migration tool

Asked for: the keyframed doors have to be replaced by lever arms before video 2. `LeverArmMigration`
(`pbd.format`) + `java pbd.PbdConvertMain lever <input.pbd|.pbdbin|folder> <output>`:

- Converts an instance whose keyframes are a plain open/close motion - the first keyframe is the closed pose
  (it becomes the instance's own `pos`/`rot`), the last the open pose (`openPos`/`openRot`), `speed` from the
  clip's duration, its `sound=` becomes `openSound`/`closeSound`; `linkGroup`, children and container triggers
  are untouched.
- **Refuses, and says why**, what an arm cannot express. Per instance: a looping clip, a `channel` keyframe
  (humidity-driven), a scale animation, a single keyframe, times that do not strictly increase, a first and last
  pose that are the same, a middle keyframe that is off the straight line between the two end poses ("more than two
  poses") or that carries a sound, and an instance that already has an arm. Per file: a file that places others with
  `pbd_ref` (the copy would have them inlined) and `.pbdasset` (a zip: unpack, convert `scene.pbd`, pack with
  `PbdConvertMain asset`). It never overwrites its input, writes a report per file, and with a folder keeps the
  relative layout.
- The converted file is regenerated from the parsed scene, so **comments and formatting are not kept**; sound
  *timing* differs (keyframe sounds fire when the clip crosses a time, arm sounds when the arm leaves/returns to
  the closed end).
- Verified by `RegressionLeverArmMigration` (57 checks): poses of the converted file equal the keyframe poses at
  the first/last time and at mid-clip for single-axis rotations, tilted and rotated doors, drawers (position
  keyframes), refusals one by one, folder mode, report text, no overwrite. 29/29 mutants killed (the first run
  found the "quaternion not updated" mutant surviving - a rotated drawer and a tilted door were added to kill it).

### 2026-10-07 - format coherence: every field through every format (6 real data-loss bugs)

You asked for a systematic coherence test. `RegressionFormatRoundTrip` builds ONE scene that uses every field of
every class (instances, keyframes, lever arms, modifiers, curves, mesh data, includes, lights, container
triggers, indestructible/hardness...) - plus awkward values (a space, `#`, `=`, `,`, a bracket list, a quoted URL)
- writes it as pretty `.pbd`, minified `.pbd`, `.pbdbin` and `.pbdasset`, reads each back and compares **every
public field by reflection**. A completeness guard fails if the scene leaves any field at its default (a field
nobody sets would be "compared" while empty on both sides and prove nothing). First run: red, as intended. Real bugs
found and fixed:

1. several `include_material` lines kept only the **last** (the scene now keeps the list; every writer uses it);
2. an include path containing a space came back unreadable;
3. **curve blocks vanished** (the serializer never wrote them);
4. any free-form value with whitespace, `=`, `,` or `#` (`linkGroup = "front doors"`) was written unquoted and
   broke the file when read back from `.pbdbin`/minified;
5. the materials of a **placed** file (`pbd_ref`) were lost on conversion: the referenced file's
   `include_material` lines were in nobody's list (now carried, rebased to the including file's folder, or made
   absolute for a file fetched from a URL);
6. `.pbdasset` bundled only the last include's materials and textures.

Also verified: re-writing what was read gives identical text (no drift), `.pbdbin` read-write-read is stable.
25/25 mutants of the fixes killed (`tools/regression/mutants_roundtrip.py`; the survivors of the first mutation round - modifier params, category, channel,
a `#` in metadata - were test gaps, closed by adding awkward data of exactly those kinds). Known format limits:
a value containing `"` cannot be written (no escape in the format); numbers are written with 6 significant
digits; re-serializing drops comments and formatting. `.pbdasset` does not bundle the files a `pbd_ref` points to
(flatten or embed first) and the add-on's embed does not carry bundled materials.

### 2026-10-07 - the Blender add-on, checked against the engine (a real export bug)

`tools/regression/blender_addon_check.py` runs the add-on's real exporter, importer and operators on a fake `bpy`
(`fake_bpy.py`; Blender's real `mathutils`), writes files, and has the engine's real parser and hierarchy
resolver (`BlenderAddonDump.java`) say what it sees. The invariant: for every object, the world matrix the engine
computes from the exported file (arm closed / half / open, or a keyframe time) equals Blender's own, through the
Z-up -> Y-up change and the engine's x2 factor for a canonical shape. Scenarios: hinged door on a pivot, parent
inverse (Ctrl+P), child of a primitive, keyframes, warnings, embed vs reference, round trip of files the engine
wrote through the add-on and back. Found and fixed:

- **a child of a canonical primitive was exported two times too large/far** (a Blender primitive is 2x2x2, the
  engine's 1x1x1, and the parent's factor was applied to the child's own size and offset in the wrong place);
- the importer **parsed `leverArm` and then dropped it** (an imported door lost its arm);
- the embed anchor is now a `group` pivot, so an embedded reference can carry an arm;
- guard rails: a keyframed arm warns that keyframes win; `indestructible` on a `pbd_ref` is not exported (the
  engine refuses it); a `lever arm` on a `ref`/`light` object is not exported (use a pivot).

23 add-on mutants (a changed factor, a dropped field, a swapped axis...) were all killed (the spec has 74 now: see 2026-10-08).

### 2026-10-07 - lever arm: lock, release, sounds, attach, lights, drag (the rest of the specification)

Section 2 is the result; the build order was: format fields `release` / `locked` / `lockAt` /
`openSound` / `closeSound` (Java parser, serializer, `.pbdbin`, `.pbdasset`, add-on UI, export, import);
`LeverArmSystem` (pure, no GPU) holding the live value, who drives it, lock, release and the sounds, so all of it
could be tested; `pick` (what a click grabs, through any attached primitive) and `dragToward` (replaces the
40-sample search by coarse sampling + golden-section refinement, worst error 0.0002 degrees on a hinge);
`LightTracker`; `setParent` that really re-parents; `pbd_ref` copying everything (`PbdInstance.copyAs`).
`PbdRenderer` and `Main` were rewired to use `LeverArmSystem` (**reviewed**, compile-checked).

### 2026-10-07 - G strong enough to cut: solid crater, falling pieces

Section 3.2. `VoxelCrater` (solid irregular crater), `VoxelConnectivity` (face-connected pieces), `VoxelDebris`
(what stays, what falls, what is dust), `RigidDebris` (rigid bodies that fall, land on the floor/remains/boxes and
sleep), `DestructionWorld` (the whole flow behind `PbdRenderer.destroyAt`, with a host interface so it runs
without a window), `VoxelFallDemo` (the pictures). `PbdRenderer.destroyAt` is now a thin call into it.

---

Everything from here down is the journal as it stood before 2026-10-07: entries without dates, newest first, kept as written - self-corrections included. A **Status** note was added under every entry that a later one changed. The entries themselves were not edited, except for two headings: the minified-`.pbd` bug (now marked FIXED; `RegressionMinifiedMetadataQuoting` quotes that heading) and a pointer in the "Lever-arm system" heading.

### Lever-arm drag-and-drop: simple geometric prototype built (no physics engine - chosen explicitly over the alternative)

Asked for right after the correction below shipped: given the choice between a quick geometric prototype (no new dependency, can't be verified visually here either way) and integrating a real physics library (bigger, also unverifiable here, and this codebase has none today), a simple prototype now, with real physics as a later option, was the one picked.

How it works: left-click now branches on what's under the crosshair at the moment the button goes down - a keyframed instance still gets the old single-click scripted toggle (`toggleKeyframedInstanceAlongRay`, narrowed to keyframe-only - see its own doc), while a lever-arm instance is grabbed instead (`PbdRenderer.findLeverArmGrabPoint`, reusing the exact same ray-vs-oriented-box test via a newly-shared `findClosestInstanceAlongRay` helper) and the grabbed point's own LOCAL position is remembered. Every frame the button stays down, `PbdRenderer.dragArmValueTowardRay` samples the arm value in 40 steps across `[0,1]`, evaluates what world transform the instance WOULD have at each one (`previewWorldTransform`, via a throwaway `HierarchyResolver` call - doesn't mutate anything), and commits (`setArmValue`) whichever value puts the grabbed point closest to THIS frame's ray. On release, `setOpen` takes over and eases the rest of the way to whichever end is now closer, reusing the already-existing scripted/sound/`linkGroup` behavior rather than inventing separate "let go" logic.

Deliberately simple, and deliberately general: sampling rather than a closed-form angle solve means this works for a pure rotation, a pure slide, or a combined arm alike, with no assumption about a single fixed hinge axis - the honest tradeoff is less precision than a real solve (a 1/40 step) and a per-frame cost (41 whole-scene `HierarchyResolver` passes while actively dragging - cheap for scenes this project's own examples run, revisit if a scene is ever large enough for that to matter).

Caught and fixed in the same pass, from this project's own documented precedent (a scene switch leaving a stale destroyed-instance index behind, fixed earlier - see that entry further down): a drag in progress when N/B switches scenes would have left a stale instance index pointed at the NEW scene's (possibly smaller) instance list. Reset alongside the exact same `containerContents.clear()`/`renderer.clearDestroyed()` scene-load point that already exists for this reason.

Also added: `PBD_FORMAT_SPEC.md` never actually documented the `leverArm {}` block's syntax at all (an oversight from when the mechanism first shipped, below) - added a `## Lever-arm` section matching `## Keyframes`'s own style and placement.

Partially verified by execution, now - the core numerical question (does the 40-sample search actually converge on the right arm value, rather than some sign-flipped or axis-swapped value that still happens to compile) was checked for real: `dragArmValueTowardRay`'s search loop and `previewWorldTransform` were copied verbatim into a standalone harness (not retyped from memory) that parses a real one-door `leverArm` scene through the actual `PbdEngine`/`PbdParser`, and calls the actual, already-compiled `HierarchyResolver` - none of that needs LWJGL, only `PbdRenderer.java` itself does. For six known arm values (0.0, 0.25, 0.33, 0.5, 0.725, 1.0) plus a ray built to look exactly at each value's true grab-point location, the search recovered every one within 0.02 - including a discriminating case built specifically to rule out the metric trivially agreeing with itself (a ray aimed at the CLOSED point's location, checked that it resolves near 0.0 and not 1.0 or something else). All checks passed.

Still not, and can't be from here: the LWJGL-bound plumbing around that math - the GLFW mouse-down/mouse-held wiring in `Main.java`, screen-to-world ray construction, and the click branching between drag-start and the keyframe fallback - none of that compiles in this sandbox, so it's reviewed by careful reading only, not run. And "does dragging feel right" (the step size, how forgiving the grab point's pickup radius feels, whether 40 samples/frame is smooth enough) is inherently a thing to feel at a keyboard, not something a harness can answer. Recommend actually grabbing a door in-engine before recording video 2 - the number-crunching underneath it is now confirmed sound, but that's a different claim from "it feels good to drag."

> **Status (2026-10-08):** the 40-sample search described here was replaced by coarse sampling plus golden-section refinement in `LeverArmSystem.dragToward` (worst error 0.0002 degrees on a hinge, instead of a 1/40 step), and the drag maths moved out of `PbdRenderer` into the pure `LeverArmSystem`, where `RegressionLeverArmSystem` checks it without a GPU. The mouse wiring in `Main.java` and the feel of the drag are still only reviewed (section 5, item 6). The stale-index reset on a scene switch described here is still in `Main.java`.

### Lever-arm correction: the arm value needed to be settable from OUTSIDE the scripted easing, not just toggled (facade bug fix + setArmValue/getArmValue added)

Caught immediately after the entry just below shipped, from feedback on it: that first pass assumed a lever-arm's 0..1 value would always be driven by `PbdRenderer`'s own `speed`-based easing, toggled on/off like a keyframed door. Wrong for the actual intent - the open/closed ENDPOINTS are authored fixed points, but the motion between them was never meant to be necessarily scripted; the specifically wanted case is a mouse drag-and-drop with real physics, where the door's openness at any instant is whatever the drag/physics computes that frame, not something this class eases toward on its own clock.

That wrong assumption had leaked into real logic, not just the easing itself - two fixes:
- `isInstanceOpen`/`isInstanceFullyOpen`/`isInstanceFullyClosed` required `instanceOpen` (a discrete toggle-intent flag, set only by a click or `setOpen`) to AGREE with the continuous arm value for a lever-arm instance. A door driven any other way - a drag, for instance - could sit fully open at arm value 1.0 and still report as closed to the facade, so container contents would never appear: exactly the "link to the other modules - facade - like what's displayed inside" this was asked to get right. Fixed: for a lever-arm instance, all three now read `instanceArmValue` directly and ignore `instanceOpen` entirely. `instanceOpen` remains meaningful only for the keyframe mechanism, and as the scripted-easing TARGET in `updateAnimation` - never as something the facade checks for a lever-arm instance.
- There was no way to set the value directly at all - `updateAnimation`'s easing loop was the only writer, so there was nothing an external drag system could even call. Added `PbdRenderer.setArmValue(instanceId, value)` (clamped to [0,1], same `linkGroup` propagation as `setOpen`) as that missing write path, and `PbdRenderer.getArmValue(instanceId)` as the read side - this is the roadmap's long-pending `getArmRotationValue` accessor, named `ArmValue` instead since the value isn't necessarily a pure rotation (a sliding or combined arm has no single rotation to report). A new per-instance flag (`armExternallyDriven`) stops `updateAnimation`'s own easing from fighting a `setArmValue` caller every frame by skipping that instance entirely while the flag is set; `setOpen`/`toggleKeyframedInstanceAlongRay` (the existing click/scripted path) clear it, hand control back to scripted easing.

At the time this fix shipped, the actual drag-and-drop interaction was deliberately left for a follow-up decision (no physics engine exists anywhere in this codebase, and the real mechanics were an open question not worth guessing at blind) - see the entry above, written right after: a simple geometric prototype now exists, built against exactly the `setArmValue`/`getArmValue` seam this entry describes, which is what that seam was for.

> **Status (2026-10-08):** still the design: `setArmValue` / `getArmValue` now live on `LeverArmSystem` (the pure class) with `PbdRenderer` delegating, and `getArmValue`, `isArmLocked` and `setArmLocked` are also on the static `Main` facade that mods call (section 2, row 5).

### Lever-arm mechanism shipped as the door-keyframe replacement, format-wide (MVP DONE, but see the correction above - three backlog items deliberately deferred)

Requested so every container door currently animated via keyframes could be switched over before video 2 is recorded - keyframe-interpolated swings were reported to produce visual glitches, and the backlog entry below (now trimmed to just what's still open) had already proposed this exact replacement.

Design: an instance's own base `position`/`rotationDeg` is pose 0 (closed); a new `leverArm { openPos openRot speed }` block defines pose 1 (open) - both `openPos`/`openRot` independently nullable, falling back to the base pose; `speed` is arm-units/second (default 1.0, must be > 0). A continuous value in [0,1] drives a 2-point lerp between the two poses, eased the same way keyframe playback already eased, just driven by this value instead of animation time.

Wired everywhere the format lives:
- **`.pbd` text**: new `leverArm {}` block in `PbdInstance`/`PbdParser`/`PbdSerializer` (pretty and minified both).
- **Java engine**: `HierarchyResolver` gained a 3-arg `resolve(scene, animTime[], armValue[])` overload (keyframes still win if an instance somehow has both); `PbdRenderer` gained a parallel `instanceArmValue[]` array, an `updateAnimation` branch that eases it toward 0/1 at `speed` units/sec, and - the one easy-to-miss piece - `isInstanceFullyOpen`/`isInstanceFullyClosed` now also check it, which is what actually lets a lever-arm door's container contents show/hide correctly; without that half of the wiring the door would animate but its contents would never appear.
- **Blender addon**: new UI fields + a "Capture Current Pose as Open" button (`properties.py`, `ui_panel.py`), and `export_pbd.py`/`import_pbd.py` emission + parsing, deliberately reusing the SAME axis-conversion pipeline (`_to_pbd_matrix`/`_to_blender_matrix`) already used for the base pose, so the open pose can't drift into a different coordinate convention than the closed one.
- **`.pbdbin`/`.pbdasset`**: confirmed free - `.pbdbin` is gzip over `PbdSerializer`'s own minified text and `.pbdasset` zip-embeds `scene.pbd`'s raw bytes unchanged, so neither reimplements the schema and both already carry `leverArm` data with no extra code.

Verified: 19 checks in `tools/regression/RegressionLeverArm.java` (wired into `run.sh`) - parsing, pretty+minified round-trips, lerp at several arm values, clamping outside [0,1], no-data/NaN fallback to the closed pose, and keyframe-wins-if-both precedence. The Blender addon side can't run here either (no real Blender), so it was verified separately: the real `_to_pbd_matrix`/`_to_blender_matrix`/`_lever_arm_from_fields` functions extracted via `ast` straight out of the edited source files and run against a genuinely-installed standalone `mathutils` package (not a stub) - 19/19 checks, including a 5-case export-then-import round trip proving the two conversions are true mathematical inverses for a lever-arm's open pose.

Deliberately NOT built (MVP scope, not needed for the door use case this was requested for - see the trimmed backlog entry below): generic multi-primitive attachment, and per-motion SFX on the arm itself (today only a keyframe can carry `sound=`). A public accessor for AI/modding WAS initially left out here too, but that turned out to be wrong, not just incomplete - see the correction entry above, which also fixes a real facade bug this version shipped with.

Flagged, not fixed: whether the remote moderation server (`server/upload.php`, not present in this sandbox - lives on pbd.mazetrojan.fr) does anything `.pbd`-structure-aware that a new block type could trip up is unknown from here. The Blender-side upload client (`upload_server.py`) does no block-level validation at all, so it won't block a submission either way. And - same caveat as every other `PbdRenderer.java` change in this project's history - the engine-side half was never actually rendered; LWJGL doesn't exist in this sandbox, so it's verified by careful reading and brace-by-brace review, not execution. Recommend opening one lever-arm door in-engine before recording video 2.

> **Status (2026-10-08):** all of what was "deliberately NOT built" below is built: attaching other primitives (section 3.1), a sound on the arm's own motion (`openSound` / `closeSound`) and the AI accessor (the correction entry). The arm also gained `release`, `locked` and `lockAt`. `RegressionLeverArm` has 45 checks now (19 when this was written), and the add-on side is checked by `blender_addon_check.py` against the engine's parser instead of by functions extracted with `ast`. Section 2 is the current, line-by-line picture.

### Voxel-destruction radius raised 14 -> 20 for video-1 visibility, stress-tested empirically first (DONE, recommend one test shot before recording)

Requested so destruction reads clearly on camera for video 1. `CRATER_RADIUS_VOXELS` was the one constant most directly controlling how much geometry a single hit visibly removes, but raising it blindly risked reopening the crater OOM already fixed once at a wider radius elsewhere in this file.

Verified the real cost first rather than guessing: `computeCraterRemovedSet`/`applyCraterAdaptive` copied VERBATIM (not reimplemented or approximated) into a standalone, dependency-free harness and run under this project's own documented `-Xmx512m` test condition at radius 14/20/28/40/56 against a worst-case single 512^3 region:

```
radius=14   rayPhaseMs=10.4   adaptiveMs=20.4    totalMs=30.8    removedVoxels=344   survivingEntries=40208    heapDeltaMB=2.7
radius=20   rayPhaseMs=4.1    adaptiveMs=43.9    totalMs=47.9    removedVoxels=514   survivingEntries=109814   heapDeltaMB=6.9
radius=28   rayPhaseMs=15.8   adaptiveMs=66.1    totalMs=81.9    removedVoxels=742   survivingEntries=286042   heapDeltaMB=17.6
radius=40   rayPhaseMs=3.6    adaptiveMs=146.6   totalMs=150.2   removedVoxels=1026  survivingEntries=805718   heapDeltaMB=38.8
radius=56   rayPhaseMs=7.8    adaptiveMs=396.1   totalMs=403.8   removedVoxels=1593  survivingEntries=2152063  heapDeltaMB=96.0
```

The radius=14 row (40208 entries / ~2.7MB / ~31ms) lines up closely with the baseline ALREADY documented elsewhere in this file for the real renderer (40633 entries / ~2MB / 43ms), which is what gives this standalone extraction credibility rather than just plausibility. Cost scales with the crater's boundary surface area (super-quadratic, trending cubic over this range), not linearly - 20 roughly doubles 14's cost, which still leaves real headroom under the OOM that was previously hit at a much wider radius; 28 and beyond grow fast enough that they were not chosen.

Still open, honestly: this is a timing/memory measurement, not a visual one - whether 20 actually LOOKS dramatically more destroyed on camera is unconfirmed, never having been rendered here. The separately-documented "rays pass through voxels" issue (see its own entry elsewhere in this file) was not re-investigated this batch. The textured-primitive G-key crash mitigation also remains unverified on real hardware, unchanged from before.

> **Status (2026-10-08):** superseded. The crater is no longer a set of ray tunnels: it is a solid irregular volume of radius 30 voxels (`VoxelCrater`, section 3.2), so the table above - which measured the old ray-tunnel crater, about 500 removed voxels - no longer describes the cost; the current cost is in section 4 (`RegressionCraterOOM`). The caveat that it was never rendered here still stands.

### E-key item pickup now plays a sound; both it and G's existing doomshotgun are now preloaded (DONE - asset files still missing, same gap as before, now two filenames deep)

Requested so taking an item with E plays a sound for video 1. Added `soundPlayer.play("pickup.wav")` to `Main.java`'s E-key handler on a successful pickup, matching the existing shape of G's `destroyAt(...)` -> `doomshotgun.wav` call right below it.

Bug caught and fixed along the way: the scene-load preload loop only ever collected sounds from `kf.sound` across every instance's keyframes, so neither "doomshotgun.wav" (G) nor the new "pickup.wav" (E) - both fixed, non-scene-authored SFX triggered straight from a key handler - was ever in that set. Both would have hit the exact first-press decode-on-the-audio-thread mistiming `SoundPlayer.preload`'s own doc warns about ("not quite locked to the first frame"), every session, on whichever of G/E got pressed first. Added both filenames to the preload set explicitly.

Still missing, pre-existing (not introduced here): no `src/main/resources/sounds` directory exists in this checkout, and there is no `.wav` file anywhere in the project - same gap already flagged for `doomshotgun.wav`, now also true for `pickup.wav`. `SoundPlayer` fails soft (logs a warning, no-ops) rather than crashing, so nothing breaks, but neither sound will actually be heard until real `.wav` files are supplied and placed at `src/main/resources/sounds/doomshotgun.wav` and `.../pickup.wav`.

> **Status (2026-10-08):** unchanged: the `.wav` files are still not in the upload (section 6, item 2).

### Sandbox reset: rebuilt from the delivered archives; regression tests now live IN the repo

The sandbox filesystem was reset between turns: /home/claude/work and every test under /tmp were gone, and only the
archives delivered on Sep 25 survived - so anything not yet packaged (the previous turn's tutorial sections, the
CRATER_RADIUS_VOXELS constant, the bend write-up) had to be redone. The regression checks used to live in a scratch
directory outside the repo, which is exactly why they were lost. They now live in tools/regression/ (RegressionPrimitives,
RegressionTaper, RegressionPerVertexColor, RegressionCraterOOM) with a run.sh that compiles the project and runs them
under a 512MB heap. It needs JOML compiled from source - GitHub's JOML sources are NOT plain-javac-able: the
//#ifdef __GWT__ blocks must be stripped and the org/joml/jre and org/joml/experimental packages left out - plus the
LWJGL 3.3.3 jars.

> **Status (2026-10-08):** the four suites named here are now the full list of section 1, all run by `tools/regression/run.sh`.

### Taper voxelization clipped (then over-stretched) any shape that gets WIDER than its original box - FIXED, third attempt

First attempt shipped only tested a narrowing taper and the identity case - a widening one (bottomScale=1 ->
topScale=2) came back at 77% of the exact frustum volume, because the voxel grid is sized from the instance's own
raw scale before any modifier, so whatever grows past the original box was never in the grid at all.

Second attempt widened gridX/gridZ (and gridOriginLocal's own placement) by the taper's own max scale factor and
made it WORSE - 308% of the exact volume, not 77%. The reason took working through carefully: rasterizeAdaptive's
canonical-space formula, (gox-offX)/gx*extent+origin, ALWAYS re-normalizes the full [offX,offX+gridX] octree range
to exactly [origin, origin+extent] - regardless of what gridX actually is. So widening gridX alone does not widen
what "canonical space" means to the classifier; it only adds resolution inside the SAME window. wrapWithTaper kept
classifying the same FRACTION of the grid as before, and the wider gridOriginLocal then placed that same fraction
across a proportionally wider world-space region - stretching the whole shape by the taper's max scale, not just
its actually-wide end.

Third attempt: rasterizeAdaptive's own 18-argument overload (already used by voxelizeMesh for an arbitrary
bounding box) takes localOrigin/localExtent directly - scaling X/Z's by taperMaxScale (Y untouched, taper never
touches it) makes the widened grid's own full range map to TRUE canonical [-0.5*taperMaxScale,
+0.5*taperMaxScale] instead of a self-normalized [-0.5,0.5]. wrapWithTaper's own math (divide by the real scale
factor to undo the taper) was never the problem and did not change - it just needed input already expressed in
the right units. Verified: RegressionTaperWide now reports 1.00017 (was 3.08, was 0.77) and is wired into run.sh;
every already-shipped case (all primitive volumes, the narrowing-taper frustum, identity taper, the panel/OOM/
color suites) reports IDENTICAL numbers to before touching any of this, confirming the fix is additive, not a
side-effect-laden rewrite. taperMaxScale never goes below 1.0 by construction, so a narrowing-only taper's own
grid, offsets and canonical mapping are byte-for-byte what they always were.

A second, smaller instance of the exact same mismatch was still sitting in PbdRenderer.buildVoxelColorFn, which
independently recomputes gvx/gvz from inst.scale for its own UV normalization rather than reading the grid
PrimitiveVoxelizer actually built - fixed the same way (duplicating the small taperMaxScale scan, since Result
does not currently expose its own gridX/gridY/gridZ to read instead). This one is NOT independently verified by
an automated test the way everything else above is - buildVoxelColorFn needs a constructed PbdRenderer, which
needs a real GL context this sandbox does not have - so it is a reasoned, not measured, fix; worth a real check
when a build with a GPU is available.

bend has the same grid-extent problem and is NOT fixed by any of this - its own widest reach depends on the whole
arc, not one linear factor, so it needs its own extent computation, most naturally from the polar-geometry
structure described in the bend entry above (rho's own maximum over the swept angle range) rather than a reused
taperMaxScale.

> **Status (2026-10-08):** the taper half stands; `bend` has the same grid-extent problem and is still open (section 6, item 12).

### Taper modifier now accounted for in voxelization (DONE - part of the "modifiers not applied" gap)

Of the two modifiers flagged as a confirmed, real gap two turns ago
(voxelization tests the UN-modified canonical shape, so a bent or
tapered instance destroyed into voxels didn't match its own visible,
modified shape), taper is now implemented - bend is NOT (see below for
exactly why, not just "still pending").

Taper (pbd.tese's own applyTaper: pos.x *= scale, pos.z *= scale,
scale linear in Y from bottomScale to topScale, Y itself untouched) is
CLOSED-FORM invertible precisely because Y never changes - scale is a
direct function of a region's own Y range alone, with no coupling to
X/Z the way bend's own angle-times-offset formula has. PrimitiveVoxelizer.
wrapWithTaper inverse-maps a region's [minX,maxX]x[minZ,maxZ] through
the CONSERVATIVE (widest) scale bound across that region's own
[minY,maxY] before testing it against the shape's own already-existing
canonical-space classifier. The widened box is a SUPERSET of the region's true preimage, so
BOTH verdicts carry over safely (outside the shape -> outside; inside -> inside). An earlier version downgraded every
"inside" to "ambiguous" on the mistaken belief that only "outside" survives the widening; that never changed a result
(the size-1 point test agrees with a box verdict by construction) and only forced needless recursion - removing it
kept the voxel set identical (same 23,272 regions, same volume ratio) and cut voxelization of the test frustum from
300 ms to 92 ms.

Verified rigorously, not just "produces voxels that look tapered": a
cylinder tapered bottomScale=1.0/topScale=0.3 into a real frustum
(lampshade shape) matches the EXACT mathematical frustum-volume formula
(pi*h/3 * (r1^2+r1*r2+r2^2)) to within 0.005% (ratio 1.00005). An
identity taper (both scales = 1.0, a no-op) still matches the plain
cylinder volume formula to within 0.08%, confirming the wrapping
introduces no error when there's nothing to correct for. Directly
confirmed the shape actually narrows near the tapered end too (not
just a volume coincidence): the top's own real width measured ~34
voxels against a full ~100-voxel diameter, matching the requested 0.3x
scale. Full primitive-type and thin-panel-performance regression
suites re-run after restructuring voxelize()'s own switch statement to
share one classifier-wrapping point across cylinder/cone/torus/sphere -
identical numbers to every prior run for every UN-modified instance,
confirming the restructuring itself introduced no regression.

> **Status (2026-10-08):** taper is done; `bend`, `twist`, `curve` and `shear` remain (section 6, item 12).

### Bend modifier: the inverse is exact and well-conditioned - two earlier conclusions in this file were wrong

Two turns ago this file claimed bend has no closed-form inverse. Wrong. One turn ago it claimed the closed form exists
but is numerically unreliable for high-aspect / small-angle bends, and that no cheap check can catch its failures. Also
wrong. What actually happened: tan(phi) = aspect*newLength / (aspect/totalAngle - newOffset) fixes phi only up to +n*pi,
and every candidate is an EXACT solution of both forward equations - I was choosing among them by smallest residual,
which cannot tell exact solutions apart. The "errors of hundreds of units" were exact solutions lying far outside the
canonical box. The right selector is the canonical range itself (a preimage must have |l|,|k| <= 0.5), which is also
precisely the question voxelization asks.

Re-verified on 300,000 random trials (angle +-0.05..8 rad, aspect 0.2..80, output points rounded to float32): an in-box
preimage is found for 100% of them; in the non-folding, non-wrapping regime 219,536 of 219,536 recover the true (l,k),
worst error 2.8e-6; and the case that "proved" unreliability (angle -0.0516, aspect 78.9) is recovered exactly. The
forward formula does cancel badly in float32 there (R1 ~ -1528 against a result ~ -0.8, costing roughly 1e-4), but that
is the GPU's precision, irrelevant to an inverse that runs in double.

Structure worth building on: in the aspect-scaled plane (u = aspect*length, w = offset) bend is EXACTLY a polar map -
(aspect*newLength, R1-newOffset) = (R1-k) * (sin phi, cos phi), phi = totalAngle*(l+0.5). The image of the canonical box
is therefore an annular sector, and point AND box classification both reduce to polar geometry: rho range from distance
extremes, phi range from corner angles, enumerate the branches phi = theta + n*pi inside [0, totalAngle], build one
canonical AABB per branch (a superset of that branch's true preimage, so both "inside" and "outside" verdicts stay safe),
call a region inside if any single branch's box is inside, outside only if every branch's is. A non-folding bend also
preserves volume for shapes symmetric in the offset axis (the polar Jacobian is 1 - k*totalAngle/aspect, which averages
to 1) - a ready-made exact test.

Not done yet: (1) the grid must cover the BENT shape's own box (same root cause as the taper-clipping entry above);
(2) the wrapper itself plus angle/axis parameter parsing; (3) a rejection test against an analytic ground truth - a first
attempt reported 14.5% of out-of-box points "accepted", most likely an artifact of the test (outside the box the map folds
onto in-box images, so accepting can be right), but that is unconfirmed; (4) targeting: findDestructibleAlongRay tests the
UNMODIFIED canonical box, so a bent or tapered instance can be "hit" on empty space or missed on its visible geometry
(inferred from that ray test's own log wording, not yet reproduced).

> **Status (2026-10-08):** none of the four "Not done yet" items has been built; the maths stands (section 6, item 12).

### Voxel color sampled per-REGION instead of per-VERTEX (FIXED)

Reported directly, from a real screenshot: low-resolution (large,
merged) blocks showed blocky/streaky patterns instead of real texture
detail. Confirmed in VoxelMeshBuilder.buildMeshWithColor: colorFn was
called exactly ONCE per region (at that region's own corner), and the
SAME single flat color got broadcast to every vertex of that region's
entire box - invisible for a small, crater-adjacent region (size 1-2),
but MOST of a real object's own surface stays merged into large
regions by design (an untouched interior region has no reason to
expand - see PrimitiveVoxelizer's own doc), so most of the visible
surface was one-flat-color-per-block rather than showing the real
texture's own variation.

Fixed: colorFn is now a BiFunction(voxel, worldVertexPosition) called
once per VERTEX (addFace's own 4 corners per face), not once per
region - each corner of a large region's face now samples a
DIFFERENTLY positioned texel, and the GPU's own rasterizer interpolates
smoothly between them across the face. Verified directly: a synthetic
linearly-varying color function, sampled across a single size-20
region, now shows real 0.0-to-1.0 variation across that one region's
own vertices (a single face's own 4 corners: [0.0, 0.0, 1.0, 1.0], not
four identical values) - confirmed NOT possible under the old per-
region sampling. Performance re-verified at realistic scale (40,500
entries, ~972,000 resulting vertices, each requiring a real texture-
pixel lookup): 1.8 seconds - well within a single G-key press's own
budget, not a per-frame cost.

This is per-VERTEX interpolation, not true per-fragment GPU texture
sampling - a further, real improvement over this (binding the actual
texture to voxel debris and sampling it continuously in the fragment
shader, rather than baking an interpolated color into vertex
attributes) would give genuinely crisp per-pixel detail even on a
single large face, matching what the un-destroyed primitive's own
tessellated surface already gets. Not attempted this turn - a bigger
change (a new shader path specifically for voxel debris, since the
current useVertexColor mode exists exactly to avoid needing per-
fragment texture binding for this case) - but the per-vertex fix
already directly addresses the reported blocky/streaky symptom.

### G-key destruction logic moved behind PbdRenderer's own facade (DONE)

The architectural refactor requested (and previously, honestly,
deferred twice for time/risk reasons) is done: the full raycast/
retarget/crater/color/state/draw pipeline for voxel destruction no
longer lives inline in Main.java's own game loop. Main.java's G-key
handler is now 4 lines - call PbdRenderer.destroyAt(rayOrigin,
rayDir), print the returned message, play the SFX if hit() - instead
of the ~300 lines it used to be. The destroyed-debris draw loop and
scene-switch cleanup are likewise now renderer.drawDestroyed(...) and
renderer.clearDestroyed() calls instead of Main.java directly touching
destroyedRenderers/destroyedGridOrigins.

Moved as private methods/state on PbdRenderer: DestroyedPlacement,
applyCraterAdaptive, computeCraterRemovedSet, encodeGridCoord,
buildVoxelColorFn (now reading this class's own voxelTexturePixels
directly rather than needing it passed in), parseColorTriple (a small
duplicate rather than a cross-class dependency for one trivial
function). applyCraterAdaptive and computeCraterRemovedSet turned out
to touch no instance state at all, so both ended up static - better
design, and it kept them testable via reflection without needing a
real GL context. buildVoxelMeshRenderer is a new, deliberately
minimal, voxel-specific GPU-renderer construction, NOT a share of
Main.java's own general-purpose buildMeshRenderer (which also handles
regular mesh instances, container items, and LOD rebuilds - pulling
those three unrelated call sites into this refactor too would have
expanded it well past what was actually asked); voxel debris always
carries real per-vertex colors and renders through
ClassicMeshRenderer's own useVertexColor path, which the shader
prioritizes over baseColor/texture entirely, so the texture/uvScale
setup Main.java's own buildMeshRenderer does for regular meshes is
correctly skipped here rather than pointlessly duplicated.

Verified, not just compiled: every existing regression test for this
system (the OOM fix under a constrained 512MB heap, the ray-cast
crater's own non-sphericity check, the zero-removed-voxels bug, all
seven primitive types including the anisotropic-splitting fix, the
thin-panel performance fix, the per-object-extent UV mapping) re-run
against the new PbdRenderer-based location and produced IDENTICAL
numbers to their pre-refactor runs (same region counts, same
timings, same reach-per-direction values) - this was a relocation of
working, tested logic, not a rewrite that happened to compile.

> **Status (2026-10-08):** the flow moved one step further since: into `pbd.voxel.DestructionWorld` (pure, no GPU - `PbdRenderer.destroyAt` is a thin call into it). See the journal entry "G strong enough to cut" above.

### Voxel-destruction texture crash: real root cause found this time - a DOUBLE decode, not decode timing

The last two attempts both moved WHEN stbi_load ran (mid-frame, then
scene-load-time) without questioning whether calling it TWICE on the
same file was itself the problem - and the load-time version crashed
too, its own log landing right after MaterialTextureArray's own resize
line for a large (3000x2000) JPEG. That timing is the real clue: this
project's own materials get decoded once by MaterialTextureArray for
its GL upload, and voxel-color sampling's own cache was decoding the
EXACT SAME FILES a second time, immediately after - two native
allocate/decode/free cycles on the same large image back to back,
exactly the kind of repeated stress that exposes native heap
corruption (Windows NTSTATUS 0xC0000374).

Fixed by never decoding twice: MaterialTextureArray.build() now keeps
a copy of the RGBA bytes it already decodes for its own GL upload
(MaterialTextureArray.PixelData, pixelDataOf()) instead of discarding
them once uploaded. PbdRenderer's voxel-color cache reads directly
from that - zero additional stbi_load calls anywhere in this
project's own voxel-destruction path. This also resolves a real,
separately-raised concern: a cache built by scanning materials only
once would silently miss anything loaded later through this engine's
own intended hot-loading; reading from MaterialTextureArray's own
already-built data instead means this cache is derived from - not a
second, independent process racing against - whatever build() was
actually called with, including a future hot-load-triggered
re-upload().

Texture sampling was NOT removed - kept and fixed, per direct
instruction. If this build still crashes, the STBImage angle (timing,
double-decoding) is very likely fully ruled out at that point, which
would itself be useful: worth checking next whether it's specific to
one exact file (a corrupt/unusual JPEG, say - charcoal.jpg being large
enough to need resizing is at least a correlation worth re-testing
in isolation) rather than assuming the whole feature again.

> **Status (2026-10-08):** never confirmed on real hardware (section 5, item 2; backlog item 5).

### "G-key rays pass through voxels" - not independently reproduced or newly diagnosed this turn

Real bug already found and fixed two turns ago: the retarget path's
own local-space ray-box test, when it missed (common once debris has
already been partially carved), used to fall back to the CAMERA's own
position as the impact point - nowhere near the object - which this
project's roadmap already documents. Re-checked the retarget sphere-
test's own radius formula specifically (half the box diagonal,
0.5*sqrt(sx^2+sy^2+sz^2)) since an elongated object seemed like a
plausible new lead - it's mathematically exact for "smallest sphere
guaranteed to contain the whole box regardless of aspect ratio", so
not a bug on its own. Nothing else in this path changed since the
already-documented fix, and no new log/repro came with this specific
report to point at something concrete beyond that. If this persists
on the build with the texture-crash fix above, the most useful next
report would be the same kind of detail that made the texture crash
and the earlier NaN/hidden-instance bugs possible to actually fix:
console output from the exact G-key press that passed through
(position/direction lines this project's own logging already prints),
so a real, specific case can be checked rather than guessed at again.

> **Status (2026-10-08):** still unreproduced (backlog item 6). What the destruction world does since is covered by `RegressionDestructionWorld`: a second shot goes through the first hole, and a shot at the remains removes more of them.

### Voxel-destruction texture crash: fourth attempt, DIFFERENT approach this time (not just harder-guarded)

Every earlier attempt called STBImage.stbi_load() fresh, live, from
inside the G-key handler's own call stack - mid-frame, interleaved
with whatever else that same frame's own GL calls were doing - and
kept correlating with a real, repeatedly-reported native crash
(Windows NTSTATUS 0xC0000374) despite defensive hardening and one
unrelated-but-plausible fix (a shared shader program) that turned out
NOT to be the actual cause either, confirmed by the crash recurring
again after that fix shipped. This turn moved texture decoding
entirely OUT of the game loop: every texture a scene's own materials
reference is now decoded ONCE, at scene-load time, in the exact same
call context MaterialTextureArray's own (long-confirmed-working)
texture loading already uses - cached as plain Java byte[] pixel data
on PbdRenderer (voxelTexturePixelsFor). The destruction code path
itself no longer imports or calls STBImage/MemoryStack at all - it's a
pure Java array lookup from that point on. This is a genuinely
different fix, not the same pattern retried harder - if the crash
persists after this, the actual cause is very unlikely to be STBImage
call timing/context at all, which would be a real, valuable data point
for whatever's tried next.

> **Status (2026-10-08):** superseded by the double-decode finding above, which replaced this entry's cache by a read of `MaterialTextureArray`'s own pixel data.

### Modifiers (bend/taper/twist/curve/shear) are not applied during voxelization at all - confirmed architectural gap, not a quick fix

Confirmed by reading ModifierRegistry's own doc directly: modifiers are
deliberately never touched by the parser or any CPU-side code - they're
applied entirely inside the GPU's own tessellation shaders
(pbd.tesc/pbd.tese), which is where the actual deformation math (a
real, apparently non-trivial "constant-radius circular arc" bend
formula, taper, with their own prior bug-fixing history visible in
that shader's own comments) lives. PrimitiveVoxelizer has no path to
that GLSL logic at all, so a bent/tapered instance voxelizes as its
UN-deformed base shape - the voxel result won't visually match a
modified primitive. A real fix means porting that same deformation
math into Java, applied to each candidate voxel position during
classification (or the classifier's own local-space point, inverse-
transformed through the modifier) - substantial, correctness-
sensitive work given the shader's own comments describe a formula
that already went through real bugs before reaching its current form;
not attempted this turn rather than risk a rushed, likely-wrong port
of unfamiliar deformation math under time pressure. Next step:
read pbd.tese's own bend/taper implementation in full (lines ~200-380)
before writing anything in Java, to reuse the ALREADY-correct formula
rather than re-derive one independently.

> **Status (2026-10-08):** partly closed: taper has been applied in voxelization since (entries above); `bend`, `twist`, `curve` and `shear` are not (section 4, backlog item 12).

### CRITICAL: non-cube primitives were catastrophically broken by an earlier "thin panel" fix (FIXED)

Confirmed by direct testing (a real gap this session should have
caught much earlier - every prior verification only ever used type
"cube"): cone/sphere/torus produced ZERO voxels (disappeared entirely),
cylinder produced its ENTIRE bounding cube as one solid block (its
curvature never resolved, looking "almost square"). Root cause: an
earlier fix for a real thin-panel problem (a door only 3-4 voxels thick
on one axis forcing needless fine subdivision on its much larger other
axes) used a "stop subdividing once size drops to this shape's
smallest axis" heuristic - which for anything WITHOUT one dramatically
thin axis (every round shape - cylinder, sphere, cone, torus, where
all three axes sit close together) stopped subdivision after
essentially one level, since the smallest axis is nearly as large as
the whole grid.

Fixed with TRUE independent per-axis (anisotropic) splitting instead
of a shared-size heuristic - VoxelOctree.insertFilledBox already
accepted independent min/max per axis, so this needed no change to the
octree itself, only to how rasterizeAdaptive recurses: each axis now
halves and keeps recursing independently, stopping only once ITS OWN
size reaches 1, not once some shared threshold is hit. Verified
rigorously, not just "produces some voxels": cylinder/cube volume
ratio 0.786 (expected pi/4 = 0.785), cone/cube 0.262 (expected pi/12 =
0.262), sphere/cube 0.524 (expected pi/6 = 0.524), torus 0.156
(expected ~0.155) - matching the known geometric formulas almost
exactly. Also confirmed the earlier thin-panel performance fix and the
crater OOM fix BOTH still hold with the corrected splitting (identical
numbers: 3916 regions/8ms for the thin panel, 40633 entries/50ms for
the OOM stress test) - the anisotropy needed to be correct, not just
present, and now is.

### Retarget crater could silently place the impact at the CAMERA's own position (FIXED)

A real, reported "the shot registers (SFX plays) but doesn't alter the
voxel, about half the time" bug: the follow-up-hit code path's own
finer local-space ray-box test, when it missed (which happens often
once debris has ALREADY been partially carved by an earlier hit - its
real, now-smaller visible extent no longer fills the canonical box
this test checks against, even though the broader sphere test that
triggers a retarget at all is generous enough to still find it),
fell back to using the camera's OWN local-space position as the
"impact point" - nowhere near the actual object, so the crater computed
from it removed nothing from what's really left, while the SFX (tied
to the retarget being FOUND, not to the crater actually landing) still
played. Now falls back to the ray's own closest approach point to the
object's center instead - not exact, but genuinely near the object,
not the player.

### Architectural refactor requested, honestly deferred (not attempted this turn)

Explicitly, strongly requested: the full G-key destruction/retarget
logic (raycasting, crater computation, texture-color sampling, mesh
building, renderer construction, state tracking) currently lives
inline in Main.java's own game loop instead of behind a proper facade
(PbdRenderer, matching setOpen/hideInstance/isContainerOpen and
everything else already moved there this session). This is a real,
valid architectural complaint, not dismissed - but moving it correctly
means relocating several hundred lines across multiple interdependent
helper methods (applyCraterAdaptive, computeCraterRemovedSet,
buildVoxelColorFn, the retarget ray-sphere test, the destroyed-debris
draw loop) in one pass, immediately after fixing the single most
severe bug this session found (every non-cube primitive silently
broken). Attempting a rushed, large-scale reorganization in the same
turn - on top of an already reported pattern of regressions stacking
on regressions - risked exactly the kind of new bug a careful,
dedicated pass would avoid. Next priority for a turn with room to do
it properly and verify each piece as it moves, not silently dropped.

> **Status (2026-10-08):** done - see "G-key destruction logic moved behind PbdRenderer's own facade (DONE)" above.

### Tutorial: reuse-what-exists and quality-submission sections (DONE)

server/tutorial.php now has "Reusing what's already shared" (File > Import > PBD Scene / PBD Materials with Include vs
Merge, from a file or a URL; the Reference (pbd_ref) button and Test Reference; Clear Remote Cache, because remote fetches
are cached permanently) and "Making it a quality submission" (keyframes, keyframe sounds, container-door links, lights,
materials, references). Every label and behavior cited was read out of the addon source rather than remembered: a first
draft written from memory was wrong in several places (there is no Description field in the File metadata box, Reference
and Light are not in the Shift+A menu, Load .pbdmat is local-only) - and the "import operators don't exist" conclusion I
drew from that same draft was itself wrong, because I had only listed the addon's top-level files and missed its
operators/ package.

Open: the file FORMAT supports description=, but the Blender panel exposes no field for it (the File metadata box shows
Name, Kind, Authors and the automatic Origin only), so an addon user cannot author one.

> **Status (2026-10-08):** `server/tutorial.php` is not in the upload, so it cannot be edited from here; draft text for new sections (lever arm, pivot, embedding, "re-use existing resources with `include_material`") is in `ADDON_USER_GUIDE.md`. The `description=` gap is backlog item 14.

### Real per-voxel texture-pixel sampling RE-ENABLED and refined

Was disabled two turns ago (native crash, couldn't verify the fix) -
re-enabled now that the actual crash cause (ClassicMeshRenderer
recompiling a shader on every construction - see that turn's own
history) is believed fixed. ALSO refined the UV mapping itself: the
earlier attempt normalized a voxel's position against the shared
octree's own gridSize directly, which for a non-cubic object (most of
this project's own door/panel assets) compressed almost the whole
texture into a tiny sliver of the 0..1 range. Now normalizes against
the OBJECT's own real per-axis extent instead - verified directly: a
thin door panel (gvx=4, gvy=60, gvz=100) now spans U/V ranges of
[0.0, 0.99] on both axes, not a compressed fraction. Extracted into a
shared buildVoxelColorFn used by BOTH the initial-destruction and
follow-up-hit (retarget) paths, so a voxel hit a second time keeps its
real texture-sampled color instead of reverting to flat.

> **Status (2026-10-08):** the crash came back after this and the entries above chased it (decode timing, then the double decode). Sampling was kept, as instructed. Still unconfirmed on hardware.

### availableVolume/canHide facade methods added

availableVolume(containerName): the container's own bounding-box
volume minus the summed volume of every item currently placed in it
(getContainerItems(), extended to also expose each item's own width/
height/depth for this purpose). canHide(containerName,
playerHitboxVolume): availableVolume >= the given hitbox size -
playerHitboxVolume is a parameter, not hardcoded, since this facade
has no business assuming the actual player capsule/hitbox dimensions
gameplay code owns. Verified against real reflection-constructed
objects: an empty 2x2x2 container reports volume 8; placing a 1x1x1
item drops it to 7; canHide correctly returns true/false against
different hitbox sizes at that 7.0 available volume.

> **Status (2026-10-08):** `Main.availableVolume` and `Main.canHide` exist; the two backlog entries of the same names further down are done.

### Crater OutOfMemoryError on a wider blast radius (FIXED, directly caused by last turn's own radius increase)

Confirmed by a real stack trace: raising craterRadiusVoxels from 6 to
14 (requested as "bigger alterations") widened outerTestRadius (used
to decide which merged regions get expanded for per-voxel crater
testing) from ~8.4 to ~19.6 - at that reach, a single large merged
region (up to 128+ voxels on a side for a sizeable prop) could get
fully expanded into size^3 individual int[] entries just for coming
anywhere near the blast, which for one big region alone could already
be millions of entries, several such regions compounding further into
a genuine OutOfMemoryError. Fixed by replacing the flat "expand the
whole region the instant it's in reach" approach with
applyCraterAdaptive - the same octree-recursion principle
PrimitiveVoxelizer.rasterizeAdaptive itself already uses for the
original voxelization: a region entirely outside the outer radius
survives whole and unexamined; only genuinely ambiguous regions -
actually near the crater's own boundary - recurse into progressively
finer octants, so only geometry ACTUALLY close to the impact is ever
refined down to individual voxels. Verified against a real, memory-
constrained run (-Xmx512m) on a realistically-sized object at the new
14-voxel radius: 43ms, ~2MB memory delta, 40,633 surviving entries -
not the millions that caused the crash.

> **Status (2026-10-08):** the radius is 30 now and the crater a solid volume; `RegressionCraterOOM` (16 checks) holds the cost inside a 512 MB heap for a 0.8 cube, a 2 x 2 x 0.2 wall and at double the radius. `LongIntMap` and `VoxelCrater` point at this entry.

### destroyedRenderers/destroyedGridOrigins were never cleared on scene switch (FIXED, likely related to a separate reported N-key crash)

A real, separate reported crash (ArrayIndexOutOfBoundsException on
pressing N) couldn't be pinned to an exact line with certainty (the
reported line number no longer matched current code after several
turns of edits, and this session's sandbox has no way to load two
different real scenes and actually press N to reproduce it directly).
This IS a confirmed, real bug found along the way, and a strong
candidate for the same crash's actual cause: destroying something in
one scene, then switching (N/B) to a DIFFERENT scene - very possibly
with fewer instances, or none at all - left stale index-based entries
in destroyedRenderers/destroyedGridOrigins pointing at positions that
may not exist in the new scene's own, differently-sized arrays.
containerContents was already correctly cleared on scene switch; these
two were not. Fixed by clearing both (each renderer properly closed
first, not just dropped, to avoid yet another GL-resource leak) at the
same point containerContents already resets. If the N-key crash
persists after this, the exact scene sequence (which scene, how many
instances, what was destroyed beforehand) would help pin down whatever
remains.

> **Status (2026-10-08):** the fix is in place (`renderer.clearDestroyed()` at scene load); `Main` also resets the lever-arm drag state there.

### Not addressed this turn (too large to rush, listed honestly rather than done halfway)

- Blender addon: no button to export one or more `.pbdmat` files
  independently (only as part of a full `.pbd` export today).
- Blender addon: including a remote `.pbdmat` shows nothing in the
  Material panel - not yet investigated.
- The `doomshotgun.mp3` SFX request: the trigger call
  (`soundPlayer.play("doomshotgun.wav")`) is wired into every
  successful destruction/follow-up hit this turn, but SoundPlayer only
  supports `.wav` (`javax.sound.sampled` has no built-in MP3 decoder -
  see that class's own doc). Adding real MP3 decoding would mean a new
  third-party dependency this sandbox has no way to test audio
  playback against at all - safer to flag this plainly than integrate
  something unverifiable under time pressure. The file needs
  converting to `.wav` and placed at
  `src/main/resources/sounds/doomshotgun.wav` for the now-wired trigger
  to actually play it.
- The METIER/domain-specific requirements the tutorial doesn't cover -
  noted as raised, not yet actioned; needs the user's own specifics on
  what's missing before this can be worked on concretely.

> **Status (2026-10-08):** these are in the backlog (section 6): the `.wav` conversion of the shotgun sound (item 2), exporting a `.pbdmat` on its own and showing a remote one (item 15), the tutorial's domain requirements (item 19).

### Raycast returning NONE despite real hits, and clicking voxel debris no longer toggling its door (FIXED - same root cause)

Confirmed directly from a pasted console log: several instances logged
"HIT at world-dist=NaN". Math.max/min in Java propagate NaN through
any comparison (NaN < x and NaN > x are both false), so these hits
could never win against a valid closestDist, but were never explicitly
excluded either - traced to hideInstance() zeroing a destroyed
instance's own worldTransforms scale to hide it (by design), which
makes that matrix singular; inverting a singular matrix produces NaN/
Infinity throughout. findDestructibleAlongRay never skipped an
already-destroyed instance, so it kept testing this degenerate matrix
every time. Fixed by skipping any instance flagged in hiddenInstances
before the ray-box test even runs (voxel debris already has its own,
separate re-hit path - the ray-sphere retarget test - for exactly this
case). Added a second, defensive layer directly in rayBoxIntersection
itself (returns -1 instead of a NaN result, via Float.isNaN) in case
some other path ever feeds it a degenerate matrix this specific fix
didn't anticipate. The same zeroed-matrix problem affected
toggleKeyframedInstanceAlongRay (mouse-click door toggle) identically -
a zero-scale box has no volume for a click to ever land in, which is
the actual reported "clicking a voxel no longer triggers its
animation" bug - fixed by using the instance's LIVE transform
(resolveLiveTransforms, the same one its voxel debris is already drawn
at) instead of the zeroed one whenever the instance is hidden.

### Voxel debris was untargetable by BOTH systems that raycast against an instance's own bounding box (FIXED)

Found by taking a reported "the second shot crashes it" claim seriously
and re-reading the destruction/retarget code with fresh eyes: every
single `new ClassicMeshRenderer(...)` call - triggered by EVERY voxel
destruction, EVERY follow-up hit on existing debris, and every
container item / regular mesh instance too - was compiling and linking
a brand new GLSL program from source (`classic.vert`/`classic.frag`)
every time, rather than sharing one compiled program the way a shader
is meant to be used. Repeated shader compilation under time pressure,
back-to-back during actual gameplay, is a known source of driver-level
instability on some GPU/driver combinations - lines up with a crash
specifically correlating with a SECOND (or later) destruction in a
session, not the first, and with "pressing G too much" crashing things
independently reported earlier too. Fixed by caching one compiled
ShaderProgram per shaderDir path (a static map), shared across every
ClassicMeshRenderer that resolves to it - all three of this project's
own construction sites already pass the exact same path literal, so
this covers voxel destruction, container items, and regular mesh
instances all at once, not just the one path that happened to be
under test. `close()` no longer tears down the shared program (only
this instance's own mesh-specific buffers) since other live renderers
may still depend on it.

### Per-voxel texture sampling DISABLED (safety rollback, not fixed)

Was implemented (real STBImage-based pixel sampling with a triplanar
UV projection), defensively hardened across multiple turns (bounds-
checked texture dimensions, a broad catch(Throwable)), and STILL kept
correlating with a real, reported native crash on a textured
material's own destruction (Windows NTSTATUS 0xC0000374, heap
corruption) - including a second crash reported on the SECOND
destruction of a session, immediately after this same code had just
been touched again. Never reproducible in this sandbox (no working
LWJGL natives here at all), and not conclusively root-caused despite
direct line-by-line comparison against MaterialTextureArray's own
working stbi_load usage. Given an explicit instruction not to keep
stacking unverified fixes on top of an unresolved crash, this turn
REMOVED the STBImage-touching code path entirely from voxel
destruction rather than patching it further - every voxel now gets the
material's own flat color (or this renderer's own neutral-gray
default), the same behavior confirmed working before texture sampling
was ever added. Re-enabling real texture sampling needs either a way
to reproduce/debug the native crash directly (a Windows machine, or
working LWJGL natives in a sandbox), or a from-scratch reimplementation
someone can actually test end-to-end before it ships again.

> **Status (2026-10-08):** superseded: sampling was re-enabled ("Real per-voxel texture-pixel sampling RE-ENABLED" above) and the crash chased further in the entries above it.

### gridOriginLocal was fundamentally miscalculated for non-cubic objects (FIXED)

Confirmed root cause of TWO separate reported symptoms at once - "the
voxel is still not visually positioned correctly" (reported across
several turns) and "no crater appears anymore" (a specific reported
case: cube.019, 3 voxels thick on one axis, 0 out of 29097 voxels
removed): `gridOriginLocal`'s own formula divided offX/offY/offZ (a
quantity in gridSize's shared, cubic octree units) by gridX/gridY/gridZ
(this axis's own, often much smaller, real voxel count) before scaling
by this axis's real-world size - a unit mismatch. One octree unit
always equals voxelWorldSize on ANY axis (confirmed against
PrimitiveVoxelizer's own sx/sy/sz = instance.scale.x/y/z), so the
correct conversion is a flat multiply by voxelWorldSize, not a divide
by gridX first. For a roughly-cubic object this produced nearly the
same (wrong, but close enough to go unnoticed) answer either way -
only became severely visible for a strongly non-cubic shape, which
describes most of this project's own door/panel assets. Fixed at both
call sites (the canonical-primitive path and voxelizeMesh's own copy).
Confirmed via direct reproduction against the exact reported case
(cube.019): before the fix, the computed impact point's Z coordinate
(127.6 in the real, correct grid) came out as 138 - completely outside
that object's own real Z-range of [126,129] - after the fix, 57 real
voxels correctly overlap the crater instead of 0.

### Voxel octree needs true anisotropic subdivision for thin geometry

Confirmed root cause of a real reported case (a door panel producing
thousands of tiny separate pieces and 100K+ vertices for a small
crater): the octree is cubic (size governs all three axes together),
so a THIN primitive (small on one axis, like a door's own ~2cm
thickness) forces recursion to keep chasing that thin axis's
resolution all the way down, dragging the other two - much larger -
axes into needless fine subdivision along with it, even deep in what
should be mergeable "interior". Mitigated this turn by stopping
recursion once size reaches the SMALLEST of the shape's own three
real per-axis voxel counts (falling back to a center-point test, same
as the existing size==1 case always did) rather than always going to
size==1 - measurably better (confirmed via a direct test against the
exact reported dimensions: region count dropped from what would have
been ~26,596 individual pieces to 3,916), but NOT a full fix: the two
larger axes still can't merge PAST that point even though they'd have
room to. A real fix needs the octree itself to split axes
independently (stop subdividing an axis specifically once IT reaches
its own native resolution, keep splitting others that still need it) -
a bigger structural change to VoxelOctree not attempted this turn,
given the risk of reworking a core data structure without enough time
left to verify it thoroughly.

> **Status (2026-10-08):** done by the true per-axis splitting in "CRITICAL: non-cube primitives" above.

### Reported rotation/scale bug on some primitives (not yet diagnosed)

Reported via two screenshots (a grill/BBQ scene) showing an oddly thin,
mis-oriented flat panel/blade shape. No .pbd file was attached for
this specific scene, so the exact instance(s) involved couldn't be
identified or reproduced - worth a closer look with the actual source
file next time this comes up, rather than guessed at without one.

> **Status (2026-10-08):** still no file to look at (backlog item 18, which records one possible lead).

### Crash on G-key destruction of a textured primitive (mitigated, not confirmed fixed)

Reported as a native crash (Windows NTSTATUS 0xC0000374, heap
corruption) when pressing G on a primitive with a textured material -
almost certainly related to last session's new STBImage-based per-
voxel texture sampling, added the same session this was first
reported. Compared directly against MaterialTextureArray's own
working stbi_load/stbi_image_free pattern - the two matched, no
structural difference found by inspection. Added defensive bounds-
checking (validating texW/texH and the buffer's own remaining() before
trusting them) and a broad catch(Throwable) around the whole texture-
loading block, falling back to a flat color on anything unexpected -
a responsible mitigation, not a confirmed fix, since this couldn't be
reproduced or root-caused without a working native LWJGL runtime
(unavailable in this sandbox - see this repo's own session history).
If it recurs, the specific texture file that triggered it would help
narrow this down further.

> **Status (2026-10-08):** mitigated several times (bounds checks, a rollback, a shader cache, decoding at scene load, the double-decode fix); never reproduced here and never confirmed fixed on hardware (backlog item 5).

### The old "not yet implemented" list (every entry from here to the end of the journal carries its current status)
Not yet implemented - captured here rather than attempted under time
pressure alongside whatever else was in progress. Ordered roughly by
how tightly each depends on something else on this list.

### LOD metadata not working in Java (likely quick fix, investigate first)

Reported: LOD tiers set as `1` and `0` in Blender don't work in the
Java engine, which is suspected to expect powers of 2 up to 64+
instead. Checked this turn: the Java side's own `meshDataByLod` is
keyed directly by the `<N>` in a `vertexDataLod<N>` field name (see
`PbdParser.applyInstanceField`'s own `VERTEX_DATA_LOD_KEY` handling)
and used purely as an ORDERING key (a `TreeMap`, ascending) by
`desiredMeshLodTier` in `Main.java` - nothing in that path actually
requires powers of 2 or a specific range up to 64. This means the
reported mismatch is more likely in the BLENDER ADDON's own LOD UI/
export side (what value it asks for, what it writes as the field's own
`<N>`) than in the Java engine's own tier-selection logic - next step
is reading the addon's own LOD properties/export code (likely in
`properties.py`/`export_pbd.py`) to see what it actually expects
there, not assuming the engine side needs to change.

> **Status (2026-10-08):** still open and the add-on's LOD code still unread (backlog item 13).

### Minified .pbd not loading - FIXED (filed as "bug, not yet investigated")

Reported as broken with no further detail yet - needs a concrete
repro (does the ENGINE reject a minified file outright, parse it
wrong, or something else?) before it can be diagnosed. Given this
project's own history this turn (the metadata-value-swallowing
regression in minified mode, already fixed), worth checking first
whether this is the SAME class of issue resurfacing somewhere it
wasn't fully covered, or a genuinely new one.

> **Status (2026-10-07): FIXED, on both producers.** A minified file has no line ends, and the free-text metadata (`name`, `kind`, `author`, `origin`, `description`) is read to the end of the line when unquoted - so one unquoted value swallowed the whole scene. The Java `PbdSerializer` (`quoteMetadataValue`) and the Blender add-on's own minifier (`_quote_metadata_value`) now always quote these fields in minified output. Guarded by `RegressionMinifiedMetadataQuoting` (6 checks), `RegressionFormatRoundTrip` and the add-on check. The real-world trigger was the project's own `blueMetalBoard.pbdbin`.

### canHide facade accessor

A getter (`canHide(containerInstanceName)`, naming TBD) on the runtime
facade returning whether there's enough room to hide in a given
container - built on `availableVolume` below plus the player's own
rough hitbox volume.

> **Status (2026-10-08):** **done** - `Main.canHide` (see "availableVolume/canHide facade methods added" above).

### availableVolume facade accessor

`availableVolume(containerInstanceName)` = the container's own bounding
box volume (already buildable from the existing `volume()` facade
getter added this session) MINUS the summed volume of every item
CURRENTLY placed in it (via `getContainerItems()`, also already built).
Depends on nothing further not already in place - the most
immediately buildable item on this list.

> **Status (2026-10-08):** **done** - `Main.availableVolume`.

### Door-closed facade accessor for AI/modding

A facade method so a modder's own zombie-AI code can check whether a
container's associated door(s) are closed, without needing to know
this engine's own internal `isInstanceOpen`/`isContainerOpen`
mechanics - wraps that existing mechanism, doesn't replace it.

> **Status (2026-10-08):** partly: by instance, `PbdRenderer.isInstanceFullyClosed` and - for a lever arm - the continuous value (`Main.getArmValue`); by container, `PbdRenderer.isContainerOpen` / `areAllDoorsClosed` exist (by index). A by-name accessor on `Main` is backlog item 11.

### Container refill-on-reopen logic needs rework

Current behavior: an emptied container re-rolls new random items if
its door is opened again. Reported as the wrong behavior - once
empty, should STAY empty. Fix should use `getContainerItems()`/
`removeContainerItem()` (built this session) to check REAL current
state rather than re-rolling, and the request notes this is also a
chance to better DISTRIBUTE items across a group of linked containers
(not just decide empty-vs-not).

> **Status (2026-10-08):** half done on 2026-10-08: an emptied container now stays empty when its door is opened again (`pbd.pz.ContainerStock`, `RegressionContainerStock`; section 1, item 10), for the length of a session - nothing is saved to disk. The other half of the request, a better distribution of the items across a group of linked containers, is still open (backlog item 7).

### H-key exit: record an inverse keyframe from the hide start point

When exiting a hiding spot, animate out using the REVERSE of however
the player got in, rather than however `updateHideTransition`
currently interpolates - needs comparing against whatever
`startHiding`/`startExitingHide` currently do to see whether this is a
small adjustment or a rework.

> **Status (2026-10-08):** open (backlog item 8).

### Lever-arm system (major feature, format-wide) - MVP SHIPPED + corrected + a first drag prototype, see the three Lever-arm entries at the head of this older group

The core of this was built: two authored endpoints (closed = the instance's own base pose, open = a new `leverArm` block's pose) with a continuous 0..1 value driving the motion, wired through `.pbd`/`.pbdbin`/`.pbdasset`, the Java engine, and the Blender addon. A first pass wrongly assumed that value would always be scripted (eased on a timer); corrected to also support an external driver (`PbdRenderer.setArmValue`/`getArmValue` - this IS the `getArmRotationValue()` accessor this backlog item originally asked for, built and no longer pending) once it was clear the actual goal was a mouse drag-and-drop, not a baked animation. A simple (no physics engine) geometric drag prototype was then built against that same seam. See the three top entries for what shipped, what was wrong about the first version, and how each was verified.

Still genuinely open from the original ask below, not yet built:
- Other primitives linked/attached to a lever-arm (parent-like attachment, not just synchronized open state the way `linkGroup` already does for doors).
- SFX on the arm's own motion (today only a keyframe can carry `sound=`).
- A REAL physics-driven drag, as opposed to the simple geometric one that now exists (see the two top entries) - no physics engine exists in this codebase, and integrating one (which library, real constraints vs. the current direct geometric mapping) is still an open, undecided question.
- Whether `server/upload.php` (not present in this sandbox) needs any change for moderation to handle the new block type - unknown from here.

> **Status (2026-10-08):** all of "Still genuinely open" is built and checked except a real physics engine and the server question - section 2 is the line-by-line account, against the original wording quoted there.

### Bend modifier - proper fix

Current bend implementation flagged as still wrong. Requested
approach: match Blender's own classic technique - a loop cut
(subdivision) along the affected axis (e.g. a cylinder), THEN a Simple
Deform (Bend) modifier with angle/axis - rather than whatever
approximation is currently in place. Needs comparing against the
CURRENT bend modifier's own implementation to see how far off it is
from this specific technique.

> **Status (2026-10-08):** open, and a question for you (backlog item 12).

### Web-based visualizer + documentation refresh

Eventually: a web version of the visualizer/viewer, plus a rewrite of
the index and tutorial pages, and a full pass over all existing
documentation to bring it up to date with everything else on this
list once built.

> **Status (2026-10-08):** the documentation refresh was done on 2026-10-07; the web visualizer and the rewrite of the index and tutorial pages are still open (backlog item 20).
