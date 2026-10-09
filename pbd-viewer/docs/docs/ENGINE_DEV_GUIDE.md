# PBD Engine — Developer / Architecture Guide

Java 21 + LWJGL 3.3.4 (OpenGL 4.3) + JOML. Gradle project.

Revised 2026-10-08. This guide is the map of the code and the rules that are easy to break. Its
neighbours: `docs/PBD_FORMAT_SPEC.md` (the format itself), `docs/ROADMAP.md` (what is done, pending and
decided, with the evidence), `docs/STATUS_SUMMARY.md` (the same, one pass), `docs/TEST_PROCEDURE.md` (how
to prove nothing regressed), `docs/ADDON_USER_GUIDE.md` (the Blender side).

## Running it

```
./gradlew run                                    # loads the default scene
./gradlew run --args="path/to/scene.pbd"         # loads a specific .pbd
./gradlew run --args="path/to/scene.pbdbin"      # loads a compiled .pbdbin (see docs/PBD_FORMAT_SPEC.md)
./gradlew run --args="path/to/scene.pbdasset"    # loads a bundled .pbdasset (extracted to a temp dir automatically)
./gradlew run --args="path/to/model.obj"         # classic OBJ viewer
./gradlew run --args="path/to/model.fbx"         # FBX viewer (binary FBX only, see pbd.fbx)
./gradlew convertTileGeometry -Pinput=... -Poutput=...   # tileGeometry.txt -> .pbd batch conversion
```

