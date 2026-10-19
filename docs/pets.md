# Pets: verified atlas contract and missing states

Optional local metadata `gadgetBasePath` selects a separately validated Gadget base
while retaining the regular `spritesheetPath` and desktop atlas bytes. The paired SDK
and staging changes below implement the [preserving delivery plan](superpowers/plans/2026-10-18-pet-gadget-base.md).
Source review, controller integration and live/physical acceptance are separate gates.

## Approved live-pet proof of concept — 2026-10-18

The owner initially limited personal artwork to Akuma. A later explicit instruction
expands the queue **after Akuma** to Kitana, SF6 C. Viper and Cthulhu. Create the new
pets from scratch with all standard states and watch listening/talking/sleeping states.
Kitana should walk regally and cool herself with her fans while waiting. Cthulhu should
match his existing profile portrait. Supplied references and personal outputs remain
private; preserve existing artwork and profile assignments until validated replacements
are deliberately selected. Build the complete reusable pipeline. Preserve the original base atlas;
use registered listening/talking/sleeping companions and validate actual playback mouth
response and physical legibility. Per-profile Portrait/Pet selection initially preserves
the current picture/GIF, superseding older automatic-pet-priority notes below. The approved
[expansion design](superpowers/specs/2026-10-18-relay-expansion-design.md) and
[roadmap](roadmap.md) track implementation and separate acceptance.

Approved profile-page extension (2026-10-11): the [multi-box carousel design](superpowers/specs/2026-10-11-multi-box-profile-carousel-design.md)
uses each profile's cached portrait until its validated pet is available, then crossfades
to that pet. New listening/talking/sleeping poses remain a separate registered companion
sheet. Only the selected foreground page animates; personal art and visual acceptance
remain private, and no new animation delivery is claimed yet.

## Source and local evidence

