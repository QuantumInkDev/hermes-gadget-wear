# Progress

## Repository bootstrap — 2026-10-05

Prepared a public MIT repository with a README, the project brief, project-specific agent instructions, and development/secret ignores. The brief's maintainer-only section is stored locally under the ignored `.local/` directory.

Demo note at bootstrap: documentation only. Runnable starter artifacts were added in M0 below.

Validation at bootstrap: staged whitespace checks passed, local documentation links resolve, credential/address pattern checks passed, and Git excludes the maintainer notes, local configuration, signing keys, and build outputs.

## M0 foundation — 2026-10-05

Read and pinned the Gadget SDK protocol/integration/porting/architecture sources and relevant implementation/vector tests before coding. Verified the pet generator, atlas, constants, store, and renderer against a pinned Hermes snapshot and a read-only local geometry survey. Wrote the PRD, architecture, pet corrections, upstream proposal index, and self-host TLS guide.

Added `protocol`, `wear`, and `mobile` Gradle modules, a checksummed Gradle 9.4.1 wrapper, version catalog, matching application IDs, and Compose starter screens. Cleartext is denied and all backup domains are excluded. CI builds both apps, runs checks with warnings treated as errors, scans secrets, and uploads debug APKs.

Demo note: generated `wear-debug.apk` (28,640,892 bytes) and `mobile-debug.apk` (29,559,076 bytes), containing the setup/intro screens. These artifacts have not been launched on an emulator or physical device, and conversation controls are not implemented yet.

Local validation: `./gradlew ktlintFormat check :wear:assembleDebug :mobile:assembleDebug --warning-mode=fail` passed with the devserver environment variable set. No Kotlin, Gradle, or Android lint warnings remained. Both Android host unit-test tasks have no source tests at this starter stage. APK production does not establish round-screen readability or device behavior.

Public-tree validation: gitleaks 8.30.1 found no leaks in the staged export; private-address/tailnet checks and local documentation-link checks passed. Maintainer notes, SDK location, and both APK outputs remain ignored. Git attributes normalize source line endings while preserving Windows wrapper checkout compatibility.

[GitHub CI run](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37379033226) passed the Android build/check job and secret-scan job for commit `9e9b696`, with both debug APKs uploaded. M0 is complete; emulator and physical-device evidence remain later gates.

## M1 stock protocol — 2026-10-05

Implemented defensive device identities, SHA-256 IDs, enrollment Base64, authentication/OTA HMAC, little-endian binary framing, bounded JSON envelopes, PCM negotiation, ordered handshake/pairing state, handshake timeout, heartbeat echo, and liveness. Tests cover upstream vectors, malformed and out-of-order input, unknown messages/channels, size/nesting limits, key redaction, and pairing revocation.

Demo note: a JVM client starts the actual unmodified SDK devserver with isolated ephemeral state, enrolls and approves a synthetic identity, receives `You said: hello café`, uploads 0.4 seconds of synthetic 16 kHz PCM and verifies byte-identical paced playback, then reconnects with HMAC and receives `You said: reconnected`. Server output, runtime addresses, and identity material are discarded from reports.

Local validation: 13 protocol tests plus one live SDK integration test passed, zero failures/errors/skips. `.local/venv/bin/python -m pytest .local/upstream/hermes-gadget-sdk/tests/test_protocol.py -q` passed all four upstream Python tests. SDK commit: `323e3303ab68981f810fc3208119cd8a22e64af0`.

The integration test requires `HERMES_GADGET_PYTHON`; without it, that test is explicitly skipped. Its enabled state is a Gradle test input: changing from an absent variable to a configured devserver was verified to rerun the test and pass with zero skips. CI installs the pinned server and sets the variable. No full Hermes gateway, physical microphone/playback, or phone Data Layer evidence is claimed by M1.

M1's protocol and live devserver checks also passed in the CI run linked above. M1 is complete; M2 is the first real-Hermes and watch-hardware conversation gate.

## M2 direct mode — 2026-10-05 — software verified, hardware gate open

The maintainer approved explicit LAN/tailnet cleartext with a per-endpoint warning. Implemented direct OkHttp WebSockets with normal TLS validation, private-address checks on every cleartext DNS resolution, no redirects/proxies/downgrade, generation-guarded callbacks, bounded work/audio queues, jittered reconnect, and no input replay. TLS, token, enrollment, and protocol errors require explicit correction. Ordinary inactivity disconnects after 90 seconds; a stalled turn/prompt has a 120-second inactivity cap.

