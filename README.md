# Hermes Gadget for Wear OS

A Wear OS watch app and Android phone companion for talking to your own Hermes agent,
answering approval prompts, and seeing your profile's pet on your wrist. There is no
project-hosted conversation backend, account, analytics or advertising.

**Status:** M0/M1 are complete; the core M2 real-Hermes Ultra conversation passed.
M3–M7 software and emulator checks are recorded in [progress](docs/progress.md).
M8 adds watch shortcuts, ambient rendering and release preparation. Actual phone/watch
Data Layer, off-network relay, provider output, the remaining Ultra quality/battery matrix
and Play internal testing remain acceptance gates. An unsigned bundle is not a Play release.

Hold to speak, release to send, swipe up to cancel, or type a message. Save independently
paired endpoint profiles. Direct connections validate TLS; private LAN/tailnet `ws` requires
explicit per-endpoint consent. The phone relay forwards watch-owned TLS bytes and requires
`wss`. Connection failure never silently downgrades TLS or replays input. Optional server
extensions add Opus, pets and phone BYOK voice; stock SDK v0.2.0 remains supported.

## Documentation

Start with the [self-host and watch setup guide](docs/self-host.md).
The [brief](docs/hermes-gadget-wear-prompt.md), [PRD](docs/PRD.md),
[architecture](docs/architecture.md), [pet analysis](docs/pets.md),
[client speech](docs/client-speech.md) and [profiles](docs/profiles-v2.md) explain the design.
[Upstream proposals](docs/upstream.md) and the [SDK patch series](server/README.md) are local
work, not upstream approval. Gateway v2 remains proposal-only.

[Release preparation](docs/release.md), [privacy draft](docs/privacy.md) and
[Data Safety worksheet](docs/data-safety.md) describe the outstanding publisher/device gates.
[Session status](SESSION_STATUS.md) records where to continue.

## Modules

| Directory | Purpose |
| --- | --- |
| `protocol/` | Pure Kotlin/JVM wire protocol, transports, endpoint policy, conversation, media and action validation |
| `wear/` | Watch UI, Keystore identities/catalog, direct/relay connections, PCM/Opus, pets, Tile, complication and ambient |
| `mobile/` | Setup/import, opt-in opaque relay, companion association, encrypted BYOK vault and phone speech |
| `server/` | Three optional upstreamable SDK patches; no live gateway installation |
| `tools/` | Pinned SDK setup/integrations, pet registration and original store-art generation |

## Build and verify

Install JDK 17, Python 3.10+, Git and Android SDK packages `platforms;android-37.0`,
`build-tools;36.0.0`, and `ndk;27.1.12297006`. Set `JAVA_HOME` and `ANDROID_HOME`, or use
ignored `local.properties`. Gradle 9.4.1 verifies its distribution checksum. Both apps
compile/target API 37; Wear minimum is 33 and phone minimum is 29.

```bash
python3 tools/setup-devserver.py
python3 tools/setup-extensions.py
HERMES_GADGET_PYTHON="$PWD/.local/venv/bin/python" \
HERMES_GADGET_EXTENSION_PYTHON="$PWD/.local/extension-venv/bin/python" \
./gradlew check :wear:assembleDebug :wear:assembleDebugAndroidTest \
  :mobile:assembleDebug :mobile:assembleDebugAndroidTest \
  :wear:bundleRelease :mobile:bundleRelease --warning-mode=fail
```

Setup pins SDK v0.2.0 commit `323e3303ab68981f810fc3208119cd8a22e64af0`. Stock and patched
SDK environments stay separate under ignored `.local/`. Extension setup requires a fresh
unmodified checkout; it refuses to reset existing changes. On Windows use each environment's
`Scripts/python.exe` and `gradlew.bat`.

The gate includes 56 JVM tests, strict Android lint/ktlint and Kotlin/Gradle warnings.
Live SDK tests require both Python environment variables; CI supplies them. Android host
unit-test tasks have no source tests; recorded device instrumentation runs are separate.
CI builds both instrumentation APKs but does not execute an emulator. The optional extension
job runs 31 Python SDK/tool tests with ffmpeg. Format with `./gradlew ktlintFormat`.

Debug APKs are in `wear/build/outputs/apk/debug/` and `mobile/build/outputs/apk/debug/`.
Release bundles are in each module's `build/outputs/bundle/release/`; without private signing
configuration they are unsigned. [CI](.github/workflows/ci.yml) scans secrets and publishes
both debug apps and unsigned review bundles. Signing keys and Play upload access are not
included. Both form factors need matching certificates for Data Layer.

## Repository hygiene

Never commit personal endpoints, tailnet details, credentials, signing keys, device identities
or personal pet art. Maintainer setup belongs in ignored `.local/`. Public demos use synthetic
servers and original procedural art. Existing enrolled devices must not be reset for tests.

## License

[MIT](LICENSE).
