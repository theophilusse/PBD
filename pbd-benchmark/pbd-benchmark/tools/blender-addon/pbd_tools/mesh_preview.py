"""
Direct Python mesh generation/deformation for PBD visual effects that have
no accurate native Blender modifier equivalent - built once here, then
this module is the single source of truth Blender-side (approximated
otherwise only where noted, e.g. bend still uses Simple Deform since that
IS mathematically the same real arc bend as pbd.tese's applyBend).

Every formula here is a direct port of the matching GLSL function in
pbd.tese, cross-checked against it with concrete sample points before
being trusted (see the project's development history) - but ported into
Blender's NATIVE mesh space rather than the shader's canonical space.
Blender's native primitives have a half-extent of 1.0 (a 2x2x2 box) while
the shader's canonical primitives use 0.5 (a 1x1x1 box) - the same reason
the exporter multiplies scale by 2 (see export_pbd.py's
_BLENDER_TO_SHADER_SCALE). Length-like parameters (the smooth radii) get
the same x2 factor; angle-rate parameters (shear's factors) get the
inverse, /2, since the position values they multiply are themselves 2x
larger here - both were checked numerically against the canonical-space
formula before being used below, not assumed.
"""

import math

import bmesh
import bpy

_NATIVE_SCALE = 2.0  # matches export_pbd.py's _BLENDER_TO_SHADER_SCALE


def _blender_to_pbd(v):
    """Same mapping export_pbd.py's _AXIS_CONVERSION applies to transforms,
    here applied directly to a raw vertex position: Blender is Z-up, the
    shader's formulas (and this module's ports of them) assume Y-up."""
    x, y, z = v
    return (x, z, -y)


def _pbd_to_blender(v):
    x, y, z = v
    return (x, -z, y)


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


def _eval_cube_face_flat_native(u, v, face):
    px, py = (u - 0.5) * _NATIVE_SCALE, (v - 0.5) * _NATIVE_SCALE
    h = 0.5 * _NATIVE_SCALE
    if face == 0: return (h, py, -px), (1.0, 0.0, 0.0)
    if face == 1: return (-h, py, px), (-1.0, 0.0, 0.0)
    if face == 2: return (px, h, -py), (0.0, 1.0, 0.0)
    if face == 3: return (px, -h, py), (0.0, -1.0, 0.0)
    if face == 4: return (px, py, h), (0.0, 0.0, 1.0)
    return (-px, py, -h), (0.0, 0.0, -1.0)


def _eval_cube_face_smooth_native(u, v, face, smooth_canonical):
    """smooth_canonical = (sx, sy, sz) in the SAME units the .pbd file /
    the properties panel use (0..0.5 each) - doubled internally to match
    this function's native-space output."""
    flat_pos, flat_nrm = _eval_cube_face_flat_native(u, v, face)
    half = 0.5 * _NATIVE_SCALE
    s = tuple(_clamp(c, 0.0, 0.5) * _NATIVE_SCALE for c in smooth_canonical)

    inner_bound = tuple(half - c for c in s)
    inner_pos = tuple(_clamp(flat_pos[i], -inner_bound[i], inner_bound[i]) for i in range(3))
    offset = tuple(flat_pos[i] - inner_pos[i] for i in range(3))
    offset_len = math.sqrt(sum(c * c for c in offset))

    if offset_len < 1e-6:
        return _pbd_to_blender(flat_pos), _pbd_to_blender(flat_nrm)

    s_safe = tuple(max(c, 1e-6) for c in s)
    scaled = tuple(offset[i] / s_safe[i] for i in range(3))
    scaled_len = math.sqrt(sum(c * c for c in scaled))
    scaled_dir = tuple(c / scaled_len for c in scaled)
    local_offset = tuple(scaled_dir[i] * s[i] for i in range(3))
    pos = tuple(inner_pos[i] + local_offset[i] for i in range(3))

    nrm_raw = tuple(local_offset[i] / (s_safe[i] * s_safe[i]) for i in range(3))
    nrm_len = math.sqrt(sum(c * c for c in nrm_raw)) or 1.0
    nrm = tuple(c / nrm_len for c in nrm_raw)
    return _pbd_to_blender(pos), _pbd_to_blender(nrm)


def build_smooth_cube_mesh(smooth_x, smooth_y, smooth_z, segments=10):
    """Returns (vertices, faces, normals) for a rounded cube in Blender's
    native (half-extent 1.0) space - vertices are NOT welded across face
    seams (each face generates its own edge vertices), which is a fine
    trade-off for a preview mesh and keeps this simple; normals are
    per-vertex, matching the smooth-shaded look the engine produces."""
    smooth = (smooth_x, smooth_y, smooth_z)
    verts = []
    faces = []
    normals = []

    for face in range(6):
        base = len(verts)
        for j in range(segments + 1):
            v = j / segments
            for i in range(segments + 1):
                u = i / segments
                pos, nrm = _eval_cube_face_smooth_native(u, v, face, smooth)
                verts.append(pos)
                normals.append(nrm)
        for j in range(segments):
            for i in range(segments):
                a = base + j * (segments + 1) + i
                b = base + j * (segments + 1) + i + 1
                c = base + (j + 1) * (segments + 1) + i + 1
                d = base + (j + 1) * (segments + 1) + i
                faces.append((a, b, c, d))

    return verts, faces, normals


