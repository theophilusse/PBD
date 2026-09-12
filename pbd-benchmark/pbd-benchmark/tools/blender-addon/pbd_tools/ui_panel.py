import os

import bpy
import mathutils

from . import anim_compat


def draw_pbd_properties(layout, obj, scene):
    """Shared by both panels below so every PBD feature exposed here shows
    up the same way regardless of which panel the user has open."""
    layout.prop(obj.pbd, "is_pbd_primitive")
    if not obj.pbd.is_pbd_primitive:
        return

    layout.prop(obj.pbd, "pbd_type")
    layout.prop(obj.pbd, "pbd_metadata")
    if obj.pbd.pbd_metadata:
        layout.label(text="Renders nothing (engine + Blender wireframe)", icon='INFO')

    # Two-way container linking, both visible from whichever end the
    # user selects - not hidden behind pbd_metadata alone, since a door
    # itself never has that checked and needs to be able to see/manage
    # its links too.
    box = layout.box()
    box.label(text="Container linking:", icon='LINKED')
    if obj.pbd.pbd_metadata:
        box.prop(obj.pbd, "pbd_container_trigger", text="Opens with")
        if obj.pbd.pbd_container_trigger:
            box.label(text=f"Shows contents once '{obj.pbd.pbd_container_trigger.name}' is open.")
        else:
            box.label(text="No door set - falls back to any open", icon='ERROR')
            box.label(text="object in the scene (see below to link one).")
    linked = _storage_cubes_linked_to(obj, scene)
    if linked:
        box.label(text=f"Controls {len(linked)} storage volume(s):")
        for cube in linked:
            box.label(text=f"  - {cube.name}", icon='CUBE')
    op = box.operator("pbd.link_storage_to_door", icon='LINKED')
    op_help = box.column(align=True)
    op_help.scale_y = 0.8
    op_help.label(text="Select storage cube(s), then shift-select")
    op_help.label(text="the door LAST (making it active), then click.")

    if obj.pbd.pbd_type != 'ref':
        box = layout.box()
        box.label(text="Dimensions (grows from center):")
        box.prop(obj.pbd, "pbd_dim_x")
        box.prop(obj.pbd, "pbd_dim_y")
        box.prop(obj.pbd, "pbd_dim_z")

        box2 = layout.box()
        box2.label(text="Face position (moves just that face):")
        row = box2.row(align=True)
        row.prop(obj.pbd, "pbd_face_min_x")
        row.prop(obj.pbd, "pbd_face_max_x")
        row = box2.row(align=True)
        row.prop(obj.pbd, "pbd_face_min_y")
        row.prop(obj.pbd, "pbd_face_max_y")
        row = box2.row(align=True)
        row.prop(obj.pbd, "pbd_face_min_z")
        row.prop(obj.pbd, "pbd_face_max_z")

    if obj.pbd.pbd_type == 'ref':
        # A reference has no geometry of its own (see
        # OBJECT_OT_add_pbd_ref) - material/cap/smooth/taper/bend/shear
        # all apply to a shape this object doesn't have, so none of them
        # are shown; only where the referenced file goes.
        layout.prop(obj.pbd, "pbd_ref_source")
        if not obj.pbd.pbd_ref_source:
            layout.label(text="Set a source .pbd file above", icon='ERROR')
        layout.label(text="Blender can't preview the referenced file's", icon='INFO')
        layout.label(text="geometry yet - this Empty only marks placement")
        return

    layout.prop_search(obj.pbd, "pbd_material", scene, "pbd_materials")

    if obj.pbd.pbd_type in ('cylinder', 'cone'):
        row = layout.row()
        row.prop(obj.pbd, "pbd_cap")
        if obj.pbd.pbd_type == 'cone' and obj.pbd.pbd_cap in ('top', 'bottom'):
            row.enabled = False
            layout.label(text="Cone has one cap - use Both/None", icon='INFO')

    if obj.pbd.pbd_type == 'cube':
        box = layout.box()
        box.label(text="Smooth (per axis):")
        box.prop(obj.pbd, "pbd_cube_smooth_x")
        box.prop(obj.pbd, "pbd_cube_smooth_y")
        box.prop(obj.pbd, "pbd_cube_smooth_z")
        if max(obj.pbd.pbd_cube_smooth_x, obj.pbd.pbd_cube_smooth_y, obj.pbd.pbd_cube_smooth_z) > 0.001:
            box.label(text="Previewed live by regenerating the mesh", icon='INFO')

    if obj.pbd.pbd_type in ('cylinder', 'cone'):
        box = layout.box()
        box.prop(obj.pbd, "pbd_taper_enabled")
        if obj.pbd.pbd_taper_enabled:
            box.prop(obj.pbd, "pbd_taper_bottom_scale")
            box.prop(obj.pbd, "pbd_taper_top_scale")
            box.label(text="No live preview: Blender's Taper uses one", icon='INFO')
            box.label(text="factor, not independent top/bottom scales")

    box = layout.box()
    box.prop(obj.pbd, "pbd_bend_enabled")
    if obj.pbd.pbd_bend_enabled:
        box.prop(obj.pbd, "pbd_bend_axis")
        box.prop(obj.pbd, "pbd_bend_angle")
        box.label(text="Previewed live via a Simple Deform modifier", icon='INFO')

    box = layout.box()
    box.prop(obj.pbd, "pbd_shear_enabled")
    if obj.pbd.pbd_shear_enabled:
        box.prop(obj.pbd, "pbd_shear_factor_x")
        box.prop(obj.pbd, "pbd_shear_factor_y")
        box.prop(obj.pbd, "pbd_shear_factor_z")
        box.label(text="Previewed live by regenerating the mesh", icon='INFO')

    draw_animation_section(layout, obj)