Added a pure JVM conversation reducer for cumulative replies, turn matching, guarded exactly-once prompt answers, cards and RGB565 transfers, 16 kHz PCM upload/playback ordering, capture cancellation, and bounded action execution. Recording tokens guard both PCM data and failure callbacks, so an old backend cannot cancel a newer hold. The watch now has encrypted atomic endpoint/key storage under Android Keystore, memory-only media, bound-service lifecycle, runtime permissions, typed input, hold/release/swipe gestures, a live meter, two-second Cancel hold, and platform actions. Fixed an activity-stop path that could discard unsaved drafts by retaining the binding until activity destruction; the emulator regression covers background/return. Typed entry starts a bounded conversation service before opening the full-screen keyboard. Standalone metadata is now true following the direct emulator demo.

Local build gate: `./gradlew ktlintFormat check :wear:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebug --warning-mode=fail --max-workers=4` passed with the pinned devserver environment configured. All 29 JVM tests passed with zero failures/errors/skips: 13 original protocol tests, 10 conversation/action/media tests, four endpoint-policy tests, and two actual SDK integration tests. The new live test uses the stock hub's native TLS hook with its unmodified echo delegate, rejects an untrusted certificate, and validates pairing, typed replies, exact PCM loopback, prompt answer, card, image, server receipt of an action result, cancellation, HMAC reconnect, idle closure, and a new user connection after idle. Production trust settings are unchanged.

Three Android Keystore instrumentation tests and one opt-in setup UI instrumentation test passed on a 480×480 round Wear OS 7 / API 37 ARM64 emulator. CI compiles the instrumentation APK; emulator execution remains a separately recorded local check. The approved dynamic cleartext platform policy has one targeted `InsecureBaseConfiguration` XML suppression, justified by the per-endpoint connector gate. No other lint, Kotlin, or Gradle warnings remained.

Demo note: see [synthetic emulator screenshots and reproduction](demo/m2/README.md). The app paired to the real stock devserver, typed `Hi` through Wear's keyboard, received `You said: Hi`, captured and looped back emulator PCM, required a fresh gesture after mic permission, displayed prompts/cards/images, discarded a recording by downward swipe, and started a new session with a two-second Cancel hold. SDK output confirmed successful vibration, brightness, and notification results. Notification denial omitted its action; grant on reconnect added it. No timer-handler app exists on this emulator, so timer advertisement was correctly omitted. The emulator also reached Offline with zero foreground services after inactivity. Host microphone/audio were disabled; these checks do not measure physical microphone, speaker, STT, or vibration quality.

[GitHub CI for the final code commit](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37392054562) passed the Android build/test/lint/format job and secret-scan job for `ea5f3c0`. Both debug APKs were uploaded. The initial M2 implementation also passed [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37391714134) for `aaa2e3a`. Runner migration / Node-action deprecation annotations remain separate from the strict application checks. Local staged-export gitleaks and personal-address/document-link checks passed.

Physical demo — 2026-10-06: wireless ADB pairing/connection succeeded with the maintainer's watch. Verified Samsung model `SM-L705U`, Android 16 / API 36, watch hardware, and its 480×480 display. The maintainer explicitly approved Hermes enrollment; the exact watch-specific pairing was approved on the supplied gateway. The watch reached Ready and subsequently reconnected using its saved identity without another approval. Installed the updated M2 debug APK with its encrypted setup preserved. Through Samsung's full-screen keyboard, entered `Reply exactly WATCH M2 OK, without tools.`, closed the keyboard with Done, and sent the message. Accessibility inspection showed the streamed reply including `WATCH M2 OK`; read-only gateway queries confirmed one matching user message and one exact final assistant reply. Physical captures and setup remain ignored. This proves typed input via automation and a real gateway round trip, not manual typing, physical audio, or all action behavior.

The physical test exposed a draft-loss bug: the connection could idle out while the keyboard was open, then Send cleared the draft despite the service rejecting it. The editor now retains endpoint-scoped in-memory drafts, shows an explicit Connect control when offline, and clears the draft only after the active WebSocket accepts the text frame. Reconnect never resends input automatically. Local acceptance is not a server delivery acknowledgement. Submission futures also resolve false when stopped or rejected by the bounded queue.

