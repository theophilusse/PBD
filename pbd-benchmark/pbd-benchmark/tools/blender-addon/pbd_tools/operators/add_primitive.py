import bpy


class OBJECT_OT_add_pbd_primitive(bpy.types.Operator):
    """Adds a PBD primitive: a standard Blender mesh for preview/manipulation
    in the viewport, tagged with the type and material the exporter will
    read later."""

    bl_idname = "object.add_pbd_primitive"
    bl_label = "Add PBD Primitive"
    bl_options = {'REGISTER', 'UNDO'}

    prim_type: bpy.props.EnumProperty(
        items=[
            ('plane', "Plane", ""),
            ('sphere', "Sphere", ""),
            ('cylinder', "Cylinder", ""),
            ('cone', "Cone", ""),
            ('cube', "Cube", ""),
            ('disc', "Disc", ""),
            ('torus', "Torus", ""),
        ],
        default='cube',
    )

    def execute(self, context):
        # Uses Blender's own native default sizes (all 6 primitives fit in
        # a 2x2x2 box, 2x2x0 for the flat ones - plane and disc) - the
        # exporter compensates for this factor at export time (see
        # export_pbd.py), which removes any distinction between objects
        # created here and objects created via Blender's native Mesh tools
        # and tagged by hand afterwards. Disc uses fill_type='NGON' so it's
        # an actual filled circle, not just a wire outline - confirmed
        # (not assumed) to match the same 2x2x0 sizing as the others.
        if self.prim_type == 'plane':
            bpy.ops.mesh.primitive_plane_add()
        elif self.prim_type == 'sphere':
            bpy.ops.mesh.primitive_uv_sphere_add()
        elif self.prim_type == 'cylinder':
            bpy.ops.mesh.primitive_cylinder_add()
        elif self.prim_type == 'cone':
            bpy.ops.mesh.primitive_cone_add()
        elif self.prim_type == 'cube':
            bpy.ops.mesh.primitive_cube_add()
        elif self.prim_type == 'disc':
            bpy.ops.mesh.primitive_circle_add(fill_type='NGON')
        elif self.prim_type == 'torus':
            # major=0.7, minor=0.3 here (not the engine's canonical
            # 0.35/0.15) for the same reason cylinder/cube aren't created
            # at their literal 0.5 half-extent: every type's exported
            # scale gets the same x0.5 (_BLENDER_TO_SHADER_SCALE)
            # correction, so every native mesh needs to start at 2x its
            # canonical size for scale=1 to come out matching after export.
            bpy.ops.mesh.primitive_torus_add(major_radius=0.7, minor_radius=0.3)
        else:
            self.report({'ERROR'}, f"Unknown PBD type: {self.prim_type}")
            return {'CANCELLED'}

        obj = context.active_object
        obj.name = self.prim_type
        obj.pbd.is_pbd_primitive = True
        obj.pbd.pbd_type = self.prim_type
        return {'FINISHED'}


class OBJECT_OT_add_pbd_ref(bpy.types.Operator):
    """Adds a pbd_ref: an Empty (no geometry of its own) that places
    another .pbd file's whole scene here, transformed by this object's
    pos/rot/scale - see PbdParser.parsePbdRef on the engine side, which
    this mirrors. Blender can't preview the referenced file's actual
    geometry (that would need a full .pbd importer, planned for after the
    format reaches a stable 2.0 - see the project README), so the Empty's
    display is just a placement/orientation marker, not a preview."""

    bl_idname = "object.add_pbd_ref"
    bl_label = "Add PBD Reference"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        bpy.ops.object.empty_add(type='ARROWS', radius=1.0)
        obj = context.active_object
        obj.name = "pbd_ref"
        obj.pbd.is_pbd_primitive = True
        obj.pbd.pbd_type = 'ref'
        return {'FINISHED'}


def menu_func(self, context):
    self.layout.separator()
    for prim_type, label in (
        ('plane', "PBD Plane"),
        ('sphere', "PBD Sphere"),
        ('cylinder', "PBD Cylinder"),
        ('cone', "PBD Cone"),
        ('cube', "PBD Cube"),
        ('disc', "PBD Disc"),
        ('torus', "PBD Torus"),
    ):
        op = self.layout.operator(OBJECT_OT_add_pbd_primitive.bl_idname, text=label)
        op.prim_type = prim_type


CLASSES = (OBJECT_OT_add_pbd_primitive, OBJECT_OT_add_pbd_ref)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.VIEW3D_MT_mesh_add.append(menu_func)


def unregister():
    bpy.types.VIEW3D_MT_mesh_add.remove(menu_func)
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
