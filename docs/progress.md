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

## M1 stock protocol — 2026-10-05

Implemented defensive device identities, SHA-256 IDs, enrollment Base64, authentication/OTA HMAC, little-endian binary framing, bounded JSON envelopes, PCM negotiation, ordered handshake/pairing state, handshake timeout, heartbeat echo, and liveness. Tests cover upstream vectors, malformed and out-of-order input, unknown messages/channels, size/nesting limits, key redaction, and pairing revocation.

Demo note: a JVM client starts the actual unmodified SDK devserver with isolated ephemeral state, enrolls and approves a synthetic identity, receives `You said: hello café`, uploads 0.4 seconds of synthetic 16 kHz PCM and verifies byte-identical paced playback, then reconnects with HMAC and receives `You said: reconnected`. Server output, runtime addresses, and identity material are discarded from reports.

Local validation: 13 protocol tests plus one live SDK integration test passed, zero failures/errors/skips. `.local/venv/bin/python -m pytest .local/upstream/hermes-gadget-sdk/tests/test_protocol.py -q` passed all four upstream Python tests. SDK commit: `323e3303ab68981f810fc3208119cd8a22e64af0`.

The integration test requires `HERMES_GADGET_PYTHON`; without it, that test is explicitly skipped. CI installs the pinned server and sets the variable. No full Hermes gateway, physical microphone/playback, or phone Data Layer evidence is claimed by M1.

## Milestones

| Milestone | Status |
| --- | --- |
| M0: Gradle skeleton, CI, PRD, architecture, pet analysis | Implemented; local checks passed |
| M1: Protocol vectors and devserver integration | Implemented; local checks passed, zero skips |
| M2: Watch direct mode and real Hermes demo | Not started |
| M3: Phone relay and transport selection | Not started |
| M4: Opus and measurements | Not started |
| M5: Pets and companion-sheet tooling | Not started |
| M6: Client BYOK TTS | Not started |
| M7: Multi-endpoint profiles and v2 proposal | Not started |
| M8: Tile, complication, ambient, battery, internal testing | Not started |

Next: resolve the TLS/cleartext policy and implement M2. The supplied maintainer endpoint is kept out of public files. For M3, resolve whether the relay must preserve watch-owned TLS or the phone is explicitly trusted. Multiplexed gateway v2 remains a later decision. No upstream patches or PRs have been submitted.