def draw_animation_section(layout, obj):
    """Animation (a door opening, say) uses Blender's OWN native
    keyframing on rotation/location - there's no separate PBD keyframe
    tool, the exporter (_serialize_keyframes) just reads whatever
    rotation_euler/location f-curves the object already has. This
    section exists purely to make that discoverable and to remove the
    need to know Blender's own keyframing shortcuts: without it, nothing
    anywhere hints that keyframing is even the right approach, or
    confirms it actually picked something up."""
    box = layout.box()
    box.label(text="Animation (door/drawer opening):")

    box.prop(obj.pbd, "pbd_link_group")
    if obj.pbd.pbd_link_group:
        box.label(text="Opens together with other objects", icon='LINKED')
        box.label(text="using this same Link Group name.")
    box.prop(obj.pbd, "pbd_loop_animation")

    times = _keyframe_times(obj)
    if times:
        box.label(text=f"{len(times)} keyframe(s):", icon='CHECKMARK')
        for t in times:
            row = box.row(align=True)
            row.label(text=f"  t={t:.2f}s")
            op = row.operator("pbd.goto_keyframe", text="", icon='PLAY')
            op.time_seconds = t
            op = row.operator("pbd.delete_keyframe", text="", icon='X')
            op.time_seconds = t
    else:
        box.label(text="No keyframes on this object yet.", icon='INFO')

    row = box.row(align=True)
    row.operator("pbd.insert_animation_keyframe", text="Insert Keyframe Here", icon='KEY_HLT')
    tip = box.column(align=True)
    tip.scale_y = 0.8
    tip.label(text="Move the timeline (bottom of screen),")
    tip.label(text="rotate/move this object to its next")
    tip.label(text="pose, click Insert again. Exported")
    tip.label(text="keyframe times = frame / scene FPS.")


