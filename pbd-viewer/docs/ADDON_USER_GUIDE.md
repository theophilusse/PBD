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

Under that grid a short list, **Ready-made, with a lever arm**, makes whole pieces in one
click: **Door (hinge)**, **Drawer** and **Light Switch + Lamp** (the same three are in
`Add > Mesh`, as *PBD Door*, *PBD Drawer* and *PBD Light Switch*). What they make, and how
to do the same on an object you built yourself, is in **Lever arm** below.

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
- **Refresh UV Mapping** — gives this object's preview the texture
  coordinates the **engine** gives it. A primitive made with the add-on already
  has them (nothing to press); the button is for when you have overwritten the
  UVs by hand, or changed the shape. They do not depend on the size of the object:
  the engine's shader draws every primitive as a few patches (a cube is six, one per
  face; a cylinder is three) and each patch shows the whole picture once - times the
  material's UV Scale - whatever its size, so a door 4 cm thick and a wall 4 m long
  both show the picture once on each face. (Blender's own cube projection gave every
  face a layout of its own, and a locker's picture streaked in the viewport while the
  engine showed it whole.) The coordinates are worked out for every corner of every
  face by `uv_layout.py` and compared point by point with the engine's own evaluator of
  the shader on each run (`scenario_uv_previews`; a hundred-thousandth of the unit is
  what Blender's float32 can honour). A `mesh` object keeps the UVs it has.
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

**The quick way.** Select the object (or, for a door, its pivot) and, in the box *Lever Arm
(alternative to keyframe animation)*, click **Door**, **Drawer** or **Lever**. Like
*Insert Keyframe Here*, it works from where the object is *now*: that pose is the closed one,
and the open one is worked out for you.

- **Door** swings 90 degrees about the object's vertical axis and goes on to the nearer end when
  let go. The object turns about its *own* origin, so a door whose hinge is on its edge wants the
  pivot of step 1 below (the **Door (hinge)** button under *Create* does exactly that, and puts a
  wooden panel and a metal handle on it).
- **Drawer** slides out along the object's own front (-Y) by four fifths of its depth and stays
  where the player lets go.
- **Lever** tilts 60 degrees about the object's X axis and goes on to the nearer end - a switch, a
  lever to pull.

The **X** next to the *Lever Arm* tick removes it again. Everything these buttons set is an
ordinary setting of the box below (open pose, speed, release): change it there. **Light Switch +
Lamp** (under *Create*) makes a wall plate, a toggle that flips up when the player drags it, and a
lamp that is on while the toggle is up; move the lamp to the ceiling.

To build a door by hand, or to see what the buttons did:

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

### Lights that switch with a door, a drawer or a lever

A light that shares its **Link Group** with something that opens is **on while that thing is
open and off while it is shut**: the lamp of a wardrobe, the light a fridge turns on, the lamp a
wall switch flips. The thing that opens needs a Lever Arm or keyframes, and must not loop.

The quick way: select the door (or drawer, or lever - or the pivot it hangs on) and click
**Add Light Linked to This**, in the box *Light that follows this object*. The button adds a
light, gives it and the door a common Link Group (a name of its own if the door had none) and
puts the light next to the door; move it where it should shine. The box then lists the lights
that follow this object. The button is greyed until the object has a Lever Arm or keyframes.

On a light, the box *Light* shows what the light follows. With a partner it says **On while
open: *door name*** and the **Enabled** tick is greyed - the engine ignores it for a linked
light, so there is no switch that does nothing. If nothing that opens has the light's Link
Group, the box says so (an error icon) and **Enabled** decides, as for any light; give the door
the same Link Group, or use the button.

With several partners - the two leaves of a wardrobe - the light is on while **any** of them is
open. A light's own keyframes or lever arm do not make it a switch.

### Lights the player switches with E

In the game, **E** on any part of a model switches that model's lights (and takes an item out of an open container when an item
is the nearer thing under the crosshair). Nothing needs to be set up for that beyond making the light **belong to what the player
looks at**:

