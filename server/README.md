# Upstream-bound SDK patch series

Base: SDK `v0.2.0`, commit `323e3303ab68981f810fc3208119cd8a22e64af0`.

Apply the numbered [patches](patches/) in order to a clean checkout. Patch 1 adds optional Opus negotiation, bounded raw packets/OpusHead configuration, ffmpeg decode/encode pipes, paced downlink, protocol documentation, and compatibility tests. Patch 2 adds optional profile pet manifests and bounded PNG assets, safely confined metadata paths, and alpha/taxonomy tests. Patch 3 adds opt-in client speech acknowledgment, bounded final-text requests and profile voice hints, with voice-off and duplicate handling. Patch 4 adds an enrolled-key-scoped, durable final-text outbox with explicit read/ACK, bounded retention and silent recovery. It requires the matching [Hermes context hook version 2](core-patches/README.md), a valid private grant and the real runner allowlist; `enable_relay_outbox` defaults to false. Patch 6 supplies the native Windows storage-privacy verifier and its accepted AMD64/ARM64 synthetic host checks. Installed-profile privacy/grants/core preflight and staged rollout still gate activation. This increment does not expose conversation/history/task methods. Default `enable_client_tts` is false. Default `enable_pets` is false. Default `enable_opus` is false; missing capabilities or ffmpeg support keeps stock PCM16. Local development branch: `wear/optional-extensions`. No upstream PR or maintainer agreement is claimed, and this series is not installed into live Hermes.

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

SDK patch 6 adds lazy, standard-library Win32 same-object inspection for private Relay
authority/storage paths and SQLite sidecars. It preserves the POSIX and stock-disabled
paths. Exact User/SYSTEM/Administrators leaf owners/grants are allowed; TrustedInstaller
is an ancestor-owner exception only. Unsafe paths/descriptors deny access without repair.
Fresh pinned-source reapplication matches all 274 reviewed Windows/pet SDK files. The
export passes 402 affected software checks, with 10 explicit Windows-only skips on Mac;
actual AMD64 and ARM64 gateway interpreters each pass all 10 native methods, zero skips.
The reduced token reads its permissive control and receives exact 0xC0000022 for the
private leaf through fresh relative opens. This is the stated leaf-DACL test boundary,
not another real account's full-path access. Existing installed source/ACLs/listeners and
optional flags remain unchanged. Per-profile rollout preflight is still required.

SDK canonical conversation voice INPUT (V2, private incremental source delta over the Task10 fix1 SDK `286e1348`; **not** a standalone
stock-v0.2.0 installer, and no standalone patch `0007` is claimed here; folding it into the final clean series remains a later explicit
obligation). Both flags (`enable_relay_voice_input`, `enable_relay_voice_output`) default false and nothing is activated. The input half
adds `relay.voice.info` and `relay.voice.capture.{start,finish,result,discard}`, strict channel-5 uplink assembly, a private durable
paid-attempt store (`relay-voice.sqlite3`), exactly one accounted configured STT call per sealed capture, authority checkpoints S1-S6,
content-free logs, the exact core voice-seam validation, the logical-chat auto-TTS guard and Windows verifier coverage of the new
storage. A transcript is a result that the author submits through the unchanged `relay.turn.submit`. Stock channels 1-4, `audio.start`
and `on_utterance` are unchanged. Source tests use the registered rig with a synthetic socket and a synthetic provider boundary only:
they prove protocol, authority, accounting and refusal behaviour, NOT any provider, model, voice, local backend (`input.local_fallback`
is `false` and is never inferred from an import), installed profile, other host, device or the universal V1-P / initialized-plugin
inspection. The core patch `0002` is unchanged by this stage. Interpretations and their boundary tests: design section 18;
contract: SDK `docs/relay-voice.md`.