class OBJECT_OT_pbd_goto_keyframe(bpy.types.Operator):
    """Moves the playhead to this keyframe's frame, so its pose is what's
    visible in the viewport for hand-editing (move/rotate the object,
    then re-keyframe from the toolbar button above) - this add-on has no
    separate pose-editing UI of its own, this is the bridge to using
    Blender's normal transform tools at the right moment in time."""
    bl_idname = "pbd.goto_keyframe"
    bl_label = "Go To Keyframe"
    bl_options = {'REGISTER', 'UNDO'}
    time_seconds: bpy.props.FloatProperty()

    def execute(self, context):
        fps = context.scene.render.fps
        context.scene.frame_set(round(self.time_seconds * fps))
        return {'FINISHED'}


class OBJECT_OT_pbd_delete_keyframe(bpy.types.Operator):
    """Removes every rotation_euler/location keyframe point at this exact
    time (across whichever of those 6 channels actually have one there)
    from the active object - the missing "edit" half of the panel's
    keyframe list, which previously only showed a count with no way to
    change it short of switching to Blender's own Dope Sheet by hand."""
    bl_idname = "pbd.delete_keyframe"
    bl_label = "Delete Keyframe"
    bl_options = {'REGISTER', 'UNDO'}
    time_seconds: bpy.props.FloatProperty()

    def execute(self, context):
        obj = context.object
        fcurves = anim_compat.get_object_fcurves(obj)
        fps = context.scene.render.fps
        frame = self.time_seconds * fps
        removed = 0
        for fc in fcurves:
            if fc.data_path not in ("rotation_euler", "location"):
                continue
            for kp in list(fc.keyframe_points):
                if abs(kp.co.x - frame) < 0.5:  # within half a frame - float rounding, not a real second keyframe nearby
                    fc.keyframe_points.remove(kp)
                    removed += 1
        self.report({'INFO'}, f"Removed {removed} keyframe point(s) at t={self.time_seconds:.2f}s")
        return {'FINISHED'}


def _keyframe_times(obj):
    """Frame numbers (already converted to seconds) with a keyframe on
    rotation_euler or location, deduplicated and sorted - same curves
    _serialize_keyframes reads at export time (see anim_compat for why
    this doesn't touch action.fcurves directly)."""
    fcurves = anim_compat.get_object_fcurves(obj)
    if not fcurves:
        return []
    curves = [anim_compat.find_fcurve(fcurves, "rotation_euler", i) for i in range(3)]
    curves += [anim_compat.find_fcurve(fcurves, "location", i) for i in range(3)]
    frames = sorted({kp.co.x for c in curves if c for kp in c.keyframe_points})
    fps = bpy.context.scene.render.fps
    return [f / fps for f in frames]


def draw_material_catalog(layout, scene):
    """Shared by both panels - the scene-wide PBD Materials list backing a
    .pbdmat file (see materials.py). Editing a field here updates a real
    Blender Material immediately, so every object already using that name
    re-previews with the new color/shading right away.

    Typing a name directly into an object's Material field (prop_search
    allows free text, not just catalog picks) does NOT add it here - the
    catalog and an object's pbd_material are only linked by name, not
    auto-synced either direction. An object referencing a name with no
    catalog entry has nothing wrong with it (it exports and renders
    fine, falling back to a neutral color - see PbdRenderer's
    resolveMaterialEntry) but its texture/UV/normal-map/etc fields
    literally don't exist anywhere to edit until a catalog entry for
    that name exists - "Sync from Objects" below is the fast way to
    create one for everything already in use.
    """
    if len(scene.pbd_materials) == 0:
        box = layout.box()
        box.label(text="No materials yet.", icon='INFO')
        box.label(text="Add one below, or Sync from")
        box.label(text="Objects if you've already typed")
        box.label(text="material names on some objects.")

    row = layout.row()
    row.template_list("PBD_UL_materials", "", scene, "pbd_materials", scene, "pbd_materials_active_index", rows=3)
    col = row.column(align=True)
    col.operator("pbd.material_add", text="", icon='ADD')
    col.operator("pbd.material_remove", text="", icon='REMOVE')

    idx = scene.pbd_materials_active_index
    if 0 <= idx < len(scene.pbd_materials):
        entry = scene.pbd_materials[idx]
        box = layout.box()
        box.prop(entry, "name")
        box.prop(entry, "color")
        box.prop(entry, "shininess")
        box.prop(entry, "specular")
        box.prop(entry, "texture_path")
        box.prop(entry, "uv_scale")
        box.prop(entry, "normal_map_path")
        box.prop(entry, "roughness_map_path")
        box.prop(entry, "displacement_map_path")
        if entry.displacement_map_path:
            box.prop(entry, "displacement_scale")
        box.prop(entry, "material_source")
        box.prop(entry, "reflectivity")
        box.prop(entry, "transparency")

    row = layout.row(align=True)
    row.operator("pbd.material_load", text="Load .pbdmat", icon='IMPORT')
    row.operator("pbd.material_save", text="Save .pbdmat", icon='EXPORT')
    layout.operator("pbd.material_sync_from_objects", text="Sync from Objects", icon='FILE_REFRESH')
    layout.operator("pbd.refresh", text="Refresh Materials", icon='FILE_REFRESH')


