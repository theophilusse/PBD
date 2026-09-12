"""
Reads an object's rotation/location f-curves across Blender's animation
API versions. Blender 4.4 introduced Action Slots: f-curves moved from
`action.fcurves` into a per-slot Channelbag. `action.fcurves` kept
working through the 4.x series as a deprecated proxy for the first
slot/layer/strip - but per Blender's own migration docs, that proxy is
"considered deprecated, and will be removed in Blender 5.0", and by 5.0
`action.fcurves` raises AttributeError outright ('Action' object has no
attribute 'fcurves').

This project's own test Blender is 4.0.2 (pre-slots), so the new-API path
below is written directly from Blender's official 4.4 migration
documentation, not verified against a real 4.4+/5.0 install - if
keyframes still don't export/show on a specific Blender version, that's
the first place to look.
"""

import bpy


def get_object_fcurves(obj):
    """Every f-curve on obj's active action, regardless of Blender's
    animation-data API version. Returns [] if obj has no action at all -
    the normal case, not an error."""
    if not obj.animation_data or not obj.animation_data.action:
        return []
    action = obj.animation_data.action

    # Blender 4.4+: f-curves live in a channelbag tied to a specific
    # slot, not directly on the action. action_slot only exists on 4.4+
    # AnimData - its absence means this is pre-4.4, where the legacy
    # path below is simply the normal, correct API, not a fallback.
    action_slot = getattr(obj.animation_data, "action_slot", None)
    if action_slot is not None:
        try:
            from bpy_extras import anim_utils
            channelbag = anim_utils.action_get_channelbag_for_slot(action, action_slot)
            if channelbag is not None:
                return list(channelbag.fcurves)
        except (ImportError, AttributeError):
            pass  # fall through to the legacy path below

    # Pre-4.4 Blender, or 4.4-4.x if the slot lookup above didn't apply
    # (e.g. no slot got auto-assigned - a known rough edge in early 4.4,
    # see Blender's own release notes) - action.fcurves still works here
    # as either the real API (pre-4.4) or the deprecated proxy (4.4-4.x).
    # Removed outright in 5.0, so this must stay wrapped in try/except:
    # letting that AttributeError propagate would take the whole export
    # down with it, not just silently skip this object's keyframes.
    try:
        return list(action.fcurves)
    except AttributeError:
        return []


def find_fcurve(fcurves, data_path, index):
    """list.find() equivalent for the plain list get_object_fcurves returns (fcurves.find() is a method on Blender's own FCurves collection, not on a plain Python list)."""
    for fc in fcurves:
        if fc.data_path == data_path and fc.array_index == index:
            return fc
    return None
