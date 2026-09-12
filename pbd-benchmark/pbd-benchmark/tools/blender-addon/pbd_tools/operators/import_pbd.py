"""
Imports a .pbd file (and its include_material .pbdmat, if any) into the
current Blender scene, reconstructing objects with the right type,
transform, material, modifiers, keyframes, and PBD-specific flags
(metadata, linkGroup) - the inverse of export_pbd.py.

The text parser below is a small hand-rolled recursive-descent reader
matching PbdParser.java's own grammar (see that file for the
authoritative spec) - not a wrapper around it, since this runs inside
Blender's own Python with no JVM available. Kept deliberately close to
the Java parser's structure (same field names, same block shapes) so the
two stay easy to compare and keep in sync by hand if the format changes.
"""

import math
import os

import bpy
import mathutils

from .. import anim_compat
from . import export_pbd  # for _BLENDER_TO_SHADER_SCALE and _to_pbd_matrix - see below
from .. import materials as materials_module


# ---------------------------------------------------------------------
# Tokenizing / low-level parsing helpers
# ---------------------------------------------------------------------

class ParseError(Exception):
    pass


class _Reader:
    """Character-index cursor over the whole file text, mirroring
    PbdParser.java's own reader style (skip whitespace/comments, read an
    identifier, expect a specific character) rather than a line-oriented
    approach - this format nests braces and puts multiple fields on one
    line freely, so a line-by-line reader would need most of the same
    logic anyway."""

    def __init__(self, text):
        self.text = text
        self.pos = 0

    def peek(self):
        self._skip_ws()
        return self.text[self.pos] if self.pos < len(self.text) else ''

    def _skip_ws(self):
        while self.pos < len(self.text):
            c = self.text[self.pos]
            if c in ' \t\r\n':
                self.pos += 1
            elif c == '#':
                nl = self.text.find('\n', self.pos)
                self.pos = len(self.text) if nl == -1 else nl + 1
            elif self.text.startswith('//', self.pos):
                nl = self.text.find('\n', self.pos)
                self.pos = len(self.text) if nl == -1 else nl + 1
            else:
                break

    def at_end(self):
        self._skip_ws()
        return self.pos >= len(self.text)

    def expect(self, ch):
        self._skip_ws()
        if self.pos >= len(self.text) or self.text[self.pos] != ch:
            got = self.text[self.pos:self.pos + 20] if self.pos < len(self.text) else '<EOF>'
            raise ParseError(f"Expected '{ch}' at position {self.pos}, got: {got!r}")
        self.pos += 1

    def read_identifier(self):
        self._skip_ws()
        start = self.pos
        while self.pos < len(self.text) and (self.text[self.pos].isalnum() or self.text[self.pos] in '_.'):
            self.pos += 1
        if start == self.pos:
            raise ParseError(f"Expected identifier at position {self.pos}, got: {self.text[self.pos:self.pos+20]!r}")
        return self.text[start:self.pos]

    def read_raw_value(self):
        """A quoted string, a (a,b,c)/[a,b,c] tuple, or a bare word/number
        - read up to (but not past) the next unescaped comma or closing
        brace at THIS nesting level. Mirrors PbdParser.readRawValue: the
        exact delimiter set matters (comma is a value separator inside a
        modifier/keyframe block's single line, e.g. 'axis=x angle=30' has
        no commas at all, but 'pos=(1,2,3)' does inside the parens)."""
        self._skip_ws()
        start = self.pos
        if self.pos < len(self.text) and self.text[self.pos] == '"':
            self.pos += 1
            s = self.pos
            while self.pos < len(self.text) and self.text[self.pos] != '"':
                self.pos += 1
            value = self.text[s:self.pos]
            self.pos += 1  # closing quote
            return value
        depth = 0
        while self.pos < len(self.text):
            c = self.text[self.pos]
            if c in '([':
                depth += 1
            elif c in ')]':
                depth -= 1
            elif c == '}' and depth <= 0:
                break
            elif c in ' \t\r\n' and depth <= 0:
                # a bare word ends at whitespace UNLESS we're still inside
                # a tuple's parens (spaces after commas are common: "(1, 2, 3)")
                break
            self.pos += 1
        return self.text[start:self.pos].strip()


