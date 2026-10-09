# Status summary (distilled from docs/ROADMAP.md)

This is a status-by-feature snapshot, not a replacement for
`docs/ROADMAP.md` — the roadmap is the chronological record (including
the self-corrections, which are worth keeping); this groups its current
end-state by area so it can be read in one pass.

Four statuses, used deliberately narrowly:
- **not done** — not built, or explicitly rolled back/disabled.
- **pending** — partially done, or done but with an open, named gap.
- **done** — the roadmap's own entry claims it fixed/verified, with real
  supporting detail (numbers, a specific repro) in its own text — but
  *this review* did not re-run or re-read the code to confirm it still
  holds today.
- **done-and-tested** — independently re-verified **this session**, by
  actually running it against the current code, not by reading the
  roadmap's account of it. Given this project's own history (bend and
  taper were each confidently marked fixed and then found wrong more
  than once), "the roadmap says so" and "I just watched it pass" are
  kept visibly separate on purpose.

## Format layer (.pbd / .pbdbin / .pbdasset)

| Item | Status | Note |
|---|---|---|
| Plain `.pbd` multi-word scene metadata (`kind = static prop container`) | **done-and-tested** | Re-verified this session against the real `appliances_cooking_01_44.pbd`. |
| `include_material` round-trip through `.pbdbin` | **done** | Confirmed by reading `PbdBinFormat`/`PbdSerializer` source this session; mechanism is sound, not exercised against a real asset with `include_material` specifically. |
| **Minified metadata swallows the rest of the file** (`.pbdbin`, or `.pbd` exported with "minify") | **pending — root cause now found, not fixed** | Was logged as "not yet investigated"; see "Headline finding" below. This is the single most severe open bug: it affects essentially every Blender-exported minified asset, with silent total data loss. |
| `vertexData=`/base64 `=`-padding truncated by `readRawValue()` stopping at `=` | **not done** | Re-confirmed broken this session against real `mushroom.pbd` and an isolated repro. Separate bug from the metadata one above — same symptom family (parser stops too early) but a different code path (`readRawValue`'s bare-word branch, not `readMetadataValue`). |
| LOD tier field (`vertexDataLod<N>`) mismatch (Blender writes 0/1, Java "expects" powers of 2) | **pending** | Roadmap's own reading of `PbdParser.applyInstanceField`/`Main.java` concludes the Java side has no such requirement (it's just an ordering key) — so the bug is more likely in the Blender addon's own LOD UI/export. Not yet checked there. |

## Voxel destruction (G-key system)

| Item | Status | Note |
|---|---|---|
| Primitive voxelization formulas (cube/cylinder/cone/sphere/torus/disc/plane) | **done-and-tested** | Re-ran `RegressionPrimitives` this session; ratios match π/4, π/12, π/6, ~0.155 as logged. |
| Taper applied during voxelization | **done-and-tested** | Re-ran `RegressionTaper`/`RegressionTaperWide` this session; 1.00017 as logged. |
| Bend applied during voxelization | **not done** | The math is solved (closed-form inverse, 300k-trial verified per the roadmap) but not wired into the voxelizer — grid extent, the wrapper itself, and targeting are all still open. **Also: an older roadmap entry ("Bend modifier — proper fix") asks for a different technique entirely (Blender-style loop-cut + Simple Deform) and doesn't look reconciled with the newer polar-geometry solve — worth confirming with you which direction is still wanted before anyone builds either one.** |
| Twist/curve/shear during voxelization | **not done** | Same architectural gap as bend/taper originally; no entry indicates either was started. |
| Per-voxel texture color sampling | **done** | Re-enabled, per-vertex (not per-fragment) interpolation, UV fixed for non-cubic objects, double-decode crash fixed — all per the roadmap's own numbers. Needs a real GL context to re-verify; not re-run this session. |
| Textured-primitive G-key native crash | **pending** | Roadmap's own words: "mitigated, not confirmed fixed." Still genuinely open. |
| Shared/cached shader program (fixes repeated-compile driver instability) | **done** | Per roadmap; not re-run this session (needs a GL context). |
| Crater OOM at wide blast radius | **done** | 43 ms / ~2 MB / 40,633 entries at radius 14 under `-Xmx512m`, per roadmap. `RegressionCraterOOM` needs `PbdRenderer` (LWJGL), which this session's sandbox can't link — not independently re-run. |
| NaN hit-distance on raycast against a hidden instance; door-click-while-hidden | **done** | Per roadmap (skip hidden instances pre-test, NaN-guard in `rayBoxIntersection`, live transform for click-toggle). Not yet independently re-read against the actual current `PbdRenderer` code this session — still on the list to confirm by hand (no GL context to execute it). |
| Voxel debris untargetable by both raycast systems | **done** | Per roadmap (shared shader fix, see above). |
| `gridOriginLocal` wrong for non-cubic objects | **done** | Concrete before/after (Z=138 → Z=57, correctly inside range) in the roadmap's own text. |
| Anisotropic octree subdivision for thin geometry | **pending** | Explicitly flagged in its own entry as "NOT a full fix" — mitigated (26,596 → 3,916 regions) but the real fix (independent per-axis octree splitting) is deferred. |
| `destroyedRenderers`/`destroyedGridOrigins` leak on scene switch | **done** | Per roadmap; explicitly only a "strong candidate" for the separately-reported N-key crash, not a confirmed match. |
| G-key logic moved behind `PbdRenderer` facade (architecture) | **done** | Roadmap reports re-running the full suite post-refactor with identical numbers. Consistent with this session's own re-run of 4 of 5 suites against current code. |
| "Rays pass through voxels" | **pending** | Latest roadmap pass couldn't reproduce or add anything new; needs a fresh repro with console output (position/direction) to make progress. |

