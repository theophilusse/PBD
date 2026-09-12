"""
Scene-level material catalog, backing a .pbdmat file (see PbdMatParser on
the engine side). Each pbd_materials entry is one `material NAME { ... }`
block; editing a field here immediately updates a real Blender Material
so every object using that name previews with the right color in the
viewport - not just at export time.
"""

import bpy
import os


class OBJECT_OT_pbd_refresh(bpy.types.Operator):
    """Re-syncs every PBD material's Blender representation and switches
    Solid-shading viewports to display texture images.

    Solid mode (Blender's default viewport shading) only ever shows a
    material's flat diffuse_color - it has no node graph to read, so an
    Image Texture node being correctly wired to Base Color (which is all
    _sync_material_to_blender sets up) is invisible there no matter how
    correct it is. Material Preview / Rendered modes DO read the node
    graph and would already show it - but Solid is what most people are
    looking at by default, and there's no obvious UI hint that a
    different display mode exists. Solid mode also has its own texture
    display, toggled per-viewport via Viewport Shading > Color >
    Texture - scripted here rather than left as a manual step to find.
    """
    bl_idname = "pbd.refresh"
    bl_label = "Refresh PBD Materials"
    bl_options = {'REGISTER', 'UNDO'}
    bl_description = "Re-applies every PBD material's color/texture/etc to its Blender material, and switches viewports to show textures in Solid shading"

    def execute(self, context):
        for entry in context.scene.pbd_materials:
            _sync_material_to_blender(entry)
        for obj in bpy.data.objects:
            if obj.type == 'MESH' and getattr(obj, "pbd", None) and obj.pbd.pbd_material:
                assign_material_to_object(obj, obj.pbd.pbd_material)

        switched = 0
        for window in context.window_manager.windows:
            for area in window.screen.areas:
                if area.type == 'VIEW_3D':
                    for space in area.spaces:
                        if space.type == 'VIEW_3D':
                            space.shading.color_type = 'TEXTURE'
                            switched += 1

        self.report({'INFO'}, f"Refreshed {len(context.scene.pbd_materials)} material(s), "
                               f"switched {switched} viewport(s) to texture display")
        return {'FINISHED'}


