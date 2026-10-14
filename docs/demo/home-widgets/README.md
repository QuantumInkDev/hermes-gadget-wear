# Phone home widgets: native evidence

[Interactive native gallery](index.html) — seven layouts × four appearances, using
original public fixture robots. The dimensions are **rows × columns**; 1×4 and 1×5
are horizontal. Captions and portraits are separate; every cell opens the exact bot.

`HomeWidgetHostTest` binds real provider IDs, sends RemoteViews through AppWidgetManager,
inflates them in AppWidgetHostView and measures 112 small/large, portrait/landscape,
font 1.0/1.3 and LTR/RTL cases. Host external padding is excluded explicitly. Raw native
view exports are 28 PNGs; `host-screen.png` is the unretouched secure-free synthetic
debug host screenshot. No production phone window or personal art is captured.

The native service accepts the sixteen-picture adaptive object; all size variants share
16 decoded bitmap objects after parceling, within 1 MiB and the screen-derived ceiling.
Missing/corrupt art falls back per cell; withdrawn/empty targets route to configuration.
All fixed/system caption contrasts measured in these host renders are at least 4.5:1.

[Manifest](manifest.json) binds every PNG's dimensions/SHA to the source commit and APKs.
The captures are native host content, not a claim of Samsung One UI placement.

The emulator could not provide a preservable current wallpaper, so the capability probe
made no change. Real two-wallpaper palette changes remain unverified. System night
resources are compared with Android's actual DeviceDefault day/night accent. The same
hosts also rebuild their previously published RemoteViews across an actual OS night
change: System surface/text/accent change, every fixed palette and app appearance stay
unchanged, and the original OS mode is restored. API 29–30 runtime is unavailable; fallback resources and lint
pass, but older-device acceptance remains open. Physical placement, exact taps, five-column
grid support and reboot acceptance await the phone/owner.

To add: long-press the launcher → Widgets → Quantum Relay for Hermes → choose a preset.
Choose slots, use Move earlier/later to order them, pick an appearance and Save widget.
Reconfigure through the launcher or Settings → Home screen widgets. Widgets use the
current effective portrait's static PNG fallback; animation remains in the apps.
