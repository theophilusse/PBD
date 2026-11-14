# PBD Format Specification

PBD (Primitive Based Description) is a plain-text format describing a
3D scene as a set of parametric primitive instances, reconstructed
procedurally on the GPU rather than stored as baked triangle meshes.

This spec describes the format as implemented by `pbd.format.PbdParser`
/ `PbdSerializer`. Where something is a deliberate design choice with a
reason behind it, that reason is included — this is meant to be read by
someone extending the format, not just someone writing one file by hand.

## Lexical notes

- Whitespace (including newlines) and `#`-to-end-of-line comments are
  insignificant between tokens.
- **Fields are NOT comma-separated.** `pos = (0,1,0)` on its own line,
  then the next field on the next line (or just separated by
  whitespace) — never `pos = (0,1,0),`. This is easy to get wrong if
  you're used to a format that DOES use commas between fields (this
  project's own tileGeometry.txt importer/exporter does, for instance,
  which caused a real bug in an early version of the .pbd serializer
  when the two conventions got crossed).
- Numbers inside a vector ARE comma-separated: `(x, y, z)`. Whitespace
  around the commas is optional.
- A bare identifier (no quotes) is used for most string values
  (material names, instance ids, enum-like values). Quote a value
  (`"like this"`) if it contains a space — `name`, `author`, `origin`,
  and `source` typically need this in practice, most others don't.

### How a value is read, and when it must be quoted

A value after `=` is read as the first of these that applies:

1. **A quoted string** `"..."` — everything up to the next `"`. There are
   **no escape sequences**, so a value can never contain a double quote
   (the writers cannot write one, and do not invent an escape).
2. **A balanced group** `( ... )` or `[ ... ]` — read up to the matching
   bracket, spaces, newlines and commas inside included. This is how
   vectors `(1, 2, 3)` and lists `[(0,0,0), (1,1,1)]` are written.
3. **A bare token** — up to the next whitespace or one of
   `{ } ( ) [ ] = ,`. Anything that would not read back as one token
   needs quotes: a value with a space, an `=` or a `,`, a `#` (it would
   start a comment), or no characters at all.

<!-- check: scene -->
```
cube crate {
    pos      = (0, 0.5, 0)
    label    = "front crate"        # a space: quoted
    expr     = "a=b"                # an '=': quoted
    names    = "x,y"                # a ',': quoted
    tag      = "a #1"               # a '#': quoted
    corners  = [(0,0,0), (1, 1, 1)] # a list is read to its closing bracket
}
```

Two deliberate exceptions. The free-text scene metadata (`name`, `kind`,
`author`, `origin`, `description`) is read **to the end of the line** (or
to a `#`) when unquoted, so `kind = static prop container` works; quote it
if it contains a `#`, and a minified file always quotes these (it has no
line ends to stop at). And a `vertexData` / `vertexDataLod<N>` value may be
written bare even though base64 ends in `=` padding: the engine reads it up
to the next whitespace or closing brace (the Blender add-on always did).
Writers always quote it, and so should you.

The writers do the quoting for you: `PbdSerializer` and the Blender
exporter quote a value exactly when a bare one would not read back
identically, so a value survives parse → write → parse in **every** format
(`tools/regression/RegressionFormatRoundTrip.java` checks it, awkward values
included). Numbers are written with at most six significant digits.

## File structure

```
pbd_version 1

name   = "My Wardrobe"        # optional scene metadata, all free-standing at the top
kind   = furniture
author = "Alice"
author = "Bob"                 # repeatable - every author= line is kept, not just the last
origin = "https://example.com/wardrobe.pbd"
description = "A tall oak wardrobe with two working doors"  # optional - server/search.php indexes and searches this if present; NOT yet exposed in the Blender add-on's own File metadata UI (name/kind/authors/origin are, this one isn't yet) - add it by hand-editing the exported .pbd if you want it searchable today

include_material "materials.pbdmat"   # optional, repeatable - see Materials below

curve myPath { ... }            # optional, see Curves below

<primitive_type> <id> {
    ...fields...
}
<primitive_type> <id> {
    ...fields...
}
```

## Scene-level metadata