def _sync_material_to_blender(entry):
    """Finds or creates a Blender Material matching this entry's name and
    pushes color/specular/texture into it. diffuse_color drives Solid
    shading (proven in this add-on's own test renders); an Image Texture
    node wired into Principled BSDF's Base Color drives Material Preview /
    Rendered shading, so a texture_path actually shows there - a flat
    Base Color value alone can never display an image, no matter what
    that value is."""
    mat_name = "pbd_mat_" + entry.name
    mat = bpy.data.materials.get(mat_name)
    if mat is None:
        mat = bpy.data.materials.new(name=mat_name)
        mat.use_nodes = True

    mat.diffuse_color = (entry.color[0], entry.color[1], entry.color[2], 1.0)
    mat.specular_intensity = entry.specular

    if mat.use_nodes:
        nodes = mat.node_tree.nodes
        links = mat.node_tree.links
        bsdf = nodes.get("Principled BSDF")
        if bsdf is not None:
            if "Specular IOR Level" in bsdf.inputs:  # property name changed across Blender versions
                bsdf.inputs["Specular IOR Level"].default_value = entry.specular
            elif "Specular" in bsdf.inputs:
                bsdf.inputs["Specular"].default_value = entry.specular

            tex_node = nodes.get("PBD Texture")
            mapping_node = nodes.get("PBD UVScale")
            if entry.texture_path:
                if tex_node is None:
                    tex_node = nodes.new("ShaderNodeTexImage")
                    tex_node.name = "PBD Texture"
                    tex_node.location = (bsdf.location.x - 500, bsdf.location.y)
                if mapping_node is None:
                    mapping_node = nodes.new("ShaderNodeMapping")
                    mapping_node.name = "PBD UVScale"
                    mapping_node.location = (bsdf.location.x - 700, bsdf.location.y)
                    coord_node = nodes.new("ShaderNodeTexCoord")
                    coord_node.name = "PBD TexCoord"
                    coord_node.location = (bsdf.location.x - 900, bsdf.location.y)
                    links.new(coord_node.outputs["UV"], mapping_node.inputs["Vector"])
                mapping_node.inputs["Scale"].default_value = (entry.uv_scale, entry.uv_scale, entry.uv_scale)
                links.new(mapping_node.outputs["Vector"], tex_node.inputs["Vector"])
                try:
                    image = bpy.data.images.load(bpy.path.abspath(entry.texture_path), check_existing=True)
                    tex_node.image = image
                    links.new(tex_node.outputs["Color"], bsdf.inputs["Base Color"])
                except RuntimeError as e:
                    print(f"[PBD] Could not load texture '{entry.texture_path}': {e}")
                    bsdf.inputs["Base Color"].default_value = (entry.color[0], entry.color[1], entry.color[2], 1.0)
            else:
                if tex_node is not None:
                    nodes.remove(tex_node)
                if mapping_node is not None:
                    nodes.remove(mapping_node)
                coord_node = nodes.get("PBD TexCoord")
                if coord_node is not None:
                    nodes.remove(coord_node)
                bsdf.inputs["Base Color"].default_value = (entry.color[0], entry.color[1], entry.color[2], 1.0)

            # Normal map: needs a dedicated Normal Map node between the
            # image and the BSDF (Blender doesn't accept a raw image color
            # as a normal input - that node is what un-remaps the
            # 0..1-encoded tangent-space vector back into a usable normal).
            normal_tex_node = nodes.get("PBD NormalTexture")
            normal_map_node = nodes.get("PBD NormalMap")
            if entry.normal_map_path:
                if normal_tex_node is None:
                    normal_tex_node = nodes.new("ShaderNodeTexImage")
                    normal_tex_node.name = "PBD NormalTexture"
                    normal_tex_node.location = (bsdf.location.x - 500, bsdf.location.y - 300)
                if normal_map_node is None:
                    normal_map_node = nodes.new("ShaderNodeNormalMap")
                    normal_map_node.name = "PBD NormalMap"
                    normal_map_node.location = (bsdf.location.x - 300, bsdf.location.y - 300)
                try:
                    normal_image = bpy.data.images.load(bpy.path.abspath(entry.normal_map_path), check_existing=True)
                    normal_image.colorspace_settings.name = 'Non-Color'  # normal maps are data, not sRGB color
                    normal_tex_node.image = normal_image
                    links.new(normal_tex_node.outputs["Color"], normal_map_node.inputs["Color"])
                    links.new(normal_map_node.outputs["Normal"], bsdf.inputs["Normal"])
                except RuntimeError as e:
                    print(f"[PBD] Could not load normal map '{entry.normal_map_path}': {e}")
            else:
                if normal_tex_node is not None:
                    nodes.remove(normal_tex_node)
                if normal_map_node is not None:
                    nodes.remove(normal_map_node)

            if "Roughness" in bsdf.inputs:
                # No real reflection probe in Blender's viewport, and no
                # single "reflectivity" input on Principled BSDF either -
                # lower roughness reads as glossier/more mirror-like,
                # which is the closer single-slider approximation of "more
                # reflective" than Transmission (which means see-through,
                # not reflective) would be. Same "close visual
                # approximation, not physically exact" policy as this
                # add-on's bend/smooth previews.
                bsdf.inputs["Roughness"].default_value = max(0.05, 0.5 - entry.reflectivity * 0.45)

            mat.blend_method = 'BLEND' if entry.transparency > 0.001 else 'OPAQUE'
            if "Alpha" in bsdf.inputs:
                bsdf.inputs["Alpha"].default_value = 1.0 - entry.transparency
    return mat


def _sync_material_entry(self, context):
    _sync_material_to_blender(self)
    # Re-push to every object currently using this material by name, so
    # a rename or color edit is visible immediately, not just for
    # objects assigned to it afterward.
    for obj in bpy.data.objects:
        if obj.type == 'MESH' and getattr(obj, "pbd", None) and obj.pbd.pbd_material == self.name:
            assign_material_to_object(obj, self.name)


def assign_material_to_object(obj, material_name):
    """Finds-or-creates the Blender material for material_name (from the
    scene catalog if present, else a neutral fallback) and makes it the
    object's only material slot. Called whenever pbd_material changes
    (see properties.py) and whenever a catalog entry it's using is edited
    (see _sync_material_entry above)."""
    scene = bpy.context.scene
    entry = scene.pbd_materials.get(material_name)
    if entry is not None:
        mat = _sync_material_to_blender(entry)
    else:
        # Not in the catalog - likely one of the engine's hard-coded
        # names (wood/metal/plastic) or a name from an included .pbdmat
        # this session hasn't loaded. Neutral grey placeholder rather
        # than refusing the name.
        mat_name = "pbd_mat_" + material_name
        mat = bpy.data.materials.get(mat_name)
        if mat is None:
            mat = bpy.data.materials.new(name=mat_name)
            mat.diffuse_color = (0.65, 0.65, 0.68, 1.0)

    if obj.data.materials:
        obj.data.materials[0] = mat
    else:
        obj.data.materials.append(mat)


