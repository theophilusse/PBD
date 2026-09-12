bl_info = {
    "name": "PBD Format Tools",
    "author": "pbd-benchmark",
    "version": (0, 1, 0),
    "blender": (4, 0, 0),
    "location": "View3D > Sidebar > PBD tab, Add > Mesh, File > Export",
    "description": "Create and export PBD (Primitive Based Description) primitives",
    "category": "Import-Export",
}

from . import materials
from . import properties
from . import ui_panel
from .operators import add_primitive
from .operators import export_pbd
from .operators import import_pbd

_MODULES = (materials, properties, add_primitive, export_pbd, import_pbd, ui_panel)


def register():
    for module in _MODULES:
        module.register()


def unregister():
    for module in reversed(_MODULES):
        module.unregister()