def write_smooth_cube_mesh(obj, smooth_x, smooth_y, smooth_z, segments=10):
    """Replaces obj's mesh data in place with a freshly generated rounded
    cube matching (smooth_x, smooth_y, smooth_z) - the direct-generation
    approach requested in place of Bevel's single-width approximation,
    since Bevel has no per-axis width concept to match the engine's
    per-axis ellipsoid rounding."""
    verts, faces, normals = build_smooth_cube_mesh(smooth_x, smooth_y, smooth_z, segments)

    mesh = obj.data
    bm = bmesh.new()
    bm_verts = [bm.verts.new(v) for v in verts]
    bm.verts.ensure_lookup_table()
    for f in faces:
        try:
            bm.faces.new([bm_verts[i] for i in f])
        except ValueError:
            pass  # duplicate face, shouldn't happen with this grid, skip defensively
    bm.normal_update()
    bm.to_mesh(mesh)
    bm.free()
    mesh.update()


def _apply_shear_native(pos, factor_x, factor_y, factor_z):
    """Direct port of pbd.tese's applyShear, position only (the preview
    mesh doesn't need custom split normals - Blender recomputes reasonable
    ones from the deformed geometry itself). `pos` is in Blender's native
    space (Z-up); converted to the shader's Y-up convention before the
    formula runs, then back, since the formula itself is an unmodified
    port and still thinks in Y-up."""
    px, py, pz = _blender_to_pbd(pos)
    fx, fy, fz = factor_x / _NATIVE_SCALE, factor_y / _NATIVE_SCALE, factor_z / _NATIVE_SCALE

    if abs(fx) > 1e-6:
        angle = fx * py
        c, s = math.cos(angle), math.sin(angle)
        py, pz = py * c - pz * s, py * s + pz * c
    if abs(fy) > 1e-6:
        angle = fy * pz
        c, s = math.cos(angle), math.sin(angle)
        pz, px = pz * c - px * s, pz * s + px * c
    if abs(fz) > 1e-6:
        angle = fz * px
        c, s = math.cos(angle), math.sin(angle)
        px, py = px * c - py * s, px * s + py * c

    return _pbd_to_blender((px, py, pz))


def write_cap_preview(obj, prim_type, cap_mode):
    """Rebuilds obj's mesh from scratch via bmesh.ops.create_cone (works
    for both cylinder and cone - a cone is just radius2=0), then removes
    the top and/or bottom cap face - identified by face normal.z close to
    +-1, Blender's real up axis regardless of the engine's Y-up convention
    (see _blender_to_pbd/_pbd_to_blender) - based on cap_mode ('both' /
    'top' / 'bottom' / 'none'). 'top' keeps the +Z cap only (matches the
    engine's +Y = top), 'bottom' keeps -Z only.

    No native Blender modifier removes a single named face like this
    (Boolean/Mask both need an auxiliary object or a vertex group set up
    by hand), so - same policy as smooth and shear - this writes the mesh
    directly. Regenerating the whole topology fresh each call rather than
    caching and deleting from it: simpler, and immune to ever
    accidentally deleting from an already-modified mesh - segments (32)
    and size (radius 1, depth 2) match this add-on's other native-size
    primitives (see export_pbd.py's _BLENDER_TO_SHADER_SCALE).
    """
    radius2 = 0.0 if prim_type == 'cone' else 1.0
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=32,
                           radius1=1.0, radius2=radius2, depth=2.0)
    bm.normal_update()

    keep_top = cap_mode in ('both', 'top')
    keep_bottom = cap_mode in ('both', 'bottom')
    to_delete = []
    for f in bm.faces:
        if f.normal.z > 0.9 and not keep_top:
            to_delete.append(f)
        elif f.normal.z < -0.9 and not keep_bottom:
            to_delete.append(f)
    if to_delete:
        bmesh.ops.delete(bm, geom=to_delete, context='FACES')

    bm.to_mesh(obj.data)
    bm.free()
    obj.data.update()


def write_shear_preview(obj, base_verts, factor_x, factor_y, factor_z):
    """Deforms obj's mesh into shear(base_verts) - base_verts must be the
    object's UNDEFORMED native-space vertex positions (see
    _sync_shear_preview, which caches these the first time shear is
    enabled so repeated adjustments deform from the original shape rather
    than compounding onto an already-sheared one)."""
    mesh = obj.data
    if len(mesh.vertices) != len(base_verts):
        return  # topology mismatch (e.g. type changed) - let the caller rebuild instead

    for i, v in enumerate(mesh.vertices):
        v.co = _apply_shear_native(base_verts[i], factor_x, factor_y, factor_z)
    mesh.update()
