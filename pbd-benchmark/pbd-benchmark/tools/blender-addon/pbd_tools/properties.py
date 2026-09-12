import bpy

from . import materials
from . import mesh_preview

# Fixed name for the one native modifier this add-on still manages (bend);
# smooth and shear are previewed by directly writing mesh data instead,
# see mesh_preview.py - there is no native Blender effect that matches
# either accurately enough to justify a modifier-based approximation, per
# the project's "implement it in Python if Blender has nothing that
# actually matches" policy.
_BEND_PREVIEW_MODIFIER = "PBD_Bend_Preview"
_SHEAR_BASE_KEY = "_pbd_shear_base_verts"


def _sync_bend_preview(self, context):
    """Keeps a native Blender SimpleDeform modifier in sync with the PBD
    bend settings, so the viewport mesh actually curves the same way the
    engine's pbd.tese/applyBend does. Unlike smooth/shear below, this one
    genuinely matches: SimpleDeform's Bend mode IS the same constant-
    curvature circular arc pbd.tese now implements (the previous version
    of applyBend was not - see pbd.tese's comment on `shear` for what that
    actually did, and why it needed a different name and a from-scratch
    Python preview instead of a modifier).

    deform_axis maps 1:1 to the engine's bend axis (X/Y/Z), both meaning
    "which coordinate does the rotation leave unchanged" - checked for all
    three, not assumed: a subdivided cube was bent under each of
    SimpleDeform's three deform_axis settings and the vertex coordinates
    inspected directly. X left X unchanged and rotated Y/Z; Y left Y
    unchanged and rotated Z/X; Z left Z unchanged and rotated X/Y.
    """
    obj = self.id_data
    existing = obj.modifiers.get(_BEND_PREVIEW_MODIFIER)

    if not (self.is_pbd_primitive and self.pbd_bend_enabled):
        if existing is not None:
            obj.modifiers.remove(existing)
        return

    mod = existing if existing is not None else obj.modifiers.new(
        name=_BEND_PREVIEW_MODIFIER, type='SIMPLE_DEFORM')
    mod.deform_method = 'BEND'
    mod.deform_axis = self.pbd_bend_axis
    mod.angle = self.pbd_bend_angle


def _sync_cap_preview(self, context):
    """Regenerates the mesh via mesh_preview.write_cap_preview whenever
    the cap setting changes, for cylinder/cone only."""
    obj = self.id_data
    if not (self.is_pbd_primitive and self.pbd_type in ('cylinder', 'cone')):
        return
    mesh_preview.write_cap_preview(obj, self.pbd_type, self.pbd_cap)


def _sync_smooth_preview(self, context):
    """Regenerates the cube's mesh directly from mesh_preview's per-axis
    ellipsoid-rounding formula - the exact math pbd.tese uses, not Bevel's
    single-width flat-facet approximation (Bevel has no per-axis width
    concept at all, so it could not represent a flat-topped, round-sided
    cushion correctly no matter how it was configured). All-zero
    smoothness correctly regenerates a plain flat cube (the verified
    degenerate case), so this always regenerates rather than needing a
    separate "disabled" code path."""
    obj = self.id_data
    if not (self.is_pbd_primitive and self.pbd_type == 'cube'):
        return
    mesh_preview.write_smooth_cube_mesh(
        obj, self.pbd_cube_smooth_x, self.pbd_cube_smooth_y, self.pbd_cube_smooth_z)


