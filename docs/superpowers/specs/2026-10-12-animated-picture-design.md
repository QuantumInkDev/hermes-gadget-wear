# Animated watch-picture overrides

Status: implemented and software-verified final stage, 2026-10-12. Physical animation
power/quality acceptance remains open; see [progress](../../progress.md).

## Contract

Accept a watch-specific GIF only after validating at most 256 by 256 pixels, 256 KiB
encoded bytes, 120 frames and ten seconds per loop. Reject zero or out-of-canvas frames,
truncated blocks and invalid timing; clamp valid very short frame delays to 50 ms.
Import through the normal photo picker. Preserve the box portrait and do not modify live
server art. Generate a static normalized PNG first-frame fallback using Android's decoder.

Keep the current version-1 roster PNG descriptor intact. Synchronize an additional versioned,
optional animation manifest and hash-addressed GIF asset. Metadata binds animation hash,
encoded length and fallback portrait hash to the stable source/profile key. A new watch
validates all limits, exact bytes/hash and association before using it. Older watches ignore
the extra Data Layer path and retain the PNG. Roster/asset ordering tolerates either arrival
order; incomplete animation transfers retain the static fallback. Source changes and
retirement remove only unreferenced local assets after successful publication.

Use Android AnimatedImageDrawable on the selected foreground page, clipped to the same
circular portrait area. Static previews and ambient mode show the PNG fallback. Pause when
screen/lifecycle is inactive, reduce-motion is enabled or battery saver is active. Do not
animate several neighboring pages. A valid selected pet retains priority over the picture.
Keep phone/watch cache bounds, worker limits and protected-asset eviction explicit. Maximum
GIF decoder frame budget is 2,097,152 full-canvas pixels across all frames (8 MiB RGBA),
in addition to the 120-frame limit; encoded and decoded limits both apply.

## Verification

Use original synthetic GIF fixtures with distinct frames; prove import, transfer, visible
frame change, pause, static fallback and old-roster compatibility. Malformed dimensions,
too many frames, oversized data, mismatched hash and withdrawn profiles must retain safe
fallbacks. Measure actual watch battery/rendering before claiming animated battery quality.
Document exact export limits and the existing sprite-pet distinction for publishers/users.
