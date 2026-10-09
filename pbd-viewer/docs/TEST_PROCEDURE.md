# Cross-format consistency test procedure

Run this whenever a feature touches the format, the engine, the Blender
addon, or the upload path — not just when a bug is reported. It exists
because this project has **two independent writers of the same minified
text shape** (`pbd.format.PbdSerializer` in Java, and `export_pbd.py`'s
own `_minify_pbd_text`/`_quote_if_needed` in the Blender addon) and at
least one reader (`PbdParser`) that all have to agree, and nothing today
checks that agreement automatically. The concrete bug this procedure was
written to have caught — a multi-word `kind`/`name` silently swallowing
an entire minified scene — is exactly a case where one side was fixed
(`PbdSerializer`, see its `quoteMetadataValue()` doc comment) and the
other (`export_pbd.py`) was not. See `docs/ROADMAP.md`, "Minified .pbd
not loading", and `tools/regression/RegressionMinifiedMetadataQuoting.java`.

Each layer below says what to run, what "pass" means, and which real file
in this repo it protects. Layers A–C are automatable today and should
run every time. D–G need a human or a Blender session; do them for any
change that touches what they cover.

## A. Format round-trip (automatable, no Blender/LWJGL needed)

For every format pair that's supposed to be lossless — `.pbd` text ↔
`PbdScene` ↔ `.pbdbin`, `.pbd` ↔ `.pbdasset` — round trip and diff the
*meaningful* fields (name/kind/authors/origin/description, every
instance's type/pos/rot/scale/mat, every modifier, every keyframe
including `sound=`, `vertexData` byte-for-byte after base64 decode, not
the base64 string itself since re-encoding can legally differ in padding
style).

Minimum fixture set per format change — put each new one under
`tools/regression/fixtures/` style data if it doesn't fit as an inline
string in a `Regression*.java` class:
- a scene with **multi-word** `name`/`kind`/`author`/`origin`/`description`
  (plain and quoted) — this is the exact family that broke twice
- a scene with at least one `mesh` instance carrying real `vertexData`
  (triggers `PbdMeshData.toBase64`/`fromBase64` and, combined with
  metadata, the quote-scanning hazard above)
