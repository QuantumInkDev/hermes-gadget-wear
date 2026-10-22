# Relay canonical voice: serial implementation plan (PROPOSED)

Status: **PROPOSED plan for review together with `relay-canonical-voice-design.md`.** No stage is started,
implemented, tested or accepted by writing it. The controller owns the root plan, its refinement, dispatch,
acceptance and reviews. This plan inserts reviewable voice increments between the accepted pure Task2 state
(private `c0b808b`) and the phone/watch controller tasks; it does not replace O1–O5/N1–N10, the existing Task3–8
requirements, or the fifteen-deliverable goal, and it never treats a text-only controller as preserved voice.

## 0. Constraints carried into every stage

* Global Constraints of `2026-10-22-native-relay-clients.md` apply verbatim: Android-free protocol, stock Gadget
  SDK v0.2.0 compatibility, own enrollment/key per endpoint/profile/device, persist conversation identity not
  bindings, same durable UUIDv7 + server-clock expiry + receipt-first retry, O5 copies display-only, retention
  defaults, no personal data in Git, no gatewayv2/publication/push/merge/signing rotation.
* ONE serial implementer then ONE fresh review per stage; no helper/nested/parallel/fallback agents. Each stage
  works in NEW independent private copies; predecessor source/evidence/fixtures stay frozen.
* Source stages (V1–V6) run no device/ADB/AVD/quarantine/installed/live/provider/network/dependency/shared-
  environment operations. Existing owned offline fixture seams only; guards preserved.
* Original N6 exact synthetic app-data/identity/Keystore recovery and N10/API 37 preservation stay REQUIRED and
  unwaived; no reset/repaint/substitution/reopen of quarantined fixtures.
* Each stage records semantic old RED only where current behaviour can actually fail the new requirement, new
  feature controls where the behaviour does not exist yet (never fictional RED against absent APIs), stock-off
  positive controls, one justified affected matrix at the end, and no repeated broad matrices for counts.
* Synthetic provider seams prove routing, counts, fences and cancellation only — never voice quality, provider
  behaviour or device audio.

## 1. Dependency order

```
V0 design review ─► V1 core voice seam ─► V2 SDK voice input ─► V3 SDK voice output
                                              │                       │
                                              └──► V4 native voice codec + pure capture ledger (vectors from V2/V3)
                                                                   │
            Task3 phone controller base (binding, encrypted ledger store, receipt-first) ─► V5 phone voice
            Task6 watch client base (own keys/session/TLS relay)                          ─► V6 watch voice
                                                                   ▼
                    V7 native fixture acceptance (independently backed-up owned fixtures)
                                                                   ▼
                    V8 staged server rollout + authorized physical/owner-observed checks (inside Task8)
```

V2 depends on V1 (the bridge refuses to advertise without the exact seam). V3 depends on V2 (store, flags,
channel routing). V4 needs V2/V3 fixtures for captured vectors. V5 needs V4 and the Task3 controller base; V6 needs
V4 and the Task6 base. V7 needs V5/V6. V8 needs V7 and the Task10/server gates. Task3/Task6 may implement their
typed-text parts before V5/V6, but **no build may expose hold-to-talk inside a selected Relay conversation before
its V5/V6 voice stage is reviewed**; until then the microphone is disabled there with the "type instead" state
(design §4.2), and stock device-chat voice stays unchanged.

## 2. Stages

### V0 — Design review (this artifact)

ONE fresh independent design review of both documents against the brief, the pinned sources and the approved
goal. Exit: controller disposition; any amendment is made to these documents before V1 dispatch.

### V1 — Core voice seam (core patch, inert unless called)

Scope: PROPOSED core module `gateway.session_native_voice` (+ `NATIVE_VOICE_VERSION = 1` on the module and
`SessionAuthority`) exported as a new numbered file under `server/core-patches/` with README update:
`transcribe_native_once(path, *, authorize, deadline_s)`, `synthesize_native_once(text, *, output_dir, authorize,
deadline_s)`, `speakable_text(text)`, frozen dataclasses `NativeTranscription`, `NativeSpeech` (design §12.1).