class OBJECT_OT_pbd_insert_animation_keyframe(bpy.types.Operator):
    """Inserts a rotation_euler keyframe on the active object at the
    current frame - exactly what pressing I over Rotation in the N-panel
    or viewport already does; this just saves knowing that shortcut, and
    makes it a one-click action from the PBD tab specifically. Position
    a door/drawer object at its current pose (closed, say) before
    clicking, move the timeline forward, pose it again (open), click
    again - two keyframes is enough for a working open/close animation.
    """
    bl_idname = "pbd.insert_animation_keyframe"
    bl_label = "Insert PBD Animation Keyframe"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        obj = context.object
        if obj is None:
            self.report({'ERROR'}, "No active object")
            return {'CANCELLED'}
        obj.keyframe_insert(data_path="rotation_euler", frame=context.scene.frame_current)
        self.report({'INFO'}, f"Keyframed rotation at frame {context.scene.frame_current}")
        return {'FINISHED'}


class OBJECT_OT_pbd_quick_export(bpy.types.Operator):
    """One-click export with no file browser - writes to
    scenes/handmade/<blend file name>.pbd under the configured project
    root (or quick_export.pbd if this .blend hasn't been saved yet), so
    iterating on a model doesn't mean re-picking a save location every
    time. Use File > Export > PBD Scene instead for a specific path."""
    bl_idname = "pbd.quick_export"
    bl_label = "Quick Export PBD"
    bl_options = {'REGISTER'}

    def execute(self, context):
        path = _quick_export_path(context)
        if path is None:
            self.report({'ERROR'}, "Set PBD Project Root above first")
            return {'CANCELLED'}
        os.makedirs(os.path.dirname(path), exist_ok=True)
        from .operators import export_pbd
        count, warnings, kf = export_pbd.export_scene_to_pbd(context, path)
        for w in warnings:
            self.report({'WARNING'}, w)
        self.report({'INFO'}, f"Exported {count} instance(s) to {path}")
        return {'FINISHED'}