- a scene with `pbd_ref`/`include_material`/`containerTrigger` — anything
  resolved against a base directory, since that resolution differs
  between a loose `.pbd` (its own parent dir) and a `.pbdbin` (fixed this
  project cycle to use `inputPath.toAbsolutePath().getParent()`, see
  `PbdBinFormat.read()`'s own comment)
- a scene with at least one `keyframe { … sound=… }` block
- run every fixture through **both pretty and minified** serialization,
  Java-side (`new PbdSerializer(true/false)`), not just one

Run: `sh tools/regression/run.sh` (needs `JOML_CLASSES`/`LWJGL_DIR`, see
the script's own header comment). A clean run prints
`ALL REGRESSION SUITES PASSED`; a `KNOWN-BUG` line is expected and fine,
a `FAIL` line is not — treat it like a failing suite.

## B. Java engine logic (LWJGL-free subset — compiles and runs directly)

Only 8 files import `org.lwjgl.*` (`Main`, `ClassicMeshRenderer`,
`GlWindow`, `MaterialTextureArray`, `PbdMeshCache`, `PbdRenderer`,
`SkydomeRenderer`, `TextureLoader` — 9 symbols total: `GLFW*`, `GL`,
`GL43`, `STBImage`, `MemoryStack`, `MemoryUtil*`). Everything else —
`pbd.format`, `pbd.pz`, `pbd.net`, `pbd.fbx`, `pbd.voxel`, `pbd.audio`,
`pbd.sky`, and `pbd.classicmesh.{ObjMesh,ObjParser}` — compiles and runs
with just JOML on the classpath, no GPU/display needed:

```sh
javac -cp JOML_CLASSES -d out $(find src/main/java/pbd/format \
  src/main/java/pbd/pz src/main/java/pbd/net src/main/java/pbd/fbx \
  src/main/java/pbd/voxel src/main/java/pbd/audio src/main/java/pbd/sky \
  -name "*.java") src/main/java/pbd/classicmesh/ObjMesh.java \
  src/main/java/pbd/classicmesh/ObjParser.java src/main/java/pbd/PbdEngine.java
```

Use this to write a throwaway repro (`PbdEngine engine = new PbdEngine();
engine.load(...)`/`engine.loadAny(...)`) for anything reported against
parsing, voxelization, modifiers, or the format layer, **before**
assuming it needs a real window — most reported "engine" bugs turn out
to live here, not in `PbdRenderer`.

## C. The two serializers, cross-checked against each other

This is the step that would have caught the minified-metadata bug
immediately, and doesn't exist as an automated check yet:

1. Build one `.pbd` fixture scene by hand (multi-word metadata + a mesh,
   as in A above).
2. Produce minified text two ways:
   - Java: `new PbdSerializer(false).serialize(scene)`
   - Python: feed the same pretty text through `export_pbd.py`'s
     `_minify_pbd_text` (importable standalone — it and
     `_quote_if_needed` touch no `bpy`, see the extraction trick in the
     Blender stub section below if the surrounding module's `bpy` import
     gets in the way)
3. Parse BOTH outputs with the same `PbdParser` and assert they produce
   the same `PbdScene` (same name/kind/instances/etc). A mismatch here
   means the two writers have drifted — fix whichever is wrong relative
   to `PbdParser`'s actual, current reading rules (not relative to the
   other writer, which may itself be wrong).

Do this for every field `export_pbd.py` writes independently of the Java
serializer whenever either one changes its quoting/escaping rules.

## D. Blender addon (stub-based, no Blender install needed)

`bpy`/`mathutils`/`bmesh`/`bpy_extras` can be stood in with permissive
`_Auto`/`_AutoModule`/`_AutoClass` objects so the addon's real control
flow executes outside Blender (proven against `import_pbd.py` this
project cycle). Use this for anything that's really string/data-shape
logic wearing a `bpy` costume: import/export field handling,
`pbd_ref`/`mesh` whitelists, LOD field naming, material include modes.
It will NOT catch anything that depends on Blender's actual mesh/object
data model behaving a specific way (that's section E). When a stubbed
run and a real-Blender run would plausibly disagree, say so rather than
reporting only the stub result.

## E. Blender's own rendering/preview (manual — needs Blender)

No substitute for opening the file. For any change touching geometry,
materials, or `mesh_preview.py`: load the fixture set from A in Blender,
confirm the viewport matches what the voxelizer/engine computed
(especially after a primitive-formula change — `RegressionPrimitives`
checks volume ratios numerically, but a visibly wrong orientation or
flipped normal won't show up as a wrong number), and check
`ui_panel.py`'s draw path for anything newly reachable (a new field, a
new conditional section) with the full range of object states it can
encounter — a draw-time `AttributeError` here fails silently into
Blender's console, not a clean exception a test harness catches.

## F. Documentation vs. reality

Comments in this codebase routinely explain *why*, including past bugs
and fixes (e.g. `PbdSerializer.quoteMetadataValue`'s own comment). That's
valuable and worth keeping, but a comment claiming something is fixed is
a claim, not a test — re-verify it against current behavior, not just
against its own prose, whenever you touch the surrounding code (this is
exactly how the minified-metadata bug was found: the comment's fix was
real but incomplete, and only running it against a real asset showed
the gap). After any bug fix: grep `docs/ROADMAP.md` for the issue before
writing a new entry — it may already be logged (as "Minified .pbd not
loading" was) waiting on exactly the repro you just built.

## G. Server-side contract (PHP server itself is out of this repo)

This checkout has the Blender-side client
(`tools/blender-addon/pbd_tools/pbd_tools/operators/upload_server.py`)
but not the PHP server it talks to. Test what's visible here: the
request shape `upload_server.py` builds (fields, multipart layout,
expected status codes) and its error handling (the reported
`ModuleNotFoundError`/"No files submitted" cases belong here). Any
check of the server's own behavior has to run in the server's own repo
against this same request shape — note that boundary explicitly in a
bug report rather than guessing at server-side code this checkout
doesn't contain.

## Adding a new feature: the recurring checklist

1. Add a fixture exercising it to section A's set; run both pretty and
   minified.
2. If it adds a new `PbdParser`/`PbdSerializer` field, add the matching
   write in `export_pbd.py` in the same change and cross-check per C —
   don't let the two drift for even one commit.
3. Run section B's compile+run for the format/voxel/logic layers it
   touches.
4. If it touches addon UI or export options, run D, then do E by hand.
5. Update `docs/ROADMAP.md` with the outcome — done, done-and-tested
   (state what "tested" meant), or pending with what's left — in this
   project's existing style (including self-corrections when a first
   attempt turns out wrong; that history is a feature, not noise).
