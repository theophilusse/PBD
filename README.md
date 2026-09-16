# PBD — Primitive Based Description

A scene/asset format and Java engine built around one idea: most game
props are close enough to a handful of primitives (boxes, cylinders,
cones, discs, tori, planes) that describing them *as* primitives —
position, scale, a couple of modifiers, a material — is both far
smaller on disk and far easier to hand-edit than a traditional
triangle mesh, while still falling back to a real mesh for anything
that genuinely needs one.

Originally built for **Project Zomboid**, as a from-scratch FPS-mode
engine (`tileGeometry`-compatible) and a Blender add-on to author
content for it. Not currently part of the PZFPS mod itself — see
[Status](#status) below.

## Why

A traditional OBJ/FBX export of a simple prop is routinely 50-60x
larger on disk than the same shape described as PBD primitives - see
`docs/renders/` for example scenes. That difference compounds across
a real asset library: slower downloads for players on modest
connections, more to hold in memory, more to upload for anyone
distributing a modded server.

PBD doesn't replace meshes - it sits alongside them. A `mesh`
primitive type exists specifically for organic/complex shapes that
don't decompose into primitives cleanly, so a scene can freely mix
both.

## What's actually in this repo

- **A text format** (`.pbd`) - human-readable, diffable, hand-editable.
  See `docs/PBD_FORMAT_SPEC.md`.
- **Two compiled variants**:
  - `.pbdbin` - gzip-compressed minified text. Small, fast to parse,
    not human-readable.
  - `.pbdasset` - a zip bundling a scene with its materials and
    textures into one self-contained file.
- **A Java engine** (LWJGL/OpenGL) that loads and renders `.pbd` /
  `.pbdbin` / `.pbdasset` directly - procedural primitives are
  tessellated and cached, not pre-baked to disk.
- **A Blender add-on** (`tools/blender-addon/pbd_tools/`) to author
  scenes: place primitives, assign materials, keyframe animations,
  and export back to any of the three formats - including straight
  back into `.pbdasset`/`.pbdbin`, which the add-on can also
  **re-import**, so round-tripping through the compiled formats
  doesn't require going back through the source `.pbd`.
- **Remote references** - a `pbd_ref` can point at a URL instead of a
  local file; the engine fetches and caches it on load. Anything from
  a non-project host prompts for confirmation before downloading (see
  `PbdFetcher`) - a scene file can name any server it wants, and
  nothing about loading a scene should silently start pulling from
  wherever a `pbd_ref` happens to point.

## Features beyond static geometry

These are the ones a traditional static mesh format doesn't have an
equivalent for at all, not just a smaller version of:

- **Keyframe animation** on any instance - door swings, water level in
  a barrel, anything expressible as position/rotation over time.
  Keyframes can carry a sound file, played once when crossed.
- **Growth/weathering channels** - a `growthDays`-tagged modifier lets
  a material respond to accumulated in-world time (moss or mushrooms
  appearing on wood left out in the rain, say) without any extra
  per-frame authoring.
- **Procedural container contents** - a `metadata=true` volume linked
  to one or more doors bin-packs a random selection of props from an
  asset pool into itself the moment it's first opened, cached from
  then on. Individual items can be picked out (aimed at + a keypress)
  and physically removed, with whatever was resting above it marked
  as falling.
- **LOD as data, not just tessellation detail** - a `mesh` instance
  can carry additional lower-detail geometry variants
  (`vertexDataLod<N>=`) alongside its base shape, which the engine
  switches between on the same global LOD control that already
  governs procedural tessellation detail.

## Status

**As of September 2026, this is not part of the PZFPS mod.** It was
proposed to that mod's team and turned down - existing contributors
are already comfortable with their current traditional-mesh pipeline,
and introducing a second format was, reasonably, judged not worth the
disruption at this stage. This repository is an independent,
standalone project: the engine, format, and tooling all work without
PZFPS and don't depend on it.

If you're evaluating PBD for your own project: the format and engine
are functional and the numbers above are real, measured against this
repo's own test assets - but this is a young, single-maintainer
project. Expect rough edges, and treat anything not explicitly listed
above as not yet implemented rather than assuming it's covered.

## Getting started

- **Format reference:** `docs/PBD_FORMAT_SPEC.md`
- **Engine internals / building from source:** `docs/ENGINE_DEV_GUIDE.md`
- **Blender add-on usage:** `docs/ADDON_USER_GUIDE.md`
- **Asset sharing site (pbd.mazetrojan.fr) source:** `server/` - upload
  review, moderation, and browsing for shared assets; see its own
  README for setup.

```bash
./gradlew run --args="path/to/scene.pbd"
```

## Contributing

Issues and PRs are welcome - this is exactly the kind of project that
benefits from more eyes on the format and more real assets tested
against it. If you're planning something larger than a small fix,
opening an issue first to discuss it is worth doing before investing
a lot of time into it.

## License

Not yet chosen - add a `LICENSE` file before relying on this being
open source in any formal sense. Given the pitch to potential
contributors, an OSI-approved permissive license (MIT, Apache-2.0) is
worth picking soon rather than leaving this unspecified.