Updated validation: the strict full build/check command above passed again after the fix and final test changes. All 31 JVM tests passed with zero failures/errors/skips, including the enabled stock SDK tests and two new queued-submission rejection/close regressions. The opt-in draft UI regression passed on the owned round API 37 emulator with isolated stock SDK state: draft retained after disconnect, Send disabled offline, Connect inside the editor, draft retained after reconnect, no automatic replay, and a fresh Send producing the echo reply. It required visible editor nodes and fresh accessibility state after lazy-list updates. No identity-reset instrumentation ran on the personal watch. The physical display timeout was temporarily extended for the automated text demo and restored to its original value afterward.

The core physical M2 voice/STT/reply demo passed by maintainer report below. The remaining Ultra media/action, permission, lifecycle, and quality matrix remains open. Phase implementation advances in order under the maintainer's explicit authorization; incomplete hardware acceptance is recorded separately.

## M3 phone relay — 2026-10-06 — software verified, hardware gate open

Resolved relay trust: the maintainer requires watch-owned TLS through an opaque phone relay, with direct fallback under the configured endpoint's policy. Implemented bounded ChannelClient streams, an authenticated watch loopback CONNECT bridge, normal OkHttp TLS/hostname checks, same-package wearable capability discovery, Automatic/Phone/Direct selection, and same-endpoint network fallback. Certificate/authentication failures do not trigger fallback, and no input is replayed. The last reply is retained across transport replacement. The watch connects on app open and still idles out. The phone requires opt-in, provides Stop controls, uses a connected-device foreground service only for active channels, and offers Android companion association for background startup. No identity/key handling exists on the phone.

Local checks: the strict full build/check command passed with 38 JVM tests and zero failures/errors/skips. A live pinned SDK TLS test pairs and reconnects through a simulated byte-forwarding phone; ciphertext capture contains neither the enrollment key nor the synthetic plaintext prompt. TLS trust rejection, header bounds, proxy authentication/authority, manual overrides, and fallback restrictions are covered. An API 37 Android TLS test verifies untrusted-certificate rejection and a trusted HTTPS exchange through the real loopback socket. The first rapid follow-up connection exposed asynchronous pump teardown; the bridge now waits up to one second for its bounded slot before rejecting a new connection. Test trust exists only in test code.

Maintainer infrastructure: enabled a tailnet-only HTTPS proxy on the existing allowed Gadget port, with Funnel disabled and the backend unchanged. A default-trust TLS WebSocket upgrade negotiated `hermes-gadget.v1`; no enrollment or conversation was sent. The live policy already contained the own-device Gadget grant, with no Gadget port in the box-to-box grant, so no ACL edit was made. Configuration and verification files are ignored. This host probe does not prove the phone's Android VPN or off-network reachability. A new TLS URL receives an independent endpoint identity; the personal watch's saved LAN setup is preserved until the maintainer selects it.

Demo note: JVM relay pairing/text/reconnect and Android loopback TLS passed; actual ChannelClient, companion association, background foreground-service startup, Bluetooth interruption, phone Tailscale VPN, and off-network conversation remain hardware gates. M3 acceptance is not complete. No server extensions have been installed into the running Hermes environment.

## M4 Opus — 2026-10-06 — software verified, Ultra measurements open

Implemented optional per-direction `welcome.audio` selection, missing-selection PCM fallback, RFC OpusHead configuration separate from packets, Android unified CSD parsing, 20 ms packet validation, codec token guards, bounded capture/playback, and an actual local codec probe before advertising Opus. Capture release flushes the encoder before `audio.end`; cancellation discards it. Android decodes Opus at 48 kHz for AudioTrack. No mid-stream format switch or input replay occurs.

Prepared SDK patch 1 against the exact v0.2.0 base on local branch `wear/optional-extensions`, commit `9c400f9`, with SDK protocol documentation and tests. `enable_opus` defaults false and requires ffmpeg Opus support. Uplink wraps packets in CRC-protected Ogg and decodes off the event loop into stock STT WAV. Downlink uses a persistent bounded ffmpeg pipe, demuxes raw packets and enforces the playback lead. Elapsed-time testing caught a copied pacing-clock sign error; the optional Opus path now rebases correctly. Provider-direct Opus is not implemented. Nothing was installed into live Hermes.

Validation: strict build/check passed with 42 JVM tests and zero failures/errors/skips, including enabled unmodified SDK direct/relay integrations and four new negotiation/header/token/packet tests. The isolated exported patch series applies cleanly and its 21 Python tests pass, including real ffmpeg, live WebSocket enrollment/Opus loopback, stock extensions-off behavior, malformed input, sequence rejection, unpaired rejection, and elapsed pacing. Two Android codec tests plus the relay TLS regression passed on the owned round API 37 emulator. Android-to-ffmpeg and ffmpeg-to-Android packet decoding both passed. The stock SDK offline-draft UI regression passed after the capture changes. The opt-in service-level emulator demo passed negotiated Opus capture, flush on release, SDK reply/loopback playback path, and cancellation. Host microphone/speaker were disabled. No personal watch was reset or modified.