Requirements: runner's own single profile config at call time; configured provider once with every automatic
re-send disabled after a request may have reached a provider; installed-only local STT fallback once; stable
enums only; no paths/errors/credentials in results or new log lines; caller-owned output directory; `authorize`
called before each provider send and after each await; deadlines bound waiting, never claim cancellation.

Tests (core canonical runner, synthetic home, synthetic provider transport at the provider boundary, no network):
* Semantic old RED (real current behaviour): a synthetic 5xx/connection-reset provider on the existing
  `transcribe_audio` path with default config issues **two** provider requests (`max_retries` default 1,
  `transcription_cloud.py` 43–57); the same fixture through the new seam issues exactly one. Same for the
  transcode-retry path when the first request may have reached the provider.
* New controls: configured success; empty; over-limit; local fallback used only after configured failure and only
  if installed; no fallback installation; `authorize` refusal before send → zero provider calls; refusal after
  await → result withheld; output files only in the given directory; log capture contains no path/text/key.
* ElevenLabs TTS: the installed SDK's retry/timeout configuration is inspected and pinned; fixture counts exactly
  one HTTP request per chunk and stops at the first failed chunk (no retry).
* Stock-off controls: existing `run_inbound` STT/echo, `text_to_speech_tool` and runner voice reply behaviour
  unchanged.

Exit: exported patch applies with `git apply --check` to a fresh pinned core checkout; focused report; ONE review.

### V2 — SDK voice input (SDK patch, flags default false)

Scope (PROPOSED): `plugin/relay_voice.py` (`VoiceBridge`, `Captures`, `VoiceStore` on `relay-voice.sqlite3`),
`relay.voice.info` and `relay.voice.capture.{start,finish,result,discard}`, channel-5 routing in `hub._on_binary`
before stock handling, `DeviceSession.send_binary(..., authorize=)`, adapter flags `enable_relay_voice_input` /
`enable_relay_voice_output`, CoreBridge `voice_available()` exact validation, logical-chat auto-TTS guard, Windows
privacy verifier coverage of the new file/sidecars/temp directory, startup/periodic temp sweep, protocol docs.

Tests (registered rig: `tests/relay_bridge_support.py` + core `tests.gateway.relay_native_support.rig`; only the
socket and the V1 provider boundary are synthetic):
* Semantic old RED (current behaviour, confirm or refute first): with global `voice.auto_tts` on, a Relay TEXT
  turn on a logical chat reaches a TTS provider call that cannot be delivered (design §2.8). If refuted, record the
  refutation and keep the guard test as a control.
* New feature controls (methods do not exist yet, so these start as feature controls, not RED): methods listed
  only with both flags + seam + permission; `unsupported` after authorization otherwise; exact `voice.info`;
  strict params refusals; capture start/finish authority S1–S5 with revoke, key replacement, grant change, profile
  namespace mismatch, detach/rebind, core epoch change at each point; gaps, duplicates, reorder, truncation,
  oversize, overlong, too fast, stream reuse, early/late frames, deadline, socket close; Opus header/packet rules
  and decode failure; one live capture per session, global caps, `busy`; token bucket and hourly budget refused
  before accounting; durable row before provider call (crash injection at each §10.2 server row → `unknown`, zero
  extra provider calls after restart); result long-poll, TTL erasure, discard before/after seal; transcript never
  returned after revoke.
* End-to-end single admission: capture → result → existing `relay.turn.submit` with the transcript → exactly one
  core admission; duplicate `finish`, concurrent `finish`, repeated `result`, reconnect + `result`, cancelled STT
  and lost responses produce zero additional admissions and zero additional provider calls; stock device chat
  untouched (no `on_utterance`, no transcript echo).
* Stock-off positive controls: flags off → `relay.info` methods identical to current; stock `audio.start`/channel
  1 upload still reaches `on_utterance` with its existing permissive semantics; stock welcome unchanged; existing
  relay suites' affected subset unchanged.

Exit: exported patch, fresh pinned-source reapplication, focused report with call counts; ONE review.

