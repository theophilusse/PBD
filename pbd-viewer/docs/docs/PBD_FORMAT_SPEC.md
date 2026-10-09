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

The Blender add-on's `.pbdbin`/`.pbdasset` buttons (N-panel, PBD tab)
shell out to `PbdConvertMain` and need TWO things set up first, both
now flagged directly in the panel rather than only surfacing as an
error after clicking: **Project Root** (above the buttons) pointing at
this Gradle project's root, and the project having been built at least
once (`./gradlew build` or `./gradlew run`) so `PbdConvertMain` is
actually compiled under `build/`.

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

`PbdFetcher` only fetches without asking from `pbd.mazetrojan.fr`
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
geometry - a placed file's anchor included). Modifiers: `taper` is applied
when voxelizing (verified against the exact frustum volume); `bend`,
`twist`, `curve` and `shear` are not yet - such an instance breaks as its
straight shape.

Both fields are verified round-tripping through every format - plain
`.pbd`, minified, `.pbdbin` and `.pbdasset` - by the kitchen-sink scene of
`RegressionFormatRoundTrip`, which sets non-default values on both.

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

The Blender add-on's own Spot Angle field taught a real lesson worth
remembering for any future `subtype='ANGLE'` property: Blender expects
`min`/`max`/`default` for such a property in RADIANS too, not just the
value itself - writing them as bare degree numbers (as this field
initially did) silently produces a wildly wrong effective range (a
"1 to 89" intended as degrees becomes a 1-to-89-*radian* range, and
Blender clamps up to that radian minimum), not an error, so it's easy
to ship without noticing without actually testing an assigned value
round-trips back out correctly - which is exactly how this was caught.