class PbdMaterialEntry(bpy.types.PropertyGroup):
    # Named "name" (not e.g. "mat_name") specifically so prop_search can
    # search this collection directly - see ui_panel.py's use of it on
    # pbd_material - and so bpy.data-style `scene.pbd_materials["wood"]`
    # lookup by name works, both being standard Blender conventions for
    # entries meant to be found by name rather than only by index.
    name: bpy.props.StringProperty(name="Name", default="material", update=_sync_material_entry)
    color: bpy.props.FloatVectorProperty(
        name="Color", subtype='COLOR', size=3, min=0.0, max=1.0,
        default=(0.7, 0.7, 0.7), update=_sync_material_entry,
    )
    shininess: bpy.props.FloatProperty(name="Shininess", default=8.0, min=0.0, soft_max=128.0, update=_sync_material_entry)
    specular: bpy.props.FloatProperty(name="Specular", default=0.1, min=0.0, max=1.0, update=_sync_material_entry)
    texture_path: bpy.props.StringProperty(name="Texture", default="", subtype='FILE_PATH', update=_sync_material_entry)
    normal_map_path: bpy.props.StringProperty(name="Normal Map", default="", subtype='FILE_PATH', update=_sync_material_entry)
    roughness_map_path: bpy.props.StringProperty(name="Roughness Map", default="", subtype='FILE_PATH', update=_sync_material_entry)
    displacement_map_path: bpy.props.StringProperty(name="Displacement Map", default="", subtype='FILE_PATH', update=_sync_material_entry)
    displacement_scale: bpy.props.FloatProperty(name="Displacement Scale", default=0.02, min=0.0, soft_max=0.5, update=_sync_material_entry,
        description="World-space height at a fully-white texel - matches PbdRenderer's own default of 0.02 when this field is omitted from a .pbdmat")
    material_source: bpy.props.EnumProperty(
        name="Material Source",
        items=[
            ('EMBEDDED', "Embed in this .pbd", "Writes this material into a .pbdmat generated alongside this scene's .pbd file"),
            ('SHARED', "Reference materials/ folder", "Points include_material at the shared, project-wide materials/NAME.pbdmat instead of duplicating it here - use for a material that's shared across many assets (ambientcg-derived, say)"),
        ],
        default='EMBEDDED',
    )
    reflectivity: bpy.props.FloatProperty(name="Reflectivity", default=0.0, min=0.0, max=1.0, update=_sync_material_entry)
    transparency: bpy.props.FloatProperty(name="Transparency", default=0.0, min=0.0, max=1.0, update=_sync_material_entry)
    uv_scale: bpy.props.FloatProperty(name="UV Scale", default=1.0, min=0.01, soft_max=20.0, update=_sync_material_entry,
        description="Tiling repeat factor across the surface - 1.0 = texture spans the surface once, higher = smaller/more repeated tiles")


class OBJECT_OT_pbd_material_add(bpy.types.Operator):
    bl_idname = "pbd.material_add"
    bl_label = "Add PBD Material"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        entry = context.scene.pbd_materials.add()
        base = "material"
        name, i = base, 1
        existing = {m.name for m in context.scene.pbd_materials}
        while name in existing:
            i += 1
            name = f"{base}{i}"
        entry.name = name
        context.scene.pbd_materials_active_index = len(context.scene.pbd_materials) - 1
        return {'FINISHED'}


class OBJECT_OT_pbd_material_remove(bpy.types.Operator):
    bl_idname = "pbd.material_remove"
    bl_label = "Remove PBD Material"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        scene = context.scene
        idx = scene.pbd_materials_active_index
        if 0 <= idx < len(scene.pbd_materials):
            scene.pbd_materials.remove(idx)
            scene.pbd_materials_active_index = min(idx, len(scene.pbd_materials) - 1)
        return {'FINISHED'}


class OBJECT_OT_pbd_material_sync_from_objects(bpy.types.Operator):
    """Adds a catalog entry (default color/shininess, no texture) for
    every distinct pbd_material name already set on a PBD object that
    doesn't have one yet - the exact gap that makes a typed-but-not-added
    material's fields (UV scale, texture, normal map...) invisible: they
    only exist on catalog entries, and typing a name directly (prop_search
    allows free text) never creates one."""
    bl_idname = "pbd.material_sync_from_objects"
    bl_label = "Sync PBD Materials From Objects"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        existing = {m.name for m in context.scene.pbd_materials}
        used = {
            obj.pbd.pbd_material
            for obj in bpy.data.objects
            if obj.type == 'MESH' and getattr(obj, "pbd", None)
            and obj.pbd.is_pbd_primitive and obj.pbd.pbd_material
        }
        missing = sorted(used - existing)
        for name in missing:
            entry = context.scene.pbd_materials.add()
            entry.name = name
        self.report({'INFO'}, f"Added {len(missing)} material(s): {', '.join(missing) if missing else '(none missing)'}")
        return {'FINISHED'}


