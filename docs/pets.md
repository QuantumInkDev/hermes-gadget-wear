# Pets: verified atlas contract and missing states

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

Short rows leave transparent trailing cells. Upstream `render._raw_frames` caps the default at six columns and stops at the first blank frame; generated eight-frame rows do not imply that existing renderers use all eight. Match that six-frame default for the base sheet, trim blank tails, and use explicit companion counts (three for talking). Never animate blank trailing columns. Canonical Hermes names `wave`, `jump`, and `run` alias `waving`, `jumping`, and `running`.

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
