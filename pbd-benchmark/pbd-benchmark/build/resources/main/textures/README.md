# Textures

## night_sky.jpg (not included)

The skydome (`pbd.render.SkydomeRenderer`) looks for a night-sky texture
at `night_sky.jpg` in this folder. It isn't bundled here - copy your own
file to this exact path and name:

```
src/main/resources/textures/night_sky.jpg
```

Use a **tonemapped LDR image** (`.jpg` or `.png`), not an HDR `.exr` and
not a Blender/Godot-native resource (`.blend`, `.tres`, `.usdc`) - none of
those are image formats `TextureLoader` (stb_image) can read. For a pack
like `NightSkyHDRI003`, that means the `_4K_TONEMAPPED.jpg` file
specifically, not the `_4K_HDR.exr` one.

The image should be **equirectangular** (spans the full sky: U = 0..1
around the horizon, V = 0..1 from straight up to straight down) - that's
the mapping `skydome.frag` samples with.

If the file is missing, the app doesn't crash: `TextureLoader.load2D`
logs a message to stderr and the night sky falls back to a plain dark
color (see `SkydomeRenderer`/`PbdRenderer` for the same "degrade
gracefully, never throw for a missing optional asset" pattern used
elsewhere in this project).