## Blender addon (Python)

| Item | Status | Note |
|---|---|---|
| `export_pbd.py`'s own minifier (`_minify_pbd_text`/`_quote_if_needed`) | **not done — newly found this session** | `kind` is written with no quoting call at all; `name`/`author`/`origin` are only quoted if they contain a space. Either gap reproduces the metadata-swallowing bug independently of the Java-side fix. Not previously diagnosed — the roadmap only had the symptom logged. |
| `import_pbd.py` name=/kind=/author= handling, `pbd_ref`/`mesh` whitelist | **pending — not yet re-checked against the real file** | Known bugs against the *legacy* snapshot; not yet re-verified against this project's actual current `import_pbd.py`. |
| `upload_server.py` `ModuleNotFoundError`, "No files submitted" | **pending — not yet investigated this session** | From the Phase-10 evidence batch; still open. |
| `ui_panel.py` draw-time `AttributeError` on keyframe-sound creation | **pending — not yet investigated this session** | From the Phase-10 evidence batch; still open. |
| Scene `description=` field has no UI | **pending** | Format supports it; panel doesn't expose it. Logged plainly as open in the roadmap itself. |
| Standalone `.pbdmat` export | **not done** | Explicitly listed as not attempted. |
| Remote `.pbdmat` not shown in Material panel | **pending — not investigated** | |
| `.pbdasset` ~29 MB bloat | **pending — not yet investigated this session** | From the Phase-10 evidence batch. |

## Build / dependencies

| Item | Status | Note |
|---|---|---|
| JOML `Vector3fc` classpath error | **pending — not yet checked against real `build.gradle.kts`** | From the Phase-10 evidence batch. |

## Facade / modding API

| Item | Status | Note |
|---|---|---|
| `availableVolume`/`canHide` | **done-and-tested (per roadmap's own reflection-based test)** | Not independently re-run this session; the method is straightforward and the roadmap's own verification is concrete (8→7, true/false at the boundary). |
| Door-closed facade accessor | **not done** | Proposed only. |
| Container refill-on-reopen rework | **not done** | Current (wrong) behavior identified; fix path named; not built. |
| H-key exit inverse keyframe | **not done** | Needs reading current hide-transition code first. |

## Audio

| Item | Status | Note |
|---|---|---|
| `doomshotgun.mp3` trigger | **pending** | Trigger is wired; `SoundPlayer` only decodes `.wav`. Needs either a converted file or a new MP3-decoding dependency. |

## Large features not started

| Item | Status | Note |
|---|---|---|
| Lever-arm system (free rotation / lockable position, cross-format, AI-readable) | **not done** | Explicitly flagged as needing its own design pass across both languages, every format, and possibly the PHP server, before implementation starts. |
| Web-based visualizer + documentation refresh | **not done** | "Eventually" item. |

## Docs / tutorial

| Item | Status | Note |
|---|---|---|
| `server/tutorial.php` reuse + quality-submission sections | **done (per roadmap)** | Content verified against actual addon source, not memory, per the roadmap's own account. **Note**: this review has no `server/` directory to check — it isn't in `tree.txt` or in the archives you sent, consistent with the PHP server being a separate deployment from this repo (matches `upload_server.py` being a client only, with nothing server-side in this checkout). |

## Headline finding this session: minified-file metadata corruption

Confirmed against your own real `blueMetalBoard.pbdbin`: loading it
collapses the entire ~17-instance scene into the `name` field
(`kind=null`, 0 instances recovered), and re-serializing/re-parsing what
*is* recovered then throws `IllegalStateException`. Root cause, fully
reproduced against current code (not inferred):

- `PbdParser.readMetadataValue()` hands a quoted value to
  `readRawValue()`, which scans for the **next literal `"` anywhere in
  the remaining text** — no concept of "this field," "this instance,"
  or "this line." In a minified file (one line, no real `\n`), that's
  only safe if nothing else in the file has a stray quote before the
  field's own intended close.
- The Java-side serializer was already fixed to always quote metadata in
  minified mode (see its own `quoteMetadataValue()` comment) — and this
  session's own test (`RegressionMinifiedMetadataQuoting`, now in
  `tools/regression/`) confirms that fix holds for a Java-only
  round-trip.
- **The Blender addon's independent minifier was never updated to
  match.** `export_pbd.py` writes `kind` with no quoting at all, and
  quotes `name`/`author`/`origin` only if they contain a space. Either
  gap reproduces the exact swallow-to-EOF failure — confirmed by running
  the addon's own `_minify_pbd_text`/`_quote_if_needed` directly against
  a clean multi-word `kind` and watching it consume the entire rest of
  the file.

This was already on your radar (`docs/ROADMAP.md`, "Minified .pbd not
loading (bug, not yet investigated)") — it's now fully diagnosed rather
than just reported. A real fix needs `export_pbd.py` to quote
unconditionally in minified mode (matching the Java side's own fix) and,
separately, both quoting functions eventually need to escape a literal
`"` inside a value — neither handles that today.
