import math
import os

import bpy
import mathutils

from .. import anim_compat
from bpy_extras.io_utils import ExportHelper

# Blender is Z-up, the .pbd format is Y-up (like most real-time/GLSL
# pipelines). The conversion is done by conjugating each local transform
# with this -90 deg rotation around X. Verified independently before this
# file was written: composing the converted local transforms along a
# hierarchy gives exactly the same result as converting the world matrix
# directly (difference on the order of 1e-16).
_AXIS_CONVERSION = mathutils.Matrix.Rotation(math.radians(-90.0), 4, 'X')
_AXIS_CONVERSION_INV = _AXIS_CONVERSION.inverted()


def _to_pbd_matrix(matrix):
    return _AXIS_CONVERSION @ matrix @ _AXIS_CONVERSION_INV


# Default local dimensions of a native Blender mesh for each type
# (verified: all 6 Blender primitives fit in a 2x2x2 box, 2x2x0 for the
# flat ones (plane, disc) - none of them needs an explicit size/radius/
# depth argument for this, it is the native default).
_EXPECTED_LOCAL_DIMS = {
    'plane': (2.0, 2.0, 0.0),
    'sphere': (2.0, 2.0, 2.0),
    'cylinder': (2.0, 2.0, 2.0),
    'cone': (2.0, 2.0, 2.0),
    'cube': (2.0, 2.0, 2.0),
    'disc': (2.0, 2.0, 0.0),
    'torus': (2.0, 2.0, 0.6),
}

# The shader (see pbd.tese) defines its canonical primitives with a
# half-extent of 0.5 (unit 1x1x1 box), while a native Blender mesh has a
# half-extent of 1.0 (2x2x2 box) - without compensation, everything would
# render at exactly half the size visible in Blender. Compensated once
# here, at the source, rather than requiring every user to create their
# primitives in one exact way.
_BLENDER_TO_SHADER_SCALE = 2.0


def _expected_local_dims(prim_type):
    # Fallback matches the 2x2x2 convention every current type follows -
    # not (1,1,1), which predates that convention and would falsely flag
    # any future type left out of the table above by mistake.
    return _EXPECTED_LOCAL_DIMS.get(prim_type, (2.0, 2.0, 2.0))


def _mesh_scale_factor(obj, expected):
    """Ratio (actual mesh dimension / expected canonical dimension) on
    each non-degenerate axis, after removing the object's own scale.
    Comes out to ~1.0 everywhere for a mesh created via Add > Mesh > PBD
    ...; a mismatch flags a mesh that no longer matches the canonical
    shape - for instance resized in Edit Mode (the mesh changed, not
    obj.scale), or created via Blender's native tool whose default sizes
    differ (its default cube is 2x2x2, not 1x1x1)."""
    factors = []
    for i in range(3):
        scale_i = obj.scale[i]
        if abs(scale_i) < 1e-8 or expected[i] < 1e-8:
            continue
        local_dim = obj.dimensions[i] / scale_i
        factors.append(local_dim / expected[i])
    return factors


def _check_mesh_matches_canonical(obj):
    """None if the mesh matches the expected canonical size for its type;
    otherwise an explicit warning message."""
    factors = _mesh_scale_factor(obj, _expected_local_dims(obj.pbd.pbd_type))
    if not factors:
        return None
    avg = sum(factors) / len(factors)
    if any(abs(f - avg) > 0.05 for f in factors):
        return (f"'{obj.name}': the mesh does not match a clean canonical "
                f"{obj.pbd.pbd_type} (factor differs per axis: "
                f"{['%.2f' % f for f in factors]}) - edited in Edit Mode?")
    if abs(avg - 1.0) > 0.05:
        return (f"'{obj.name}': the mesh is about {avg:.2f}x the native "
                f"Blender size expected for a {obj.pbd.pbd_type} - check "
                f"that it wasn't resized in Edit Mode, or that a modifier "
                f"(Bevel, Subdivision...) isn't changing its bounding box.")
    return None


def _format_vec3(v, precision=6):
    return f"({v[0]:.{precision}g}, {v[1]:.{precision}g}, {v[2]:.{precision}g})"


def _pbd_parent_of(obj):
    """The native Blender parent, if it is itself a tagged PBD primitive -
    otherwise None (the object is a root in the .pbd file)."""
    parent = obj.parent
    if parent is not None and getattr(parent, "pbd", None) and parent.pbd.is_pbd_primitive:
        return parent
    return None


