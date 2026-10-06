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

Added a pure JVM conversation reducer for cumulative replies, turn matching, guarded exactly-once prompt answers, cards and RGB565 transfers, 16 kHz PCM upload/playback ordering, capture cancellation, and bounded action execution. The watch now has encrypted atomic endpoint/key storage under Android Keystore, memory-only media, bound-service lifecycle, runtime permissions, typed input, hold/release/swipe gestures, a live meter, two-second Cancel hold, and platform actions. Wear keyboard draft loss was found on the emulator and fixed by retaining the binding until activity destruction. Typed entry starts a bounded conversation service before opening the full-screen keyboard. Standalone metadata is now true following the direct emulator demo.

Local build gate: `./gradlew ktlintFormat check :wear:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebug --warning-mode=fail --max-workers=4` passed with the pinned devserver environment configured. All 29 JVM tests passed with zero failures/errors/skips: 13 original protocol tests, 10 conversation/action/media tests, four endpoint-policy tests, and two actual SDK integration tests. The new live test uses the stock hub's native TLS hook with its unmodified echo delegate, rejects an untrusted certificate, and validates pairing, typed replies, exact PCM loopback, prompt answer, card, image, server receipt of an action result, cancellation, HMAC reconnect, idle closure, and a new user connection after idle. Production trust settings are unchanged.

Three Android Keystore instrumentation tests and one opt-in setup UI instrumentation test passed on a 480×480 round Wear OS 7 / API 37 ARM64 emulator. CI compiles the instrumentation APK; emulator execution remains a separately recorded local check. The approved dynamic cleartext platform policy has one targeted `InsecureBaseConfiguration` XML suppression, justified by the per-endpoint connector gate. No other lint, Kotlin, or Gradle warnings remained.

Demo note: see [synthetic emulator screenshots and reproduction](demo/m2/README.md). The app paired to the real stock devserver, typed `Hi` through Wear's keyboard, received `You said: Hi`, captured and looped back emulator PCM, required a fresh gesture after mic permission, displayed prompts/cards/images, discarded a recording by downward swipe, and started a new session with a two-second Cancel hold. SDK output confirmed successful vibration, brightness, and notification results. Notification denial omitted its action; grant on reconnect added it. No timer-handler app exists on this emulator, so timer advertisement was correctly omitted. The emulator also reached Offline with zero foreground services after inactivity. Host microphone/audio were disabled; these checks do not measure physical microphone, speaker, STT, or vibration quality.

M2 is **not complete**: no physical Watch Ultra / real-Hermes pairing and conversation has been approved or demonstrated. Android 13–16 / Wear OS 4–6 compatibility, real clock timer execution, permission-denied and background behavior on the Ultra, and physical audio remain hardware checks. Personal endpoint details were not written to tracked files. M3 is not started.

## Milestones

| Milestone | Status |
| --- | --- |
| M0: Gradle skeleton, CI, PRD, architecture, pet analysis | Complete; local and CI checks passed |
| M1: Protocol vectors and devserver integration | Complete; local and CI checks passed |
| M2: Watch direct mode and real Hermes demo | Software and emulator verified; physical Ultra / real-Hermes gate open |
| M3: Phone relay and transport selection | Not started |
| M4: Opus and measurements | Not started |
| M5: Pets and companion-sheet tooling | Not started |
| M6: Client BYOK TTS | Not started |
| M7: Multi-endpoint profiles and v2 proposal | Not started |
| M8: Tile, complication, ambient, battery, internal testing | Not started |

Next: finish M2 on the physical watch after availability and pairing approval. The cleartext policy is resolved. The supplied maintainer endpoint is kept out of public files. For M3, resolve whether the relay must preserve watch-owned TLS or the phone is explicitly trusted. Multiplexed gateway v2 remains a later decision. No upstream patches or PRs have been submitted.