The format converters are one class, `pbd.PbdConvertMain` (the Blender add-on's "Export as .pbdbin /
.pbdasset" buttons shell out to it, with the classpath printed by the `printRuntimeClasspath` Gradle task):

```
PbdConvertMain bin   <input.pbd> <output.pbdbin>
PbdConvertMain asset <input.pbd> <output.pbdasset> [author] [version] [description]
PbdConvertMain lever <input.pbd|.pbdbin|folder> <output.pbd|.pbdbin|folder>     # keyframe doors -> lever arms
```

`lever` is the migration the project owes before the keyframed doors go away
(`pbd.format.LeverArmMigration`, details under "Lever arm" below). A folder is walked recursively and the
results land at the same relative paths under the output folder; the original is never touched.

## Controls

| Input | What it does |
|---|---|
| WASD + mouse, Space / Shift | Move and look, up / down. Esc closes the window. |
| Left mouse | On a **lever arm** (or anything attached to one): grab it and drag while the button is held; on release the arm's `release=` policy decides (`snap` finishes the swing to the nearer end, `free` stays where it was let go). A locked arm prints `[Lever] '<id>' is locked`. On a **keyframed** door or lid: toggles it (the old behaviour). |
| E | Take the container item under the crosshair (plays `pickup.wav` when one is taken). |
| H | Hide inside the container under the crosshair; press again from inside to come out. |
| G | Shoot what is under the crosshair: carve a crater, let what it cut off fall (plays `doomshotgun.wav` on a hit). |
| `-` / `=` | Detail (LOD) down / up. |
| C | Toggle the Transform-Feedback mesh cache. |
| Up / Down, Left / Right | Time of day, day of year (held). Hold T for a fast multi-day time-lapse. |
| N / B | Next / previous `.pbd` / `.pbdbin` / `.pbdasset` in the same folder. |
| F3 / F5 | Hide or show the statistics overlay. |

Sounds live in `src/main/resources/sounds/` (`SoundPlayer` is built on that folder). It decodes WAV only and
logs a warning - it does not fail - when a file is missing, which is the current state of `pickup.wav` and
`doomshotgun.wav`: both are wired and preloaded, the files themselves still have to be supplied. Every
keyframe `sound=` and every lever arm `openSound` / `closeSound` of the scene (`PbdScene.soundFiles()`) is
preloaded at scene load, so the first click does not stutter.

## Package map

```
pbd.format   - the .pbd TEXT FORMAT and its siblings: PbdParser (read), PbdSerializer (write, pretty or
               minified), PbdScene / PbdInstance (+ Keyframe, LeverArm) / PbdModifier / PbdCurve /
               PbdMeshData (the data model), PrimitiveRegistry, ModifierRegistry, MaterialRegistry,
               PbdMatParser (.pbdmat), PbdBinFormat (.pbdbin = "PBDB" + version byte + gzip of the
               minified text), PbdAssetFormat (.pbdasset = zip), LeverArmMigration (keyframe door ->
               lever arm). No LWJGL dependency - pure data, runnable without a GPU.
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
               light move?), ChannelTracker (named channels that accumulate over time).
pbd.voxel    - destruction (the G key): VoxelOctree, PrimitiveVoxelizer, VoxelMeshBuilder, VoxelSolid +
               LongIntMap (queries on octree-aligned cubes), VoxelCrater (the hole), VoxelConnectivity
               (face-connected pieces), VoxelDebris (what stays, what falls), RigidDebris (a falling
               body), DestructionWorld (the whole chain behind one `destroyAt`). No GL either.
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
               pure, so the frame loop in Main only feeds it the doors' state).
pbd.net      - PbdFetcher: GET-only download of a .pbd / .pbdmat / .pbdbin with a local cache and a
               fallback URL; redirects are deliberately not followed.
pbd.audio    - SoundPlayer: javax.sound.sampled, WAV only, preloaded per scene.
pbd.sky      - SolarCalculator (sun position from latitude / day / hour, for the skydome and the lighting).
pbd.app      - Main: the runnable entry point, wiring everything above into one interactive viewer, and
               the static modding facade (see "Runtime facade").
```

Two rules the package map is built on:

- **Logic that can be pure is pure, and the GL class is its thin adapter.** `PbdRenderer` owns buffers,
  shaders and draw calls; the rules (lever-arm state and drag, light tracking, the destruction chain) live in
  classes with no `org.lwjgl` import. That is the only reason they have regression suites at all - nothing
  here can open an OpenGL window. Only ten files import LWJGL (Main, ClassicMeshRenderer, GlWindow,
  MaterialTextureArray, PbdMeshCache, PbdRenderer, ShaderProgram, SkydomeRenderer, TextRenderer,
  TextureLoader); a new rule that does not need GL should not become the eleventh.
- **Nothing is cached across a scene change or a hot-loaded asset.** `PbdRenderer.upload` builds a fresh
  `LeverArmSystem`, a fresh destruction world, fresh light tracking; assets are loaded on the fly, so a
  cache keyed on "the scene" would be stale the moment one is replaced. If you need to cache, key it on the
  data you read, not on the scene.

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
5. Decide whether destruction must see it. Today only **taper** is applied while voxelizing a primitive;
   bend, twist, curve and shear are not, so G targets and carves the unmodified shape (ROADMAP backlog item
   12 has the maths for bend and the open question).

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
   hierarchy rebuild does not undo it).
3. Carve a **solid, irregular crater** (`VoxelCrater`): 28 lobes with their own reach, blended, digging
   deeper along the shot than sideways, with a jittered rim. Deterministic per impact. A radius of 30 voxels
   of 5 mm (`CRATER_RADIUS_VOXELS`, `VOXEL_WORLD_SIZE`) cuts a hole about 0.30 world units across. Cubes
   wholly inside are dropped whole, wholly outside kept whole, only the cubes on the rim are split - the
   octree is never expanded to unit voxels (that was the out-of-memory crash of the old crater).
4. Split what is left into face-connected pieces (`VoxelConnectivity`, working on octree-aligned cubes
   through `VoxelSolid`, never per voxel) and decide what stays (`VoxelDebris`): an object standing on the
   floor keeps every piece that still touches its lowest layer; anything else (a door, a shelf) keeps its
   largest piece. The rest become rigid bodies (`RigidDebris`: gravity, floor and static colliders,
   friction, a little bounce, sleep), at most 6 per hit and at least 400 voxels each - smaller crumbs are
   dropped - with at most 24 live pieces in the scene.
