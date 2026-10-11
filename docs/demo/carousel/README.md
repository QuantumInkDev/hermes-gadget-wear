# Synthetic multi-source watch carousel

Original robot portraits/pets from [the generator](../../../tools/carousel_fixtures.py),
three separate SDK processes, and the owned API 37 round watch emulator. Duplicate names
remain distinct via their source label. No personal artwork, endpoint, key or conversation
is included. The [fixture manifest](fixtures/manifest.json) records hashes and sizes; each
base sheet remains unchanged while `tools/extend-pet.py` registers its companion rows.

| Capture | Observation |
| --- | --- |
| [First portrait](01-portrait-first.png), [second](02-portrait-second.png), [third](03-portrait-third.png) | Separate settled pages and original cached pictures |
| [First pet](07-pet-first.png), [second](08-pet-second.png), [third](09-pet-third.png) | Validated endpoint-scoped pets replace portraits |
| [Listening](10-listening.png) | Selected foreground companion row during real synthetic AudioRecord capture |
| [Unfinished review](04-review-before-ambient.png), [ambient](05-ambient.png), [wake](06-review-restored.png) | Setup text survives actual ambient composition replacement |

The instrumentation checks real horizontal swipes, accessible Next and vertical/rotary
scrolling; recording and approval lock switching. Three distinct device identities and
per-endpoint unsent drafts remain separate without replay. A renderer test checks stable
name placement through pet arrival, rejected pet preserving a portrait, and moving selected
versus frozen neighbor frames. Companion and legacy actual-service tests verify delivery,
endpoint cache isolation, state fallbacks and 6/3/6 registered rows. These tests do not prove
physical lip sync, Bluetooth throughput or battery usage.

Build both debug/test APKs first. Set the stock and optional-extension Python paths using
the README setup commands, then run only against the owned Hermes_M2 AVD:

```sh
python3 tools/carousel-demo.py --adb /path/to/sdk/platform-tools/adb \
  --adb-port 5041 --serial emulator-5570 --reset-owned-emulator \
  --pets --captures docs/demo/carousel
```

The runner explicitly resets only that owned AVD's test app, rejects physical devices,
starts ephemeral synthetic servers, and removes its port reversals on exit. `--pets`
requires `HERMES_GADGET_EXTENSION_PYTHON`; omit it for stock servers, or use `--legacy-pets`
for the original base-only pet fixture. `--test` selects another instrumentation class.
New phone/watch catalog Data Layer exchange and the real three-box rollout remain hardware
acceptance gates; this demo seeds the watch's isolated cache rather than claiming that path.