def _parse_vec3(raw):
    inner = raw.strip()
    if inner.startswith('(') and inner.endswith(')'):
        inner = inner[1:-1]
    parts = [p.strip() for p in inner.split(',')]
    return tuple(float(p) for p in parts)


# ---------------------------------------------------------------------
# Top-level .pbd parsing
# ---------------------------------------------------------------------

def parse_pbd_file(filepath):
    """Returns (include_material_filename_or_None, [instance_dict, ...]).
    Each instance dict has: type, id, fields (plain key->raw-string map),
    modifiers (list of {type, params}), keyframes (list of {time, pos, rot})."""
    with open(filepath, 'r', encoding='utf-8') as f:
        text = f.read()
    reader = _Reader(text)
    include_material = None
    instances = []

    while not reader.at_end():
        keyword = reader.read_identifier()
        if keyword == 'pbd_version':
            reader.read_raw_value()
            continue
        if keyword == 'include_material':
            include_material = reader.read_raw_value().strip('"')
            continue
        # Otherwise: TYPE NAME { ... } - covers every primitive type and pbd_ref
        prim_type = keyword
        name = reader.read_identifier()
        inst = _parse_instance_body(reader, prim_type, name)
        instances.append(inst)

    return include_material, instances


def _parse_instance_body(reader, prim_type, name):
    inst = {'type': prim_type, 'id': name, 'fields': {}, 'modifiers': [], 'keyframes': []}
    reader.expect('{')
    while reader.peek() != '}':
        key = reader.read_identifier()
        reader.expect('=') if reader.peek() == '=' else None
        # Some blocks (keyframe) use bare "key { ... }" with no '=' before
        # the brace - check for that before assuming a value follows.
        # "modifier" doesn't hit this case: its grammar is
        # "modifier TYPE { ... }" (a type word always comes between the
        # keyword and the brace), so it falls through to the raw-value
        # read below like any other field, with "modifier" handled
        # specially there once its raw value (the type word) is known.
        if reader.peek() == '{':
            body = _read_brace_block_fields(reader)
            if key == 'keyframe':
                inst['keyframes'].append(_keyframe_from_fields(body))
            continue
        raw = reader.read_raw_value()
        if key == 'modifier':
            # raw is actually the modifier's type word (e.g. "bend"),
            # read immediately before its own '{' - re-read properly:
            mod_type = raw
            reader.expect('{')
            params = {}
            while reader.peek() != '}':
                pkey = reader.read_identifier()
                reader.expect('=')
                params[pkey] = reader.read_raw_value()
                if reader.peek() == ',':
                    reader.pos += 1
            reader.expect('}')
            inst['modifiers'].append({'type': mod_type, 'params': params})
            continue
        inst['fields'][key] = raw
        if reader.peek() == ',':
            reader.pos += 1
    reader.expect('}')
    return inst


def _read_brace_block_fields(reader):
    reader.expect('{')
    fields = {}
    while reader.peek() != '}':
        key = reader.read_identifier()
        reader.expect('=')
        fields[key] = reader.read_raw_value()
        if reader.peek() == ',':
            reader.pos += 1
    reader.expect('}')
    return fields


def _keyframe_from_fields(fields):
    kf = {'time': float(fields.get('time', 0.0))}
    if 'pos' in fields:
        kf['pos'] = _parse_vec3(fields['pos'])
    if 'rot' in fields:
        kf['rot'] = _parse_vec3(fields['rot'])
    return kf


# ---------------------------------------------------------------------
# PBD -> Blender axis conversion (inverse of export_pbd._to_pbd_matrix)
# ---------------------------------------------------------------------