5. Each frame `updateDebris(dt)` steps the pieces; `drawDestroyed` draws remains and pieces; further hits
   keep carving remains or a piece lying on the floor; `clearDestroyed()` runs on a scene switch.

Landing surfaces are the floor, the remains, and the boxes of nearby cubes and cylinders. **To make a hit
stronger or weaker** change `CRATER_RADIUS_VOXELS`; the cost grows fast (measured at radius 30: 25-60 ms to
carve plus about 50 ms to mesh; radius 60 costs about three times that), which is why it is a constant and
not a per-shot parameter yet.

**Known limits** (ROADMAP section 4): "what stays attached" is a heuristic - the format has no support or
hinge data, so a door shot through the middle sheds its smaller half, not the half the hinges do not hold;
pieces do not collide with each other; a piece does not inherit an animated instance's motion; spheres,
cones, tori and meshes are not landing surfaces; bend / twist / curve / shear are not applied by the
voxelizer (taper is); a panel narrower than the crater (a 0.45-wide door) gets a hole, not a cut.

**Verified by** `RegressionDestructionWorld` and the four suites under it (`RegressionVoxelCrater`,
`RegressionVoxelConnectivity`, `RegressionVoxelMesh`, `RegressionRigidDebris`, plus
`RegressionCraterOOM` under `-Xmx512m`). `tools/regression/VoxelFallDemo.java` renders the sequence with a
small software rasteriser (`VoxelFallDemo OUT_DIR post|plank|door|cube|crate|post2 [wide|close]`); the
pictures are in `docs/renders/g_*.png`. **Not verified here:** the OpenGL side (mesh upload, textured voxel
faces, the native crash `0xC0000374` reported with a textured primitive).

### The building blocks

