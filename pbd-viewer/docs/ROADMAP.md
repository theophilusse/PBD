# Roadmap

How this file is organised. Sections 1-6 are the **current state**, rewritten on 2026-10-07 and brought up to date on
2026-10-08, 2026-10-12, 2026-10-20, 2026-10-25, 2026-11-09 and 2026-11-14 - read these first. Section 7 is the **journal**: every entry the project ever wrote, newest first (the oldest are undated), kept as it was
(self-corrections included - a wrong conclusion that was later fixed is worth keeping) with a short status
note added wherever a later entry changed the picture. Other docs: `STATUS_SUMMARY.md` (one table per area),
`TEST_PROCEDURE.md` (how to check everything), `PBD_FORMAT_SPEC.md`, `ENGINE_DEV_GUIDE.md`,
`ADDON_USER_GUIDE.md`.

Three words are used on purpose, and never loosely:

- **verified** - a test in `tools/regression/` ran the real code and passed. Where it matters the test was
  also shown to *fail* when the code under it is deliberately broken (mutation testing, see
  `TEST_PROCEDURE.md`), so "passes" is not the same as "cannot fail".
- **reviewed** - the code was read line by line but nothing could run it here (everything that needs an
  OpenGL window, Blender itself or the PHP server).
- **unknown** - not available here at all.

## 1. State of the project (2026-11-14)

**What exists.** A scene description format (`.pbd` text, `.pbdbin` = gzip of the minified text,
`.pbdasset` = zip with its materials/textures), a Java engine (LWJGL/OpenGL viewer: GPU tessellation of
primitives, materials, keyframe animation, lights - some of which switch with a door -, containers and the facade for
AI/modding code, voxel destruction on **G** (a crater that throws chips, a connection too thin for its material that breaks,
pieces that fall), item pickup and light switching on **E** (each with its sound, an MP3 played through a decoder of our own), hiding places the
player takes cover in with **H** and an AI can ask about, lever arms you can grab with the mouse, and scene description files - `.pbdscene` - that place many models with a few lines and play a camera path, keys and a stress report over them), a Blender add-on that
reads and writes the format, the Project Zomboid converters, and a moderation/upload server
(`pbd.aptheo.fr`, **not part of this repository** - see section 6).

**What the last four iterations did** (details in the journal). Items 1-11 are 2026-10-07 and 2026-10-08, items 12-17 are
2026-10-12, items 18-25 are 2026-10-18 to 2026-10-20, items 26-28 are 2026-10-24 and 2026-10-25, item 29 is 2026-10-26, items 30-32 are 2026-11-09, items 33-34 are 2026-11-10, item 35 is 2026-11-14:

1. **Lever arm, finished against its original specification** - see section 2, line by line. Free or locked,
   open/close sounds, anything attachable, the value readable by AI code, a smooth mouse drag, in every
   format and in the Blender add-on.
2. **The two checks you asked for** - section 3: every primitive type can hang on an arm (and be the arm),
   and one G hit now cuts a post or a plank in two and the loose piece falls and settles.
3. **A format-coherence test that does not trust anyone to remember anything** - one scene with every field
   the format has goes through pretty `.pbd`, minified `.pbd`, `.pbdbin` and `.pbdasset`, and what comes back
   is compared field by field by a reflection walk. It found six real data-loss bugs (journal, 2026-10-07).
4. **The Blender add-on checked against the engine's own parser** - without Blender (fake `bpy`, real
   `mathutils`, the engine's real parser and hierarchy resolver as the judge). It found a real export bug
   (children of a primitive were exported at twice the size/offset).
5. **A migration tool** for the keyframe doors you wanted replaced before video 2:
   `PbdConvertMain lever <file|folder> <output>`.
6. **Documentation checked by the code it describes.** `PBD_FORMAT_SPEC.md` was rewritten against the code (its
   lever-arm section knew none of `release` / `locked` / `lockAt` / the sounds, and its curves example used a
   syntax the parser does not read); every marked example in it is now parsed by the real parser on each run
   (`RegressionSpecExamples`), so a doc cannot drift quietly. The mutation tooling that backs the "tests can fail"
   claims lives in the repository instead of a scratch folder.
7. **A shot at a `mesh` instance** (section 3.3). It had never been tried and was broken four ways - a closed cube
   mesh lost 21 % of its volume, a scaled mesh stood in the wrong place, a flat mesh became a block over a metre
   thick, and the broad phase tested the wrong box. Fixed, with `RegressionMeshVoxel` and a mutant spec of its own (a flat or open mesh is no longer refused: item 18).
8. **The Blender add-on can no longer write a file the engine refuses because of a name.** An object called
   `front door` was exported as `cube front door {` (found by a probe while adding the Description field); ids are
   now cleaned up and reported, the importer reads every id the engine reads, a `#` or a tab in free text is quoted
   as the Java writer does, and the File metadata box has a **Description** field (backlog item 14).
9. **The numbers in the docs are checked by code** (`tools/regression/check_doc_counts.py`, the last step of
   `run.sh`; it checks itself first and has 35 mutants of its own): on its first pass over the finished state it
   reported 24 numbers that the day's changes had made stale.
10. **An emptied container stays empty** (backlog item 7): what the player takes with **E** is still gone when the
   door is closed and opened again. The rules moved out of `Main` into the pure `pbd.pz.ContainerStock`, with
   `RegressionContainerStock` and a mutant spec of its own.
11. **The Blender add-on run on real Blender** (Blender as a Python module: 5.2.2 first, then 4.2, 4.5, 5.0 and 5.1), and its interface code drawn on a
   recording layout on the fake. Findings, all fixed and pinned: a script that edits objects and exports at once got the
   positions from before its edits (Blender refreshes its matrices between two clicks, not two lines), so an imported then
   exported scene came out with a door panel three units off; an import with one unreachable `pbd_ref` stopped with a
   traceback instead of leaving a placeholder and a warning (a failed fetch was a plain `Exception`, the importer catches
   `OSError`); the sidebar said `v0.41.0` whatever the version (now read from `bl_info`); the materials-folder
   fallback was one folder short; the user guide described a "Project Root" field that no longer exists; and the
   `pbd_tools.zip` people install was a copy of the sources from before all of this (0.43.1, ten of its twelve files different, no lever arm in it at all) - the add-on
   check now compares it with the sources, and a real Blender installs, enables and disables it. Pressing every
   button once found nothing else.

Items 12-17 answer the request of 2026-10-10: voxel destruction that leaves something behind instead of deleting it, an
effect defined by the material, a break when an attachment is too small, a verdict on hiding places, the toggleable light
and the lever arms in the add-on, and the sound of the pickup.

12. **The crater throws chips instead of deleting.** What a hit takes out of an object - the crater's voxels, and the
   crumbs too small to fall as a piece (under 400 voxels) - used to vanish. It is now thrown up as small cubes
   (`DebrisEmitter`, `ParticleSystem`, `ParticleDraw`; all pure) that start inside the hole, fly out along the line of
   fire, fall, bounce on the floor, the stumps and the boxes of the scene, slide, come to rest and shrink away over the
   last quarter of their life. At most 1500 are alive (a new one replaces the one nearest the end of its life); a bigger crater throws more (15 % to 150 %
   of the material's count); a chip has the colour of the surface it came off - a textured wall sheds chips of its
   texture - unless the material gives a tint; the same hit throws the same chips. `RegressionParticles` (148) checks
   them against the continuous equations, `mutants_particles.py` has 273 mutants. Drawing them (one draw of a shared cube
   per chip) is **reviewed**, not run.
13. **How a material breaks is the material's business, not the primitive's.** `BreakProfile` reads ten keys of a
   `.pbdmat` block - `breakWidth`, `debrisCount`, `breakCount`, `debrisSize`, `debrisSpeed`, `debrisLifetime`,
   `debrisGravity`, `debrisBounce`, `debrisColor`, `breakSound` - and starts every material from the profile of its
   name (`wood`, `metal`, `plastic`, anything else), so every primitive made of one material breaks alike and one line
   retunes them all; keys written in a primitive's metadata are not read (a test pins that). A value that cannot be read,
   or is outside its range, is refused with a warning and the default of that key stays - it is never clamped; a mistyped
   `break*` / `debris*` key is reported. The table of defaults and ranges in `PBD_FORMAT_SPEC.md` is compared cell by
   cell with the code on every run. The four formats carry it (a material that sets all ten is in the round trip's
   kitchen-sink scene), and the add-on reads and writes it (Materials panel, *Custom break*).
   `RegressionBreakProfile` (76), 115 mutants; the add-on's side, 276 mutants.
14. **A piece held too thinly breaks away whole - the "cassure"** (`VoxelFracture`). After the crater, a connection
   narrower than the material's `breakWidth` (wood 3 cm, metal 1 cm, plastic 1.5 cm, anything else 2 cm; 0 turns the rule
   off for the material) cannot hold: the thin run shatters (its voxels are thrown up as chips of their own, `breakCount`),
   the piece it held is cut loose - what stays and what falls is then decided as for any cut - and the hit plays the
   material's `breakSound` once. "Thin" is a morphological opening: a cell is thick when a cube of `breakWidth` on a side fits in
   the solid and covers it. It is computed on flat arrays in a window around the crater (never on the octree expanded);
   a run that was already thin before the hit, or touches only one thick piece (the ragged rim of a crater), is left
   alone, and a window of over 8,000,000 cells is skipped - the hit then stays as the crater made it, and says so. It
   applies to a piece that already fell as well. `RegressionVoxelFracture` (59: hand-made situations with exact voxel
   counts, a brute-force reference on random solids and craters), 168 mutants; the whole flow around it,
   `RegressionDestructionWorld` (172) and `RegressionWorldDebris` (60), 227 mutants.
15. **Hiding places: a verdict AI code can ask for, and no coming in or out through a closed door** (`HidingRules`, `DoorParts`,
   `SlabOpenings`; all pure). Static getters on the facade, `Main`: `isHideoutConcealing(id)` is true when the place has
   doors and every one is *shut* - fully closed (a lever arm at 1 % or less, a keyframed door back at its start; swinging or ajar
   is not closed) **and unaltered** (item 23: since 2026-10-20 a hit that reached the door at all makes it open; the first version
   of this item measured the hole and let a dent or a small hole still hide);
   `getHideoutState(id)` returns what that is made of (door count, all closed, whether one was altered, the lowest integrity, the
   widest hole in metres, shut, concealing, and the reason in words); `isPlayerConcealed()` is the player's own state (inside, the move
   in complete, the place conceals). What is left of a shot door is measured by `SlabOpenings` from what the destruction world says
   - by the widest square of opening, not by volume - and reported, but it no longer decides. **H** is refused, with a message that
   says why, to go in or out of a *shut* place. `RegressionSlabOpenings` (39), `RegressionHidingRules` (189), 86 + 277 mutants. The
   H key and the getters in a running window are **reviewed**.
16. **The Blender add-on can add the toggleable light and the lever arms** (v0.45.0; you could not find them). Engine side,
   no new format key: a light that shares its `linkGroup` with something that opens - keyframes or a lever arm, and not
   a loop - is on while any of them is open (`LightLinks`: `RegressionLightLinks` (84), 57 mutants). Add-on side: in the
   box *Lever Arm* the buttons **Door**, **Drawer** and **Lever** put an arm on the selected object from where it
   stands now - the way *Insert Keyframe Here* works for keyframes - and the **X** takes it off; in the box *Light that
   follows this object* **Add Light Linked to This** adds a lamp that shares the link group of what opens; under *Create*,
   **Door (hinge)**, **Drawer** and **Light Switch + Lamp** make whole working pieces (the same three are in *Add > Mesh*).
   Checked against the engine, not only for "does not crash": the open pose the engine computes from the export is the
   one Blender has, the add-on's answer to "does this open?" is compared with the engine's object by object, `LightLinks` is
   asked which lamp follows which door in an exported scene, and on real Blender every button is pressed; 184 mutants.
17. **The pickup plays `resources/sounds/item-equip.mp3`.** `GameSounds.forPickup` decides which sound goes with **E**
   (an item came out: the sound; the press found nothing: silence), `SoundPlayer` plays an MP3 through `Mp3Decoder`. The
   JDK reads WAV but has no MP3 reader, and a third-party jar would change the build, so the decoder is a Java port of
   minimp3 (public domain), Layer III only. A decoder is code where "it played a file and it sounded right" proves
   almost nothing, so it was compared with the C original bit for bit on recorded, derived, hand-made and random
   streams, and within one 16-bit step with the ISO conformance streams: the record is `TEST_PROCEDURE.md`, section F.
   `RegressionMp3Decoder` (224), `RegressionGameSounds` (51), 134 + 89 mutants. The real `item-equip.mp3` has never been
   seen here (the sounds folder is not in the upload).

Items 18-26 answer the request of 2026-10-18 (sent again on the 18th, 19th and 20th): the empty shade of the desk lamp that became a
full cylinder when shot, a triangle that could not be shot at all, a key to switch the light of a model with a click you can choose, a
texture that came out mirrored once the object was voxels and a way to mirror it, a texture that did not look right in Blender, a hiding
place whose door has been shot, the new domain name, strict rules on the upload, and - not done yet - a viewer in the browser and a
description format for the scene of the video.

18. **What is shot is voxelized as it is drawn: every parameter of a primitive, and the triangles of a mesh.** The report: the empty
   cylinder that is the shade of `desklamp.pbd` (`cap=none`) came out of the conversion as a full cylinder - are all the modifying
   parameters of a primitive taken into account? They were not: the voxelizer had its own, older idea of each shape (a solid, whatever
   `cap`, `faces`, `bend` or `shear` said), and a mesh was voxelized only if it was closed. Now the voxels are those of the surface the
   GPU draws. `ShapeEval` is a port of the tessellation shader (the seven primitives, the rounding of a cube, taper, bend and shear in the
   file's order); `ModifierParams` is the one place that says what a modifier's parameters are called and what they default to (the
   renderer's buffer, the baked mesh cache and the port all call it); `PrimitiveParts` says which parts a primitive is drawn with
   (`cap=`, `faces=`); `ForwardVoxelizer` builds a hollow shape as a wall of 3 voxels (never more than 40 % of what it grows across) and
   a bent or sheared solid as a lattice carried through the modifiers; `VoxelCavities` closes the pinholes of a solid; a plane or a disc
   is a slab of whole layers; a negative scale is a mirror (`GridMirror`). A shape too big for the work is voxelized whole with larger
   voxels and the hit says so; nonsense (a zero or not-a-number scale, a voxel of zero, a bend of 1e30) gives nothing - never an
   exception or a hang. A mesh is voxelized from its triangles (`MeshVoxelizer`): a closed one by the ray-parity inside test, an open
   one (a single triangle, a flat quad, a sheet) as a wall. `twist` and `curve` do nothing on the GPU and so nothing here. Checks:
   `RegressionShaderPort` (85: the port against numbers worked out independently from the text of the shader), `RegressionVoxelShapes`
   (106), `RegressionVoxelLattice` (23), `RegressionVoxelLimits` (16), `RegressionMeshVoxel` (130), `RegressionMirror` (54); mutation specs
   `mutants_voxel_shapes.py` (331), `mutants_mesh_voxel.py` (84), `mutants_mirror.py` (77). What was chosen, and the cases where it will look
   wrong, are in section 4 and in `ENGINE_DEV_GUIDE.md` ("Voxelizing what is drawn").
19. **What cannot be turned into cubes is destroyed outright.** The report: `[G-key] closest hit: #3 'let_triangle'` answered "can't be
   voxelized" and the object stayed. A triangle can now be shot (item 18); what has no voxel form even so - a mesh with no readable
   triangle or whose three corners lie on a line, a shape thinner than a voxel, a cube with every face removed - is destroyed by the
   hit (`DestructionWorld.destroyWhole`): it stops being drawn, a burst of particles of its own material comes out of where the shot
   landed (as many as its size asks for), and its face counts as entirely open for the hiding places. Only `indestructible` is still
   refused. `RegressionDestructionWorld` (172), 227 mutants.
20. **E switches the light of the model under the crosshair, with a click you can choose** (`LightSwitch`, `UseKey`,
   `GameSounds.forLightSwitch`; pure - `Main` hands over what the crosshair is on and plays what it is told). E has two jobs and what
   is aimed at says which: it takes an item out of an open container, or it flips the lights of the model aimed at (a desk lamp, a
   wall plate). The *model* is the nearest group above the part whose children carry its id as a prefix - what a `pbd_ref` makes of the
   file it places; lights that share a `linkGroup` with the part are switched with it wherever they stand (a plate far from its lamp);
   the switch is a room switch (any light on: all go off; else all go on); a light that follows a door is never switched by hand; where
   an item and a model are both under the crosshair the nearer wins, the item on a tie. The click is the optional key `switchSound` of the
   light (a file of the sounds folder, named by its file name alone, like a lever arm's `openSound`), else `light-switch.wav`, which
   `tools/scripts/make_light_switch_wav.py` writes (it decodes; nobody has listened to it) and which goes in `src/main/resources/sounds/`. In
   the add-on the light's panel has a **Click Sound** field, exported by file name, read back and sent along with an upload.
   `RegressionUseKey` (158), `RegressionGameSounds` (51); mutants `mutants_use_key.py` (151), `mutants_game_sounds.py` (92),
   `mutants_addon_light_click.py` (21). The handler in `Main` and the sound of the click are **reviewed**.
21. **A shot object keeps its picture the right way round; a negative UV scale mirrors it.** The report: the texture mapping reversed
   when an object became voxels. The cause: the voxel colouring chose its own axes (the dominant axis of the face, `u = z` on both X
   faces) while the shader runs the picture along different axes on different patches (the +X face of a cube runs `u` along -Z, the -Z
   face along -X...), so the faces on which the two disagreed came out mirrored - the handle of a locker was on the left in the viewer and
   on the right once the locker was shot. `VoxelTextureMapper` now runs the shader backwards: a corner of a voxel face is moved into the
   instance's frame, the modifiers are undone (`ShapeEval.unmap`), the patch and its (u, v) are found (`ShapeEval.locate`), (u, v) is
   multiplied by the UV scale of the material and the picture read at the texel it falls in (repeating, row 0 at the bottom); a mesh uses
   the UVs of its own triangles (`MeshTextureMapper`). A **negative `uvScale`** (`uvScaleU`, `uvScaleV`) is a mirror of the picture on that
   axis, in the shader and in the voxel mapping alike (`MaterialUv`). `RegressionTextureMapper` (361: the same 1700 points of every
   primitive, with and without modifiers, that hold the port to the shader), `mutants_texture_mapper.py` (148). As before, a colour is read
   at each corner of a voxel face and interpolated across it, so a big merged face is a gradient of the picture rather than crisp texels
   (section 4).
22. **The add-on shows a texture the way the engine does, and can mirror it** (v0.46.0). The report: the textures did not look right in
   Blender and the UV scale did not match. Blender's own cube projection gave every face a layout of its own, while the engine's shader
   draws a primitive as a few patches (a cube is six, a cylinder three) that each show the whole picture once, times the UV scale,
   whatever their size. `uv_layout.py` now gives every preview mesh the engine's coordinates - worked out for every corner of every face
   and compared point by point with the engine's own evaluator of the shader on each run, whatever the size of the primitive - and
   **Refresh UV Mapping** gives them back to an object whose UVs were overwritten. **Mirror U** / **Mirror V** (Materials panel and the
   object's panel) flip the sign of the UV scale of that axis, which the engine reads as a mirror; the keys are written, read back and
   understood by each of the three readers of a `.pbdmat`. The picture is still drawn by Blender's shader: its filtering and lighting are
   Blender's. `mutants_addon_uv.py` (176) and, on real Blender, `mutants_addon_uv_real.py` (57).
23. **A door that has been altered is open: the place no longer hides.** The report: when the door of a container (the children of the
   lever arm linked to it) is altered - typically turned into voxels - the container must count as open, and a player hiding inside with
   **H** is visible from outside, on the facade. A door is the listed instance that can move **together with everything that hangs from it**
   (`DoorParts`); it is *shut* when it is fully closed **and unaltered** - no hit has turned it, or a part of it, into voxels, and none of
   it has been destroyed - and an altered door is open whatever its arm says and however small the damage (a dent, a hole, a handle).
   There is no threshold any more (item 15 had one). A place is *concealing* when it has a door and every door is shut:
   `isHideoutConcealing(id)`, `getHideoutState(id)` (which now says whether a door was altered) and `isPlayerConcealed()` (false for
   a player behind an altered door). **H** goes in or out of a place that is not shut. `RegressionHidingRules` (189),
   `RegressionSlabOpenings` (39); mutants 277 + 86. The H key and the getters in a running window are **reviewed**.
24. **The domain is `pbd.aptheo.fr`.** Named once in each program: `FetchPolicy.SITE_HOST` in the engine (the one host fetched from without
   asking, and the user agent), `PBD_SITE_HOST` in the add-on's `fetcher.py` (the user agent, the default host, the upload and version
   addresses, the labels of the button) and `PBD_SITE_HOST` in `server/pbd_metadata.php`; the tutorial, the server README, the docs and the
   tests quote the new name. The former host name is trusted nowhere - there is no alias, and a test pins that - and the user agents of
   the older add-on are no longer accepted by the site (`legacy_user_agents` in `pbd_config.php` lets a given string through for a while).
   The references a library already holds to the former host are rewritten by `server/migrate_domain.php --from <the former host>`
   (`--dry-run` first; it backs up what it changes). `tools/regression/check_site_host.py` keeps it finished: the three constants name
   the same host (and so does the add-on zip), and no other host of the kind `pbd.<name>.<tld>` is written anywhere in the project.
25. **What a file may ask of the program is bounded wherever a file is read** (the upload: metadata, injections, an include that
   loops...). The same numbers on purpose in three layers, compared with each other by tests; every rule has a mutant that removes it.
   *The site* (`server/`; `TEST_PROCEDURE.md`, section H): at most 40 files, 30 MB each and 120 MB together; only plain file names
   (letters, digits, `_`, `-`, one dot); an allow-list of kinds and a list that is refused first whatever the rest says (`php`, `phar`,
   `htaccess`, `svg`, `html`, `js`, `zip`...); the bytes are read, never the extension or the declared type - a picture or a sound is
   rewritten from the pieces the engine needs, so hidden data, profiles, trailing bytes and metadata do not survive (at most 8192 pixels
   a side, 64 million pixels a submission); a `.pbd` / `.pbdmat` is read with the engine's own lexer rules and may say only what the format
   knows, with a cap on every size (identifier, value, block, number of blocks); the free text of the metadata is limited and may not
   hold markup characters; a reference may only name a file of the same submission, a file already in the library by its
   `https://pbd.aptheo.fr/db/` address, or one of the engine's own sounds, and the scene is expanded once to check the chain (depth 4
   under a submission, no cycle, at most 50,000 instances, at most 256 files read); the library is *closed* (whatever a text file in it
   refers to is in it too); counters limit uploads and logins, the moderator has a session and a CSRF token, the include-only scripts and
   the private folders are never served, and `/search.php/x.png` is a 404, not another name for the page. *The add-on* (`safety.py`,
   `fetcher.py`, `upload_rules.py`): it refuses before sending what the site would refuse, fetches under the engine's rules (https only; no
   private, loopback or link-local host, however the address is written; no redirect; 64 MB), unpacks a bundle all or nothing and never
   to a path that leaves its folder, stops an import at 50,000 objects and at six levels of `pbd_ref`, and shows a name that holds
   bidirectional or invisible characters as escapes; the host and the port its request connects to are the ones its policy judged (`urllib`
   reads an address its own way, so for each address the policy lets through the check builds the connection and compares). *The engine* (`FetchPolicy`, `PbdFetcher`, `CappedRead`, `PbdLimits`): the same
   address rules, 256 fetches a load, one question per untrusted host, a download kept whole or not at all, eight levels of `pbd_ref`,
   200,000 instances, 64 MB of text or of decompressed data, a bundle of at most 512 entries (64 MB each, 256 MB together) whose names
   may neither leave the folder nor mean something else on another system. `RegressionFetchPolicy` (675), `RegressionFetcher` (229),
   `RegressionIncludeLimits` (213), `addon_safety_check.py` (417), the 6 PHP suites (2446 checks) and the site through PHP's built-in
   server, Apache and nginx (section 3 of `TEST_PROCEDURE.md`, H); mutants `mutants_fetch_policy.py` (532), `mutants_fetcher.py` (247),
   `mutants_addon_safety.py` (1907) and the five `mutants_server_*.py`. **Not covered**: a name that resolves to a public address when it
   is checked and to a private one when the connection is made (DNS rebinding), the configuration of the real host, and an attack nobody
   thought of.
26. **A scene description file: every model placed by a few lines, the same file for the video and for the stress tests** (`.pbdscene`).
   The report: "a simple scene description format so that I can include all my PBD models, shoot the presentation video in one take and
   run stress tests". A scene file is a handful of line-based directives - `place`, `row`, `grid`, `ring`, `scatter` (with a seed),
   `folder` (every model file of a folder, laid out in a row, a grid or a ring) and `ground` - that `SceneExpander` expands into an
   **ordinary `.pbd` of `pbd_ref` blocks**: the engine, the add-on and the site open the result with nothing new, and the limits of item 25
   apply to it. A second part, the *show*, is played by a pure director (`SceneDirector`, `CameraPath`; `Main` only hands it the clock
   and the keys): a camera path (a monotone cubic through the keys, at rest at both ends, or an orbit), the keys E, G and H pressed at
   given times, a keyframed door clicked, a lever arm opened or closed, the time of day, `quit`. W, A, S, D, Space or Left Shift pressed
   by the person takes the show over for good. `show report=stress.txt` writes a stress report next to the scene file (the renderer, the
   counts of instances, patches, triangles and draw calls, the load times, the Java heap, and the frame statistics: average, 1 % and
   0.1 % lows, percentiles, freezes), by rules that make it unable to overwrite a document. Two commands show what a file stands for
   without a window: `PbdConvertMain expand` and `PbdConvertMain check`; the viewer opens a `.pbdscene` like any other file. Every limit
   is checked at the line that crosses it. `PBD_FORMAT_SPEC.md` ("Scene description files") is the specification; its three whole scene
   files and the layout of its report are loaded through the engine by `RegressionSpecExamples` (183). `RegressionSceneFormat` (581),
   `RegressionSceneDirector` (211); mutants `mutants_scene_format.py` (596), `mutants_scene_director.py` (395), and `mutants_docs.py` (273)
   for the examples. **Conventions chosen where the request was silent** (they could be the other way; the journal lists them):
   ids counted from 1; the front of a model is its +Z side; a ring starts on the +Z side of its centre and goes toward +X; a grid counts x,
   then y, then z. **Not verified**: a take on a GPU (the camera path is held to scipy's curves and the program is run on a software renderer, but
   what a take looks like with your models nobody has seen) and the speed of a real card on a forest.
27. **A scene of 600 models died in the middle of its first frame; two guards now keep it from coming back.** The renderer took two
   64-byte matrices from LWJGL's memory stack - 64 KB per thread, given back only when the block that owns it ends, which for the renderer
   was the frame - for every model it drew, so at about 512 models the stack was full and the program stopped with `OutOfMemoryError: Out
   of stack space`, after every scene tried by hand had worked. No Java suite opens a window, so none could see it. The matrices are
   now taken once, before the loop (`PbdRenderer.renderCached`). `check_gl_stack.py` reads every source for memory taken from that stack
   inside a loop (and for the other ways of misusing it) and fails the run; `gl_runtime_check.py` starts the real viewer under a virtual
   screen on five scenes of its own - a show that presses E, 1,300 models (2,601 instances), a W pressed in the window that takes a show
   over, the G key at a lamp shade, a triangle, a cube and a mesh that cannot be turned into cubes - and reads the exit status, the output
   and the report; `mutants_gl_stack.py` (204), `mutants_gl_stack_sources.py` (16) and `mutants_gl_runtime.py` (15) break the checker, the
   program's sources and the program on purpose (the old defect is one of them). Neither stage is in a total (they print no `PASS:` lines);
   `TEST_PROCEDURE.md`, section I, says what each needs and shows. **What this does not show**: the project's own shaders (when the upload
   has none, the run uses stand-ins and says so), a real card, the picture, the sound and the mouse; and two limits of the renderer found
   while reading it for this and tracked in the backlog (items 32 and 33): one draw call per instance every frame with no culling, and a
   texture array that never asks OpenGL how many layers it may use.