def _serialize_keyframes(obj):
    """Reads obj's own Blender keyframes (rotation_euler and location
    f-curves) and returns `keyframe { }` block strings, one per unique
    keyframe time across those curves. Evaluates every curve (not just
    the one that actually has a keyframe point at a given frame) at each
    collected time, so a time where only rotation was keyed still gets a
    consistent, curve-interpolated position rather than a gap - the
    engine's own interpolation (see HierarchyResolver.interpolate) then
    only has to do plain linear interpolation between these already-
    resolved poses, not reproduce Blender's own curve handles/easing.
    Returns an empty list if obj has no animation data at all - the
    normal case, not an error.
    """
    fcurves = anim_compat.get_object_fcurves(obj)
    if not fcurves:
        return []

    rot_curves = [anim_compat.find_fcurve(fcurves, "rotation_euler", i) for i in range(3)]
    loc_curves = [anim_compat.find_fcurve(fcurves, "location", i) for i in range(3)]
    all_curves = [c for c in rot_curves + loc_curves if c is not None]
    if not all_curves:
        return []

    frames = sorted({kp.co.x for c in all_curves for kp in c.keyframe_points})
    fps = bpy.context.scene.render.fps

    lines = []
    for frame in frames:
        time = frame / fps
        rot_rad = [c.evaluate(frame) if c else obj.rotation_euler[i] for i, c in enumerate(rot_curves)]
        loc = [c.evaluate(frame) if c else obj.location[i] for i, c in enumerate(loc_curves)]

        # Reuses the same proven _to_pbd_matrix + decompose path every
        # other rotation in this file goes through, rather than a
        # hand-derived per-component remap of the raw Euler values - a
        # rotation's axis conversion isn't a simple component swap the
        # way a position's is, and getting that wrong by reasoning it out
        # under time pressure is exactly the mistake that caused this
        # project's earlier rotation composition bug (see applyInstanceField's
        # "rot" case on the engine side for that story).
        euler = mathutils.Euler((rot_rad[0], rot_rad[1], rot_rad[2]), 'XYZ')
        local_matrix = mathutils.Matrix.Translation(mathutils.Vector(loc)) @ euler.to_matrix().to_4x4()
        pbd_matrix = _to_pbd_matrix(local_matrix)
        pbd_loc, pbd_rot_quat, _ = pbd_matrix.decompose()
        pbd_rot_deg = [math.degrees(a) for a in pbd_rot_quat.to_euler('XYZ')]

        lines.append(f"  keyframe {{ time={time:.4g} pos={_format_vec3(pbd_loc)} rot={_format_vec3(pbd_rot_deg)} }}")
    return lines