Reviewed `agent/pet/constants.py`, `store.py`, and `generate/{prompts,atlas,orchestrate}.py` at Hermes commit [79af3f6cea8067284a7ea5725078578b3f790adb](https://github.com/NousResearch/hermes-agent/tree/79af3f6cea8067284a7ea5725078578b3f790adb/agent/pet). `store.pets_dir()` uses the active Hermes home, and `resolve_active_pet` uses the configured slug then alphabetical fallback. Base metadata uses `id`, `displayName`, `description`, and `spritesheetPath`; generated sheets are normally lossless WebP, so the delivery extension must transcode to PNG without discarding alpha.

A local read-only geometry survey of sheet-named files found 17 standard 1536×1872 RGBA sheets, two 1536×2288 RGBA sheets (11 cell rows), and three 96×104 RGBA images. No names, metadata, paths, hashes, or personal art are copied here. Geometry alone does not establish licensing or verify animation quality; the tiny images must not be treated as full atlases.

## Geometry and corrections

Cells are 192×208. Current sheets are 8×9 (1536×1872), legacy sheets 9×8 (1728×1664). The default loop is 1100 ms. Constants expose six stepped frames, but generator `ROW_SPECS` has varying occupied counts:

| Row | Index | Generated frames |
| --- | --- | --- |
| idle | 0 | 6 |
| running-right | 1 | 8 |
| running-left | 2 | 8 |
| waving | 3 | 4 |
| jumping | 4 | 5 |
| failed | 5 | 8 |
| waiting | 6 | 6 |
| running | 7 | 6 |
| review | 8 | 6 |

Short rows leave transparent trailing cells. Upstream `render._raw_frames` caps the default at six columns and stops at the first blank frame; generated eight-frame rows do not imply that existing renderers use all eight. Respect the six-frame maximum for the base sheet, trim blank tails, and use explicit companion counts (three for talking). Never animate blank trailing columns. Canonical Hermes names `wave`, `jump`, and `run` alias `waving`, `jumping`, and `running`.

The Gadget contract accepts one to six occupied base poses. A shorter row must contain
a complete cycle; four-pose walks leave the remaining cells transparent and advertise
four frames. Previews must use each row's actual count. Do not truncate an eight-pose
source cycle to six and assume its last-to-first transition works. Geometry checks do
not establish alternating limbs, natural gait, faithful proportions or consistent lighting;
review the moving base and all companion states against the original artwork separately.

When the owner requests edits to original pixels, retain those source cells without
whole-body resampling and record the allowed edit regions. Compare the native raster
alongside the original before evaluating watch-size samples. A shared character palette
must cover every state, including error and companion rows; restoring an original row
can reintroduce a previously removed lighting defect. Palette membership and unchanged
pixels are measurable gates, but do not replace visual review of joints and movement.
Fixed appearance details must not be recalculated into a new shape for every frame.
Shared palette membership also does not establish matched exposure; compare the actual
base and companion lighting, and avoid darkening an already corrected source again.

`state_rows_for_grid` selects Codex rows for **any row count >= 9**, otherwise legacy rows. Thus adding 11 or 12 rows does not itself switch to a wrong taxonomy in this function; extra rows are unnamed and unavailable through normal state lookup. Existing consumers do not share a contract for them. Preserve the base sheet and use a companion rather than extending row count.

Hatching calls `compose_atlas(normalize_cells(frames_by_state))` once. Normalization aligns body column profiles and baselines, computes a global motion-envelope cap `K`, then scales each state by `K / median_pose_height`. This is shared normalization, not literally one identical scale factor for all input rows. A later independent pass lacks the original cap and registration, so it can create visible size/baseline jumps.

## State mapping and fallback

| Gadget state | Current row | Companion / fallback |
| --- | --- | --- |
| ready | idle | idle |
| newly paired or connected | waving | canonical legacy wave |
| thinking / tool status | running | canonical legacy run |
| transcript | review | idle if absent |
| approval | waiting | review if absent; legacy waiting falls back to idle in upstream lookup |
| successful end | jumping once | canonical jump, then idle |
| failed end / connection or auth error | failed | idle plus explicit error text if absent |
| microphone open | missing | listening, else review |
| audible playback | missing | amplitude-selected talking, else idle with subtle amplitude bob |
| offline | missing | sleeping, else static idle |
| ambient | procedural | low-power outline from idle; no generated row |

The dedicated SDK built-in talk face confirms speech needs its own expression. It is not a reusable licensed sample pet for the store listing. Listening needs an attentive pose; working-in-place and waiting-on-user convey different semantics. Talking needs three mouth shapes selected by playback amplitude, with hysteresis and silence returning to closed mouth.

## Companion contract and prompt text

Propose optional `gadgetSheet` metadata in `pet.json`, naming a separate `gadget-sheet.png`, explicit row names, counts, and 192×208 cells. This is a proposal, not existing metadata. Never alter `spritesheetPath` or normal atlas row count. Renderers without the extension continue reading the base sheet.

STATE_ACTIONS-style descriptions:

- **listening (6 frames):** attentive listening toward the viewer; slight ear/head tilt and tiny blink; feet, body center, clothing, props, size, and baseline fixed; no speaking mouth, walking, or detached sound effects.
- **talking (3 frames):** identical attentive front-facing character and fixed body in all frames; mouth closed, half open, then open; only the mouth and a minimal head expression change; no gesture, sway, moving clothes, or scale change. These are amplitude poses, not an automatic looping animation.
- **sleeping (6 frames):** peaceful asleep pose with closed eyes and subtle slow breathing; keep center, baseline, and proportions fixed; no floating letters, bubbles, floor shadows, or detached effects.

All prompts retain identity, palette, transparent/chroma-key background, separate cell gutters, full silhouette, and fixed registration from upstream's row builder.

## Existing-pet extension prototype at M5

Measure the median nonempty alpha bounding box and baseline from the base idle row (ignore alpha <=16). Extract new row poses, use one reference registration for the row, rescale with nearest-neighbour to the existing idle character height while preserving aspect ratio, and align the baseline/body center. Reject empty frames, clipping, row drift, and extreme scale changes. Do not stretch every pose to an independent width/height or normalize the new sheet as a fresh hatch. Test with original synthetic sprites; inspect personal art only locally. Different poses/accessories make bounding-box matching imperfect, so human side-by-side review remains necessary.

The better upstream path generates optional extra rows in the same hatch normalization pass, then packs base and companion separately. It needs explicit pack row specs rather than calling today's fixed `compose_atlas` for the companion.

## Delivery and renderer gates

`pet.request`, `pet.manifest`, and binary asset channel 4 are implemented in opt-in SDK patch 2; upstream adoption remains proposed. Stock servers ignore the request and the watch uses its original placeholder; never wait indefinitely. Authenticate delivery, enforce compressed/decoded byte and dimension limits, reject malformed rows, validate SHA-256 before cache promotion, and cache per endpoint/hash with a quota. Never use the slug as an unchecked local path. Support absent sheets, missing states, legacy aliases, opaque/invalid assets, interrupted transfer, and expired profile selection. Hardware checks must assess small round-screen legibility and ambient power before M5/M8 completion.

## M5 implementation and reproduction

SDK patch 2 (`2bcdfc7`, local branch `wear/optional-extensions`) adds disabled-by-default
`enable_pets`, current-profile selection, path/symlink confinement, alpha-preserving
PNG conversion, occupied frame counts, bounded paced channel-4 delivery, and protocol
documentation/tests. The watch requests after pairing, validates metadata, sequence,
length and hash, and checks PNG bounds before decoding on a bounded worker. Its private
cache uses endpoint/hash directories, an 8 MiB global quota and 64-file limit. Prepared
96×104 frames use nearest-neighbour scaling; no image decoding occurs on the UI thread.

The renderer uses base/legacy aliases and companion fallbacks above. Completed turns
emit one jump/failure cue; pairing and the new-session gesture emit a short wave.
Talking selects three poses with amplitude hysteresis (PCM buffered for AudioTrack,
not microphone amplitude). Missing talking uses idle plus amplitude bob. A procedural
amber robot is the original stock-server placeholder. Offline stays static.

Extend existing art with Pillow installed:

```sh
python3 tools/extend-pet.py --base /path/to/base.webp \
  --companion /path/to/generated-companion.png --output /path/to/gadget-sheet.png
```

Input companion: 1152×624 RGBA, three rows listening/talking/sleeping, left-packed
6/3/6 occupied frames. The tool prints a proposed `gadgetSheet` metadata fragment for
review; it never edits pet.json or the base/input sheet. One scale/registration per row
matches median idle height, body center and baseline while preserving aspect ratio and
relative pose movement. Empty/clipped/drifting/extreme poses fail. Human review remains
necessary for accessories and unusual sleeping poses; no generation provider is wired.

Validation: 46 JVM tests pass (zero failures/errors/skips). The clean exported patch
series passes 29 Python tests including pet delivery, stock/Opus compatibility and two
tool tests. API 37 pet delivery/cache and cache-corruption instrumentation passed; the
stock SDK draft regression still passed. Strict build/lint/format/warnings checks pass.
[Synthetic round-screen capture](demo/m5/README.md). Personal pet transfer, Ultra
legibility, physical lip sync and ambient power remain unverified.

## Carousel presentation — 2026-10-11

Each stable page chooses its validated endpoint pet, last good cached portrait, then the
original neutral robot. The portrait can be the imported box image or an explicit per-profile
watch override selected on the phone. An override survives box refreshes without changing
the box's original image; a validated endpoint pet still takes priority over either portrait.
Portraits occupy 160×160 dp inside a fixed 160×168 dp frame; pets retain a centered
96×104 dp canvas. A 220 ms crossfade keeps the identity text in place.
Only the selected available foreground page animates;
neighbor previews and offline/ambient remain static. Existing wave/work/jump/error cues,
legacy fallback and amplitude mouth hysteresis are retained. Ambient chooses the selected
page's pet, including a neutral outline for an unapproved card, rather than a previous
conversation's pet.

[Original three-color fixtures](demo/carousel/fixtures/manifest.json) include unchanged
1536×1872 base sheets plus registered 1152×624 listening/talking/sleeping companions.
The renderer test rejects corrupt pet bytes while retaining a portrait, checks identity
placement through arrival and compares actual moving/frozen rendered frames. Actual-service
base-only and companion delivery tests pass; the three-page test checks endpoint artwork
isolation and real synthetic listening. [Captures and reproduction](demo/carousel/README.md).
These are synthetic software checks; owner artwork, physical lip sync and power remain open.

## Watch GIF overrides — 2026-10-12

A user-selected GIF is an optional portrait loop, independent of endpoint pet delivery.
It has a static normalized PNG fallback in the original version-1 roster. The extra manifest
binds the animation and fallback hashes to the stable box/profile key. Import limits are
256×256, 256 KiB, 120 frames, ten seconds per loop after clamping short delays to 50 ms,
and 2,097,152 full-canvas pixels across frames. See [picture setup](profile-setup.md).

Only the selected available foreground page runs the loop, including a reviewed catalog
card before endpoint enrollment. Neighbor previews, reduced motion and power saver use
the PNG. Ambient receives that static portrait and displays a dim grayscale circle, or a
64×64 sparse black/white rendition for low-bit displays, under the existing minute shift.
A pet idle outline retains priority. No GIF decoding or animation occurs in ambient mode.
These rendering rules do not establish physical watch battery cost or lip-sync quality.

## Optional talking expression overlays (v1)

A companion `talking` row with exactly three occupied poses may explicitly declare:

```json
{"name":"talking","index":1,"frames":3,"overlay":{"anchor_frame":0,"rect":[80,100,112,124]}}
```

This synthetic example uses half-open integer cell coordinates in 192×208. The rectangle
must be nonempty, inside the cell, at most 96 pixels wide and 104 high. Anchor is exactly
integer zero. Booleans, extra descriptor fields, other states/kinds/counts and malformed
rectangles are rejected. No path, mask or shader is supported. Listening and sleeping
retain full-frame semantics; ordinary pets remain unchanged.

Updated Wear clients advertise `caps.pet.expression_overlays: 1`. The optional SDK extension
preserves the validated descriptor with the original PNG hash and omits dependent rows
for every other capability value (including boolean true). Ordinary base and full-frame
companion rows remain available. A companion with no remaining rows is omitted entirely.
The private SDK change awaits independent review and controller-owned patch export.

During cache decode, each talking pose replaces the rectangle of pose zero with its own
source pixels, including transparent pixels. Composition precedes nearest-neighbour scaling
to 96×104, so contain scaling retains the same geometry and sampling as ordinary sprites.
The original decoded atlas and PNG bytes are never edited; only three prepared in-memory
frames are retained. Existing endpoint/hash validation, playback amplitude hysteresis,
drain/stop callbacks, static/reduced-motion/power-saving and ambient policies apply.

Staging explicitly opts in with `tools/pet-companion.py --expression-overlay LEFT TOP RIGHT
BOTTOM` in **registered output cell coordinates**. This is mutually exclusive with the
unchanged strict full-frame `--mouth-box` gate. The private receipt records raw registered
full-frame and outside-region differences, three distinct nonempty region samples,
`requires_expression_overlays=1`, and unproven SDK negotiation. This is staging evidence;
human mouth/identity/props/seam/reduced-size review and actual negotiated delivery/playback
must pass separately before a bundle is deliverable. Raw generated inputs and original
base files remain unchanged. This extension does not establish artwork or physical O2
acceptance.

## Optional Gadget base without replacing desktop art

With the corresponding optional SDK producer, a local `pet.json` can contain:

```json
{
  "id": "sample",
  "displayName": "Original sample",
  "spritesheetPath": "desktop/regular.webp",
  "gadgetBasePath": "gadget-base.png"
}
```

Hermes still loads `desktop/regular.webp`. Gadget packaging selects `gadget-base.png`
and uses the existing base validator and ordinary manifest/channel-4 sender. The regular
file and its row count stay untouched. The alternate can have different artwork; it must
meet the same geometry, alpha, occupied-row, PNG and hash requirements. There is no new
wire field or client capability. Existing companion ordering and expression-overlay
negotiation remain unchanged. A producer without this refinement ignores the local field
and continues to package the regular atlas; it may still refuse an oversized regular PNG.

The field is optional. When absent, packaging retains its previous base selection.
When present, it must be a nonempty relative string of at most 512 UTF-8 bytes. Invalid
Unicode, NUL, absolute/drive/UNC paths, traversal in either POSIX or Windows spelling,
missing files and symlinks escaping the pet directory refuse the package, with no fallback
to the regular sheet. Metadata is parsed once and capped at 64 KiB; each source remains
bounded at 8 MiB, each delivered PNG at 2 MiB and each decoded sheet at 3.2 million pixels.
Invalid pet assets leave conversation handling independent of pet availability.

Stage a fresh bundle using separately prepared inputs, outside every source directory:

```sh
python3 tools/pet-companion.py \
  --metadata /work/source/pet.json \
  --companion /work/poses/companion.png \
  --gadget-base /work/prepared/watch-base.png \
  --output-dir /work/staged/sample
```

The optional trailing Python argument is `build(metadata, companion, output_dir,
mouth_box=None, overlay_rect=None, gadget_base=None)`. With an alternate, the stager keeps
the exact regular `spritesheetPath` (including subdirectories), copies its bytes unchanged,
copies the alternate to a distinct `gadget-base` filename with its source suffix, and
registers the companion against that selected Gadget base. It does not resize or repaint
either base. The receipt records all input and output hashes, delivery hashes, registration
and the existing pending acceptance gates. Source hashes and copied/generated output hashes
are rechecked; a failure removes the new staging directory and preserves the inputs.
The receipt's `outputs` indexes the bundle files other than `manifest.json` itself.

Output filename collisions, including case aliases and file/directory conflicts, refuse
before copying or writing. Existing outputs are refused. Alternate staging requires output
outside every source directory; legacy no-field calls retain their outside-source-pet
placement rule. Metadata already declaring `gadgetBasePath` requires the explicit argument,
even when it names the regular file; without it staging refuses before creating output.
With it, a syntactically valid old reference may be replaced (it need not still exist), and
the fresh metadata names the alternate actually copied. Malformed metadata is rejected.
No-argument staging of metadata without the field keeps the legacy bundle layout and API.
`--prepare-grid` still accepts only `--prepared-output`; it cannot combine with `--gadget-base`.
Existing `--mouth-box` or `--expression-overlay` gates apply in registered output coordinates.

Tests use original synthetic art, actual SDK packaging/sending, and an explicitly supplied
isolated Hermes `load_pet` source with synthetic configuration. They do not establish live
installation, personal artwork approval, native playback, physical lip sync or power cost.
