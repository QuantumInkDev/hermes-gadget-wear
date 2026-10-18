# Upstream work

Planned, not implemented or installed: an optional `maintenance_observation` runtime-status
object sampled only under an actually engaged external drain. The existing generic update
timestamp can retain an old work count, so it cannot authorize the prepared rollout.
The [isolated source plan](superpowers/plans/2026-10-18-maintenance-observation.md) requires
strict source availability, per-instance sample identity, unchanged marker binding and
real status/watcher tests. Export only its clean delta after review; preserve the separate
bootstrap gate and current pinned Stage1 package. No new network/Gadget API is proposed.

Baseline: Hermes Gadget SDK `v0.2.0`, commit `323e3303ab68981f810fc3208119cd8a22e64af0`; pet source snapshot `79af3f6cea8067284a7ea5725078578b3f790adb`. A local SDK patch series is prepared against the pinned base; no upstream pull request or maintainer agreement is claimed.

| Proposal | Target | Status / gate |
| --- | --- | --- |
| Optional mic/speaker Opus negotiation, packet framing and paced codec pipeline | Gadget SDK | Patch 1 implemented/tested with real ffmpeg and SDK; Android cross-codec tests pass; Ultra measurements open |
| Optional client TTS and profile voice hint | Gadget SDK | Patch 3 implemented/tested; opt-in negotiation and final text; physical/provider gate open |
| Pet manifest and bounded PNG asset transfer on channel 4 | Gadget SDK | Patch 2 implemented/tested; Android delivery/cache checks pass; real-profile/Ultra gate open |
| Optional talking-region replacement and exact capability negotiation | Gadget SDK | Patch 5 reviewed/freshly reapplied; 89 affected compatibility checks pass; personal art/live/physical gates open |
| Optional companion metadata and extra rows in one hatch normalization pass | Hermes Agent | Later-row registration tool implemented/tested; same-hatch generator change remains proposed |
| Pending outbox on reconnect | Gadget SDK | Patch 4 implemented and independently reviewed; enrolled-key grants, bounded silent read/ACK and durable final capture; fresh export/host/live gates tracked in progress |
| Native Windows private authority/storage validation | Gadget SDK | Patch6 reviewed/freshly reapplied;402 software checks and20 actual native method passes; installed-profile activation still gated |
| Generic adapter final-delivery context and coherent queue/recovery handoff | Hermes Agent | [Clean core patch](../server/core-patches/README.md), hook version 2; 100 focused actual-core checks per clean/private-runtime fixture; no upstream agreement or live activation |
| Profile discovery/select with multiplexed gateway | Gadget SDK / Hermes Agent | [V2 design proposal](profiles-v2.md) only; **DECISION** and upstream maintainer feedback before implementation |
| Read-only watch catalog projection from host profile/avatar inventory | Hermes Agent / self-host catalog tooling | [Multi-box carousel design](superpowers/specs/2026-10-11-multi-box-profile-carousel-design.md) approved for isolated development; independent Gadget endpoints; live rollout requires review |

Each SDK implementation must be a clean branch/PR series against the pinned baseline, with protocol docs, tests, extensions-off compatibility, and stock gadget coverage. Record actual branch/commit/PR links and maintainer responses here when created. A local proposal is not an upstream agreement. Never install an unreviewed extension into the maintainer's running Hermes environment as part of development.

M4 local branch `wear/optional-extensions`, first patch `feat: negotiate optional packet Opus using ffmpeg`, includes SDK protocol documentation and tests. Reproduce from [server patch series](../server/README.md). The opt-in path uses a correctly rebased lead-pacing clock; elapsed-time testing caught the copied sign error that otherwise let packets burst. Stock PCM behavior remains unchanged in this patch. No provider-direct Opus optimization, hardware STT nonregression, gateway installation, or upstream review is claimed.

M5 local SDK patch 2 `2bcdfc7` implements authenticated, opt-in profile pet delivery. [Pet documentation](pets.md) records the companion metadata proposal, original-fixture evidence, renderer fallback and registration-tool limits. No Hermes Agent generator change, real-profile delivery, upstream review, or live installation is claimed.

M6 local SDK patch 3 `8a1d854` acknowledges optional client speech, sends bounded final reply text and a profile voice hint, deduplicates finals, respects voice-off, and suppresses automatic server synthesis for acknowledged client sessions. Default `enable_client_tts` is false. Stock clients and extension-off servers retain their speech behavior. Explicit voice attachments retain their existing server path. Actual Hermes adapter/profile integration, real provider output and phone/watch transport remain unverified. No upstream review or live installation is claimed.

Carousel catalog implementation is an isolated standalone [publisher](../server/catalog/README.md),
not a Gadget wire extension. Its five auth/projection/persistence/artwork tests pass without
modifying stock SDK v0.2.0. The app's real three-stock-server and three-TLS-catalog tests pass;
independent URLs and pairing keys remain the routing boundary. Proposed private per-box
mappings, installation and rollback notes are prepared for owner review. No live deployment,
new pairing, upstream PR or agreement is claimed. Existing SDK patch exports remain unchanged.

The 2026-10-18 outbox increment exports SDK patch 4 and a separate clean-core hook patch.
Independent review accepts the registry-completeness and queued/redirected provenance
corrections. Recovered text never triggers automatic speech, tool work or approvals.
Fresh pinned-source reapplication passes 187 SDK and 100 actual-core warning-strict
checks without skips; 265 SDK files and all 13 changed core files match reviewed source.
Native Windows verifier patch6 now passes fresh reapplication,402 affected software
checks and10 native methods on each actual gateway architecture. Installed-profile
preflight/grants/core checks and staged live activation remain separate gates. Private existing core modifications are excluded from the public delta.
Earlier software/stock compatibility evidence is not a claim of full-core warning
cleanliness, physical speech, continuity/task implementation or live deployment.
