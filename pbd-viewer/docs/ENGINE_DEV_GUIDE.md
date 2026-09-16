# PBD Engine — Developer / Architecture Guide

Java 21 + LWJGL 3.3.4 (OpenGL 4.3) + JOML. Gradle project.

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

Controls: WASD + mouse to move/look, Space/Shift up/down, `-`/`=` for
LOD, `C` toggles the Transform-Feedback mesh cache, arrow keys for
time-of-day/day-of-year, `N`/`B` cycle sibling `.pbd` files in the same
folder, left-click toggles the nearest keyframed object (door/lid),
`E` removes the container item under the crosshair.

## Package map

```
pbd.format   - the .pbd TEXT FORMAT: PbdParser (read), PbdSerializer
               (write, pretty or minified), PbdScene/PbdInstance/
               PbdModifier data model, PrimitiveRegistry,
               ModifierRegistry, MaterialRegistry. No LWJGL dependency -
               pure data, fully unit-testable without a GPU.
pbd          - PbdEngine: a friendly facade over pbd.format for other
               code that just wants to load/inspect/save scenes without
               learning every class above individually.
pbd.render   - the GPU side: PbdRenderer (upload + tessellation-shader
               draw), PbdMeshCache (Transform Feedback bake-to-mesh
               cache), MaterialTextureArray, SkydomeRenderer, FlyCamera,
               GlWindow, TextRenderer, BitmapFont.
pbd.classicmesh - a second, simpler render path for pre-triangulated
               meshes (ObjMesh: interleaved position+normal, plain
               index buffer) - used by the .obj viewer, the .fbx
               viewer, and container-content item rendering. No
               tessellation, no LOD - it's the "just draw these
               triangles" path.
pbd.fbx      - binary FBX (Kaydara format, version <7500) node-tree
               reader (FbxBinaryReader) and geometry extractor
               (FbxGeometryExtractor) producing an ObjMesh. See that
               package's own doc comments for exactly what's handled
               (triangles only, one Geometry per file, ByPolygonVertex/
               Direct normals) versus not yet (n-gons, multi-mesh
               files, the full FBX transform-pivot stack).
pbd.pz       - Project Zomboid interop: TileGeometryParser/Serializer/
               Converter (tileGeometry.txt <-> .pbd, both directions),
               PbdPaths (every project-relative resource path in one
               place), PzGamePaths (the actual game install path, from
               .env), MaterialLookup (drop-item -> material name table),
               BinPacker (shelf-packing, single or multi-container),
               ContainerContents (random FBX selection + packing +
               simplified falling physics, deterministically seeded).
pbd.sky      - SolarCalculator (sun position from lat/day/hour, for the
               skydome and lighting).
pbd.app      - Main: the actual runnable entry point, wiring everything
               above into one interactive viewer/converter.
```

## The .pbd format itself

See `docs/PBD_FORMAT_SPEC.md` for the full syntax reference. In short:
a text format, one block per instance (`cube name { pos=... scale=... }`),
free-form fields for anything not universal (metadata, loop,
containerTrigger, LOD, ...), optional keyframe blocks for animation,
optional modifier blocks (bend/shear/taper), and scene-level metadata
(name/kind/author/origin) at the top of the file.

## Adding a new primitive type

1. Add tessellation math to `pbd.tese` (the GPU evaluates the actual
   surface there) and LOD/bend-detection logic to `pbd.tesc` if it needs
   special handling.
2. Register the type + its canonical dimensions in `PrimitiveRegistry`.
3. Add patch-count logic to `PatchExpander` if it's not the default
   1-patch-per-instance case.
4. Blender side: add it to `add_primitive.py`'s enum and creation logic,
   and to the UI panel if it needs type-specific fields.

## Adding a new modifier

1. Register it in `ModifierRegistry` (name + expected params).
2. Add the GPU-side math wherever the modifier actually applies
   (typically `pbd.tese` for a per-vertex deformation).