Demo note: [measured synthetic payload table](audio-measurements.md) records PCM 256 kbit/s versus Android Opus 23.584 kbit/s uplink and host ffmpeg 36.264 kbit/s downlink for one original three-second tone. This is not Bluetooth throughput, end-to-end latency, actual speech/STT accuracy, or Ultra battery evidence. M4 acceptance remains open for the required actual-device comparison. Continue M5 in order under the maintainer's advance authorization.

## M5 pets — 2026-10-06 — software verified, Ultra gate open

Implemented authenticated optional pet manifests/channel-4 PNG transfers with exact sequence/length/hash, dimension/compression/alpha limits and expiry. SDK patch 2 (`2bcdfc7`) resolves the current profile's active pet with confined paths, preserves base/legacy taxonomy, converts WebP to alpha PNG, trims blank tails and paces chunks. Missing pets/extensions do not block conversations. No running Hermes was changed.

The watch prepares pixel-scaled frames on a bounded worker, caches per endpoint/hash with an 8 MiB/64-file quota, maps state/legacy fallbacks, emits once-only turn cues and uses playback amplitude/hysteresis for talking or idle bob. An original procedural robot is the fallback. `tools/extend-pet.py` matches later rows to median idle height/center/baseline using shared row registration, rejects empty/clipped/drifting/extreme poses, and leaves the base and inputs unchanged. Same-hatch generation remains an upstream proposal.

Validation: the strict full build/check command passed; 46 JVM tests, zero failures/errors/skips. A fresh pinned checkout accepted the exported two-patch series and passed 29 Python SDK/tool tests. API 37 actual SDK→GadgetService pet transfer/cache/endpoint isolation and PNG corruption/bounds/alpha/blank-tail instrumentation passed. The stock SDK offline-draft/resend regression passed after the renderer changes. Personal-watch state remains untouched. Scope the patch whitespace attribute to preserve required unified-diff context prefixes; the inner SDK diff is whitespace-clean.

Demo note: [original sample on the round emulator](demo/m5/README.md), including cached rendering after fixture loss. Software checks do not establish personal-profile delivery, small-watch legibility, physical lip sync, or ambient/battery acceptance. Staged-export gitleaks, private-value and local-document-link checks passed. M5 hardware acceptance remains open. M4 commit `82a206b` [CI](https://github.com/QuantumInkDev/hermes-gadget-wear/actions/runs/37468005261) passed all three jobs.

## Milestones

Maintainer voice demo — 2026-10-06: the maintainer reports that the physical watch successfully asked about the weather, received an answer, and played the profile's ElevenLabs voice. This verifies a real hold-to-talk/STT/reply/playback path by maintainer report; no bitrate, STT comparison, battery, or audio-quality measurement is inferred. They described the flow as clunky and authorized advancing the remaining phases while away from the home network. The core M2 conversation demo has passed; the remaining physical media/action, permission, lifecycle, and quality matrix stays open. Phase implementation may advance in sequence under that authorization, with unverified milestone acceptance kept explicit.

| Milestone | Status |
| --- | --- |
| M0: Gradle skeleton, CI, PRD, architecture, pet analysis | Complete; local and CI checks passed |
| M1: Protocol vectors and devserver integration | Complete; local and CI checks passed |
| M2: Watch direct mode and real Hermes demo | Core physical typed/voice demo passed; remaining Ultra matrix open |
| M3: Phone relay and transport selection | Software verified; actual Data Layer/off-network gate open |
| M4: Opus and measurements | Software/cross-codec verified; Ultra speech/bitrate/battery gate open |
| M5: Pets and companion-sheet tooling | Software/emulator verified; real profile/Ultra gate open |
| M6: Client BYOK TTS | Not started |
| M7: Multi-endpoint profiles and v2 proposal | Not started |
| M8: Tile, complication, ambient, battery, internal testing | Not started |

Next: M6 BYOK client TTS, then M7–M8 in order. Keep M2/M3 hardware gates open while the maintainer is away. Relay TLS and direct cleartext decisions are resolved. Multiplexed gateway v2 remains proposal-only pending decision and upstream feedback.
