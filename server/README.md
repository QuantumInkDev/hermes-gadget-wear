# Upstream-bound SDK patch series

Base: SDK `v0.2.0`, commit `323e3303ab68981f810fc3208119cd8a22e64af0`.

Apply the numbered [patches](patches/) in order to a clean checkout. Patch 1 adds optional Opus negotiation, bounded raw packets/OpusHead configuration, ffmpeg decode/encode pipes, paced downlink, protocol documentation, and compatibility tests. Patch 2 adds optional profile pet manifests and bounded PNG assets, safely confined metadata paths, and alpha/taxonomy tests. Patch 3 adds opt-in client speech acknowledgment, bounded final-text requests and profile voice hints, with voice-off and duplicate handling. Patch 4 adds an enrolled-key-scoped, durable final-text outbox with explicit read/ACK, bounded retention and silent recovery. It requires the matching [Hermes context hook version 2](core-patches/README.md), a valid private grant and the real runner allowlist; `enable_relay_outbox` defaults to false. Windows activation remains denied until its native storage-privacy implementation and host checks pass. This increment does not expose conversation/history/task methods. Default `enable_client_tts` is false. Default `enable_pets` is false. Default `enable_opus` is false; missing capabilities or ffmpeg support keeps stock PCM16. Local development branch: `wear/optional-extensions`. No upstream PR or maintainer agreement is claimed, and this series is not installed into live Hermes.

Run `python3 tools/setup-extensions.py` from this repository for an isolated patched checkout/environment, then run its protocol, plugin-unit, Opus, pet, client-speech, grant, outbox and service tests, plus `tools/tests/test_extend_pet.py`. If a prior patched checkout exists, use `--sdk-dir` and `--environment` with fresh ignored `.local/` paths; modified checkouts are refused rather than reset. Keep the unmodified SDK environment for stock integration. See [upstream status](../docs/upstream.md) and [measurements](../docs/audio-measurements.md).

SDK patch 4 is exported from the independently reviewed storage/service/core integration.
Its SDK and core patches are separate: never feed a core patch to the SDK setup helper.
Actual-core adapter and recovery tests run through the pinned Hermes canonical test
runner with temporary homes; SDK-only tests do not establish that hook integration.
Source checks, fresh reapplication and live acceptance are recorded separately in
[progress](../docs/progress.md). No optional server flag has been enabled on a live box.

SDK patch 5 adds optional talking-region metadata and exact client capability negotiation.
Updated clients compose three mouth poses from an immutable body anchor; old clients
receive ordinary base/listening/sleeping rows and omit overlay-dependent talking. The
validated descriptor is limited to the declared cell and bounded region. Existing unmasked
pets retain empty optional-row omission. Fresh pinned-source reapplication matches all
265 reviewed SDK files and passes 89 affected pet/protocol/plugin/tooling compatibility
checks, with no skips. Source/fix review and synthetic native composition are accepted;
personal artwork, live transfer, physical playback and power remain separate gates.
No profile assignment or optional live server flag is changed by exporting this patch.