28. **Not done yet**: a way to look at a PBD in the browser (the site keeps the file, a page draws it; the candidates and what each
   costs are in the backlog, item 20 - the recommendation is a page of plain JavaScript with a port of `ShapeEval`, checked against the
   same numbers as the Java one). It comes with the next delivery.
29. **A mutant that applies to nothing guards nothing: the mutation specs are now read as text on every run.** `mutate.py` has always said
   PATTERN NOT FOUND for a mutant whose text is gone from the code, but only when its spec is run, and a round takes hours; meanwhile functions are
   renamed, loops folded into tables, checks moved into libraries, and the number the documents quote for the spec stays right. Reading the specs
   against the code for the first time found 27 mutants in five specs that applied to nothing - among them the four that guarded the refusal of a
   private address and the four that guarded an IPv4 address inside an IPv6 one - and the rules against an address written in an unusual form
   (octal, hexadecimal, one number) and against a host that ends with a number had never had a mutant at all. Two older specs, run again, showed
   the same thing in a smaller way (the mirror's let one mutant live, the figures of the documents' checker named four patterns that had moved).
   They are rewritten on the present code, `mutants_fetch_policy.py` gained 72 mutants (532 now), and its survivors showed fourteen checks missing in
   `RegressionFetchPolicy` (an IPv4 tail that is empty, too long for an int or begins with a sign; single-word hosts that look like hexadecimal
   numbers) - they are written. One survivor was a mutant that sets a texture the add-on had refused to `None`: the fake `bpy` took it, Blender
   raises a `TypeError`, so the fake refuses it now too (one check pins that). `check_mutant_specs.py`, a step of `run.sh`, reads every spec and
   every file it breaks in a second, requires each `old` text to be found exactly once, each mutant to change something and each suite it
   names to exist; it checks itself first on a made-up project, and `mutants_mutant_specs.py` (91) breaks it. **What this does not
   show**: that a mutant which applies is killed - that is a run of its spec; `TEST_PROCEDURE.md`, section E, says which have been run to the end.

Items 30-32 answer the request of 2026-11-09: the name of the site (said to be unchanged in the add-on and in the server), a texture that has to be
*mapped* properly in Blender, which includes turning its UV by steps of 90 degrees, and a viewer that stopped on `Could not create the GLFW window`.