def _serialize_instance(obj):
    # Root -> matrix_world (absolute position); PBD child -> matrix_local
    # (already relative to the parent, Blender's matrix_parent_inverse included).
    has_pbd_parent = _pbd_parent_of(obj) is not None
    matrix = obj.matrix_local if has_pbd_parent else obj.matrix_world
    pbd_matrix = _to_pbd_matrix(matrix)
    loc, rot_quat, scale = pbd_matrix.decompose()
    euler_deg = [math.degrees(a) for a in rot_quat.to_euler('XYZ')]

    if obj.pbd.pbd_type == 'ref':
        # No `_BLENDER_TO_SHADER_SCALE` compensation here - a ref isn't a
        # native-2x2x2-sized mesh, its scale is a plain multiplier on
        # whatever the referenced file's own content already is, so it
        # should pass straight through, not be silently doubled.
        lines = [f"pbd_ref {obj.name} {{"]
        lines.append(f"  source = {obj.pbd.pbd_ref_source}")
        lines.append(f"  pos    = {_format_vec3(loc)}")
        if any(abs(a) > 1e-4 for a in euler_deg):
            lines.append(f"  rot    = {_format_vec3(euler_deg)}")
        if any(abs(s - 1.0) > 1e-4 for s in scale):
            lines.append(f"  scale  = {_format_vec3(list(scale))}")
        if has_pbd_parent:
            lines.append(f"  parent = {obj.parent.name}")
        lines.append("}")
        return "\n".join(lines)

    lines = [f"{obj.pbd.pbd_type} {obj.name} {{"]
    lines.append(f"  pos   = {_format_vec3(loc)}")
    if any(abs(a) > 1e-4 for a in euler_deg):
        lines.append(f"  rot   = {_format_vec3(euler_deg)}")
    scaled = [s * _BLENDER_TO_SHADER_SCALE for s in scale]
    lines.append(f"  scale = {_format_vec3(scaled)}")
    if has_pbd_parent:
        lines.append(f"  parent = {obj.parent.name}")
    lines.append(f"  mat   = {obj.pbd.pbd_material}")
    if obj.pbd.pbd_type in ('cylinder', 'cone') and obj.pbd.pbd_cap != 'both':
        lines.append(f"  cap   = {obj.pbd.pbd_cap}")
    if obj.pbd.pbd_type == 'cube':
        sx, sy, sz = obj.pbd.pbd_cube_smooth_x, obj.pbd.pbd_cube_smooth_y, obj.pbd.pbd_cube_smooth_z
        if max(sx, sy, sz) > 0.001:
            lines.append(f"  smoothX = {sx:.4g}")
            lines.append(f"  smoothY = {sy:.4g}")
            lines.append(f"  smoothZ = {sz:.4g}")
    if obj.pbd.pbd_bend_enabled:
        # pbd_bend_angle is stored/returned in radians (subtype='ANGLE') -
        # the .pbd format's modifier fields are in degrees, matching `rot=`.
        lines.append("  modifier bend { axis=%s angle=%.4g }" % (
            obj.pbd.pbd_bend_axis.lower(), math.degrees(obj.pbd.pbd_bend_angle)))
    if obj.pbd.pbd_shear_enabled:
        lines.append("  modifier shear { factorX=%.4g factorY=%.4g factorZ=%.4g }" % (
            obj.pbd.pbd_shear_factor_x, obj.pbd.pbd_shear_factor_y, obj.pbd.pbd_shear_factor_z))
    if obj.pbd.pbd_taper_enabled:
        lines.append("  modifier taper { bottomScale=%.4g topScale=%.4g }" % (
            obj.pbd.pbd_taper_bottom_scale, obj.pbd.pbd_taper_top_scale))
    lines.extend(_serialize_keyframes(obj))
    if obj.pbd.pbd_link_group:
        lines.append(f"  linkGroup = {obj.pbd.pbd_link_group}")
    if obj.pbd.pbd_metadata:
        lines.append("  metadata = true")
        if obj.pbd.pbd_container_trigger:
            lines.append(f"  containerTrigger = {obj.pbd.pbd_container_trigger.name}")
    if obj.pbd.pbd_loop_animation:
        lines.append("  loop = true")
    lines.append("}")
    return "\n".join(lines)


def _write_pbdmat(context, pbd_filepath):
    """Writes EMBEDDED-source entries from scene.pbd_materials to <same
    base name>.pbdmat next to the .pbd file. SHARED-source entries are
    skipped here entirely - they're not duplicated into a per-scene
    file, they get their own include_material line pointing straight at
    the project-wide materials/NAME.pbdmat instead (see material_source
    on PbdMaterialEntry). Returns a list of include_material targets (0
    or more) - a completely empty catalog, or one where every entry is
    shared, are both normal cases, not errors.
    """
    embedded = [e for e in context.scene.pbd_materials if e.material_source == 'EMBEDDED']
    shared = [e for e in context.scene.pbd_materials if e.material_source == 'SHARED']

    includes = []

    if embedded:
        base, _ = os.path.splitext(pbd_filepath)
        mat_filepath = base + ".pbdmat"
        lines = []
        for entry in embedded:
            lines.append(f"material {entry.name} {{")
            lines.append("  color     = (%.4g, %.4g, %.4g)" % tuple(entry.color))
            lines.append(f"  shininess = {entry.shininess:.4g}")
            lines.append(f"  specular  = {entry.specular:.4g}")
            if entry.texture_path:
                lines.append(f"  texture   = {os.path.basename(entry.texture_path)}")
                if abs(entry.uv_scale - 1.0) > 0.001:
                    lines.append(f"  uvScale   = {entry.uv_scale:.4g}")
            if entry.normal_map_path:
                lines.append(f"  normalMap = {os.path.basename(entry.normal_map_path)}")
            if entry.roughness_map_path:
                lines.append(f"  roughnessMap = {os.path.basename(entry.roughness_map_path)}")
            if entry.displacement_map_path:
                lines.append(f"  displacementMap = {os.path.basename(entry.displacement_map_path)}")
                lines.append(f"  displacementScale = {entry.displacement_scale:.4g}")
            if entry.reflectivity > 0.001:
                lines.append(f"  reflectivity = {entry.reflectivity:.4g}")
            if entry.transparency > 0.001:
                lines.append(f"  transparency = {entry.transparency:.4g}")
            lines.append("}")
            lines.append("")
        with open(mat_filepath, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        includes.append(os.path.basename(mat_filepath))

    if shared:
        materials_dir = _resolve_materials_dir(context)
        pbd_dir = os.path.dirname(os.path.abspath(pbd_filepath))
        for entry in shared:
            shared_path = os.path.join(materials_dir, f"{entry.name}.pbdmat")
            if not os.path.exists(shared_path):
                # Not fatal - the exported .pbd just won't find this
                # material until the file shows up (a scrape/conversion
                # run away from finishing, say) - report it rather than
                # silently reference a path that doesn't resolve.
                print(f"[PBD export] WARNING: shared material '{entry.name}' not found at {shared_path}")
            rel = os.path.relpath(shared_path, pbd_dir)
            includes.append(rel.replace(os.sep, "/"))

    return includes


def _resolve_materials_dir(context):
    """The project-wide materials/ folder, from scene.pbd_project_root
    if set (see materials.py's register()), else a best-effort guess
    relative to this .py file's own location (tools/blender-addon/pbd_tools/
    -> ../../../src/main/resources/materials), which only works if the
    add-on is installed from inside a checkout of this project rather
    than copied elsewhere - pbd_project_root is the reliable way, this
    is just a fallback so a missing setting doesn't hard-fail."""
    root = context.scene.pbd_project_root
    if root:
        return os.path.join(root, "src", "main", "resources", "materials")
    guessed = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "..",
                                             "src", "main", "resources", "materials"))
    return guessed