- **Parent the light to the lamp** (select the light, then the lamp, Ctrl+P - or build the lamp with the light as a child). E on the
  base, the pole, the shade - anything of that lamp - switches the light. A lamp that other files place with a Reference is one
  model per placement: two copies of a desk lamp on one desk are switched one at a time.
- **Or give the light and the part the same Link Group** (a wall plate and the hall lamp it is meant for, or any flat file with no
  parents): E on the plate switches the light, wherever the light stands. A light whose Link Group is shared with a *door* is the
  door's, not E's.
- If the crosshair is on a part of a model with no light, nothing is switched and the console says why.

The light panel has a **Click Sound** field: a `.wav` file played when E switches this light. Only the file's name is exported
(`switchSound`) and the file travels with **Upload to the server**; the engine looks it up in its sounds folder
(`src/main/resources/sounds/`). Leave it empty for the engine's own click (`light-switch.wav`). The field is greyed for a light
that follows a door - E never switches those, the door decides - and the box under it says what E does.

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

**The Texture mapping box.** Every material that has a picture (a texture, or a normal map alone) shows a *Texture mapping* box in the PBD
Materials panel and in the object's panel (the one of the object shows its material): the **UV Rotation** row (0 / 90 / 180 / 270 degrees, the one
in force is pressed) with **Rotate Left** and **Rotate Right** first, then **Mirror U** and **Mirror V**, then a line that says in words how the
picture lies now (`Now: Turned 90° counter-clockwise, mirrored left-right`, or `Now: As in the file`). In the list of materials, a turned
picture shows its angle and a mirrored one a mirror mark. The box is under the Material field of an object, not in a menu: if you only see
mirror buttons there, the add-on that is running is older than v0.48.0 (see the end of *Compiled formats* for how to tell which one runs, and
how to update it).

**Solid view does not show any of it.** Blender's Solid shading draws a picture straight from the mesh's UVs and ignores the Mapping node where the
add-on keeps a texture's repeat, mirror and turn: only **Material Preview** (and Rendered) show them. When a viewport of the screen is in Solid
shading the box says so and offers **Show the Mapping (Material Preview)**, which switches the viewports that are in Solid and leaves the others as they are.

**Mirror U / Mirror V.** In the Texture mapping box, two buttons flip the picture of the material left-right (U) or
top-bottom (V) - the sign of the UV scale on that axis, which the engine reads as a mirror (`uvScale = -1` mirrors both,
`uvScaleU = -1` only U). A mirrored axis is marked "(on)"; pressing again flips it back. The buttons are in
the PBD Materials panel and in the object's panel (there they flip the picture of every object that uses its material), and act
on the material drawn beside them. Why they exist: the
voxel conversion of a shot object has to give every voxel the colour of the pixel the shader shows there, and a model whose
picture comes out the wrong way round (a handle slot on the left in the viewer, on the right after a hit) was mirrored
somewhere along the way; the engine's voxel mapping now follows the shader exactly, and when a picture itself is the wrong
way round for a surface, this is how you turn it.

**UV Rotation, Rotate Left / Rotate Right.** To map a texture properly it must sometimes be turned, not only scaled and mirrored (planks
that run across a door instead of along it, a label sideways on a crate). At the top of the Texture mapping box, the **UV Rotation** row (0 / 90 / 180 / 270,
the one in force is pressed) sets the turn of the picture on the surface, and **Rotate Left** / **Rotate Right** step it a quarter turn
counter-clockwise or clockwise (four steps bring it back). The turn is a property of the material, like its scale: every object that uses
the material is turned with it, so an object that must differ gets a material of its own. The buttons are in the PBD Materials panel and in
the object's panel, in the same box as the Mirror ones.