class OBJECT_OT_pbd_export_to_tilegeometry(bpy.types.Operator):
    """Quick-exports the current scene (see pbd.quick_export), then runs
    PbdToTileGeometryMain on the result to add it as a new tile in the
    project's tileGeometry.txt - this was previously a Java-only CLI
    tool with no Blender-side button at all, which is exactly why it
    wasn't visible anywhere in the addon."""
    bl_idname = "pbd.export_to_tilegeometry"
    bl_label = "Add to tileGeometry.txt"
    bl_options = {'REGISTER'}

    tileset_name: bpy.props.StringProperty(
        name="Tileset Name",
        default="custom_assets",
        description="Which tileset in tileGeometry.txt this becomes a new tile of - created if it doesn't exist yet",
    )

    def invoke(self, context, event):
        return context.window_manager.invoke_props_dialog(self)

    def execute(self, context):
        path = _quick_export_path(context)
        if path is None:
            self.report({'ERROR'}, "Set PBD Project Root above first")
            return {'CANCELLED'}
        os.makedirs(os.path.dirname(path), exist_ok=True)
        from .operators import export_pbd
        count, warnings, kf = export_pbd.export_scene_to_pbd(context, path)
        for w in warnings:
            self.report({'WARNING'}, w)

        root = context.scene.pbd_project_root
        tile_geometry_path = os.path.join(root, "src", "main", "resources", "pz", "tileGeometry.txt")
        classpath_dirs = self._find_build_output(root)
        if classpath_dirs is None:
            self.report({'ERROR'}, "No compiled classes found under build/ - run the project once "
                                    "(./gradlew build or ./gradlew run) so PbdToTileGeometryMain is compiled")
            return {'CANCELLED'}

        try:
            import subprocess
            result = subprocess.run(
                ["java", "-cp", classpath_dirs, "pbd.pz.PbdToTileGeometryMain", path, tile_geometry_path, self.tileset_name],
                cwd=root, capture_output=True, text=True, timeout=30,
            )
            for line in result.stdout.splitlines():
                self.report({'INFO'}, line)
            if result.returncode != 0:
                self.report({'ERROR'}, f"PbdToTileGeometryMain failed: {result.stderr[-300:]}")
                return {'CANCELLED'}
        except (OSError, subprocess.SubprocessError) as e:
            self.report({'ERROR'}, f"Failed to run PbdToTileGeometryMain: {e}")
            return {'CANCELLED'}

        self.report({'INFO'}, f"Added to {tile_geometry_path}")
        return {'FINISHED'}

    def _find_build_output(self, root):
        """Locates compiled .class files without requiring the user to
        know Gradle's own output layout - checks the two conventional
        spots (build/classes/java/main for a plain build, or a fat/shadow
        jar if one exists) and returns None if neither is found, rather
        than guessing a classpath that would just fail with a confusing
        ClassNotFoundException instead."""
        classes_dir = os.path.join(root, "build", "classes", "java", "main")
        if os.path.isdir(classes_dir):
            return classes_dir
        libs_dir = os.path.join(root, "build", "libs")
        if os.path.isdir(libs_dir):
            jars = [f for f in os.listdir(libs_dir) if f.endswith(".jar")]
            if jars:
                return os.path.join(libs_dir, jars[0])
        return None


class OBJECT_OT_pbd_add_bounding_box(bpy.types.Operator):
    """Adds a new metadata (invisible) cube matching the active object's
    exact world-space bounding box - the "add a bounding-box" macro:
    useful for marking an FBX-derived object's hitbox before bin-packing,
    or any other case where the storage-space/collision volume should
    exactly track an existing object's real extent rather than being
    positioned by hand and eyeballed."""
    bl_idname = "pbd.add_bounding_box"
    bl_label = "Add Bounding Box (metadata)"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        obj = context.object
        if obj is None:
            self.report({'ERROR'}, "No active object")
            return {'CANCELLED'}

        # bound_box is 8 local-space corners; each needs the object's own
        # matrix_world applied to get the true world-space extent this
        # box should cover - not just obj.location +/- obj.dimensions/2,
        # which would be wrong for a rotated object.
        world_corners = [obj.matrix_world @ mathutils.Vector(c) for c in obj.bound_box]
        xs = [c.x for c in world_corners]
        ys = [c.y for c in world_corners]
        zs = [c.z for c in world_corners]
        center = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2)
        size = (max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs))

        bpy.ops.object.add_pbd_primitive(prim_type='cube')
        box_obj = context.active_object
        box_obj.name = obj.name + "_hitbox"
        box_obj.location = center
        # Native cube dimensions are 2 units (see add_primitive.py) - scale
        # to match size exactly, same convention pbd_dim_x/y/z already use.
        box_obj.scale = (size[0] / 2, size[1] / 2, size[2] / 2)
        box_obj.pbd.pbd_metadata = True

        self.report({'INFO'}, f"Added {box_obj.name}: {size[0]:.3f} x {size[1]:.3f} x {size[2]:.3f}")
        return {'FINISHED'}


