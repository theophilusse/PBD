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

## File structure

```
pbd_version 1

name   = "My Wardrobe"        # optional scene metadata, all free-standing at the top
kind   = furniture
author = "Alice"
author = "Bob"                 # repeatable - every author= line is kept, not just the last
origin = "https://example.com/wardrobe.pbd"

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
special types `pbd_ref` (place another file's scene here) and `group`
(the parser's own internal representation of a resolved `pbd_ref` — you
won't write `group` by hand in a source file).

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

## Free-form fields

Any field name not covered above is stored as raw text and interpreted
by whoever consumes the scene (the parser doesn't reject unknown
fields — this is what lets new features land without a parser version
bump). Fields actually used today:

| Field | On | Meaning |
|---|---|---|
| `metadata = true` | any instance | Position/size are kept but nothing renders - an invisible marker volume (a container's interior, an FBX-derived hitbox). |
| `linkGroup = name` | keyframed instances | Every instance sharing this exact group name opens/closes together from one click. |
| `loop = true` | keyframed instances | Keyframes ping-pong continuously instead of click-to-open/close; clicking has no effect. |
| `containerTrigger = doorId` | a `metadata` instance | **Repeatable** - one line per door that can open this container. Contents show once ANY listed door is fully open. No lines at all falls back to "any open instance in the whole scene." |
| `LOD = n` | any instance, esp. inside a composition | Which level-of-detail tier this instance represents, for a future distance-based swap. |

## Keyframes

```
cube door1 {
    pos = (0, 1, 0)
    keyframe { time=0 pos=(0,1,0) rot=(0,0,0) }
    keyframe { time=1.5 rot=(0,90,0) }
}
```

- `time` is in seconds.
- `pos`/`rot` are each optional PER keyframe — an omitted one means
  "hold whatever this instance's own base value is," which lets a
  door's keyframes mention only `rot` without repeating its position
  every time.
- `rot` in a keyframe is Euler degrees, same convention/axis order as
  the instance-level `rot=`.

## Modifiers

```
cube leg1 {
    ...
    modifier bend { axis=y angle=15 }
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

```
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

Places another `.pbd` file's entire scene at this transform. The
referenced file's own instances become children of an invisible anchor
at this transform (internally represented as the `group` type) — this
is resolved by the parser at load time; a `pbd_ref` never appears as a
`group` in a hand-written source file.

`source` resolves one of three ways, checked in this order:

1. **A full `http://` or `https://` URL** — fetched via `PbdFetcher`
   (Java) / `fetcher.py` (Blender add-on), cached locally by a hash of
   the URL under `src/main/resources/cache/remote`, so repeat loads
   don't re-fetch. An optional `fallback = <url>` field is tried if the
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
a `metadata`-flagged anchor cube, instead of the file staying external.
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

```
curve myPath {
    point = (0, 0, 0)
    point = (1, 2, 0)
    point = (2, 0, 0)
}
```

Referenced by a `curve` modifier for Frenet-frame-based deformation
along the path (see `PbdCurve`).

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

- **`.pbdbin`** — gzip-compressed minified `.pbd` text (see `PbdBinFormat`). Not a separate binary schema: the same `PbdSerializer` minified output, just compressed. Smaller and faster to load than plain text; not meant for hand-editing. `PbdEngine.SceneHandle.saveBin(path)` writes one from any loaded scene.
- **`.pbdasset`** — a zip container bundling a scene together with everything it needs to be self-contained: `scene.pbd`, every `.pbdmat`/texture it references, an optional preview icon (`icon.png`), and a `manifest.txt` with author/date/version/description metadata on top of the `.pbd`'s own scene-level `name`/`kind`/`author`/`origin` fields (see `PbdAssetFormat`). Use this instead of a plain `.pbd` when handing someone one file instead of several linked ones. Build one with `PbdAssetFormat.pack(...)` directly (its manifest and extra-files map need more parameters than a thin facade wrapper would add value over).

Both round-trip losslessly through their respective pack/unpack or write/read pair - verified by parsing a scene, writing it in the compiled form, reading it back, and comparing instance counts and scene metadata.

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
