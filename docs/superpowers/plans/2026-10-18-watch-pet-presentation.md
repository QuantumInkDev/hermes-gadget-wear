# Per-profile watch portrait and pet presentation plan

> Focused O1 client increment under the approved expansion design. Implement and
> independently review before enabling live pet delivery.

**Goal:** Each watch profile deliberately selects Portrait or Pet, preserving the
existing pictures/GIFs initially. Receiving a real pet must not unexpectedly replace art.

## Global Constraints

- Existing portraits/GIFs and independent enrollments remain unchanged.
- Default every existing and new profile to Portrait. No source art generation or edits.
- Preserve clear artwork, portrait hold/release/cancel, outside recording rings, current
  haptics, horizontal swipes, stable scrolling and final Jump to profile control.
- Only the selected foreground artwork animates; reduced motion, Battery Saver and ambient
  retain static fallbacks. No new controls float over artwork.
- Scope cached art and preferences to the exact profile; never substitute a same-name bot.
- Keep personal endpoints/artwork/receipts ignored under `.local/`; public fixtures synthetic.
- No installed server/provider/physical operations by the implementer. Controller owns rollout.

## Verified source seam

`ProfileCarousel.ProfileIdentity` currently selects any cached/current pet before a
portrait. Therefore enabling live pet delivery as-is would override the owner's GIFs.
`GadgetService` already caches bounded per-endpoint PetAtlas and advertises pet assets;
`ProfilePages` gives stable page keys and `PageArtwork` holds both portrait/pet assets.
`PetView` renders the atlas with playback-driven mouth selection and existing fallbacks.
`WatchNavigation` owns persistent page scroll. Preserve those ownership boundaries.

## Task 1: Local durable mode and native selector

Owned files: new focused watch presentation store/model and tests; focused
`GadgetService.kt`, `ProfileCarousel.kt`, `PetView.kt` and watch conversation/footer UI
integration; affected native tests. Avoid phone/MainActivity and unrelated protocol work.

- [ ] Introduce Portrait/Pet typed preference with default Portrait and a bounded private
  durable watch store. Key by stable profile page identity plus reviewed endpoint identity
  where present; never put keys/URLs into logs or toString. Validate at most 64 entries,
  use atomic replacement and publish only after successful persistence. Corrupt storage
  preserves bytes and reports an actionable failure rather than silently saving over it.
- [ ] Restore mode after process recreation and maintain independent choices while swiping.
  Recycled/removed catalog entries cannot apply another profile's preference. Do not prune
  offline profiles or delete their cached art/keys. Updates during a locked turn apply
  only to the same mounted profile; changing mode must never select/connect/send a bot.
- [ ] In Portrait mode, render the effective existing GIF/static override first. If no
  portrait exists, show an honest static placeholder with an option to choose Pet; do not
  silently change the stored choice when new art arrives. Pet mode uses the exact profile's
  current/cached real atlas. Missing/unsupported pet falls back to retained portrait/static
  placeholder and a concise explanation. Never label the procedural sample as a real pet.
- [ ] Put the accessible Portrait/Pet selector in the card footer/settings area, before the
  final Jump control, preserving the unobstructed first view. Show the current selection
  clearly with native controls and theme tokens. No previous/next buttons or artwork badges.
- [ ] Keep the original artwork/recording target geometry. If extending PetView to fit the
  same round surface, preserve frame aspect ratio with contain scaling and transparent
  margins; no clipping or stretch. Hold-to-talk/rings/haptics must work for Pet and Portrait.
- [ ] Keep animation eligibility tied to selected foreground state. Offscreen cached pets,
  static thumbnails, reduced motion, Battery Saver and ambient must not tick an animation
  loop. Do not change fallback state taxonomy or manufacture talking companion art here.
- [ ] Write failing behavioral regressions for receiving pet while default Portrait is
  selected, independent persistence/process restart, missing pet fallback, late old-profile
  atlas callback, unreadable/corrupt store preservation and successful-write publication.
- [ ] Verify native portrait/pet choices, long labels and large fonts in Light/Dark/Moonlight
  on an owned watch emulator through device_list/device_open. Exercise both recording
  surfaces using existing synthetic audio/server fixtures, stable vertical scroll and swipes.
  No personal fixtures, reset, enrollment replacement or use of another worker's AVD.
- [ ] Run targeted JVM/native checks and strict wear APK/instrumentation builds; record
  commands/counts/skips and synthetic captures in
  `.superpowers/sdd/2026-10-18-watch-pet-presentation/task-1-report.md`. Commit exact paths,
  no push. Independent review precedes actual live-profile pet rollout and device delivery.

## Preflight

| Task | Interface/consistency | Ruling |
| --- | --- | --- |
| 1 | Existing pet-first ProfileIdentity vs approved preserve-picture default | Explicit mode owns selection; incoming/cached pets never change that preference. |
| 1 | Shared picture/pet gesture and geometry vs PetView's fixed small canvas | Adapt contain scaling at this seam; preserve recording gesture on the outer artwork. |
| 1 | Offline artwork retention vs bounded preference store | Do not garbage-collect offline profiles; refuse a new entry at capacity with an honest error. |

This increment does not deliver a live server pet or accept Akuma companion artwork.
Its purpose is to make that later rollout safe for the owner's current presentation.
