# pbd-benchmark

Benchmark and reference engine for the PBD (Primitive Based Description)
format: parametric primitives described as text, reconstructed
procedurally on the GPU (tessellation shaders) without ever transferring a
full vertex buffer.

## What exists so far

- `src/main/java/pbd/format/` - `.pbd` parser -> `PbdScene`: data
  structures (`PbdInstance`, `PbdModifier`, `PbdCurve`), open type
  registries (`PrimitiveRegistry`, `ModifierRegistry`, `MaterialRegistry`),
  hierarchy resolution (topological sort, cycle detection). Supports `#`
  end-of-line comments.
- `src/main/java/pbd/render/` - logic consuming a `PbdScene`:
  - `HierarchyResolver`: computes `Matrix4f[] worldTransforms` in a single
    sequential pass (parent always resolved before child).
  - `PatchExpander` / `GpuPatch`: splits each logical instance into one or
    more GPU patches (1 for plane/sphere, 2 for cone, 3 for cylinder, 6
    for cube) since these shapes don't all share a continuous (u,v)
    parameterization.
  - `GlWindow`: window + GLFW/OpenGL 4.3 core context.
  - `ShaderProgram`: compiles and links a shader pipeline (with or without
    tessellation - two constructors), reused by all three renderers below.
  - `PbdRenderer`: builds the 5 SSBOs (instances/patches/worldTransforms/
    modifiers/materials) from a scene and issues the `GL_PATCHES` draw call.
    Also computes `lastTriangleCount` every frame - a CPU-side estimate
    (not a GPU measurement) using the same distance-based LOD formula as
    `pbd.tesc` and a per-patch triangle formula confirmed exact against a
    real GPU query (see below for why it's computed rather than queried).
  - `MaterialCatalog`: visual properties (color, shininess, specular
    strength) for the `wood`/`metal`/`plastic` test materials; any other
    material name falls back to a neutral grey default rather than failing.
  - `FlyCamera`: free-flying camera (see controls below).
  - `BitmapFont` / `TextRenderer`: an in-memory 5x7 bitmap font (no
    external font file dependency) plus on-screen text rendering for the
    stats overlay, a shader program kept separate from the PBD pipeline.
- `src/main/java/pbd/classicmesh/` - the comparison baseline:
  - `ObjParser` / `ObjMesh`: minimal Wavefront OBJ reader (positions,
    normals, triangulated n-gons, vertex deduplication by (position,
    normal) pair) - not a general-purpose importer, just enough to load a
    reference mesh for the benchmark.
  - `ClassicMeshRenderer`: traditional VBO/IBO rendering path, real vertex
    attributes uploaded once, no tessellation, no procedural generation,
    one fixed material for the whole mesh.
- `src/main/java/pbd/app/Main.java` - the viewer: loads a `.pbd` scene by
  default, or a classic `.obj` mesh if the given file has that extension
  (routes to `ClassicMeshRenderer` instead of `PbdRenderer`, specifically
  so the two paths' stats can be compared side by side). Free-flying
  camera, on-the-fly LOD switching (PBD path only), on-screen stats overlay.

## Controls (Main.java)

| Key | Action |
|---|---|
| `W` `A` `S` `D` | Horizontal movement (independent of look direction) |
| `Space` | Move up |
| `Left Shift` | Move down |
| Mouse | Look around - yaw/pitch, no roll |
| `-` / `=` | Step LOD down / up (PBD path only; discrete steps, not continuous) |
| `Escape` | Quit |

LOD steps only change uniforms (`lodMinLevel`/`lodMaxLevel` on
`PbdRenderer`, read by the TCS) - no GPU buffer is re-uploaded when the
step changes. The stats overlay shows that one-time upload total to make
the point concrete rather than just asserted; the classic OBJ path shows
its own (fixed, always-real) upload size for direct comparison.

- `src/main/resources/shaders/pbd/` - the full tessellation pipeline
  (`pbd.vert`/`pbd.tesc`/`pbd.tese`/`pbd.frag`): dynamic LOD by camera
  distance in the TCS, procedural generation of the 6 primitives (plane,
  sphere, cylinder, cone, cube, disc) + their parts (walls/caps/faces) in
  the TES, a material lookup + Blinn-Phong shading in the FS so wood/metal/
  plastic read as visually distinct, `bend` and `taper` modifiers applied,
  cube edge/corner rounding (a `smooth` param, Minkowski box+sphere
  construction - degenerates exactly to a flat cube at 0 and exactly to a
  sphere at its max of 0.5, both checked before trusting it). `twist` and
  `curve` (which needs a Frenet frame) remain no-ops in the TES switch.
- `src/main/resources/shaders/classic/` - the plain (non-tessellated)
  `classic.vert`/`classic.frag` pair for the OBJ comparison path, using the
  same Blinn-Phong lighting model as `pbd.frag` for a fair visual comparison.
- `src/main/resources/shaders/text/` - the stats overlay's own 2D
  vert/frag pair.
- `src/main/resources/scenes/` - three commented example scenes: basic
  primitives, a petal (ellipsoid + modifiers), a rigged arm (parent/child
  hierarchy).
- `src/main/resources/scenes/zomboid/` - three composed Project Zomboid
  assets (rain barrel, street lamp, crate stack), built with the Blender
  add-on below and rendered to confirm they read correctly - see `docs/renders/`.
- `tools/blender-addon/pbd_tools/` - the Blender -> `.pbd` export add-on:
  one-click creation buttons in the 3D viewport's `N` > PBD sidebar tab (or
  `Add > Mesh > PBD ...`), a properties panel (type, material), export to
  `.pbd`. Parent/child hierarchy uses Blender's native parenting directly
  (`obj.parent`), no parallel system to maintain. Details in
  `tools/blender-addon/README.md`.
- `src/test/java/` - JUnit 5 suite: parsing, modifiers, curves, hierarchy
  sort, error cases (cycle, unknown parent/type), transform propagation in
  `HierarchyResolver`, patch counting in `PatchExpander`, OBJ parsing
  (deduplication, triangulation, negative indices) in `ObjParserTest`.

## What has been verified, and how

- **`format/`, `render/HierarchyResolver`/`PatchExpander`, `classicmesh/ObjParser`**:
  compiled and tested with `javac` directly against a local JOML stub
  faithful to its real API (checked against its javadoc and source code) -
  no network access to Maven Central in the environment this repo was
  prepared in. The OBJ parser test caught two real bugs before they
  shipped: vertex deduplication keyed on raw face tokens instead of
  resolved indices (so a positive and a negative/relative reference to the
  same vertex didn't merge), and a hand-written test fixture that
  accidentally reused a normal index across faces.
- **The PBD shaders**: compiled and **linked for real** against a live
  OpenGL 4.3 context (Mesa/llvmpipe via Xvfb) - not just per-stage syntax
  validation (`glslangValidator`), but an actual `glLinkProgram` and a real
  `glDrawArrays(GL_PATCHES, ...)` with no GL error, reading back rendered
  pixels to confirm correctly lit geometry appears on screen. This process
  found and fixed real bugs on **three separate occasions**, all the same
  class of mistake: `layout(location=N)` collisions between shader stages
  (explicit locations are global to the whole linked program, not private
  to each stage) - first between `pbd.tesc`'s uniforms and `pbd.frag`'s,
  then between `text.vert` and `text.frag`, then again when adding
  materials, between `pbd.tese`'s new `vWorldPos`/`vNormal`/`vMaterialID`
  outputs and `pbd.frag`'s matching inputs missing their own explicit
  locations. None of these are visible from per-stage validation alone -
  only a real link surfaces them. Also discovered along the way: GLSL 460
  wasn't actually needed (dropped to 430, the real minimum for SSBOs),
  which matters because **430 exceeds native macOS's ceiling (4.1)** -
  this part of the pipeline will not run under native macOS OpenGL, only
  via the VM path identified earlier.
- **The three test materials**: rendered three spheres side by side
  (wood/metal/plastic) through the real, linked shader pipeline and
  visually confirmed they read as matte brown, sharp bright highlight, and
  softer colored highlight respectively.
- **The classic OBJ path**: `classic.vert`/`classic.frag` linked and drawn
  for real against an actual VBO/IBO (the same hard-edge test cube used to
  verify the parser), confirming real vertex attributes flow correctly
  end to end, hard edges preserved.
- Two known, unrelated limits: the cone's normal is an approximation
  (fixed slope, worth refining if precise shading near the apex matters),
  and the cube's 6 faces haven't been checked for consistent winding
  (relevant for back-face culling, not yet enabled anywhere in this project).
- **Triangle count per patch**: unlike a classic mesh, a patch's triangle
  count depends on its tessellation level, not on a fixed vertex count.
  The first formula tried (`2 * level^2`, applying the same level to both
  parametric directions of every patch) was measured exact against a real
  `GL_PRIMITIVES_GENERATED` query - but it was the wrong formula to apply
  everywhere: a cylinder wall only needs fine subdivision around its
  circumference, not along its height, and a cube face needs none at all
  in either direction, since a flat surface looks identical regardless of
  how finely it's cut. Applying the LOD level symmetrically to a patch
  that's only curved in one direction (or not curved at all) wastes
  triangles on a dimension that buys nothing - for a streetlamp built from
  4 cylinders and a sphere, this meant ~416 triangles at the coarsest tier
  when a much lower count would look identical. `pbd.tesc` now decides,
  per patch, which of its two directions are actually curved
  (`roundedAxes()`): both for a sphere, one for a cylinder/cone wall or
  cap, neither for a cube face or plane - directions that aren't curved
  are pinned at a flat floor of 1 regardless of LOD tier. Three formulas
  came out of this, each checked against a live `GL_PRIMITIVES_GENERATED`
  query before being trusted (not just derived on paper):
    - sphere (round x round): `2 * level^2` - unchanged, still exact.
    - cylinder/cone wall or cap (round x flat): `4 * level - 2` - **not**
      the naive `2 * level * 1` product; confirmed exact across 9 tested
      levels (1 to 64). Real quad tessellation adds boundary-
      reconciliation triangles whenever the two parametric directions get
      different levels, which a simple product misses.
    - cube face / plane (flat x flat): constant `2` - confirmed unchanged
      across every level tested, i.e. always the minimum single quad.
  `PbdRenderer.lastTriangleCount` is a CPU computation matching these
  three verified formulas exactly (see its extensive field doc for the
  full story, including why this is computed rather than queried - the
  query mechanism itself was independently confirmed correct in isolation
  but never worked once wired into this class, and no LWJGL-specific
  environment was available to isolate that wiring bug further). Checked
  against a 4-cylinder-plus-sphere scene matching a real streetlamp
  (5 instances, 13 patches): 200 triangles at the coarsest tier versus
  ~416 with the old symmetric formula, for the same visual result.
  Worth noting honestly: this still isn't the same simplification an
  artist would make by hand (a hand-modeled "lowest LOD" cylinder might
  drop to a 4-sided prism, ~12 triangles total) - the flat floor of 1
  reduces the non-curved direction to its absolute minimum, but the curved
  direction still evaluates true cylinder/cone math rather than swapping
  to a different primitive at low LOD. The remaining lever for going lower
  is the tier's level value itself (`LOD_LEVELS` in `Main.java`), not the
  flat floor, which is already at its hard minimum of 1.
- **`GlWindow`, `ShaderProgram`, `PbdRenderer`, `ClassicMeshRenderer`,
  `Main`** (the LWJGL glue itself): written from standard LWJGL patterns,
  **without tool-based verification** in the sessions that produced them
  (at explicit request, to move faster) - so not compiled against a real
  LWJGL here. The buffer layouts they build match exactly what was proven
  to work in the native tests described above (same offsets, same std430
  order). One issue already caught by careful reading rather than
  compilation: `ByteBuffer` defaults to `BIG_ENDIAN` in Java, corrected
  explicitly to native order before every GPU upload.
- **The bitmap font and text rendering**: every glyph hand-drawn then
  rendered at a large magnification for visual inspection before being
  ported to Java; the atlas generated by the Java code was compared
  byte-for-byte against the verified version (identical). The
  `text.vert`/`text.frag` pipeline was linked and executed for real (same
  method as the PBD shaders) - this is what caught the second instance of
  the location-collision bug above. Two characters (`[` `]`) and the comma
  were missing on first real use, found via a combined scene+overlay
  render test, added and re-verified afterwards.
- **`FlyCamera`**: the horizontal forward/right logic and the yaw
  convention were checked with an independent arithmetic test (plain Java,
  no JOML) across several yaw angles. Uses only JOML primitives already
  confirmed elsewhere in this project (constructors, public fields,
  `perspective`/`lookAt`) rather than unverified convenience methods.
- **`disc`, `taper`, and the cube's `smooth` rounding**: all three
  rendered together through the real, linked shader pipeline (six
  instances side by side: a sharp cube, a lightly-rounded one, a
  fully-rounded one, a tapered cylinder, a standalone disc, and a
  reference sphere) and visually confirmed - the lightly-rounded cube
  reads as a proper cushion shape, the fully-rounded one (smoothness at
  its 0.5 maximum) is visually indistinguishable from the reference
  sphere, confirming the degenerate-to-sphere limit derived on paper, and
  the tapered cylinder is a clean frustum. The rounding formula (clamp to
  an inner box, project the leftover onto a sphere of the smoothing
  radius) and the taper normal (derived from the wall's actual tangent
  vectors, not just carried over from the untapered case) were both
  worked out algebraically before this render, which is what it was
  checking.
- **The Blender add-on**: installed and exercised for real in headless
  Blender 4.0 - register/unregister, creating the 5 primitives via its own
  operators, native Blender parent/child hierarchy (including the case of
  a non-uniformly-scaled parent, where Blender already expresses the
  child's position in the parent's scaled frame), export. The Z-up -> Y-up
  axis conversion was verified independently (see
  `tools/blender-addon/README.md`). The three `scenes/zomboid/` scenes
  were composed with this add-on, then rendered with the already-validated
  shaders (same files, unchanged) to confirm they read correctly - see
  `docs/renders/`. That verification render goes through a small C harness
  (GLFW directly, not LWJGL), so it doesn't test `PbdRenderer.java` itself,
  only that the exported data produces the right geometry once interpreted
  by the shaders. The new sidebar panel (`VIEW3D_PT_pbd_tools`) and the
  full French-to-English translation pass across the add-on were written
  without headless-Blender verification, per explicit request to move
  faster - the panel uses standard, well-established Blender API patterns
  (`bl_space_type='VIEW_3D'`, `bl_region_type='UI'`, `bl_category`), and
  the translation only touched comments and message strings, not logic.

## Build & test

```
./gradlew test
./gradlew run --args="src/main/resources/scenes/zomboid/rain_barrel.pbd"
./gradlew run --args="path/to/reference_mesh.obj"
```

(The Gradle wrapper is not included in this export - generate it with
`gradle wrapper` if you don't have Gradle installed locally. The LWJGL
version in `build.gradle.kts` has not been checked against Maven Central
in this session - confirm/adjust it if `gradlew` can't find it.)

## Not implemented yet

- `bench/` - the actual benchmark harness (bandwidth, VRAM, FPS/distance,
  CSV export) comparing the PBD and classic OBJ paths systematically. Both
  paths and their stats overlays exist; this is about automating the
  comparison and logging it, rather than reading numbers off the screen.
- A caching layer for static, fixed-view assets (raised for this game's
  actual use case: fixed isometric, pixelated, non-moving scenery, where
  the "correct" LOD for a given object doesn't change frame to frame and
  doesn't depend on which instance is being drawn - only on type,
  modifiers, and LOD tier). Direction confirmed: **Transform Feedback**,
  capturing the tessellator's real GPU output into a buffer once and
  redrawing it without tessellation on later frames - not a CPU-side
  reimplementation of the shape math (a small proof of concept for the
  latter was built and discarded once this was decided). Deliberately
  sequenced after the shape work above, not started yet.
- `twist` and `curve` modifiers on the GPU side (registered, not wired
  into the TES). `curve` needs a Frenet frame along a curve and, per a
  later discussion, a variable radius profile along it (the trumpet-bell
  case) - meaningfully more work than everything implemented so far.
- CSG/boolean "anti-primitives" - explicitly dropped; `taper` and the cube
  `smooth` param cover the concrete cases that motivated the idea
  (lampshade frustum, cushions), and real-time CSG in a tessellation
  shader is a research-level problem, not a small addition.

On macOS, `-XstartOnFirstThread` is already wired into the `run` task in
`build.gradle.kts` (Cocoa constraint) - but remember: native macOS OpenGL
(4.1) is not enough for the SSBOs (4.30 minimum) used here.
