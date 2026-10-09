# Roadmap

## Lever-arm drag-and-drop: simple geometric prototype built (no physics engine - chosen explicitly over the alternative)

Asked for right after the correction below shipped: given the choice between a quick geometric prototype (no new dependency, can't be verified visually here either way) and integrating a real physics library (bigger, also unverifiable here, and this codebase has none today), a simple prototype now, with real physics as a later option, was the one picked.

How it works: left-click now branches on what's under the crosshair at the moment the button goes down - a keyframed instance still gets the old single-click scripted toggle (`toggleKeyframedInstanceAlongRay`, narrowed to keyframe-only - see its own doc), while a lever-arm instance is grabbed instead (`PbdRenderer.findLeverArmGrabPoint`, reusing the exact same ray-vs-oriented-box test via a newly-shared `findClosestInstanceAlongRay` helper) and the grabbed point's own LOCAL position is remembered. Every frame the button stays down, `PbdRenderer.dragArmValueTowardRay` samples the arm value in 40 steps across `[0,1]`, evaluates what world transform the instance WOULD have at each one (`previewWorldTransform`, via a throwaway `HierarchyResolver` call - doesn't mutate anything), and commits (`setArmValue`) whichever value puts the grabbed point closest to THIS frame's ray. On release, `setOpen` takes over and eases the rest of the way to whichever end is now closer, reusing the already-existing scripted/sound/`linkGroup` behavior rather than inventing separate "let go" logic.

Deliberately simple, and deliberately general: sampling rather than a closed-form angle solve means this works for a pure rotation, a pure slide, or a combined arm alike, with no assumption about a single fixed hinge axis - the honest tradeoff is less precision than a real solve (a 1/40 step) and a per-frame cost (41 whole-scene `HierarchyResolver` passes while actively dragging - cheap for scenes this project's own examples run, revisit if a scene is ever large enough for that to matter).

Caught and fixed in the same pass, from this project's own documented precedent (a scene switch leaving a stale destroyed-instance index behind, fixed earlier - see that entry further down): a drag in progress when N/B switches scenes would have left a stale instance index pointed at the NEW scene's (possibly smaller) instance list. Reset alongside the exact same `containerContents.clear()`/`renderer.clearDestroyed()` scene-load point that already exists for this reason.

Also added: `PBD_FORMAT_SPEC.md` never actually documented the `leverArm {}` block's syntax at all (an oversight from when the mechanism first shipped, below) - added a `## Lever-arm` section matching `## Keyframes`'s own style and placement.

Partially verified by execution, now - the core numerical question (does the 40-sample search actually converge on the right arm value, rather than some sign-flipped or axis-swapped value that still happens to compile) was checked for real: `dragArmValueTowardRay`'s search loop and `previewWorldTransform` were copied verbatim into a standalone harness (not retyped from memory) that parses a real one-door `leverArm` scene through the actual `PbdEngine`/`PbdParser`, and calls the actual, already-compiled `HierarchyResolver` - none of that needs LWJGL, only `PbdRenderer.java` itself does. For six known arm values (0.0, 0.25, 0.33, 0.5, 0.725, 1.0) plus a ray built to look exactly at each value's true grab-point location, the search recovered every one within 0.02 - including a discriminating case built specifically to rule out the metric trivially agreeing with itself (a ray aimed at the CLOSED point's location, checked that it resolves near 0.0 and not 1.0 or something else). All checks passed.

Still not, and can't be from here: the LWJGL-bound plumbing around that math - the GLFW mouse-down/mouse-held wiring in `Main.java`, screen-to-world ray construction, and the click branching between drag-start and the keyframe fallback - none of that compiles in this sandbox, so it's reviewed by careful reading only, not run. And "does dragging feel right" (the step size, how forgiving the grab point's pickup radius feels, whether 40 samples/frame is smooth enough) is inherently a thing to feel at a keyboard, not something a harness can answer. Recommend actually grabbing a door in-engine before recording video 2 - the number-crunching underneath it is now confirmed sound, but that's a different claim from "it feels good to drag."

## Lever-arm correction: the arm value needed to be settable from OUTSIDE the scripted easing, not just toggled (facade bug fix + setArmValue/getArmValue added)

Caught immediately after the entry just below shipped, from feedback on it: that first pass assumed a lever-arm's 0..1 value would always be driven by `PbdRenderer`'s own `speed`-based easing, toggled on/off like a keyframed door. Wrong for the actual intent - the open/closed ENDPOINTS are authored fixed points, but the motion between them was never meant to be necessarily scripted; the specifically wanted case is a mouse drag-and-drop with real physics, where the door's openness at any instant is whatever the drag/physics computes that frame, not something this class eases toward on its own clock.

That wrong assumption had leaked into real logic, not just the easing itself - two fixes:
- `isInstanceOpen`/`isInstanceFullyOpen`/`isInstanceFullyClosed` required `instanceOpen` (a discrete toggle-intent flag, set only by a click or `setOpen`) to AGREE with the continuous arm value for a lever-arm instance. A door driven any other way - a drag, for instance - could sit fully open at arm value 1.0 and still report as closed to the facade, so container contents would never appear: exactly the "link to the other modules - facade - like what's displayed inside" this was asked to get right. Fixed: for a lever-arm instance, all three now read `instanceArmValue` directly and ignore `instanceOpen` entirely. `instanceOpen` remains meaningful only for the keyframe mechanism, and as the scripted-easing TARGET in `updateAnimation` - never as something the facade checks for a lever-arm instance.
- There was no way to set the value directly at all - `updateAnimation`'s easing loop was the only writer, so there was nothing an external drag system could even call. Added `PbdRenderer.setArmValue(instanceId, value)` (clamped to [0,1], same `linkGroup` propagation as `setOpen`) as that missing write path, and `PbdRenderer.getArmValue(instanceId)` as the read side - this is the roadmap's long-pending `getArmRotationValue` accessor, named `ArmValue` instead since the value isn't necessarily a pure rotation (a sliding or combined arm has no single rotation to report). A new per-instance flag (`armExternallyDriven`) stops `updateAnimation`'s own easing from fighting a `setArmValue` caller every frame by skipping that instance entirely while the flag is set; `setOpen`/`toggleKeyframedInstanceAlongRay` (the existing click/scripted path) clear it, hand control back to scripted easing.

At the time this fix shipped, the actual drag-and-drop interaction was deliberately left for a follow-up decision (no physics engine exists anywhere in this codebase, and the real mechanics were an open question not worth guessing at blind) - see the entry above, written right after: a simple geometric prototype now exists, built against exactly the `setArmValue`/`getArmValue` seam this entry describes, which is what that seam was for.

## Lever-arm mechanism shipped as the door-keyframe replacement, format-wide (MVP DONE, but see the correction above - three backlog items deliberately deferred)

Requested so every container door currently animated via keyframes could be switched over before video 2 is recorded - keyframe-interpolated swings were reported to produce visual glitches, and the backlog entry below (now trimmed to just what's still open) had already proposed this exact replacement.

Design: an instance's own base `position`/`rotationDeg` is pose 0 (closed); a new `leverArm { openPos openRot speed }` block defines pose 1 (open) - both `openPos`/`openRot` independently nullable, falling back to the base pose; `speed` is arm-units/second (default 1.0, must be > 0). A continuous value in [0,1] drives a 2-point lerp between the two poses, eased the same way keyframe playback already eased, just driven by this value instead of animation time.

Wired everywhere the format lives:
- **`.pbd` text**: new `leverArm {}` block in `PbdInstance`/`PbdParser`/`PbdSerializer` (pretty and minified both).
- **Java engine**: `HierarchyResolver` gained a 3-arg `resolve(scene, animTime[], armValue[])` overload (keyframes still win if an instance somehow has both); `PbdRenderer` gained a parallel `instanceArmValue[]` array, an `updateAnimation` branch that eases it toward 0/1 at `speed` units/sec, and - the one easy-to-miss piece - `isInstanceFullyOpen`/`isInstanceFullyClosed` now also check it, which is what actually lets a lever-arm door's container contents show/hide correctly; without that half of the wiring the door would animate but its contents would never appear.
- **Blender addon**: new UI fields + a "Capture Current Pose as Open" button (`properties.py`, `ui_panel.py`), and `export_pbd.py`/`import_pbd.py` emission + parsing, deliberately reusing the SAME axis-conversion pipeline (`_to_pbd_matrix`/`_to_blender_matrix`) already used for the base pose, so the open pose can't drift into a different coordinate convention than the closed one.
- **`.pbdbin`/`.pbdasset`**: confirmed free - `.pbdbin` is gzip over `PbdSerializer`'s own minified text and `.pbdasset` zip-embeds `scene.pbd`'s raw bytes unchanged, so neither reimplements the schema and both already carry `leverArm` data with no extra code.

Verified: 19 checks in `tools/regression/RegressionLeverArm.java` (wired into `run.sh`) - parsing, pretty+minified round-trips, lerp at several arm values, clamping outside [0,1], no-data/NaN fallback to the closed pose, and keyframe-wins-if-both precedence. The Blender addon side can't run here either (no real Blender), so it was verified separately: the real `_to_pbd_matrix`/`_to_blender_matrix`/`_lever_arm_from_fields` functions extracted via `ast` straight out of the edited source files and run against a genuinely-installed standalone `mathutils` package (not a stub) - 19/19 checks, including a 5-case export-then-import round trip proving the two conversions are true mathematical inverses for a lever-arm's open pose.

Deliberately NOT built (MVP scope, not needed for the door use case this was requested for - see the trimmed backlog entry below): generic multi-primitive attachment, and per-motion SFX on the arm itself (today only a keyframe can carry `sound=`). A public accessor for AI/modding WAS initially left out here too, but that turned out to be wrong, not just incomplete - see the correction entry above, which also fixes a real facade bug this version shipped with.

Flagged, not fixed: whether the remote moderation server (`server/upload.php`, not present in this sandbox - lives on pbd.mazetrojan.fr) does anything `.pbd`-structure-aware that a new block type could trip up is unknown from here. The Blender-side upload client (`upload_server.py`) does no block-level validation at all, so it won't block a submission either way. And - same caveat as every other `PbdRenderer.java` change in this project's history - the engine-side half was never actually rendered; LWJGL doesn't exist in this sandbox, so it's verified by careful reading and brace-by-brace review, not execution. Recommend opening one lever-arm door in-engine before recording video 2.

## Voxel-destruction radius raised 14 -> 20 for video-1 visibility, stress-tested empirically first (DONE, recommend one test shot before recording)

Requested so destruction reads clearly on camera for video 1. `CRATER_RADIUS_VOXELS` was the one constant most directly controlling how much geometry a single hit visibly removes, but raising it blindly risked reopening the crater OOM already fixed once at a wider radius elsewhere in this file.

Verified the real cost first rather than guessing: `computeCraterRemovedSet`/`applyCraterAdaptive` copied VERBATIM (not reimplemented or approximated) into a standalone, dependency-free harness and run under this project's own documented `-Xmx512m` test condition at radius 14/20/28/40/56 against a worst-case single 512^3 region:

```
radius=14   rayPhaseMs=10.4   adaptiveMs=20.4    totalMs=30.8    removedVoxels=344   survivingEntries=40208    heapDeltaMB=2.7
radius=20   rayPhaseMs=4.1    adaptiveMs=43.9    totalMs=47.9    removedVoxels=514   survivingEntries=109814   heapDeltaMB=6.9
radius=28   rayPhaseMs=15.8   adaptiveMs=66.1    totalMs=81.9    removedVoxels=742   survivingEntries=286042   heapDeltaMB=17.6
radius=40   rayPhaseMs=3.6    adaptiveMs=146.6   totalMs=150.2   removedVoxels=1026  survivingEntries=805718   heapDeltaMB=38.8
radius=56   rayPhaseMs=7.8    adaptiveMs=396.1   totalMs=403.8   removedVoxels=1593  survivingEntries=2152063  heapDeltaMB=96.0
```

The radius=14 row (40208 entries / ~2.7MB / ~31ms) lines up closely with the baseline ALREADY documented elsewhere in this file for the real renderer (40633 entries / ~2MB / 43ms), which is what gives this standalone extraction credibility rather than just plausibility. Cost scales with the crater's boundary surface area (super-quadratic, trending cubic over this range), not linearly - 20 roughly doubles 14's cost, which still leaves real headroom under the OOM that was previously hit at a much wider radius; 28 and beyond grow fast enough that they were not chosen.

Still open, honestly: this is a timing/memory measurement, not a visual one - whether 20 actually LOOKS dramatically more destroyed on camera is unconfirmed, never having been rendered here. The separately-documented "rays pass through voxels" issue (see its own entry elsewhere in this file) was not re-investigated this batch. The textured-primitive G-key crash mitigation also remains unverified on real hardware, unchanged from before.

## E-key item pickup now plays a sound; both it and G's existing doomshotgun are now preloaded (DONE - asset files still missing, same gap as before, now two filenames deep)

Requested so taking an item with E plays a sound for video 1. Added `soundPlayer.play("pickup.wav")` to `Main.java`'s E-key handler on a successful pickup, matching the existing shape of G's `destroyAt(...)` -> `doomshotgun.wav` call right below it.

Bug caught and fixed along the way: the scene-load preload loop only ever collected sounds from `kf.sound` across every instance's keyframes, so neither "doomshotgun.wav" (G) nor the new "pickup.wav" (E) - both fixed, non-scene-authored SFX triggered straight from a key handler - was ever in that set. Both would have hit the exact first-press decode-on-the-audio-thread mistiming `SoundPlayer.preload`'s own doc warns about ("not quite locked to the first frame"), every session, on whichever of G/E got pressed first. Added both filenames to the preload set explicitly.

Still missing, pre-existing (not introduced here): no `src/main/resources/sounds` directory exists in this checkout, and there is no `.wav` file anywhere in the project - same gap already flagged for `doomshotgun.wav`, now also true for `pickup.wav`. `SoundPlayer` fails soft (logs a warning, no-ops) rather than crashing, so nothing breaks, but neither sound will actually be heard until real `.wav` files are supplied and placed at `src/main/resources/sounds/doomshotgun.wav` and `.../pickup.wav`.

## Sandbox reset: rebuilt from the delivered archives; regression tests now live IN the repo

The sandbox filesystem was reset between turns: /home/claude/work and every test under /tmp were gone, and only the
archives delivered on Sep 25 survived - so anything not yet packaged (the previous turn's tutorial sections, the
CRATER_RADIUS_VOXELS constant, the bend write-up) had to be redone. The regression checks used to live in a scratch
directory outside the repo, which is exactly why they were lost. They now live in tools/regression/ (RegressionPrimitives,
RegressionTaper, RegressionPerVertexColor, RegressionCraterOOM) with a run.sh that compiles the project and runs them
under a 512MB heap. It needs JOML compiled from source - GitHub's JOML sources are NOT plain-javac-able: the
//#ifdef __GWT__ blocks must be stripped and the org/joml/jre and org/joml/experimental packages left out - plus the
LWJGL 3.3.3 jars.

## Taper voxelization clipped (then over-stretched) any shape that gets WIDER than its original box - FIXED, third attempt

First attempt shipped only tested a narrowing taper and the identity case - a widening one (bottomScale=1 ->
topScale=2) came back at 77% of the exact frustum volume, because the voxel grid is sized from the instance's own
raw scale before any modifier, so whatever grows past the original box was never in the grid at all.

Second attempt widened gridX/gridZ (and gridOriginLocal's own placement) by the taper's own max scale factor and
made it WORSE - 308% of the exact volume, not 77%. The reason took working through carefully: rasterizeAdaptive's
canonical-space formula, (gox-offX)/gx*extent+origin, ALWAYS re-normalizes the full [offX,offX+gridX] octree range
to exactly [origin, origin+extent] - regardless of what gridX actually is. So widening gridX alone does not widen
what "canonical space" means to the classifier; it only adds resolution inside the SAME window. wrapWithTaper kept
classifying the same FRACTION of the grid as before, and the wider gridOriginLocal then placed that same fraction
across a proportionally wider world-space region - stretching the whole shape by the taper's max scale, not just
its actually-wide end.

Third attempt: rasterizeAdaptive's own 18-argument overload (already used by voxelizeMesh for an arbitrary
bounding box) takes localOrigin/localExtent directly - scaling X/Z's by taperMaxScale (Y untouched, taper never
touches it) makes the widened grid's own full range map to TRUE canonical [-0.5*taperMaxScale,
+0.5*taperMaxScale] instead of a self-normalized [-0.5,0.5]. wrapWithTaper's own math (divide by the real scale
factor to undo the taper) was never the problem and did not change - it just needed input already expressed in
the right units. Verified: RegressionTaperWide now reports 1.00017 (was 3.08, was 0.77) and is wired into run.sh;
every already-shipped case (all primitive volumes, the narrowing-taper frustum, identity taper, the panel/OOM/
color suites) reports IDENTICAL numbers to before touching any of this, confirming the fix is additive, not a
side-effect-laden rewrite. taperMaxScale never goes below 1.0 by construction, so a narrowing-only taper's own
grid, offsets and canonical mapping are byte-for-byte what they always were.

A second, smaller instance of the exact same mismatch was still sitting in PbdRenderer.buildVoxelColorFn, which
independently recomputes gvx/gvz from inst.scale for its own UV normalization rather than reading the grid
PrimitiveVoxelizer actually built - fixed the same way (duplicating the small taperMaxScale scan, since Result
does not currently expose its own gridX/gridY/gridZ to read instead). This one is NOT independently verified by
an automated test the way everything else above is - buildVoxelColorFn needs a constructed PbdRenderer, which
needs a real GL context this sandbox does not have - so it is a reasoned, not measured, fix; worth a real check
when a build with a GPU is available.

bend has the same grid-extent problem and is NOT fixed by any of this - its own widest reach depends on the whole
arc, not one linear factor, so it needs its own extent computation, most naturally from the polar-geometry
structure described in the bend entry above (rho's own maximum over the swept angle range) rather than a reused
taperMaxScale.

## Taper modifier now accounted for in voxelization (DONE - part of the "modifiers not applied" gap)

Of the two modifiers flagged as a confirmed, real gap two turns ago
(voxelization tests the UN-modified canonical shape, so a bent or
tapered instance destroyed into voxels didn't match its own visible,
modified shape), taper is now implemented - bend is NOT (see below for
exactly why, not just "still pending").

Taper (pbd.tese's own applyTaper: pos.x *= scale, pos.z *= scale,
scale linear in Y from bottomScale to topScale, Y itself untouched) is
CLOSED-FORM invertible precisely because Y never changes - scale is a
direct function of a region's own Y range alone, with no coupling to
X/Z the way bend's own angle-times-offset formula has. PrimitiveVoxelizer.
wrapWithTaper inverse-maps a region's [minX,maxX]x[minZ,maxZ] through
the CONSERVATIVE (widest) scale bound across that region's own
[minY,maxY] before testing it against the shape's own already-existing
canonical-space classifier. The widened box is a SUPERSET of the region's true preimage, so
BOTH verdicts carry over safely (outside the shape -> outside; inside -> inside). An earlier version downgraded every
"inside" to "ambiguous" on the mistaken belief that only "outside" survives the widening; that never changed a result
(the size-1 point test agrees with a box verdict by construction) and only forced needless recursion - removing it
kept the voxel set identical (same 23,272 regions, same volume ratio) and cut voxelization of the test frustum from
300 ms to 92 ms.

Verified rigorously, not just "produces voxels that look tapered": a
cylinder tapered bottomScale=1.0/topScale=0.3 into a real frustum
(lampshade shape) matches the EXACT mathematical frustum-volume formula
(pi*h/3 * (r1^2+r1*r2+r2^2)) to within 0.005% (ratio 1.00005). An
identity taper (both scales = 1.0, a no-op) still matches the plain
cylinder volume formula to within 0.08%, confirming the wrapping
introduces no error when there's nothing to correct for. Directly
confirmed the shape actually narrows near the tapered end too (not
just a volume coincidence): the top's own real width measured ~34
voxels against a full ~100-voxel diameter, matching the requested 0.3x
scale. Full primitive-type and thin-panel-performance regression
suites re-run after restructuring voxelize()'s own switch statement to
share one classifier-wrapping point across cylinder/cone/torus/sphere -
identical numbers to every prior run for every UN-modified instance,
confirming the restructuring itself introduced no regression.

## Bend modifier: the inverse is exact and well-conditioned - two earlier conclusions in this file were wrong

Two turns ago this file claimed bend has no closed-form inverse. Wrong. One turn ago it claimed the closed form exists
but is numerically unreliable for high-aspect / small-angle bends, and that no cheap check can catch its failures. Also
wrong. What actually happened: tan(phi) = aspect*newLength / (aspect/totalAngle - newOffset) fixes phi only up to +n*pi,
and every candidate is an EXACT solution of both forward equations - I was choosing among them by smallest residual,
which cannot tell exact solutions apart. The "errors of hundreds of units" were exact solutions lying far outside the
canonical box. The right selector is the canonical range itself (a preimage must have |l|,|k| <= 0.5), which is also
precisely the question voxelization asks.

Re-verified on 300,000 random trials (angle +-0.05..8 rad, aspect 0.2..80, output points rounded to float32): an in-box
preimage is found for 100% of them; in the non-folding, non-wrapping regime 219,536 of 219,536 recover the true (l,k),
worst error 2.8e-6; and the case that "proved" unreliability (angle -0.0516, aspect 78.9) is recovered exactly. The
forward formula does cancel badly in float32 there (R1 ~ -1528 against a result ~ -0.8, costing roughly 1e-4), but that
is the GPU's precision, irrelevant to an inverse that runs in double.

Structure worth building on: in the aspect-scaled plane (u = aspect*length, w = offset) bend is EXACTLY a polar map -
(aspect*newLength, R1-newOffset) = (R1-k) * (sin phi, cos phi), phi = totalAngle*(l+0.5). The image of the canonical box
is therefore an annular sector, and point AND box classification both reduce to polar geometry: rho range from distance
extremes, phi range from corner angles, enumerate the branches phi = theta + n*pi inside [0, totalAngle], build one
canonical AABB per branch (a superset of that branch's true preimage, so both "inside" and "outside" verdicts stay safe),
call a region inside if any single branch's box is inside, outside only if every branch's is. A non-folding bend also
preserves volume for shapes symmetric in the offset axis (the polar Jacobian is 1 - k*totalAngle/aspect, which averages
to 1) - a ready-made exact test.

Not done yet: (1) the grid must cover the BENT shape's own box (same root cause as the taper-clipping entry above);
(2) the wrapper itself plus angle/axis parameter parsing; (3) a rejection test against an analytic ground truth - a first
attempt reported 14.5% of out-of-box points "accepted", most likely an artifact of the test (outside the box the map folds
onto in-box images, so accepting can be right), but that is unconfirmed; (4) targeting: findDestructibleAlongRay tests the
UNMODIFIED canonical box, so a bent or tapered instance can be "hit" on empty space or missed on its visible geometry
(inferred from that ray test's own log wording, not yet reproduced).

## Voxel color sampled per-REGION instead of per-VERTEX (FIXED)

Reported directly, from a real screenshot: low-resolution (large,
merged) blocks showed blocky/streaky patterns instead of real texture
detail. Confirmed in VoxelMeshBuilder.buildMeshWithColor: colorFn was
called exactly ONCE per region (at that region's own corner), and the
SAME single flat color got broadcast to every vertex of that region's
entire box - invisible for a small, crater-adjacent region (size 1-2),
but MOST of a real object's own surface stays merged into large
regions by design (an untouched interior region has no reason to
expand - see PrimitiveVoxelizer's own doc), so most of the visible
surface was one-flat-color-per-block rather than showing the real
texture's own variation.

Fixed: colorFn is now a BiFunction(voxel, worldVertexPosition) called
once per VERTEX (addFace's own 4 corners per face), not once per
region - each corner of a large region's face now samples a
DIFFERENTLY positioned texel, and the GPU's own rasterizer interpolates
smoothly between them across the face. Verified directly: a synthetic
linearly-varying color function, sampled across a single size-20
region, now shows real 0.0-to-1.0 variation across that one region's
own vertices (a single face's own 4 corners: [0.0, 0.0, 1.0, 1.0], not
four identical values) - confirmed NOT possible under the old per-
region sampling. Performance re-verified at realistic scale (40,500
entries, ~972,000 resulting vertices, each requiring a real texture-
pixel lookup): 1.8 seconds - well within a single G-key press's own
budget, not a per-frame cost.

This is per-VERTEX interpolation, not true per-fragment GPU texture
sampling - a further, real improvement over this (binding the actual
texture to voxel debris and sampling it continuously in the fragment
shader, rather than baking an interpolated color into vertex
attributes) would give genuinely crisp per-pixel detail even on a
single large face, matching what the un-destroyed primitive's own
tessellated surface already gets. Not attempted this turn - a bigger
change (a new shader path specifically for voxel debris, since the
current useVertexColor mode exists exactly to avoid needing per-
fragment texture binding for this case) - but the per-vertex fix
already directly addresses the reported blocky/streaky symptom.

## G-key destruction logic moved behind PbdRenderer's own facade (DONE)

The architectural refactor requested (and previously, honestly,
deferred twice for time/risk reasons) is done: the full raycast/
retarget/crater/color/state/draw pipeline for voxel destruction no
longer lives inline in Main.java's own game loop. Main.java's G-key
handler is now 4 lines - call PbdRenderer.destroyAt(rayOrigin,
rayDir), print the returned message, play the SFX if hit() - instead
of the ~300 lines it used to be. The destroyed-debris draw loop and
scene-switch cleanup are likewise now renderer.drawDestroyed(...) and
renderer.clearDestroyed() calls instead of Main.java directly touching
destroyedRenderers/destroyedGridOrigins.

Moved as private methods/state on PbdRenderer: DestroyedPlacement,
applyCraterAdaptive, computeCraterRemovedSet, encodeGridCoord,
buildVoxelColorFn (now reading this class's own voxelTexturePixels
directly rather than needing it passed in), parseColorTriple (a small
duplicate rather than a cross-class dependency for one trivial
function). applyCraterAdaptive and computeCraterRemovedSet turned out
to touch no instance state at all, so both ended up static - better
design, and it kept them testable via reflection without needing a
real GL context. buildVoxelMeshRenderer is a new, deliberately
minimal, voxel-specific GPU-renderer construction, NOT a share of
Main.java's own general-purpose buildMeshRenderer (which also handles
regular mesh instances, container items, and LOD rebuilds - pulling
those three unrelated call sites into this refactor too would have
expanded it well past what was actually asked); voxel debris always
carries real per-vertex colors and renders through
ClassicMeshRenderer's own useVertexColor path, which the shader
prioritizes over baseColor/texture entirely, so the texture/uvScale
setup Main.java's own buildMeshRenderer does for regular meshes is
correctly skipped here rather than pointlessly duplicated.

Verified, not just compiled: every existing regression test for this
system (the OOM fix under a constrained 512MB heap, the ray-cast
crater's own non-sphericity check, the zero-removed-voxels bug, all
seven primitive types including the anisotropic-splitting fix, the
thin-panel performance fix, the per-object-extent UV mapping) re-run
against the new PbdRenderer-based location and produced IDENTICAL
numbers to their pre-refactor runs (same region counts, same
timings, same reach-per-direction values) - this was a relocation of
working, tested logic, not a rewrite that happened to compile.

## Voxel-destruction texture crash: real root cause found this time - a DOUBLE decode, not decode timing

The last two attempts both moved WHEN stbi_load ran (mid-frame, then
scene-load-time) without questioning whether calling it TWICE on the
same file was itself the problem - and the load-time version crashed
too, its own log landing right after MaterialTextureArray's own resize
line for a large (3000x2000) JPEG. That timing is the real clue: this
project's own materials get decoded once by MaterialTextureArray for
its GL upload, and voxel-color sampling's own cache was decoding the
EXACT SAME FILES a second time, immediately after - two native
allocate/decode/free cycles on the same large image back to back,
exactly the kind of repeated stress that exposes native heap
corruption (Windows NTSTATUS 0xC0000374).

Fixed by never decoding twice: MaterialTextureArray.build() now keeps
a copy of the RGBA bytes it already decodes for its own GL upload
(MaterialTextureArray.PixelData, pixelDataOf()) instead of discarding
them once uploaded. PbdRenderer's voxel-color cache reads directly
from that - zero additional stbi_load calls anywhere in this
project's own voxel-destruction path. This also resolves a real,
separately-raised concern: a cache built by scanning materials only
once would silently miss anything loaded later through this engine's
own intended hot-loading; reading from MaterialTextureArray's own
already-built data instead means this cache is derived from - not a
second, independent process racing against - whatever build() was
actually called with, including a future hot-load-triggered
re-upload().

Texture sampling was NOT removed - kept and fixed, per direct
instruction. If this build still crashes, the STBImage angle (timing,
double-decoding) is very likely fully ruled out at that point, which
would itself be useful: worth checking next whether it's specific to
one exact file (a corrupt/unusual JPEG, say - charcoal.jpg being large
enough to need resizing is at least a correlation worth re-testing
in isolation) rather than assuming the whole feature again.

## "G-key rays pass through voxels" - not independently reproduced or newly diagnosed this turn

Real bug already found and fixed two turns ago: the retarget path's
own local-space ray-box test, when it missed (common once debris has
already been partially carved), used to fall back to the CAMERA's own
position as the impact point - nowhere near the object - which this
project's roadmap already documents. Re-checked the retarget sphere-
test's own radius formula specifically (half the box diagonal,
0.5*sqrt(sx^2+sy^2+sz^2)) since an elongated object seemed like a
plausible new lead - it's mathematically exact for "smallest sphere
guaranteed to contain the whole box regardless of aspect ratio", so
not a bug on its own. Nothing else in this path changed since the
already-documented fix, and no new log/repro came with this specific
report to point at something concrete beyond that. If this persists
on the build with the texture-crash fix above, the most useful next
report would be the same kind of detail that made the texture crash
and the earlier NaN/hidden-instance bugs possible to actually fix:
console output from the exact G-key press that passed through
(position/direction lines this project's own logging already prints),
so a real, specific case can be checked rather than guessed at again.

## Voxel-destruction texture crash: fourth attempt, DIFFERENT approach this time (not just harder-guarded)

Every earlier attempt called STBImage.stbi_load() fresh, live, from
inside the G-key handler's own call stack - mid-frame, interleaved
with whatever else that same frame's own GL calls were doing - and
kept correlating with a real, repeatedly-reported native crash
(Windows NTSTATUS 0xC0000374) despite defensive hardening and one
unrelated-but-plausible fix (a shared shader program) that turned out
NOT to be the actual cause either, confirmed by the crash recurring
again after that fix shipped. This turn moved texture decoding
entirely OUT of the game loop: every texture a scene's own materials
reference is now decoded ONCE, at scene-load time, in the exact same
call context MaterialTextureArray's own (long-confirmed-working)
texture loading already uses - cached as plain Java byte[] pixel data
on PbdRenderer (voxelTexturePixelsFor). The destruction code path
itself no longer imports or calls STBImage/MemoryStack at all - it's a
pure Java array lookup from that point on. This is a genuinely
different fix, not the same pattern retried harder - if the crash
persists after this, the actual cause is very unlikely to be STBImage
call timing/context at all, which would be a real, valuable data point
for whatever's tried next.

## Modifiers (bend/taper/twist/curve/shear) are not applied during voxelization at all - confirmed architectural gap, not a quick fix

Confirmed by reading ModifierRegistry's own doc directly: modifiers are
deliberately never touched by the parser or any CPU-side code - they're
applied entirely inside the GPU's own tessellation shaders
(pbd.tesc/pbd.tese), which is where the actual deformation math (a
real, apparently non-trivial "constant-radius circular arc" bend
formula, taper, with their own prior bug-fixing history visible in
that shader's own comments) lives. PrimitiveVoxelizer has no path to
that GLSL logic at all, so a bent/tapered instance voxelizes as its
UN-deformed base shape - the voxel result won't visually match a
modified primitive. A real fix means porting that same deformation
math into Java, applied to each candidate voxel position during
classification (or the classifier's own local-space point, inverse-
transformed through the modifier) - substantial, correctness-
sensitive work given the shader's own comments describe a formula
that already went through real bugs before reaching its current form;
not attempted this turn rather than risk a rushed, likely-wrong port
of unfamiliar deformation math under time pressure. Next step:
read pbd.tese's own bend/taper implementation in full (lines ~200-380)
before writing anything in Java, to reuse the ALREADY-correct formula
rather than re-derive one independently.

## CRITICAL: non-cube primitives were catastrophically broken by an earlier "thin panel" fix (FIXED)

Confirmed by direct testing (a real gap this session should have
caught much earlier - every prior verification only ever used type
"cube"): cone/sphere/torus produced ZERO voxels (disappeared entirely),
cylinder produced its ENTIRE bounding cube as one solid block (its
curvature never resolved, looking "almost square"). Root cause: an
earlier fix for a real thin-panel problem (a door only 3-4 voxels thick
on one axis forcing needless fine subdivision on its much larger other
axes) used a "stop subdividing once size drops to this shape's
smallest axis" heuristic - which for anything WITHOUT one dramatically
thin axis (every round shape - cylinder, sphere, cone, torus, where
all three axes sit close together) stopped subdivision after
essentially one level, since the smallest axis is nearly as large as
the whole grid.

Fixed with TRUE independent per-axis (anisotropic) splitting instead
of a shared-size heuristic - VoxelOctree.insertFilledBox already
accepted independent min/max per axis, so this needed no change to the
octree itself, only to how rasterizeAdaptive recurses: each axis now
halves and keeps recursing independently, stopping only once ITS OWN
size reaches 1, not once some shared threshold is hit. Verified
rigorously, not just "produces some voxels": cylinder/cube volume
ratio 0.786 (expected pi/4 = 0.785), cone/cube 0.262 (expected pi/12 =
0.262), sphere/cube 0.524 (expected pi/6 = 0.524), torus 0.156
(expected ~0.155) - matching the known geometric formulas almost
exactly. Also confirmed the earlier thin-panel performance fix and the
crater OOM fix BOTH still hold with the corrected splitting (identical
numbers: 3916 regions/8ms for the thin panel, 40633 entries/50ms for
the OOM stress test) - the anisotropy needed to be correct, not just
present, and now is.

## Retarget crater could silently place the impact at the CAMERA's own position (FIXED)

A real, reported "the shot registers (SFX plays) but doesn't alter the
voxel, about half the time" bug: the follow-up-hit code path's own
finer local-space ray-box test, when it missed (which happens often
once debris has ALREADY been partially carved by an earlier hit - its
real, now-smaller visible extent no longer fills the canonical box
this test checks against, even though the broader sphere test that
triggers a retarget at all is generous enough to still find it),
fell back to using the camera's OWN local-space position as the
"impact point" - nowhere near the actual object, so the crater computed
from it removed nothing from what's really left, while the SFX (tied
to the retarget being FOUND, not to the crater actually landing) still
played. Now falls back to the ray's own closest approach point to the
object's center instead - not exact, but genuinely near the object,
not the player.

## Architectural refactor requested, honestly deferred (not attempted this turn)

Explicitly, strongly requested: the full G-key destruction/retarget
logic (raycasting, crater computation, texture-color sampling, mesh
building, renderer construction, state tracking) currently lives
inline in Main.java's own game loop instead of behind a proper facade
(PbdRenderer, matching setOpen/hideInstance/isContainerOpen and
everything else already moved there this session). This is a real,
valid architectural complaint, not dismissed - but moving it correctly
means relocating several hundred lines across multiple interdependent
helper methods (applyCraterAdaptive, computeCraterRemovedSet,
buildVoxelColorFn, the retarget ray-sphere test, the destroyed-debris
draw loop) in one pass, immediately after fixing the single most
severe bug this session found (every non-cube primitive silently
broken). Attempting a rushed, large-scale reorganization in the same
turn - on top of an already reported pattern of regressions stacking
on regressions - risked exactly the kind of new bug a careful,
dedicated pass would avoid. Next priority for a turn with room to do
it properly and verify each piece as it moves, not silently dropped.

## Tutorial: reuse-what-exists and quality-submission sections (DONE)

server/tutorial.php now has "Reusing what's already shared" (File > Import > PBD Scene / PBD Materials with Include vs
Merge, from a file or a URL; the Reference (pbd_ref) button and Test Reference; Clear Remote Cache, because remote fetches
are cached permanently) and "Making it a quality submission" (keyframes, keyframe sounds, container-door links, lights,
materials, references). Every label and behavior cited was read out of the addon source rather than remembered: a first
draft written from memory was wrong in several places (there is no Description field in the File metadata box, Reference
and Light are not in the Shift+A menu, Load .pbdmat is local-only) - and the "import operators don't exist" conclusion I
drew from that same draft was itself wrong, because I had only listed the addon's top-level files and missed its
operators/ package.

Open: the file FORMAT supports description=, but the Blender panel exposes no field for it (the File metadata box shows
Name, Kind, Authors and the automatic Origin only), so an addon user cannot author one.

## Real per-voxel texture-pixel sampling RE-ENABLED and refined

Was disabled two turns ago (native crash, couldn't verify the fix) -
re-enabled now that the actual crash cause (ClassicMeshRenderer
recompiling a shader on every construction - see that turn's own
history) is believed fixed. ALSO refined the UV mapping itself: the
earlier attempt normalized a voxel's position against the shared
octree's own gridSize directly, which for a non-cubic object (most of
this project's own door/panel assets) compressed almost the whole
texture into a tiny sliver of the 0..1 range. Now normalizes against
the OBJECT's own real per-axis extent instead - verified directly: a
thin door panel (gvx=4, gvy=60, gvz=100) now spans U/V ranges of
[0.0, 0.99] on both axes, not a compressed fraction. Extracted into a
shared buildVoxelColorFn used by BOTH the initial-destruction and
follow-up-hit (retarget) paths, so a voxel hit a second time keeps its
real texture-sampled color instead of reverting to flat.

## availableVolume/canHide facade methods added

availableVolume(containerName): the container's own bounding-box
volume minus the summed volume of every item currently placed in it
(getContainerItems(), extended to also expose each item's own width/
height/depth for this purpose). canHide(containerName,
playerHitboxVolume): availableVolume >= the given hitbox size -
playerHitboxVolume is a parameter, not hardcoded, since this facade
has no business assuming the actual player capsule/hitbox dimensions
gameplay code owns. Verified against real reflection-constructed
objects: an empty 2x2x2 container reports volume 8; placing a 1x1x1
item drops it to 7; canHide correctly returns true/false against
different hitbox sizes at that 7.0 available volume.

## Crater OutOfMemoryError on a wider blast radius (FIXED, directly caused by last turn's own radius increase)

Confirmed by a real stack trace: raising craterRadiusVoxels from 6 to
14 (requested as "bigger alterations") widened outerTestRadius (used
to decide which merged regions get expanded for per-voxel crater
testing) from ~8.4 to ~19.6 - at that reach, a single large merged
region (up to 128+ voxels on a side for a sizeable prop) could get
fully expanded into size^3 individual int[] entries just for coming
anywhere near the blast, which for one big region alone could already
be millions of entries, several such regions compounding further into
a genuine OutOfMemoryError. Fixed by replacing the flat "expand the
whole region the instant it's in reach" approach with
applyCraterAdaptive - the same octree-recursion principle
PrimitiveVoxelizer.rasterizeAdaptive itself already uses for the
original voxelization: a region entirely outside the outer radius
survives whole and unexamined; only genuinely ambiguous regions -
actually near the crater's own boundary - recurse into progressively
finer octants, so only geometry ACTUALLY close to the impact is ever
refined down to individual voxels. Verified against a real, memory-
constrained run (-Xmx512m) on a realistically-sized object at the new
14-voxel radius: 43ms, ~2MB memory delta, 40,633 surviving entries -
not the millions that caused the crash.

## destroyedRenderers/destroyedGridOrigins were never cleared on scene switch (FIXED, likely related to a separate reported N-key crash)

A real, separate reported crash (ArrayIndexOutOfBoundsException on
pressing N) couldn't be pinned to an exact line with certainty (the
reported line number no longer matched current code after several
turns of edits, and this session's sandbox has no way to load two
different real scenes and actually press N to reproduce it directly).
This IS a confirmed, real bug found along the way, and a strong
candidate for the same crash's actual cause: destroying something in
one scene, then switching (N/B) to a DIFFERENT scene - very possibly
with fewer instances, or none at all - left stale index-based entries
in destroyedRenderers/destroyedGridOrigins pointing at positions that
may not exist in the new scene's own, differently-sized arrays.
containerContents was already correctly cleared on scene switch; these
two were not. Fixed by clearing both (each renderer properly closed
first, not just dropped, to avoid yet another GL-resource leak) at the
same point containerContents already resets. If the N-key crash
persists after this, the exact scene sequence (which scene, how many
instances, what was destroyed beforehand) would help pin down whatever
remains.

## Not addressed this turn (too large to rush, listed honestly rather than done halfway)

- Blender addon: no button to export one or more `.pbdmat` files
  independently (only as part of a full `.pbd` export today).
- Blender addon: including a remote `.pbdmat` shows nothing in the
  Material panel - not yet investigated.
- The `doomshotgun.mp3` SFX request: the trigger call
  (`soundPlayer.play("doomshotgun.wav")`) is wired into every
  successful destruction/follow-up hit this turn, but SoundPlayer only
  supports `.wav` (`javax.sound.sampled` has no built-in MP3 decoder -
  see that class's own doc). Adding real MP3 decoding would mean a new
  third-party dependency this sandbox has no way to test audio
  playback against at all - safer to flag this plainly than integrate
  something unverifiable under time pressure. The file needs
  converting to `.wav` and placed at
  `src/main/resources/sounds/doomshotgun.wav` for the now-wired trigger
  to actually play it.
- The METIER/domain-specific requirements the tutorial doesn't cover -
  noted as raised, not yet actioned; needs the user's own specifics on
  what's missing before this can be worked on concretely.

## Raycast returning NONE despite real hits, and clicking voxel debris no longer toggling its door (FIXED - same root cause)

Confirmed directly from a pasted console log: several instances logged
"HIT at world-dist=NaN". Math.max/min in Java propagate NaN through
any comparison (NaN < x and NaN > x are both false), so these hits
could never win against a valid closestDist, but were never explicitly
excluded either - traced to hideInstance() zeroing a destroyed
instance's own worldTransforms scale to hide it (by design), which
makes that matrix singular; inverting a singular matrix produces NaN/
Infinity throughout. findDestructibleAlongRay never skipped an
already-destroyed instance, so it kept testing this degenerate matrix
every time. Fixed by skipping any instance flagged in hiddenInstances
before the ray-box test even runs (voxel debris already has its own,
separate re-hit path - the ray-sphere retarget test - for exactly this
case). Added a second, defensive layer directly in rayBoxIntersection
itself (returns -1 instead of a NaN result, via Float.isNaN) in case
some other path ever feeds it a degenerate matrix this specific fix
didn't anticipate. The same zeroed-matrix problem affected
toggleKeyframedInstanceAlongRay (mouse-click door toggle) identically -
a zero-scale box has no volume for a click to ever land in, which is
the actual reported "clicking a voxel no longer triggers its
animation" bug - fixed by using the instance's LIVE transform
(resolveLiveTransforms, the same one its voxel debris is already drawn
at) instead of the zeroed one whenever the instance is hidden.

## Voxel debris was untargetable by BOTH systems that raycast against an instance's own bounding box (FIXED)

Found by taking a reported "the second shot crashes it" claim seriously
and re-reading the destruction/retarget code with fresh eyes: every
single `new ClassicMeshRenderer(...)` call - triggered by EVERY voxel
destruction, EVERY follow-up hit on existing debris, and every
container item / regular mesh instance too - was compiling and linking
a brand new GLSL program from source (`classic.vert`/`classic.frag`)
every time, rather than sharing one compiled program the way a shader
is meant to be used. Repeated shader compilation under time pressure,
back-to-back during actual gameplay, is a known source of driver-level
instability on some GPU/driver combinations - lines up with a crash
specifically correlating with a SECOND (or later) destruction in a
session, not the first, and with "pressing G too much" crashing things
independently reported earlier too. Fixed by caching one compiled
ShaderProgram per shaderDir path (a static map), shared across every
ClassicMeshRenderer that resolves to it - all three of this project's
own construction sites already pass the exact same path literal, so
this covers voxel destruction, container items, and regular mesh
instances all at once, not just the one path that happened to be
under test. `close()` no longer tears down the shared program (only
this instance's own mesh-specific buffers) since other live renderers
may still depend on it.

## Per-voxel texture sampling DISABLED (safety rollback, not fixed)

Was implemented (real STBImage-based pixel sampling with a triplanar
UV projection), defensively hardened across multiple turns (bounds-
checked texture dimensions, a broad catch(Throwable)), and STILL kept
correlating with a real, reported native crash on a textured
material's own destruction (Windows NTSTATUS 0xC0000374, heap
corruption) - including a second crash reported on the SECOND
destruction of a session, immediately after this same code had just
been touched again. Never reproducible in this sandbox (no working
LWJGL natives here at all), and not conclusively root-caused despite
direct line-by-line comparison against MaterialTextureArray's own
working stbi_load usage. Given an explicit instruction not to keep
stacking unverified fixes on top of an unresolved crash, this turn
REMOVED the STBImage-touching code path entirely from voxel
destruction rather than patching it further - every voxel now gets the
material's own flat color (or this renderer's own neutral-gray
default), the same behavior confirmed working before texture sampling
was ever added. Re-enabling real texture sampling needs either a way
to reproduce/debug the native crash directly (a Windows machine, or
working LWJGL natives in a sandbox), or a from-scratch reimplementation
someone can actually test end-to-end before it ships again.

## gridOriginLocal was fundamentally miscalculated for non-cubic objects (FIXED)

Confirmed root cause of TWO separate reported symptoms at once - "the
voxel is still not visually positioned correctly" (reported across
several turns) and "no crater appears anymore" (a specific reported
case: cube.019, 3 voxels thick on one axis, 0 out of 29097 voxels
removed): `gridOriginLocal`'s own formula divided offX/offY/offZ (a
quantity in gridSize's shared, cubic octree units) by gridX/gridY/gridZ
(this axis's own, often much smaller, real voxel count) before scaling
by this axis's real-world size - a unit mismatch. One octree unit
always equals voxelWorldSize on ANY axis (confirmed against
PrimitiveVoxelizer's own sx/sy/sz = instance.scale.x/y/z), so the
correct conversion is a flat multiply by voxelWorldSize, not a divide
by gridX first. For a roughly-cubic object this produced nearly the
same (wrong, but close enough to go unnoticed) answer either way -
only became severely visible for a strongly non-cubic shape, which
describes most of this project's own door/panel assets. Fixed at both
call sites (the canonical-primitive path and voxelizeMesh's own copy).
Confirmed via direct reproduction against the exact reported case
(cube.019): before the fix, the computed impact point's Z coordinate
(127.6 in the real, correct grid) came out as 138 - completely outside
that object's own real Z-range of [126,129] - after the fix, 57 real
voxels correctly overlap the crater instead of 0.

## Voxel octree needs true anisotropic subdivision for thin geometry

Confirmed root cause of a real reported case (a door panel producing
thousands of tiny separate pieces and 100K+ vertices for a small
crater): the octree is cubic (size governs all three axes together),
so a THIN primitive (small on one axis, like a door's own ~2cm
thickness) forces recursion to keep chasing that thin axis's
resolution all the way down, dragging the other two - much larger -
axes into needless fine subdivision along with it, even deep in what
should be mergeable "interior". Mitigated this turn by stopping
recursion once size reaches the SMALLEST of the shape's own three
real per-axis voxel counts (falling back to a center-point test, same
as the existing size==1 case always did) rather than always going to
size==1 - measurably better (confirmed via a direct test against the
exact reported dimensions: region count dropped from what would have
been ~26,596 individual pieces to 3,916), but NOT a full fix: the two
larger axes still can't merge PAST that point even though they'd have
room to. A real fix needs the octree itself to split axes
independently (stop subdividing an axis specifically once IT reaches
its own native resolution, keep splitting others that still need it) -
a bigger structural change to VoxelOctree not attempted this turn,
given the risk of reworking a core data structure without enough time
left to verify it thoroughly.

## Reported rotation/scale bug on some primitives (not yet diagnosed)

Reported via two screenshots (a grill/BBQ scene) showing an oddly thin,
mis-oriented flat panel/blade shape. No .pbd file was attached for
this specific scene, so the exact instance(s) involved couldn't be
identified or reproduced - worth a closer look with the actual source
file next time this comes up, rather than guessed at without one.

## Crash on G-key destruction of a textured primitive (mitigated, not confirmed fixed)

Reported as a native crash (Windows NTSTATUS 0xC0000374, heap
corruption) when pressing G on a primitive with a textured material -
almost certainly related to last session's new STBImage-based per-
voxel texture sampling, added the same session this was first
reported. Compared directly against MaterialTextureArray's own
working stbi_load/stbi_image_free pattern - the two matched, no
structural difference found by inspection. Added defensive bounds-
checking (validating texW/texH and the buffer's own remaining() before
trusting them) and a broad catch(Throwable) around the whole texture-
loading block, falling back to a flat color on anything unexpected -
a responsible mitigation, not a confirmed fix, since this couldn't be
reproduced or root-caused without a working native LWJGL runtime
(unavailable in this sandbox - see this repo's own session history).
If it recurs, the specific texture file that triggered it would help
narrow this down further.

Not yet implemented - captured here rather than attempted under time
pressure alongside whatever else was in progress. Ordered roughly by
how tightly each depends on something else on this list.

## LOD metadata not working in Java (likely quick fix, investigate first)

Reported: LOD tiers set as `1` and `0` in Blender don't work in the
Java engine, which is suspected to expect powers of 2 up to 64+
instead. Checked this turn: the Java side's own `meshDataByLod` is
keyed directly by the `<N>` in a `vertexDataLod<N>` field name (see
`PbdParser.applyInstanceField`'s own `VERTEX_DATA_LOD_KEY` handling)
and used purely as an ORDERING key (a `TreeMap`, ascending) by
`desiredMeshLodTier` in `Main.java` - nothing in that path actually
requires powers of 2 or a specific range up to 64. This means the
reported mismatch is more likely in the BLENDER ADDON's own LOD UI/
export side (what value it asks for, what it writes as the field's own
`<N>`) than in the Java engine's own tier-selection logic - next step
is reading the addon's own LOD properties/export code (likely in
`properties.py`/`export_pbd.py`) to see what it actually expects
there, not assuming the engine side needs to change.

## Minified .pbd not loading (bug, not yet investigated)

Reported as broken with no further detail yet - needs a concrete
repro (does the ENGINE reject a minified file outright, parse it
wrong, or something else?) before it can be diagnosed. Given this
project's own history this turn (the metadata-value-swallowing
regression in minified mode, already fixed), worth checking first
whether this is the SAME class of issue resurfacing somewhere it
wasn't fully covered, or a genuinely new one.

## canHide facade accessor

A getter (`canHide(containerInstanceName)`, naming TBD) on the runtime
facade returning whether there's enough room to hide in a given
container - built on `availableVolume` below plus the player's own
rough hitbox volume.

## availableVolume facade accessor

`availableVolume(containerInstanceName)` = the container's own bounding
box volume (already buildable from the existing `volume()` facade
getter added this session) MINUS the summed volume of every item
CURRENTLY placed in it (via `getContainerItems()`, also already built).
Depends on nothing further not already in place - the most
immediately buildable item on this list.

## Door-closed facade accessor for AI/modding

A facade method so a modder's own zombie-AI code can check whether a
container's associated door(s) are closed, without needing to know
this engine's own internal `isInstanceOpen`/`isContainerOpen`
mechanics - wraps that existing mechanism, doesn't replace it.

## Container refill-on-reopen logic needs rework

Current behavior: an emptied container re-rolls new random items if
its door is opened again. Reported as the wrong behavior - once
empty, should STAY empty. Fix should use `getContainerItems()`/
`removeContainerItem()` (built this session) to check REAL current
state rather than re-rolling, and the request notes this is also a
chance to better DISTRIBUTE items across a group of linked containers
(not just decide empty-vs-not).

## H-key exit: record an inverse keyframe from the hide start point

When exiting a hiding spot, animate out using the REVERSE of however
the player got in, rather than however `updateHideTransition`
currently interpolates - needs comparing against whatever
`startHiding`/`startExitingHide` currently do to see whether this is a
small adjustment or a rework.

## Lever-arm system (major feature, format-wide) - MVP SHIPPED + corrected + a first drag prototype, see the three dated entries at the top of this file

The core of this was built: two authored endpoints (closed = the instance's own base pose, open = a new `leverArm` block's pose) with a continuous 0..1 value driving the motion, wired through `.pbd`/`.pbdbin`/`.pbdasset`, the Java engine, and the Blender addon. A first pass wrongly assumed that value would always be scripted (eased on a timer); corrected to also support an external driver (`PbdRenderer.setArmValue`/`getArmValue` - this IS the `getArmRotationValue()` accessor this backlog item originally asked for, built and no longer pending) once it was clear the actual goal was a mouse drag-and-drop, not a baked animation. A simple (no physics engine) geometric drag prototype was then built against that same seam. See the three top entries for what shipped, what was wrong about the first version, and how each was verified.

Still genuinely open from the original ask below, not yet built:
- Other primitives linked/attached to a lever-arm (parent-like attachment, not just synchronized open state the way `linkGroup` already does for doors).
- SFX on the arm's own motion (today only a keyframe can carry `sound=`).
- A REAL physics-driven drag, as opposed to the simple geometric one that now exists (see the two top entries) - no physics engine exists in this codebase, and integrating one (which library, real constraints vs. the current direct geometric mapping) is still an open, undecided question.
- Whether `server/upload.php` (not present in this sandbox) needs any change for moderation to handle the new block type - unknown from here.

## Bend modifier - proper fix

Current bend implementation flagged as still wrong. Requested
approach: match Blender's own classic technique - a loop cut
(subdivision) along the affected axis (e.g. a cylinder), THEN a Simple
Deform (Bend) modifier with angle/axis - rather than whatever
approximation is currently in place. Needs comparing against the
CURRENT bend modifier's own implementation to see how far off it is
from this specific technique.

## Web-based visualizer + documentation refresh

Eventually: a web version of the visualizer/viewer, plus a rewrite of
the index and tutorial pages, and a full pass over all existing
documentation to bring it up to date with everything else on this
list once built.
