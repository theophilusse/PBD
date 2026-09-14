# PBD Tools — Blender Add-on User Guide

This add-on lets you build, edit, and export assets in the PBD (Primitive
Based Description) format from Blender, and preview them in the PBD
engine viewer.

## Installation

1. In Blender: **Edit > Preferences > Add-ons > Install...**
2. Select the `pbd_tools` folder (or a zip of it).
3. Enable "PBD Tools" in the add-on list.
4. Open the sidebar in the 3D Viewport (press `N`) — you'll find a new
   **PBD** tab with two panels: **PBD Tools** and **PBD Materials**.

## Setting up your project

At the top of the **PBD Tools** panel, set **PBD Project Root** to the
folder containing this project's `build.gradle.kts`. This is used to:
- find the shared `materials/` folder (for the .pbdmat browser),
- locate `gradlew` for **Export & Visualize**,
- run the tileGeometry export tool.

## Creating primitives

**PBD Tools > Create** has a button per primitive type: Plane, Sphere,
Cylinder, Cone, Cube, Disc, Torus, plus **Reference** (places another
`.pbd` file into the scene). Every created object is a normal Blender
mesh you can move, rotate, and scale like anything else — the add-on
just tags it so the exporter knows what it is.

**Convert to PBD Mesh** (below the Create grid) is different from all
of those: select ANY existing mesh — imported, sculpted, built by hand,
doesn't matter — and click it to embed that object's own current
geometry directly, instead of a procedural shape. Use this for content
that doesn't fit the plane/sphere/cylinder/cone/cube/disc/torus model at
all. Unlike the procedural types, its exported size is its own real
size (no ×2 native-size convention to think about), and its faces
section / smooth / taper / bend / shear controls don't apply — a `mesh`
carries whatever geometry it already has, verbatim.

### Object properties (Properties panel > Object > PBD, or the sidebar)

- **PBD Type** — the primitive kind. Change this if you build a mesh by
  hand and want to tag it afterward.
- **Metadata Only (invisible)** — the object's position/size are kept in
  the export but nothing is drawn, in Blender or in the engine. Used for
  a container's interior storage volume (see **Containers** below).
- **Dimensions (Dim X/Y/Z)** — world-space size; edits `obj.scale`
  underneath. Grows from the object's own center.
- **Faces** (cube only) — untick any of the 6 to remove that face
  entirely (an open-topped crate, a cabinet with no back panel).
  Live-previewed; labeled in Blender's own axis terms.
- **Face position (min/max per axis)** — moves just ONE face, leaving
  the opposite face fixed, instead of growing symmetrically like
  Dimensions does. Handy for a shelf whose back should stay against a
  wall while only the front moves. Use the **Mirror X/Y/Z** buttons to
  flip one side's careful hand-tuned face positions onto the other
  side of a symmetric object instead of re-entering them by hand.
- **Refresh UV Mapping** — re-projects this object's UVs sized to its
  current dimensions. Blender's default primitive UVs don't account for
  non-uniform scale, so a tall thin door can look stretched in the
  viewport even though the engine renders it correctly; run this after
  resizing to fix that. (This is a Blender-viewport approximation of the
  engine's own per-face UV math, not a byte-for-byte match — good enough
  to check proportions, not a texel-perfect preview.)
- **Cap** (cylinder/cone) — which end(s) get a solid cap.
- **Smooth X/Y/Z** (cube) — rounds edges on each axis.
- **Shear**, **Bend**, **Taper** — the other supported modifiers.
  Bend previews live via a native Blender modifier; smooth/shear/taper
  are previewed by editing mesh data directly (Blender has no exact
  native equivalent for those).
- **Link Group** — objects sharing the same Link Group name open/close
  together from a single click in the engine (a wardrobe's two doors).
- **Loop Animation** — this object's keyframes ping-pong continuously
  in the engine instead of the default click-to-open/close behavior; a
  ceiling fan, not a door.

### Animation

Use Blender's normal keyframing (`I` over Location/Rotation, or the
**Insert Keyframe Here** button in the panel) to animate an object's
position/rotation. The panel lists every detected keyframe time with
**Goto** and **Delete** buttons. Keyframes export automatically.

### Containers (bin-packed contents)

A "container" is a **Metadata Only** cube (the interior storage volume)
linked to one or more doors:

1. Create a cube, check **Metadata Only**.
2. In the **Doors that open this container** list, click **+** to add a
   row, then set its **Door object** field to the door (or lid) that
   should open it. Add more rows for several doors sharing one
   compartment.
3. The **This object opens N container(s)** section (shown when you
   select a door) lists every storage cube that door controls — a
   read-only view computed from the cubes' own lists, so it's never out
   of sync.

In the engine: the container's contents (a random selection of `.fbx`
items, bin-packed into the available volume) are computed and shown
only once at least one linked door is fully open, then cached for the
rest of that run. Press **E** while looking at a placed item to remove
it; anything resting above it falls.

## Materials

