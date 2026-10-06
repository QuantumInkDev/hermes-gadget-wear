# Session status

Updated: 2026-10-06

M0/M1 complete. The core M2 physical Ultra demo passed: automated real-Hermes typed round trip, followed by maintainer-reported weather/STT/reply/ElevenLabs playback. The maintainer found the flow clunky and authorized continuing M3–M8 while away/off the home network. Remaining Ultra action/media, permission/lifecycle, audio-quality and battery checks stay open.

M3 software is implemented: watch-owned TLS through an opaque ChannelClient phone relay and loopback CONNECT bridge; Automatic/Phone/Direct override; same-endpoint network fallback without input replay; last-reply preservation; opt-in companion, stop controls, connected-device foreground service, Android companion association. Relay cannot use ws and certificate/authentication errors do not trigger downgrade. The watch connects on app open and idles out as before. Actual Data Layer, background startup, Bluetooth interruption, phone VPN and off-network conversation are unverified. See [progress](docs/progress.md), [architecture](docs/architecture.md), and [self-host setup](docs/self-host.md).

Verified: strict full Android/JVM build, lint, format, warnings-as-errors, both debug APKs and watch instrumentation APK; 38 JVM tests, zero failures/errors/skips, including actual stock SDK direct and relay tests. API 37 Android loopback TLS test rejects untrusted certificates and accepts test-injected trust. Existing M2 Keystore/setup/draft emulator results are recorded in progress; no personal-watch reset tests were run. M3 commit `210bbcc` was pushed; [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37465546979) passed.

Maintainer TLS infrastructure is configured privately: tailnet-only Serve HTTPS proxy on the existing allowed Gadget port, Funnel disabled, backend unchanged. Certificate/hostname validation and the real WebSocket upgrade passed using default trust without sending enrollment/conversation. Read-only live ACL verification confirmed the existing own-device grant and no Gadget port in box-to-box access; no policy edit made. Actual phone access remains unverified. Private files are under `.local/tailscale/`; never print or track them or the `.hermes/.env` credential. The personal watch's saved LAN profile remains unchanged. A new TLS URL needs its independent endpoint identity/pairing.

M4 optional Opus is implemented with per-direction selection, stock PCM fallback, platform probe, MediaCodec capture/playback, unified CSD extraction and raw 20 ms packets. The isolated SDK patch has real ffmpeg pipes, bounded Ogg wrapping, paced output and protocol docs. [Measurements](docs/audio-measurements.md) are synthetic software counts only. Strict app checks/42 JVM tests, three Android codec/TLS tests, the stock draft regression, and 21 Python patch/compatibility/live-WebSocket tests passed. The live Hermes environment remains unchanged. The opt-in service-level Opus emulator demo also passed: actual AudioRecord capture, negotiated Opus upload, SDK loopback response/playback path, encoder flush on release and recording cancellation. Host microphone/speaker were disabled; this does not measure physical quality. The patch series was exported from `9c400f9` on local SDK branch `wear/optional-extensions`. No upstream PR/review is claimed.

M4 commit `82a206b` was pushed; [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37468005261) passed all three jobs. M5 pet delivery, cache/renderer/state fallbacks, original placeholder and registration tooling are implemented. Strict app checks and 46 JVM tests passed with zero failures/errors/skips. The exported SDK patches (Opus `9c400f9`, pets `2bcdfc7`) applied to a fresh pinned checkout; 29 Python SDK/tool tests passed. Two Android pet delivery/cache/corruption checks and the stock draft regression passed on the owned emulator. [Pet docs](docs/pets.md) and [synthetic capture](docs/demo/m5/README.md) record limits. Real-profile pets, Ultra legibility/lip sync and ambient power remain open.

M5 staged-export gitleaks, personal-value and local-document-link checks passed.

M6 phone BYOK shared/per-endpoint vault, quota checks, native Opus/PCM streaming, cancellation and server negotiation are implemented. Strict full checks passed: 53 JVM tests, zero failures/errors/skips, 31 Python tests from a fresh three-patch export, actual owned-phone Keystore and owned-watch native Opus decode instrumentation. Stock-readable replies and optional client speech are checked against actual SDK processes. No paid provider call, personal key or live gateway extension was used. Provider output/billing/voice quality and Data Layer remain open.

M6 `ac329d1` CI passed all jobs. M7 v1 encrypted endpoint catalog, independent keys, migration/removal, phone setup/import review and media teardown are implemented. Strict checks passed with 56 JVM tests, zero failures/errors/skips. Actual two-stock-server tests, five owned-watch vault tests, one owned-phone vault test and actual watch-service switching during recording/reply clearing/reconnect/removal passed. Actual Data Layer import, swipe/rotary and physical profile media remain open. See docs/profiles-v2.md; v2 remains proposal-only.

M7 `009b174` CI passed all jobs. M8 watch Tile, generic complication, sparse ambient outline,
compact conversation controls, service-owned drafts and foreground release after turn/audio
are implemented. Strict checks, both debug/instrumentation APKs and both unsigned release
bundles passed with 56 JVM tests and zero failures/errors/skips. Eleven owned-watch device
tests passed (six vault, two surfaces, lifecycle, draft, profile); final compact-layout draft
regression passed again. Actual Tile render/tap/process-death excerpt, system ambient/wake and
normal/1.3 font captures are in docs/demo/m8/. Official bundletool 1.18.3 validated both unsigned
bundles; package/SDK/standalone/version codes/signing absence were inspected. CI publishes
review bundles and debug apps. Original store art and privacy/Data Safety/listing/signing
checklists are prepared. No signing credentials or Play upload access was supplied.

CONTINUE HERE: real-device/publisher gates in docs/release.md. M8 is software verified, not
battery or Play accepted. Test real relay/Data Layer, profile sync/background/VPN, actual
provider voice/billing, physical Ultra audio/STT/legibility/pet/ambient power, Tile/complication
and button mapping; then supply signing/publisher/privacy/Play setup and perform internal-track
installation. Do not deploy the optional server patches into live Hermes without their review.

The maintainer authorizes advancement with open hardware gates. Do not mark Ultra throughput/STT/quality/battery or actual Data Layer complete. Multiplexed gateway v2 remains proposal-only pending explicit DECISION and upstream-maintainer feedback; no maintainer messages have been authorized.

Physical pairing approval was already fulfilled. Private ADB/settings/captures remain ignored; screen timeout was restored. Use only the owned Hermes test AVD for local checks and exact serials. Existing unrelated AVDs remain untouched. The public demo uses synthetic data and original procedural artwork. No PR has been created.