def _to_blender_matrix(pbd_matrix):
    """Inverse of export_pbd._to_pbd_matrix - same axis-conversion
    matrix, applied on the opposite side. _to_pbd_matrix computes
    AXIS @ blender_matrix @ AXIS_INV; undoing that is AXIS_INV @
    pbd_matrix @ AXIS, i.e. swap which side AXIS and AXIS_INV go on. Not
    re-derived by hand here on purpose - see this project's own history
    of getting rotation axis conversions wrong when reasoned out fresh
    under time pressure; reusing the one place that conversion is
    already proven is the safer move."""
    axis = export_pbd._AXIS_CONVERSION
    axis_inv = axis.inverted()
    return axis_inv @ pbd_matrix @ axis


def _pbd_transform_to_blender(pos, rot_deg, scale):
    """pos/rot_deg/scale as parsed from the .pbd text (PBD space) ->
    (blender_location, blender_euler, blender_scale). Builds the full
    TRS matrix in PBD space and decomposes the conjugated result for all
    three at once - mirroring _serialize_instance's own
    pbd_matrix.decompose() on the export side exactly. Scale can't be
    converted as 3 independent numbers the way position's axis remap
    works: it's expressed along the object's OWN rotated local axes, so
    it has to go through the same rotation-aware conjugation as
    position/rotation, not a separate per-component pass - the first
    version of this function did that and silently swapped Y/Z scale on
    any object with a non-trivial rotation, caught by round-tripping a
    rotated test object rather than only checking axis-aligned ones."""
    rx, ry, rz = (math.radians(a) for a in rot_deg)
    rot_matrix = (mathutils.Matrix.Rotation(rz, 4, 'Z')
                  @ mathutils.Matrix.Rotation(ry, 4, 'Y')
                  @ mathutils.Matrix.Rotation(rx, 4, 'X'))
    scale_matrix = mathutils.Matrix.Diagonal((scale[0], scale[1], scale[2], 1.0))
    pbd_matrix = mathutils.Matrix.Translation(mathutils.Vector(pos)) @ rot_matrix @ scale_matrix
    blender_matrix = _to_blender_matrix(pbd_matrix)
    loc, rot_quat, blender_scale = blender_matrix.decompose()
    blender_scale = tuple(s * 0.5 for s in blender_scale)  # inverse of _BLENDER_TO_SHADER_SCALE=2.0
    return loc, rot_quat.to_euler('XYZ'), blender_scale


# ---------------------------------------------------------------------
# Scene reconstruction
# ---------------------------------------------------------------------

_CAP_TYPES = {'cylinder', 'cone'}


def _create_object_for_instance(inst, base_dir):
    prim_type = inst['type']
    fields = inst['fields']
    name = inst['id']

    if prim_type == 'group':
        bpy.ops.object.add_pbd_primitive(prim_type='ref')  # closest existing creator - an Empty
        obj = bpy.context.active_object
        obj.pbd.pbd_type = 'ref'
    elif prim_type not in ('plane', 'sphere', 'cylinder', 'cone', 'cube', 'disc', 'torus'):
        raise ParseError(f"Unknown primitive type '{prim_type}' for instance '{name}' - "
                          "add it to both PrimitiveRegistry.java and this import module together")
    else:
        bpy.ops.object.add_pbd_primitive(prim_type=prim_type)
        obj = bpy.context.active_object

    obj.name = name

    pos = _parse_vec3(fields['pos']) if 'pos' in fields else (0.0, 0.0, 0.0)
    rot = _parse_vec3(fields['rot']) if 'rot' in fields else (0.0, 0.0, 0.0)
    scale = _parse_vec3(fields['scale']) if 'scale' in fields else (1.0, 1.0, 1.0)
    loc, euler, bscale = _pbd_transform_to_blender(pos, rot, scale)
    obj.location = loc
    obj.rotation_euler = euler
    obj.scale = bscale

    if 'mat' in fields:
        obj.pbd.pbd_material = fields['mat']
    if 'cap' in fields and prim_type in _CAP_TYPES:
        obj.pbd.pbd_cap = fields['cap']
    if prim_type == 'cube':
        for axis in ('X', 'Y', 'Z'):
            key = f'smooth{axis}'
            if key in fields:
                setattr(obj.pbd, f'pbd_smooth_{axis.lower()}', float(fields[key]))
    if prim_type == 'ref' or prim_type == 'group':
        if 'source' in fields:
            obj.pbd.pbd_ref_source = fields['source'].strip('"')
    if fields.get('metadata') == 'true':
        obj.pbd.pbd_metadata = True
    if 'linkGroup' in fields:
        obj.pbd.pbd_link_group = fields['linkGroup']
    if fields.get('loop') == 'true':
        obj.pbd.pbd_loop_animation = True

    for mod in inst['modifiers']:
        _apply_modifier(obj, mod)

    for kf in inst['keyframes']:
        _apply_keyframe(obj, kf)

    return obj


