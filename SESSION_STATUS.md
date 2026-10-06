# Session status

Updated: 2026-10-05

M0/M1 are complete. M2 direct-mode software is implemented and verified locally, including a stock SDK and round watch emulator demo. M2 remains open until the physical Watch Ultra / real-Hermes demo passes. The phone remains a starter; M3 has not started.

Verified locally: both debug APKs and the watch instrumentation APK build; strict ktlint/Android lint/Kotlin/Gradle checks pass; 29 JVM tests pass with zero skips (including two live SDK tests); three Android Keystore tests and one opt-in setup UI test pass on Wear OS 7 / API 37. See [progress](docs/progress.md) and [synthetic screenshots](docs/demo/m2/README.md) for exact evidence and boundaries. The targeted cleartext XML lint suppression implements the approved dynamic-host choice; other lint rules remain strict.

Implemented: per-endpoint warning/private DNS enforcement, normal TLS validation, encrypted identities/settings, ordered conversation/media/action handling, guarded approvals, PCM backends, lifecycle/permissions, round scrolling, hold/release/downward discard, two-second new-session hold, reconnect without replay, and idle disconnect. The direct app is standalone. The emulator verified pairing, typed and PCM round trips, prompts/cards/images, vibration/brightness/notifications, and saved-key reconnect. Its missing timer handler was not advertised. Host mic/audio were disabled. No physical audio, STT, battery, or old Wear OS result is claimed.

CONTINUE HERE: obtain watch availability and pairing approval for the real Hermes demo. The maintainer already supplied the endpoint in conversation; never copy it into tracked files, captures, or test arguments. Their approved policy allows LAN/tailnet ws only with per-endpoint declaration and warning acceptance. Use the built wear debug APK and [self-host guide](docs/self-host.md). Do not mark M2 complete from emulator evidence or proceed to M3 before the milestone gate.

Open decisions: watch-owned TLS through an opaque phone relay versus accepting the phone as trusted; later multiplexed gateway v2. The cleartext decision is resolved. No relay or upstream server extension is implemented.

Maintainer notes, upstream checkouts, Python environment, AVD/captures/logs, downloaded toolchains, and build state remain ignored. Existing unrelated AVDs were untouched. The public demo uses synthetic server data and an original procedural grid only.