def _sync_shear_preview(self, context):
    """Deforms the object's mesh with mesh_preview's shear formula (the
    same math as pbd.tese's applyShear). The FIRST time shear is enabled
    (or if the vertex count no longer matches, e.g. the type changed), the
    object's current shape is cached as a custom property so repeated
    slider adjustments deform from that original shape rather than
    compounding onto an already-sheared mesh; disabling shear restores it.
    """
    obj = self.id_data

    if not (self.is_pbd_primitive and self.pbd_shear_enabled):
        if _SHEAR_BASE_KEY in obj:
            base = obj[_SHEAR_BASE_KEY]
            mesh = obj.data
            if len(mesh.vertices) == len(base) // 3:
                for i, v in enumerate(mesh.vertices):
                    v.co = (base[i * 3], base[i * 3 + 1], base[i * 3 + 2])
                mesh.update()
        return

    mesh = obj.data
    needs_new_base = (
        _SHEAR_BASE_KEY not in obj
        or len(obj[_SHEAR_BASE_KEY]) != len(mesh.vertices) * 3
    )
    if needs_new_base:
        flat = []
        for v in mesh.vertices:
            flat.extend(v.co)
        obj[_SHEAR_BASE_KEY] = flat

    base = obj[_SHEAR_BASE_KEY]
    base_verts = [(base[i * 3], base[i * 3 + 1], base[i * 3 + 2]) for i in range(len(base) // 3)]
    mesh_preview.write_shear_preview(
        obj, base_verts, self.pbd_shear_factor_x, self.pbd_shear_factor_y, self.pbd_shear_factor_z)


def _sync_material_assignment(self, context):
    """Pushes the picked material onto the object so it actually previews
    that color/shading in the viewport - see materials.py for how a name
    resolves to a real Blender Material (the scene catalog if it's
    there, else a neutral placeholder)."""
    obj = self.id_data
    if obj.type == 'MESH' and self.pbd_material:
        materials.assign_material_to_object(obj, self.pbd_material)


def _make_dim_getset(axis):
    def getter(self):
        obj = self.id_data
        return getattr(obj.dimensions, axis) if obj else 0.0

    def setter(self, value):
        obj = self.id_data
        current_scale = getattr(obj.scale, axis)
        if abs(current_scale) < 1e-6:
            return  # can't recover a native size from a fully-collapsed axis
        native_size = getattr(obj.dimensions, axis) / current_scale
        if native_size < 1e-6:
            return
        setattr(obj.scale, axis, value / native_size)

    return getter, setter


def _make_face_getset(axis, is_max):
    """Moves ONE face of the object's bounding box on one axis - the
    opposite face stays exactly where it was, rather than dim_x/y/z's
    symmetric "grow from center" behavior. This is what building
    adjoining panels actually needs: a cabinet's inner wall face pinned
    to a specific position while only the outer face (thickness) moves,
    not both moving apart from the middle. Assumes the object isn't
    rotated - min/max stop meaning "a single axis-aligned face" the
    moment rotation is involved, and this add-on's own primitives
    (panels, walls) are built axis-aligned in the first place."""
    def getter(self):
        obj = self.id_data
        if not obj:
            return 0.0
        center = getattr(obj.location, axis)
        half = getattr(obj.dimensions, axis) / 2.0
        return center + half if is_max else center - half

    def setter(self, value):
        obj = self.id_data
        current_scale = getattr(obj.scale, axis)
        if abs(current_scale) < 1e-6:
            return
        native_size = getattr(obj.dimensions, axis) / current_scale
        if native_size < 1e-6:
            return
        center = getattr(obj.location, axis)
        half = getattr(obj.dimensions, axis) / 2.0
        other_face = (center - half) if is_max else (center + half)  # stays fixed
        new_dim = abs(value - other_face)
        new_center = (value + other_face) / 2.0
        setattr(obj.location, axis, new_center)
        setattr(obj.scale, axis, new_dim / native_size)

    return getter, setter


def _sync_metadata_display(self, context):
    """Wireframe-only display in Blender for a metadata marker - still
    visible and clickable for positioning it, but visually distinct from
    real geometry it's easy to otherwise mistake it for. Engine-side
    invisibility (see PatchExpander's metadata check) doesn't need any
    equivalent Blender-side toggle here - display_type is purely a
    Blender viewport setting, unrelated to what gets exported."""
    obj = self.id_data
    obj.display_type = 'WIRE' if self.pbd_metadata else 'TEXTURED'


class PbdPrimitiveProperties(bpy.types.PropertyGroup):
    """PBD data attached to a Blender object. An object without
    is_pbd_primitive=True is simply ignored by the exporter - no need to
    remove it from the scene to exclude it from the export."""

    is_pbd_primitive: bpy.props.BoolProperty(
        name="PBD Primitive",
        default=False,
    )
    pbd_type: bpy.props.EnumProperty(
        name="Type",
        items=[
            ('plane', "Plane", "Canonical plane (unit square, XZ)"),
            ('sphere', "Sphere", "Canonical sphere (radius 0.5)"),
            ('cylinder', "Cylinder", "Canonical cylinder (radius 0.5, height 1)"),
            ('cone', "Cone", "Canonical cone (radius 0.5, height 1)"),
            ('cube', "Cube", "Canonical cube ([-0.5, 0.5]^3)"),
            ('disc', "Disc", "Canonical flat disc (diameter 1, lies in XZ)"),
            ('torus', "Torus", "Canonical torus (major radius 0.35, minor radius 0.15)"),
            ('ref', "Reference (pbd_ref)", "Places another .pbd file's whole scene here, transformed - see pbd_ref_source"),
        ],
        default='cube',
    )
    pbd_material: bpy.props.StringProperty(
        name="Material",
        default="default",
        description="Pick from the scene's PBD Materials list (see the "
                    "Materials panel) or type a name directly - 'wood', "
                    "'metal' and 'plastic' have real shading on the "
                    "engine's GPU side even without a catalog entry; any "
                    "other undefined name falls back to a neutral grey "
                    "appearance rather than failing",
        update=_sync_material_assignment,
    )
    pbd_link_group: bpy.props.StringProperty(
        name="Link Group",
        default="",
        description="Objects sharing this name (and each having their own keyframes) open/close together from a single click in the engine - a wardrobe's two doors, say. Leave empty for independent objects",
    )
    pbd_metadata: bpy.props.BoolProperty(
        name="Metadata Only (invisible)",
        default=False,
        description="This primitive's position/size are kept (for a container's interior storage volume, say - later used for bin-packing game items into it) but it renders nothing at all, in the engine or in Blender - the shape and size still matter, only the visibility doesn't",
        update=_sync_metadata_display,
    )
    pbd_container_trigger: bpy.props.PointerProperty(
        type=bpy.types.Object,
        name="Opens With",
        description="Only meaningful on a Metadata-Only storage volume: which door/lid object's animation controls whether this container's bin-packed contents are computed and shown. The engine only packs and displays items once THIS object's keyframe animation reaches its fully-open pose - never before. Separate from Link Group on purpose: Link Group makes several doors move together when clicked, this says which single door gates a storage volume's contents, and the two ideas would collide if forced into the same field (e.g. a wardrobe with two synced doors sharing one Link Group, where the storage volume also needs a group name, but for an unrelated reason)",
    )
    pbd_loop_animation: bpy.props.BoolProperty(
        name="Loop Animation",
        default=False,
        description="This object's keyframes ping-pong continuously in the engine (a ceiling fan, a decorative element) instead of the default click-to-open/close behavior - no click needed, and clicking it has no effect",
    )
    pbd_ref_source: bpy.props.StringProperty(
        name="Source .pbd",
        default="",
        subtype='FILE_PATH',
        description="Path to the .pbd file this reference places into the scene, resolved relative to wherever THIS file gets exported",
    )

    # World-space size in meters - a live view onto obj.dimensions
    # (get/set, not a stored value) so it's never stale for a type whose
    # native per-axis size isn't the common 2.0 (torus's Z is 0.6).
    # Centering needs no separate handling: every primitive here has its
    # origin at its own geometric center, and scaling around the object
    # origin - which setting obj.scale always does - can't move that
    # center.
    pbd_dim_x: bpy.props.FloatProperty(name="Dim X", min=0.001, unit='LENGTH',
        get=_make_dim_getset('x')[0], set=_make_dim_getset('x')[1])
    pbd_dim_y: bpy.props.FloatProperty(name="Dim Y", min=0.001, unit='LENGTH',
        get=_make_dim_getset('y')[0], set=_make_dim_getset('y')[1])
    pbd_dim_z: bpy.props.FloatProperty(name="Dim Z", min=0.001, unit='LENGTH',
        get=_make_dim_getset('z')[0], set=_make_dim_getset('z')[1])

    # Per-face position (world space) - moves just that one face, the
    # opposite face stays put. See _make_face_getset for why this is
    # different from (and usually more useful than) Dim X/Y/Z above.
    pbd_face_min_x: bpy.props.FloatProperty(name="Face -X", unit='LENGTH',
        get=_make_face_getset('x', False)[0], set=_make_face_getset('x', False)[1])
    pbd_face_max_x: bpy.props.FloatProperty(name="Face +X", unit='LENGTH',
        get=_make_face_getset('x', True)[0], set=_make_face_getset('x', True)[1])
    pbd_face_min_y: bpy.props.FloatProperty(name="Face -Y", unit='LENGTH',
        get=_make_face_getset('y', False)[0], set=_make_face_getset('y', False)[1])
    pbd_face_max_y: bpy.props.FloatProperty(name="Face +Y", unit='LENGTH',
        get=_make_face_getset('y', True)[0], set=_make_face_getset('y', True)[1])
    pbd_face_min_z: bpy.props.FloatProperty(name="Face -Z", unit='LENGTH',
        get=_make_face_getset('z', False)[0], set=_make_face_getset('z', False)[1])
    pbd_face_max_z: bpy.props.FloatProperty(name="Face +Z", unit='LENGTH',
        get=_make_face_getset('z', True)[0], set=_make_face_getset('z', True)[1])

    # Cylinder/cone only - which end caps to export. Read by the exporter
    # into a plain `cap=` param (PbdInstance's free-form params map, no
    # parser change needed) and by PatchExpander to decide which patches
    # to emit - see its docstring. "Top" = +Y, "bottom" = -Y, matching the
    # local Y axis being the height axis for every canonical primitive.
    # Previewed by regenerating the mesh (see mesh_preview.write_cap_preview)
    # rather than a modifier - no native Blender modifier removes one named
    # face without extra per-object setup (a vertex group or a second
    # boolean object), which this add-on's other previews already avoid
    # needing for the same reason (smooth, shear).
    pbd_cap: bpy.props.EnumProperty(
        name="Caps",
        items=[
            ('both', "Both", "Keep both end caps (default)"),
            ('top', "Top only", "Keep only the +Y cap"),
            ('bottom', "Bottom only", "Keep only the -Y cap"),
            ('none', "None", "Remove both end caps (open tube)"),
        ],
        default='both',
        update=lambda self, context: _sync_cap_preview(self, context),
    )

    # Bend - a real constant-curvature circular arc (see pbd.tese's
    # applyBend). Axis picks both the bend plane and (cyclically) which
    # coordinate is the arc-length parameter - use whichever axis this
    # object has meaningful extent along (a cylinder standing in Y wants
    # X or Z so the pole's own height drives the curve).
    pbd_bend_enabled: bpy.props.BoolProperty(
        name="Enable Bend",
        default=False,
        update=_sync_bend_preview,
    )
    pbd_bend_axis: bpy.props.EnumProperty(
        name="Bend Axis",
        items=[
            ('X', "X (arc-length Y)", "Bends the Y extent into the Y/Z plane; X untouched"),
            ('Y', "Y (arc-length Z)", "Bends the Z extent into the Z/X plane; Y untouched"),
            ('Z', "Z (arc-length X)", "Bends the X extent into the X/Y plane; Z untouched"),
        ],
        default='X',
        update=_sync_bend_preview,
    )
    pbd_bend_angle: bpy.props.FloatProperty(
        name="Bend Angle",
        default=0.0,
        subtype='ANGLE',  # stored/returned in radians, displayed in degrees - export converts back to degrees
        description="Total angle swept across the object's full extent along the chosen axis",
        update=_sync_bend_preview,
    )

    # Shear (formerly mislabeled `bend`) - rotates one plane of coordinates
    # by an angle proportional to a third, which in practice scales that
    # cross-section rather than preserving it like a real bend does. Kept
    # for the warped/spiral look it gives flat shapes (a disc-based leaf's
    # curve, say); all three factors apply together, not a single axis
    # choice, so combined effects are possible where useful. No native
    # Blender modifier matches this, so it's previewed by directly
    # deforming the mesh (see mesh_preview.write_shear_preview).
    pbd_shear_enabled: bpy.props.BoolProperty(
        name="Enable Shear",
        default=False,
        update=_sync_shear_preview,
    )
    pbd_shear_factor_x: bpy.props.FloatProperty(
        name="Factor X", default=0.0, soft_min=-5.0, soft_max=5.0,
        description="Case X: rotates Y/Z by an angle proportional to Y",
        update=_sync_shear_preview,
    )
    pbd_shear_factor_y: bpy.props.FloatProperty(
        name="Factor Y", default=0.0, soft_min=-5.0, soft_max=5.0,
        description="Case Y: rotates Z/X by an angle proportional to Z",
        update=_sync_shear_preview,
    )
    pbd_shear_factor_z: bpy.props.FloatProperty(
        name="Factor Z", default=0.0, soft_min=-5.0, soft_max=5.0,
        description="Case Z: rotates X/Y by an angle proportional to X",
        update=_sync_shear_preview,
    )

    # Taper modifier (cylinder/cone) - independently scales the radius at
    # each end, e.g. for a lampshade-style frustum. Both default to 1.0
    # (no change, matching an untapered shape).
    pbd_taper_enabled: bpy.props.BoolProperty(
        name="Enable Taper",
        default=False,
    )
    pbd_taper_bottom_scale: bpy.props.FloatProperty(
        name="Bottom Scale", default=1.0, min=0.0, soft_max=2.0,
        description="Radius multiplier at the -Y end",
    )
    pbd_taper_top_scale: bpy.props.FloatProperty(
        name="Top Scale", default=1.0, min=0.0, soft_max=2.0,
        description="Radius multiplier at the +Y end",
    )

    # Cube only - per-axis rounding radius for the edges/corners (0 = sharp
    # on that axis, up to 0.5 = fully round on that axis). Independent
    # per-axis values, not one shared value, specifically so a wide flat
    # cushion can have a flat top (smoothY near 0) while its side edges
    # are still rounded (larger smoothX/smoothZ). Previewed by directly
    # regenerating the mesh (see mesh_preview.write_smooth_cube_mesh) -
    # Bevel has no per-axis width concept, so it could not represent this
    # shape at all, not even approximately.
    pbd_cube_smooth_x: bpy.props.FloatProperty(
        name="Smooth X", default=0.0, min=0.0, max=0.5,
        description="Rounding radius along local X - 0.5 degenerates that axis fully round",
        update=_sync_smooth_preview,
    )
    pbd_cube_smooth_y: bpy.props.FloatProperty(
        name="Smooth Y", default=0.0, min=0.0, max=0.5,
        description="Rounding radius along local Y (height) - keep near 0 for a flat top/bottom, e.g. a cushion",
        update=_sync_smooth_preview,
    )
    pbd_cube_smooth_z: bpy.props.FloatProperty(
        name="Smooth Z", default=0.0, min=0.0, max=0.5,
        description="Rounding radius along local Z",
        update=_sync_smooth_preview,
    )


CLASSES = (PbdPrimitiveProperties,)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Object.pbd = bpy.props.PointerProperty(type=PbdPrimitiveProperties)


def unregister():
    del bpy.types.Object.pbd
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