def _apply_modifier(obj, mod):
    mtype = mod['type']
    params = mod['params']
    if mtype == 'bend':
        obj.pbd.pbd_bend_enabled = True
        if 'axis' in params:
            obj.pbd.pbd_bend_axis = params['axis'].upper()
        if 'angle' in params:
            obj.pbd.pbd_bend_angle = math.radians(float(params['angle']))  # .pbd stores degrees, pbd_bend_angle (subtype='ANGLE') stores radians
    elif mtype == 'shear':
        obj.pbd.pbd_shear_enabled = True
        for axis in ('X', 'Y', 'Z'):
            key = f'factor{axis}'
            if key in params:
                setattr(obj.pbd, f'pbd_shear_factor_{axis.lower()}', float(params[key]))
    elif mtype == 'taper':
        obj.pbd.pbd_taper_enabled = True
        if 'bottomScale' in params:
            obj.pbd.pbd_taper_bottom_scale = float(params['bottomScale'])
        if 'topScale' in params:
            obj.pbd.pbd_taper_top_scale = float(params['topScale'])
    # twist/curve are documented no-ops engine-side (see roadmap notes) -
    # nothing meaningful to reconstruct in Blender for them yet.


def _apply_keyframe(obj, kf):
    """Inserts a real Blender keyframe at kf['time'] (converted to a
    frame via the scene's own fps) reproducing the imported rotation -
    this feeds back into the SAME rotation_euler f-curves
    _serialize_keyframes/anim_compat already read, so a round-tripped
    file keeps working with the existing keyframe panel, goto/delete
    buttons, and export untouched."""
    scene = bpy.context.scene
    frame = round(kf['time'] * scene.render.fps)
    scene.frame_set(frame)
    if 'rot' in kf:
        _, euler, _ = _pbd_transform_to_blender(
            kf.get('pos', (0, 0, 0)), kf['rot'], (1, 1, 1))
        obj.rotation_euler = euler
    obj.keyframe_insert(data_path='rotation_euler', frame=frame)


