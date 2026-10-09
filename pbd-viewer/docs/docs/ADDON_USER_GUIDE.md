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

There is no project field to fill in. **Export & Visualize**, **Add to
tileGeometry.txt**, **.pbdbin** and **.pbdasset** each open Blender's file
browser, and find the project (the folder that holds `build.gradle.kts`) by
walking up from the place you choose to save to - and, if that finds
nothing, from where your `.blend` file is saved. So save inside the Gradle
project (anywhere under it), or keep the `.blend` there. If neither is
true the button says so (`No build.gradle.kts found above ...`) instead of
doing nothing.

The shared `materials/` folder (the .pbdmat browser) is set on its own:
**Materials Folder**, in the **PBD Materials** panel. Left empty, the
add-on looks in `src/main/resources/materials` of the project - which only
works when the add-on is run from a checkout of the project, not when it
is installed in Blender's own add-ons folder; in that case set the folder
once.

## File metadata

The **File metadata** box at the top of the PBD Tools panel describes the
whole file, not any object in it:

- **Name** - what it is called ("Wardrobe", "Rain Barrel").
- **Kind** - a free category ("furniture", "container", "decoration").
- **Authors** - several are separated by commas; each is credited on its
  own line.
- **Origin** - the URL a file was fetched from. Whatever fetched it fills
  it in; leave it empty for a file you made yourself.
- **Description** - one line of free text: what it is, what is special
  about it, which of its materials are new. It is meant for the site's
  search.

Uploading needs a name, a kind and an author. What you type is written as
typed - spaces, `#`, `=` and commas included - with two exceptions the
format forces on the add-on: a double quote is written as an apostrophe
(the format has no way to escape one) and a line break as a space. **Import**
fills the box from the file, description included.

## Creating primitives

**PBD Tools > Create** has a button per primitive type: Plane, Sphere,
Cylinder, Cone, Cube, Disc, Torus, plus **Reference** (places another
`.pbd` file into the scene), **Light**, and **Pivot (hinge)** (an
invisible anchor with no shape: the hinge of a door, see **Lever arm**
below). Every created object is a normal Blender mesh (or Empty, for a
Reference and a Pivot) you can move, rotate, and scale like anything
else — the add-on just tags it so the exporter knows what it is.

**Convert to PBD Mesh** (below the Create grid) is different from all
of those: select ANY existing mesh — imported, sculpted, built by hand,
doesn't matter — and click it to embed that object's own current
geometry directly, instead of a procedural shape. Use this for content
that doesn't fit the plane/sphere/cylinder/cone/cube/disc/torus model at
all. Unlike the procedural types, its exported size is its own real
size (no ×2 native-size convention to think about), and its faces
section / smooth / taper / bend / shear controls don't apply — a `mesh`
carries whatever geometry it already has, verbatim.

### Names

An object's name becomes its **id** in the file, and other lines point at
it by that id (a child's `parent`, a container's door). The format cannot
hold a space or any of `{ } ( ) [ ] = , # "` in an id, so the exporter
writes each run of them as one underscore - `front door` becomes
`front_door`, `a=b` becomes `a_b` - and lists the names it changed in its
warnings. The object keeps its name in Blender; if two names would come to
the same id, the second gets `_2`. Hyphens, dots, accents and digits are
fine. (Before this, `front door` produced a file the engine refused with
"Expected '{'".)

**Material names are not changed for you**, because they must match the
`.pbdmat` file and the shared library letter for letter. A material name
with a space makes the file unreadable, and the export warns you which one:
rename it, using letters, digits and `_`, `-`, `.`.

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

Keyframes are the older way to make a door. For anything the player
opens, prefer the **lever arm** below: no clip to bake, and the player
can grab it with the mouse.

### Lever arm (doors, drawers, lids, levers)

A lever arm is **two poses and one number**. The object's own
Location/Rotation is the *closed* pose (arm value 0); a second pose you
capture is the *open* one (arm value 1). The engine moves between them:
when the player clicks the door, when a script calls `setOpen`, or when
the player **grabs the door with the mouse and pulls**. Nothing is
baked, so the door can be left ajar, locked, or read by game code at any
value in between - and that value is what decides when a container's
contents show up (see **Containers**).