30. **A texture can be turned in steps of 90 degrees, in Blender and in the engine** (add-on v0.47.0). Engine: the material key `uvRotation` (degrees,
   multiples of 90; `MaterialUv.quarterTurns` reads it - a value that is not a multiple of 90, not a number or absurdly large is ignored and
   reported) turns the picture **once, where it is loaded** (`TextureTurn`; `MaterialPictures` works out which pictures a scene needs - a picture is a
   file *and* its quarter turns, each file is decoded once, and nothing is kept from one call to the next, so the hot loading of a scene stays
   safe). Every reader of a material gets the same picture: the texture arrays of the tessellated shapes (the four maps alike, so the normal,
   roughness and displacement maps keep lining up), the classic mesh renderer and the voxels of a shot object (`voxelTexturePixelsFor(path, turns)`).
   The scale and the mirror come first and the turn after - the order of Blender's Mapping node of type *Point* - and the turn is counter-clockwise
   as seen on the surface (u to the right, v up). Add-on: the **UV Rotation** row (0 / 90 / 180 / 270) and **Rotate Left** / **Rotate Right**
   on every material, in the Materials panel and in the object's panel; the picture node and the normal map go through one *PBD UVScale* Mapping node
   whose Rotation Z is *minus* the angle (Blender's node turns the coordinates, the engine the picture); the Save button, the exporter and the
   importer carry `uvRotation`. The Blender node is not trusted to say what it should: the test renders a plane with it in **Cycles** and
   compares 24 x 24 cells with the engine's own turned picture, for four angles and five scales (mirrors included), so the two agree to the
   pixel. `RegressionTextureRotation` (286), the add-on check (891 on the fake `bpy`, 878 on real Blender), mutants
   `mutants_texture_rotation.py` (99), `mutants_addon_rotation.py` (72), `mutants_addon_rotation_real.py` (20). The site's lint reads any key of a
   material and takes the new one as it is (checked; no change of the server was needed). **Chosen where the request was silent**: only
   multiples of 90 (a free angle would have to be done by the shader at every pixel of every frame); the numbers stored in a normal map are not
   turned with its picture (Blender does not either). **Not verified**: the drawing of a turned picture on a GPU (the renderers need a window;
   what is checked is the picture they are given), and a free angle, which is not supported.
31. **The viewer asks the machine for a window more than once before it gives up** (`GlContextPlan`, `GlContextNegotiator`, `LwjglGlfwDriver`).
   The report: `./gradlew run` ended on `GLFW_VERSION_UNAVAILABLE ... EGL: Failed to create context: Arguments are inconsistent` (EGL's
   `EGL_BAD_MATCH`) and `Could not create the GLFW window (check that the requested context - OpenGL 4.3 core - is available on this machine)`.
   The window used to make one request - 4.3 core, forward-compatible, on the platform GLFW picks - and stop. Since GLFW 3.4 (LWJGL 3.3.3 and
   later) a Linux session that has Wayland and X11 is opened on Wayland, whose contexts are all EGL ones, and WSL's Direct3D 12 layer reports
   OpenGL 4.2: both say no to that one request and yes to a neighbouring one, or to a software renderer. Now: the same request first (a machine that
   worked sees no change and nothing printed), then the same without the forward-compatible flag, then a compatibility context, on Linux each with
   EGL as well as GLX, and on a Wayland window the same on X11 (GLFW stopped and started on that platform); the request that worked is asked first
   by the next window of the run; one line says which request was refused and which one is used. When nothing works the exception lists
   every request with GLFW's own answer, what a window with *no* requirement gets from the machine (version and renderer), the session variables
   that matter and the cures that fit the system - `LIBGL_ALWAYS_SOFTWARE=1`, `MESA_GL_VERSION_OVERRIDE=4.3 MESA_GLSL_VERSION_OVERRIDE=430`,
   `PBD_GL_PLATFORM=x11|wayland`, `PBD_GL_API=native|egl`, the driver (`ENGINE_DEV_GUIDE.md`, "The window does not open"). `RegressionGlContext`
   (144: a GLFW that follows a script stands in for the machine - one that works at once, one that needs the second request, the other platform or
   EGL, one that refuses everything, one that cannot start, a later window, Windows -, the words of the message, and the lines that connect the plan to
   LWJGL, read as text), mutants `mutants_gl_context.py` (277). **Not verified, and not verifiable in the sandbox this was written in (no display,
   no GPU)**: that GLFW answers the way the script does, and which of the requests a given GPU, driver and desktop accepts. The cause on the machine
   that reported it is a guess from the log; if every request fails there, the message it prints is the next piece of evidence.
32. **The domain name was `pbd.aptheo.fr` in every source and in every zip of the last delivery; the older zips still carry the former one.** The report
   said that neither the add-on nor the server had been changed. Read again, the add-on (`fetcher.py`, `upload_server.py`, the labels of its
   buttons), the server (`pbd_metadata.php`, the README, the tutorial, `nginx.conf.example`) and the engine (`FetchPolicy.SITE_HOST`) name only the new
   host, `check_site_host.py` keeps it so (it reads every file of the project and of the server and of the add-on's zip) and the former name is
   found, in the zips delivered before, in the add-on 0.43.1 to 0.45.0 and in `pbd-benchmark.zip` - an add-on installed from one of those, or a
   server folder copied from one of them, still says the old name. The marker is the add-on's version in the panel's first line (`PBD Tools v0.47.0`
   now; 0.46.0 is the first with the new name) and the server's `current_version.txt`. A search of your own tree for the former name (`grep -rIn` with
   it) lists what is left; a library that holds references to the former host is rewritten by `server/migrate_domain.php --from <the former host>`
   (`--dry-run` first).

Items 33 and 34 answer the request of 2026-11-10: *"I told you the UV of a texture must be turnable, not just mirrored, **in the add-on**. And you
rushed the `.pbdbin` export, which is very important"*, with the Blender error `PbdConvertMain failed: ... UnsupportedClassVersionError:
pbd/PbdConvertMain ...`.

33. **The `.pbdbin` and `.pbdasset` buttons write their files themselves; they no longer run a Java found on the PATH** (add-on v0.48.0, hardened in
   v0.48.1). The cause of the error: the button asked Gradle for the project's classpath and ran `pbd.PbdConvertMain` with the `java` of the PATH, and
   the classes are compiled for Java 21 - a machine whose PATH leads to an older Java refuses them (*"has been compiled by a more recent version of
   the Java Runtime"*). Neither format needs Java: a `.pbdbin` is `PBDB`, a version byte and the gzip of the minified text; a `.pbdasset` is a zip
   (`manifest.txt`, `scene.pbd`, the materials and the pictures they name). `compiled_export.py` (no `bpy`, so a suite runs it alone) writes both and
   **the engine reads what it writes** - `PbdBinFormat`, `PbdAssetFormat` and the plain parser, on files of every shape the tests could think of.
   What the old route did not do and this does: the engine's own limits (64 MB of text, 512 files, the rules for an entry's name, `PbdLimits`) are
   checked **before the first byte**, so the add-on never writes a file the engine would refuse to open; the file is written under a name of its own
   and moved into place, so a failure (full disk, a name Windows refuses) leaves the earlier file as it was; a `.pbdasset` carries the pictures of its
   materials (the old one left them out, quietly) and rewrites the `include_material` lines to the paths inside the bundle (a second material folder
   becomes `materials/2/...`); a `.pbdbin` has its `.pbdmat` and its pictures **beside it, named after it** (`crate.pbdbin` -> `crate.pbdmat`; the
   old one wrote a `scene.pbdmat` that the next export overwrote); a name with a bracket, a brace, a comma or an `=` is quoted so that the engine's parser
   reads it back (tested through that parser); `pbd_ref` models are copied into the file (Inline References); and what cannot be done is **said** (a
   picture that is not there, two pictures with one file name), never skipped. The file browser opens on the `.blend`'s name in the `.blend`'s folder
   with the right extension (what Blender's own exporters do; a name kept from the last export in the other format gets the extension of this one,
   and a name that is already a file is marked), from the panel's two buttons **and from File > Export** (*PBD Binary (.pbdbin)*, *PBD Asset bundle
   (.pbdasset)*, v0.48.1 - the menu where a Blender user looks for an exporter), and a name that is nothing - empty, a folder, only the extension - is refused with
   *Give the file a name*: **the mutation round found that it used to write a hidden `.pbdbin` in whatever folder Blender was running in.** The one
   button that still runs Java, *Add to tileGeometry.txt*, now asks `java_runtime.py` for a Java that is new enough: the release the classes need is read
   from their class files (the major version in the first eight bytes, in a folder or in a jar), then `PBD_JAVA`, `JAVA_HOME`, the PATH, the JDKs Gradle
   fetched and the usual install folders are asked `java -version` one by one, and when none is new enough the message names each Java it found
   and the release it has. `addon_compiled_check.py` (151 checks, no Blender), the add-on check (891 on the fake `bpy`, 878 on real Blender: the
   dialog, the bundles, the references, awkward names, the Java button), mutants `mutants_addon_compiled.py` (349, made from the two modules by
   `py_mutants.py`) and `mutants_addon_compiled_ops.py` (114, the operators and the menu entries in `ui_panel.py`): every one killed, 12 listed as equivalent with the reason.
   **Not verified**: Blender's file-browser window itself (nobody has looked at it - the operator is exercised, not the screen), names with non-Latin
   characters through the Java button (the encoding of a command line depends on the locale), and the Windows branches of the search for a Java
   (made-up `C:\` folders only).
34. **The UV rotation is a box of its own in the add-on, with a sentence that says what the picture is doing** (v0.48.0; the rotation itself is
   item 30). The report was that the add-on offered "a simple mirroring". The add-on has had *UV Rotation* (0 / 90 / 180 / 270) and *Rotate Left* /
   *Rotate Right* since v0.47.0 - but in a row among the others, below the scale, where a Mirror U / Mirror V pair drawn above it was the first thing
   to see, and **nothing in Blender's default Solid view shows any of it**: Solid draws the mesh's own UVs and ignores the Mapping node, where the
   add-on keeps the repeat, the mirror and the turn (the preview of *Material Preview* and *Rendered* does show them). Now every picture-bearing
   material (a texture *or* a normal map) has one **Texture mapping** box, in the Materials panel and in the object's panel: *UV Rotation* with
   *Rotate Left* / *Rotate Right* first, then *Mirror U* / *Mirror V* (marked *(on)*), then **"Now: Turned 90 degrees counter-clockwise, mirrored
   left-right"** in words; when a viewport is in Solid shading the box also says that Solid cannot show the mapping and offers *Preview Material*, which
   switches the viewport to Material Preview. Each button acts on the entry drawn beside it, not on the active object's material. The Mapping node is
   the same one item 30 renders in Cycles and compares with the engine, cell by cell; it exists while the material has a texture or a normal map,
   which is also when the box is offered. `blender_addon_check.py` draws both panels on the recording layout and presses every button
   (`scenario_rotation_panels`), `mutants_addon_mapping_ui.py` (90) breaks the box, the sentence, the Solid note and the wiring,
   `mutants_addon_rotation.py` (72) and `mutants_addon_uv.py` (176) the rows and the operators. The proof that is easiest to look at is
   `uv_mapping_proof.png` of the delivery: real Blender (Cycles) renders a plane after the add-on's own buttons were pressed - *Rotate Left* three times,
   *Mirror U*, *Mirror V*, and together - next to what the engine puts on the same surface texel by texel, and the two agree in all 1024 cells of each
   of the seven cases. **Not known** then: which add-on the report was made with - item 35 is what the next report showed (the title of the panel was not the proof it
   was taken for).

Item 35 answers the first two questions of 2026-11-14 (*"Why did you make me three zips for tools? Do not scatter. The PBDBIN still does not export. The
UV rotation seems to work"*, with the same screenshot as on 2026-11-10), item 36 the third (*"does the server remove EXIF and the like from the
textures, for obvious reasons of security?"*).

35. **An add-on that was updated in place went on running its old code, and the `.pbdbin` button died again of `PbdConvertMain failed`** (add-on
   v0.48.2). The message **cannot come from v0.48.0 or later**: the text `PbdConvertMain failed` exists only in the add-ons up to v0.47.0
   (`ui_panel.py`), and since v0.48.0 the button writes the file itself and starts no program (item 33). The Blender in the screenshot ran older
   code. Blender does that when a newer copy of an add-on is installed over one that is running: it keeps the add-on's modules in memory, and
   switching the add-on off and on again reloads **only the package** (`__init__.py`, so `bl_info` becomes the new one), not the modules under it -
   `from . import ui_panel` hands the old module back, because the package already has a name `ui_panel`. Reproduced on real Blender 4.2.23 and
   5.1.2: after such an install `bl_info` says the new version, the first line of the panel - **read from `bl_info` each time it is drawn** -
   names the new version, and the buttons are the old ones. **So the advice "if the first line of the panel does not say v0.48.1, Blender is
   running an older add-on" (item 34, the journal of 2026-11-10, the guide) was unreliable**: that line can name the new version over the old code.
   What shows that the new code is in memory is that *File > Export* lists *PBD Binary (.pbdbin)* and *PBD Asset bundle (.pbdasset)* (they exist
   only in v0.48.1 and later). Now `pbd_tools/__init__.py` lets go of the add-on's modules - the entries of `sys.modules` **and** the package's own
   names, because the import finds either - before it imports them, so switching the add-on off and on after an install runs the new files; the
   way that cannot fail stays *install the zip, tick the add-on, quit Blender completely and start it again* (the README and the guide say it
   first). **Checked** by `blender_addon_check.py` on real Blender 4.2, 4.5, 5.0, 5.1 and 5.2: `scenario_update_in_session` installs two marked copies
   of the add-on through Blender's own installer, the second over the first, switches the add-on off and on, and looks at every module Python holds
   and every name of the package (all the second copy), at File > Export (each format once) and at a `.pbdbin` written by the updated add-on - it
   **fails on the code without the purge**; `scenario_compiled_starts_no_program` (fake `bpy` too) watches `subprocess.Popen` and `os.system` while
   both buttons write their file, with a `java` that dies of `UnsupportedClassVersionError` first on the PATH: no program is started; the whole story
   was also run by hand, with the real zip of v0.47.0, on Blender 4.2.23 and 5.1.2 (not part of `run.sh`): with the v0.48.2 zip installed over it and nothing
   switched, the add-on in memory is still v0.47.0 (its `bl_info`, its button asking for a Gradle project); with the checkbox switched off and on it is
   v0.48.2, File > Export has the two entries, and the `.pbdbin` button writes a file that starts with `PBDB` and starts no program; and a hole
   of my own tests is closed - `scenario_zip` and the first version of the update test enabled the **sources** (their folder was on `sys.path`),
   so "the installed zip works" had looked at the wrong files; now the module's `__file__` must be in the folder Blender installed into. Mutants:
   `mutants_addon_update_real.py` (7: the purge removed, run after the imports, of the table only, of the names only, not of `operators/`, not of the
   first module, too wide) and three more in `mutants_addon_compiled_ops.py` (114: a button that starts a program before it writes). **Not
   verified**: that this is what happened on the machine that reported it - a screenshot shows the message, not the add-on that printed it; an
   older zip installed by mistake gives the same message (the delivery folder held the add-on of v0.43.1 to v0.48.1 side by side, and the
   archive of `tools/` carries a copy of the add-on and its zip), which is why a delivery now has **one** add-on zip and the older ones are taken
   away; and the reporter's Blender (4.0.2) was not run.
36. **Pictures and sounds an author uploads reach the library without their metadata** (asked on 2026-11-14; nothing in the site had to change).
   `pbd_process_submission`, the function `upload.php` calls, sends every `.png`, `.jpg`, `.jpeg` and `.wav` through `pbd_sanitize_binary`
   (`pbd_sanitize.php`): the file is parsed by its structure and written again with only what draws or plays it - a PNG keeps `IHDR`, `PLTE`,
   `tRNS`, `IDAT` and `IEND`; a JPEG its frame header, its tables, its scans and the fixed parts of `JFIF` / `Adobe`; a WAV `fmt ` and `data` - so
   no EXIF (GPS, make, model, dates, a thumbnail), no XMP, IPTC or Photoshop block, no ICC profile, no comment, no PNG text chunk, no time stamp and
   nothing after the end of the picture survives. A file that cannot be parsed is **refused**, not passed on; the moderator's *accept* step
   requires a picture to be exactly what the sanitizer writes; `php check_library.php` lists the files of a library that still carry data the
   sanitizer would remove. The site's own suites check all of it (`server_sanitize_check.php`, `server_upload_lib_check.php`,
   `server_moderate_lib_check.php`, three runs through a real web server; mutants `mutants_server_sanitize.py`, `mutants_server_upload_lib.py`,
   `mutants_server_moderate_lib.py`). On 2026-11-14 it was also checked **from the outside**, by tools that are not the site's: 18 files (7 JPEG -
   EXIF with GPS, make, model, artist and dates, a thumbnail inside the EXIF, XMP, IPTC, an MPF segment, an ICC profile, a comment, a second picture
   after the end, progressive, grayscale, CMYK, 4:4:4; 10 PNG - text and compressed text, EXIF, ICC, dpi, every ancillary chunk, a zip, a script and a
   second PNG after `IEND`, Adam7, 16 bits, gray, gray and alpha, palette with `tRNS`; 1 WAV - `LIST`/`INFO`, `bext`, trailing bytes), made with
   Pillow and ImageMagick and a few segments injected by hand, went through the real `pbd_process_submission`, and what the site **stored** was
   read with Pillow, ImageMagick's `identify -verbose`, PHP's `exif_read_data` and a search for every planted string: 0 of 18 kept anything but its
   pixels, and the pixels are the originals' (not part of `run.sh`; the files and scripts were not kept). **What this does not do**: remove what is
   hidden in the pixel values themselves (steganography) - the sanitizer cannot see it; hide the author's file names, which the stored name keeps
   as a suffix of the public name (cleaned to the characters the site allows); clean files that entered the library before the sanitizer existed
   (that is what `check_library.php` is for); and the `.pbd` / `.pbdmat` texts are public by design (author, description, links). The site takes
   only `.pbd`, `.pbdmat`, `.png`, `.jpg`, `.jpeg` and `.wav` - **not `.pbdbin` or `.pbdasset`** - and the add-on's *Upload* button sends the `.pbd`
   text with its materials, pictures and sounds, whatever format was exported last.

**How much is checked.** 44 Java suites (5441 checks) + the Blender add-on check (891 checks) + the add-on safety check (417 checks)
+ the compiled-formats check (151 checks) = 6900 checks,
all green; run them all with `tools/regression/run.sh` (`TEST_PROCEDURE.md`), which ends by comparing the numbers the
docs quote with the ones it has just counted. The add-on check also runs on real Blender 4.2, 4.5, 5.0, 5.1 and 5.2
(`blender_real_check.py`, 878 on each, not added in), and the site has 6 PHP suites (2446 checks) and three runs through a real web server (not
added in either). Three more stages print no `PASS:` lines and are in no total: `check_gl_stack.py` and `gl_runtime_check.py` (item 27), and `check_mutant_specs.py` (item 29: every mutant of every spec still applies to the code it breaks). The newer code was also shown to **fail when the code under it is
broken on purpose** (mutation testing: `tools/regression/mutate.py` and the `mutants_*.py` files next to it re-run it):
53 spec files, 10383 mutants (a mutant is one deliberate break of the code under test, or of a document, that the
checks must notice); what the last round found spec by spec, killed or not, is in `TEST_PROCEDURE.md`, section E. The specs: the add-on
`mutants_addon.py` (103), `mutants_addon_break_material.py` (283), `mutants_addon_mechanisms.py` (182), `mutants_addon_real.py` (13),
`mutants_addon_light_click.py` (21), `mutants_addon_uv.py` (176), `mutants_addon_uv_real.py` (57), `mutants_addon_rotation.py` (72), `mutants_addon_rotation_real.py` (20), `mutants_addon_mapping_ui.py` (90),
`mutants_addon_compiled.py` (349), `mutants_addon_compiled_ops.py` (114), `mutants_addon_update_real.py` (7), `mutants_addon_safety.py` (1907); the
formats, the lever arm and the keys `mutants_roundtrip.py` (33), `mutants_bare_base64.py` (4), `mutants_migration.py` (29),
`mutants_arm_facade.py` (4), `mutants_lever_arm_system.py` (61), `mutants_light_links.py` (57), `mutants_container_stock.py` (20),
`mutants_use_key.py` (151); the scene description file and its show `mutants_scene_format.py` (596), `mutants_scene_director.py` (395); the memory LWJGL lends and the viewer run for real `mutants_gl_stack.py` (204), `mutants_gl_stack_sources.py` (16), `mutants_gl_runtime.py` (15); the window `mutants_gl_context.py` (277); the destruction chain `mutants_voxel_shapes.py` (331), `mutants_mirror.py` (77),
`mutants_texture_mapper.py` (148), `mutants_texture_rotation.py` (99), `mutants_mesh_voxel.py` (84), `mutants_voxel_debris.py` (102), `mutants_destruction_world.py` (227),
`mutants_voxel_fracture.py` (168), `mutants_particles.py` (273), `mutants_break_profile.py` (115); hiding places
`mutants_slab_openings.py` (86), `mutants_hiding_rules.py` (277); sounds `mutants_mp3_decoder.py` (134), `mutants_game_sounds.py` (92); what a
file may ask of the program `mutants_fetch_policy.py` (532), `mutants_fetcher.py` (247); the site `mutants_server_http.py` (340),
`mutants_server_metadata.py` (45), `mutants_server_sanitize.py` (854), `mutants_server_upload_lib.py` (242),
`mutants_server_moderate_lib.py` (207); the documents `mutants_docs.py` (273), `mutants_doc_counts.py` (45); the one domain name `mutants_site_host.py` (38); the check that the specs still fit the code `mutants_mutant_specs.py` (91).

| Area | Suite (checks) | Covers |
|---|---|---|
| Primitives / taper / colour | `RegressionPrimitives` (7), `RegressionTaper` (3), `RegressionTaperWide` (8), `RegressionPerVertexColor` (2) | voxel volumes vs. the exact formulas, taper vs. frustum volume, colour per vertex |
| A primitive voxelized as it is drawn | `RegressionShaderPort` (85), `RegressionVoxelLattice` (23), `RegressionVoxelShapes` (106), `RegressionVoxelLimits` (16), `RegressionMirror` (54) | the port of the shader against numbers worked out from its text; which voxels, to the voxel; every shape with every parameter (`cap`, `faces`, rounding, taper, bend, shear, scale) against exact volumes; the extremes (huge, tiny, zero, NaN) under a time limit; a negative scale as a mirror |
| The picture of a shot object | `RegressionTextureMapper` (361), `RegressionTextureRotation` (286) | the shader's mapping run backwards on 1700 points of every primitive (with and without modifiers), a negative UV scale, a mesh's own UVs, the wiring; a picture turned by quarter turns (`uvRotation`) is the same picture for the shader's arrays, the classic mesh renderer and the voxels, whatever the scale, the mirror and the file |
| Voxel destruction chain | `RegressionCraterOOM` (16), `RegressionVoxelCrater` (26), `RegressionVoxelConnectivity` (17), `RegressionVoxelMesh` (10), `RegressionRigidDebris` (20), `RegressionMeshVoxel` (130), `RegressionVoxelDebris` (44), `RegressionDestructionWorld` (172), `RegressionWorldDebris` (60) | crater cost under a 512 MB heap, crater shape, split into pieces, meshing, falling bodies, a `mesh` instance as the target (closed: solid; open: a wall), the pieces' bookkeeping, the whole world (G -> chips -> cut -> break -> fall -> rest, a piece shot again, a piece that breaks while falling, an object destroyed outright) and what it leaves behind |
| Chips and breaking (material-defined) | `RegressionParticles` (148), `RegressionBreakProfile` (76), `RegressionVoxelFracture` (59) | the chips against the continuous equations (flight, bounce, friction, rest, fade, the cap, the emitter); the ten break keys of a `.pbdmat` (defaults by material, ranges, refusals, the spec's table against the code); the cassure (a connection thinner than the material holds does not hold), against a brute-force reference |
| Hiding places | `RegressionSlabOpenings` (39), `RegressionHidingRules` (189) | how much of a shot door is open (the widest square hole, not a percentage of damage) and the verdicts: an altered door is open, concealing, shut, the player's own state, may the player go in / out |
| Formats | `RegressionMinifiedMetadataQuoting` (6), `RegressionFormatRoundTrip` (105), `RegressionPbdRefCopy` (248) | minified files, every field through every format (a material with all ten break keys, a light with its click included; the one field the files leave out on purpose, a scene file's show, is proved to be written by none), `pbd_ref` copies everything |
| Lever arm and lights | `RegressionLeverArm` (45), `RegressionLeverArmSystem` (147), `RegressionLeverArmAttach` (90), `RegressionLeverArmMigration` (57), `RegressionLightLinks` (84) | the format block, the run-time system (value, lock, release, sounds, grab, drag), anything attachable, the keyframe -> arm tool, which light follows which door and when it is on |
| The E key | `RegressionUseKey` (158), `RegressionContainerStock` (33) | what the crosshair is on, whose lights a press switches (the model rule, the link group, the room switch), the nearer-wins arbitration with an item, the click; what a container remembers between two openings: loaded on the first opening only, shelved when every door is shut, given back as it was left (an emptied container stays empty), forgotten on a new scene |
| Sounds | `RegressionMp3Decoder` (224), `RegressionGameSounds` (51) | the MP3 decoder against the C original it was ported from (bit for bit on recorded streams, within one step on the conformance ones) and the choice and the playing of the pickup / shot / click sounds |
| The window | `RegressionGlContext` (144) | the requests for an OpenGL 4.3 core context in their order (the old one first, the forward-compatible flag dropped, a compatibility context; GLX then EGL on Linux; X11 after Wayland), the walk through them against a GLFW that follows a script, the words of the message when every one fails, the settings that choose a platform or an API, and the lines that connect them to LWJGL, read as text |
| What a file may ask of the program | `RegressionFetchPolicy` (675), `RegressionFetcher` (229), `RegressionIncludeLimits` (213) | every address a scene may name, line by line (the private ranges in every spelling, a network path, an alternate stream); the fetch against a local server (a redirect, a download that never ends, a half-written cache); the include depth, the instance budget, the size of a decompressed file and of a bundle, the names inside a bundle |
| Documentation | `RegressionSpecExamples` (183), `check_doc_counts.py` | every example marked `<!-- check: scene -->` / `<!-- check: material -->` in `docs/*.md` is parsed by the real parser; the spec's and the add-on guide's lever-arm examples are pinned to the numbers the text states; the break table of the spec is compared cell by cell with the code; the light a press of E switches is asked of the engine's own `LightSwitch`; an unmarked example that shows a `leverArm` fails; the numbers the docs quote (checks per suite, mutants per spec, totals) are compared with the run |
| A scene description file | `RegressionSceneFormat` (581), `RegressionSceneDirector` (211) | every directive's text to the character, the scatter, rings and orbits against a second implementation in Python, the folder listing, every limit at the line that crosses it, hostile lines and tens of thousands of random combinations held to the few shapes of line the expansion writes; the camera path against scipy's curves, the director's clock and events, the sun, the camera the engine takes and gives back, the frame statistics and the one file a report may write, and the lines of `Main` that connect them |
| The viewer, run for real | `check_gl_stack.py`, `gl_runtime_check.py` (no `PASS:` lines: in no total) | nothing taken from LWJGL's stack inside a loop (138 made-up pieces of Java, then every source); the real viewer under a virtual screen on five scenes of its own: 2,601 instances, a show's keys, a W that takes it over, G at a lamp shade, a triangle, a cube and a mesh that cannot be turned into cubes |
| Blender add-on | `blender_addon_check.py` (891), `addon_safety_check.py` (417), `addon_compiled_check.py` (151) | the add-on's export/import against the engine, closed/half/open/keyframe poses, the File metadata box, names the format cannot hold; the lever-arm, light and ready-made-piece buttons; the click of a light; the texture coordinates against the engine's evaluator of the shader, Mirror U / V, the UV rotation; the break settings of a material; its panels, menus, dialogs and buttons drawn and pressed on a recording layout; registration; sources that cannot be read; the two export buttons start no program; the committed `pbd_tools.zip` is the sources. The safety check: the fetch rules, the budget of an import, the files an upload may hold, hostile files |
| Blender add-on, on real Blender | `blender_real_check.py` (878) | the same scenarios with the add-on registered in Blender 4.2, 4.5, 5.0, 5.1 and 5.2 (878 on each): every icon exists, the registered settings are the declared ones, every button pressed once (none dies with an exception), a script's edits exported and imported at once, hostile files and the upload operator (their messages read from Blender's own console), the meshes built with `bmesh`, the zip installed, enabled and disabled by Blender's own installer, and a copy installed over a running one (every module and every name the second copy). Needs `pip install bpy`; not part of the totals above |
| The PBD site | `server_sanitize_check.php` (542), `server_lint_check.php` (466), `server_lint_policy_check.php` (295), `server_http_check.php` (458), `server_upload_lib_check.php` (442), `server_moderate_lib_check.php` (243), `server_e2e_php` (417), `server_e2e_apache` (522), `server_e2e_nginx` (522) | what an upload, the moderator and the search page do, rule by rule, and the whole site through a real web server (`TEST_PROCEDURE.md`, section H); not part of the totals above |

**What is NOT verified, and why** (the honest list - none of it is hidden in a test that passes):

- Everything that needs a GPU window: the real GL drawing of voxel remains, flying pieces and **chips** (one draw of the
  shared cube per chip), the mouse and key wiring in `Main.java` (the **H** refusal and its message, the **E** handler - item or light, and the click -, the
  pickup sound's trigger, the facade getters in a running window), how a drag *feels*, how the picture of a shot object looks (its
  mapping is verified by arithmetic, never by eye), shader paths, frame times. The pure logic underneath is
  verified; the glue is **reviewed** only - except what `gl_runtime_check.py` has run since 2026-10-25: the real program on a software
  renderer starts, draws 2,601 instances, reads the keys of a show (E, G), is taken over by a W pressed in its window, ends by itself,
  writes its report, and answers a shot at a lamp shade, a triangle, a cube and a mesh that cannot be turned into cubes with a destruction
  and no exception. What it did not run: the project's own shaders (it used stand-ins; the upload has none), a real card, the picture,
  the sounds, the mouse. The pictures in `docs/renders/g_*.png` are produced by the real
  `DestructionWorld` and a small software rasteriser (`tools/regression/VoxelFallDemo.java`) - they show
  what the engine is *told to draw* (the chips, the cassure, the pieces), not what OpenGL draws.
- Blender's **window**: how the panels look, undo, the viewport drawing of `mesh_preview.py`. The add-on is run on a
  fake `bpy` with Blender's real `mathutils` and - with `pip install bpy` - on **real Blender 4.2, 4.5, 5.0, 5.1 and 5.2** (operators,
  registration, icons, depsgraph), but never drawn in a window. Your Blender (4.0.2), 4.1, 4.3 and 4.4 were not run (backlog item 23 says what was done for 4.0).
- The site (`server/`): its code is run here on PHP, Apache and nginx (`TEST_PROCEDURE.md`, section H) and moderation accepts a
  `leverArm` block, a `switchSound` and every other key of the format. What is **unknown**: the real host's configuration (`AllowOverride`,
  the PHP-FPM pool, TLS), the real library, and the habits of the real moderators. **Reviewed**, not run: the wording of `tutorial.php` and
  `index.php`, and the maintenance scripts that run from a shell (`migrate_domain.php`, `check_library.php`, `repair_texture_refs.php`).
- The sound files (`item-equip.mp3`, `doomshotgun.wav`, any door sound or `breakSound`): not in the upload, so the real
  `item-equip.mp3` has **never been seen**; `light-switch.wav`, the click of E, was written here by
  `tools/scripts/make_light_switch_wav.py` - it decodes, nobody has listened to it: the decoder is verified on streams made here (`TEST_PROCEDURE.md`, section F), not
  on that file, and no sound has been played through a real sound card. `SoundPlayer` logs a warning and carries on when a file is
  missing or cannot be decoded, so nothing crashes, but nothing is heard until the files are there.
- The native crash on G over a *textured* primitive (Windows `0xC0000374`): mitigated several times (bounds checks, a rollback, a shader cache, decoding at scene load, the double-decode fix),
  never reproduced here, never confirmed fixed.

**Ground rules in force.** The real folder layout is never changed (new files go inside existing folders).
Nothing is cached in the destruction path (assets can be hot-loaded). No claim of "fixed" without a test
that failed before and passes after.

**Behaviour changes you will notice** (all intended):

- **G is much stronger**: the crater is a solid irregular volume of radius 30 voxels (a hole about 0.30
  world units across) instead of a thin ray tunnel - see section 3.2.
- **Blender export of a child of a primitive changed**: the child is exported with `pos/parent_factor` and
  `scale x own/parent` (a Blender primitive is 2x2x2, the engine's 1x1x1). Earlier exports of such children
  were two times off; **re-export any scene that has parts parented to a primitive**.
- **An embedded `pbd_ref`** (the add-on's "Embed contents" box) is now anchored on a `group`, not on a
  `metadata` cube. Old embedded files still load.
- **The writer keeps more**: curves, every `include_material`, awkward values (spaces, `#`, `=`, `,`) and the
  materials of placed (`pbd_ref`) files are now written back by every format.
- **Blender object names**: a name the format cannot hold is written cleaned up (`front door` -> `front_door`,
  a clash gets `_2`) and the export lists what changed; before, such a scene could not be loaded at all. A material
  name with a space is only reported. The importer now accepts every id the engine does (`wall-left`, `a:b`).
- **A `mesh` instance can be shot**: a closed one is solid, an open or flat one (a sheet, a single triangle) a wall of 3 voxels
  (15 mm); one with nothing to voxelize is destroyed by the hit (the first version refused an open mesh and left it standing).
- **A container remembers what you took**: close its door and open it again and the item is still gone (and what
  rested on it has fallen); before, the whole content was rolled again. Until the scene changes or the viewer
  restarts - nothing is saved to disk.
- **Left click**: a lever-arm instance (or anything attached to one) is grabbed and dragged; a keyframed
  door still toggles on click. A locked arm reports "locked" and does not move.
- **A hit no longer makes material disappear.** What the crater takes out (and the crumbs too small to be a piece) is thrown
  up as chips that fall, bounce and fade; the number is `debrisCount` of the material, scaled by how much was removed (a post
  shot through throws about twenty, a big slab a hundred), up to 1500 alive at once.
- **A thin connection breaks.** After a hit, a connection narrower than the material's `breakWidth` (wood 3 cm, metal 1 cm,
  plastic 1.5 cm, other 2 cm; 0 = never) shatters into chips and the piece it held is cut loose and falls: a plank cut almost
  through no longer hangs by a sliver. A material that must not do that says `breakWidth=0`.
- **How a material breaks is set in the material** (`.pbdmat`: ten `break*` / `debris*` keys, see `PBD_FORMAT_SPEC.md`),
  never in a primitive's metadata - metadata written there is ignored. A key that cannot be read is refused with a warning and
  its default stays.
- **Hiding: a shut place cannot be entered or left, and a shot door is an open door.** **H** says "The door of 'X' is closed - open it
  before getting in/out" when every door is fully closed **and unaltered**. A door that a hit has reached at all - a dent, a hole,
  voxels, a handle shot off - counts as open, whatever its arm says and however small the damage: **H** works, and a player inside is
  not concealed (`isPlayerConcealed()` is false), so seen from outside. (The version of 2026-10-12 let a hole under 3 cm hide and
  a hole under 40 cm keep the place shut; there is no threshold any more.) A door that does not move at all (a linked instance with
  no lever arm and no keyframes) is not a door, and a closed door that is locked still keeps the place shut.
- **A light that shares its `linkGroup` with a door or a drawer follows it**: on while any of them is open, off when all are
  shut; its own `lightEnabled` is ignored then. A looping instance never switches a light.
- **E plays a sound** when it takes an item out of a container (`resources/sounds/item-equip.mp3`); a press that finds nothing is silent.
- **G keeps the shape of what it hits.** The shade of a lamp (`cap=none`) stays a tube, a crate without its lid stays open, a bent pole
  stays bent, a cut-out leaf stays cut out: the voxels are those of the surface that is drawn. The wall of a hollow shape is 3 voxels
  (15 mm) thick, and a tube narrower than about 7 voxels (3.5 cm) comes out nearly solid. A shape too big for the work gets larger
  voxels and the hit says so. What cannot be turned into cubes at all is destroyed by the hit instead of being refused (item 19).
- **E has two jobs**: it takes the item under the crosshair out of an open container, or it flips the lights of the model aimed at (a
  lamp, a wall plate: item 20); the nearer wins. The click is the light's `switchSound`, else `light-switch.wav`.
- **A shot object keeps its picture the right way round** on every face; a **negative `uvScale`** mirrors the picture (the add-on's
  **Mirror U / V** write it) and a **negative object scale** mirrors the object, in the viewer and in the voxels alike.
- **The engine, the add-on and the site refuse more** (item 25): only https addresses that are not the person's own network (plain
  http only to this very machine, for tests), no redirect, 64 MB a file, 8 levels of `pbd_ref` (6 in the add-on, 4 on the site) and
  200,000 instances (50,000 in the add-on and on the site). A file that reaches a limit is refused with a message that names it; none
  of them is near a real model. The former host name is not trusted any more: a library that still holds references to it needs
  `server/migrate_domain.php`.

## 2. Lever-arm specification, checked line by line

The original specification is the roadmap entry "Lever-arm system (major feature, format-wide)". An earlier
revision of this file had cut it down to "what is still open", so here is its **first wording in full** - the
reference the table below is judged against:

> A new primitive/mechanism, reflected across EVERY format (`.pbd`, `.pbdbin`, `.pbdasset`, the Blender addon, the Java engine):
> - Free rotation OR a lockable position.
> - Other primitives can be linked/attached to it (same idea as `linkGroup` for synchronized doors, but a real parent-like
>   attachment, not just synchronized open state).
> - Two defined endpoints: 0 = closed, up to 1 = fully open - meant to let PHYSICS drive a door's position continuously
>   (0..1) instead of the current keyframe-interpolated swing, which is reported to produce visual glitches.
> - `getArmRotationValue()` (name to be finalized) - lets AI/modding code read how open a door-like arm currently is, e.g. to
>   decide whether a zombie can see through a door that's ajar rather than only checking a binary open/closed state, and to
>   gate whether a container's own contents should be shown/hidden.
> - SFX support the same way a keyframe can carry a `sound=` - a lever arm should be able to sonify its own opening/closing motion.
>
> This is the largest item here and likely needs its own dedicated design pass (format spec changes, parser/serializer support
> in both Java and the Python addon, PHP server handling if it affects upload/moderation, renderer support) before
> implementation starts, not a single-turn addition.

Three clarifications came later and are included in the table: the arm needs an **open angle and a closed angle** (to
link with the other modules - the facade, e.g. what is displayed inside a container); there need **not be any baked
animation**; and the door should open with a **mouse drag and drop with physics** (you chose a geometric prototype first,
real physics later).

| # | What was asked | State | Where it lives | Evidence |
|---|---|---|---|---|
| 1 | "reflected across EVERY format (`.pbd`, `.pbdbin`, `.pbdasset`, the Blender addon, the Java engine)" | **verified** (server: unknown, row 9) | `.pbd`: `PbdInstance.LeverArm`, `PbdParser.parseLeverArm`, `PbdSerializer` (pretty and minified). `.pbdbin` is gzip of the minified text, `.pbdasset` zips `scene.pbd` - neither re-implements the schema, so both carry it. Blender: `properties.py`, `ui_panel.py`, `operators/export_pbd.py`, `operators/import_pbd.py`. Engine: `HierarchyResolver`, `LeverArmSystem`, `PbdRenderer` | `RegressionLeverArm` (45): parse + pretty/minified round trip, interpolation, clamping. `RegressionFormatRoundTrip` (105): a door whose arm has every option set (`release = free`, `locked`, `lockAt`, both sounds - one with a space in its name) goes through all four formats and is compared field by field. `RegressionLeverArmAttach`: attachments survive all four. `blender_addon_check.py` (891): the pose the engine computes from the add-on's export equals Blender's at arm 0 / 0.5 / 1 |
| 2 | "Free rotation OR a lockable position" | **verified** | `release = snap` (default: eases to the nearer end) `\| free` (stays where it was left); `locked = true`, `lockAt = 0..1`; run-time `setArmLocked` / `isArmLocked` (`PbdRenderer`, and `Main` for mods) | `RegressionLeverArmSystem`: a locked arm ignores easing, drag, `setValue`, `setTarget` and release; can still be *picked* so the game can say "it's locked"; unlocking applies the release policy; locking mid-swing freezes it; linked twins; format: `RegressionLeverArm`; add-on fields: add-on check |
| 3 | "Other primitives can be linked/attached to it ... a real parent-like attachment, not just synchronized open state" | **verified for every primitive type** | `parent = <arm>` (also on a `pbd_ref ... parent=`), run-time `PbdEngine.SceneHandle.setParent(child, parent)` | section 3.1: `RegressionLeverArmAttach` (90) |
| 4 | "Two defined endpoints: 0 = closed, up to 1 = fully open - meant to let PHYSICS drive a door's position continuously (0..1) instead of the current keyframe-interpolated swing" - and later "an open angle AND a closed angle, to link with the other modules (facade)" | **verified** (geometry + value); the "physics" is a **geometric drag** - your choice ("Prototype simple maintenant"); a physics engine is still open | the instance's own `pos`/`rot` = closed (0); `leverArm { openPos openRot }` = open (1); `HierarchyResolver.resolve(scene, animTimes, armValues)`; `LeverArmSystem.setValue/dragToward` | `RegressionLeverArm` (lerp at several values, clamping, NaN -> closed); `RegressionLeverArmSystem`: the drag is checked against a hinge worked out by hand - worst error 2.3e-6 of the 0..1 range (0.0002 degrees), 400 random cameras/values, a sliding arm, an arm under a moved and turned parent, exact ends, no jump over 1 % per 0.5 % of aim, about 0.015 ms per frame |
| 5 | "`getArmRotationValue()` (name to be finalized) - lets AI/modding code read how open a door-like arm currently is ... and gate whether a container's own contents should be shown/hidden" | **verified** (value) / **reviewed** (GL wiring); named `getArmValue` - the value is not always a rotation (a drawer slides) | `PbdRenderer.getArmValue(id)`, **`Main.getArmValue(id)`** (static facade next to `availableVolume`/`canHide`, added today - before, only `PbdRenderer` had it), `LeverArmSystem.value`. Facade link: `isInstanceOpen/FullyOpen/FullyClosed` read the arm value for lever-arm instances, so contents appear when a *dragged* door is open | `RegressionLeverArmSystem`: value contract, "no arm -> NaN", facade-without-a-scene answers NaN/false instead of throwing (3 checks, 4/4 mutants). The container wiring in `PbdRenderer` is **reviewed** |
| 6 | "SFX support the same way a keyframe can carry a `sound=`" | **verified** | `openSound` / `closeSound`: played once when the arm leaves / returns to the closed end, whoever moved it (drag, script, easing); hysteresis (closed at 1 % or less, "left the closed end" only above 2 %) so aim jitter cannot chatter; a looping arm never clicks | `RegressionLeverArmSystem` (nine checks: one sound per crossing, none while it keeps moving, jitter inside the dead band is silent, a file name with a space survives, silence when settled, none for a looping arm); round trip keeps both names; add-on fields. Actual audio output: needs the sound files and a sound card |
| 7 | "needs a dedicated design pass: format spec, parser/serializer in both Java and the Python addon, PHP server if it affects upload/moderation, renderer support" | **done** except the server | `PBD_FORMAT_SPEC.md` (`## Lever-arm`, rewritten with every field), `ADDON_USER_GUIDE.md`, `ENGINE_DEV_GUIDE.md`, `tools/blender-addon/README.md` | `RegressionSpecExamples` (183): the spec's three lever-arm examples (a door on a pivot, a drawer, a locked lid) and the add-on guide's door are parsed by the real parser on every run and pinned to what the text says (90 degrees about Y, speed 2, snap, two sounds one of them with a space in its name; a pure slide that stays where it is left; locked at 0 and opening -100 degrees about X); `mutants_docs.py` breaks these examples on purpose (148 mutants in all; `TEST_PROCEDURE.md`, section E, says what the last round found) |
| 8 | later: "no mandatory baked animation - I want to open the door with a mouse drag and drop with physics" | **prototype done**: geometric drag-and-drop, no physics engine | `LeverArmSystem.pick` (what a click grabs - through any attached primitive), `dragToward` (coarse sampling + golden-section refinement, no hinge assumed), `PbdRenderer.findLeverArmGrabPoint/dragArmValueTowardRay`, `Main` mouse handling | the maths: `RegressionLeverArmSystem`. The mouse wiring and the feel: **reviewed only** - grab a door in-engine before video 2 (section 5) |
| 9 | PHP server (`upload.php` moderation, `tutorial.php`) | **verified** (the code, run here on PHP, Apache and nginx); the real host **unknown** | `pbd_lint.php` reads every field of a `leverArm` block (a field the format does not have is refused, with its name) and treats `openSound`, `closeSound`, `switchSound` and every other key ending in `Sound` as a sound reference like any other; the add-on's client (`upload_server.py`) checks the files with `upload_rules.py` before it sends anything | `server_lint_check.php`, `server_upload_lib_check.php`, the e2e runs; `tutorial.php` has its sections (section 6, item 4) |
| 10 | later: "the keyframes on the doors have to be replaced by lever arms before video 2" | **tool done**; running it over the asset library is yours (the library is not in the upload) | `LeverArmMigration`, `java pbd.PbdConvertMain lever <file.pbd\|.pbdbin\|folder> <output>` | `RegressionLeverArmMigration` (57), 29/29 mutants. Converts a door's rotate/slide keyframes to an arm (and its sound to `openSound`/`closeSound`), leaves anything it cannot express alone and says why (journal, "keyframe -> lever-arm migration tool"). It never overwrites its input |
| 11 | later (2026-10-10): "I cannot see anywhere in the add-on how to add a toggleable light source and lever arms, the way keyframes are" | **done**: checked against the engine, not only for "does not crash"; the Blender window itself **reviewed** | Add-on v0.45.0: box *Lever Arm* (**Door**, **Drawer**, **Lever**, and **X** to take it off), box *Light that follows this object* (**Add Light Linked to This**), *Create* and *Add > Mesh* (**Door (hinge)**, **Drawer**, **Light Switch + Lamp**): `operators/add_mechanism.py`, `ui_panel.py`. Engine: `LightLinks` (a light that shares a `linkGroup` with something that opens - keyframes or a lever arm, not a loop - is on while any of them is open); no new format key | `RegressionLightLinks` (84); `blender_addon_check.py` (891): the open pose the engine computes from the export is Blender's, the add-on's answer to "does this open?" is the engine's object by object, the engine says which lamp follows which door in an exported scene; `blender_real_check.py` (878): every button pressed on real Blender; `mutants_addon_mechanisms.py` (182), `mutants_light_links.py` (57) |

Extras that were built because a check of the above showed they were needed: **lights follow arms**
(`LightTracker`: a lamp hung on a door used to stay behind in the buffer while its mesh moved), **run-time
`setParent`** (it only changed an index before, so a save lost the attachment), **`pbd_ref` copies everything**
(keyframes, arms, lights, `indestructible`, meshes, container triggers were being dropped - `RegressionPbdRefCopy`,
248 checks), and **a Pivot in Blender** (Create > Pivot (hinge): the engine's `group`) because an arm is best put on a
scale-1 pivot.

**Known limits of the lever arm** (all documented in `PBD_FORMAT_SPEC.md`):
a pivot's scale must stay 1 (children inherit their parent's *scale* as well as its pose); an arm on a `ref` or
`light` object is not exported from Blender (use a pivot); poses are interpolated as Euler angles, angle by angle
(the format can express a swing of more than 180 degrees about one axis, but the Blender add-on writes the Euler
triple nearest the closed pose, so it exports such a swing as its shorter equivalent - hand-write `openRot` for
such a door); keyframes win over an arm if an instance has both; Blender shows the arm poses but has no physics or
drag; mid-pose agreement between Blender and the engine is only guaranteed for single-axis rotations.

## 3. The two checks asked for on 2026-10-07

### 3.1 Can other primitives be attached to a lever arm? - yes, every one of them

`RegressionLeverArmAttach` (90 checks) was written against the primitive registry, so a primitive added
tomorrow is covered without touching the test (the first check fails loudly if the list changes):

- All ten types - `plane, sphere, cylinder, cone, cube, disc, torus, mesh, light, group` - attached to a pivot
  move **and turn** with it at arm 0, 0.37 and 1; the expected poses are computed by hand, not by the engine's
  own matrix code.
- Each of the ten can also **be** the arm (a position-plus-rotation arm), and what rides on it follows.
- Clicking any attached primitive that has geometry grabs the **arm** (and reports the point in the arm's own
  frame, which is what a drag keeps fixed). A `light` or a `group` has nothing to click and is only reachable
  through what hangs on it. A mesh far from its origin is grabbed where its triangles are.
- **Nesting**: a drawer that is its own arm, inside a door that is an arm, with a knob and a tag on the
  drawer - 6 combinations of door x drawer positions; the drawer slides along the *door's* own axis; a click on
  the knob grabs the drawer (nearest arm), not the door.
- **A placed file hangs on it too**: a `pbd_ref ... parent=hinge` of a `.pbd`, a `.pbdbin` and a `.pbdasset`
  - the referenced file's group and both its parts sit where the hinge puts them at 0, 0.6 and 1, and grabbing
  the handle that came from the file grabs the door.
- Attachments survive **all four formats**, and a light on the arm moves its light (`LightTracker`, 12 checks).
- **Run-time attach/detach** (`setParent`): the saved text carries `parent = hinge` (pretty and minified),
  parents are kept before children, an unknown name / a cycle / "own parent" are refused and change nothing,
  detaching makes the part a root again. The pose is not compensated in either direction (its `pos`/`rot`/`scale`
  are kept as written and read relative to the new parent, or as world values after a detach), which the
  `setParent` doc now says.
- Scale: a handle under a scale-1 pivot keeps its size; under a scaled panel it inherits the scale (expected
  values worked out in the test). That is why the add-on recommends a scale-1 pivot as the arm.

In Blender the same is true by construction - anything parented to the pivot is exported with its `parent`
- and `blender_addon_check.py` compares every object's world matrix (door panel, handle, nested parts) between
Blender and what the engine computes from the export, at arm closed / half / open.

### 3.2 Is G powerful enough to cut a primitive, and does the severed piece fall? - yes

**Why it could not before.** The old crater was about forty one-voxel-wide ray tunnels - roughly 500 voxels
whatever the radius - which dented a thing but could never cut it. The crater is now a **solid, irregular
volume** (`VoxelCrater`) of `CRATER_RADIUS_VOXELS = 30` voxels of 5 mm: a hole about 0.30 world units across,
digging up to half again as deep along the line of fire, with a rough rim. Rule of thumb: **one hit severs
anything up to about 2 x radius (0.30) across**; a wider panel gets a hole and needs a second hit near its edge.

The whole chain is run without a GPU by `RegressionDestructionWorld` (172 checks) - the same `DestructionWorld`
object `PbdRenderer` drives, with the floor and the other boxes of the scene as landing surfaces - and
`RegressionWorldDebris` (60 checks) replays each hit by hand from the pure parts (crater, break, sorting, chips) and
requires the world to give back exactly what the parts give:

| Shot (one hit at the middle unless stated) | What the run measured |
|---|---|
| Post 0.1 x 1.0 x 0.1, hit at 0.55 height | 21,859 voxels removed; the stump keeps 34,234 (1,964 geometry pieces); **1 piece breaks off (23,907 voxels)**; falls and **comes to rest asleep, lying on the floor beside the stump** (lowest point -0.5 mm, inside the allowed -4 mm..+1.2 cm), away from the shooter's side. **23 chips** are thrown out of the hole. The first hit in a fresh JVM takes 160-213 ms (8 runs: class loading and a JIT that has not warmed up); the next hit, on another fresh post in the same JVM, 23-40 ms. The break analysis and the chips are not what takes the time: with both switched off the first hit takes 125-190 ms (9 runs). An earlier revision of this table said 60-95 ms; measured today it is not that, cold (backlog 26) |
| Same shot twice | bit-identical debris (the crater is derived from the rounded impact point) |
| Plank 0.05 x 1.0 x 0.25 | cut; standing plank cut low: the small bottom stump stays, the big top falls. The same cut on a *lever-arm* plank (not on the floor): not grounded, so the **largest** piece stays and the small one falls |
| Plank turned about Y, shot through its thin side | cut; the piece starts inside the turned plank's world box (frames agree under rotation); a second shot down the same line goes through the hole |
| Post standing on a table box | grounded by the table's top; the piece lands on the table or the floor, never through either |
| Door panel 0.04 x 1.0 x 0.45 | **not** cut (wider than the crater): a big round hole, one piece. The second shot goes through the hole and hits the wall behind; a shot near the edge removes more of the remains |
| Second shot at the piece lying on the floor, aimed at one end of it | 17,656 voxels removed; what is left (one piece of 6,251 voxels) has a new mesh (the old one is freed), is awake and settles again on the floor; 19 chips. Aimed at its middle, nothing is left of it: its 23,907 voxels are accounted for as 22,879 removed + 300 splinters + 728 crumbs thrown up (the test adds them up) |
| Shot at the stump | removes more of it; the piece lying nearby is woken (its support may have changed) and settles where it was |
| A shot at the foot of the post | whole bottom layer gone: nothing is anchored, the big piece falls |
| An object smaller than the crater (a pebble) | fully destroyed, no mesh left open - and it is thrown up as chips (18), not just deleted |
| Slab 0.30 x 0.60 x 0.04 of an ordinary material, one shot through the middle (`g_cut_slab.png` is the same in wood) | the crater leaves the top joined to the foot only by a strip a few millimetres wide on each side. A connection under 20 mm (3 cm for wood) cannot hold: the strip breaks (330 voxels of splinters, thrown up as chips of their own, and the material's `breakSound` if it names one), and the top parts **whole** (16,520 voxels), falls and lies on the floor; the foot (18,462 voxels) stays; 74 chips. The message says "a connection under 20 mm could not hold and broke ... a piece parted" |
| The same slab, the same shot, in a material with `breakWidth = 0` (`g_slab_holds.png`) | nothing breaks: the top stays up on its strip, one piece, a hole in the middle. A brittle material (`breakWidth = 0.1`) breaks on a strip 5 cm wide that an ordinary material holds |
| A 1.2 m block of a material that needs a connection of 1 m | the analysis window would be 240 x 240 x 240 = 13,824,000 cells, over the limit of 8,000,000: the break is **not analysed** and the hit says so (it stays as the crater made it) |
| A door panel 0.8 x 2.0 x 0.04 shot once through the middle, along its thin axis | a hole one can look through: 2,463 of its 64,000 face cells (160 x 400) are open, integrity 0.962, **widest gap 0.205 m** - enough to see through (3 cm), not to climb through (40 cm). A second hole elsewhere opens more, never less; a shot through the hole carves what is behind it, not the door; a dent in a 0.4 m block (a crater that does not go through) opens nothing (`SlabOpenings`) |
| 30 posts cut in a row | at most 24 live chunks (oldest dropped and its mesh closed), no mesh leaked, `clear()` frees everything |
| An indestructible wall, the sky, a zero-length aim | nothing happens, no exception |

Supporting suites: `RegressionVoxelCrater` (26: the crater is a solid volume, deterministic, rough rim),
`RegressionVoxelConnectivity` (17: face-connected pieces), `RegressionVoxelMesh` (10), `RegressionRigidDebris`
(20: the fall - the piece rests within a few millimetres of the floor, then sleeps), `RegressionCraterOOM` (16:
cost under `-Xmx512m`), `RegressionVoxelDebris` (44: what stays, what falls, what is crumbs, the count of voxels),
`RegressionVoxelFracture` (59: the break), `RegressionParticles` (148: the chips), `RegressionBreakProfile` (76: the
material's ten keys), `RegressionSlabOpenings` (39: how much of a door is open).

**See it:** `docs/renders/g_cut_post.png` (the post is cut, the chips fly, the top falls and settles), `g_cut_plank.png`,
`g_hole_door.png` (a round hole, still one piece), `g_piece_over_crate.png` (the piece lands on a crate),
`g_hit_the_piece.png` (the fallen piece shot a second time), `g_cut_slab.png` (a wooden slab: the strip the crater leaves
cannot hold, it shatters and the whole top comes away), `g_slab_holds.png` (the same slab in a material that never breaks that
way: the top stays up). Eight frames each, the chips drawn one by one with the matrix the renderer hands to its cube; they are
produced by `java VoxelFallDemo OUT_DIR post|plank|door|cube|crate|post2|slab|rubber [wide|close]` (compile
`tools/regression/*.java` as `run.sh` does) - the real `DestructionWorld`, a tiny software rasteriser instead of OpenGL.

**What is not covered:** how it looks and runs in the real viewer (texture colours on the cut faces, frame time
with 24 chunks, the unconfirmed textured-primitive crash) - section 5.

### 3.3 A `mesh` instance can be shot too

Shooting a mesh had never been tried. A probe that fired at a cube mesh and measured what came out found it broken
four ways, each pinned by a test that fails without the fix (`RegressionMeshVoxel`, 130 checks since; `mutants_mesh_voxel.py`, 84 mutants):

| What was wrong | What the test pins now |
|---|---|
| The inside of a mesh is found by casting a ray along +X from each region and counting crossings; a ray that runs exactly along a cube side's diagonal passes through two triangles and counts "one and a half" crossings | a closed unit cube mesh fills **all** of its 100^3 voxels (it lost 21 %); a tetrahedron and an octahedron (rays through vertices and edges) come out at a sixth of their box, a concave L of three cubes at exactly three cubes |
| The grid origin forgot the instance **scale** | the same mesh at scale 2 fills 8 times the voxels and stands at -1..1, not shifted by half its size; scaled differently on every axis it is 200 x 100 x 50 voxels; off-centre and scaled, it sits at 4..6 |
| A flat or open mesh (a quad, a decal, a triangle) was rasterised into a block over a metre thick | first refused (2026-10-08); then, because a refusal left `let_triangle` standing and impossible to shoot (2026-10-18), voxelized as what it is, a surface: `MeshVoxelizer.voxelizeThin` fills every voxel within half a wall of a triangle, the wall being 3 voxels (15 mm) grown to both sides - a single triangle comes out within 3 % of the volume of its parallel body. A mesh with no readable triangle, or whose triangle is a line, has no voxel: the hit destroys it outright (section 1, item 19) |
| The shot's broad phase tested a mesh against the canonical unit box, not its own vertices | `DestructionWorld.findFresh` (new, pure; the renderer delegates to it) uses `PbdMeshData.bounds()`: a ray at the mesh's real position hits it with the hit point in the mesh's own units; a ray where the canonical box would be, but no mesh, hits nothing |

End to end, a mesh post 0.1 x 1.0 x 0.1 shot at mid-height behaves exactly like the primitive post of section 3.2
(21,859 voxels removed, 34,234 kept, **one piece of 23,907 falls and comes to rest beside the stump**), and so do a
scaled and shifted slab and a mesh turned about Y (the remains stand where the mesh stood, at its scaled size).
Closed means `MeshVoxelizer.isClosed`: the vertices that coincide are welded (within 1e-5 of the longest side) and every edge must be
shared by an even number of triangles. A mesh that is *almost* closed (a missing triangle, a duplicated face) is therefore open and
becomes a wall like any other open mesh, instead of streaking along the rays that cross its flaw as it did. What is not covered: how
the remains of a mesh instance look in the real viewer (the original is hidden by zeroing its transform and the remains are drawn by
`ClassicMeshRenderer`) - section 5, item 11.

## 4. Known limits of the destruction world

Referenced from `DestructionWorld.java`, `VoxelDebris.java` and `VoxelCrater.java`. None is a bug being
chased; each is a decision, with the case where it will look wrong.

- **"What stays attached" is a heuristic.** The format has no support/hinge data. If the object stood on the
  floor (its lowest point within 5 cm of it) every piece still touching its lowest layer (within 2 voxels) stays
  - the ground holds it up; pieces no longer touching it fall; if the whole bottom was blown away, everything
  falls. Otherwise (a hanging door, a wall shelf, a frame) the **largest** piece stays and the rest fall - so a
  door shot through the middle sheds its smaller half, not "whichever half the hinges hold".
- **Pieces do not collide with each other** (each falls independently onto the floor, the remains and nearby
  boxes), so two pieces can overlap. **Chips collide with the floor, the boxes and the remains, not with the pieces and not
  with each other.**
- **A piece does not carry the motion of an animated instance**: a piece broken off a swinging door starts at
  rest. (The remains follow the door's live transform.)
- **Landing surfaces** are the scene's floor, the remains, and the boxes of nearby `cube` and `cylinder`
  instances. `sphere`, `cone`, `torus` and `mesh` instances are not landing surfaces - a piece falls through them.
- **Crumbs are thrown up, not simulated**: a piece smaller than 400 voxels (about a 3.7 cm cube) is not kept as a piece, its
  volume goes into the chips of the hit; at most 6 pieces per hit (the biggest); at most 24 live chunks (the oldest is
  dropped and its mesh freed).
- **Chips** are point masses with a cube drawn on them. At most 1500 are alive (a new one replaces the one nearest the end of
  its life). How many a hit throws follows the volume it removed - between 15 % and 150 % of the material's `debrisCount`
  (a post shot through: about 20 of 120) - so a hit on a thin object throws few whatever the material, and `debrisCount` (0 to
  400) is what to raise for more. A chip is one flat colour, that of the surface where it broke off, taken per chip. Drawing
  them (one draw of a shared cube each) is reviewed, not run (section 5).
- **The break (the cassure) is a rule about width, not a model of load.** A connection narrower than `breakWidth` between two
  thick parts does not hold, whatever it carries and whatever else holds those parts; a thin run that touches only one thick part
  (the ragged rim of a crater) or that was already thin before the hit (a cone's tip) is left alone, and the cube that
  measures "thick" is never wider than the object on that axis, so a 2 cm plank is not broken for being thinner than 3 cm.
  Analysing a break needs a window around the crater: over 8,000,000 cells (a material that asks for a 1 m connection in a block
  of that size) it is skipped and the hit says so.
- **Hiding places.** The doors of a place are the instances named by its `containerTrigger`s - only those are measured, not the
  walls of the box. A door is the listed instance together with everything that hangs from it. An instance that cannot move (no
  keyframes, no lever arm) is not a door; a closed door that is locked still keeps the place shut (it is the player who must
  get in, and the door is not a way); a door that a hit has reached at all is open - there is no threshold on the size of the damage
  (the integrity and the widest gap are measured, as a slab looked through along its thinnest axis, and reported, but decide nothing).
- **Lights.** A light that shares its `linkGroup` with a driver (keyframes or a lever arm, not a loop) is **on while any driver is
  open**; its own `lightEnabled` is ignored then; there is no inverted polarity yet (a lamp that goes off when the door opens).
- **Crater size**: one hit severs up to about 0.30 across; wider things get a hole. Measured by
  `RegressionCraterOOM` inside a 512 MB heap: at radius 30, carving takes 25-60 ms and meshing about 50 ms per hit
  (100-150 thousand vertices); doubling the radius to 60 costs about three times as much (173 + 132 ms) - faster
  than linear, far below the cube of the radius, because only the crater's boundary is refined.
- **Meshes** are voxelized from their triangles (section 3.3): a **closed** mesh gets a solid body by exact surface tests and a
  ray-parity inside test; an open one (a sheet, a single triangle, a box with a face missing) a wall of 3 voxels grown to both sides.
  A mesh with no readable triangle, or only lines, cannot be voxelized and is destroyed outright by the hit. Imported "soup" meshes whose
  faces each carry their own corners are welded for the closedness test within 1e-5 of the longest side; a seam wider than that makes the
  mesh open, and a wall of it is what you get.
- **Modifiers**: `taper`, `bend` and `shear` are applied when voxelizing, as the shader applies them and in the order the file lists
  them; `twist` and `curve` do nothing on the GPU and so nothing here. The ray that picks the target of a G hit still tests the
  unmodified box (backlog item 12): an instance bent far out of its box can be "hit" in empty air of the box, or missed where it bulges out
  of it.
- **Hollow shapes** (`cap`, `faces`) have a wall of 3 voxels (15 mm at 5 mm) whatever the drawn wall is - the drawn surface has no
  thickness - and the wall never takes more than 40 % of the extent it grows across, so a tube narrower than about 7 voxels (3.5 cm) comes
  out nearly solid: chosen, not measured to be right. A plane or a disc is a slab of whole layers (8 % of its larger side, at most
  10 cm, at least 2 voxels).
- **A voxel covered exactly half is filled** (rounded half up, as the analytic path does), so a wall whose faces fall exactly in the
  middle of voxels comes out one voxel thicker (4 instead of 3).
- **Sizes**: a shape that needs more than 4,000,000 lattice cells, or more than 1000 voxels on a side, is voxelized whole with larger
  voxels (the hit says so) - an object of 20 m is shot with bigger cubes than a post. The worst case costs about two seconds, a sphere
  on a grid of 1000 cubed six or seven, on the 2-core workspace that measured it. A cell of the lattice longer than four voxels (a fold of
  the surface under a violent modifier) goes whole into the voxel of its middle: an approximation, not a measured one.
- **Colour** is the shader's mapping run backwards (item 21) and is read at each corner of a voxel face, then interpolated across the
  face by the GPU: a large flat face shows a smooth gradient of the picture rather than crisp texels, and the wall of a crater, which the
  shader never draws, takes the colour of the skin nearest to it.
- **No caching** between hits beyond the live state itself (the voxel grid is rebuilt on the first hit of an
  instance), so assets loaded or replaced on the fly are always seen as they are now.

## 5. Before recording: what only a GPU can confirm

Everything below needs the real viewer (`./gradlew run --args="path/to/scene.pbd"`). None of it can be checked
from here; each item says what correct looks like. Do it once, on a scene with a door, a post and a textured
wall, before video 1 (items 1-5, 8, 13, 15) and again before video 2 (all).

1. **G on a post / plank** (untextured): the hole is visible, chips fly out of it (a few dozen small cubes that fall, bounce
   and shrink away), the top piece falls, tumbles and lies on the floor; nothing flickers; the sound plays (needs
   `doomshotgun.wav`). Compare with `docs/renders/g_cut_post.png`.
2. **G on a textured primitive**: no crash (the long-standing `0xC0000374`). If it crashes, send the console
   output of that press - it has the position/direction lines and the texture name.
3. **G twice in the same place / on the fallen piece**: a second shot goes through the first hole; the lying
   piece shrinks. No stale piece left hanging in the air.
4. **Frame time while 6-24 pieces are alive**: stays smooth (the overlay shows FRAME ms).
5. **E on a container item**: the pickup sound plays (`resources/sounds/item-equip.mp3` - the first time that file goes
   through the decoder; if it is silent the console says why); the item leaves the container; E on nothing is silent.
6. **Drag a lever-arm door** with the left mouse button: the door follows the cursor smoothly, is not
   "sticky", snaps to the nearer end on release (or stays where it was left if `release = free`); a locked arm
   says "locked" in the console and does not move; the open/close sounds play once each way; the contents of a
   container appear when the door is open and vanish when it is closed; a lamp on the door moves its light.
7. **Left click on a keyframed door** (while any are left): the old scripted toggle still works.
8. **N / B** (switch scene) while a drag is in progress or after a destruction: no exception, nothing stale.
9. **Hot loading**: replace a `.pbd`/`.pbdmat` on disk while the viewer runs and trigger G on it - the new
   asset is the one that breaks.
10. **Upload** a `.pbdasset` with the add-on to the real server (once the server side is looked at, section 6).
11. **G on a `mesh` instance** (a closed model - the add-on's *Convert to PBD Mesh*): the original disappears, the
    remains appear exactly where it stood (scale and rotation included), the piece falls and rests, and a second
    shot goes through the hole.
12. **A Blender scene with spaces in the object names**, exported with the new add-on, opens in the viewer and
    the parts hang where they should.
13. **E on a container item, then close the door and open it again**: the item is still gone, the others are where
    they were left, nothing flickers, the pickup sound played once, and a second container opened at the same time is
    not affected.
14. **The add-on's panels in a Blender window** (not the viewer): press N, open the PBD tab. The first line reads
    `PBD Tools v0.47.0`; a selected object shows the box *Lever Arm* with **Door**, **Drawer**, **Lever** (and **X** once it
    has one) and, ticked, open position / rotation, speed, release, lock, lock at, both sounds; the box *Light that follows
    this object* with **Add Light Linked to This**; a Reference shows Test Reference; the Materials panel lists the `.pbdmat`
    of its folder and, under *Custom break*, the ten break settings; *Create* offers **Door (hinge)**, **Drawer** and **Light
    Switch + Lamp**; every icon is drawn. The checks run the panel code on a recording layout and ask real Blender whether the
    icons exist and press every button - nothing has looked at the result.
15. **A connection too thin breaks** (the cassure): shoot a wooden plank or slab through the middle so that the crater leaves the
    top held by a thin strip (`g_cut_slab.png`): the strip shatters into chips and the **whole top** comes away and falls;
    the same shot on a material with `breakWidth = 0` leaves the top standing (`g_slab_holds.png`). If the material names a
    `breakSound`, it plays once for the hit.
16. **H on a hiding place with the door closed**: refused, the console says "The door of 'X' is closed - open it before getting
    in" ; drag the door open with the left mouse button and press H again: in you go; inside, close the door by dragging it
    (the mouse still looks and grabs while you hide), then H says "... before getting out". Shoot the door through (a hole
    wider than 40 cm) and H works again. From a mod, `Main.isHideoutConcealing(id)` is true only with every door fully closed
    and no hole of 3 cm, and `isPlayerConcealed()` follows once the move in is complete.
17. **A lamp linked to a door**: it is off with the door shut and on while the door is open or dragged open, switching once
    each way, not flickering when the door is held at the edge; two doors sharing a link group keep it on while either is open.
18. **Chips on a textured wall**: the chips have the texture's colours (a flat colour each), not the primitive's base colour.
19. **G on the shade of a lamp** (`desklamp.pbd`, a cylinder with `cap=none`): the crater is in its wall and it is still a tube afterwards
    (look through it); the same on a crate with its lid removed (`faces`) and on a bent pole; shoot the hollow wall twice - it stays one
    wall, no filled core appears.
20. **G on `let_triangle`** (and on a flat quad): it breaks into a thin wall of cubes that falls and rests; the console no longer says
    "can't be voxelized". A mesh whose triangle is a line vanishes with a few chips ("destroyed outright").
21. **E on a lamp, then on a wall plate**: the lights of the model go off, then on, one click each time (`switchSound` of the light if
    it names a file, else `light-switch.wav`); E on an item of an open container still takes the item, with the pickup sound; with a
    lamp in front of the item the lamp wins, behind it the item does. Check that the click is not unpleasant: nobody has heard it.
22. **A textured object shot with G**: the remains show the picture the way round it had before the shot, on every face - the handle of
    a locker stays on the left. Then give the material `uvScale = -1` and look again, before and after a shot.
23. **H in a container whose door has been shot** (a dent is enough): H works from outside, and inside `isPlayerConcealed()` is false.
24. **In Blender**: a textured primitive shows its picture as the engine does, on each face; **Mirror U** flips it (and the engine shows
    it flipped after an export); the light box has a **Click Sound** row.
25. **A scene file, on your models**: put them in one folder and open a `.pbdscene` that places them (the spec's `showroom.pbdscene` with
    your own file names, or one `folder` line with `layout=grid`): every model is on screen, standing where the file says, the front of a
    model on a `ring` toward the middle. Add a `show` with a camera path and a `quit`: the camera starts and ends at rest, the take is the
    same twice, the window closes by itself and the report is written next to the file; `PbdConvertMain check` on the same file gives
    the same instance count as the report.
26. **A stress run**: the forest of the spec (2,500 trees, `vsync=off`), then ten thousand (`count=(100,1,100)`). Read the report - the
    draw calls per frame, the 1 % low, the freezes - and keep it: where the frames pass 33 ms is where that machine stops, and it is the
    number backlog item 32 waits for.
27. **Take a show over**: press W (or A, S, D, Space, Left Shift) during a show: the camera is yours from that frame on, the sun and the
    doors stop moving by script, the window stays open, and the report is still written when you close it.
28. **The window opens** on the machine that stopped on `Could not create the GLFW window` (item 31 of section 1): the viewer starts, with at
    most one line `[GL] The request '...' was refused (...); the window is made with '...' instead.` in the console. If it still stops, the
    message lists every request it made with GLFW's own answer, what a window with no requirement gets, and the cures that fit the system -
    send that text, it is the next piece of evidence; `LIBGL_ALWAYS_SOFTWARE=1 ./gradlew run` tells a driver problem from a program one.
29. **A texture turned**: in Blender, on an object with a picture, **UV Rotation** 90 and the **Rotate Left / Rotate Right** buttons turn the picture in
    the material preview (the top edge goes to the left for 90), a normal-mapped surface keeps its lighting; export, open in the viewer: the same
    picture, the same way round, and after a G shot the remains still show it. Then 90 with a mirror (**Mirror U**) and a scale of 3 on one axis:
    three repeats along the surface's own u, as in Blender.

## 6. Open backlog

Ordered by what blocks the two videos first. "Blocked" says what is missing, not who is slow.

| # | Item | Why it matters | Blocked / size |
|---|---|---|---|
| 1 | **The GPU smoke test** (section 5) | every claim about *how it looks* rests on it | needs a machine with a GPU - 20 minutes |
| 2 | **Sound files**: `doomshotgun.wav`, `item-equip.mp3`, `light-switch.wav` (+ door sounds, and the files a material's `breakSound` names) in `src/main/resources/sounds/` | video 1 asks for the pickup sound; `SoundPlayer` plays `.wav` and `.mp3` (own decoder, section 1 item 17); E clicks | the files are not in the upload: the real `item-equip.mp3` has never been through the decoder; `light-switch.wav` (written by `tools/scripts/make_light_switch_wav.py`, delivered with the code) decodes but nobody has heard it |
| 3 | **Run the migration tool over the asset library** (`PbdConvertMain lever <folder> <out>`) | keyframe doors must go before video 2 | the library is not in the upload - 1 command, then read its report |
| 4 | **Server**: the site's code is in hand and tested (`TEST_PROCEDURE.md`, section H); moderation tolerates `leverArm`, `switchSound` and every key of the format; `tutorial.php` has its sections on the lever arm, the light switch, the upload rules and "re-use existing resources with `include_material`" (written 2026-10-20 from the add-on guide) | video 2 opens the site; the tutorial must push people to upload assets with includes | the wording is **reviewed** only; the real host and library are unknown |
| 5 | Textured-primitive G crash (`0xC0000374`) | would kill a recording | needs a real run; send the console output |
| 6 | "Rays pass through voxels" | reported once, never reproduced; the one real bug found (fallback to the camera position) is fixed | needs a console log of the exact press |
| 7 | ~~Container refill on reopen~~ - **the half about re-rolling is done 2026-10-08** (`ContainerStock`, section 1 item 10). **Still open: a better distribution of items across linked containers** (the old request also asked for it) | the re-roll was visible on camera | the rules are verified, the wiring in `Main` is reviewed (section 5, item 13); the distribution is a design question - by volume share, at random per box? Nothing is saved to disk: a restart of the viewer refills the containers |
| 8 | **H-key exit**: leave a hiding spot with the *inverse* of how the player got in | polish | needs reading `startHiding`/`startExitingHide`; GL-bound |
| 9 | **Real physics behind the drag** (inertia, collisions) - the drag is geometric today | you chose the prototype first | which library, real constraints - a design decision |
| 10 | **Rattle sound for a locked arm** (when you pull a locked door) | small polish | one new format field across every format (the round-trip test's completeness guard will insist it is carried everywhere) |
| 11 | ~~**Door-closed accessor by container name** for AI code~~ | **Done 2026-10-12**: `Main.isHideoutConcealing(id)` (every door fully closed, none shot through), `getHideoutState(id)` (door count, closed, integrity, widest hole, shut, concealing, the reason in words), `isPlayerConcealed()`; and **H** is refused through a shut door (section 1 item 15) | closed |
| 12 | **The G *targeting* ray** still tests the unmodified box, so a bent or sheared instance can be "hit" on empty space of its box, or missed where it bulges out of it (the modifiers themselves are applied by the voxelizer: section 1 item 18) | a bent object is aimed at as its straight self | the box test would have to use `ShapeEval`'s bounds; and a **question for you**: two bend entries exist in the journal - the older "Bend modifier - proper fix" asks for Blender's own technique (loop cut, then Simple Deform) for how a bent shape is drawn; the newer one solved how to *voxelize* a bent shape. They are different problems - does the on-screen bend still need the Blender-style rework? |
| 13 | **LOD tiers**: the Java side takes any `vertexDataLod<N>` as an ordering key; the report that 0/1 "don't work" points at the add-on's LOD UI/export, not yet read | tiers set in Blender | read `properties.py`/`export_pbd.py` LOD code |
| 14 | ~~`description =` has no field in the add-on's File box~~ | **Done 2026-10-08**: a Description field in the File metadata box (export, import, File-box scenario in the add-on check) | closed |
| 15 | Add-on: export a `.pbdmat` on its own; show a remote `.pbdmat` in the Material panel | convenience | small each |
| 16 | `.pbdasset` of ~29 MB (bloat) | upload size | verify against a real asset |
| 17 | JOML `Vector3fc` classpath error from an older build | build | reported fixed through the `printRuntimeClasspath` task; cannot be re-checked here - `build.gradle.kts` is not in the upload |
| 18 | The grill/BBQ scene with an oddly thin, mis-oriented panel (two screenshots, no file) | a visible modelling bug in a reported scene | never diagnosed. **Possible lead, unconfirmed**: the child-of-a-primitive export bug fixed on 2026-10-07 (journal) - re-export that scene with the fixed add-on and look again |
| 19 | The tutorial's domain-specific ("metier") requirements | raised once, never specified | needs your list of what is missing |
| 20 | **A viewer in the browser** + a rewrite of the site's index | you want to look at a PBD without installing anything, without rewriting the engine for the web. Candidates: (a) a page of plain JavaScript that reads the `.pbd` (text; `.pbdbin` through `DecompressionStream`) from `/db/` and draws it with WebGL, with a port of `ShapeEval` checked against the same numbers as the Java one - real, small, but it shows the primitives, materials and lights, not the destruction or the mechanisms at first; (b) a glTF export from the add-on shown with `<model-viewer>` - rejected: a second kind of upload to accept and inspect, and a picture that is no longer the engine's; (c) rendering on the server - not possible on PHP hosting; (d) the Java engine compiled to WebAssembly - not simple; (e) a picture, or a short turn-table film, of the model rendered by the real engine on the machine of whoever accepts the upload (the show can already orbit a model; saving the frames is not in the viewer yet) and shown by the page - exact, but not interactive, and it needs that step at every acceptance. Recommendation: (a), and (e) if a gallery of pictures is wanted | planned for the next delivery |
| 21 | `Main.availableVolume(name)` (hence `canHide`) subtracts the volume of **every item of every open container** from the asked container's volume, not only the items in that box; and it takes the instance's `scale`, not its world size | `canHide` can answer "no room" because another container happens to be open | found by reading on 2026-10-08, no suite calls it. Fix: keep only the items whose box is the asked instance (`owner.metaIndices.get(item.containerIndex)`), through a pure helper that a test can feed - small |
| 22 | **Two remote caches, not one.** The engine caches fetched URLs under `src/main/resources/cache/remote` (`PbdPaths.REMOTE_CACHE_DIR`); the add-on under `pbd_cache/remote` next to the `.blend` (the system temp folder when it was never saved). Code comments in the add-on said they were one - the user guide and the format spec now say they are two | a file fetched in Blender is fetched again by the viewer | decide: unify (the add-on would need to know the project folder, which it no longer asks for) or keep two. Small either way |
| 23 | **Blender versions**: `blender_real_check.py` was run on 4.2.23 LTS, 4.5.15 LTS, 5.0.1, 5.1.2 and 5.2.3 LTS (878 checks pass on each; the sweep of 2026-11-14), which covers the three animation APIs: no action slots (4.2: the legacy path of `anim_compat.py`, the one 4.0 has), slots with the deprecated `action.fcurves` still there (4.5), slots only (5.0 and later). **Not run: yours, 4.0.2** (`bpy` modules on PyPI start at 4.2; Blender's download server is not reachable from the workspace), 4.1, 4.3, 4.4. For 4.0, two one-off checks against its API stubs (`fake-bpy-module-4.0`, not part of `run.sh`): the 29 icons the add-on names are all in the 4.0 icon list, and a type check of the add-on against the stubs finds nothing missing from 4.0 apart from what the add-on registers itself and the 4.4+ call `anim_utils.action_get_channelbag_for_slot`, which `anim_compat.py` reaches only on an object with an action slot (the stubs cannot see through untyped `obj` / `context` parameters: a partial net, not a run) | an upgrade or your 4.0.2 could break something no run here has seen | run `blender_real_check.py` on a 4.0.2 binary, or try the add-on by hand once on it and send the console output if anything is red |
| 24 | **Light polarity**: a light linked to a door is on while the door is open; a lamp that must be on while it is shut (and go out when the door opens) cannot be said | a closet light, a fridge | one new format field across every format - the round trip's completeness guard will insist it is carried everywhere |
| 26 | **The first G press stalls for about 0.2 s** (cold JVM: class loading and an unwarmed JIT, 160-213 ms against 23-40 ms from the second hit on) | a visible hitch on the very first shot of a recording | a warm-up at scene load - a throw-away hit on a tiny object, in a pure helper next to `DestructionWorld`, never in `Main` - would take it away; to be checked on the GPU machine first (the viewer has run frames before the first shot, which may already have warmed some of it) |
| 25 | **Chips and pieces do not collide** with each other (chips only meet the floor, the boxes and the remains); chips keep one flat colour | a chip can fly through a piece lying on the floor | needs a decision on the cost: chips against the voxels of every live piece, each frame |
| 27 | ~~**A scene description format**~~ | **Done 2026-10-25** (section 1 item 26; the spec's "What a scene file does not do (yet)" lists what it leaves out). Still open, small each: a scene that places another scene; a model fetched from an address; captions or titles; a sound track; a lever arm opened only part of the way; control over the easing of the camera beyond rest at the first and last key; writing the video itself (record the window with the tool you like - the take is repeatable) | closed, apart from those |
| 28 | **The site's lint was never compared with the engine's parser on the same files.** `pbd_lint.php` reads a `.pbd` / `.pbdmat` with the engine's lexer rules written again in PHP; `server_lint_check.php` and `server_lint_policy_check.php` hold expectations written by hand from those rules | a file the engine reads and the site refuses is a model that cannot be shared; a file the site lets through that the engine reads differently is the dangerous way round | a small Java program (`PbdLintOracle`) that prints what `PbdParser` and `PbdMatParser` make of a file, and a script (`lint_differential.py`) that gives thousands of generated and damaged files to both and lists every disagreement |
| 29 | **The site's maintenance scripts have no test**: `migrate_domain.php` (rewrites the references a library holds to another host; backs up what it changes), `check_library.php` (is the library closed?) and `repair_texture_refs.php` | they are run once, by hand, on the real library - the one place where a mistake cannot be tried again | each on a made-up library in a temporary folder (the upload suites already build one), and a mutant for every rule |
| 30 | **Suites that no mutation spec runs yet**: `RegressionIncludeLimits` (what a file may ask of the engine: depth, instances, size - the security ones), `RegressionVoxelMesh`, `RegressionVoxelConnectivity`, `RegressionVoxelCrater`, `RegressionPrimitives`, `RegressionPerVertexColor`, `RegressionPbdRefCopy`, `RegressionMinifiedMetadataQuoting`, `RegressionRigidDebris`, `RegressionCraterOOM`; and, on the site, `pbd_lint.php` (two suites) | a check that has never been seen to fail may not be able to | `mutants_include_limits.py` and `mutants_server_lint.py` first. (`RegressionVoxelLimits` is the last guard of `mutants_voxel_shapes.py`, which mutates the caps it checks - the number of cells, the width of the grid - but has no spec of its own for the extremes it is about.) |
| 31 | **The upload button does not read the text files it sends.** `upload_rules.py` checks the files (how many, names, endings, sizes, one `.pbd`, at most one `.pbdmat`) and that Name, Kind and Author are filled in; it does not check what a file *refers to* or the lengths and characters of the metadata. A material set to *Reference materials/ folder* (it writes `include_material materials/NAME.pbdmat`), a texture or a sound named by a path of the author's disk and an include whose address is not the library's all go to the site and come back refused, with the reference named. Found on 2026-10-20 while the add-on guide was corrected (it had claimed more than the add-on does) | the whole submission is sent for nothing, and the refusal uses one of the 10 uploads an hour (it is not a strike: only a forbidden ending, a "picture" that is not one or a wrong user agent count towards a ban) | the add-on would read its text files with the site's rules for references and the metadata limits and say which reference to change before sending; a test for each rule in `addon_safety_check.py`, and a mutant for each |
| 32 | **One draw call per instance, every frame, with no culling.** `PbdRenderer.renderCached` draws every instance that is not a group or metadata with a call of its own, whether it is in view or not (the stress report's `draw calls per frame` is that number) | a scene of tens of thousands of models will be slow before it is wrong, and a stress test measures exactly this first | needs the report of a stress run on a real card (section 5, item 26) before choosing: culling by the view frustum is cheap and local to the loop; drawing identical models with one instanced call is a larger change that touches the shaders |
| 33 | **The texture array does not look at what OpenGL allows.** `MaterialTextureArray` holds one layer per distinct texture, all of the size of the first texture (the others are resized, bilinear), keeps a copy of every texture's pixels on the Java heap (the voxel colouring reads them), and never asks for `GL_MAX_ARRAY_TEXTURE_LAYERS` or for the video memory it will take | a showroom of every model you made may hold hundreds of distinct textures, large ones: past the layer limit the load would fail somewhere inside OpenGL, and past the memory the card swaps or the driver refuses | no test can see it (the runtime check uses a handful of small textures or none); first step: ask OpenGL for the limit and say, by name, which texture did not fit; a second: size the layers to the largest texture, or group by size |
| 34 | **The window on a machine that refuses the first request** (item 31 of section 1): the plan was written from a log, in a sandbox with no display and no GPU | the viewer must open on the machine that reported the failure | the first run there: it opens, or it prints the list of what was tried (section 5, item 28) |
| 35 | **A texture turned by a free angle** (`uvRotation` takes multiples of 90 only) | a decal, a board laid at 30 degrees | the shader would turn the coordinates of every pixel of every frame, for all four maps and again in the voxels' colouring: a design decision, not a small change |

Closed since the last revision of this list (the journal entries below were left as written; status notes were
added to them): MP3 decoding and the pickup sound (section 1 item 17), the door-closed accessor by container name (item 11),
availableVolume / canHide (`Main`), **minified `.pbd` not loading** (fixed on both producers),
crater OOM, texture double decode, non-cube primitives, NaN ray distance, draw-time write in `ui_panel.py`
(moved into an operator), `upload_server.py` imports, the add-on's `description` field (item 14), a file refused because an object name held a space,
an import stopped by one unreachable reference, stale matrices read by a script, a sidebar title that ignored the version. Re-read against the current code on 2026-10-08.

**Not worth doing now, and why**: moving individual cube *edges* (needs the tessellation shader generalised).

---

## 7. Journal (newest first)
### 2026-11-14 - a `.pbdbin` button that still said `PbdConvertMain failed` because an old add-on was the one running, and what the site keeps of a picture

Three questions in one message, with the screenshot of the same Blender error as on 2026-11-10: (1) "why three zips for tools? do not scatter"; (2)
"the PBDBIN still does not export; the UV rotation seems to work"; (3) "does the server remove the EXIF and other metadata of the textures, for
obvious reasons of security?" Section 1, items 35 and 36, say what exists; this entry says what was decided, what the checking found and what is not
known.

**Decisions** (each could be the other way; say so if you want it changed):

- *The cause was looked for in what was running, not in the export code.* The message is not in any add-on from v0.48.0 on, and the export of that
  code was already tested (fake `bpy` and real Blender 4.2 to 5.2). What can print it is code of v0.47 or older, and Blender keeps an add-on's
  modules in memory (item 35). So the fix is in the package (`__init__.py` lets go of the old modules when it is loaded again), not in the
  button, and the procedure written in the README and the guide now starts with "quit Blender completely and start it again".
- *One add-on zip per delivery*, `pbd_tools_addon_v<version>.zip`, a plain copy of `tools/blender-addon/pbd_tools.zip`; the older ones are taken out
  of the folder. The archive of `tools/` still carries the add-on's sources and that same zip, because the test that compares the zip with the
  sources needs both in the tree.
- *The site was left as it is.* It already rewrites every picture and every sound (item 36); what changed is that this is now known from a run on
  files made by other tools, not only from the site's own suites.

**What the checking found:**

1. *My own advice was unreliable.* The first probe of a stale install read the code **from the disk** (`inspect.getsource`), which is already the new
   one, and so told nothing; read from memory (the panel's title, `hasattr` on a function that exists only in the new code, `bl_info`), the same
   install showed the old buttons under a title that named the new version. "Look at the first line of the panel" - which I had written in the guide, the README and the
   report - proved nothing for an add-on updated in place.
2. *A hole in my own tests.* The first version of the update test, and `scenario_zip` until today, enabled the add-on **from the sources**: the
   sources' folder is on `sys.path` while the test runs, so Blender's `addon_utils.enable("pbd_tools")` imported it instead of the copy it had
   installed. "The installed zip works" had been looking at the wrong files; the sources' folder is now taken off the path and the module's
   `__file__` must be in the folder Blender installed into.
3. *Python's import system* needed both halves of the purge: `from . import ui_panel` does not read a file when the package already has a name
   `ui_panel`, so deleting the entries of `sys.modules` alone changes nothing - one mutant of the new spec proves it.
4. *The rounds*: `mutants_addon_update_real.py` (7) killed on real Blender 4.2; one mutant I wrote first was broken (it died of a `NameError`, which
   proves nothing) and was replaced by one that moves the purge after the imports; `mutants_addon_compiled_ops.py` (114, three added) killed.
5. *The site*: 18 files that carry every kind of metadata a camera, an editor or a hostile author puts in a picture or a sound, made by Pillow and
   ImageMagick and by hand, went through the real submission code and came out with their pixels and nothing else (item 36).

**What is not known**: that the old add-on is what ran on the machine that reported it (item 35: a screenshot does not name the code that printed
it); Blender 4.0.2, the reporter's; the Blender file browser and panels as they look on a screen; and what the real site's host does with a file
after the site's code has stored it (its backups, its logs).

### 2026-11-10 - a `.pbdbin` export that ran the wrong Java, and a texture rotation nobody could see

Two reports in one message, with a screenshot of Blender's error (`PbdConvertMain failed: ... UnsupportedClassVersionError: pbd/PbdConvertM...`):
(1) "I told you the UV of a texture must be *turnable*, not just mirrored - in the **add-on**"; (2) "you rushed the `.pbdbin` export, which is very
important". Section 1, items 33 and 34, say what exists; this entry says what was decided, what the checking found and what is not known.

**Decisions** (each could be the other way; say so if you want it changed):

- *The export runs no Java.* Two ways out of the error existed: keep the Java button and look harder for a Java that is new enough, or write the
  formats in Python. A `.pbdbin` is a gzip and a `.pbdasset` a zip, the engine's readers are small, and the Java route needed the project's Gradle
  build above the file and half a minute - so the add-on writes them (`compiled_export.py`) and the engine is made to read what it wrote. The one
  button that still needs Java, *Add to tileGeometry.txt* (it runs a class of the engine), looks for a Java that is new enough (`java_runtime.py`).
- *A `.pbdbin` keeps its materials beside it*, as a `.pbd` does, named after it (`crate.pbdbin`, `crate.pbdmat`, the pictures next to them); the
  `.pbdasset` is the self-contained one. *Inline References* is on by default, because the engine's own compiler inlines the models a `pbd_ref`
  names; off, the `pbd_ref` lines stay.
- *The file browser offers the `.blend`'s name* in the `.blend`'s folder with the extension of the button pressed - what Blender's own exporters do.
  A path that is nothing is refused (*Give the file a name*), never made into a default. *File > Export* lists the two formats next to PBD (.pbd),
  because that is where a Blender user looks for an exporter and the panel's two buttons were the only way in.
- *The rotation is one box, "Texture mapping"*, with *UV Rotation* first, the Mirror buttons after it, and the state in words - because a row among
  others, below the scale and under a pair of Mirror buttons, is what the report describes. The box also says what Blender hides: the **Solid** view
  draws the mesh's own UVs and ignores the Mapping node, so nothing - repeat, mirror or turn - shows there; *Material Preview* does.

**What the checking found:**

1. *A real defect of the first version of the button*, found by a mutant that survived: the guard against a file with no name could never fire,
   because `check()` puts the extension on whatever it is given, so `""` became `.pbdbin` - and a file called `.pbdbin` (hidden on Linux and macOS)
   was written into whatever folder Blender was running in. Eight spellings (empty, blanks, a folder, `//`, the extension alone, in either case)
   are now refused with nothing written anywhere, which the test checks by looking at the folder.
2. *A difference from the engine's reader*: the add-on's test for an address stripped white space before looking at `http://`, the engine's does
   not (a quoted `" https://..."` is a file name to it). Now the same, and a test pins it.
3. *Mistakes of my own tests*: a bundle's second material folder is `materials/2/...`, not `materials/extra.pbdmat` (the expectation was wrong, the
   code was right); a spec key written without its function's name; a missing `import io`.
4. *Fifteen mutants of older specs applied to nothing* - the code had moved (`_quote_if_needed` became `quote_word`; the Mirror and Rotate buttons
   are drawn by `draw_texture_mapping`) - found by `check_mutant_specs.py` and re-anchored; one mutant added to `mutants_addon_uv.py` for the
   box's normal-map case survived only because that spec does not run the scenario that kills it (it is killed in `mutants_addon_mapping_ui.py`),
   so it was taken out again.
5. *The rounds*: the first rounds of `mutants_addon_compiled.py` (349) and `mutants_addon_compiled_ops.py` (111) left mutants alive; each survivor was
   a check that was missing or one of the two defects above, and the checks were written (126 checks became 151 in `addon_compiled_check.py`, and
   the add-on check gained its compiled-format scenarios). The last rounds - on the code as delivered - killed every one; so did
   `mutants_addon_mapping_ui.py` (90) and `mutants_addon_rotation.py` (72). `TEST_PROCEDURE.md`, section E, says which older specs were run to the
   end on the present code, and which were not.

**What is not known**: Blender's own windows - the file browser and the panel were never drawn in front of anybody here (the operators are pressed,
the layout is recorded); which add-on the report was made with (if the first line of the panel does not say `PBD Tools v0.48.1`, Blender is
running an older one, and an add-on already loaded needs a **restart** of Blender after it is replaced); and, for the Java button, names with
non-Latin characters (a command line's encoding depends on the locale) and the Windows search (made-up `C:\` folders only).

*Status, 2026-11-14: the panel's first line is not that proof (it is read from the new `bl_info` over the old modules) - the next report showed the same
error from an add-on that had been updated in place; see item 35 and the entry above.*

### 2026-11-09 - a viewer that stopped on its window, a texture that turns by quarter turns, and a domain name that was said to be unchanged

Three reports in one message: (1) "nothing was changed in the domain name, in the add-on or in the server"; (2) "textures must be *mapped*
properly in Blender, which includes turning the UV of the material, usually by steps of 90 degrees"; (3) `./gradlew run` ended on
`GLFW_VERSION_UNAVAILABLE ... EGL: Failed to create context: Arguments are inconsistent` and `Could not create the GLFW window`. Section 1,
items 30 to 32, say what exists; this entry says what was decided where the request was silent, what the checking found, and what is not known.

**Decisions** (each could be the other way; say so if you want it changed):

- *Quarter turns only.* `uvRotation` takes multiples of 90 degrees (a thousandth of a degree is forgiven, for an angle that went through a float);
  any other value is ignored and reported, never thrown. A free angle would be done by the shader for every pixel of every frame, for the four maps
  and again for the colour of the voxels - backlog 35. The picture is turned **once, where it is loaded**, so every reader gets the same pixels.
- *The direction*: `uvRotation = 90` turns the picture counter-clockwise as seen on the surface (u to the right, v up), after the scale and the
  mirror - the order of Blender's Mapping node of type *Point*. The node turns the *coordinates*, the picture turns the other way, so the add-on
  writes Rotation Z = minus the angle. That this is what Blender shows is not argued: the check renders a plane in Cycles and compares it with the
  engine's picture, cell by cell.
- *The vectors stored in a normal map are not turned* with its picture (Blender does not either), so the lighting you see in Blender is the lighting
  the engine shows.
- *The window makes up to nine requests, in the order a machine is most likely to accept*, before it gives up: the request it always made, then the
  same without the forward-compatible flag, then a compatibility context; on Linux each with GLX and with EGL; on a Wayland window, the same on X11.
  A request that worked is asked first by the next window of the run. `PBD_GL_PLATFORM` and `PBD_GL_API` choose for you.
- *Nothing was changed on the site*: its lint reads any key of a material (checked by running it on a material with `uvRotation`), and the former
  host name stays out of every file of the project - it is not even written in the documents.

**What the checking found:**

1. *A mistake of mine in `run.sh`*: when the two new suites were added to its list, two pairs of names were written together
   (`RegressionSlabOpenings` + `RegressionDestructionWorld`, `RegressionSceneFormat` + `RegressionSceneDirector`), so four suites would not have run
   and the run would have ended in failure on two classes that do not exist. Found by reading the list before the full run, and compared with the
   list of the last delivery.
2. *The first round of mutants of each new piece left some alive, and each survivor was a check that was missing*: the engine's rotation 13 of
   99 (a hundredth of a degree taken for a quarter turn and a thousandth refused, infinity as a number of turns, a picture looked up without its
   turns, and the whole of the classic mesh path - a picture never turned, handed over with the wrong sides, uploaded from the end of its
   buffer, its turned copy never given back, the rotation reported before it was read), the add-on's 2 of 90 (a `uvRotation` that is not a
   multiple of 90 read as a quarter turn all the same; the Mapping node of the render fed with the plane's *Generated* coordinates instead of its
   UV - the same on a plain plane, so the check now renders a plane whose UV map is turned half a turn), the window's 5 of 277 (a property that
   must win over the environment for the API, the word `auto` with spaces round it, the line that sends WSL to the software renderer, the one that
   suggests `glxinfo -B`, the cures that ignore the session variables). They are checks now and the same mutants are killed; `TEST_PROCEDURE.md`,
   section E, says which rounds ran to the end.
3. *The documentation's own example is checked by the engine*: the `crate` material of the format spec (scale 2, V mirrored, turned 90) is read by
   `MaterialUv`, and a number, a key or a name changed in it fails `RegressionSpecExamples` (7 mutants in `mutants_docs.py`). Adding it raised the number
   of checked examples by one and two older mutants of that spec then survived, because the lower bound of the count had been exactly the old count;
   the bound is 24 now.
4. *The domain, again*: every source and every zip of the last delivery names `pbd.aptheo.fr` (the add-on from 0.46.0, `pbd-server.zip`, the engine,
   the docs); the former name is found only in the older zips (the add-on 0.43.1, 0.44.0 and 0.45.0, and `pbd-benchmark.zip`). An add-on installed from
   one of those, or a server folder copied from one of them, still says it - the panel's first line (`PBD Tools v0.47.0` now) and the server's
   `current_version.txt` tell which one is in use. A library that holds references to the former host is rewritten by `migrate_domain.php`.

**What is not known**: whether the window opens on the machine that reported the failure (the plan was written from the log, in a sandbox with no
display and no GPU: if every request fails there, the message it prints is the next piece of evidence); the drawing of a turned picture on a
GPU (what is checked is the picture the renderers are given, and the lines that give it to them); and the site through Apache and nginx (not
installed here: the run through PHP's own server passed).

### 2026-10-24 .. 2026-10-25 - a scene description file for the video and for stress tests, a stack that filled up at 500 models, a viewer that is run for real, and a test of the site that failed one run in thirty

The two requests of 2026-10-18 that the first delivery left open: (7) a way to look at a PBD in the browser without writing the engine
again for the web (**still open**: backlog 20, section 1 item 28), and (8) "a simple scene description format, so that I can include all
my PBD models, make the presentation video in one take, and also do stress tests". Section 1, items 26 and 27, say what exists. This
entry is what was decided where the request was silent, what the checking found, and what is and is not known.

**Decisions** (each is a policy that could be the other way; say so if you want it changed):

- *The scene file is a shorthand, not a new scene format.* It is expanded to an ordinary `.pbd` of `pbd_ref` blocks, so the engine, the
  add-on and the site read the result with nothing new and every limit of section 1 item 25 applies to it. What it adds is a second part,
  the *show*, which lives in the scene file and never in the `.pbd` (the field `PbdScene.show` is left out of every file format on
  purpose, and a test proves it).
- *Conventions where two tools could differ*: ids are counted from 1 (`locker_1` ..); the front of a model is its +Z side; a ring puts
  its first model on the +Z side of its centre and goes toward +X, the models facing the middle unless told otherwise; a grid counts x,
  then y, then z; a scatter draws four numbers for every placement, used or not, from a generator written out in the code (SplitMix64), so
  that the same file gives the same field on every machine and a larger `count` only adds trees.
- *A value can only be a value.* Every number, name and path is checked against a closed form before it is written into the PBD text, and
  a value cannot hold a quotation mark, so it cannot close its own quotation, open a brace, add a line or add a field. A scene file cannot
  place another scene file (no loop of scene files is possible), fetches nothing and runs nothing; the only thing it can write is its
  report, and the report only to a plain file name next to it, never over a file that is not a report.
- *Bounded wherever it is read*: a file of 4 MiB, 100,000 lines, 4,096 characters a line; a directive writes a million placements at most
  and a scene holds 5,000,000 at most (its own `limit=` defaults to the 200,000 of every `.pbd`); a folder lists 10,000 files, six levels
  deep; the text a scene expands to is capped below the parser's own limit. Each is checked at the line that crosses it.
- *The show's clock is the sum of the frame times, each capped at a quarter of a second as for everything else*: a slow machine plays the
  same show more slowly, never a different one. Events at one time happen in the order of the file. `open` and `close` *set* a state (a
  drag cannot be scripted, so a lever arm is opened to its end); `click` is for a keyframed door. A person's W, A, S, D, Space or Left
  Shift ends the show for good, and the report is still written.
- *The stress report is written next to the scene file and printed*: the renderer, the counts, the load times, the heap, and the frame
  statistics as percentiles of nearest rank (a frame that really happened, not an interpolation), the lows, the freezes. Frames inside the
  warm-up are left out. `vsync=off` is how to see what a scene can really do.
- *The memory is the machine's limit before it is the format's*: about 2.6 KB an instance, `check` measures it.

**What the checking found** (each pinned by a check that fails without the fix):

1. *A scene of 600 models died in the middle of its first frame* with `OutOfMemoryError: Out of stack space`, after every scene tried by hand
   had worked. The renderer took two 64-byte matrices from LWJGL's memory stack for every model it drew; that stack is 64 KB per thread
   and gives memory back only when the block that owns it ends - for the renderer, the frame - so it was full at about 512 models. No
   Java suite opens a window; nothing in the code looks wrong when read. The matrices are taken once, before the loop. A reading of every
   source (`check_gl_stack.py`) and a run of the real viewer under a virtual screen (`gl_runtime_check.py`) now keep it from coming back,
   each checked by mutants of its own (`TEST_PROCEDURE.md`, section I). The first scene of the runtime check had 800 models: the mutants
   that take *one* matrix per model (51 KB, which fits) did not make it fail, so the scene has 1,300 (2,601 instances).
2. *The mutation round on the scene format found what the checks could not see*: a directive that accepted any key; a tuple read without its
   parentheses; a file-name pattern whose `?` meant "anything"; the text of the `materials` lines left out of the cap on the text; an error
   message that was not cut; two folder entries that differ only by their case, ordered by the luck of the file system (the check now makes
   twelve such pairs, created in both orders); an orbit with an extra word; `z0 = z1` in a scatter's area; a ring with `columns`; a
   negative `every`; a close with no open. Each is a check now (the suite grew from 520 to 581); what cannot change any behaviour is listed,
   with the reason, at the head of the spec.
3. *A new field of the scene, `show`, was in no file format - and the completeness guard of the round trip said so.* The guard exists so
   that a field added next month cannot be silently dropped by a format; it failed on the first field that is meant to be dropped. The
   field is now left out *explicitly*: the guard does not skip it, it proves that pretty text, minified text and `.pbdbin` are the same
   bytes with a show and without, and that none gives one back. Two mutants write a show into a file and are killed.
4. *A test of the site failed one run in thirty, and the site was right.* The check "a hostile user name is never shown back" also
   looked for the letters `OR` in the page before the form - and every page carries a nonce, 22 random characters of base64, which
   holds `OR` by chance in about one page of 200. The check now looks for the name itself, as written or escaped, with the nonces taken out,
   and is checked on made-up pages. (About 3 % of the runs of one web server; about 9 % of a whole `run.sh`, which has three.)
5. *The run's own accounting*: the runtime check writes `OK:` lines, not `PASS:` lines, so its output is shown and kept beside the logs
   that are counted, not among them; the numbers of the docs come from the logs.
6. *The mutation round on the show's player found a defect in the report, and thirty-five holes in the checks.* The defect: the 99.9th
   percentile of the frame times - and so the "0.1 % low" of the stress report - was the *worst* frame whenever a run had a multiple of
   1,000 frames. The rank was `ceil(99.9 / 100 * n)`, and in floating point `99.9 / 100 * 1000` is 999.0000000000001, which a ceiling takes
   for 1,000 (20 of the 80,000 cases tried, all of them for 99.9). The rank is worked out in whole numbers now; a check runs 1,000 to
   20,000 frames and compares every percentile with its exact rank, and a mutant puts the floating-point rank back. The holes (42 of the 399
   mutants of the first full round lived; 35 are checks now, 7 are listed with their reasons at the head of the spec as unable to change any
   behaviour): the glide of the camera was checked in x and in the yaw only (not y, z, the pitch, nor the short way round the other way),
   and not with a frame time that is NaN, negative or infinite; the sun's channels were checked with two keys only (the stretch of a time
   was found from the first two), and the value at the last key not to the bit; events behind a `quit` in the same frame; the box that a
   model is looked at by (a light or a reference inside it, a part two levels below it, a part turned by 45 degrees, a panel with no thickness,
   a mesh with its own bounds, the last part of the scene, a parenting loop in a scene built by hand); the borders of a freeze (100 ms) and
   of a slow frame (1/30 s); a negative frame time that ate the frames after it; a negative warm-up; a camera path of three keys (scipy's
   values) and one of slopes so small that their product is lost; a direction of a ten-millionth of a unit, and one that is NaN; and
   three lines of `Main` that connect the show (the guard on a pose that is null, the hour the director starts from, the warm-up).
7. *A suite was quoted with a number that changed with the port of the run.* `RegressionFetcher` looked for addresses whose cache name holds a
   `-` and a `_` (the two letters of the URL-safe alphabet that the usual one does not have) and checked each name as it found it. The name
   is a hash of the address, and the address holds the port the test server was given this time: in about one run in four one name held both
   letters (228 checks), in the others two names did (229). The documents quoted 229, and the comparison of the documents with the logs of
   the final run was the first to say so. The suite now looks for one address of each kind first and makes exactly one check for each; nine
   runs in a row gave 229, and the seventeen mutants of the cache name are still killed. The counts of all the logs kept from earlier runs
   (fourteen runs, fifty-five logs) were compared: none but this one ever went down, except that of the end-to-end check of the site, whose
   check of a hostile user name was rewritten between two of those runs (item 4); its loop prints two lines per name, and it gave 417 in
   each of the last four runs.
8. *The mirror's spec, run again, let one mutant live.* "The shader normal divides by the scale" (a NaN for a sheet flattened on Y) was killed
   when the spec was written; a week later the check that killed it no longer reached the line, because the code around it had moved on.
   `RegressionMirror` now calls the row builder of the forward voxelizer directly, on a disc and a plane whose Y scale is zero, sheared, and
   requires every point to be finite and every normal to be (0, 1, 0) (53 to 54 checks); the 77 mutants of the spec are killed again.
9. *Reading the specs as text found 27 mutants that applied to nothing, in five specs* (the sounds of the keys 7, the rules about what a scene
   may fetch 8, the mesh path of the voxelizer 1, the add-on's mechanisms 2 and its materials 9) - for the reason in section 1 item 29: the code
   had moved on and nobody had run the spec. Among them, the refusal of a private address and the reading of an IPv4 address inside an IPv6
   one had no mutant that worked, and the rules against an address in an unusual spelling had never had one. The repair, spec by spec: the
   sounds of the keys were rewritten and three mutants for the click of a lamp added (92, all killed); `mutants_fetch_policy.py` was rewritten
   on the present code and gained 72 mutants (80 repaired or new, all killed, after fourteen checks were written for what the first run left
   alive and four mutants were set aside as equivalent, with their reasons at the head of the spec); the mesh voxelizer's reason for a scale of
   zero had its stale mutant rewritten, with two more (3, killed); the two mutants of a step of the add-on that no longer exists (a part's texture laid out
   before or after its resize - the layout never depends on size now) were removed, with the reason at the head of the spec; the nine of the
   materials were rewritten on the table of the four maps and on `_file_of_material`, with eight more (seven in that spec, one in the general
   add-on spec, whose scenario replays the hostile files). `check_mutant_specs.py` keeps it from happening again (item 29).
10. *The add-on's own copy of the address rules was put through the engine's, and its mutation round left ninety mutants alive - all of them
   holes in the checks, none a defect of the rules.* `safety.py` is the add-on's second copy of what the engine's `FetchPolicy` decides about an address, a reference, a bundle
   entry and a download under a cap (Blender cannot call Java, so it is written again in Python); `addon_safety_check.py` asks the engine
   and the add-on the same questions. The first 700 mutants of its spec (the whole first family - an operator, a constant or a bound
   turned over - and the start of the second) left 90 alive, in three groups. *The reasons*: the suites compared the verdict (refused or
   not), so an address refused for the wrong reason lived; the reasons are now checked for the traps (an empty user name, a port at the
   edge of its range, a host of one part or of five, a control character, a network path, an address given where a file was). *The
   edges*: the longest address allowed and the one byte more; port 2147483647; the allowance of the gzip reader for each member of a
   file made of several (the second member is asked for what is left of the cap, read through a spy); the two numbers the add-on sets
   itself (six levels of include, 50,000 instances of an import), which only the engine's own had been pinned; the last test of a bundle
   entry (the place it resolves to is forced outside the root, to a sibling that shares the root's spelling and to the root itself, and
   must be refused). *The characters*: a letter left out of the alphabet of a name lived because the corpus put a letter in only a few
   places; every printable ASCII character is now put in every place of an address (2,914 probes) and compared with the engine.
   Instead of one more example for each, the add-on's functions were compared with the engine's one by one: the grammar of an IPv6
   address against `java.net.URI` (1,183 texts), the host texts (19,131) and the addresses in bytes (32,068) against the non-public
   methods of `FetchPolicy` that decide what is a number, an address and a public one, and the verdicts on 8,342 addresses. **They
   agree on every one.** 25 mutants no input can tell from the original, each with the reason at the head of the spec (a test that a
   later line repeats, a bound the previous line already holds). One more check came from reading `fetcher.py`: the policy reads an
   address the way `java.net.URI` does, but the request is built by `urllib`, which reads it its own way - a host that the two read
   differently is the usual way around a check of this kind. For each of the 3,384 addresses of the corpus that the policy lets through
   (in both modes), the check now builds the connection and requires its host and its port to be the ones the policy judged: they are.
   What is *not* known: the 1,207 mutants after the 700 have not been run, and a name that resolves to a public address when it is
   checked and to a private one when it is used (DNS rebinding) is not covered (section 1, item 25, says so).
**What is verified, reviewed and unknown.** *Verified*: the expansion of every directive and every refusal (581 checks), the show's
player (211), the three whole scene files and the report layout of the spec, loaded through the engine; the formats leave the show out;
the stack is never taken inside a loop (and the real viewer, on a software renderer, draws 2,601 instances, presses the keys of a show,
is taken over by a W, ends by itself, writes its report, and destroys a lamp shade, a triangle, a cube and a mesh that cannot be turned
into cubes). *Reviewed*: the wording of the documents. *Unknown*: a take on a GPU with your models and the project's own shaders (the runtime
check used stand-ins when the upload had none, and says so); the speed of a real card on a forest (backlog 32, with the first run of
section 5 item 26 to supply the number); a very large texture set (backlog 33); the sound of `light-switch.wav`, which nobody has heard.

### 2026-10-18 .. 2026-10-20 - a hollow shape that stays hollow, a triangle that can be shot, E for the lamp, a picture the right way round, an altered door that is open, the new domain, and what a file may ask of the program

The request of 2026-10-18 (sent again on the 18th, 19th and 20th with the same three pictures): (1) the lampshade of `desklamp.pbd`, an
empty cylinder with no caps, became a full cylinder when it was shot - "check that every parameter of a primitive that can be changed
is implemented in the voxel conversion"; the triangles were not even implemented, and a shot at `let_triangle` answered "can't be
voxelized" - "find a solution, such as destroying it"; (2) a key (E, two functions?) that switches the light of a model, with a click
that can be set in Blender; (3) the texture mapping reverses when an object becomes voxels, the add-on needs a function that mirrors
a texture along an axis, and the textures still do not display right in Blender (the UV scale does not match); (4) when the door of a
container - the children of the lever arm linked to it - is altered, typically turned into voxels, the container is open, and
someone hiding in it (H) is visible from outside, on the facade; (5) the domain changes, front end, back end, add-on and
documentation; (6) strict rules for the upload (metadata, injections, an include that loops...); (7) a way to look at a PBD in the
browser without writing the engine again for the web; (8) a simple scene description format, for the presentation video in one take
and for stress tests. Section 1, items 18-26, say what exists. (7) and (8) are **not done** (backlog 20 and 27: next delivery; *status note, 2026-10-25: (8) is done - see the entry above - and (7) is
still open*). This
entry is how the rest was checked, what that found, and what was decided where the request was silent.

**Decisions** (each is a policy that could be the other way; say so if you want it changed):

- *A hollow shape is a wall of three voxels* (15 mm at the usual 5 mm), never more than 40 % of the extent it grows across: a thin tube
  stays a tube and a lampshade stays open at both ends. A plane or a disc is a slab of whole layers - 8 % of its larger side, at most
  10 cm, never fewer than two layers, rounded half up - laid from its lower face.
- *A voxel is filled when at least half of it is covered*, and exactly half fills: a face in the middle of a voxel is what round sizes
  give, and it must be decided by the rule, not by the last digit of a sum.
- *A shape too big for the work is voxelized whole, with larger voxels, and the hit says so*; nothing is cut. Nonsense (a zero or
  not-a-number scale, a voxel of zero, a bend of 1e30) gives nothing - never an exception or a hang. `twist` and `curve` do nothing on
  the GPU, so they do nothing here.
- *An open mesh is a wall.* A closed one is voxelized by the ray-parity inside test; a single triangle, a flat quad or a sheet has no
  inside and becomes a wall of at least two voxels.
- *What cannot become cubes is destroyed, not refused* (`DestructionWorld.destroyWhole`): it stops being drawn, a burst of particles of
  its own material comes out where the shot landed, and its face counts as open for the hiding places. Only `indestructible` is refused.
- *E: the nearer wins.* Where an item and a model are both under the crosshair, the nearer is used and the item on a tie. The model is
  the nearest group above the part whose children carry its id as a prefix (what a `pbd_ref` makes of the file it places), else the
  whole scene; lights that share a `linkGroup` are switched with it, wherever they stand; it is a room switch (any light on: all go
  off; else all go on); a light that follows a door is never switched by hand. The click is the light's `switchSound`, else
  `light-switch.wav`.
- *A negative `uvScale` is a mirror of the picture* on that axis - in the shader and in the voxel mapping alike - just as a negative
  object scale already mirrored the object. The colour of a voxel face is the nearest texel.
- *An altered door is open, however small the damage.* The 3 cm / 40 cm thresholds of the 2026-10-12 entry are gone.
- *The former host name is trusted nowhere*: no alias, no redirect, and the user agent of the older add-on is refused by the site
  unless `legacy_user_agents` lists it. The former name is written nowhere in the project any more (the tests use `pbd.example.org`),
  and `check_site_host.py` fails a run if it comes back or if the three declarations of the host (engine, add-on, site) differ.
- *The limits a file may ask of the program are far above what a real model needs* (item 25), so they only ever stop a file that was
  made to be stopped. Where a limit is not the same in two programs (six levels of `pbd_ref` in the add-on, eight in the engine,
  four under a submission on the site) it is because each stops a different thing; the numbers are compared by tests.

**What the checking found** (each pinned by a check that fails without the fix):

1. *The picture came out mirrored on the faces where the voxel colouring and the shader disagreed.* The colouring took the dominant
   axis of the face (`u = z` on both X faces); the shader runs the picture along other axes on other patches (the +X face of a cube
   runs `u` along -Z, the -Z face along -X...). The handle of a locker was on the left in the viewer and on the right once the locker
   was shot. `VoxelTextureMapper` now runs the shader backwards, and `RegressionTextureMapper` holds it to the port on the same
   points as the shader itself.
2. *The voxelizer that existed read a hollow cylinder as a full one because it never looked at `cap`, `faces`, `bend` or `shear`*: its
   classifiers saw a box and a few parameters. `ShapeEval`, the port of the tessellation shader, is now the one place that says what
   is drawn (`RegressionShaderPort` compares it with numbers worked out independently from the text of the shader), and
   `ForwardVoxelizer` carries the drawn surface (or the solid) forward through the same modifiers.
3. *A lattice laid from the object's origin lost the ends of a tube*: a tube five voxels tall, centred on its origin, had half a
   voxel at each end and lost both. The lattice is laid from the shape's own lower corner, so a flat face parallel to an axis lies on
   voxel boundaries.
4. *Adding up the coverage of the parts of a shell filled a frame of voxels along every edge*: the faces of a box are grown inward from
   their own surfaces and overlap where they meet. A voxel's coverage is now the largest of the parts', not the sum.
5. *A slab whose faces fell inside a layer left the layer speckled* (a leaf of 0.8 m made 264 voxels of 1264 in each outer layer):
   the slab is now whole layers.
6. *Putting a cell into its centre voxel skipped rows when the cell was longer than a voxel.* The lattice is built so that no cell is
   longer than half a voxel, checked after the pass, and the pass is repeated finer when more than 2 % of the volume sits in longer
   cells; only a cell wider than four voxels (a fold of the shader's own surface) is still put into its centre voxel.
7. *The site answered `/search.php/x.png` with the page on some set-ups (path info), and the refusal that closes that door was itself
   announcing the version of PHP* (`X-Powered-By` was removed after the answer was given). `AcceptPathInfo Off`, the nginx example
   and the order inside `pbd_harden_php()` are fixed, and the runs through PHP's own server, Apache and nginx ask all three the same
   questions.
8. *Some of my own earlier statements were wrong* and are corrected: the add-on guide said the add-on checks more than it does (it does
   not read the text files: backlog 31); it advised uploading a `.pbdasset`, which the site does not accept; the Material Source
   entry that writes `include_material materials/NAME.pbdmat` was called "Shared", and the site refuses what it writes; a comment
   in `PrimitiveVoxelizer` still described a bend as unhandled; and the docstring of `lint_check.php` pointed at a differential check
   that was in no list (it is backlog 28 now).
9. *Two pages of the site had a visual bug nobody saw because the page content policy blocks it*: an inline `style=` attribute in
   `tutorial.php` and in `search.php` (a note and a full-width filter row). They are classes with the page's nonce now; `tutorial.php`
   was opened in a headless Chromium and its console is empty.
10. *The mutation round on the voxel conversion found no bug in the engine - it found five things the checks could not see.* Each is now
    pinned, and the mutant that put it back is killed: (a) a taper that makes the BOTTOM wider (the old check widened only the top, so a
    grid that ignored a wide bottom passed it; the check now tries four pairs of ends); (b) the wall of a hollow shape on a thin object
    (a box five voxels thick must keep a hole in its middle - the wall is at most 40 % of the extent it grows across); (c) the half voxel
    (a voxel covered by exactly half is filled): seventeen boxes with a face off, four of them with a tie, are counted voxel by voxel by
    hand and the voxelizer has to give the same number - all seventeen are exact; (d) a plane whose slab is exactly two and a half layers
    (three layers, the tie rounds up); (e) a bar six metres long, which has more voxels along it than the octree is wide (1200 against
    1024 at 5 mm) though few lattice cells: it must get larger voxels, not be cut at 5.12 m. A sixth question, whether the shear of every
    closed solid is honoured and not only a cube's, was asked of a sphere, a cone, a torus, a cylinder and a cube in each rounding and
    was answered without a finding (volume within 0.7 % of the primitive's own after a shear of 0.9, the skin covered at 0.5). A cube
    sheared by 0.9 has a corner of 35 degrees, so the check "solid 2.5 voxels behind the skin" is asked at 0.5, where it is meaningful,
    and the cone, whose apex is thinner than a voxel, is given 2 % of its skin for it.

### 2026-10-12 - chips instead of a crater that vanishes, a material that decides how it breaks, the cassure, hiding places an AI can ask about, lamps and arms in the add-on, and an MP3 decoder

The request of 2026-10-10: voxel destruction is "almost perfect", but (1) the add-on has no way to add a toggleable light source
and lever arms the way keyframes can be added; (2) the pickup sound is to be `resources/sounds/item-equip.mp3`; (3) a hit should leave
particles coming out of the crater (a particle system and/or real voxels) instead of deleting everything; (4) the facade needs a
getter that says whether a hiding place's door is closed and intact enough for someone inside to be undetectable, and nobody may
get in or out while the door is closed; (5) the effect is to be defined by the **material**, not by a primitive's metadata, and it
is to include a break: when an attachment is too small after a shot, the whole piece must go away with a break. Section 1, items
12-17, say what exists. This entry is how it was checked, what that found, and what was decided where the request was silent.

**Decisions** (each is a policy that could be the other way; say so if you want it changed):

- *Chips, not voxel chunks.* A chip is a point mass drawn as one cube (no mesh, no rigid body), so 1500 can be alive at once; real
  voxel chunks stay for pieces of 400 voxels or more. The crumbs under that size used to be thrown away; their volume now goes
  into the chips.
- *The material decides, the primitive's metadata is not read.* Ten keys of a `.pbdmat` block (section 1 item 13); every material
  starts from the profile of its name (`wood`, `metal`, `plastic`, anything else). A value that is unreadable or out of range is
  **refused with a warning and the default of that key stays - never clamped**, so a typo cannot quietly become a different number.
- *The break is a rule about width.* A connection narrower than `breakWidth` between two thick parts does not hold; a thin run
  that touches only one thick part (a crater's rim) or was already thin before the hit is left alone. 0 turns it off.
- *A door is an instance that can move.* A linked instance with neither keyframes nor a lever arm is not a door: a place whose only
  "door" is a wall that never opens would be shut for good. Only the instances named by the place's `containerTrigger`s are
  measured, not the walls of the box.
- *Concealing and shut are two limits.* Someone inside is hidden when every door is fully closed (a lever arm at 1 % or less, a
  keyframed door back at its start) and no hole of 3 cm fits in it; the place is shut - **H refused** - when every door is closed
  and no hole a person (40 cm) fits through has been shot in it. A closed door that is locked still keeps the place shut.
  *(Replaced on 2026-10-20 - section 1, item 23: there is no limit any more; a door that has been altered at all is open.)*
- *H does not open the door for you.* It is refused with a message that says why; the player drags the door open (the mouse still
  looks and grabs while hiding), which is how it works for a person. Reviewed, not run.
- *A linked light follows its driver and its file setting is ignored.* On while any keyframe door or lever arm that shares its
  `linkGroup` is open, off when all are shut, edge-triggered; a loop never switches a light. There is no inverted polarity (backlog 24).

**What the checking found** (all of it pinned by a check that fails without the fix):

1. *Three checks that could not fail.* A probe that printed what a second shot at the piece lying on the floor really leaves showed
   that a shot at its **middle** cuts all of it away: no piece is left, so "the new piece is awake" (8d), "it settles again on the
   floor" (8e) and a check of the same kind on a piece that breaks while it falls (20c) were `allMatch` over an empty list - true,
   whatever the world did. The shot is now aimed at one end of the piece, every such check first requires that there is a piece, and
   the voxels are accounted for (23,907 = 22,879 removed + 300 splinters + 728 crumbs + 0 left). The suite went from 147 to 148 checks
   and the 227 mutants of `mutants_destruction_world.py` were run again on it, not on the earlier one.
2. *A constant a test quotes cannot be mutated.* `javac` copies a `static final` number into every class that mentions it, so a test
   that compared with `Mp3Decoder.SOME_LIMIT` compared the old number with itself after the engine's constant was changed; the
   tests that need such a value ask the running program by reflection (`TEST_PROCEDURE.md`, section E).
3. *"`case X ->` somewhere in `Main`" proves nothing.* The first tests of the key handlers looked in all of `Main` and were
   satisfied by any other `switch` of that size; `Sources.block` now cuts out the one handler first. The same section lists the dead
   `contact` flag of a particle's sub-step (nothing read it, so no test could fail on it: it was removed, not tested).
4. *The MP3 decoder.* Written, then compared with the C original bit for bit on recorded, derived, hand-made and random streams and
   within one 16-bit step with the ISO conformance streams (`TEST_PROCEDURE.md`, section F). A one-off sweep of 2079 valid mutants of
   its arithmetic and logic left 126 alive; six of them, four gaps, were real, all at the **end of the data** (a stream that ends right
   after a header, the look for the third header of a free-format frame, a read that stops exactly at the end, bits past the end read
   as ones). Each has a crafted stream now. Every difference ever found with the C original is one deliberate one (a frame with
   mixed blocks at 8 kHz is refused, where minimp3 writes past the end of its channel).
5. *Real Blender showed two things about the checks, not the add-on* (section C of `TEST_PROCEDURE.md`): a reference into a collection
   (`scene.pbd_materials[3]`) is only good until the collection changes, and read as another entry's defaults on about one run in a
   few on 4.5 - the check now finds its entry again by position at every use, and 4.5 was run five times in a row after the fix; and
   `bpy.types` has no attribute named after a registered `PropertyGroup`, so ranges are asked of `Class.bl_rna`.
6. *A mutation spec had gone stale, and said so.* The last full run of `mutants_addon.py` was from 2026-10-08. Run again on the final
   add-on, 100 of its 102 mutants were killed and two reported `PATTERN NOT FOUND`: the object panel had gained a call
   (`draw_follower_lights`) between the lines they replaced. Not a survivor but a test that no longer tested - exactly what the
   "pattern not found counts as a failure" rule is for. Both patterns were rewritten against the current code and both mutants are
   killed (102/102).
7. *Statements corrected while the documents were written*: `mutate.py` was said to stop a run by a time limit (it has none; only
   the one-off sweep had), "4500 frames" were 4500 random streams of eight frames each, the wording of the seeds and the ISO figures
   were put right against the logs, and one "difference" with the C original was the comparison harness's own (it did not clear the
   info structure before each call, as the port does; it does now).

**Seen** (software rasteriser, `VoxelFallDemo`; not OpenGL): the chips come out of the hole, fall, bounce and shrink away; a wooden
slab shot through the middle loses its whole top, which lies flat on the floor, while the same slab in a material with
`breakWidth = 0` keeps it; a door gets a round hole; a post's top lands on a crate. The demo draws a chip with `ParticleDraw.matrix`,
the matrix the renderer hands to its cube, so the pictures also check that matrix by eye.

**Costs measured** (this workspace, two cores, an idle machine, no GPU; cold JVM unless said otherwise): the first hit on a post, 160-213 ms
(23-40 ms from the next hit on), and not made longer by the break or the chips (125-190 ms with both off); one step of 1/60 s
with the 1500 chips of the cap alive, 0.10-0.14 ms with only the floor to land on, 0.28 ms with eight boxes, 0.58-0.66 ms with four
voxel solids as well (worst single step 1-8 ms); the break analysis of a 1.28 m solid block, the worst case (a window of 0.9 to 5.5
million cells for a connection of 3 to 13 cm), 150-360 ms and 22-111 MB of heap - a post, a plank or a door has a far smaller window,
the object's own box, and a window over 8 million cells is not analysed at all.

**Not verified**: the GL drawing of chips, the H refusal and the getters in a running window, any sound through a real sound card,
the real `item-equip.mp3` (never seen), and the server. Blender 4.0.2, 4.1, 4.3 and 4.4 were not run (backlog 23).

**Counts after this entry**: 30 Java suites (2015 checks) + the add-on check (438) = 2453 checks, all green; 415 on each of the five
real Blenders; 23 mutation spec files, 2333 mutants, all killed.

### 2026-10-08 (later) - the add-on on real Blender: stale matrices, an import that crashed on one dead reference, and a sidebar that lied about its version

Started as the packaging step: the add-on zip was stale, so it was to be rebuilt from the sources. Before shipping a
zip, the add-on was run on something other than the fake it had been checked on.

- **`pip install bpy`** gives Blender as a Python module (5.2.2 LTS first, about 400 MB each; 4.2, 4.5, 5.0 and 5.1 later, see "Other Blender versions" below). `blender_addon_check.py` now has a
  second mode (`PBD_REAL_BPY=1`, wrapped as `blender_real_check.py`) that registers the add-on in it and runs the same
  scenarios; `run.sh` runs it when `bpy` is importable. Its checks are not added to the totals (they repeat the fake's).
- **The fake grew a user interface.** `fake_bpy.py` now has a recording `UILayout` with Blender 4.0's signatures that
  refuses an unknown setting, operator, list, choice or keyword, a positional label and a wrong type, and a registry
  that records `register_class` / `unregister_class`. The check draws the object panel, the sidebar, the Materials panel,
  the three menus and both import dialogs for every kind of object and variant (lists empty and filled); a draw-time
  error used to fail silently into Blender's console (`draw_pbd_properties_safe` turns it into a red box, which the check
  now looks for). 25 mutants (sidebar title, lever-arm fields, misnamed operators, unregister leaving traces...) were
  all killed.
- **Findings** (each pinned by a check that fails without the fix, and by a mutant):
  1. *Stale matrices.* Real Blender computes `matrix_world`, `matrix_local` and `dimensions` in a depsgraph that a Python
     assignment does not refresh. A scene imported then exported in one script had a door panel three units off, and a
     script that moved objects and exported at once got the old positions. The exporter now calls
     `context.view_layer.update()` first, the importer before it returns. Only real Blender can see this.
  2. *One dead reference stopped a whole import.* `fetcher.FetchError` was a plain `Exception`; the importer documents and
     catches `OSError`/`ValueError`. With no network or a 404, `Import PBD` printed a traceback, and a scene with one
     unreachable `pbd_ref` was not imported at all instead of leaving an empty placeholder and a warning. Found by pressing
     every button of the add-on once on real Blender. `FetchError` is an `OSError` now; the check replaces the network
     by a stub that fails.
  3. *The sidebar's first line* read `PBD Tools v0.41.0` whatever the version, while the user guide tells people to compare
     it with `bl_info`. It is built from `bl_info` now, and the version is 0.44.0.
  4. *The materials-folder fallback* counted four folders up from the add-on's file in a checkout, where it is five.
  5. *The user guide* described a "PBD Project Root" field and a one-click Export that the add-on no longer has, and
     said the add-on and the engine share one remote cache (they keep two: backlog item 22). Corrected.
  6. *The zip people install was stale.* `tools/blender-addon/pbd_tools.zip` still held version 0.43.1: ten of the twelve
     files differed from the sources, so a user who installed it got none of the fixes above - and no lever arm at all (not a
     word of `lever_arm` or `leverArm` in its sources: no panel, no block in what it exported). It is a copy, not a link (the README says so), and nothing compared the two. The add-on check
     now does (the zip holds exactly the sources' files, byte for byte, in a folder of its own; the check was seen to FAIL on the
     old zip before the zip was rebuilt), and on real Blender it hands the zip to Blender's own installer
     (`Preferences > Add-ons > Install...`), checks that exactly one add-on lands among the user's add-ons with the
     sources' name and version, enables it (sidebar panel and operators appear) and disables it (they go away).
- **What the real run checks**: every icon the add-on names exists in this Blender, the settings Blender registered are
  exactly the ones the add-on declares, every button pressed once on a plain scene and once with a `mesh` primitive
  selected does not die with a Python exception, a script's edits are exported and imported at once, registration is
  complete and leaves nothing behind. `mutants_addon_real.py` (6 mutants, all killed on real Blender, all surviving on the
  fake) puts each class of mistake back: no refresh before export, none after import, an icon Blender does not have, a
  panel in a space it does not have, a choice list whose default is not a choice, an attribute that does not exist.
  (The zip scenario has no mutant of its own: a mutant changes the add-on, and the zip is rebuilt from the changed add-on in that
  mode; what guards the committed zip is the comparison above, seen to fail on the old one.)
- **Not found**: nothing else crashed - of the 37 buttons pressed, only Import PBD died (finding 2); the other 36 pass on a plain
  scene and with a `mesh` primitive.
  **Not run**: the panels in a window, undo, the viewport drawing, Blender 4.0.2 (yours), 4.1, 4.3 and 4.4.
- **Other Blender versions** (backlog item 23). The same check, unchanged, was run on Blender 4.2.23 LTS, 4.5.14 LTS, 5.0.1,
  5.1.2 and 5.2.2 LTS: 248 checks pass on each, none fails. They are the three animation APIs `anim_compat.py` has to
  know: 4.2 has no action slots (so the export reads `action.fcurves`, the path Blender 4.0 takes too), 4.5 has slots and
  the deprecated `action.fcurves` proxy, 5.0 and later have slots only. Two things came with it. (1) **Blender 4.2 as a Python
  module crashes at exit as soon as any operator has been registered** - a three-line operator of our own in a scratch file
  does it, nothing of the add-on - so a passing run ended with exit status 139; the real run now leaves with `os._exit` and
  its exit status is the check's verdict (otherwise `mutate.py` would have counted every mutant as killed). (2) **Blender 4.0
  itself could not be run** (no `bpy` wheel before 4.2, and Blender's download server is not reachable from the workspace),
  so for 4.0 only two static checks exist, both against its API stubs (the `fake-bpy-module-4.0` package) and neither is part of
  `run.sh`: every icon the add-on names (29) is in the 4.0 icon list - a name Blender does not know is a `TypeError` that blanks
  a panel - and a type check finds no call, keyword or module attribute missing from 4.0 beyond what the add-on registers
  itself and the guarded 4.4+ channelbag call. The stubs cannot see through the untyped parameters (`obj`, `context`), so this
  is a partial net. Your 4.0.2 stays the one version nobody here has run.


### 2026-10-08 - a shot at a mesh, a Description field, names the format cannot hold, and the docs' own numbers

The request of 2026-10-07 (polish the roadmap, check the specification, keep developing, check lever-arm attachments
and the G cut) was sent again this morning. What came after the two checks of section 3:

- **A shot at a `mesh` instance was broken four ways** (section 3.3): found by a probe, then pinned by
  `RegressionMeshVoxel` (61 checks) and its 27 mutants - 27/27 killed; the one mutant that survived ("nudge only
  one of the two ray coordinates") was shown equivalent for every mesh that can be built by hand and was dropped,
  with the reason written in the spec file. The target search of `PbdRenderer` moved into the pure
  `DestructionWorld.findFresh` so the broad phase has tests; `PbdMeshData.bounds()` is new. Four comments that
  contradicted the code were corrected (`PrimitiveVoxelizer` said meshes return null; `DestructionWorld` said
  the crater cost grows with the cube of the radius and "a few MB", and its refusal message said "mesh type";
  `Main` had a mangled block about the G flow).
- **The first mutation round on the lever-arm run-time chain** (61 mutants) left seven alive, each a real gap in
  the tests: nothing checked where "fully open" begins, that `setValue` clamps, that a silent update still records
  the arm's state, a grabbed point swinging behind the camera, a lamp that only slides or only turns, or the
  tolerance of the light tracker. Twelve checks were added; two equivalent mutants are documented in the spec file.
  `SceneHandle.setParent`'s Javadoc was corrected to say what the code does: the pose is **not** compensated.
- **The documentation checker had a bug of its own.** It only took a bare fence line for an opening fence, so a
  fence that carries a language (an `sh` block) threw the pairing off and the prose between two code blocks was
  reported as an unchecked lever-arm example. Fixed, with a self-check on a made-up document. The add-on guide's door example is now a checked and
  pinned example (7 more doc mutants, 31/31 killed in all).
- **`check_doc_counts.py`** compares the numbers the docs quote (checks per suite, mutants per spec, suite and
  mutant totals, "K/K mutants" claims) with what `run.sh` has just counted. Its first pass over the finished state
  reported 24 numbers that the day's changes had made stale (`138` for `RegressionLeverArmSystem`, which has 147;
  `87` for `RegressionLeverArmAttach`, which has 90; `111` add-on checks where there are 208; an `18 Java suites`
  total that is 19; `23/23` and `24/24` mutants, sizes no spec has any more; a "Seven spec files" that cannot be
  compared). It checks itself first on a made-up document, and `mutants_doc_counts.py` breaks it 20 ways (20/20
  killed) - a pattern that silently stopped matching would otherwise make every run green.
- **Add-on: Description field and a File-box scenario** (5 text fields x pretty / minified x awkward text: spaces, `#`,
  `=`, commas, brackets, a tab, a line break, padding, a double quote). It found a real divergence: the Python
  `_quote_if_needed` did not quote a `#` or a tab as `PbdSerializer.quoteIfNeeded` does, so a one-word name like
  `C#` came back as `C`. Text is now written as typed, with two exceptions the format forces (a double quote
  becomes an apostrophe, a line break a space).
- **Add-on: object names.** The same probe style, pointed at names, found that an object called `front door` was
  exported as `cube front door {` and the engine refused the file (and so did `a=b`, `a{b}`, `porte d'entrée`...).
  Every id the exporter writes (the instance's own, `parent`, `containerTrigger`, an embed's anchor and the prefix
  of its copies) now goes through one function that writes each run of unusable characters as `_`, keeps a name that
  was already fine, numbers a clash (`_2`) and lists what changed in the export's warnings. The importer's identifier
  rule was narrower than the engine's (letters, digits, `_` and `.` only, so `wall-left` could not be opened); it
  now follows the engine's. A material name with a space is reported, not rewritten. The check covers 13 awkward
  names, a pivot, a reference, an embed and a light hanging on one, a name clash, a container trigger and ids the
  engine accepts; 74 add-on mutants (all killed) pin each character of the rule, each place an id is written and
  each field of an embedded lever arm. That last group found one more gap: no arm in the embed fixture was locked,
  so the embed writer could drop `locked` without any check failing; the fixture's hinge is now locked and has both
  sounds, and every field of the arm is compared with the engine's own inlining.
- **An emptied container stays empty** (backlog item 7, the half about re-rolling). The frame loop threw a container's
  contents away when its doors closed and loaded them again with the same seed, so everything the player had taken
  came back. The decision moved into the pure `pbd.pz.ContainerStock`: the contents are loaded on the first
  opening, put on a shelf when every door is shut and given back as they were left on the next opening; a new scene
  forgets them. `RegressionContainerStock` (30 checks) runs the rules on stand-in contents (the real ones need the
  game's FBX files), `mutants_container_stock.py` breaks them 16 ways (all killed), and the suite reads `Main`'s
  source to check that every outcome has a case there. The wiring in the frame loop is **reviewed**, not run. While
  in there: the renderer of an item taken with E was never closed (a shelved group only releases the renderers of
  the items still inside) - it now is. Not done: distributing items across linked containers, and anything saved to
  disk. Reading `Main.availableVolume` turned up a separate defect (backlog item 21), listed, not fixed.
- Docs: `ADDON_USER_GUIDE.md` gained the File metadata box, the lever-arm / pivot recipe with a checked example,
  the names rules, the limits, and a **draft of the tutorial text** (backlog item 4); `tools/blender-addon/README.md`,
  `ENGINE_DEV_GUIDE.md`, `TEST_PROCEDURE.md`, `STATUS_SUMMARY.md` brought up to date; the add-on zip regenerated
  from the current sources.

### 2026-10-07 - documentation pass, a parser tolerance and the AI facade

Rewrote this file's state sections; brought `PBD_FORMAT_SPEC.md`, `ADDON_USER_GUIDE.md`, `TEST_PROCEDURE.md`,
`STATUS_SUMMARY.md`, `ENGINE_DEV_GUIDE.md` and the add-on `README.md` up to date (the spec's `## Lever-arm`
section had no `release`/`locked`/`lockAt`/sounds; its `## Curves` example used a syntax the parser does not
read - `point = ...` lines instead of `kind =` and `points = [...]`). The new `RegressionSpecExamples` makes that
kind of drift fail instead of go unnoticed: nine spec examples carry a `<!-- check: scene -->` (or `material`)
marker and are parsed by the real parser on each run; the lever-arm ones are pinned to the numbers in the text; a doc
example that shows a `leverArm` without the marker fails; the number of checked examples cannot shrink. It was
mutation-tested by breaking the docs in a scratch copy (24/24 then, 31/31 since). The mutation scripts themselves (`mutate.py`,
`mutants_*.py`) are now in `tools/regression/` - before, they lived in a scratch folder, which made every "N/N mutants
killed" claim unrepeatable. Two small code changes came out of checking the spec against the code:

- `Main.getArmValue / isArmLocked / setArmLocked`: the lever-arm specification asked for an accessor for
  AI/modding code; it existed only on `PbdRenderer`, not on the static facade modders actually call
  (`availableVolume`, `canHide`, ...). Added next to them; with no scene loaded they answer NaN / false instead
  of throwing (3 checks in `RegressionLeverArmSystem`, 4/4 mutants killed). The live case needs a renderer.
- **A bare (unquoted) base64 `vertexData=`/`vertexDataLod<N>=` is now read** by the engine. Its padding `=` is
  structural to `readRawValue`, so a hand-written file (or an older tool's, such as the project's own
  `mushroom.pbd`) failed with "Expected an identifier" a few tokens later; the Blender add-on's reader had always
  taken it. Every writer here quotes it, so only hand-made files could hit this. 4 checks in
  `RegressionFormatRoundTrip` and 2 in the add-on check (engine and add-on agree on the same text); 4/4 mutants.
  Quoting stays the recommendation.

### 2026-10-07 - keyframe -> lever-arm migration tool

Asked for: the keyframed doors have to be replaced by lever arms before video 2. `LeverArmMigration`
(`pbd.format`) + `java pbd.PbdConvertMain lever <input.pbd|.pbdbin|folder> <output>`:

- Converts an instance whose keyframes are a plain open/close motion - the first keyframe is the closed pose
  (it becomes the instance's own `pos`/`rot`), the last the open pose (`openPos`/`openRot`), `speed` from the
  clip's duration, its `sound=` becomes `openSound`/`closeSound`; `linkGroup`, children and container triggers
  are untouched.
- **Refuses, and says why**, what an arm cannot express. Per instance: a looping clip, a `channel` keyframe
  (humidity-driven), a scale animation, a single keyframe, times that do not strictly increase, a first and last
  pose that are the same, a middle keyframe that is off the straight line between the two end poses ("more than two
  poses") or that carries a sound, and an instance that already has an arm. Per file: a file that places others with
  `pbd_ref` (the copy would have them inlined) and `.pbdasset` (a zip: unpack, convert `scene.pbd`, pack with
  `PbdConvertMain asset`). It never overwrites its input, writes a report per file, and with a folder keeps the
  relative layout.
- The converted file is regenerated from the parsed scene, so **comments and formatting are not kept**; sound
  *timing* differs (keyframe sounds fire when the clip crosses a time, arm sounds when the arm leaves/returns to
  the closed end).
- Verified by `RegressionLeverArmMigration` (57 checks): poses of the converted file equal the keyframe poses at
  the first/last time and at mid-clip for single-axis rotations, tilted and rotated doors, drawers (position
  keyframes), refusals one by one, folder mode, report text, no overwrite. 29/29 mutants killed (the first run
  found the "quaternion not updated" mutant surviving - a rotated drawer and a tilted door were added to kill it).

### 2026-10-07 - format coherence: every field through every format (6 real data-loss bugs)

You asked for a systematic coherence test. `RegressionFormatRoundTrip` builds ONE scene that uses every field of
every class (instances, keyframes, lever arms, modifiers, curves, mesh data, includes, lights, container
triggers, indestructible/hardness...) - plus awkward values (a space, `#`, `=`, `,`, a bracket list, a quoted URL)
- writes it as pretty `.pbd`, minified `.pbd`, `.pbdbin` and `.pbdasset`, reads each back and compares **every
public field by reflection**. A completeness guard fails if the scene leaves any field at its default (a field
nobody sets would be "compared" while empty on both sides and prove nothing). First run: red, as intended. Real bugs
found and fixed:

1. several `include_material` lines kept only the **last** (the scene now keeps the list; every writer uses it);
2. an include path containing a space came back unreadable;
3. **curve blocks vanished** (the serializer never wrote them);
4. any free-form value with whitespace, `=`, `,` or `#` (`linkGroup = "front doors"`) was written unquoted and
   broke the file when read back from `.pbdbin`/minified;
5. the materials of a **placed** file (`pbd_ref`) were lost on conversion: the referenced file's
   `include_material` lines were in nobody's list (now carried, rebased to the including file's folder, or made
   absolute for a file fetched from a URL);
6. `.pbdasset` bundled only the last include's materials and textures.

Also verified: re-writing what was read gives identical text (no drift), `.pbdbin` read-write-read is stable.
25/25 mutants of the fixes killed (`tools/regression/mutants_roundtrip.py`; the survivors of the first mutation round - modifier params, category, channel,
a `#` in metadata - were test gaps, closed by adding awkward data of exactly those kinds). Known format limits:
a value containing `"` cannot be written (no escape in the format); numbers are written with 6 significant
digits; re-serializing drops comments and formatting. `.pbdasset` does not bundle the files a `pbd_ref` points to
(flatten or embed first) and the add-on's embed does not carry bundled materials.

### 2026-10-07 - the Blender add-on, checked against the engine (a real export bug)

`tools/regression/blender_addon_check.py` runs the add-on's real exporter, importer and operators on a fake `bpy`
(`fake_bpy.py`; Blender's real `mathutils`), writes files, and has the engine's real parser and hierarchy
resolver (`BlenderAddonDump.java`) say what it sees. The invariant: for every object, the world matrix the engine
computes from the exported file (arm closed / half / open, or a keyframe time) equals Blender's own, through the
Z-up -> Y-up change and the engine's x2 factor for a canonical shape. Scenarios: hinged door on a pivot, parent
inverse (Ctrl+P), child of a primitive, keyframes, warnings, embed vs reference, round trip of files the engine
wrote through the add-on and back. Found and fixed:

- **a child of a canonical primitive was exported two times too large/far** (a Blender primitive is 2x2x2, the
  engine's 1x1x1, and the parent's factor was applied to the child's own size and offset in the wrong place);
- the importer **parsed `leverArm` and then dropped it** (an imported door lost its arm);
- the embed anchor is now a `group` pivot, so an embedded reference can carry an arm;
- guard rails: a keyframed arm warns that keyframes win; `indestructible` on a `pbd_ref` is not exported (the
  engine refuses it); a `lever arm` on a `ref`/`light` object is not exported (use a pivot).

23 add-on mutants (a changed factor, a dropped field, a swapped axis...) were all killed (the spec has 74 now: see 2026-10-08).

### 2026-10-07 - lever arm: lock, release, sounds, attach, lights, drag (the rest of the specification)

Section 2 is the result; the build order was: format fields `release` / `locked` / `lockAt` /
`openSound` / `closeSound` (Java parser, serializer, `.pbdbin`, `.pbdasset`, add-on UI, export, import);
`LeverArmSystem` (pure, no GPU) holding the live value, who drives it, lock, release and the sounds, so all of it
could be tested; `pick` (what a click grabs, through any attached primitive) and `dragToward` (replaces the
40-sample search by coarse sampling + golden-section refinement, worst error 0.0002 degrees on a hinge);
`LightTracker`; `setParent` that really re-parents; `pbd_ref` copying everything (`PbdInstance.copyAs`).
`PbdRenderer` and `Main` were rewired to use `LeverArmSystem` (**reviewed**, compile-checked).

### 2026-10-07 - G strong enough to cut: solid crater, falling pieces

Section 3.2. `VoxelCrater` (solid irregular crater), `VoxelConnectivity` (face-connected pieces), `VoxelDebris`
(what stays, what falls, what is dust), `RigidDebris` (rigid bodies that fall, land on the floor/remains/boxes and
sleep), `DestructionWorld` (the whole flow behind `PbdRenderer.destroyAt`, with a host interface so it runs
without a window), `VoxelFallDemo` (the pictures). `PbdRenderer.destroyAt` is now a thin call into it.

---

Everything from here down is the journal as it stood before 2026-10-07: entries without dates, newest first, kept as written - self-corrections included. A **Status** note was added under every entry that a later one changed. The entries themselves were not edited, except for two headings: the minified-`.pbd` bug (now marked FIXED; `RegressionMinifiedMetadataQuoting` quotes that heading) and a pointer in the "Lever-arm system" heading.

### Lever-arm drag-and-drop: simple geometric prototype built (no physics engine - chosen explicitly over the alternative)

Asked for right after the correction below shipped: given the choice between a quick geometric prototype (no new dependency, can't be verified visually here either way) and integrating a real physics library (bigger, also unverifiable here, and this codebase has none today), a simple prototype now, with real physics as a later option, was the one picked.

How it works: left-click now branches on what's under the crosshair at the moment the button goes down - a keyframed instance still gets the old single-click scripted toggle (`toggleKeyframedInstanceAlongRay`, narrowed to keyframe-only - see its own doc), while a lever-arm instance is grabbed instead (`PbdRenderer.findLeverArmGrabPoint`, reusing the exact same ray-vs-oriented-box test via a newly-shared `findClosestInstanceAlongRay` helper) and the grabbed point's own LOCAL position is remembered. Every frame the button stays down, `PbdRenderer.dragArmValueTowardRay` samples the arm value in 40 steps across `[0,1]`, evaluates what world transform the instance WOULD have at each one (`previewWorldTransform`, via a throwaway `HierarchyResolver` call - doesn't mutate anything), and commits (`setArmValue`) whichever value puts the grabbed point closest to THIS frame's ray. On release, `setOpen` takes over and eases the rest of the way to whichever end is now closer, reusing the already-existing scripted/sound/`linkGroup` behavior rather than inventing separate "let go" logic.

Deliberately simple, and deliberately general: sampling rather than a closed-form angle solve means this works for a pure rotation, a pure slide, or a combined arm alike, with no assumption about a single fixed hinge axis - the honest tradeoff is less precision than a real solve (a 1/40 step) and a per-frame cost (41 whole-scene `HierarchyResolver` passes while actively dragging - cheap for scenes this project's own examples run, revisit if a scene is ever large enough for that to matter).

Caught and fixed in the same pass, from this project's own documented precedent (a scene switch leaving a stale destroyed-instance index behind, fixed earlier - see that entry further down): a drag in progress when N/B switches scenes would have left a stale instance index pointed at the NEW scene's (possibly smaller) instance list. Reset alongside the exact same `containerContents.clear()`/`renderer.clearDestroyed()` scene-load point that already exists for this reason.

Also added: `PBD_FORMAT_SPEC.md` never actually documented the `leverArm {}` block's syntax at all (an oversight from when the mechanism first shipped, below) - added a `## Lever-arm` section matching `## Keyframes`'s own style and placement.

Partially verified by execution, now - the core numerical question (does the 40-sample search actually converge on the right arm value, rather than some sign-flipped or axis-swapped value that still happens to compile) was checked for real: `dragArmValueTowardRay`'s search loop and `previewWorldTransform` were copied verbatim into a standalone harness (not retyped from memory) that parses a real one-door `leverArm` scene through the actual `PbdEngine`/`PbdParser`, and calls the actual, already-compiled `HierarchyResolver` - none of that needs LWJGL, only `PbdRenderer.java` itself does. For six known arm values (0.0, 0.25, 0.33, 0.5, 0.725, 1.0) plus a ray built to look exactly at each value's true grab-point location, the search recovered every one within 0.02 - including a discriminating case built specifically to rule out the metric trivially agreeing with itself (a ray aimed at the CLOSED point's location, checked that it resolves near 0.0 and not 1.0 or something else). All checks passed.

Still not, and can't be from here: the LWJGL-bound plumbing around that math - the GLFW mouse-down/mouse-held wiring in `Main.java`, screen-to-world ray construction, and the click branching between drag-start and the keyframe fallback - none of that compiles in this sandbox, so it's reviewed by careful reading only, not run. And "does dragging feel right" (the step size, how forgiving the grab point's pickup radius feels, whether 40 samples/frame is smooth enough) is inherently a thing to feel at a keyboard, not something a harness can answer. Recommend actually grabbing a door in-engine before recording video 2 - the number-crunching underneath it is now confirmed sound, but that's a different claim from "it feels good to drag."

> **Status (2026-10-08):** the 40-sample search described here was replaced by coarse sampling plus golden-section refinement in `LeverArmSystem.dragToward` (worst error 0.0002 degrees on a hinge, instead of a 1/40 step), and the drag maths moved out of `PbdRenderer` into the pure `LeverArmSystem`, where `RegressionLeverArmSystem` checks it without a GPU. The mouse wiring in `Main.java` and the feel of the drag are still only reviewed (section 5, item 6). The stale-index reset on a scene switch described here is still in `Main.java`.

### Lever-arm correction: the arm value needed to be settable from OUTSIDE the scripted easing, not just toggled (facade bug fix + setArmValue/getArmValue added)

Caught immediately after the entry just below shipped, from feedback on it: that first pass assumed a lever-arm's 0..1 value would always be driven by `PbdRenderer`'s own `speed`-based easing, toggled on/off like a keyframed door. Wrong for the actual intent - the open/closed ENDPOINTS are authored fixed points, but the motion between them was never meant to be necessarily scripted; the specifically wanted case is a mouse drag-and-drop with real physics, where the door's openness at any instant is whatever the drag/physics computes that frame, not something this class eases toward on its own clock.

That wrong assumption had leaked into real logic, not just the easing itself - two fixes:
- `isInstanceOpen`/`isInstanceFullyOpen`/`isInstanceFullyClosed` required `instanceOpen` (a discrete toggle-intent flag, set only by a click or `setOpen`) to AGREE with the continuous arm value for a lever-arm instance. A door driven any other way - a drag, for instance - could sit fully open at arm value 1.0 and still report as closed to the facade, so container contents would never appear: exactly the "link to the other modules - facade - like what's displayed inside" this was asked to get right. Fixed: for a lever-arm instance, all three now read `instanceArmValue` directly and ignore `instanceOpen` entirely. `instanceOpen` remains meaningful only for the keyframe mechanism, and as the scripted-easing TARGET in `updateAnimation` - never as something the facade checks for a lever-arm instance.
- There was no way to set the value directly at all - `updateAnimation`'s easing loop was the only writer, so there was nothing an external drag system could even call. Added `PbdRenderer.setArmValue(instanceId, value)` (clamped to [0,1], same `linkGroup` propagation as `setOpen`) as that missing write path, and `PbdRenderer.getArmValue(instanceId)` as the read side - this is the roadmap's long-pending `getArmRotationValue` accessor, named `ArmValue` instead since the value isn't necessarily a pure rotation (a sliding or combined arm has no single rotation to report). A new per-instance flag (`armExternallyDriven`) stops `updateAnimation`'s own easing from fighting a `setArmValue` caller every frame by skipping that instance entirely while the flag is set; `setOpen`/`toggleKeyframedInstanceAlongRay` (the existing click/scripted path) clear it, hand control back to scripted easing.

At the time this fix shipped, the actual drag-and-drop interaction was deliberately left for a follow-up decision (no physics engine exists anywhere in this codebase, and the real mechanics were an open question not worth guessing at blind) - see the entry above, written right after: a simple geometric prototype now exists, built against exactly the `setArmValue`/`getArmValue` seam this entry describes, which is what that seam was for.

> **Status (2026-10-08):** still the design: `setArmValue` / `getArmValue` now live on `LeverArmSystem` (the pure class) with `PbdRenderer` delegating, and `getArmValue`, `isArmLocked` and `setArmLocked` are also on the static `Main` facade that mods call (section 2, row 5).

### Lever-arm mechanism shipped as the door-keyframe replacement, format-wide (MVP DONE, but see the correction above - three backlog items deliberately deferred)

Requested so every container door currently animated via keyframes could be switched over before video 2 is recorded - keyframe-interpolated swings were reported to produce visual glitches, and the backlog entry below (now trimmed to just what's still open) had already proposed this exact replacement.

Design: an instance's own base `position`/`rotationDeg` is pose 0 (closed); a new `leverArm { openPos openRot speed }` block defines pose 1 (open) - both `openPos`/`openRot` independently nullable, falling back to the base pose; `speed` is arm-units/second (default 1.0, must be > 0). A continuous value in [0,1] drives a 2-point lerp between the two poses, eased the same way keyframe playback already eased, just driven by this value instead of animation time.

Wired everywhere the format lives:
- **`.pbd` text**: new `leverArm {}` block in `PbdInstance`/`PbdParser`/`PbdSerializer` (pretty and minified both).
- **Java engine**: `HierarchyResolver` gained a 3-arg `resolve(scene, animTime[], armValue[])` overload (keyframes still win if an instance somehow has both); `PbdRenderer` gained a parallel `instanceArmValue[]` array, an `updateAnimation` branch that eases it toward 0/1 at `speed` units/sec, and - the one easy-to-miss piece - `isInstanceFullyOpen`/`isInstanceFullyClosed` now also check it, which is what actually lets a lever-arm door's container contents show/hide correctly; without that half of the wiring the door would animate but its contents would never appear.
- **Blender addon**: new UI fields + a "Capture Current Pose as Open" button (`properties.py`, `ui_panel.py`), and `export_pbd.py`/`import_pbd.py` emission + parsing, deliberately reusing the SAME axis-conversion pipeline (`_to_pbd_matrix`/`_to_blender_matrix`) already used for the base pose, so the open pose can't drift into a different coordinate convention than the closed one.
- **`.pbdbin`/`.pbdasset`**: confirmed free - `.pbdbin` is gzip over `PbdSerializer`'s own minified text and `.pbdasset` zip-embeds `scene.pbd`'s raw bytes unchanged, so neither reimplements the schema and both already carry `leverArm` data with no extra code.

Verified: 19 checks in `tools/regression/RegressionLeverArm.java` (wired into `run.sh`) - parsing, pretty+minified round-trips, lerp at several arm values, clamping outside [0,1], no-data/NaN fallback to the closed pose, and keyframe-wins-if-both precedence. The Blender addon side can't run here either (no real Blender), so it was verified separately: the real `_to_pbd_matrix`/`_to_blender_matrix`/`_lever_arm_from_fields` functions extracted via `ast` straight out of the edited source files and run against a genuinely-installed standalone `mathutils` package (not a stub) - 19/19 checks, including a 5-case export-then-import round trip proving the two conversions are true mathematical inverses for a lever-arm's open pose.

Deliberately NOT built (MVP scope, not needed for the door use case this was requested for - see the trimmed backlog entry below): generic multi-primitive attachment, and per-motion SFX on the arm itself (today only a keyframe can carry `sound=`). A public accessor for AI/modding WAS initially left out here too, but that turned out to be wrong, not just incomplete - see the correction entry above, which also fixes a real facade bug this version shipped with.

Flagged, not fixed: whether the remote moderation server (`server/upload.php`, not present in this sandbox - lives on pbd.aptheo.fr) does anything `.pbd`-structure-aware that a new block type could trip up is unknown from here. The Blender-side upload client (`upload_server.py`) does no block-level validation at all, so it won't block a submission either way. And - same caveat as every other `PbdRenderer.java` change in this project's history - the engine-side half was never actually rendered; LWJGL doesn't exist in this sandbox, so it's verified by careful reading and brace-by-brace review, not execution. Recommend opening one lever-arm door in-engine before recording video 2.

> **Status (2026-10-08):** all of what was "deliberately NOT built" below is built: attaching other primitives (section 3.1), a sound on the arm's own motion (`openSound` / `closeSound`) and the AI accessor (the correction entry). The arm also gained `release`, `locked` and `lockAt`. `RegressionLeverArm` has 45 checks now (19 when this was written), and the add-on side is checked by `blender_addon_check.py` against the engine's parser instead of by functions extracted with `ast`. Section 2 is the current, line-by-line picture.

### Voxel-destruction radius raised 14 -> 20 for video-1 visibility, stress-tested empirically first (DONE, recommend one test shot before recording)

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

> **Status (2026-10-08):** superseded. The crater is no longer a set of ray tunnels: it is a solid irregular volume of radius 30 voxels (`VoxelCrater`, section 3.2), so the table above - which measured the old ray-tunnel crater, about 500 removed voxels - no longer describes the cost; the current cost is in section 4 (`RegressionCraterOOM`). The caveat that it was never rendered here still stands.

### E-key item pickup now plays a sound; both it and G's existing doomshotgun are now preloaded (DONE - asset files still missing, same gap as before, now two filenames deep)

Requested so taking an item with E plays a sound for video 1. Added `soundPlayer.play("pickup.wav")` to `Main.java`'s E-key handler on a successful pickup, matching the existing shape of G's `destroyAt(...)` -> `doomshotgun.wav` call right below it.

Bug caught and fixed along the way: the scene-load preload loop only ever collected sounds from `kf.sound` across every instance's keyframes, so neither "doomshotgun.wav" (G) nor the new "pickup.wav" (E) - both fixed, non-scene-authored SFX triggered straight from a key handler - was ever in that set. Both would have hit the exact first-press decode-on-the-audio-thread mistiming `SoundPlayer.preload`'s own doc warns about ("not quite locked to the first frame"), every session, on whichever of G/E got pressed first. Added both filenames to the preload set explicitly.

Still missing, pre-existing (not introduced here): no `src/main/resources/sounds` directory exists in this checkout, and there is no `.wav` file anywhere in the project - same gap already flagged for `doomshotgun.wav`, now also true for `pickup.wav`. `SoundPlayer` fails soft (logs a warning, no-ops) rather than crashing, so nothing breaks, but neither sound will actually be heard until real `.wav` files are supplied and placed at `src/main/resources/sounds/doomshotgun.wav` and `.../pickup.wav`.

> **Status (2026-10-08):** unchanged: the `.wav` files are still not in the upload (section 6, item 2).

### Sandbox reset: rebuilt from the delivered archives; regression tests now live IN the repo

The sandbox filesystem was reset between turns: /home/claude/work and every test under /tmp were gone, and only the
archives delivered on Sep 25 survived - so anything not yet packaged (the previous turn's tutorial sections, the
CRATER_RADIUS_VOXELS constant, the bend write-up) had to be redone. The regression checks used to live in a scratch
directory outside the repo, which is exactly why they were lost. They now live in tools/regression/ (RegressionPrimitives,
RegressionTaper, RegressionPerVertexColor, RegressionCraterOOM) with a run.sh that compiles the project and runs them
under a 512MB heap. It needs JOML compiled from source - GitHub's JOML sources are NOT plain-javac-able: the
//#ifdef __GWT__ blocks must be stripped and the org/joml/jre and org/joml/experimental packages left out - plus the
LWJGL 3.3.3 jars.

> **Status (2026-10-08):** the four suites named here are now the full list of section 1, all run by `tools/regression/run.sh`.

### Taper voxelization clipped (then over-stretched) any shape that gets WIDER than its original box - FIXED, third attempt

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

> **Status (2026-10-08):** the taper half stands; `bend` has the same grid-extent problem and is still open (section 6, item 12).

### Taper modifier now accounted for in voxelization (DONE - part of the "modifiers not applied" gap)

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

> **Status (2026-10-08):** taper is done; `bend`, `twist`, `curve` and `shear` remain (section 6, item 12).

### Bend modifier: the inverse is exact and well-conditioned - two earlier conclusions in this file were wrong

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

> **Status (2026-10-08):** none of the four "Not done yet" items has been built; the maths stands (section 6, item 12).

### Voxel color sampled per-REGION instead of per-VERTEX (FIXED)

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

### G-key destruction logic moved behind PbdRenderer's own facade (DONE)

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

> **Status (2026-10-08):** the flow moved one step further since: into `pbd.voxel.DestructionWorld` (pure, no GPU - `PbdRenderer.destroyAt` is a thin call into it). See the journal entry "G strong enough to cut" above.

### Voxel-destruction texture crash: real root cause found this time - a DOUBLE decode, not decode timing

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

> **Status (2026-10-08):** never confirmed on real hardware (section 5, item 2; backlog item 5).

### "G-key rays pass through voxels" - not independently reproduced or newly diagnosed this turn

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

> **Status (2026-10-08):** still unreproduced (backlog item 6). What the destruction world does since is covered by `RegressionDestructionWorld`: a second shot goes through the first hole, and a shot at the remains removes more of them.

### Voxel-destruction texture crash: fourth attempt, DIFFERENT approach this time (not just harder-guarded)

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

> **Status (2026-10-08):** superseded by the double-decode finding above, which replaced this entry's cache by a read of `MaterialTextureArray`'s own pixel data.

### Modifiers (bend/taper/twist/curve/shear) are not applied during voxelization at all - confirmed architectural gap, not a quick fix

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

> **Status (2026-10-08):** partly closed: taper has been applied in voxelization since (entries above); `bend`, `twist`, `curve` and `shear` are not (section 4, backlog item 12).

### CRITICAL: non-cube primitives were catastrophically broken by an earlier "thin panel" fix (FIXED)

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

### Retarget crater could silently place the impact at the CAMERA's own position (FIXED)

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

### Architectural refactor requested, honestly deferred (not attempted this turn)

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

> **Status (2026-10-08):** done - see "G-key destruction logic moved behind PbdRenderer's own facade (DONE)" above.

### Tutorial: reuse-what-exists and quality-submission sections (DONE)

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

> **Status (2026-10-08):** `server/tutorial.php` is not in the upload, so it cannot be edited from here; draft text for new sections (lever arm, pivot, embedding, "re-use existing resources with `include_material`") is in `ADDON_USER_GUIDE.md`. The `description=` gap is backlog item 14.

### Real per-voxel texture-pixel sampling RE-ENABLED and refined

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

> **Status (2026-10-08):** the crash came back after this and the entries above chased it (decode timing, then the double decode). Sampling was kept, as instructed. Still unconfirmed on hardware.

### availableVolume/canHide facade methods added

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

> **Status (2026-10-08):** `Main.availableVolume` and `Main.canHide` exist; the two backlog entries of the same names further down are done.

### Crater OutOfMemoryError on a wider blast radius (FIXED, directly caused by last turn's own radius increase)

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

> **Status (2026-10-08):** the radius is 30 now and the crater a solid volume; `RegressionCraterOOM` (16 checks) holds the cost inside a 512 MB heap for a 0.8 cube, a 2 x 2 x 0.2 wall and at double the radius. `LongIntMap` and `VoxelCrater` point at this entry.

### destroyedRenderers/destroyedGridOrigins were never cleared on scene switch (FIXED, likely related to a separate reported N-key crash)

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

> **Status (2026-10-08):** the fix is in place (`renderer.clearDestroyed()` at scene load); `Main` also resets the lever-arm drag state there.

### Not addressed this turn (too large to rush, listed honestly rather than done halfway)

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

> **Status (2026-10-08):** these are in the backlog (section 6): the `.wav` conversion of the shotgun sound (item 2), exporting a `.pbdmat` on its own and showing a remote one (item 15), the tutorial's domain requirements (item 19).

### Raycast returning NONE despite real hits, and clicking voxel debris no longer toggling its door (FIXED - same root cause)

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

### Voxel debris was untargetable by BOTH systems that raycast against an instance's own bounding box (FIXED)

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

### Per-voxel texture sampling DISABLED (safety rollback, not fixed)

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

> **Status (2026-10-08):** superseded: sampling was re-enabled ("Real per-voxel texture-pixel sampling RE-ENABLED" above) and the crash chased further in the entries above it.

### gridOriginLocal was fundamentally miscalculated for non-cubic objects (FIXED)

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

### Voxel octree needs true anisotropic subdivision for thin geometry

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

> **Status (2026-10-08):** done by the true per-axis splitting in "CRITICAL: non-cube primitives" above.

### Reported rotation/scale bug on some primitives (not yet diagnosed)

Reported via two screenshots (a grill/BBQ scene) showing an oddly thin,
mis-oriented flat panel/blade shape. No .pbd file was attached for
this specific scene, so the exact instance(s) involved couldn't be
identified or reproduced - worth a closer look with the actual source
file next time this comes up, rather than guessed at without one.

> **Status (2026-10-08):** still no file to look at (backlog item 18, which records one possible lead).

### Crash on G-key destruction of a textured primitive (mitigated, not confirmed fixed)

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

> **Status (2026-10-08):** mitigated several times (bounds checks, a rollback, a shader cache, decoding at scene load, the double-decode fix); never reproduced here and never confirmed fixed on hardware (backlog item 5).

### The old "not yet implemented" list (every entry from here to the end of the journal carries its current status)
Not yet implemented - captured here rather than attempted under time
pressure alongside whatever else was in progress. Ordered roughly by
how tightly each depends on something else on this list.

### LOD metadata not working in Java (likely quick fix, investigate first)

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

> **Status (2026-10-08):** still open and the add-on's LOD code still unread (backlog item 13).

### Minified .pbd not loading - FIXED (filed as "bug, not yet investigated")

Reported as broken with no further detail yet - needs a concrete
repro (does the ENGINE reject a minified file outright, parse it
wrong, or something else?) before it can be diagnosed. Given this
project's own history this turn (the metadata-value-swallowing
regression in minified mode, already fixed), worth checking first
whether this is the SAME class of issue resurfacing somewhere it
wasn't fully covered, or a genuinely new one.

> **Status (2026-10-07): FIXED, on both producers.** A minified file has no line ends, and the free-text metadata (`name`, `kind`, `author`, `origin`, `description`) is read to the end of the line when unquoted - so one unquoted value swallowed the whole scene. The Java `PbdSerializer` (`quoteMetadataValue`) and the Blender add-on's own minifier (`_quote_metadata_value`) now always quote these fields in minified output. Guarded by `RegressionMinifiedMetadataQuoting` (6 checks), `RegressionFormatRoundTrip` and the add-on check. The real-world trigger was the project's own `blueMetalBoard.pbdbin`.

### canHide facade accessor

A getter (`canHide(containerInstanceName)`, naming TBD) on the runtime
facade returning whether there's enough room to hide in a given
container - built on `availableVolume` below plus the player's own
rough hitbox volume.

> **Status (2026-10-08):** **done** - `Main.canHide` (see "availableVolume/canHide facade methods added" above).

### availableVolume facade accessor

`availableVolume(containerInstanceName)` = the container's own bounding
box volume (already buildable from the existing `volume()` facade
getter added this session) MINUS the summed volume of every item
CURRENTLY placed in it (via `getContainerItems()`, also already built).
Depends on nothing further not already in place - the most
immediately buildable item on this list.

> **Status (2026-10-08):** **done** - `Main.availableVolume`.

### Door-closed facade accessor for AI/modding

A facade method so a modder's own zombie-AI code can check whether a
container's associated door(s) are closed, without needing to know
this engine's own internal `isInstanceOpen`/`isContainerOpen`
mechanics - wraps that existing mechanism, doesn't replace it.

> **Status (2026-10-08):** partly: by instance, `PbdRenderer.isInstanceFullyClosed` and - for a lever arm - the continuous value (`Main.getArmValue`); by container, `PbdRenderer.isContainerOpen` / `areAllDoorsClosed` exist (by index). A by-name accessor on `Main` is backlog item 11.

### Container refill-on-reopen logic needs rework

Current behavior: an emptied container re-rolls new random items if
its door is opened again. Reported as the wrong behavior - once
empty, should STAY empty. Fix should use `getContainerItems()`/
`removeContainerItem()` (built this session) to check REAL current
state rather than re-rolling, and the request notes this is also a
chance to better DISTRIBUTE items across a group of linked containers
(not just decide empty-vs-not).

> **Status (2026-10-08):** half done on 2026-10-08: an emptied container now stays empty when its door is opened again (`pbd.pz.ContainerStock`, `RegressionContainerStock`; section 1, item 10), for the length of a session - nothing is saved to disk. The other half of the request, a better distribution of the items across a group of linked containers, is still open (backlog item 7).

### H-key exit: record an inverse keyframe from the hide start point

When exiting a hiding spot, animate out using the REVERSE of however
the player got in, rather than however `updateHideTransition`
currently interpolates - needs comparing against whatever
`startHiding`/`startExitingHide` currently do to see whether this is a
small adjustment or a rework.

> **Status (2026-10-08):** open (backlog item 8).

### Lever-arm system (major feature, format-wide) - MVP SHIPPED + corrected + a first drag prototype, see the three Lever-arm entries at the head of this older group

The core of this was built: two authored endpoints (closed = the instance's own base pose, open = a new `leverArm` block's pose) with a continuous 0..1 value driving the motion, wired through `.pbd`/`.pbdbin`/`.pbdasset`, the Java engine, and the Blender addon. A first pass wrongly assumed that value would always be scripted (eased on a timer); corrected to also support an external driver (`PbdRenderer.setArmValue`/`getArmValue` - this IS the `getArmRotationValue()` accessor this backlog item originally asked for, built and no longer pending) once it was clear the actual goal was a mouse drag-and-drop, not a baked animation. A simple (no physics engine) geometric drag prototype was then built against that same seam. See the three top entries for what shipped, what was wrong about the first version, and how each was verified.

Still genuinely open from the original ask below, not yet built:
- Other primitives linked/attached to a lever-arm (parent-like attachment, not just synchronized open state the way `linkGroup` already does for doors).
- SFX on the arm's own motion (today only a keyframe can carry `sound=`).
- A REAL physics-driven drag, as opposed to the simple geometric one that now exists (see the two top entries) - no physics engine exists in this codebase, and integrating one (which library, real constraints vs. the current direct geometric mapping) is still an open, undecided question.
- Whether `server/upload.php` (not present in this sandbox) needs any change for moderation to handle the new block type - unknown from here.

> **Status (2026-10-08):** all of "Still genuinely open" is built and checked except a real physics engine and the server question - section 2 is the line-by-line account, against the original wording quoted there.

### Bend modifier - proper fix

Current bend implementation flagged as still wrong. Requested
approach: match Blender's own classic technique - a loop cut
(subdivision) along the affected axis (e.g. a cylinder), THEN a Simple
Deform (Bend) modifier with angle/axis - rather than whatever
approximation is currently in place. Needs comparing against the
CURRENT bend modifier's own implementation to see how far off it is
from this specific technique.

> **Status (2026-10-08):** open, and a question for you (backlog item 12).

### Web-based visualizer + documentation refresh

Eventually: a web version of the visualizer/viewer, plus a rewrite of
the index and tutorial pages, and a full pass over all existing
documentation to bring it up to date with everything else on this
list once built.

> **Status (2026-10-08):** the documentation refresh was done on 2026-10-07; the web visualizer and the rewrite of the index and tutorial pages are still open (backlog item 20).