def import_pbd_file(context, filepath):
    """Returns (instance_count, warning_list)."""
    warnings = []
    base_dir = os.path.dirname(os.path.abspath(filepath))
    include_material, instances = parse_pbd_file(filepath)

    name_to_obj = {}
    for inst in instances:
        try:
            obj = _create_object_for_instance(inst, base_dir)
        except ParseError as e:
            warnings.append(f"{inst.get('id', '?')}: {e}")
            continue
        name_to_obj[inst['id']] = obj

    # Second pass for parenting - every object must exist first, since a
    # child can be listed before its parent isn't guaranteed by the
    # format (the engine's own PbdScene.resolveHierarchy handles this by
    # sorting, this just needs both ends to exist before linking).
    for inst in instances:
        parent_name = inst['fields'].get('parent')
        if parent_name:
            child = name_to_obj.get(inst['id'])
            parent = name_to_obj.get(parent_name)
            if child and parent:
                child.parent = parent
                # Identity, not parent.matrix_world.inverted(): the
                # parsed pos/rot/scale are already the LOCAL (parent-
                # relative) transform - matching what _serialize_instance
                # exports via matrix_local for a parented object in the
                # first place - so they should combine normally with the
                # parent's own world transform via Blender's usual
                # parent.matrix_world @ matrix_parent_inverse @
                # matrix_basis formula. Setting matrix_parent_inverse to
                # the parent's own inverse cancels the parent transform
                # out entirely instead.
                child.matrix_parent_inverse.identity()
            elif child:
                warnings.append(f"{inst['id']}: parent '{parent_name}' not found in this file")

    # Third pass for the container trigger reference - same reasoning as
    # parenting above: the referenced door object might be listed before
    # or after the storage cube referencing it, so resolve names to
    # actual objects only once every object in the file exists.
    for inst in instances:
        trigger_name = inst['fields'].get('containerTrigger')
        if trigger_name:
            storage_obj = name_to_obj.get(inst['id'])
            trigger_obj = name_to_obj.get(trigger_name)
            if storage_obj and trigger_obj:
                storage_obj.pbd.pbd_container_trigger = trigger_obj
            elif storage_obj:
                warnings.append(f"{inst['id']}: containerTrigger '{trigger_name}' not found in this file")

    if include_material:
        mat_path = os.path.join(base_dir, include_material)
        if os.path.exists(mat_path):
            try:
                parsed = materials_module._parse_pbdmat(mat_path)
                mat_dir = os.path.dirname(mat_path)
                for mname, mfields in parsed.items():
                    entry = context.scene.pbd_materials.get(mname)
                    if entry is None:
                        entry = context.scene.pbd_materials.add()
                        entry.name = mname
                    if 'color' in mfields:
                        entry.color = mfields['color']
                    if 'shininess' in mfields:
                        entry.shininess = mfields['shininess']
                    if 'specular' in mfields:
                        entry.specular = mfields['specular']
                    if 'texture' in mfields:
                        entry.texture_path = os.path.join(mat_dir, mfields['texture'])
                    if 'normalMap' in mfields:
                        entry.normal_map_path = os.path.join(mat_dir, mfields['normalMap'])
                    if 'roughnessMap' in mfields:
                        entry.roughness_map_path = os.path.join(mat_dir, mfields['roughnessMap'])
                    if 'displacementMap' in mfields:
                        entry.displacement_map_path = os.path.join(mat_dir, mfields['displacementMap'])
                    if 'displacementScale' in mfields:
                        entry.displacement_scale = mfields['displacementScale']
                    if 'uvScale' in mfields:
                        entry.uv_scale = mfields['uvScale']
                    if 'reflectivity' in mfields:
                        entry.reflectivity = mfields['reflectivity']
                    if 'transparency' in mfields:
                        entry.transparency = mfields['transparency']
            except OSError as e:
                warnings.append(f"Could not read materials from {mat_path}: {e}")
        else:
            warnings.append(f"include_material '{include_material}' not found next to the .pbd file")

    return len(instances), warnings


class IMPORT_OT_pbd(bpy.types.Operator):
    """Imports a .pbd scene file into the current Blender scene,
    reconstructing every instance's type, transform, material,
    modifiers, and keyframes - the inverse of File > Export > PBD."""
    bl_idname = "import_scene.pbd"
    bl_label = "Import PBD"
    bl_options = {'REGISTER', 'UNDO'}

    filepath: bpy.props.StringProperty(subtype='FILE_PATH')
    filter_glob: bpy.props.StringProperty(default="*.pbd", options={'HIDDEN'})

    def invoke(self, context, event):
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}

    def execute(self, context):
        try:
            count, warnings = import_pbd_file(context, self.filepath)
        except (OSError, ParseError) as e:
            self.report({'ERROR'}, f"Import failed: {e}")
            return {'CANCELLED'}

        for w in warnings:
            self.report({'WARNING'}, w)
        self.report({'INFO'}, f"Imported {count} instance(s) from {self.filepath}")
        return {'FINISHED'}


def _menu_func_import(self, context):
    self.layout.operator(IMPORT_OT_pbd.bl_idname, text="PBD Scene (.pbd)")


CLASSES = (IMPORT_OT_pbd,)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.TOPBAR_MT_file_import.append(_menu_func_import)


def unregister():
    bpy.types.TOPBAR_MT_file_import.remove(_menu_func_import)
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
