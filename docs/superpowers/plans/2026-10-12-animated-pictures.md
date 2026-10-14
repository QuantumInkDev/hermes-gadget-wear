# Bounded animated watch pictures

> Execute task by task with superpowers:executing-plans. The maintainer approved the
> [animation contract](../specs/2026-10-12-animated-picture-design.md).

**Goal:** A selected watch page can show a user-selected GIF, with the existing PNG roster
remaining compatible and available whenever animation is missing or paused.

**Architecture:** Pure protocol validation and manifest, shared bounded hash cache and Android
decoder, phone-owned encrypted override metadata, optional Data Layer replication, selected-only
watch rendering. This does not modify a live box or its published portrait.

## Limits and invariants

- GIF input/output at most 262144 bytes; canvas at most 256 by 256; at most 120 frames.
- At most 2097152 full-canvas pixels across frames; loop at most 10000 ms after valid delays
  below 50 ms are clamped. Reject truncated blocks, invalid disposal, frame rectangles and data.
- Strip comment/application metadata; normalize repeat behavior. Android decode must also succeed.
- Retain the PNG fallback in the version-1 roster. Bind animation metadata to stable page key,
  fallback hash, GIF hash and exact length in `/hermes/animations/v1`.
- Hash assets live under `/hermes/animation-assets/v1/`; encoded cache at most 8 MiB/32 assets.
- Assets publish before manifest; old manifest assets remain protected until replacement
  succeeds. A watch tolerates either arrival order and never decodes an unmatched animation.
- Only a selected, foreground, available page animates; ambient, previews, power saver and
  system reduced motion show the static fallback. A validated pet keeps priority.
- Existing enrollment, setup, portrait records, v1 paths and encryption AAD stay unchanged.

### Task 1: Pure validation and manifest

- [x] Add original two-frame GIF fixtures and independent expected timing/frame metadata tests.
- [x] Implement `GifPortrait.normalize(bytes): GifInfo` with copied normalized bytes, width,
  height, frames and durationMillis; `GifPortrait.validate(bytes): GifInfo` checks normalized data.
- [x] Implement `AnimationDescriptor(sha256,bytes,fallbackSha256)` and `PortraitAnimations`
  encode/decode of bounded stable-key maps, including strict types, duplicate rejection and
  independent compatibility tests proving version-1 catalog decode remains unchanged.
- [x] Verify malformed/truncated/over-budget/timing/out-of-canvas fixtures before committing.

### Task 2: Phone import/storage/publication

- [x] Share bounded animation file/cache and decoder outside protocol. Import a GIF through the
  ordinary image picker, normalize it, validate Android decode, and normalize a first-frame PNG.
- [x] Save independent encrypted animation overrides in a new CatalogVault record; clear that
  page's animation on static replacement or Use box picture. Never change box artwork.
- [x] Publish hash assets followed by the manifest alongside the unchanged PNG roster; preserve
  old referenced assets on publication failure, and clean only this app's unreferenced paths.
- [x] Verify normal import, exact binding, restart, static replacement and failed publication.

### Task 3: Watch transfer/rendering

- [x] Fetch optional bounded manifests/assets from the selected phone; validate hashes and
  matching fallback/page before committing a cached animation. Keep static fallback on errors.
- [x] Extend artwork with a verified animation file; decode on a bounded IO worker and render
  an AnimatedImageDrawable clipped to the portrait circle. Stop/remove callbacks when inactive.
- [x] Verify actual frame change, pause, pet priority and incompatible/missing manifest fallback
  on an owned Wear emulator. Exercise cache bounds and malformed transfers.

### Task 4: Delivery

- [x] Run strict build/lint/format/JVM checks and the existing carousel, phone and relay regressions.
- [x] Obtain a fresh whole-branch review; fix important findings and record resolutions.
- [x] Update progress, setup/export instructions, release gates and SESSION_STATUS.
- [x] Deliver preserve-data physical updates; both installed APK hashes and signing certificates
  are verified, with watch enrollment/portraits and phone protected files/preferences unchanged.
- [ ] Measure physical GIF battery and phone STT/voice quality with owner observation.

Software verification: 105 JVM tests, 19 phone storage/directory, three phone stock TLS/vault/playback,
14 Wear render/cache/audio/surface checks and both carousel interactions pass. Review corrections
are recorded in docs/client-usability-review.md. Final physical delivery is verified; owner UI/audio/power
checks remain pending observation.