### V3 — SDK voice output

Scope (PROPOSED): `relay.voice.speak`/`speak.stop`, speakable registry fed by the live `SharedDelivery.send`
path only, author-only final selection (journal + core lookup + `message_id` mapping), ranges/styles, server
streaming with per-frame `authorize` under the send lock, client mode (existing client-speech gates), voice
policy (`speak_replies`, device `/voice off`), live window and one-live-per-request constraint.

Tests (same registered rig, synthetic V1 TTS provider):
* Feature controls: author-only (other device/subscriber/shared receipt → refused, no provider call); recovered
  finals (`recover` path), O5 copies, existing-source and task copies never live-speakable; `no_final` for failed/
  cancelled/steered; `unavailable` after registry eviction/restart; voice-off → `off` for live, Replay allowed;
  client mode returns exact range text and voice hint with zero server provider calls; ranges deterministic
  (full/concise/More/Replay, cut flag); live window `expired`; second live `conflict`.
* Fences: revoke, rebind, epoch change and stop during synthesis and mid-stream → no further frames after the
  failing check (frame log under the send lock), `aborted`/`stopped` recorded, provider never claimed cancelled.
* Accounting: row before provider; repeated speak = receipt read; crash injection → `unknown`, no re-synthesis;
  hourly budget before accounting; temp files removed.
* Measurement: per-frame full recheck cost on the fixture host recorded (design risk); no relaxation without a
  new review.
* Stock-off controls: device-chat auto-TTS, `tts.speak` client speech and stock streaming TTS unchanged.

Exit: exported patch, reapplication, report; ONE review.

### V4 — Native voice codec and pure capture ledger (Android-free protocol module)

Scope (PROPOSED): `RelayMethod` additions with bounds; package `protocol.relay.voice`: `VoiceInfo`,
`CaptureResult`, `SpeechResult`, call builders, channel-5 frame codec (uplink/downlink kinds), pure
`VoiceCaptureLedger` (states §8.4, bounds, scope invalidation) and its snapshot section combined atomically with
`RelayStateSnapshot` (version bump of the aggregate snapshot with `TooMany` paths for voice), redacted
`toString`, owned bounded collections.

Tests (JVM only): captured vectors from V2/V3 fixtures (method success/error/state shapes, frame streams) with
provenance; constructed negative vectors labelled; strict refusal of unknown/missing/wrong-typed members and
version ≠ 1; frame sequence/size/kind/direction rules; ledger: capture→request link prepared atomically with
Task2 `prepare`, never re-prepared after restore, `draft_offered` never auto-submits, scope change invalidation,
`TooMany` before persist-before-send. Controls: stock `Message`, `BinaryFrame` (channel 5 still ignored unless
enabled), stock negotiation/capabilities, existing `Info` decode with unknown voice method names (old builds
unaffected), all Task1/Task2 suites' affected subset.

Exit: report; ONE review. No controller, Android or transport side effects.

### V5 — Phone voice integration (with/after Task3 phone controller base)

Depends on: Task3 controller base (negotiation, info gating, own enrollment/profile pinning, binding lifecycle,
encrypted durable ledger store with persist-before-send and `TooMany` handling, receipt-first reconcile) and V4.

Scope (PROPOSED): `DirectConnection` raw `relay.response` routing before stock parsing, voice binary sink per
socket generation, non-failing voice uplink backpressure; phone voice controller wiring hold/release/slide-left,
START/FINISH/CANCEL cues, recording timer, input/output waveforms, BYOK client mode via `PhoneClientSpeech`,
playback via existing `PcmPlayback`, Stop/Replay/More/walking concise, every lifecycle row of design §11, intent
window and draft path, truthful billing/unknown notices, `EXPIRED_REPORTED` backoff/recovery UI and
`ABANDON_TRACKING`.

Tests: JVM controller tests against captured vectors and an owned offline loopback of the V2/V3 registered fixture
(no device); every §11 row; process-loss restore from the encrypted store (JVM-level persistence contract; native
durability is V7); stock-off: Relay off and non-Relay screens keep stock hold-to-talk byte-for-byte (stock
`audio.start`/frames). Androidtest sources may be added but are not run here.