To build a door:

1. **Create > Pivot (hinge)**, and put the pivot exactly on the hinge
   edge. A pivot has no shape of its own (it is the engine's `group`).
   **Keep its scale at 1**: whatever is parented to it inherits the scale,
   in Blender and in the engine (the panel and the export both warn).
2. Parent the door panel to the pivot (`Ctrl+P`), then the handle, a lamp,
   anything else. They all swing with it, and grabbing any of them with
   the mouse grabs the arm.
3. Select the pivot and tick **Lever Arm** (box *Lever Arm (alternative to
   keyframe animation)*). The pivot's current Location/Rotation is the
   closed pose. Turn it open about the hinge (a door's vertical axis is Z
   in Blender: `R Z 90`), click **Capture Current Pose as Open**, then turn
   it back (or `Ctrl+Z`). **Open Position** and **Open Rotation** can also
   be typed.
4. Set how it behaves:
   - **Speed**: arm units per second for a click or a script (2 = shut to
     open in half a second). A mouse drag ignores it.
   - **On Release**: *Snap to nearest end* (an ordinary door) or *Stay
     where released* (a heavy door left ajar, a valve wheel).
   - **Locked** and **At**: the arm starts bolted at that value - 0 is a
     door locked shut. It can still be pointed at, so the game can say
     "locked"; game code unlocks it at run time.
   - **Open Sound** / **Close Sound**: a `.wav` played once when the arm
     leaves / comes back to its closed end, whoever moved it. Only the file
     name is exported.
5. Doors that must move together (a wardrobe's two leaves) get the same
   **Link Group**; a locked leaf stays put while the other moves.

A drawer needs no pivot: put the Lever Arm on the drawer itself and
capture the pose with the drawer pulled out (the rotation stays what it
was, so it is a pure slide). An arm on a panel directly turns the panel
about its own centre, which is why a hinged door wants the pivot.

What the export writes for the door above (the `.pbd` text; this example
is fed to the engine's own parser by the test suite):

<!-- check: scene -->
```
group hinge {
  pos   = (0, 1, 0)
  scale = (1, 1, 1)
  leverArm {
    openPos = (0, 1, 0)
    openRot = (0, 90, 0)
    speed = 2
    release = free
    openSound = "door creak.wav"
  }
}
cube panel {
  pos    = (0.5, 0, 0)
  scale  = (1, 2, 0.1)
  parent = hinge
}
```

Good to know:

- **Keyframes win.** An object with both keyframes and a Lever Arm plays
  the keyframes and ignores the arm. The panel turns red and the export
  warns; delete one of the two.
- A **Reference** or a **Light** cannot carry a Lever Arm (it is not
  exported, and the export says so): put the arm on a Pivot and parent the
  Reference or the Light to it.
- The export also warns when the open pose equals the closed pose (nothing
  would move) and when a pivot's scale is not 1.
- The two poses are interpolated angle by angle. The add-on writes the
  open rotation as the Euler triple nearest the closed one, so a swing of
  more than 180 degrees about one axis comes out as its shorter equivalent:
  for a 270-degree door, write `openRot` by hand in the `.pbd`.
- Blender shows the closed pose only - there is no drag and no physics in
  Blender. Check the motion in the engine (**Export & Visualize**).
- **Import** brings it all back: a `group` becomes a Pivot, the arm's
  settings fill the panel (the open rotation shown on your side of 180
  degrees: 190, not -170), and a drawer's position keyframes come back as
  Blender keyframes.
- The same pivot trick works for anything that turns: a lever, a gate, a
  trapdoor, a valve wheel (*Stay where released*).

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
only once at least one linked door is fully open (a lever arm at 0.99 or
more, a keyframed door at the end of its clip), then cached for the
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

- **Export** (top of PBD Tools) — opens the file browser and writes the
  scene to the `.pbd` file you choose.
- **Export & Visualize** — same, then launches the engine viewer on the
  result via `gradlew run` (doesn't block Blender); the file has to be
  saved inside the Gradle project (see "Setting up your project").
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
- a full **`http://`/`https://` URL** — fetched and cached locally (in
  a `pbd_cache/remote` folder next to your `.blend`, or in the system's
  temp folder if it was never saved; **Clear Remote Cache** empties it -
  the engine keeps its own cache, under `src/main/resources/cache/remote`,
  and the two are not shared), with an optional **Fallback URL** field
  (shown once Source looks like a URL) tried if the primary one 404s.

A source that cannot be read - no such file, no network, a 404 - never
stops an import: **Import** reports it as an error, and a Reference inside a
scene that is being imported stays as an empty placeholder with a warning
that names it, while the rest of the scene comes in.

Click **Test Reference** to actually resolve/fetch it right now and
confirm it works, rather than only finding out when the engine tries to
load the scene. Check **Embed contents in this file** to copy the
referenced file's instances directly into this export instead of keeping
a pointer to an external file — useful for shipping one self-contained
file instead of several linked ones. The copies hang on an invisible
`group` anchor named like the Reference object (it carries the
Reference's position), and their ids get the prefix `<Reference name>.`,
so two embeds of the same file never collide; everything travels with
them (keyframes, lever arms, lights, meshes, link groups). The source
file on disk is never modified either way.

### Compiled formats (.pbdbin / .pbdasset)

The **.pbdbin** and **.pbdasset** buttons (next to Export) need two
things, both flagged directly in the panel now if missing:

1. The file saved inside this Gradle project (or the `.blend` kept in it):
   the project root is found from there.
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

A `.pbdasset` is one zip with the scene, the `.pbdmat` files and
textures it includes, and an optional icon. It does **not** bundle the
files a Reference (`pbd_ref`) points to: tick **Embed contents in this
file** on the Reference when the asset must stand alone.

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
- A lever arm cannot sit on a Reference or a Light (put it on a Pivot);
  a pivot's scale must stay 1; keyframes win over a lever arm; a swing of
  more than 180 degrees about one axis needs a hand-written `openRot`
  (see **Lever arm**).
- **Indestructible** is not exported for a Reference (the `pbd_ref` block
  has no such field and the engine would refuse the file): set it on the
  referenced file's own objects.
- A child of a PBD primitive is now exported relative to its parent's
  engine-side size (a knob glued to a door panel used to land twice as far
  away and twice as big): **re-export scenes made with an older version
  of the add-on** that have children of primitives.

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

## Draft text for the tutorial

Text to move into the web tutorial (the part about doors replaces the
older "keyframe a door" step; the part about resources is the one to
insist on). Adjust it to what the upload page does with the files.

**1. Build with primitives.** Everything in a PBD asset is a plane, a
sphere, a cylinder, a cone, a cube, a disc or a torus, moved, scaled and
modified. A crate is one cube; a barrel is a cylinder with a taper; a
whole house is a few dozen of them. The file stays tiny, and the engine
can cut any of them into voxels when it is shot.

**2. Reuse what already exists.** Before you paint or import a texture,
look at the **Available in materials/ folder** list of the *PBD
Materials* panel: the materials the world already uses are there. Add
the one you want and set its **Material Source** to **Shared**: your
`.pbd` then only holds a one-line `include_material` that points at the
library's material, instead of a private copy of the texture, and your
asset looks like the rest of the world. Keep **Embed** for a material that
really is new, and say so in the **Description** of the file metadata box.

**3. A door the player can grab.** Create a **Pivot (hinge)** on the hinge
edge, parent the door and its handle to it, tick **Lever Arm** on the
pivot, turn the door open, click **Capture Current Pose as Open**, turn it
back. In the game the player grabs the door with the mouse and pulls it;
let go and it swings to the nearest end (or stays where you left it, if you
chose *Stay where released*). No animation to bake, nothing to upload but
the asset itself. Give it an **Open Sound** and a **Close Sound** and it
creaks and slams by itself.

**4. Put a lamp, a bell, a plate on it.** Anything parented to the pivot
swings with it - and a drawer can sit inside a door: grab the drawer's
knob and the drawer comes out, not the door.

**5. Compose, do not copy.** To put a ready-made asset in yours, add a
**Reference** to it. If your asset must travel alone, tick **Embed
contents in this file**.

**6. Test, then upload.** **Export & Visualize** opens the engine on your
file: walk up to the door and pull it. Then export as **.pbdasset** and
upload it.