class OBJECT_OT_pbd_quick_export_and_visualize(bpy.types.Operator):
    """Quick-exports (see pbd.quick_export), then launches the Java
    engine viewer on the result via `gradlew run --args=<path>` in the
    project root, without blocking Blender - the viewer is a separate,
    long-running window, not something to wait on here."""
    bl_idname = "pbd.quick_export_and_visualize"
    bl_label = "Export and Visualize"
    bl_options = {'REGISTER'}

    def execute(self, context):
        path = _quick_export_path(context)
        if path is None:
            self.report({'ERROR'}, "Set PBD Project Root above first")
            return {'CANCELLED'}
        os.makedirs(os.path.dirname(path), exist_ok=True)
        from .operators import export_pbd
        count, warnings, kf = export_pbd.export_scene_to_pbd(context, path)
        for w in warnings:
            self.report({'WARNING'}, w)

        root = context.scene.pbd_project_root
        gradlew = os.path.join(root, "gradlew.bat" if os.name == "nt" else "gradlew")
        if not os.path.exists(gradlew):
            self.report({'ERROR'}, f"gradlew not found at {gradlew} - check PBD Project Root")
            return {'CANCELLED'}
        try:
            import subprocess
            subprocess.Popen([gradlew, "run", f"--args={path}"], cwd=root)
        except OSError as e:
            self.report({'ERROR'}, f"Failed to launch viewer: {e}")
            return {'CANCELLED'}

        self.report({'INFO'}, f"Exported {count} instance(s), launching viewer...")
        return {'FINISHED'}


def _quick_export_path(context):
    root = context.scene.pbd_project_root
    if not root:
        return None
    if bpy.data.filepath:
        name = os.path.splitext(os.path.basename(bpy.data.filepath))[0] + ".pbd"
    else:
        name = "quick_export.pbd"
    return os.path.join(root, "src", "main", "resources", "scenes", "handmade", name)


class PBD_UL_materials(bpy.types.UIList):
    def draw_item(self, context, layout, data, item, icon, active_data, active_propname, index):
        row = layout.row(align=True)
        row.prop(item, "color", text="")
        row.prop(item, "name", text="", emboss=False)


def _storage_cubes_linked_to(door_obj, scene):
    """Every object in the scene whose pbd_container_trigger currently
    points at door_obj - lets a door show what it controls without
    needing a second, parallel data structure (the per-cube pointer set
    via pbd_container_trigger, already the tested source of truth,
    stays the only place this is actually stored)."""
    return [o for o in bpy.data.objects
            if o.pbd.is_pbd_primitive and o.pbd.pbd_metadata and o.pbd.pbd_container_trigger == door_obj]


class OBJECT_OT_pbd_link_storage_to_door(bpy.types.Operator):
    """One-click multi-link: select one or more Metadata-Only storage
    cubes, shift-select the door LAST so it becomes the active object,
    then run this - sets pbd_container_trigger on every OTHER selected
    object (skipping the active one, and skipping anything that isn't a
    metadata storage cube) to point at the active object. This is the
    "link a door to several bounding boxes in one step" workflow -
    setting each cube's trigger by hand one at a time still works too,
    this is just faster for more than one."""
    bl_idname = "pbd.link_storage_to_door"
    bl_label = "Link Selected Storage Cube(s) to Active Object"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        door = context.object
        if door is None:
            self.report({'ERROR'}, "No active object - select storage cube(s), then shift-select the door last")
            return {'CANCELLED'}

        linked = 0
        skipped = []
        for obj in context.selected_objects:
            if obj == door:
                continue
            if not (obj.pbd.is_pbd_primitive and obj.pbd.pbd_metadata):
                skipped.append(obj.name)
                continue
            obj.pbd.pbd_container_trigger = door
            linked += 1

        if linked == 0:
            self.report({'WARNING'}, "Nothing linked - select Metadata-Only storage cube(s) "
                                      "in addition to the active door object")
            return {'CANCELLED'}
        msg = f"Linked {linked} storage cube(s) to '{door.name}'"
        if skipped:
            msg += f" (skipped non-storage selection: {', '.join(skipped)})"
        self.report({'INFO'}, msg)
        return {'FINISHED'}


