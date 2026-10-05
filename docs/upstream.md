# Upstream work

Baseline: Hermes Gadget SDK `v0.2.0`, commit `323e3303ab68981f810fc3208119cd8a22e64af0`; pet source snapshot `79af3f6cea8067284a7ea5725078578b3f790adb`. No upstream changes or pull requests have been created for this project yet.

| Proposal | Target | Status / gate |
| --- | --- | --- |
| Optional mic/speaker Opus negotiation, packet framing and paced codec pipeline | Gadget SDK | Proposed for M4; provider/container and ffmpeg availability must be measured |
| Optional client TTS and profile voice hint | Gadget SDK | Proposed for M6; stock server TTS remains default |
| Pet manifest and bounded PNG asset transfer on channel 4 | Gadget SDK | Proposed for M5; define transfer start/end/abort and limits, not just a new channel |
| Optional companion metadata and extra rows in one hatch normalization pass | Hermes Agent | Proposed for M5; preserve existing base atlas and consumers |
| Pending outbox on reconnect | Gadget SDK | Proposed; define expiry, order, bounded retention and deduplication |
| Profile discovery/select with multiplexed gateway | Gadget SDK / Hermes Agent | Design proposal only; **DECISION** and upstream maintainer feedback before implementation |

Each SDK implementation must be a clean branch/PR series against the pinned baseline, with protocol docs, tests, extensions-off compatibility, and stock gadget coverage. Record actual branch/commit/PR links and maintainer responses here when created. A local proposal is not an upstream agreement. Never install an unreviewed extension into the maintainer's running Hermes environment as part of development.