**PBD Materials** panel: add an entry per material name, set color,
shininess, texture maps (diffuse/normal/roughness/displacement), UV
scale, reflectivity, transparency. **Material Source** on each entry
chooses whether it's written into this scene's own `.pbdmat` file
(**Embed**) or references the shared, project-wide `materials/` folder
(**Shared**) — use Shared for a material used across many assets.

The **Available in materials/ folder** list (bottom of the panel) shows
every `.pbdmat` already in the shared folder, with a one-click **Load**
button per entry.

## Exporting

- **Export** (top of PBD Tools) — one-click export to
  `scenes/handmade/<blend file name>.pbd` under your project root.
- **Export & Visualize** — same, then launches the engine viewer on the
  result via `gradlew run` (doesn't block Blender).
- **Import** — opens a file browser to load a `.pbd` scene back into
  Blender (full round-trip: hierarchy, keyframes, materials, modifiers,
  containers).
- **Add to tileGeometry.txt** — exports the current scene and adds it as
  a new tile in the project's Project Zomboid `tileGeometry.txt`, in a
  tileset name you choose.
- **File > Export > PBD** / **File > Import > PBD Scene** — the same
  operations from Blender's standard menus, if you'd rather pick an
  exact save location than use Quick Export.

### Referencing another file (composition)

A **Reference** places another `.pbd` file's whole scene at this
object's transform (**Add > Mesh > Reference (pbd_ref)**, or the button
in PBD Tools). **Source** takes either:

- a **local path**, resolved relative to wherever this file itself gets
  exported, or
- a full **`http://`/`https://` URL** — fetched and cached locally
  (same cache the engine's `PbdFetcher` uses, under
  `src/main/resources/cache/remote`), with an optional **Fallback URL**
  field (shown once Source looks like a URL) tried if the primary one
  404s.

Click **Test Reference** to actually resolve/fetch it right now and
confirm it works, rather than only finding out when the engine tries to
load the scene. Check **Embed contents in this file** to copy the
referenced file's instances directly into this export (parented to an
invisible anchor) instead of keeping a pointer to an external file —
useful for shipping one self-contained file instead of several linked
ones. The source file on disk is never modified either way.

### Compiled formats (.pbdbin / .pbdasset)

The **.pbdbin** and **.pbdasset** buttons (next to Export) need two
things, both flagged directly in the panel now if missing:

1. **Project Root** (the field above Export) pointing at this Gradle
   project's root folder.
2. The project built at least once — `./gradlew build` or
   `./gradlew run` — so `PbdConvertMain` exists under `build/` for the
   add-on to shell out to.

Without either, clicking the button reports a clear error rather than
doing nothing silently — if you see no error and no file either, you're
very likely looking at an out-of-date copy of this add-on rather than a
missing feature; check the version number at the top of the PBD Tools
panel against this build's (see `__init__.py`'s `bl_info`) and reinstall
if they don't match. Reinstalling means removing the old one from
Preferences > Add-ons first, THEN installing the new zip, THEN
restarting Blender - just overwriting the files on disk while Blender
is still running can leave the old version's code active in memory
until a full restart.

## Known limitations

- Blender can't preview a Reference's actual geometry in the viewport
  (it shows as an Empty) — only the engine renders the real content.
- UV refresh is an approximation for editing convenience, not a
  guaranteed pixel-match to the engine's own shader.
- Mirroring face positions reads back through Blender's own dimension
  system, so very small floating-point differences from the exact
  mirrored value are normal.
- Face removal (the Faces box on a cube) removes whole flat faces;
  moving individual EDGES to make a non-axis-aligned hexahedron isn't
  implemented (see ENGINE_DEV_GUIDE.md's rough-edges list).
- Per-keyframe **Sound** only appears once the object has at least one
  detected keyframe (rotation or location) — the whole Animation section
  shows "No keyframes on this object yet" instead until you add one via
  **Insert Keyframe Here**.

## Minified export

Tick **Minify exported .pbd text** (next to Export) to write the .pbd
file with every separator trimmed to the minimum the parser can still
read - no indentation, fields space-separated instead of one per line,
comments dropped. Smaller file, not meant for hand-editing or diffing -
mirrors `PbdSerializer.java`'s own pretty/minified distinction, now
available from a plain text export too, not only via the separate
`.pbdbin` compiled format. Verified byte-for-byte equivalent when
re-parsed: same instance count, positions, keyframes, and sound fields
as the pretty version of the same scene.

## Opening .pbdbin / .pbdasset back up in Blender

**Import** now accepts `.pbdbin` and `.pbdasset` directly, not just
plain `.pbd` - pick either from the file browser and it converts
in-memory before handing off to the same reader a plain `.pbd` uses.
`.pbdasset` gets unzipped to a temp folder first (bundled
materials/textures land alongside `scene.pbd`, exactly where its own
`include_material` expects them); `.pbdbin` gets gzip-decompressed to a
hidden sibling file next to the original (`.name_decompressed.pbd`) so
a relative `include_material` inside it still resolves correctly -
writing that decompressed text to an unrelated temp directory was
tried first and silently broke materials, caught by actually testing
against a real materials-referencing file rather than assuming the
Java-side fix pattern carried over automatically.