class OBJECT_PT_pbd_primitive(bpy.types.Panel):
    """Properties-editor panel - unchanged location, kept for anyone used to finding it there."""

    bl_label = "PBD"
    bl_idname = "OBJECT_PT_pbd_primitive"
    bl_space_type = 'PROPERTIES'
    bl_region_type = 'WINDOW'
    bl_context = "object"

    def draw(self, context):
        obj = context.object
        if obj is None:
            return
        draw_pbd_properties(self.layout, obj, context.scene)


class VIEW3D_PT_pbd_tools(bpy.types.Panel):
    """3D-viewport sidebar panel (press N, "PBD" tab): one click to create
    an already-tagged primitive instead of adding a mesh and then finding
    the checkbox in Properties, plus the selected object's PBD settings
    right there so switching editors is rarely needed."""

    bl_label = "PBD Tools"
    bl_idname = "VIEW3D_PT_pbd_tools"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = "PBD"

    def draw(self, context):
        layout = self.layout

        box = layout.box()
        box.prop(context.scene, "pbd_project_root")
        row = box.row(align=True)
        row.operator("pbd.quick_export", text="Export", icon='EXPORT')
        row.operator("pbd.quick_export_and_visualize", text="Export & Visualize", icon='PLAY')
        box.operator("import_scene.pbd", text="Import", icon='IMPORT')
        box.operator("pbd.export_to_tilegeometry", text="Add to tileGeometry.txt", icon='FILE_TEXT')

        col = layout.column(align=True)
        col.label(text="Create:")
        for prim_type, label in (
            ('plane', "Plane"),
            ('sphere', "Sphere"),
            ('cylinder', "Cylinder"),
            ('cone', "Cone"),
            ('cube', "Cube"),
            ('disc', "Disc"),
            ('torus', "Torus"),
        ):
            op = col.operator("object.add_pbd_primitive", text=label)
            op.prim_type = prim_type
        col.operator("object.add_pbd_ref", text="Reference (pbd_ref)", icon='EMPTY_ARROWS')

        obj = context.object
        if obj is not None and obj.type in ('MESH', 'EMPTY'):
            layout.separator()
            box = layout.box()
            box.label(text=f"Selected: {obj.name}")
            box.operator("pbd.add_bounding_box", text="Add Bounding Box (metadata)", icon='CUBE')
            draw_pbd_properties(box, obj, context.scene)

        layout.separator()
        tip = layout.column(align=True)
        tip.scale_y = 0.8
        tip.label(text="Tip: right-click a Create button")
        tip.label(text="above to assign a keyboard shortcut")
        tip.label(text="(or Preferences > Keymap).")