def export_scene_to_pbd(context, filepath):
    tagged = [obj for obj in context.scene.objects
              if getattr(obj, "pbd", None) and obj.pbd.is_pbd_primitive]
    if not tagged:
        return 0, [], 0

    warnings = [msg for obj in tagged if not obj.pbd.pbd_shear_enabled and obj.pbd.pbd_type != 'ref'
                for msg in [_check_mesh_matches_canonical(obj)] if msg]

    # Parents must appear before their children - the .pbd parser knows
    # how to re-sort them (PbdScene.resolveHierarchy), but an already-
    # ordered export stays more readable by hand.
    ordered = []
    seen = set()

    def visit(obj):
        if obj.name in seen:
            return
        parent = _pbd_parent_of(obj)
        if parent is not None:
            visit(parent)
        seen.add(obj.name)
        ordered.append(obj)

    for obj in tagged:
        visit(obj)

    blocks = [_serialize_instance(obj) for obj in ordered]
    keyframe_count = sum(block.count("keyframe {") for block in blocks)

    mat_includes = _write_pbdmat(context, filepath)

    header = "pbd_version 1\n"
    for include in mat_includes:
        header += f"include_material {include}\n"
    if warnings:
        header += "\n# WARNING - please check before using this file:\n"
        for msg in warnings:
            header += f"#   {msg}\n"
    text = header + "\n" + "\n\n".join(blocks) + "\n"

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(text)

    return len(ordered), warnings, keyframe_count


class EXPORT_OT_pbd(bpy.types.Operator, ExportHelper):
    """Exports the scene's PBD-tagged objects to a .pbd file"""

    bl_idname = "export_scene.pbd"
    bl_label = "Export PBD"
    filename_ext = ".pbd"

    filter_glob: bpy.props.StringProperty(default="*.pbd", options={'HIDDEN'})

    def execute(self, context):
        count, warnings, keyframe_count = export_scene_to_pbd(context, self.filepath)
        if count == 0:
            self.report({'WARNING'}, "No PBD-tagged object in the scene")
            return {'CANCELLED'}
        for msg in warnings:
            self.report({'WARNING'}, msg)
        suffix = f" - {len(warnings)} warning(s) above" if warnings else ""
        # Says explicitly when zero keyframes were found, rather than
        # just going quiet about animation - if an object was keyframed
        # in Blender but this reads 0, that's the direct signal something
        # about reading its f-curves failed (see anim_compat.py), not
        # "nothing to report".
        anim_note = f", {keyframe_count} keyframe(s) across all objects" if keyframe_count else ", 0 keyframes found on any object"
        self.report({'INFO'}, f"{count} primitive(s) exported to {self.filepath}{anim_note}{suffix}")
        return {'FINISHED'}


def menu_func_export(self, context):
    self.layout.operator(EXPORT_OT_pbd.bl_idname, text="PBD (.pbd)")


CLASSES = (EXPORT_OT_pbd,)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.TOPBAR_MT_file_export.append(menu_func_export)


def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(menu_func_export)
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