- **In Blender** the turn is shown through the **Rotation Z** of the *PBD UVScale* Mapping node (type Point), which sits between the UV map and
  the picture node(s) - the diffuse picture and the normal map go through the same node, so they stay lined up. Its Rotation Z is *minus*
  the angle (90 degrees reads -90 in the node): Blender's node turns the coordinates the picture is looked up with, which turns the picture
  the other way. Change the turn with the row and the buttons, not in the node - the entry is what is saved and exported.
- **In the engine** the key `uvRotation = 90` (degrees; nothing is written for 0) turns the picture once, where it is loaded, for the
  shader, the classic meshes and the voxels a shot object breaks into alike. A quarter turn to the left (90) takes the top of the picture to
  the left.
- **Order with the scale and the mirror**: the scale (a negative one is a mirror) comes first, the turn after - as in Blender's own Mapping
  node - so `UV Scale 3` with `90` is three repeats along the surface's U whatever the turn.
- **Only quarter turns**: the engine turns the picture once instead of at every pixel of every frame, and a free angle would need the
  shader to do it. A hand-written `uvRotation` that is not a multiple of 90 is ignored and reported by the engine's log (the picture stays
  as it was).
- The numbers stored in a **normal map** are not turned with its picture (Blender does not either): if the lighting of a turned normal map
  looks wrong, turn the map in an image editor instead.

### How a material breaks

What a shot (the **G** key in the engine) throws up, and how thin a connection may get before
the piece it holds comes away whole with a crack, is the **material's** business, never a
primitive's: every primitive made of `wood` breaks like wood, and one line of the `.pbdmat`
retunes them all. Each entry has a **Custom break** tick. Unticked, nothing is written and the
engine breaks the material like any material of its name (`wood`, `metal` and `plastic` have a
profile of their own, any other name the generic one); the panel says which numbers that is.
Ticked, the entry offers:

| Field | Meaning |
|---|---|
| **Break Width** | the thinnest connection, in metres, that still holds a piece to the rest. After a shot, a piece joined by less comes away whole. 0 = never (a rubbery material) |
| **Chips per Crater**, **Chips per Break** | particles thrown up by a crater of the full size (a smaller one throws proportionally fewer), and when a piece comes away |
| **Chip Size**, **Chip Speed**, **Chip Lifetime** | mean edge in metres, mean speed in metres per second, mean life in seconds (it fades out over the last quarter) |
| **Chip Gravity**, **Chip Bounce** | a multiple of gravity (1 falls like a stone, 0 floats, negative rises); 0 = stays where it lands, 0.95 = nearly keeps its speed |
| **Tint the chips** + colour | unticked, the chips take the colour of the surface that broke (right for a textured one) |
| **Break Sound** | the name of a file in `resources/sounds` played when a piece comes away. Empty = none |

**Back to the Engine's Defaults** unticks it and resets the numbers. For a material that is
**Shared** (not embedded), the shared file decides and these fields are not written - the panel
says so. The numbers are written with six significant digits; a name that holds a quote, a tab
or a line break cannot be written as a sound name, and the panel says so.

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
- **File > Export > PBD Binary (.pbdbin)** and **PBD Asset bundle (.pbdasset)** (v0.48.1) — the compiled formats, from the same menu (the two
  `.pbdbin` / `.pbdasset` buttons of the panel do the same); see *Compiled formats* below.

### Referencing another file (composition)

A **Reference** places another `.pbd` file's whole scene at this
object's transform (**Add > Mesh > Reference (pbd_ref)**, or the button
in PBD Tools). **Source** takes either:

- a **local path**, resolved relative to wherever this file itself gets
  exported, or