3. `PbdModifier` already carries arbitrary key=value params generically -
   no parser change needed for a new modifier's own fields.
4. Blender preview: native modifier if one matches exactly (see bend's
   `SimpleDeform` in `properties.py`), otherwise direct mesh-data editing
   (see smooth/shear/taper in `mesh_preview.py`) - Blender's own
   modifiers rarely match this project's exact math, so don't assume one
   does without checking by hand against known values first.

## Known rough edges (worth knowing before you hit them)

- `pbd.fbx` only handles the FBX variants actually seen so far (see that
  package's own doc comments) - a file with n-gon polygons, multiple
  Geometry nodes, or an unusual normal/UV reference mode will throw a
  clear error rather than silently producing wrong geometry, but it
  WILL need new code, not just a config change.
- `ContainerContents`'s "physics" is a straight-down fall to the nearest
  support, not a real rigid-body simulation (no rotation, no sideways
  sliding, no simultaneous-fall collision between two items).
- `BinPacker` is a shelf-packer (left-to-right, wrap row, wrap layer),
  not an optimizer - it won't find a tighter packing a human could by
  rotating items or nesting irregular shapes. It DOES correctly shrink
  an item that fits nowhere at its real size (`scaleFactor`, applied as
  an actual `.scale()` in Main.java's item render transform - a shrunk
  item that still visually overflowed its box was a real, now-fixed bug:
  the packer computed the position assuming the shrink but nothing
  applied it to the rendered mesh).
- The multi-door/multi-box container grouping (`Main.java`'s
  `containerGroups`) groups by EXACT trigger-set equality - two boxes
  sharing only SOME of their doors won't merge into one group. No real
  scene has needed full connected-components graph grouping yet, but
  it's the corner actually being cut if one ever does.
- Container contents key off `PbdRenderer.isInstanceOpen` (toggled
  open, regardless of animation progress) for SHOWING on open, but
  `isInstanceFullyClosed` (toggled closed AND the swing animation has
  actually finished) for CLEARING once every linked door is closed
  again - deliberately asymmetric: showing immediately on click feels
  responsive, but clearing immediately on click would make items vanish
  while still visible through a still-open gap instead of disappearing
  behind a closed door.
- `_find_or_create_sound_entry` (Blender add-on) used to call
  `CollectionProperty.add()` directly from inside `draw()`, which
  Blender explicitly forbids ("Writing to ID classes in this context is
  not allowed") - reproducible every time a keyframe without a sound
  entry yet was drawn, not a version-specific or stale-install issue.
  Split into a read-only `_find_sound_entry` (safe in draw) plus
  `OBJECT_OT_pbd_add_keyframe_sound`/`OBJECT_OT_pbd_remove_keyframe_sound`
  operators that do the actual write from `execute()`, where it's
  allowed. Worth remembering for anything else that might want to
  lazily create data while drawing a panel: it can't, full stop - the
  write has to happen in an operator.
- Cube EDGE displacement (turning a cube into a non-axis-aligned
  hexahedron by moving one or more edges) doesn't exist - `faces=` (see
  the format spec) removes whole faces, which is a much smaller change
  than free-form corner positions would be. The tessellation shader's
  `evalCubeFaceFlat` would need generalizing from a fixed ±0.5 cube to
  bilinear interpolation between (possibly displaced) corner positions
  per face - a real chunk of work, not yet started.
- `ContainerContents.loadRandom` now searches `pbd.pz.PbdPaths.FBX_DIR`
  RECURSIVELY (`Files.walk`, not `Files.list`) and picks 10-50 items
  (was a flat, non-recursive listing picking 2-5, written back when the
  folder held one or two loose test files) - a real asset library
  organized into subfolders by category found zero files under the old
  listing, so every container came up empty. If you see that symptom
  again, check whether something is filtering `Files.walk`'s results
  more than intended, not whether items exist at all.
- `BinPacker.packMultiple`'s "doesn't fit anywhere, shrink into the
  roomiest container" fallback used to compute the shrink against that
  container's FULL height, not whatever room was actually left once
  other items already occupied part of it - correct for the first item
  to hit that fallback, increasingly wrong for every one after it in the
  same container. Found by testing with a genuinely large, varied item
  count (10-50, see above) rather than the 2-5 the original bug report
  never exercised heavily enough to trigger it. Now re-validates against
  the actual remaining room after the initial shrink+placement, with an
  absolute-last-resort clamp (flush against the ceiling, near-zero
  height) for a container so oversubscribed that no amount of per-item
  shrinking can rescue it - an intentionally ugly result for a scenario
  that's already a real design problem (that container is too small for
  what's supposed to go in it), not something more packing cleverness
  fixes.
- Rotation composition is `rotateZ().rotateY().rotateX()` in that order
  (matching Blender's intrinsic XYZ Euler) - this bit a real bug once
  when composed in the naive left-to-right order instead. If you touch
  rotation math, check it against a known multi-axis example by hand,
  not just single-axis cases (which can't distinguish the two orders
  at all).

## Testing

`pbd.format`, `pbd.pz`, and `pbd.fbx` have zero LWJGL dependency and are
fully testable without a GPU or display - `./gradlew test` runs the
JUnit suite. `pbd.render`/`pbd.classicmesh`/`pbd.app` need a real (or
Xvfb-virtualized) OpenGL context; there's no headless mock for those.

- The Blender add-on's compiled-format buttons (.pbdbin/.pbdasset/
  tileGeometry) used to build a classpath by hand (just
  `build/classes/java/main`, or a packaged jar) - correct for the
  project's OWN classes, missing every dependency jar (JOML, LWJGL)
  entirely. Worked fine as long as nothing reachable from
  PbdConvertMain's main() touched a dependency class; threw
  `NoClassDefFoundError`/`ClassNotFoundException` on `org.joml.Vector3fc`
  the moment it did, which for a class that serializes 3D transforms is
  essentially always - confirmed via a real Blender error report,
  fixed by adding a `printRuntimeClasspath` Gradle task (delegates to
  `sourceSets.main.runtimeClasspath`, the same mechanism `run` and
  `convertTileGeometry` already used correctly) and having the add-on
  shell out to `gradlew printRuntimeClasspath` instead of guessing at
  Gradle's output layout.

- `ContainerContents.loadRandom` used to draw its 10-50 target count
  ONCE from the shuffled file pool and accept however many of THOSE
  happened to extract successfully - an unlucky draw landing
  disproportionately on FBX variants `pbd.fbx`'s simplified reader can't
  handle yet (see its own rough-edges notes) could leave a container
  with far fewer items than intended, occasionally zero, with the rest
  of a large real asset pool never tried. Now keeps drawing additional
  untried candidates from the remaining pool until the target is met or
  the whole corpus has had a chance - verified against a real exported
  door+storage-cube scene end to end (parse -> containerGroups -> load
  -> E-key removal -> physics settle), not just in isolation.
- `BinPacker`'s `scaleFactor` is structurally bounded to <= 0.95 at
  every assignment site (verified by inspection of all of them, not
  just the common case) - there is no code path that enlarges an item
  beyond its real extracted size, only shrinks one that doesn't fit.

- Container item positioning (both the render transform and
  removeNearestToRay's hit-test) used to add an item's LOCAL offset
  straight onto its container's world CENTER with no rotation applied -
  correct only for an axis-aligned storage cube. `PbdRenderer.
  instanceWorldRotation` plus `ContainerContents.itemWorldPosition`
  (shared by both call sites now, so they can't drift apart) fixed this -
  verified against a known 90-degree rotation's expected transform, not
  just by inspection.
- "mesh" instances can now carry additional LOD variants
  (`vertexDataLod<N>=`, see PBD_FORMAT_SPEC.md) that the engine actually
  swaps between on the existing global -/+ LOD keys (`desiredMeshLodTier`/
  `rebuildMeshRenderersForLod` in Main.java) - separate from and
  alongside the tessellation-detail LOD system those same keys already
  drove, since "mesh" geometry isn't tessellated at all. A mesh with N
  captured variants steps through them one at a time as global detail
  drops from maximum; one with more variants than the engine's LOD range
  has steps for never reaches its very lowest ones - a known limitation
  of this simple a mapping.
- `ClassicMeshRenderer` (the "mesh" instance / FBX / container-item
  render path) has no texture sampling at all - only ever a flat
  baseColor, which used to stay at its own generic default regardless of
  the instance's mat=, reported as "only black, dark". Now at least
  reads that material's own color= and uses it. Real texture support
  here (a new shader + UV upload path, matching what the main
  tessellation pipeline's MaterialTextureArray already does) is a
  separate, larger undertaking, not started.

- `SoundPlayer.play()` used to decode (`AudioSystem.getAudioInputStream`
  + `Clip.open`, real disk/CPU work) the FIRST time each distinct sound
  was needed - synchronously, on the same thread driving animation and
  rendering, right at the moment of the click that needed it. That
  frame ran long; the NEXT frame's delta time (measured against the
  wall clock, which kept moving during the decode) came out oversized,
  making the door's swing visibly jump ahead right as the newly-loaded
  sound finally started - reported as sound "not quite locked to the
  first frame." `SoundPlayer.preload()`, called once per distinct sound
  a scene's keyframes reference right after construction (Main.java),
  moves that cost to scene load instead.

- `removeNearestToRay` and the E-key handling in Main.java now print
  per-item diagnostics (world position, hit-sphere radius, distance
  from the ray, hit/miss) on every E press, plus how many container
  groups are even open to check - added specifically because repeated
  reports that E finds nothing had exhausted what code-level
  inspection alone could resolve; every isolated and full-chain test
  built to reproduce it passed, so the next step is seeing the real
  numbers from an actual run rather than a further theory.
- Found while adding pbd_ref's mat= override: `_create_object_for_instance`
  had TWO checks comparing against the string 'ref' where the real
  parsed prim_type is 'pbd_ref' ('ref' is only ever obj.pbd.pbd_type's
  OWN value) - meaning source=/fallback= (and now mat=) never actually
  read back in for any REAL file, only for the in-memory 'group' shape
  the Java parser produces after resolving one, which no serializer
  ever writes to disk. The object still got created correctly (a
  separate, already-fixed check), so import didn't error - it just
  silently produced a ref object with empty source=, meaning a
  re-export of a reimported file with a ref would have written out an
  EMPTY source= and silently discarded the reference. Only caught by
  asserting the reimported VALUE matches what was exported, not just
  that import succeeds without error - worth remembering as a testing
  gap for anything else in this reader.

## Voxel system (`pbd.voxel`)

`VoxelOctree`: sparse occupancy octree, 2^maxDepth grid per axis. The
"optimization" value is `insertFilledBox` collapsing a large uniform
region (a solid cube's whole interior, say) to a single node rather
than needing one leaf per voxel - verified with a real test: a fully-
filled 64^3 grid (262144 voxels) collapses to exactly 1 region.
`collectFilledRegions()` returns the compressed form; use
`collectFilledUnitVoxels()` only when individually-addressable 1x1x1
pieces are actually needed (a destruction system, eventually), since
it deliberately gives up the compression to get there. 23/23 tests
covering insert/remove/query, out-of-bounds safety, box-fill
compression (including adjacent fills re-collapsing), and sparse
scattered-voxel correctness.

`PrimitiveVoxelizer`: converts one PbdInstance into a VoxelOctree.
`indestructible=true` and `mesh`-type instances both return null (never
voxelized - mesh deliberately deferred, see the class's own doc for
why). Zero-volume primitives (plane, disc) get extruded to a minimum
thickness (a fraction of their largest in-plane dimension) before
voxelizing, rather than mapping to zero voxels. Shape formulas are
standard parametric definitions in the canonical -0.5..0.5 local space
every primitive already uses - verified quantitatively, not just "does
it run": cylinder/cube voxel-count ratio landed at 0.81 against a
theoretical pi/4=0.785, cone/cylinder at 0.326 against a theoretical
1/3, a torus's center voxel confirmed hollow. Not cross-checked line by
line against the tessellation shader's own exact curves, so a small
boundary mismatch (rather than a wrong ratio) is possible.

**Not built yet**: rendering voxelized geometry back into the scene
(each octree region as a scaled cube instance, presumably, with correct
per-face UVs), the destroy-button raycast trigger, and physics for
disjoint fallen pieces.

## Voxel rendering + destroy trigger

`VoxelMeshBuilder` turns a VoxelOctree's regions into a PbdMeshData
directly (one box per region, no per-voxel duplication for a merged
region) - it renders through the EXISTING "mesh" instance pipeline
(ClassicMeshRenderer), no new shader work needed. UV tiles by voxel
COUNT, not world size (verified: a 4-voxel merged region shows UV
0..4, not 0..2), so a compressed region looks like the same density of
small voxels it represents rather than one stretched texture.

A real placement bug was caught and fixed here: the original
gridOriginLocal formula was missing a `-scale/2` term, producing a
world-space offset exactly `scale/2` too high on every axis - caught
by a real test comparing the voxelized mesh's world bounding box
against the original primitive's own, not by inspection. Also verified
with rotation (a 90-degree Y rotation correctly swaps the X/Z world
extents), using the same Z,Y,X rotation order every other transform in
this codebase already uses.

'G' (Main.java) raycasts for the nearest non-indestructible, non-
metadata, non-ref instance and voxelizes it on the spot - the original
gets hidden (PbdRenderer.hideInstance, zeroing its transform rather
than trying to remove one entry from the batched tessellation draw)
and its voxel mesh renders in its place, at the CORRECT world position/
rotation (position + Z,Y,X rotation + gridOriginLocal - NOT the
original's full world matrix, which would double-apply scale since the
mesh is already in world-scale units).

**Not built yet**: physics for disjoint voxel pieces (falling apart
after a hit) - this trigger currently swaps the WHOLE primitive to its
voxel form in place, not fragmenting/scattering it.

## Octree-adaptive rasterization (not just octree storage)

The curved shapes (cylinder, cone, sphere, torus) used to test every
individual voxel in their bounding grid one at a time - correct, but
not actually using the octree's own hierarchical structure for
anything beyond compressing the FINAL result. Rewritten to recursively
classify whole REGIONS first (RegionClassifier + rasterizeAdaptive in
PrimitiveVoxelizer): a region provably entirely inside the shape gets
one insertFilledBox call and stops recursing; entirely outside gets
skipped entirely; only a region actually straddling the curved
boundary recurses into its 8 children, down to individual leaf voxels
only right at the boundary itself.

Verified two ways, not just "it runs": (1) exact equivalence - a
temporary test compared the new adaptive result against the old
brute-force one, voxel-set-for-voxel-set, across cylinder/cone/sphere/
torus at three resolutions (16/32/64 per axis) plus a non-cubic grid
case, all identical, zero discrepancies; (2) real cost reduction - at
a 128^3 grid, the adaptive cylinder used 235,721 classify() calls
against 2,097,152 brute-force point tests (11.2%, roughly 9x fewer) -
a cylinder's own boundary shell is a real fraction of its volume at
any finite resolution, so this ratio is shape-accurate, not an
inflated best case.

Region containment for each shape uses the standard closest/farthest-
point-in-an-axis-aligned-box technique (independently per axis, the
box's own corners/clamped-origin), the same tool sphere/box
intersection tests use generally - torus additionally nests this same
technique twice (once for radial distance from the Y axis, once for
how far that radial distance sits from the ring's own major radius)
rather than needing shape-specific machinery.

## Light rendering

`PbdRenderer.uploadLights` collects every `light`-type instance into a
64-byte-stride SSBO (binding 5 - materials own 4, see that method's own
alignment comment for the exact field packing), read by BOTH the main
tessellation fragment shader (`pbd.frag`) and the cached path
(`cached.frag`) - point/spot lights need to look the same regardless of
which of the two is currently active (the C key toggles between them),
so both shaders carry an identical copy of the same accumulation loop
(distance falloff to zero at the light's own range, plus an angular
falloff for a spot's cone edge) rather than only one of the two paths
supporting lights at all.

Verified as thoroughly as possible without an actual GPU in this
environment: the Java-side struct packing was cross-checked field by
field against the GLSL struct it's read as (order, type, and byte
offset all confirmed to agree), and the uniform locations used by the
new `lightCount` uniform (7 in the main pipeline, 5 in the cached one)
were confirmed free by inventorying every OTHER uniform location
already in use across every shader stage sharing that same program -
not run and visually confirmed on real hardware, which remains the one
verification step this couldn't cover here.

## Developer facade (PbdEngine.SceneHandle)

Significantly enriched this pass - was previously limited to position
get/set, type (read-only), and scene-level metadata, meaning almost
any real modding task had to drop through raw() to the underlying
PbdScene/PbdInstance. Now also covers: rotation, scale, material,
category, parent (by name, not raw index), indestructible/hardness/
resistance, every light field (mode/color/intensity/range/spot angle),
plus addInstance/removeInstance/hasInstance for actually building or
tearing down a scene rather than only editing an existing one.
raw() remains available for anything not covered.

Verified with a real, standalone test exercising all 30 behaviors
(round trip of every new getter/setter, every documented exception -
duplicate name, unknown type, unknown parent, invalid lightMode - and
critically a full save-to-text-and-reload cycle confirming these
aren't just in-memory Java state but actually reach the real parser/
serializer).

## A note on this session's own build

A real, full compile against the actual LWJGL 3.3.3 jars (not the
hand-written stub used for most of this project's own iteration, which
only catches missing-type errors involving pbd's own classes) surfaced
two genuine missing imports - java.io.IOException in Main.java and
java.util.ArrayList in PbdRenderer.java - that had been silently
breaking the full build since the turns that introduced them. Worth
remembering: the stub compile used throughout this project is good for
catching most mistakes early, but a missing JDK-standard-library import
specifically will NOT surface through it if nothing else in the same
file happens to need that stub - only a real compile against the
genuine dependency catches that category of error.

## A real crash this "light" feature shipped with

`PatchExpander.partsFor` throws for any type it doesn't have a case
for - by design, "a wrong guess is worse than a loud failure." The
"mesh" case's own comment already warned, in so many words, that a new
primitive type needs a case added here even when its geometry comes
from somewhere else entirely - "light" was added without one anyway,
so any scene containing a light instance crashed immediately on load,
before a single frame rendered. Fixed (light -> zero patches, same as
"group"/"mesh"), reproduced the exact reported stack trace against a
real scene containing a light instance first to confirm the crash was
real, then confirmed the fix resolves it the same way, rather than
reasoning about it from the code alone.

Also found and fixed while looking for every other place a
zero-geometry type needs the same treatment: `findDestructibleAlongRay`
(the G-key raycast) excludes "ref"/"group" from being hit at all, since
neither has visible geometry to aim at - "light" was missing from that
same exclusion, so a light instance could be "aimed at" and would
report "can't be voxelized" rather than the ray passing through it the
way it does for a ref.

## lightEnabled

A light now has a runtime on/off separate from its intensity (a light
switch shouldn't need to remember and restore the authored brightness
by hand) - `PbdEngine.SceneHandle.setLightEnabled`/`toggleLight`/
`isLightEnabled`, an `lightEnabled=false` line only written when off
(on is the default, so the common case stays terse), and
`PbdRenderer.uploadLights` (now public) skips a disabled light
entirely when building the GPU buffer. uploadLights being callable on
its own - not just as part of the full upload() sequence - matters
here specifically: toggling a light shouldn't need re-uploading every
instance/patch/material in the whole scene along with it.
