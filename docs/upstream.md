# Upstream work

Baseline: Hermes Gadget SDK `v0.2.0`, commit `323e3303ab68981f810fc3208119cd8a22e64af0`; pet source snapshot `79af3f6cea8067284a7ea5725078578b3f790adb`. A local SDK patch series is prepared against the pinned base; no upstream pull request or maintainer agreement is claimed.

| Proposal | Target | Status / gate |
| --- | --- | --- |
| Optional mic/speaker Opus negotiation, packet framing and paced codec pipeline | Gadget SDK | Patch 1 implemented/tested with real ffmpeg and SDK; Android cross-codec tests pass; Ultra measurements open |
| Optional client TTS and profile voice hint | Gadget SDK | Proposed for M6; stock server TTS remains default |
| Pet manifest and bounded PNG asset transfer on channel 4 | Gadget SDK | Patch 2 implemented/tested; Android delivery/cache checks pass; real-profile/Ultra gate open |
| Optional companion metadata and extra rows in one hatch normalization pass | Hermes Agent | Later-row registration tool implemented/tested; same-hatch generator change remains proposed |
| Pending outbox on reconnect | Gadget SDK | Proposed; define expiry, order, bounded retention and deduplication |
| Profile discovery/select with multiplexed gateway | Gadget SDK / Hermes Agent | Design proposal only; **DECISION** and upstream maintainer feedback before implementation |

Each SDK implementation must be a clean branch/PR series against the pinned baseline, with protocol docs, tests, extensions-off compatibility, and stock gadget coverage. Record actual branch/commit/PR links and maintainer responses here when created. A local proposal is not an upstream agreement. Never install an unreviewed extension into the maintainer's running Hermes environment as part of development.

M4 local branch `wear/optional-extensions`, first patch `feat: negotiate optional packet Opus using ffmpeg`, includes SDK protocol documentation and tests. Reproduce from [server patch series](../server/README.md). The opt-in path uses a correctly rebased lead-pacing clock; elapsed-time testing caught the copied sign error that otherwise let packets burst. Stock PCM behavior remains unchanged in this patch. No provider-direct Opus optimization, hardware STT nonregression, gateway installation, or upstream review is claimed.

M5 local SDK patch 2 `2bcdfc7` implements authenticated, opt-in profile pet delivery. [Pet documentation](pets.md) records the companion metadata proposal, original-fixture evidence, renderer fallback and registration-tool limits. No Hermes Agent generator change, real-profile delivery, upstream review, or live installation is claimed.