- a full **`https://` URL** (a plain `http://` one is refused; see "Upload and import rules" below) — fetched and cached locally (in
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

The **.pbdbin** and **.pbdasset** buttons (next to Export) are written by the add-on itself, in Python. They need **no Java, no Gradle project, no
Project Root and no particular folder**: save the file anywhere. (Up to v0.47 they ran the engine's converter through Gradle and the `java` found
on the PATH, and an older Java than the one the engine is built for ended in `UnsupportedClassVersionError: pbd/PbdConvertMain` - there is no such
step any more.)

- A **.pbdbin** is the scene's minified text, compressed. Its materials stay files of their own, as for a `.pbd`: the `.pbdmat` is written
  **beside it under the same name** (`crate.pbdbin` and `crate.pbdmat`, so two exports into one folder never write over each other's materials)
  and the pictures it names are copied beside it (**Copy Textures**, in the redo panel; a different file that already has a picture's name is
  left as it is, and you are told which).
- A **.pbdasset** is one self-contained file: a manifest (the authors, today's date and the description of the *File* box), the scene, every
  material it includes - its own and the ones you included from other files - and every picture they name, in a zip. A web address that was
  included stays an address (the engine fetches it when it opens the bundle). Each folder a material comes from keeps its own pictures, so two
  `wood.pbdmat` that both name `grain.png` do not collide.
- Both copy the content of every model a **Reference** (`pbd_ref`) points to into the file (**Inline References**, on by default), as the engine's
  own compiler does, so the file can be handed over without the models; switch it off to keep the `pbd_ref` lines (the models must then be found
  beside the file). A model that is not there is named in a warning.
- The limits are the engine's: a scene of more than 64 MiB of text, a bundle of more than 512 files, or a file of more than 64 MiB in it, is
  refused **before anything is written**, in words. A file is written whole or not at all, and no temporary file is left behind.
- The file browser - opened by the two buttons of the panel or by **File > Export > PBD Binary (.pbdbin)** / **PBD Asset bundle (.pbdasset)** -
  offers the name of your `.blend` with the right extension, in the `.blend`'s folder (`crate.blend` -> `crate.pbdbin`;
  `scene.pbdbin` for a scene that is not saved yet), and keeps the extension right as you type: a name with another extension, or none, gets
  `.pbdbin` / `.pbdasset` (Blender remembers the last name you exported: the name of the last `.pbdbin` comes back with `.pbdasset` when you
  pick the other format). A name that is already a file is marked, as with Blender's own exporters. A name with **nothing in front of the extension** (an empty one, a folder, only `.pbdbin`) is refused with "Give the file
  a name" - it used to write a hidden file called `.pbdbin` into the folder Blender runs in. A path that starts with `//` is the `.blend`'s folder.
- A folder, a file, a material, a category or a link group whose name holds a space, a bracket, a brace, a comma or an `=` is written between quotes,
  so that the engine reads it whole (`scene(1).pbdmat` used to be read as `scene`).

**What is checked.** Every export is read back by the engine's own readers and compared, field by field and with the poses at three moments, with the
same scene exported as a plain `.pbd`; the materials and pictures that come out of a `.pbdasset` are loaded by the engine and the pictures compared
byte for byte with the files they came from; a `.pbdbin` is also opened again by this add-on's importer and exported once more. The same runs on
Blender 4.2, 4.5, 5.0, 5.1 and 5.2. The file browser's two functions (the name it offers and the extension it keeps) are called the way Blender calls
them, and the panel is drawn to see that each button asks for its own format. **Not run by anyone yet**: the file browser window itself, which a
script cannot open (a script gives the button its path).

One button still runs Java: **Add to tileGeometry.txt**, which needs the Gradle project for its classpath. The add-on now looks for a Java the
classes can run on - the release they were built for is read from the class file - in this order: `PBD_JAVA` (the path of a `java` program or of a
JDK folder), `JAVA_HOME`, the `PATH`, the Javas Gradle fetched for the project (`~/.gradle/jdks`) and the usual install folders of Windows, macOS
and Linux (the highest version of a kind first), asking each one `java -version`. The first that is new enough runs the tool; when none is, the
button says so, with each version it found, instead of printing a Java stack trace.

If clicking a button does nothing at all - or an error you thought was gone comes back, such as `PbdConvertMain failed` (only add-ons up to v0.47
can print it) - you are very likely running an out-of-date copy of this add-on rather than missing a feature. Blender keeps an add-on's code in
memory: a newer zip installed over a running add-on changes the files on disk and leaves the old code running until Blender is restarted, and up
to v0.48.1 switching the add-on off and on again was not enough either (the panel's title then read the NEW version over the OLD code, so the
number alone proved nothing). To update: **Preferences > Add-ons > Install...**, pick the zip, tick *PBD Format Tools*, then **quit Blender
completely and start it again**. What shows that the new copy runs: **File > Export** lists *PBD Binary (.pbdbin)* and *PBD Asset bundle
(.pbdasset)* (v0.48.1 and later), and the first line of the PBD Tools panel reads the version of the zip you installed (`v0.48.2`, see
`__init__.py`'s `bl_info`). From v0.48.2 the add-on lets go of its old modules when it is loaded again, so switching it off and on after an
install runs the new files too (a test installs two copies through Blender's installer and looks at every module Python then holds, on Blender
4.2 to 5.2); a restart remains the way to be sure.

## Upload and import rules

A scene is data from anywhere: the person who opens it did not write the names inside it, and the person who uploads a file sends
it to strangers. So the add-on, the engine and the site follow the same short list of rules, with the same numbers (a test compares
them). None of them is near a real model; a file that reaches one is refused with a message that names the file and the rule.

**What the add-on will fetch or open** (a Reference's source, a material's texture, a `.pbdasset`):

- Only an **`https://`** address, with no user name in it, an ordinary port and a host that is not your own network. A private,
  loopback or link-local address (`192.168.x.x`, `10.x.x.x`, `127.0.0.1`, `169.254.x.x`... in any spelling - dotted, one decimal number,
  octal, hexadecimal, short, or inside an IPv6 address), a name that only means something inside one network, and a name that
  *resolves* to such an address are all refused. A plain `http://` address is refused. Nothing is followed to another address: a
  redirect is a failed fetch. A host other than `pbd.aptheo.fr` is a question put to you first.
- A path on your disk - but not a network path (`\\host\share`: Windows would hand your credentials to that host), not `file:`, `ftp:` or
  another kind of address, and not an alternate data stream.
- At most 64 MB a file and 256 files in one import; a compressed file that would expand beyond 64 MB is cut off. The memory a download
  may take is decided before it starts, so a server that never stops sending ends in a message, not in Blender going down.
- A `.pbdasset` is unpacked all or nothing, and only when every entry is a plain relative path that stays inside the folder: no `..`,
  no drive, no backslash, no name Windows reads as a device (`con`, `nul`, `com1`...), at most 512 entries (64 MB each, 256 MB in all).
- An import counts what it brings: at most **6 levels** of References (a file that includes a file that includes a file...) and
  **50,000 objects** in all. A file that includes itself, or two that include each other, ends at that budget with a message, and the
  references it refused stay as empty placeholders.
- The cache keeps a file whole or not at all: a download cut short is never taken, on the next run, for the file.

**What the Upload button sends.** Two sets of checks, so that you do not wait for 120 MB to be refused for something your own
computer could see.

*Your machine checks first* (nothing is sent when one fails; each message names the file and the rule):

- a **Name**, a **Kind** and at least one **Author** are filled in (the File metadata box);
- at most **40 files**, **30 MB** each and **120 MB** in all, none of them empty; exactly one `.pbd` and at most one `.pbdmat`;
- only **plain file names**: letters, digits, `_` and `-`, one dot, an extension; at most 200 characters; two names that differ only
  in capitals count as one. A file called `my wall (2).png` or `wall.final.png` has to be renamed;
- only these kinds: `.pbd`, `.pbdmat`, `.png`, `.jpg` / `.jpeg`, `.wav`. Others are never sent - scripts and pages (`.php`, `.html`,
  `.js`, `.svg`...), archives (`.zip`, `.gz`), configuration (`.htaccess`, `.ini`), programs;
- only files of YOUR disk: a network path, an address or an alternate data stream that a texture or a sound names is never opened;
- a name that holds bidirectional or invisible characters (the kind that makes a file name read backwards) is shown as escapes in the
  messages, so that what you read is what the file is called.

*The site checks the rest*, and reads every byte again - it believes neither the extension nor the declared type. Its answer is shown
to you with the reason:

- the metadata: Name, Kind and Author at most 80 characters, Origin 300, Description 1000; no `<`, `>`, `"`, backtick or backslash in a
  value; plain ASCII text in the files;
- what a text file may *refer to*: another file of the same upload, a file already in the library (its `https://pbd.aptheo.fr/db/...`
  address) or a `.wav` sound of the engine itself (`light-switch.wav`...). Nothing else: not another site, not a path on your disk, and
  not a material set to *Reference materials/ folder* (that one points into the game's own folder, which the library does not have:
  include the library's copy by its address instead - step 2 of the tutorial text below). **The add-on does not check this one for you
  yet**; the site's answer names the reference it refused;
- the chain of References, expanded once: at most 4 levels under a submission, no cycle, at most 50,000 objects;
- pictures and sounds are rewritten from the pieces the engine needs, so metadata, profiles, thumbnails and trailing data do not
  survive; a `.pbd` is read with the engine's own rules and refused when it says something the format does not know or something too
  big.

A submission is held for review: nothing is public until a moderator accepts it.

## Known limitations

- Blender can't preview a Reference's actual geometry in the viewport
  (it shows as an Empty) — only the engine renders the real content.
- The texture coordinates in the viewport are the engine's (see **Refresh UV
  Mapping**), but the picture is drawn by Blender's shader, not the engine's:
  filtering, lighting and the exact look of a mirrored or repeated picture are
  Blender's.
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
- A light follows a door or a drawer only if something with the same Link Group has a
  Lever Arm or keyframes and does not loop. Which light follows which door is shown in the
  panels, but not in the Blender viewport: the lamp is drawn on or off by the engine.
- **Custom break** only keeps the fields the engine knows (width, the chip numbers, tint,
  sound); a `.pbdmat` read back with a key the add-on does not know loses it on the next
  export.
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

**2. Reuse what already exists.** Before you paint or import a texture, look on the site's search page for a material that already
exists (a `.pbdmat`) and copy its address with the button next to it. In Blender: **File > Import > PBD Materials**, paste the address,
and choose **Include (reference only)**. Your `.pbd` then holds one `include_material` line that points at the library's file instead
of a private copy of the texture, the credit stays with whoever made it, and your asset looks like the rest of the world. This is
exactly the kind of reference the site accepts: the address of a file that is already in the library. It refuses the other kinds - a
path on your disk, another site, and a material set to *Reference materials/ folder*, which points into the game's own folder (the
library has none). Keep **Embed in this .pbd** for a material that really is new, and say so in the **Description** of the file
metadata box.

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

**5b. A lamp the player can switch.** Parent a **Light** to the lamp (or give it the lamp's link group): in the game, **E** on any part of
the lamp switches its light, with a click. Give the light a **Click Sound** to choose the click (a `.wav` you upload with the asset), or leave
it empty for the engine's own.

**6. Test, then upload.** **Export & Visualize** opens the engine on your
file: walk up to the door and pull it. Then click **Upload to pbd.aptheo.fr**: the add-on sends the `.pbd`, its `.pbdmat` and the
pictures and sounds they name as separate files (a `.pbdasset` is for handing a model to somebody yourself; the site does not take
one). Before you do, read "Upload and import rules": file names with letters, digits, `_` and `-` only, `.pbd`, `.pbdmat`, `.png`,
`.jpg` and `.wav` files, nothing else, and every file you refer to either in the upload or already in the library.
