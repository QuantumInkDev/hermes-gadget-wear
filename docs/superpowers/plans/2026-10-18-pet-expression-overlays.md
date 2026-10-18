# Registered talking overlays for generated pet expressions

Status: focused O2 follow-up plan. Execute after reviewed watch presentation and O5 SDK
integration so source ownership is sequential. No asset or runtime acceptance is claimed.

## Problem and rendering contract

Independently generated talking poses can retain almost identical geometry while their
body shading changes slightly. An exact whole-body pixel comparison correctly rejects
those full-frame poses. Repeated redraws have not produced exact shared-body pixels.
Preserve that full-frame rejection and all original assets.

For an explicitly declared talking overlay, the native renderer instead holds the closed
talking pose as its body anchor and replaces only a reviewed cell-space mouth rectangle
with the selected generated mouth pose. The final rendered body is then exactly constant.
This is runtime sprite composition; raw generated PNGs and the original base remain
unchanged. It is not permission to paint/composite replacement artwork in Python or waive
an existing full-frame gate. Listening/sleeping remain independently reviewed full poses.

## Global Constraints

- Preserve originals, hashes, endpoint isolation, bounded asset decoding and stock behavior.
- Personal art, selected mouth coordinates and receipts stay private; public fixtures synthetic.
- No floating UI, icon or control covers the watch artwork. Keep current recording target, rings,
  haptics, swipes, scroll, Portrait default and final Jump footer.
- Mouth selection follows actual native playback amplitude with existing hysteresis; no
  timer-driven speech, provider call, automatic replay or animation while offscreen/ambient.
- No live/profile/device/provider changes by implementers. Controller owns reviewed rollout.

## Task 1: Optional manifest metadata and deterministic native rendering

Depends on reviewed O1 watch selector and O5 SDK bridge. Own focused SDK pets.py/tests,
protocol Pets.kt/tests, watch PetCache/renderer tests, staging-tool opt-in and documentation.
Avoid PhoneConversation/MainActivity, outbox/core code and public personal art.

- [ ] Add an optional `overlay` to the companion talking row only, with exact shape
  `{ "anchor_frame": 0, "rect": [left, top, right, bottom] }`. Coordinates are integers
  in the declared 192×208 cell, with `0 <= left < right <= 192`,
  `0 <= top < bottom <= 208`, width at most 96 and height at most 104. Reject booleans,
  extra fields, wrong row/kind/frame count/anchor and malformed rectangles. Talking still
  has exactly three occupied poses. No arbitrary mask file/path, shader or expression.
- [ ] Preserve this validated descriptor through SDK metadata packaging, protocol parsing,
  exact-hash cache manifests and endpoint-specific decode. Advertise
  `pet.expression_overlays = 1` only in updated clients. For clients without that exact
  capability, omit overlay-dependent rows from the sent manifest; retain ordinary base
  and supported full-frame companion rows. Do not silently render a dependent row as an
  approved full-frame animation. Disabled/stock path is unchanged.
- [ ] Compose derived in-memory talking bitmaps from anchor frame zero and the selected
  rectangle of each generated frame, replacing pixels inside the rectangle and preserving
  every pixel outside it exactly. Preserve alpha, contain geometry and pixel sampling;
  do not mutate source bitmaps or persist a rewritten art file. Bound allocations to the
  existing three frames and recycle temporary bitmaps according to existing cache ownership.
  Invalid or missing assets retain base/portrait fallback and never another profile's art.
- [ ] Keep current playback mouth thresholds and actual drain/stop semantics. Silence,
  reduced motion, Battery Saver, ambient and offscreen pages use closed/static output.
  No extra animation loop or microphone action is introduced.
- [ ] Extend staging with an explicit overlay mode/descriptor. Existing full-frame
  `--mouth-box` remains strict and unchanged. In overlay mode, receipt records the raw
  full-frame difference honestly, the reviewed region and `requires_expression_overlays=1`;
  verify all three region samples differ and include no empty/clipped pose. Do not label
  source whole-body match passed. Human review still verifies that the region is a mouth,
  identity/props, seams and reduced-size motion. SDK negotiation must be proven before
  a staged overlay bundle is described as deliverable.
- [ ] Write failing tests with synthetic poses whose bodies deliberately differ outside
  the region. Prove source bytes/bitmaps unchanged; all rendered outside-region pixels
  equal anchor; three distinct mouths; correct rectangle mapping at contain scales;
  malformed metadata fails; old clients receive fallback; late endpoint/cache callbacks
  cannot mix anchors. Existing unmasked pets and staging tests remain green.
- [ ] Run strict affected SDK/protocol/native tests and Wear builds on an owned emulator,
  with source-bound screenshots of each derived pose and a captured playback sequence.
  Fixtures use actual codec/playback callbacks with synthetic audio, not a decorative
  timer. No personal captures in public artifacts. Independent review gates integration.
- [ ] Export the SDK pet extension separately after the existing reviewed patches, reapply
  to a fresh stock checkout and prove capability-off compatibility. Controller owns export,
  public shared docs and preserving device delivery. Report exact tests/receipts/limits.

## Task 2: Akuma candidate and subsequent pet production

Depends on Task 1 review and actual render inspection. Private art only.

- [ ] Keep all previous failed full-frame builds and their failure measurements. Stage a
  new versioned Akuma candidate with original base unchanged, reviewed listening/sleeping
  identity and validated talking overlay. Do not relabel a rejected bundle in place.
- [ ] Compare original/companion identity and props, all loop seams and rendered mouths at
  native/reduced size. Reject missing necklace/props, clipping, texture seams or scale pops.
  Actual updated SDK/watch transfer and playback-driven mouths must pass before O2 complete.
- [ ] After Akuma's production pipeline/art stage is accepted, create Kitana, SF6 C. Viper,
  then Cthulhu from the private reference queue. All standard Hermes states plus registered
  watch listening/talking/sleeping states are required. Kitana's walk is regal and waiting
  cools her with fans. Cthulhu matches the current profile portrait. Preserve every existing
  profile assignment until a validated deliberate selection; no unreviewed overwrite.

## Controller ruling

Keep exact fixed-body behavior at the rendered boundary rather than spending unbounded
generation calls trying to produce byte-identical independently drawn bodies. The raw
strict comparison continues to fail where appropriate. Cost if wrong: a small optional
manifest/cache extension and visible mouth seams require rework before activation; no
current pet or enrolled client is replaced by this plan.
