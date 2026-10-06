# Hermes Gadget for Wear OS

A Wear OS watch app and Android phone companion for talking to your own Hermes agent, approving prompts, and seeing your profile's pet on your wrist.

**Status:** M0 and M1 are complete. M2 direct mode is implemented and verified against the stock SDK and a round watch emulator: pairing, typed replies, PCM capture/playback, prompts, cards, images, and supported device actions. The physical Watch Ultra / real-Hermes demo remains open. See the [synthetic watch demo](docs/demo/m2/README.md).

The initial target is the Samsung Galaxy Watch Ultra, with support planned for Wear OS 4+ devices. The app will connect to user-configured Hermes servers, using a phone relay by default or a direct WebSocket connection when configured. There is no hosted backend or analytics service planned.

## Project brief

The [project brief](docs/hermes-gadget-wear-prompt.md) defines the requirements, upstream sources, acceptance criteria, and milestones. [Progress](docs/progress.md) records what has actually been delivered.

Design and verified upstream corrections are in the [PRD](docs/PRD.md), [architecture](docs/architecture.md), and [pet analysis](docs/pets.md). Server extensions are tracked in [upstream proposals](docs/upstream.md). The [self-host guide](docs/self-host.md) covers private TLS access. Decisions marked **DECISION** in the brief require maintainer input.

## Modules

| Directory | Purpose |
| --- | --- |
| `protocol/` | Pure Kotlin/JVM protocol, direct transport, endpoint policy, conversation reducer, media framing, and action validation |
| `wear/` | Direct-mode watch app, encrypted identities, PCM backends, prompts, and device actions |
| `mobile/` | Android Compose starter; onboarding and relay follow in M3 |
| `server/` | Index of proposed upstream changes; no server fork or patches yet |
| `tools/` | Pinned SDK installation and actual devserver integration launcher |

## Build and verify

Install JDK 17, Python 3.10+, Git, and the Android SDK packages `platforms;android-37.0`, `build-tools;36.0.0`, and `ndk;27.1.12297006`. Set `JAVA_HOME` and `ANDROID_HOME`, or configure the SDK location in an ignored `local.properties`. The checked-in Gradle 9.4.1 wrapper verifies the distribution checksum. Both apps compile/target API 37; watch minimum API is 33, phone minimum API is 29.

```bash
python3 tools/setup-devserver.py
HERMES_GADGET_PYTHON="$PWD/.local/venv/bin/python" ./gradlew check :wear:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebug --warning-mode=fail
```

The setup script pins SDK `v0.2.0` to commit `323e3303ab68981f810fc3208119cd8a22e64af0`, rejects a modified checkout, and installs test dependencies into ignored `.local/` state. On Windows, use `.local/venv/Scripts/python.exe` and `gradlew.bat` with the same environment variable.

`check` runs 29 JVM tests, Android host unit-test tasks, Android lint, and ktlint. Kotlin, lint, and Gradle warnings fail verification. The two live integration tests are skipped when `HERMES_GADGET_PYTHON` is absent; CI sets it, and the recorded local run has zero skipped tests. Android host unit-test tasks have no source tests; four watch instrumentation tests were run on the emulator separately. CI compiles their APK but does not run an emulator. The approved dynamic-host cleartext exception has one narrowly documented XML lint suppression; all other lint checks remain strict.

Debug APKs are written to `wear/build/outputs/apk/debug/` and `mobile/build/outputs/apk/debug/`. [CI](.github/workflows/ci.yml) runs the same gate, scans secrets, and publishes both debug APKs. Format Kotlin with `./gradlew ktlintFormat`.

## Next gates

Finish M2 with an approved physical-watch pairing and real-Hermes conversation. Direct mode uses validated TLS by default. The maintainer approved private LAN/tailnet cleartext only after two per-endpoint choices; public cleartext destinations, redirects, proxies, and TLS downgrade are rejected. Before M3, resolve watch-owned TLS through an opaque phone relay versus a trusted-phone relay. Hardware, battery, and release acceptance remain open.

## Public repository hygiene

Keep personal server addresses, tailnet details, credentials, signing keys, and personal pet art out of commits and screenshots. Store maintainer setup notes in the ignored `.local/` directory. Never commit Android `local.properties`, device keys, or BYOK secrets.

## License

[MIT](LICENSE).