Exit: report; ONE review.

### V6 — Watch voice integration (with/after Task6 watch base)

Same as V5 for the watch: own enrollment/key/socket, watch-owned TLS through the phone byte relay (phone never
terminates TLS or sees keys), portrait gestures, rings, light haptics, foreground lease, BYOK via the existing
phone client-speech channel carrying only the server-issued range text, no speech of recovered content. Tests as
V5 plus a check that no voice bytes or keys traverse any phone-readable channel other than the opaque relay pipe
and the BYOK text channel.

### V7 — Native fixture acceptance (staged, backed up)

On independently backed-up owned native fixtures (never quarantined guests; no reset/repaint/substitution):
API 29 and current/API 37 phone and watch images where applicable, against an offline registered server fixture
with synthetic providers. Prove encrypted persistence ordering (`recording`/`finishing`/prepare+link) across
process death, no auto speech/submit after restore, audio focus loss, mic permission paths, playback stop on
hide, large fonts/themes/accessibility for the new disabled/draft/unknown states. Each environment operation is a
separately authorized controller step with backup/review; original N6 exact recovery and N10/API 37 preservation
are verified, not assumed.

### V8 — Staged server rollout and authorized physical checks (inside Task8)

Prerequisites: V1–V7 accepted; Task10 N1/N2/M2/O1–O4/N3–N5 and existing server gates; reviewed backup/rollback
(new `relay-voice.sqlite3` is additive; rollback removes flags and the file after backup); per-profile preflight;
one profile first; scoped listener restart only as separately authorized.

Checks: preserving phone/watch updates; Relay off/on compatibility; typed probes; short response playback in
server mode and BYOK; Replay/Stop/More; cross-device silence. Paid budget: ElevenLabs synthesis ≤ 6 short tests
≤ 1,500 input characters total for this feature (inside the owner's 10 tests/2,500 characters), each recorded
with its `speech_id` row; STT on the configured provider limited to ≤ 5 short owner-observed utterances.
Microphone and earbud checks are owner-observed and wait for the owner's return (approved decision 10). No
completion claim before every acceptance item of design §15 has its own evidence.

## 3. Retained obligations by stage

| Obligation | Stage(s) |
| --- | --- |
| `SnapshotBuild.TooMany` before any persist-before-send (ledger + voice) | V4 (pure), V5/V6 (store) |
| Durable retirement of erased enrollment/epoch decisions; voice records retire with the enrollment, no reactivation | V4 contract, V5/V6 store, Task3/4/7 |
| Bounded cumulative refused-message read-through work with truthful incompleteness | Task4/Task7 (unchanged; voice adds none) |
| Truthful user recovery/backoff for `EXPIRED_REPORTED`, `ABANDON_TRACKING` only by user | V5/V6 |
| Task1 M1–M6, R1-M1..M4; Task2 M1–M6, R1-M1..M3 | Consumer/integration/final review as already ruled |
| Existing-history 8,192/65,536 regression; native cache/export N-M1..M3; retention/anchors/rebuild/erasure/SAF | Task4/Task7 |
| Logical-chat auto-TTS guard | V2 |
| Windows privacy verifier for new server file/temp dir | V2 |
| Original N6 exact recovery, N10/API 37 preservation | V7/V8, unwaived |

## 4. Proposed amendments to the root plan (controller-owned, not applied here)

* Replace the Task3 prerequisite paragraph's "split reviewable disabled server, codec and native increments" with
  the V1–V8 sequence above and its exit gates.
* Task3: add "microphone disabled inside selected Relay conversations until V5 is reviewed; no stock fallback".
* Task6: same for the watch until V6.
* Task8: include V8 and the paid/owner-observed budget.

## 5. Acceptance map summary

Design §15 is the requirement-to-evidence table. A stage is complete only with its own report, verified hashes,
named RED/controls and ONE fresh review; source/JVM acceptance never implies native, live, provider, physical or
full-goal acceptance.