`VoxelOctree`: sparse occupancy octree, 2^maxDepth grid per axis. The "optimization" value is
`insertFilledBox` collapsing a large uniform region (a solid cube's whole interior, say) to a single node
rather than one leaf per voxel - verified with a real test: a fully-filled 64^3 grid (262144 voxels)
collapses to exactly 1 region. `collectFilledRegions()` returns the compressed form; use
`collectFilledUnitVoxels()` only when individually-addressable 1x1x1 pieces are actually needed, since it
deliberately gives up the compression.

`PrimitiveVoxelizer`: converts one PbdInstance into a VoxelOctree. It returns null for `indestructible=true`,
for a type it does not know, for an instance with no geometry (`ref`, `group`, `light`) and for a mesh that
encloses no volume (below). Zero-volume primitives (plane, disc) get extruded to a minimum
thickness (a fraction of their largest in-plane dimension) before voxelizing, rather than mapping to zero
voxels. Shape formulas are standard parametric definitions in the canonical -0.5..0.5 local space every
primitive already uses - verified quantitatively (`RegressionPrimitives`: cylinder / cube ratio pi/4,
cone / cylinder 1/3, sphere pi/6, a torus's centre voxel hollow). Not cross-checked line by line against the
tessellation shader's own exact curves, so a small boundary mismatch (rather than a wrong ratio) is possible.

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

## Runtime facade (what a mod or an AI script can call)

Offline (no window): `PbdEngine` / `PbdEngine.SceneHandle` - load any of the four forms, read and edit
instances by name, save pretty / minified / `.pbdbin`. See "Developer facade" below. `SceneHandle` has no
typed lever-arm setter; an arm is authored in the file or through `raw()`.

In the running game (needs the renderer, so it is GL-bound):

- `PbdRenderer` public methods: `setOpen`, `isInstanceOpen / FullyOpen / FullyClosed`, `isContainerOpen`,
  `areAllDoorsClosed`, the arm API above, `instanceWorldCenter / Size / Matrix / Rotation`,
  `findDestructibleAlongRay`, `findMetadataCubeAlongRay`, `destroyAt`, `hasDestroyed`, `clearDestroyed`,
  `uploadLights`.
- `Main` statics, so code that does not hold the renderer can still reach the live scene:
  `getContainerItems`, `availableVolume(containerName)`, `canHide(containerName, playerHitboxVolume)`,
  `removeContainerItem(sourceFile)`, and the arm trio `getArmValue(id)` (NaN when there is no scene or no
  such arm), `isArmLocked(id)`, `setArmLocked(id, locked)` (false when there is no scene). The "no scene
  loaded" contract of the arm trio is tested; the live path is not (it needs a renderer).

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
`src/test`): 20 Java suites plus the Blender add-on check, all run by one command, and a mutation runner
that proves the suites fail when the code under them is broken.

```
JOML_CLASSES=/path/to/compiled/joml LWJGL_DIR=/path/to/lwjgl/jars sh tools/regression/run.sh
```

`docs/TEST_PROCEDURE.md` is the procedure (what each suite guards, how to read a failure, the checklist
before a release, how to run the mutants). Everything in `pbd.format`, `pbd.pz`, `pbd.fbx`, `pbd.voxel` and
the GL-free classes of `pbd.render` runs without a GPU or a display. `pbd.render`'s GL classes,
`pbd.classicmesh` and `pbd.app` need a real (or Xvfb-virtualized) OpenGL context; there is no headless
mock for those, which is exactly why the logic was pulled out of them. What the suites cannot reach is
listed in `docs/STATUS_SUMMARY.md` as **pending** or **done** (as opposed to **done-and-tested**).

Three habits that paid for themselves, and are worth keeping:

- **Assert the value that came back, not that the call did not throw.** Several bugs (the importer dropping
  `leverArm`, `pbd_ref` dropping fields, a re-export writing an empty `source=`) imported without error and
  were only caught by comparing the reimported value with the exported one.
- **A new field or a new format feature gets a reflection guard, not a checklist.** See "Rules that are easy
  to break" above.
- **Break it on purpose.** `tools/regression/mutate.py` applies one exact replacement at a time to a copy of
  the code (or of the docs) and runs the suite meant to guard it; a mutant that survives is a gap in the
  test. 11 spec files, 330 mutants, all killed at the time of writing; the Java, add-on (on the fake `bpy`, or on real Blender
  when the spec says `REAL = True`), doc and script kinds are described in the script's header.

## Lessons recorded from past bugs

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
- `ClassicMeshRenderer` (the "mesh" instance / FBX / container-item render path) has no texture sampling at
  all - only ever a flat baseColor, which used to stay at its own generic default regardless of the
  instance's mat=, reported as "only black, dark". Now at least reads that material's own color= and uses
  it. Real texture support here (a new shader + UV upload path, matching what the main tessellation
  pipeline's MaterialTextureArray already does) is a separate, larger undertaking, not started. (Voxel
  remains and debris, which are drawn by this same path, get the texture's look another way: each voxel
  face's colour is sampled from the texture per vertex - `RegressionPerVertexColor`.)
- `SoundPlayer.play()` used to decode (`AudioSystem.getAudioInputStream` + `Clip.open`, real disk / CPU
  work) the FIRST time each distinct sound was needed - synchronously, on the same thread driving animation
  and rendering, right at the moment of the click that needed it. That frame ran long; the NEXT frame's
  delta time (measured against the wall clock, which kept moving during the decode) came out oversized,
  making the door's swing visibly jump ahead right as the newly-loaded sound finally started - reported as
  sound "not quite locked to the first frame." `SoundPlayer.preload()`, called once per distinct sound a
  scene's keyframes and lever arms reference right after construction (Main.java), moves that cost to scene
  load instead.
- `removeNearestToRay` and the E-key handling in Main.java print per-item diagnostics (world position,
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