| Field    | Repeatable | Meaning |
|----------|-----------|---------|
| `name`   | no  | Human-readable label for the file (not the same as any instance's own id). |
| `kind`   | no  | Free-form category (`furniture`, `container`, `decoration`, ...) - not validated against a fixed list, since new kinds keep appearing. |
| `author` | yes | One name per line; every occurrence is kept. |
| `origin` | no  | The URL this file was fetched from, if any - drives caching for network-loaded assets. A file with no `origin` was authored locally. |

All optional; a file with none of these is still valid.

## Primitive types

`plane`, `sphere`, `cylinder`, `cone`, `cube`, `disc`, `torus`, plus the
special types `mesh` (base64 vertex data, see below), `light` (see the end
of this document), `pbd_ref` (place another file's scene here) and `group`.

`group` is an invisible anchor: no geometry, no volume, nothing renders
and nothing voxelizes. It is what a resolved `pbd_ref` becomes in memory,
**and you can write one by hand**: it is the *pivot* — a transform that
other instances are parented to. A door hinges on a pivot placed on its
hinge edge (the Blender add-on's **Create > Pivot (hinge)** writes
exactly this), and the lever arm goes on the pivot, see below. Keep a
pivot's `scale` at `(1,1,1)`: children inherit their parent's scale as
well as its pose.

## Universal instance fields

```
cube door1 {
    pos    = (0, 1, 0)          # required - world (or parent-local) position, instance center
    rot    = (0, 90, 0)         # optional, default (0,0,0) - Euler degrees, intrinsic order applied as rotateZ then rotateY then rotateX (matches Blender's intrinsic XYZ - get this order wrong and single-axis rotations still look right, multi-axis ones won't)
    scale  = (1, 2, 0.1)        # optional, default (1,1,1) - full extent multiplier; every primitive's own native size is documented in PrimitiveRegistry
    mat    = wood                # optional - material name, resolved against include_material'd .pbdmat files or the engine's own built-in fallback names
    parent = someOtherInstance  # optional - pos/rot/scale become relative to the parent's own transform
}
```

`parent` is how anything is **attached** to something else: a handle to a
door, a lamp to a drawer, a latch to the handle. The child's world transform
is `parent × (translate · rotate · scale)`, so a child follows its parent
through every motion the parent makes (keyframes, a lever arm) and inherits
the parent's **scale** as well as its pose. Any type can be a parent or a
child — including `light`, `mesh` and `group`. A child may be written before
its parent (the loader sorts parents first); an unknown parent or a cycle is
an error. At run time `PbdEngine.SceneHandle.setParent(child, parent)`
attaches (or, with `null`, detaches) one; an unknown name or a cycle throws
`IllegalArgumentException` and changes nothing. Attaching can reorder the
instance list, so hold on to names, not indices. Neither direction
compensates the pose: the instance keeps the `pos`/`rot`/`scale` it has, which
are read relative to the new parent (or as world values again after a detach).

## Free-form fields

Any field name not covered above is stored as raw text and interpreted
by whoever consumes the scene (the parser doesn't reject unknown
fields — this is what lets new features land without a parser version
bump). Fields actually used today:

| Field | On | Meaning |
|---|---|---|
| `metadata = true` | any instance | Position/size are kept but nothing renders - an invisible marker volume (a container's interior, an FBX-derived hitbox). |
| `linkGroup = name` | keyframed or lever-arm instances | Every instance sharing this exact group name opens/closes together from one click or one drag (twin wardrobe doors). A locked member stays where it is while the others move. |
| `loop = true` | keyframed or lever-arm instances | The motion ping-pongs continuously instead of click-to-open/close (a fan, a pendulum); clicking or grabbing has no effect, and a looping arm never plays its open/close sounds. |
| `containerTrigger = doorId` | a `metadata` instance | **Repeatable** - one line per door (keyframed or lever-arm) that can open this container. Contents show as soon as ANY listed door is open - a keyframed door the instant it is clicked, a lever arm once its value is above 1 % (so a door you are dragging counts) - and are cleared once EVERY listed door is fully closed again, so items never vanish while still visible through the gap. No lines at all falls back to "any open instance in the whole scene." |
| `LOD = n` | any instance, esp. inside a composition | Which level-of-detail tier this instance represents, for a future distance-based swap. |

## Keyframes

<!-- check: scene -->
```
cube door1 {
    pos = (0, 1, 0)
    keyframe { time=0 pos=(0,1,0) rot=(0,0,0) }
    keyframe { time=1.5 rot=(0,90,0) sound=creak.wav }
}
```

- `time` is in seconds.
- `pos`/`rot` are each optional PER keyframe — an omitted one means
  "hold whatever this instance's own base value is," which lets a
  door's keyframes mention only `rot` without repeating its position
  every time. `scale` is optional the same way.
- `rot` in a keyframe is Euler degrees, same convention/axis order as
  the instance-level `rot=`.
- `sound` is an audio file played when playback crosses this keyframe's
  time (and when a click starts the motion from a keyframe that has one).
  Quote a name with a space.
- `channel = name` makes a keyframe's `time` a value along a named scene
  channel (today `humidity`, which rises only while it rains) instead of
  elapsed seconds - a moss patch that grows in the wet. Only instances with
  no parent are channel-driven for now.
- Playback: a click toggles; time runs from the first keyframe's time to
  the last at one second per second; `loop = true` ping-pongs it forever.

Keyframes are the older mechanism for a door. A lever arm (next section)
is the replacement: it needs no baked clip and can be dragged with the
mouse. `java pbd.PbdConvertMain lever <file|folder> <output>` converts a
keyframed door for you - see "Migrating keyframed doors" below.

## Lever-arm

A lever arm is a **two-pose object driven by one number**, the *arm
value*: 0 = closed … 1 = open. Nothing has to be baked: the value can come
from scripted easing (a click), from a **mouse drag** (the player grabs the
door and pulls it), from a script, or later from a physics step. It is the
replacement for keyframed doors, drawers and lids - and unlike a clip, the
door can be left ajar, locked, or read by AI code at any value in between.

<!-- check: scene -->
```
group hinge {                          # a pivot on the door's hinge edge - keep its scale at 1
    pos = (0, 1, 0)                    # this pose is the CLOSED end (arm value 0)
    leverArm {
        openRot    = (0, 90, 0)        # the OPEN end (arm value 1): turned 90 degrees about Y
        speed      = 2.0               # arm units per second - scripted easing only, not a drag
        openSound  = "door creak.wav"  # once, when it leaves the closed end
        closeSound = door_shut.wav     # once, when it comes back
    }
}
cube panel {                           # attached: it swings with the pivot
    parent = hinge
    pos    = (0.5, 0, 0)
    scale  = (1, 2, 0.1)
}
cube handle {                          # so does anything else, of any type
    parent = hinge
    pos    = (0.9, 0, 0.1)
    scale  = (0.1, 0.1, 0.2)
}
```

| Field | Default | Meaning |
|---|---|---|
| `openPos = (x, y, z)` | the instance's own `pos` | Position at arm value 1. Omit it for a pure rotation (a hinge). |
| `openRot = (x, y, z)` | the instance's own `rot` | Euler degrees at arm value 1, same convention and axis order as `rot`. Omit it for a pure slide (a drawer). |
| `speed = n` | `1.0` | Arm units per second for the **scripted** easing (a click, `setOpen`): `speed = 2` is shut-to-open in half a second. Must be > 0. A drag ignores it. |
| `release = snap \| free` | `snap` | What letting go of a dragged arm does. `snap`: it eases on to the nearer end (exactly halfway goes to open). `free`: it stays exactly where it was let go - a swing door left ajar, a valve wheel. |
| `locked = true` | `false` | The arm starts **locked** at `lockAt`: no drag, no `setOpen`, no easing moves it until it is unlocked at run time (`setArmLocked`). It can still be picked, so the game can say "it's locked". |
| `lockAt = n` | `0` | 0..1: the arm value a locked arm is pinned at (0 = shut). |
| `openSound = file` / `closeSound = file` | none | Audio file played once when the arm leaves / returns to its **closed end** - whoever moved it (drag, script or easing). The arm counterpart of a keyframe's `sound=`. Quote a name with a space. |

- **The two poses.** The instance's own `pos`/`rot` is the closed end;
  `openPos`/`openRot` the open end; a value in between lerps position and
  Euler angles linearly, angle by angle: `openRot = (0, 270, 0)` really
  turns 270 degrees, and a swing about two axes at once follows the
  straight interpolation of its angles, not a great circle. (The Blender
  add-on only knows the open *orientation*, so it writes the Euler triple
  nearest the closed pose: a swing of more than 180 degrees about one axis
  comes out as its shorter equivalent - hand-write `openRot` for such a
  door.) Either end may be omitted - "hold the base value", as in a
  keyframe.
- **At most one `leverArm` block per instance.** If an instance also has
  `keyframe` blocks, **the keyframes win** (the Blender add-on warns).
- **Anything can hang on an arm.** Every instance attached with `parent`,
  to any depth and of any type (`plane sphere cylinder cone cube disc torus
  mesh light group`, a placed `pbd_ref` too) moves with it, and grabbing
  any of them with the mouse grabs the arm. A lamp on a door carries its
  light with it. An attached instance can itself carry an arm - a drawer
  in a door; grabbing the drawer's knob pulls the drawer, not the door.
- **Put the arm on a pivot.** A `group` placed on the hinge edge, scale
  `(1,1,1)`, with the panel and the handle parented to it: the pivot turns
  about the hinge, and no scale leaks into the children. (An arm directly
  on a panel turns the panel about its own centre.)
- **Twin doors.** Instances sharing a `linkGroup` open, close and drag
  together; a locked member stays put while the others move.
- **Looping.** `loop = true` on the instance turns the arm into a
  decorative ping-pong (a fan); it cannot be grabbed and never clicks.
- **The facade link.** An instance with an arm reports the arm value to the
  rest of the engine: it counts as *open* above 1 %, *fully open* from 99 %,
  *fully closed* up to 1 % - so a container's contents (`containerTrigger`)
  appear when a **dragged** door opens and clear when it is shut.
- **Reading the value.** AI and modding code call
  `pbd.app.Main.getArmValue(id)` (0..1, `NaN` for no arm or no scene;
  `isArmLocked` / `setArmLocked` next to it) or, with a renderer in hand,
  `PbdRenderer.getArmValue / setArmValue / setOpen / releaseArm`. A zombie
  can tell a door ajar at 0.3 from one that is shut.
- **Every format.** `.pbdbin` and `.pbdasset` carry the block with no extra
  schema (they are the minified and the pretty text, compressed or zipped).
  The Blender add-on writes and reads it, and shows the arm's poses (it has
  no drag and no physics: the engine does). Verified field by field by
  `RegressionFormatRoundTrip` and against Blender's own matrices by
  `tools/regression/blender_addon_check.py`.

More shapes - a drawer that slides and stays where it is left, a chest lid
locked shut until a script unlocks it:

<!-- check: scene -->
```
cube drawer {
    pos = (0, 0.4, 0)
    scale = (0.8, 0.2, 0.9)
    leverArm { openPos = (0, 0.4, 0.7)  release = free }
}
cube chest_lid {
    pos = (3, 1, 0)
    leverArm { openRot = (-100, 0, 0)  locked = true }
}
```

**Not in the format**: a real physics engine behind the drag (inertia,
collisions) - the drag today is geometric: the arm takes the value that puts
the grabbed point closest to the player's aim; and a sound for pulling on a
locked arm.

### Migrating keyframed doors

`java pbd.PbdConvertMain lever <input.pbd | .pbdbin | folder> <output>`
turns the keyframed doors of existing assets into lever arms: for a plain
open/close swing the first keyframe becomes the instance's own pose (closed),
the last becomes `openPos`/`openRot`, `speed` is `1 / duration`, the clip's
`sound` becomes `openSound`/`closeSound`, and the keyframes go. It converts
only what an arm can say exactly - the converted door passes through the same
poses as the clip did - and **reports and leaves alone** a looping clip, a
`channel` or `scale` keyframe, a path that bends or carries a sound halfway,
an instance that already has an arm, a file with a `pbd_ref` (it would be
flattened) and a `.pbdasset`. It never overwrites its input; with a folder it
keeps the relative layout. The output is regenerated from the parsed scene, so
comments and formatting are not kept, and sound *timing* differs slightly (a
keyframe sound fires when the clip crosses its time, an arm sound when the arm
leaves or returns to the closed end).

## Modifiers

<!-- check: scene -->
```
cube leg1 {
    pos   = (0, 0.5, 0)
    scale = (0.1, 1, 0.1)
    modifier bend  { axis=y angle=15 }
    modifier taper { bottomScale=1 topScale=0.5 }
}
```

One instance may carry several `modifier` blocks. Each has a type
(validated against `ModifierRegistry`) and arbitrary key=value params
specific to that type — the parser doesn't need to know a modifier's
own field names, only that a block exists.

## Materials (`include_material`, `.pbdmat`)

```
include_material "wood.pbdmat"
include_material "metal.pbdmat"    # repeatable - accumulates, last include wins only on an actual name COLLISION between files
```

A `.pbdmat` file:

<!-- check: material -->
```
author = Jane Doe                # required for upload (see server/upload.php) - repeatable, same as a .pbd's own author=; unquoted is fine too (reads to end of line, same as name=/kind=/author= on the .pbd side)

material wood {
    color            = (0.5, 0.3, 0.15)
    shininess        = 8
    specular         = 0.1
    texture          = Wood066_Color.png
    normalMap        = Wood066_NormalGL.png
    roughnessMap     = Wood066_Roughness.png
    displacementMap  = Wood066_Disp.png
    uvScale          = 4.0
    reflectivity     = 0.0
    transparency     = 0.0
    displacementScale = 0.02
}
```

All fields but `color` are optional. Texture paths resolve relative to
the `.pbdmat` file's own location, falling back to the project's shared
`textures/` folder if not found alongside it.

### Mapping a texture: scale, mirror, rotation

Three keys of the material block say how its pictures sit on a surface. They act on **every map of the material alike**
(`texture`, `normalMap`, `roughnessMap`, `displacementMap`, so the maps keep lining up) and in **every place the engine draws the
material**: the tessellation shader, the classic mesh renderer, and the voxels a shot object is made of (a shot object keeps its picture
the way it was drawn: scaled, mirrored and turned exactly alike).

<!-- check: material -->
```
material crate {
    color       = (0.6, 0.4, 0.2)
    texture     = Crate_Color.png
    normalMap   = Crate_NormalGL.png
    uvScale     = 2.0         # repeats across a face on both axes (uvScaleU / uvScaleV set one axis; they win over uvScale, in any order)
    uvScaleV    = -2.0        # a NEGATIVE scale mirrors that axis: two repeats along V, upside down
    uvRotation  = 90          # degrees, a multiple of 90: the picture turned a quarter turn counter-clockwise
}
```

- **`uvScale`, `uvScaleU`, `uvScaleV`** - how many times the picture repeats along each axis of the surface (default 1); a negative number
  shows it mirrored on that axis. A value that is not a number is ignored and reported.
- **`uvRotation`** - the picture turned, in **degrees, multiples of 90 only**: `0` (the default, nothing written), `90` (a quarter
  turn counter-clockwise, as seen on the surface with u to the right and v up: the top edge of the picture goes to the left),
  `180` (upside down), `270` or `-90` (a quarter turn clockwise); `360` and beyond, and negative values, count round (`450` is `90`).
  The picture is turned **once, where it is loaded**, so the shader, the classic mesh renderer and the voxels all read the very same
  turned picture - which is also why the angle cannot be free: a turn of 45 degrees would have to be done by the shader at every pixel.
  Anything that is not a multiple of 90 (a thousandth of a degree is forgiven, for an angle that went through a float on its way here),
  not a number, or absurdly large is **ignored - the picture is not turned - and reported**, never thrown: a typo in a material must not
  stop a scene from loading.
- **The order**: the scale (and the mirror) comes first, then the turn - the same order as Blender's Mapping node of type *Point*
  (scale, then rotate). So `uvScaleU = 3` with `uvRotation = 90` is three repeats along the surface's u, whatever the picture's turn.
  Blender's node turns the *coordinates* and the engine the *picture*, which are opposite things: the add-on writes `uvRotation = 90` for
  a node whose Rotation Z is -90 degrees (and its **UV Rotation** row, with the Rotate Left / Rotate Right buttons, does it for you).
- **What is not turned**: the numbers stored in a normal map (the direction each pixel's bump leans) stay as they are, only the arrangement
  of the pixels turns - which is what Blender does as well, so the lighting you see in Blender is the lighting the engine shows. A normal
  map that must lean the other way after a turn is turned in an image editor.

### How a material breaks

What a shot on **G** throws up, and how thin a connection may get before the
piece it holds comes away whole, is a property of the **material** - never of
a primitive's metadata. Every primitive made of `oak` breaks like oak, and
one line of the `.pbdmat` retunes all of them. Ten more optional keys of the
material block describe it (see "Voxel destruction" for what the engine does
with them):

<!-- check: material -->
```
material oak {
    color          = (0.45, 0.28, 0.13)
    breakWidth     = 0.04              # a connection thinner than 4 cm does not hold: the piece comes away whole
    debrisCount    = 200               # chips thrown by a full-size crater
    breakCount     = 70                # chips thrown when a piece parts
    debrisSize     = 0.03              # mean edge of a chip, in metres
    debrisSpeed    = 2.5               # mean launch speed, in metres per second
    debrisLifetime = 5                 # mean life of a chip, in seconds
    debrisGravity  = 0.8               # multiple of gravity
    debrisBounce   = 0.2               # 0 = stays where it lands, 0.95 = keeps nearly all its speed
    debrisColor    = (0.4, 0.3, 0.2)   # tint of the chips; leave it out and they take the colour of the surface that broke
    breakSound     = wood-crack.wav    # played when a piece parts (a file of resources/sounds)
}
```

Every key is optional. What a block leaves out comes from the material's
**name** - exactly, so `Wood` is not `wood`: wood, metal and plastic have a
profile of their own and any other name gets the generic one, so a `.pbdmat`
that only sets `color` still breaks sensibly. The eight numeric keys, their
ranges and the value each profile starts from:

<!-- check: breakdefaults -->
```
key             range           wood    metal   plastic  any other name
breakWidth      0 .. 1          0.030   0.010   0.015    0.020
debrisCount     0 .. 400        160     100     140      120
breakCount      0 .. 400        60      45      55       50
debrisSize      0.001 .. 0.25   0.022   0.014   0.018    0.020
debrisSpeed     0 .. 30         3.0     4.5     3.8      3.5
debrisLifetime  0.1 .. 30       4.0     3.0     3.5      3.5
debrisGravity   -1 .. 5         1.0     1.0     1.0      1.0
debrisBounce    0 .. 0.95       0.25    0.45    0.40     0.35
```

- **Units.** `breakWidth` is a length in metres (the engine's voxels are 5 mm, so
  `0.03` is six voxels); `debrisSize` the mean edge of a chip, also in metres;
  `debrisSpeed` metres per second; `debrisLifetime` seconds; `debrisGravity` a
  multiple of gravity (`0` floats, negative drifts up); `debrisBounce` how much of
  its speed a chip keeps on a hard landing. `debrisCount` is the number of chips
  a crater of the full size (the 30-voxel radius of G) throws - a smaller hit
  throws proportionally fewer, between 15 % and 150 % of it - and `breakCount`
  the number a piece throws when it parts.
- **`breakWidth = 0`** switches the "connection too thin" rule off for the
  material (rubber, say); `debrisCount = 0` or `breakCount = 0` throws nothing.
- **`debrisColor`** is three numbers from 0 to 1, in parentheses. Left out, a
  chip is the colour of the surface where it broke off - a textured wall sheds
  chips of its own texture.
- **`breakSound`** is a file of `resources/sounds`, preloaded with the scene's
  other sounds and played when a piece parts. Quote a name with a space.
- **A value that cannot be read is not guessed at, nor clamped**: not a number,
  outside its range, a fraction for a whole-number key, a colour that is not
  three numbers from 0 to 1 - the default of that key is used and one line
  says so when the file loads. A key that begins with `break` or `debris` and
  is none of the ten is reported too: a typo (`debrisCout`) would otherwise
  quietly leave the default in place.
- **Only the `.pbdmat` carries it.** A `.pbd` and a `.pbdbin` refer to the file
  with `include_material`, and a `.pbdasset` bundles it as it is, so the keys
  reach the engine through every format (checked by `RegressionFormatRoundTrip`:
  its materials set all ten). The Blender add-on keeps them when it reads and
  writes a `.pbdmat` and has the fields in its material box.

## References (`pbd_ref`)

```
pbd_ref table1 {
    source = furniture/table.pbd
    pos    = (2, 0, 0)
    rot    = (0, 45, 0)
    scale  = (1, 1, 1)
}
```

Places another file's entire scene (`.pbd`, `.pbdbin` or `.pbdasset`) at
this transform. The referenced file's own instances become children of an
invisible anchor at this transform (internally a `group`) — resolved by the
parser at load time, so a loaded scene holds the anchor plus copies of every
instance, their ids prefixed `<refId>.` (the same file placed twice cannot
collide). **Everything is copied**: geometry, keyframes, lever arms, lights,
`indestructible`/`hardness`, mesh data, `linkGroup` and container triggers
(both prefixed too, so two placements of the same wardrobe do not open
together and each container still watches its own door), and the referenced
file's own `include_material` lines (carried into the including scene,
rebased to its folder, so its materials are still found when the scene is
converted or bundled - except for a `.pbdasset` source, whose bundled
materials live in a temporary folder). Curves are merged in as they are, not
prefixed. Optional fields of the `pbd_ref` block: `source`, `fallback`, `pos`,
`rot`, `scale`, `mat` (below), `lod`, `category`, and **`parent`** — which
attaches the whole placed file to another instance. `parent = hinge` hangs a
placed handle on a lever-arm door: the file's anchor, and so every instance
that came from it, moves with the arm, and grabbing any of them grabs the
door (`RegressionLeverArmAttach` checks this for `.pbd`, `.pbdbin` and
`.pbdasset` sources). `indestructible` is not a field of the `pbd_ref` block
itself: mark the instances inside the referenced file.

`source` resolves one of three ways, checked in this order:

1. **A full `http://` or `https://` URL** — fetched via `PbdFetcher`
   (Java) / `fetcher.py` (Blender add-on), cached locally by a hash of
   the URL (the engine under `src/main/resources/cache/remote`, the add-on in
   a `pbd_cache/remote` folder next to the `.blend`: two caches, not one), so
   repeat loads don't re-fetch. An optional `fallback = <url>` field is tried if the
   primary URL 404s or is otherwise unreachable, before finally falling
   back to a previously-cached copy if one exists. `fallback` is ignored
   (with a console warning) if `source` isn't itself a URL.
2. **A relative path INSIDE a file that was itself fetched from a
   URL** — resolved as a URL against that parent URL (ordinary
   relative-URL resolution), not as a local path. A composition fetched
   from a URL can reference sibling files by relative path exactly like
   a local one can.
3. **Otherwise, a local path**, resolved relative to the INCLUDING
   file's own location — the original, still-default behavior.

Cycle detection (a `pbd_ref` chain that loops back on itself) applies
uniformly across all three cases.

```
pbd_ref remoteTable {
    source   = https://assets.example.com/furniture/table.pbd
    fallback = https://backup.example.com/furniture/table.pbd
    pos      = (2, 0, 0)
}
```

Blender's exporter can alternatively **embed** a reference's contents
directly (see the add-on's "Embed contents in this file" checkbox):
every instance from the referenced file is copied inline, parented to
an anchor `group` (a pivot - so an embedded reference can carry a lever arm,
and it adds no volume of its own), instead of the file staying external.
Files embedded by older add-on versions used a `metadata`-flagged cube as
the anchor and still load.
Both forms parse to the same in-memory result; embedding just trades a
second file for a larger single one. The add-on's "Test Reference"
button resolves/fetches `source` right now (using the exact same cache
a real load will use) so a broken URL or path surfaces immediately
rather than only when the engine tries to load the scene.

`include_material <path>` (materials, not instances) resolves the same
way for cases 1 and 3 above (URL or local); a `.pbdmat` fetched from a
URL currently has no case-2 equivalent (its own file-relative texture
paths still resolve locally, not as nested URLs) - worth revisiting if
a real composition ever needs it.

## Cube face removal (`faces`)

<!-- check: scene -->
```
cube openCrate {
    pos   = (0, 0, 0)
    scale = (2, 2, 2)
    mat   = wood
    faces = (+y,-z)
}
```

Optional. Lists REMOVED faces as canonical codes (`+x -x +y -y +z -z`,
matching `PatchExpander`'s part numbering: +x=0 -x=1 +y=2(top) -y=3
-z=5 +z=4), comma-separated and parenthesized like every other
multi-value field here (`pos`, `scale`, `rot`) — a bare unquoted comma
is structural to this parser and would truncate the value at the first
one, so `faces=-y,+z` (no parens) is invalid; `faces=(-y,+z)` is
correct. Absent (the common case) keeps all six faces, so every `.pbd`
file written before this existed still round-trips unchanged.

Blender's add-on shows this as six checkboxes labeled in BLENDER's own
axis terms (+X/-X/+Y/-Y/+Z(Top)/-Z(Bottom)) since that's what's visible
in the viewport while modeling; the exporter converts to the codes
above using the verified Blender-Z-up-to-engine-Y-up mapping (Blender
+Z/-Z, its own up axis, are the engine's +Y/-Y "top"/"bottom" — see
`export_pbd.py`'s `_removed_face_codes`).

## Curves

<!-- check: scene -->
```
curve myPath {
    kind   = catmull-rom                      # or bezier (the default if omitted)
    points = [(0, 0, 0), (1, 2, 0), (2, 0, 1.5)]
}
cube petal {
    pos = (0, 0, 0)
    modifier curve { curve=myPath }
}
```

A curve is declared once, at the top level, and referenced by name from any
number of `curve` modifiers (`curve=myPath`) for Frenet-frame-based deformation
along the path (see `PbdCurve`). `points` is one bracketed list of `(x, y, z)`
control points in file order; `kind` is `bezier` (the default) or
`catmull-rom`. Every writer keeps curves (an earlier serializer dropped
them silently - `RegressionFormatRoundTrip` guards that now). Note the
syntax: one `points = [...]` list, **not** repeated `point = ...` lines.

## Formatting: pretty vs. minified

`PbdSerializer` can write either form from the same data:

- **Pretty** — indented, one field per line, spaces around `=`. Easy to
  read and diff.
- **Minified** — every separator trimmed to the minimum a parser can
  still read unambiguously (a single space, never zero — `pbd_version`
  and its value would otherwise merge into one unparseable token).

Both round-trip losslessly through parse → serialize → parse for
everything this spec covers.

## Compiled and bundled formats

Two additional file formats exist alongside plain `.pbd` text, both loadable directly by the engine viewer (`./gradlew run --args="path/to/file"` picks the right reader by extension) and via `PbdEngine.loadAny()`:

- **`.pbdbin`** — the 4 bytes `PBDB`, a version byte (1), then a gzip stream of the minified `.pbd` text (see `PbdBinFormat`). Not a separate binary schema: the same `PbdSerializer` minified output, just compressed - so it carries every field the text format has, lever arms included, with no schema of its own to keep in step. Smaller and faster to load than plain text; not meant for hand-editing. It is **not** self-contained for materials: its `include_material` lines are relative paths, looked up next to the `.pbdbin`. `PbdEngine.SceneHandle.saveBin(path)` writes one from any loaded scene.
- **`.pbdasset`** — a zip container bundling a scene together with everything it needs to be self-contained: `scene.pbd`, every `.pbdmat`/texture it references, an optional preview icon (`icon.png`), and a `manifest.txt` with author/date/version/description metadata on top of the `.pbd`'s own scene-level `name`/`kind`/`author`/`origin` fields (see `PbdAssetFormat`). Use this instead of a plain `.pbd` when handing someone one file instead of several linked ones. Build one with `PbdAssetFormat.pack(...)` directly (its manifest and extra-files map need more parameters than a thin facade wrapper would add value over), or with `java pbd.PbdConvertMain asset <scene.pbd> <out.pbdasset> <author> <version> <description>` (what the add-on's button runs). The scene is packed as written, and **every** `include_material` file of it is bundled together with the textures those materials use (URL includes stay URLs). What it does *not* do: bundle the files a `pbd_ref` points to - the `pbd_ref` line stays and the referenced file is not in the zip, so **flatten or embed a reference before bundling** (the add-on's "Embed contents in this file").

Both round-trip losslessly through their respective pack/unpack or write/read pair. This is checked by `tools/regression/RegressionFormatRoundTrip.java`: one scene that uses **every field the format has** (awkward values included) is written as pretty `.pbd`, minified `.pbd`, `.pbdbin` and `.pbdasset`, and every public field of every instance, keyframe, lever arm, modifier, curve and mesh is compared by reflection. A completeness guard fails the test if a field is left at its default by that scene, so a field added to the format tomorrow cannot go uncarried unnoticed.

The Blender add-on's `.pbdbin`/`.pbdasset` buttons (N-panel, PBD tab) write both formats **themselves** (`compiled_export.py`), exactly as the
engine reads them: a `.pbdbin` is `PBDB`, the byte 1 and the gzip of the minified text, and its materials stay files of their own, written beside it under
its name (`crate.pbdbin` and `crate.pbdmat`, with the pictures); a `.pbdasset` is `manifest.txt`, `scene.pbd`, every material it includes and every
picture they name, each material with its pictures beside it. The models a `pbd_ref` names are copied into the file, as `PbdParser` does when it loads
one. Nothing here needs Java, Gradle or a folder to save in. The limits are `PbdLimits`' (64 MiB an entry, 512 entries, 256 MiB in all, the entry-name
rules), refused before the first byte is written; `addon_compiled_check.py` has the engine read what the add-on wrote and compares the names it accepts
with `PbdLimits.entryTarget` for every one of a list of awkward ones. The command-line converters (`PbdConvertMain bin` / `asset`) stay, for scripts.

A value that the scene reader (`PbdParser.readRawValue`) would cut short is written between quotes: a bare value ends at a space, a tab or one of
`{ } ( ) [ ] = ,`, and a `#` starts a comment. The engine's writer does this (`PbdSerializer.valueToken`); the add-on's exporter used the narrower rule
of the free-form scene-level fields (a space, a tab, a `#`) for a category, a link group, a material, a reference's address and an `include_material` line
until v0.48.0, and `scene(1).pbdmat` was read as `scene` - the engine refused the file. It now follows `valueToken` (`compiled_export.quote_word`).

## Scene description files (`.pbdscene`)

A fourth kind of file the viewer opens, and the one for **many models at once**: a `.pbdscene` says *which models stand where*
(a row of lockers, a ring of pillars, a field of trees, every model of a folder) and, if you want, *what happens while the scene
plays* (a camera path, keys pressed at set times, doors opened, the time of day). A hundred models are a hundred lines, a stress test of
ten thousand is one line, and the presentation video of every model you made is one file and one take.

It is a **shorthand, not a new scene format**. Every placement is expanded to an ordinary `pbd_ref` block (see *References* above), and
the engine reads the result like any other `.pbd`: nothing else in the engine knows the format exists. A placed model is a group whose
instances are prefixed with the placement's name (`locker_3.door`), exactly as a `pbd_ref` has always done. Two commands show what a
scene file stands for without opening a window:

```sh
java pbd.PbdConvertMain expand showroom.pbdscene showroom.expanded.pbd   # the PBD text the scene file expands to
java pbd.PbdConvertMain check  showroom.pbdscene                         # load it completely and say what it holds, or what is wrong (exit status 1)
```

`check` reports the instances and the memory they take, and for a show its length, its keys and events, and whether it ends by
itself. To play a scene, open the file in the viewer like any other (`./gradlew run --args="path/to/showroom.pbdscene"`); the extension is
read whatever its case.

The first comment line of the examples below, `# files:`, lists the files an example expects next to it: the check of this document
creates them (every model as a small stand-in with two parts, a body and a door) and loads the example through the engine.

<!-- check: scene-file -->
```
# showroom.pbdscene - every kind of placement, in a few lines.
# files: models/locker.pbd models/desk.pbd models/props/crate.pbd models/props/barrel.pbd
scene    name="Showroom" limit=5000                  # optional: a name, and how many instances the scene may hold
materials file=materials/showroom.pbdmat             # an include_material line (repeatable)
ground   floor size=60 mat=concrete                  # a slab to stand on; not destructible unless told (destructible=true)
place    desk   model=models/desk.pbd pos=(0,0,-4) rot=(0,90,0) scale=1.5
row      locker model=models/locker.pbd count=10 step=(1.2,0,0) pos=(-6,0,5)           # locker_1 ... locker_10
grid     crate  model=models/props/crate.pbd count=(4,2,3) step=(1.1,1,1.1) pos=(10,0,0)  # 4 along x, 2 up, 3 along z: 24 crates
ring     pillar model=models/props/barrel.pbd count=8 radius=12 face=center             # eight, their fronts toward the middle
scatter  grass  model=models/props/barrel.pbd count=40 area=(-50,-50,50,50) seed=42 yaw=random scaleRange=(0.8,1.3)
folder   gallery dir=models recursive=true layout=row spacing=3 pos=(-20,0,20)         # every model file below models/
```

### The lines of a scene file

One directive per line: the directive, then words with no `=` (the id of a placement, the time of an `at` line), then `key=value`
pairs. A `#` outside quotes and parentheses starts a comment; blank lines mean nothing. A file is read as text (UTF-8; a leading byte
order mark is dropped); `\n`, `\r\n` and `\r` all end a line.

- A **value** is a bare word, a `"quoted text"` (spaces allowed) or a `(tuple, in, parentheses)` (spaces allowed; parentheses do not nest).
  There are no escapes: a value cannot hold a quotation mark, which is why it cannot carry anything out of its place (below).
- A **number** is a plain decimal (`12`, `-0.5`, `1e3`; not `0x10`, not `NaN`) of magnitude at most a million. A **whole number** is
  digits only, nine at most. `scale=2` means `scale=(2,2,2)`, as in a `.pbd`; a scale of 0 on any axis is refused (a negative scale
  mirrors the model).
- A **name** (the id of a placement, a material, a category) starts with a letter and holds letters, digits, `_` and `-`, 64 characters at
  most. It has no dot: the dot is what separates a placement from the parts of its model (`locker_3.door`).
- A **path** (`model=`, `file=`, `dir=`) is a file or folder of this disk, relative to the folder the scene file is in, 2,048 characters
  at most. It follows the rule every local `pbd_ref` follows: no network path, no address, no `:` other than the one of a drive letter, no
  control character. A scene file never fetches anything: to place a model from the library, download it first.
- **Every mistake says its line** (`scene line 7: count must be between 1 and 1000000, got 0`). A key the directive does not have is an
  error that lists the keys it has, a key written twice is an error, and a directive that does not exist is an error that lists the
  directives: a scene file is written by hand and a misspelt `stpe=` must not be silently ignored.

| directive | what it writes | its keys |
|---|---|---|
| `scene` | the name and the instance limit; once, anywhere | `name` (100 characters, no control character), `limit` (1 .. 5,000,000; default 200,000, the limit of every `.pbd`) |
| `materials` | `include_material` | `file` (required) |
| `ground <id>` | one cube, a slab | `size` (required: a number, or `(x, z)`), `pos` (the centre of the **top** face; default the origin), `thickness` (0.2; 0.001 .. 1000), `mat`, `destructible` (false) |
| `place <id>` | one model | `model` (required), `pos`, `rot` (degrees), `scale`, `mat`, `parent`, `lod`, `category` |
| `row <id>` | `count` models, `step` apart | as `place`, with `count` (1 .. 1,000,000) and `step` (a vector), both required |
| `grid <id>` | `count` = `(nx, ny, nz)` models | as `row`, `count` being three whole numbers, `nx * ny * nz` at most 1,000,000 |
| `ring <id>` | `count` models on a circle | `model`, `count`, `radius` (all three required), `center`, `start` (degrees), `face` (`center`, `out`, `none`), `turn` (degrees), `scale`, `mat`, `parent`, `lod`, `category` |
| `scatter <id>` | `count` models at random places | `model`, `count`, `area` (`(x0, z0, x1, z1)`), `seed` (0 .. 999,999,999) - all four required - `y`, `yaw` (`random` or degrees), `scale` or `scaleRange` (`(min, max)`; not both), `mat`, `parent`, `lod`, `category` |
| `folder <id>` | one model per model file of a folder | `dir` (required), `recursive`, `match`, `layout` (`grid`, `row`, `ring`), `spacing` (4), `columns`, `radius`, `pos`, `face`, `scale`, `mat`, `parent`, `lod`, `category` |
| `show` | what the show does as a whole (below) | `overlay`, `vsync`, `report`, `warmup` |
| `at <time> <action>` | one step of the show (below) | by action |

`mat`, `parent`, `lod` and `category` are the `pbd_ref` fields of the same name: `mat` overrides the material of everything the model
holds, `parent` hangs the placement on another instance of the scene (a handle on a door), `lod` is a whole number 0 .. 100.

**Conventions** (the places where two tools could differ, and so the places the checks pin):

- **Ids.** `row`, `grid`, `ring` and `scatter` name their placements `<id>_1`, `<id>_2` ... counted **from 1**, in the order described
  below. The id of a directive is used once in a file (a second `locker` is an error on its own line), and the ids a directive makes must
  not collide with another's either (a `row a` makes `a_1`, so a `place a_1` is a load error that names the id).
- **The front of a model is its +Z side.** A placement with no rotation looks the way its model does; `rot=(0,90,0)` turns the front to +X.
  `face=` below is about that side.
- **`grid`** counts `x` first, then `y`, then `z`: with `count=(4,2,3)`, `crate_1` .. `crate_4` are the first line along x, `crate_5` ..
  `crate_8` the line above it, and `crate_9` is the first one at the next z.
- **`ring`** puts model *i* (from 0) at the angle `start + 360 i / count` degrees, **measured from +Z toward +X**: at
  `center + radius * (sin a, 0, cos a)`. The first model of a ring is therefore on the +Z side of its centre, and the third of eight is
  on its +X side. `face=center` (the default) turns the front toward the centre (yaw `a + 180`), `face=out` away from it (yaw `a`),
  `face=none` leaves it alone; `turn` is added to that, and a yaw is written in (-180, 180].
- **`scatter`** draws the `x`, the `z`, the yaw and the scale of each placement from a small fixed generator (SplitMix64, written out in
  the code and not borrowed from the platform): the same file gives the same field on every machine and every Java version. **Four
  numbers are drawn for every placement, used or not**, so changing `yaw=` or `scaleRange=` moves no tree, and a larger `count` only
  adds trees. A `scaleRange` scales the three axes alike.
- **`folder`** lists the model files (`.pbd`, `.pbdbin`, `.pbdasset`, in any case) of `dir`, and with `recursive=true` of the five folders
  below it, one inside the other, and no deeper. They are placed in the order of their path below `dir`, compared without case. The id
  of each is `<id>_<file name without its extension>`, every character that is not a letter, a digit, `_` or `-` made `_`, the whole cut at
  56 characters, with `_2`, `_3` ... added when two would share one. A scene description file is not a model and is not listed. A link to
  a model file is followed; a link to a folder is not entered. `match` is a file name pattern of letters, digits, `.`, `_`, `-`, `*` and
  `?`. The layouts: `row` along +X; `grid` in lines of `columns` models (default: the square root of the count, rounded up) along +X, then
  along +Z; `ring` of `radius` (default: wide enough for `spacing` between neighbours) around `pos`, facing as a `ring` directive does.
  A folder with no model file in it is an error that says so.
- **`ground`** is a cube whose top face is at `pos`: a floor to stand on at height 0 needs no arithmetic.
- **Counting.** Placements are counted as the file is read: the line that brings their number past the limit is refused at its own line,
  before the text of any of it is written (every placement is at least one instance). The instances the models hold are counted by the
  parser, which holds the whole scene to the same limit: a model of 40 instances placed 1,000 times is 41,000 instances.

<!-- check: constants -->
```
pbd.format.PbdLimits.MAX_INSTANCES = 200000
pbd.format.SceneExpander.HARD_MAX_INSTANCES = 5000000
pbd.format.SceneExpander.MAX_PLACEMENTS_PER_DIRECTIVE = 1000000
pbd.format.SceneExpander.MAX_FILE_BYTES = 4194304
pbd.format.SceneExpander.MAX_LINES = 100000
pbd.format.SceneLine.MAX_LINE_CHARS = 4096
pbd.format.SceneLine.MAX_MAGNITUDE = 1000000
pbd.format.SceneExpander.MAX_FOLDER_FILES = 10000
pbd.format.SceneExpander.MAX_FOLDER_DEPTH = 6
```

The memory a scene needs is about **2.6 KB per instance** (`check` measures it on the machine it runs on): 200,000 instances are about half
a gigabyte; the 5,000,000 of the hard limit would need more than 12 GB. The limit is the machine's before it is the format's -
`java -Xmx...` is how to give the viewer more.

### The show: a camera path, keys, doors, the sun

Lines that begin with `show` or `at` describe what happens while the scene plays on its own. A scene file without one has no show: the
camera is yours. With one, the show starts with the first frame.

`show` (once, anywhere; every key optional): `overlay=on|off` (the statistics overlay, on by default; a clean take turns it off),
`vsync=on|off` (waiting for the screen, on by default; **a stress test turns it off**, to see how fast the scene can really go),
`report=<name>.txt` (a report written next to the scene file at the end, below) and `warmup=<seconds>` (default 2; 0 .. 3600: the first
seconds are left out of the statistics, because shaders compile and caches fill during them). `on`, `true` and `yes` mean the same, and
so do `off`, `false` and `no`.

`at <time> <action> ...`, where the time is seconds (`12`, `12.5`) or `minutes:seconds` (`1:30`, the seconds below 60), from 0 to 86,400
(a day). The lines may come in any order.

| action | what it does |
|---|---|
| `camera pos=(x,y,z) look=(x,y,z)` or `look=<id>` | a **key of the camera path**: where the camera is, and what it looks at. `look=<id>` is the centre of everything that instance holds (the box of its geometry and of its children's; the place it stands, for an instance with none), found when the show starts |
| `orbit center=(x,y,z) radius=r height=h for=s` | a circle of camera keys, one every 10 degrees (36 a turn), lasting `s` seconds from the time of the line, looking at the centre or at `look=`; `height` is the camera's y. `turns=` (default 1; negative goes the other way; at most 20) and `start=` (the angle to begin at, degrees, measured as in a `ring`) are optional |
| `key E` (or `G`, `H`) | the key of the game, pressed once; `times=n every=s` presses it `n` times (at most 1,000), `s` seconds apart (0.05 at least). **E** takes an item or switches a light, **G** destroys what is aimed at, **H** hides in a container |
| `click` | a press of the left mouse button (`times=` and `every=` as for a key). It is the click that opens a keyframed door under the crosshair; a lever arm is dragged with the button held, which a script cannot do: use `open` |
| `open <id>`, `close <id>` | **sets** (does not toggle) the state of an animated instance, a keyframed one or one with a lever arm, and of everything that shares its `linkGroup`, with the sounds the door makes. A locked lever arm refuses, and the console says so |
| `time hour=h day=d` | a key of the sun's clock: the hour (0 .. 48; past 24 is the next day) and/or the day of the year (0 .. 365) |
| `quit` | the window closes |

<!-- check: scene-file -->
```
# A take: five lockers, a camera that comes in and goes around them, a door that opens, a day that turns to evening.
# files: models/locker.pbd
row    locker model=models/locker.pbd count=5 step=(1.4,0,0) pos=(-2.8,0,0)
show   overlay=off vsync=off report=stress.txt warmup=2
at 0       camera pos=(0,1.7,8)  look=(0,1.2,0)
at 6       camera pos=(4,1.7,5)  look=locker_3                # the middle of locker_3 and everything it holds
at 0:10    orbit center=(0,0,0) radius=9 height=3 for=20 turns=1
at 12      key E
at 14      open locker_2.door
at 16      close locker_2.door
at 0       time hour=6 day=120
at 30      time hour=20
at 0:32    quit
```

- **The camera path.** Each of the six numbers (where the camera stands, what it looks at) follows its own **monotone cubic** (Fritsch and
  Butland's rule, the PCHIP of scipy): smooth, through every key exactly, and never going past the keys on either side of a stretch, so a camera that holds still
  between two equal keys stays still instead of drifting, and a straight run stays straight. The speed is zero at the first and the last
  key: the take starts and ends at rest. Before the first key the camera stands at the first, after the last at the last. Two camera keys at
  one time are an error (with both lines): no curve joins them. The **direction is derived**, not interpolated: from the position toward
  the point looked at, as a yaw and a pitch in the convention of the free camera, so a path never has a yaw that wraps. The pitch is
  limited to **89 degrees** either way, like the mouse's; a camera that passes exactly above the point it looks at therefore turns its view
  quickly, as every look-at does - keep a path off the vertical of its target.
- **The clock of the show** is the sum of the frame times, **each capped at a quarter of a second by the engine, as for everything else**: a
  machine that is slow plays the same show, more slowly, and after a stall longer than that the show is a little behind the clock on the
  wall. An event happens on the first frame that ends at or after its time, and events at one time happen in the order of the file.
  `quit` ends the show and closes the window.
- **The sun.** Between two keys the hour (or the day) moves in a straight line; before the first key it is the first, after the last it
  stays at the last. A channel with no key keeps the value the game started with. **While a show has keys of the sun, the show sets the
  clock every frame**: the arrow keys do not move it. An hour past 24 carries into the day (hour 30 of day 364 is hour 6 of day 0).
- **Taking over.** W, A, S, D, Space or Left Shift pressed **ends the show at once** (the camera, the events and the sun are yours); the
  report is still written when the window closes. While the player **hides** (H) the engine owns the camera, the path goes on in the
  background, and when the engine lets go the camera glides back to the path in 0.8 seconds (a smooth step, the shorter way round).

<!-- check: constants -->
```
pbd.format.ShowScript.MAX_TIME = 86400
pbd.format.ShowScript.MAX_CAMERA_KEYS = 100000
pbd.format.ShowScript.MAX_CHANNEL_KEYS = 10000
pbd.format.ShowScript.MAX_EVENTS = 100000
pbd.format.ShowParser.ORBIT_KEYS_PER_TURN = 36
pbd.format.ShowParser.ORBIT_MAX_TURNS = 20
pbd.format.ShowParser.MAX_REPEATS = 1000
pbd.render.CameraPath.PITCH_LIMIT = 1.5533430342749532      # 89 degrees, in radians
pbd.render.SceneDirector.BLEND_SECONDS = 0.8
```

### A stress test: the report

A scene file is also how to measure the engine. This one places 2,500 copies of a model, flies over them and quits:

<!-- check: scene-file -->
```
# A forest of 2,500 trees (raise count to (100,1,100) for ten thousand), seen from the air, with the screen's own limit off.
# files: models/tree.pbd
scene   limit=1000000
ground  field size=400
grid    forest model=models/tree.pbd count=(50,1,50) step=(4,0,4) pos=(-98,0,-98)
show    vsync=off report=stress.txt warmup=3
at 0    camera pos=(0,40,-220) look=(0,5,0)
at 60   camera pos=(0,40,220)  look=(0,5,0)
at 62   quit
```

When the window closes, the **report** is printed to the console and, with `report=`, written next to the scene file. Its layout (the
`<...>` stand for what your machine says; the facts above `frames:` are the ones the viewer knows, the lines from `frames:` on come from
the statistics):

<!-- check: report -->
```
PBD stress report
scene: <file name>
renderer: <graphics card> (OpenGL <version>)
instances: <n>
patches: <n>
triangles per frame: <n>
draw calls per frame: <n>
GPU upload: <size>
load (read, expand, parse): <n> ms
upload to the GPU: <n> ms
java heap in use: <size>
vsync: <on|off>
frames: <n> over <s> s
average: <fps> FPS
1% low: <fps> FPS   0.1% low: <fps> FPS   minimum: <fps> FPS
frame time: median <ms> ms   95% <ms> ms   99% <ms> ms   99.9% <ms> ms   worst <ms> ms
slower than 30 FPS: <n> frames   freezes (over 100 ms): <n>
```

- **Frame time** is the true time between two frames, not the capped one the simulation moves by. Frames that end inside the warm-up are
  not counted.
- **Percentiles** are the *nearest-rank* kind: the 99th percentile of 1,000 frames is the 990th and the 99.9th the 999th when they are
  ordered from the fastest to the slowest - a frame that really happened, not an interpolation (the rank is worked out in whole numbers:
  in floating point `99.9 / 100 * 1000` is a hair above 999, and the "99.9th percentile" of a thousand frames would have been the worst
  one). The **1% low** is the frame rate of the frame that only 1% of the
  frames were slower than; the **0.1% low** is the same for 0.1%; the **minimum** is the rate of the worst frame. An average of 60 frames a
  second hides a pause of a quarter of a second; the lows are what show it. A frame the clock could not tell from no time at all counts as
  one a millionth of a second long, so no rate is ever infinite.
- **Slower than 30 FPS** counts the frames over 1/30 s; **freezes** counts the frames over 100 ms.
- At most 2,000,000 frames are kept for the percentiles (4.6 hours at 120 frames a second); after that frames are still counted, and the
  report says the percentiles are those of the first ones.
- **What the draw numbers say.** The viewer draws every instance that has geometry with a draw call of its own, every frame, whether
  it is in view or not, so for a scene of models `draw calls per frame` is about the number of instances, and the cost of a frame grows
  with it and not only with the number of triangles: a forest of ten thousand trees measures the draw path first and the card second. The
  draw loop takes no memory per instance from LWJGL's native stack (it once did, and stopped at about 512 instances); a run of the real
  program on a scene of 2,601 instances is one of the checks (`TEST_PROCEDURE.md`, section I). Culling what is out of view and drawing
  identical models in one call are not done (`ROADMAP.md`, backlog 32).

<!-- check: constants -->
```
pbd.render.FrameStats.MAX_KEPT = 2000000
pbd.render.FrameStats.FREEZE_SECONDS = 0.1
pbd.render.FrameStats.CLOCK_RESOLUTION_SECONDS = 1e-6
```

The file is **only ever a report**. `report=` must be a plain file name ending in `.txt` (a letter or digit first, then letters, digits, `.`,
`_` and `-`; 64 characters at most; not the name of a device such as `CON` or `NUL`), and it is written next to the scene file and
nowhere else. The engine never replaces a file that does not begin with `PBD stress report` (a mistyped name cannot overwrite a document of
yours: it is refused, and the console says so), never writes through a link, and never writes into a folder of that name.

### Why a scene file cannot hurt, and what bounds it

A scene file is meant to be shared, so it is read as hostile input:

- **Bounded everywhere.** A file is 4 MiB at most (checked on the disk before it is read), 100,000 lines, 4,096 characters a line; a
  directive writes a million placements at most and a scene holds 5,000,000 at most; the text a scene expands to is capped below the
  parser's own limit; a folder lists 10,000 files at most; a show holds 100,000 camera keys, 100,000 events and 10,000 keys of the sun per
  channel. Every limit is checked **while reading**, at the line that crosses it, with that line's number.
- **A value can only be a value.** Every number, name and path is checked against a closed form before it is written into the PBD text,
  and a path cannot hold a quotation mark: a value cannot close its own quotation, open a brace, add a line or add a field of its own.
  `RegressionSceneFormat` tries hostile lines and tens of thousands of random combinations of pieces of lines, and holds whatever is
  accepted to the few shapes of line the expansion writes.
- **A scene cannot include a scene.** `model=` names a model (`.pbd`, `.pbdbin`, `.pbdasset`); a `.pbdscene` is refused, so no chain of
  scene files can lead back to itself. The models are only *named* by the expansion: the `pbd_ref` parser opens them, with all its own
  limits (depth, cycles, the budget of files it may open, the rules for addresses).
- **Nothing is fetched, nothing is executed.** There is no address in a scene file, no command, no script; the only thing it can write
  to your disk is the report, by the rules above.
- **The web library does not take them.** `.pbdscene` is not an extension the site's upload accepts (its check lists it among the files
  that are not recognised): a scene file is a local file of yours, and what it places is uploaded as models.

### What a scene file does not do (yet)

A scene cannot place another scene; it cannot fetch a model from an address; it has no captions or titles, no sound track, no partial
opening of a lever arm (`open` goes all the way, `close` all the way back); it does not write the video (record the window with the tool
you like: the take is repeatable, which is what makes that easy); and its camera path has no control over easing beyond resting at the
first and the last key. These are tracked in the roadmap.

## What's NOT yet part of the format

Tracked as future work, not yet implemented: further sound-linked-
keyframe features beyond a single `sound=` field per keyframe, and
moving individual cube EDGES (as opposed to whole faces, which `faces=`
above covers) into a non-axis-aligned hexahedron - would need the
tessellation shader generalized from a fixed ±0.5 cube to free-form
corner positions, not yet done.

The traditional/arbitrary `mesh` primitive (base64-encoded vertex data,
see `PbdMeshData`) IS now fully wired end to end: `PatchExpander`
(contributes zero GPU-tessellated patches - its geometry renders through
the classic-mesh path instead, see `Main.java`'s `meshInstanceRenderers`
- omitting this case used to crash the moment a scene containing one
loaded, before this was actually exercised), the Blender add-on's
**Convert to PBD Mesh** button (any existing mesh object, not just ones
created via Add PBD Primitive), and export/import both ways.

## Critical parser note: quote any value that might contain `=`

`=` is structural to this parser (it's the key/value separator) even
inside an otherwise-bare, unquoted value - `readRawValue()`'s bare-token
branch stops at the first one. Base64 data routinely ends in `=`/`==`
padding, so an unquoted `vertexData = base64:...=` silently truncates at
that padding character and desyncs the rest of the parse (surfaces as
"Expected an identifier" a token or two later, not as an obviously-
related error) - confirmed by reproducing it against a real exported
mesh instance, not just by inspection. Always wrap a base64 value in
quotes: `vertexData = "base64:...="`. `PbdSerializer.java` already did
this correctly; the Blender add-on's independent Python exporter did
not, which is what actually broke every mesh-type export until fixed.
(Since 2026-10-07 the engine's reader also accepts the *bare* form for
`vertexData` and `vertexDataLod<N>` specifically - it reads up to the next
whitespace or closing brace - so an older hand-written file such as the
project's `mushroom.pbd` loads; the add-on's reader always did. `=` stays
structural for every other value.)

## Mesh LOD variants (`vertexDataLod<N>`)

A `mesh` instance's base `vertexData=` is always its LOD 0. Additional,
optional tiers use `vertexDataLod<N> = "base64:..."` (N >= 1, same
binary layout, same quoting requirement as `vertexData` itself - see
this doc's own note on why quoting isn't optional for a base64 value).
The engine picks between them via the existing global -/+ LOD keys (see
ENGINE_DEV_GUIDE.md's own note on the exact mapping) - a lower global
detail setting shows a higher-numbered (lower-detail, by convention)
tier if the instance has one.

Blender's add-on captures/applies these directly on the SAME object
(Capture Current Shape / Apply This Shape, on a mesh-type primitive's
own LOD list) rather than requiring a second, separate visible object
per tier - each tier's geometry lives in a Mesh datablock that is never
linked to its own Object.

## Remote materials: textures fetch from the same remote directory too

When `include_material` resolves to a URL (either directly, or because
it's a relative reference inside a scene that was itself fetched from a
URL - see this doc's own References section), that `.pbdmat`'s own
`texture=`/`normalMap=`/`roughnessMap=`/`displacementMap=` filenames are
now ALSO resolved and fetched as URLs relative to the `.pbdmat`'s own
URL, not treated as local paths. A remote `.pbdmat` referencing
`wood.png` fetches `wood.png` from right next to that `.pbdmat` on the
same server - verified against a real remote fetch (not a mock), and
against the same relative-URL resolution `pbd_ref`'s own nested sources
already use.

## pbd_ref material override (`mat`)

An optional `mat=` field on a `pbd_ref` block overrides EVERY
instance's own `mat=` inside the referenced file, for this one
placement only - the source file itself, and any OTHER reference to
it, are unaffected. Applies to nested refs too (anything the
referenced file itself pulls in via its own `pbd_ref`s), since by the
time this override is applied the whole sub-tree has already been
flattened into plain instances.

## Untrusted-host fetch confirmation

`PbdFetcher` only fetches without asking from `pbd.aptheo.fr`
(this project's own asset host). Anything else - any `pbd_ref` or
`include_material` pointing at a URL on a different host - prints the
exact host and full URL and asks for a y/n confirmation on stdin
before downloading, once per distinct host per run. A scene file can
name any server it wants; nothing about loading one should silently
start pulling from wherever it happens to point.

## Voxel destruction (the G key)

The runtime turns the instance under the crosshair into 5 mm voxels, digs a
rough, solid crater out of it (radius 30 voxels, a hole about 0.30 world
units across) and lets whatever is no longer attached fall as rigid pieces
that land on the floor, the remains and nearby boxes - see
`docs/ROADMAP.md` ("The two checks asked for" and "Known limits of the
destruction world") and `docs/ENGINE_DEV_GUIDE.md`. The format carries two
independent fields, both instance-level:

- `indestructible = true` - this instance is never hit by G and never
  affected by alterations to nearby voxelized geometry. Absent means
  false - never written as `indestructible = false`. It is not a field of a
  `pbd_ref` block: mark the instances inside the referenced file (the
  add-on does not export it on a reference object, and the parser would
  reject it there).
- `hardness = X` / `resistance = X` - optional physical parameters,
  meaningful only alongside `indestructible = true`. Nullable rather
  than defaulted, so "no value given" and "explicitly zero" stay
  distinguishable. hardness is the force threshold before anything
  starts affecting this instance at all; resistance is the total
  sustained damage it takes before an effect shows, a separate axis
  from hardness. **Data only for now**: every format carries them, the
  runtime does not read them yet.

Never aimed at by G, whatever the fields say: `metadata = true` volumes
(a container's invisible interior), `light` and `group` instances (no
geometry - a placed file's anchor included). G voxelizes the
shape the GPU **draws**, with every parameter it is drawn with: `cap` (a cylinder or cone without caps is a
hollow tube, not a solid - a lampshade stays a lampshade), `faces` (a cube with faces removed stays open),
`smooth`, the modifiers `taper`, `bend` and `shear` (verified against the exact frustum volume and the shader's own
numbers), and the scale - a negative scale is a mirror image, a zero scale leaves nothing to voxelize. `twist` and
`curve` do nothing on the GPU yet and so nothing here: such an instance breaks as the straight shape it is drawn as.
A hollow shape gets a wall of 3 voxels (15 mm at the engine's 5 mm voxels; a thin tube is not filled up), a plane
or disc a slab of 8 % of its larger side (at most 0.1 m). An instance that cannot be turned into voxels at all - a
mesh with no volume, an open triangle - is destroyed outright by the hit instead of being refused: it disappears
and throws a burst of particles. Details: `docs/ENGINE_DEV_GUIDE.md`, "Voxelizing what is drawn".

Both fields are verified round-tripping through every format - plain
`.pbd`, minified, `.pbdbin` and `.pbdasset` - by the kitchen-sink scene of
`RegressionFormatRoundTrip`, which sets non-default values on both.

### What a hit throws up

The volume a hit takes out of an object does not just vanish: it is thrown up
as **particles** - little cubes, point masses with a size, a colour and a
life, far lighter than a voxel piece (no mesh of their own, no rigid body),
all drawn with one shared cube. Each starts at a random point *inside* the
removed volume, so the debris comes out of the hole, and is thrown away from
the impact, pushed on along the line of fire with a little lift. How many,
how big, how fast, how long they live, how heavy and how bouncy they are is
the **material's** business (see "How a material breaks"), never the
primitive's:

- **How many.** `debrisCount` is for a crater of the full size; a hit that
  took less throws proportionally fewer - between 15 % and 150 % of it, and at
  least one while the material has any count at all. The crumbs of a piece
  too small to fall are thrown with the crater.
- **Size, speed, life** are the profile's means times a random factor:
  0.6 to 1.4 for the size, 0.5 to 1.5 for the speed, 0.7 to 1.3 for the life.
- **Colour.** The material's `debrisColor` if it sets one, else the colour
  of the surface where the chip broke off - the same function that colours
  the voxel faces, so a textured wall sheds chips of its own texture.
- **Physics.** A chip falls under gravity times `debrisGravity`, loses a
  little speed to the air, and meets the same static surfaces the falling
  pieces do - the floor, the boxes of nearby cubes and cylinders, the voxels
  of what is left of a destroyed instance. It bounces (`debrisBounce`; an
  impact under 0.6 m/s does not), loses speed along a surface by friction,
  comes to rest on a surface that faces up and then costs nothing until
  something under it is destroyed, and shrinks away over the last quarter of
  its life. Chips do not collide with the falling pieces or with each other.
- **At most 1500 particles live at once**; a new one in a full system
  replaces the one closest to the end of its life.
- **Deterministic.** The generator is seeded from the hit, so the same hit
  always throws the same chips.

### The break: a piece held too thinly comes away whole

After the crater is dug, the engine looks at what is left. A connection
narrower than the material's `breakWidth` does not hold: it shatters, the run
of thin voxels is taken out and thrown up as `breakCount` splinters, and the
piece it was holding is a separate piece - which falls. A post shot through so
that a 1 cm sliver still joins its top to its stump no longer stands balanced on
the sliver.

A cell is *thick* when a cube `breakWidth` wide (but never wider than the
object itself on that axis: a 2 cm plank needs its whole 2 cm) fits in the solid and
covers it; what that leaves out is thin. Two kinds of thin cell are **not** a
broken connection and stay: those that were already thin *before* the hit (the
tip of a cone, the staircase on a cylinder - an object is not cut by what it
always was), and thin cells that touch only *one* thick piece (the ragged rim a
crater leaves is attached to that piece). A thin run that joins **two or more**
separate thick pieces is taken out whole, however else those pieces are joined
further away. `breakWidth = 0` turns the rule off. The material's `breakSound`
is played when a break happens. The analysis is bounded (a window over
8 000 000 cells is not analysed, the hit stays as the crater made it and the log
says so).

### Hiding places: doors that conceal and doors that block

A hiding place is a container (`metadata = true` with `containerTrigger`
lines, see the table of free-form fields) at least 0.4 world units on every axis;
the doors it names are *its* doors. A door is a listed instance that can be opened
(it has keyframes or a lever arm) **together with everything that hangs from it**:
the usual door is a group carrying the lever arm, with a panel and a handle as its
children, and a shot lands on the panel, not on the group. A listed instance that
cannot move is scenery, not a door.

A door is **shut** when two things hold, both of which the game knows:

1. it is **fully closed** - not swinging, not ajar: a lever arm at 1 % or less, a
   keyframed door back at its start;
2. it is **unaltered** - no hit has turned the door, or any part hanging from it,
   into voxels, and none of it has been destroyed.

A door that was altered counts as **open**, whatever its arm says and however small
the damage: a dent, a bullet hole, a handle shot off. The hole in a door is a way to
look in, and a door that is a pile of voxels is a way to walk in. (How much of the
door is gone - the share of its face still in the way, the *integrity*, and the side
in metres of the widest square one can look through - is still reported for a HUD or
a smarter AI, but it decides nothing: altered is altered.)

- **Concealing** - someone inside cannot be seen from outside: the place has at
  least one door and **every** door is shut. A player who takes cover behind a door
  that was altered is **visible from the outside**, "on the facade" - the place is not
  concealing.
- **Shut** - nobody can get in or out: the same condition. Two names for two questions
  ("can I be seen?", "can I get in?"); a door that is not shut, ajar or altered, answers
  yes to both. A place with no door at all is never shut and never concealing: there is
  nothing to open.
- **The H key** refuses to take the player in, or out, of a *shut* place - with a
  message that says the door is closed - and refuses a place too small to crouch
  in; anything else goes ahead.

AI and modding code read it from the facade: `Main.isHideoutConcealing(id)`
(true only when the place is concealing right now: a door opened by a drag, or
altered by a G hit, changes the answer at once; false for no scene, an unknown id
or a place with no door), `Main.getHideoutState(id)` (the whole assessment:
number of doors, all closed, whether one was altered, lowest integrity, widest hole,
shut, concealing, and a line saying why - null for no scene or an unknown id), and
`Main.isPlayerConcealed()` (the player is inside a place whose doors conceal - false
for a player in a place whose door a hit altered). Verified by `RegressionHidingRules`.

The numbers this section quotes, each checked against the code that uses it:

<!-- check: constants -->
```
pbd.voxel.DestructionWorld.VOXEL_WORLD_SIZE = 0.005
pbd.voxel.DestructionWorld.CRATER_RADIUS_VOXELS = 30
pbd.voxel.ParticleSystem.MAX_PARTICLES = 1500
pbd.voxel.ParticleSystem.FADE_FRACTION = 0.25
pbd.voxel.DebrisEmitter.MIN_CRATER_FRACTION = 0.15
pbd.voxel.DebrisEmitter.MAX_CRATER_FRACTION = 1.5
pbd.voxel.DebrisEmitter.SIZE_LOW = 0.6              # a chip's size is the profile's mean times SIZE_LOW .. SIZE_LOW + SIZE_SPAN
pbd.voxel.DebrisEmitter.SIZE_SPAN = 0.8
pbd.voxel.DebrisEmitter.SPEED_LOW = 0.5
pbd.voxel.DebrisEmitter.SPEED_SPAN = 1.0
pbd.voxel.DebrisEmitter.LIFE_LOW = 0.7
pbd.voxel.DebrisEmitter.LIFE_SPAN = 0.6
pbd.voxel.ParticleSystem.MIN_BOUNCE_SPEED = 0.6     # an impact slower than this (m/s) does not bounce
pbd.voxel.VoxelFracture.MAX_WINDOW_CELLS = 8000000
pbd.pz.HidingRules.MIN_HIDE_DIMENSION = 0.4
```

## Light sources (`light` primitive type)

No geometry of its own (same category as `pbd_ref`'s anchor) - affects
how OTHER geometry gets lit.

<!-- check: scene -->
```
light bulb {
  pos = (0, 1, 0)
  lightMode = point          # or "spot"
  lightColor = (1.0, 0.9, 0.7)
  lightIntensity = 5.0        # brightness scale, no fixed physical unit
  lightRange = 8.0            # world units - contribution reaches zero at this distance
  lightSpotAngle = 30         # degrees, half-angle of the cone - only meaningful for lightMode=spot, aimed along this instance's own -Z (rot=)
}
```

Verified round-tripping through every format: plain `.pbd`, `.pbdbin`,
and `.pbdasset`, all confirmed with a real instance carrying non-
default values on every field via the real engine loader.

`lightEnabled = false` writes a light that starts switched off; on is the
default and is never written. At run time `toggleLight` / `isLightEnabled` of
the facade switch it.

### A light the player switches with E (`switchSound`)

In the game **E** has two jobs, and the thing under the crosshair says which one: it **takes an item** out of an open
container, or it **switches the light of the model** the crosshair is on - a desk lamp, a wall plate, a ceiling fixture. No
key is needed to make a light switchable; the light only has to belong to what the player looks at. One key is optional:
`switchSound`, the click.

<!-- check: scene -->
```
group reading_lamp {
  pos = (1, 0, 2)
}
cylinder lamp_base {
  parent = reading_lamp
  pos    = (0, 0.03, 0)
  scale  = (0.25, 0.06, 0.25)
}
cylinder lamp_pole {
  parent = reading_lamp
  pos    = (0, 0.6, 0)
  scale  = (0.04, 1.2, 0.04)
}
light lamp_bulb {                       # parented to the lamp: E on the base, the pole - any part of it - switches the bulb
  parent      = reading_lamp
  pos         = (0, 1.25, 0)
  lightRange  = 4
  switchSound = "lamp click.wav"        # optional: a file of the sounds folder; without it, the engine's own click (light-switch.wav)
}
cube hall_plate {                       # a wall plate, far from the lamp it switches: same linkGroup, nothing else needed
  pos       = (4, 1.2, 0)
  scale     = (0.08, 0.12, 0.02)
  linkGroup = hall
}
light hall_lamp {
  pos        = (4, 2.4, 0.3)
  lightRange = 6
  linkGroup  = hall
}
```

- **Whose lights.** A light belongs to **one model**, and E on a part of a model switches that model's lights.
  A *model* is the nearest group above the part whose children carry its id as a prefix - exactly what a `pbd_ref` makes of
  the file it places (one group per placement, every id of the file prefixed with it). Two placements of one lamp are two
  models; a lamp placed on a desk placed in an office is a model inside a model, and a light belongs to the innermost one:
  E on the desk does not switch the lamp on it, E on the lamp does not switch the ceiling light of the office. Where no group
  above a part is a model (a lamp built by hand, as in the example, or a file opened on its own), the model is the whole tree
  the part hangs from: the top of its chain of `parent`s. Lights that share a `linkGroup` with the part aimed at are
  switched with it, wherever they stand - that is how a flat file, with no parents at all, gets a wall switch.
- **The switch** is a room switch: if any light of the model is on, they all go off; otherwise they all go on. The state is
  `lightEnabled` - the flag the lights buffer and the facade (`toggleLight` / `isLightEnabled`) read - so it is also what a
  script sees.
- **What is never switched by hand**: a light that follows a door, a drawer or a lever (previous section) - the door decides -
  and a light is never the thing aimed at. A part that has no light to switch (a wall, a table) is simply not a candidate: the
  press does nothing and the console says why ("parent the light to the lamp, or give both the same linkGroup").
- **Who wins** when an item of an open container and a part of a model are both under the crosshair: **the nearer**
  (measured from the eye along the ray); at the same distance, the item. A part of a model with nothing to switch does not
  compete: an item of a container behind it, or inside it, is still taken.
- **How far**: `REACH` below. **What can be aimed at**: instances with geometry (not lights, groups, references or the
  invisible volumes `metadata = true`); a destroyed object only while some of its cubes still stand.
- **The click** is one sound per press, however many lights it switched: the `switchSound` of the first light (in file order)
  that names one, else `light-switch.wav`. It is a file of the engine's sounds folder named by its file name alone, like a
  keyframe sound or a lever arm's `openSound`; it is decoded ahead of the first press, and the Blender add-on sends it along
  with an upload. A press that switched nothing is silent; one that took an item plays the pickup sound.

<!-- check: constants -->
```
pbd.render.LightSwitch.REACH = 3.5              # metres from the eye
```

- The Blender add-on's light panel has the **Click Sound** field (greyed for a light that follows a door - E never switches
  it) and says what E does; it exports `switchSound` by file name and reads it back. Verified by `RegressionUseKey` (every rule
  above, the model rule on a real `pbd_ref` room, the nearer-wins rule, the sounds) and, through the real parser, by the
  example above: the check of this document asks the engine's own `LightSwitch` which lights each part of it switches.

### A light that switches with a door, a drawer or a lever

No new key: give a light the same `linkGroup` as something that opens and
closes - a door, a drawer, a lid, or a small lever arm used as a wall switch -
and it is **on while that thing is open and off when it is shut**: the fridge
lamp, the wardrobe light, the switch the player flips with the mouse.

<!-- check: scene -->
```
light fridge_lamp {
    pos        = (0, 1.1, 0.2)
    lightMode  = point
    lightRange = 2.5
    linkGroup  = fridge             # the door's group: lit while the door is open
}
cube fridge_door {
    pos       = (0.3, 1, 0.5)
    scale     = (0.6, 1.8, 0.04)
    linkGroup = fridge
    leverArm { openRot = (0, 100, 0)  speed = 2 }
}
light shelf_lamp {                  # nothing in group "shelf" opens: not linked, it keeps its own flag
    pos          = (2, 1.5, 0)
    linkGroup    = shelf
    lightEnabled = false
}
```

- **What counts as a partner** (what the light follows): an instance of the
  same group that is not itself a light, has keyframes or a `leverArm`, and does
  not `loop` (a fan turning forever has no open or closed). With several
  partners - the two doors of a wardrobe - the light is on while **any** of them
  is open. A light whose group has no partner is not linked: its own
  `lightEnabled` stays what the file says.
- **Open** is the same word as everywhere else: a keyframed instance after a
  click, a lever arm above 1 %. A door held half open lights the room.
- **At load** a linked light takes the state of its group (doors load shut, so
  the light is off); the file's `lightEnabled` does not matter for a linked light
  - "link it to the door" is enough, with nothing else to untick. **Afterwards**
  it follows the group's *changes*, not every frame, so a script that switches the
  lamp by hand (`toggleLight`) is not undone a frame later; the next time the door
  opens or closes the lamp follows it again.
- A light's own keyframes or arm do not make it a switch, and a looping instance
  never switches a light.
- The Blender add-on writes `linkGroup` and `lightEnabled` as shown and has
  one-click buttons for all of it - **Add Lever Arm** (Door / Drawer / Lever),
  **Add Light Linked to This**, and the ready-made **Door (hinge)**, **Drawer**
  and **Light Switch + Lamp** (see the add-on guide). Verified by
  `RegressionLightLinks` and by the add-on check, which exports a scene and asks
  the engine's own `LightLinks` which lamp follows which door.

The Blender add-on's own Spot Angle field taught a real lesson worth
remembering for any future `subtype='ANGLE'` property: Blender expects
`min`/`max`/`default` for such a property in RADIANS too, not just the
value itself - writing them as bare degree numbers (as this field
initially did) silently produces a wildly wrong effective range (a
"1 to 89" intended as degrees becomes a 1-to-89-*radian* range, and
Blender clamps up to that radian minimum), not an error, so it's easy
to ship without noticing without actually testing an assigned value
round-trips back out correctly - which is exactly how this was caught.
