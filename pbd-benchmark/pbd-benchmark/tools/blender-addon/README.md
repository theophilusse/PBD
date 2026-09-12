# PBD Format Tools (Blender add-on)

Tested for real on Blender 4.0 in headless mode (register/unregister,
creating the 5 primitives, native Blender parent/child hierarchy, export,
and the native-Blender scale compensation below) before being shipped -
see `docs/renders/` at the repo root for example results rendered from
scenes composed with this add-on.

## Installation

1. Zip the `pbd_tools/` folder into `pbd_tools.zip` (the zip must contain
   the `pbd_tools` folder, not its contents directly at the root).
2. Blender: `Edit > Preferences > Add-ons > Install...`, pick the zip.
3. Enable the "PBD Format Tools" checkbox.

**Common pitfall**: installing only `__init__.py` (instead of the whole
`pbd_tools/` folder, zipped or copied as-is into the add-ons directory)
breaks the relative imports (`from . import properties`) with
`ImportError: attempted relative import with no known parent package` -
Python needs to see the whole package, not just that one file.

**Where to find the tools once enabled**: press `N` in the 3D viewport
and look for the **PBD** tab in the sidebar - one-click buttons to create
each primitive, plus the selected object's PBD settings inline. The same
settings are also available in the *Properties* editor, *Object* tab
(orange square icon), for anyone who prefers that workflow.

## Usage

- **Create a primitive**: either click a button in the `N` > **PBD**
  sidebar tab, or use `Add > Mesh > PBD Plane/Sphere/Cylinder/Cone/Cube`.
  Both create a Blender mesh **at Blender's own native default size**
  (2x2x2 bounding box, 2x2x0 for the plane) - this add-on doesn't force a
  different size, so tagging an object created via Blender's native Mesh
  tools (`Add > Mesh > Cube` etc.) gives exactly the same result as using
  these operators. The exporter compensates for this factor (the shader
  defines its canonical primitives with a half-extent of 0.5, so half
  Blender's native default) when writing the `.pbd` file - no manual math
  needed.
  - **Shortcuts**: right-click any "Create" button in the PBD sidebar tab
    and choose "Assign Shortcut" to bind a hotkey of your choice (or do it
    from `Edit > Preferences > Keymap`). No shortcuts are hard-coded by
    the add-on itself, to avoid clashing with your own keymap.
- **Material**: `wood`, `metal`, and `plastic` (the field in the PBD
  panel, or `Properties > Object > PBD`) have real shading on the GPU side
  (see `MaterialCatalog` in the main project) - matte brown, a sharp
  bright highlight, and a softer colored highlight respectively. Any other
  name is exported as-is and falls back to a neutral grey appearance
  rather than failing.
- **Caps** (cylinder/cone only): `Both` (default), `Top only` (+Y),
  `Bottom only` (-Y), or `None`. Exported as a plain `cap=` field, read by
  `PatchExpander` to decide which patches to generate - no shader change
  needed, since `pbd.tese` already tolerates any subset of a cylinder's
  three parts being absent.
- **Bend**: the only PBD modifier implemented on the GPU side so far.
  Enabling it in the panel both exports a `modifier bend {...}` block and
  adds a matching native Blender **Simple Deform** modifier (mode Bend,
  axis X) to the object, so the viewport mesh actually curves the same way
  the engine will render it - not just a flag with no visible effect. The
  axis choice was checked, not assumed: with `deform_axis='X'`, Simple
  Deform leaves the local X coordinate alone and only rotates Y and Z,
  which is exactly what the engine's `applyBend` does (rotates the Y/Z
  pair by an angle proportional to local Y). The engine currently bends
  around that same axis unconditionally regardless of what a file's
  `axis=` says, so there's no axis choice to exposed here yet either.
- **Hierarchy**: Blender's native parenting (`Ctrl+P`) does the work - a
  child of a PBD primitive is automatically exported with
  `parent = <parent name>`. A child of a non-PBD-tagged object (empty,
  armature...) is treated as a root in the file.
- **Export**: `File > Export > PBD (.pbd)`. Only objects with "PBD
  Primitive" checked are exported; the rest of the scene is ignored
  without needing to be deleted. Before writing the file, the exporter
  checks that each tagged object's mesh matches the native Blender size
  expected for its type (2x2x2, except the flat plane) once `obj.scale`
  is factored out - a mismatch triggers both a Blender warning and a
  comment at the top of the exported file, with the measured factor.

## The pitfall that remains: Edit Mode

The exporter only reads the object's **transform** (position/rotation/
scale), never the actual mesh data. If an object was resized by editing
its vertices in Edit Mode (`S` with the whole mesh selected) rather than
in Object Mode, Blender shows the correct size (the mesh changed) but
`obj.scale` stays unchanged - it's that value, not the visible
dimensions, that goes into the `.pbd` file. The export-time validation
catches this case (factor != 1 relative to the expected native size) and
flags it, but the fix is still manual: always resize in Object Mode (`S`,
or the *Scale*/*Dimensions* fields in the `N` panel's Item tab), never in
Edit Mode.

## Technical detail: axis conversion

Blender is Z-up, the `.pbd` format is Y-up (like most real-time/GLSL
pipelines). The conversion is done by conjugating each object's local
transform with a -90 degree rotation around X. Verified before writing
the exporter: composing the converted local transforms along a hierarchy
gives exactly the same result as converting the world matrix directly
(difference on the order of 1e-16, pure floating-point noise) - so the
exported hierarchy correctly reconstructs the same scene, including when
a parent has a non-uniform scale (in which case Blender already expresses
the child's position in the parent's scaled frame - handled automatically
by `matrix_local`, no manual computation needed on the add-on side).

## Not done yet

- No UI for `taper`, `twist`, or `curve` modifiers - not implemented on
  the GPU side yet either, see the main README's roadmap.
- No resolution of a material name to an actual Blender texture/color for
  the viewport preview - the field is plain text, matched against the
  runtime's `MaterialCatalog` only at render time, not shown in Blender.