class OBJECT_OT_pbd_material_load(bpy.types.Operator):
    """Loads a .pbdmat file's entries into the scene catalog, on top of
    (not replacing) whatever's already there - reuses the engine's own
    grammar directly rather than a second parser, since it's a small,
    line-oriented format (see the docstring on the parsing helper below)."""
    bl_idname = "pbd.material_load"
    bl_label = "Load .pbdmat"
    bl_options = {'REGISTER', 'UNDO'}

    filepath: bpy.props.StringProperty(subtype='FILE_PATH')

    def invoke(self, context, event):
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}

    def execute(self, context):
        try:
            materials = _parse_pbdmat(self.filepath)
        except OSError as e:
            self.report({'ERROR'}, f"Could not read {self.filepath}: {e}")
            return {'CANCELLED'}

        scene = context.scene
        for name, fields in materials.items():
            entry = scene.pbd_materials.get(name)
            if entry is None:
                entry = scene.pbd_materials.add()
                entry.name = name
            if "color" in fields:
                entry.color = fields["color"]
            if "shininess" in fields:
                entry.shininess = fields["shininess"]
            if "specular" in fields:
                entry.specular = fields["specular"]
            mat_dir = os.path.dirname(os.path.abspath(self.filepath))
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
        self.report({'INFO'}, f"Loaded {len(materials)} material(s) from {self.filepath}")
        return {'FINISHED'}


def _parse_pbdmat(filepath):
    """Minimal reader for `material NAME { key=value ... }` blocks -
    deliberately small and line-oriented rather than importing the
    engine's own tokenizer logic wholesale, since this only needs to
    handle the handful of fields this panel edits (color/shininess/
    specular/texture), not the full grammar PbdMatParser.java supports."""
    materials = {}
    with open(filepath, "r", encoding="utf-8") as f:
        text = f.read()

    pos = 0
    while True:
        idx = text.find("material", pos)
        if idx == -1:
            break
        brace_open = text.find("{", idx)
        brace_close = text.find("}", brace_open)
        if brace_open == -1 or brace_close == -1:
            break
        name = text[idx + len("material"):brace_open].strip()
        body = text[brace_open + 1:brace_close]
        fields = {}
        for line in body.splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" not in line:
                continue
            key, _, raw = line.partition("=")
            key, raw = key.strip(), raw.strip()
            if key == "color":
                inner = raw.strip("()")
                parts = [float(p.strip()) for p in inner.split(",")]
                fields["color"] = tuple(parts) if len(parts) == 3 else (parts[0],) * 3
            elif key in ("shininess", "specular"):
                fields[key] = float(raw)
            elif key in ("texture", "normalMap", "roughnessMap", "displacementMap"):
                fields[key] = raw
            elif key in ("uvScale", "reflectivity", "transparency", "displacementScale"):
                fields[key] = float(raw)
        materials[name] = fields
        pos = brace_close + 1
    return materials


class OBJECT_OT_pbd_material_save(bpy.types.Operator):
    bl_idname = "pbd.material_save"
    bl_label = "Save .pbdmat"
    bl_options = {'REGISTER', 'UNDO'}

    filepath: bpy.props.StringProperty(subtype='FILE_PATH', default="materials.pbdmat")

    def invoke(self, context, event):
        context.window_manager.fileselect_add(self)
        return {'RUNNING_MODAL'}

    def execute(self, context):
        lines = []
        for entry in context.scene.pbd_materials:
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
        try:
            with open(self.filepath, "w", encoding="utf-8") as f:
                f.write("\n".join(lines))
        except OSError as e:
            self.report({'ERROR'}, f"Could not write {self.filepath}: {e}")
            return {'CANCELLED'}
        self.report({'INFO'}, f"Saved {len(context.scene.pbd_materials)} material(s) to {self.filepath}")
        return {'FINISHED'}


CLASSES = (
    PbdMaterialEntry,
    OBJECT_OT_pbd_material_add,
    OBJECT_OT_pbd_material_remove,
    OBJECT_OT_pbd_material_load,
    OBJECT_OT_pbd_material_save,
    OBJECT_OT_pbd_material_sync_from_objects,
    OBJECT_OT_pbd_refresh,
)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Scene.pbd_materials = bpy.props.CollectionProperty(type=PbdMaterialEntry)
    bpy.types.Scene.pbd_materials_active_index = bpy.props.IntProperty(default=0)
    bpy.types.Scene.pbd_project_root = bpy.props.StringProperty(
        name="PBD Project Root",
        subtype='DIR_PATH',
        default="",
        description="Folder containing this project's build.gradle.kts - used to find the shared materials/ folder and to launch the engine viewer for Export & Visualize",
    )


def unregister():
    del bpy.types.Scene.pbd_project_root
    del bpy.types.Scene.pbd_materials_active_index
    del bpy.types.Scene.pbd_materials
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