class OBJECT_OT_pbd_load_pbdmat_by_path(bpy.types.Operator):
    """Loads one specific .pbdmat file, given directly by path - the
    one-click counterpart to pbd.material_load's file browser, used by
    the pbdmat list below. A plain StringProperty (not FILE_PATH
    subtype) on purpose: a FILE_PATH property makes Blender's default
    invoke() pop the file browser even when called from a panel button
    with the path already known, which defeats the point of a one-click
    list entry."""
    bl_idname = "pbd.load_pbdmat_by_path"
    bl_label = "Load This .pbdmat"
    bl_options = {'REGISTER', 'UNDO'}
    filepath: bpy.props.StringProperty()

    def execute(self, context):
        from . import materials as materials_module
        try:
            parsed = materials_module._parse_pbdmat(self.filepath)
        except OSError as e:
            self.report({'ERROR'}, f"Could not read {self.filepath}: {e}")
            return {'CANCELLED'}
        mat_dir = os.path.dirname(self.filepath)
        for name, fields in parsed.items():
            entry = context.scene.pbd_materials.get(name)
            if entry is None:
                entry = context.scene.pbd_materials.add()
                entry.name = name
            if "color" in fields:
                entry.color = fields["color"]
            if "shininess" in fields:
                entry.shininess = fields["shininess"]
            if "specular" in fields:
                entry.specular = fields["specular"]
            if "texture" in fields:
                entry.texture_path = os.path.join(mat_dir, fields["texture"])
            if "normalMap" in fields:
                entry.normal_map_path = os.path.join(mat_dir, fields["normalMap"])
            if "roughnessMap" in fields:
                entry.roughness_map_path = os.path.join(mat_dir, fields["roughnessMap"])
            if "displacementMap" in fields:
                entry.displacement_map_path = os.path.join(mat_dir, fields["displacementMap"])
            if "displacementScale" in fields:
                entry.displacement_scale = fields["displacementScale"]
            if "uvScale" in fields:
                entry.uv_scale = fields["uvScale"]
            if "reflectivity" in fields:
                entry.reflectivity = fields["reflectivity"]
            if "transparency" in fields:
                entry.transparency = fields["transparency"]
        self.report({'INFO'}, f"Loaded {len(parsed)} material(s) from {os.path.basename(self.filepath)}")
        return {'FINISHED'}


def draw_pbdmat_file_list(layout, context):
    """Lists every .pbdmat under the project's shared materials/ folder
    (see _resolve_materials_dir in export_pbd.py for how that folder is
    located) - browsing what already exists there before deciding what
    to load or reference as a SHARED material, rather than needing to
    remember file names or dig through a file browser."""
    root = context.scene.pbd_project_root
    if not root:
        layout.label(text="Set PBD Project Root above to browse materials/", icon='INFO')
        return
    materials_dir = os.path.join(root, "src", "main", "resources", "materials")
    if not os.path.isdir(materials_dir):
        layout.label(text=f"No materials/ folder found at {materials_dir}", icon='ERROR')
        return
    files = sorted(f for f in os.listdir(materials_dir) if f.endswith(".pbdmat"))
    if not files:
        layout.label(text="No .pbdmat files found yet.", icon='INFO')
        return
    col = layout.column(align=True)
    for f in files:
        row = col.row(align=True)
        row.label(text=f[:-len(".pbdmat")])
        op = row.operator("pbd.load_pbdmat_by_path", text="", icon='IMPORT')
        op.filepath = os.path.join(materials_dir, f)


class VIEW3D_PT_pbd_materials(bpy.types.Panel):
    """Separate panel (same PBD sidebar tab) so the material catalog isn't
    buried under whichever object happens to be selected - materials are
    scene-wide, not per-object."""

    bl_label = "PBD Materials"
    bl_idname = "VIEW3D_PT_pbd_materials"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = "PBD"

    def draw(self, context):
        draw_material_catalog(self.layout, context.scene)
        self.layout.separator()
        box = self.layout.box()
        box.label(text="Available in materials/ folder:")
        draw_pbdmat_file_list(box, context)


CLASSES = (PBD_UL_materials, OBJECT_PT_pbd_primitive, VIEW3D_PT_pbd_tools, VIEW3D_PT_pbd_materials,
           OBJECT_OT_pbd_insert_animation_keyframe, OBJECT_OT_pbd_goto_keyframe, OBJECT_OT_pbd_delete_keyframe,
           OBJECT_OT_pbd_quick_export, OBJECT_OT_pbd_quick_export_and_visualize, OBJECT_OT_pbd_load_pbdmat_by_path,
           OBJECT_OT_pbd_export_to_tilegeometry, OBJECT_OT_pbd_add_bounding_box, OBJECT_OT_pbd_link_storage_to_door)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
