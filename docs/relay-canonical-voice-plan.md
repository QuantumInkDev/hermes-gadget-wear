# Relay canonical voice: serial implementation plan (PROPOSED)

Status: **PROPOSED plan for review together with `relay-canonical-voice-design.md`.** No stage is started,
implemented, tested or accepted by writing it. The controller owns the root plan, its refinement, dispatch,
acceptance and reviews. This plan inserts reviewable voice increments between the accepted pure Task2 state
(private `c0b808b`) and the phone/watch controller tasks; it does not replace O1–O5/N1–N10, the existing Task3–8
requirements, or the fifteen-deliverable goal, and it never treats a text-only controller as preserved voice.

Revision 1 (correction round 1): adds the core display seam and deliberate playback (I1), range coverage
controls (I2) and routing/walking-control stages (I3), and records the Minor dispositions as concrete stage
obligations. Approved Replay/More/notification playback, concise speech and routing are required in V1–V8.

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
V0 design review ─► V1 core voice seam ─► V1-P provider parity ─► V2 SDK voice input ─► V3 SDK voice output
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

V2 depends on V1 **and V1-P** (the bridge refuses to advertise without the exact seam; V2 must not start, and
configured-provider voice must not be claimed, until V1-P passes). V3 depends on V2 (store, flags,
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
`transcribe_native_once(path, *, authorize, before_send, deadline_s)`, `synthesize_native_once(text, *, output_dir,
authorize, before_send, deadline_s)`, `speakable_text(text)`, the display read/locate/check functions and their
existing-source analogs, frozen dataclasses `NativeTranscription`, `NativeSpeech`, `NativeDisplayLocation`
(design §12.1).

Requirements: runner's own single profile config at call time; configured provider once with every automatic
re-send disabled after a request may have reached a provider; explicit timeouts and zero SDK retries for
OpenAI-compatible STT and for the ElevenLabs SDK client (built today with no timeout/retry arguments); pre-send
transcode only; installed-only local STT fallback once; `before_send` invoked immediately before each provider
request and the send refused if it raises; stable enums only; no paths/errors/credentials in results or new log
lines; caller-owned output directory; `authorize` before each send and after each await; deadlines bound waiting,
never claim cancellation. Display functions: `session:read` before and after, visible-row projection only,
identity scalars only for locate, newest-first, ≤ `max_rows`, existing 2 s deadline and progress handler.

Tests (core canonical runner, synthetic home, synthetic provider transport at the provider boundary, no network):
* Semantic old RED (real current behaviour, measured not predicted): a synthetic 5xx/connection-reset provider on
  the existing `transcribe_audio` path with default config issues **≥ 2** provider requests — SDK `max_retries`
  default 1 (`transcription_cloud.py` 53–57) plus the 5xx transcode retry (165–182); the exact count is recorded.
  The same fixture through the new seam issues exactly one. The ElevenLabs TTS path's actual request count and
  timeout under a synthetic 5xx/stall are measured and recorded the same way (no assumed number).
* New controls: configured success; empty; over-limit; local fallback used only after configured failure and only
  if installed; no fallback installation; `before_send`/`authorize` refusal before send → zero provider calls;
  refusal after await → result withheld; output files only in the given directory; log capture contains no
  path/text/key.
* Display seam: exact identity read of plain, multi-segment and multibyte rows equals the existing fragment
  reader's segments; hidden, compacted, deleted, revised and other-lineage identities refused; reader without
  `session:read` refused before any content; locate finds newest-first within bound, returns none past
  `max_rows`, honours the deadline on a 65,536-row lineage (time recorded), and never hydrates content
  (instrumented). Existing-source analogs get equivalent tests once the existing-history regression is resolved.
* ElevenLabs TTS: exactly one HTTP request per core chunk; stops at the first failed chunk (no retry).
* Stock-off controls: existing `run_inbound` STT/echo, `text_to_speech_tool`, history fragment reader and runner
  voice reply behaviour unchanged.

V1 prerequisite coverage (stage amendment, correction round 1) — STT `openai/groq/deepinfra/xai/elevenlabs` and the
cached local `faster-whisper` fallback; TTS `edge/elevenlabs/openai/deepinfra`; every other provider, including the
`local_command` template and all command/plugin providers, is refused UNSENT. Binding corrections: the display fence is
a scalar `NativeDisplayToken` (design §9.2.3), a hooked SDK must be proven to send through the injected client before
it is called and must produce exactly one gated send, consumer callbacks are fenced against close/cancel/deadline on the
owning loop, and the local fallback is cached-only at the model-creation boundary.

Exit: exported patch applies with `git apply --check` to a fresh pinned core checkout; focused report; ONE review.

### V1-P — Provider parity (mandatory, immediately after V1 and BEFORE V2 activation)

The full approved scope is "the runner's single active profile STT/TTS configuration". V1 deliberately covers only the
set above; V1-P closes the rest, each with an explicit exit gate. Never enable a provider by removing the send or
no-install guard; a custom implementation that cannot meet the contract answers truthful unsupported capability
(refused UNSENT). V1-P preserves current profile config/voice semantics for supported built-ins and exercises every
category below on the registered fixtures, then ONE source review and a current-profile integration proof:

| Provider | Exit gate |
| --- | --- |
| STT Mistral | real installed SDK transport, one gated send, zero hidden retries — **source delivered** (patch 0002, V1-P Task 1; source dependency admitted, qualification open): pinned `mistralai` 2.4.8, a configured `stt.prompt` the SDK cannot carry is refused UNSENT |
| STT command and plugin (including `local_command`) | native-once capability adapter: no implicit install/download, explicit cancellation and output bounds; incapable custom implementations refuse UNSENT |
| TTS MiniMax / xAI / Gemini | instrument each actual HTTP boundary, including auxiliary transformations (tag rewrite calls); no hidden second paid request; fail closed before a disallowed send — **source delivered** (patch 0002, V1-P Task 2; source dependency admitted, qualification open): the gate precedes the one `requests.post` in `_post_json`; a native call makes zero auxiliary tag calls (xAI keeps its local pass, Gemini the unchanged transcript; the script differs from an ordinary call's) |
| TTS Mistral | real installed SDK boundary and zero-retry proof — **source delivered** (patch 0002, V1-P Task 1; source dependency admitted, qualification open) |
| TTS NeuTTS / Kitten / Piper | installed AND cached assets only, no downloads, bounded local execution, truthful `not_started` remote accounting, no paid-provider fallback — NeuTTS dependency rows corrected at **source** level (own transformers 5.1.0 / normal phonemizer rows; managed 3.14 stays unsupported; no usable runtime, closure, platform or cache is provisioned or proved) |
| TTS command and plugin | the same native-once capability contract as STT; an arbitrary program cannot be certified single-attempt by a provider-name allowlist |
| Pre-send credential freshness (xAI OAuth, M2) | refresh BEFORE the paid send with synthetic transport proof; never refresh-and-resend after a send — **source delivered** (patch 0002, V1-P Task 2; source dependency admitted, qualification open): real resolver/pool/token store over a synthetic OAuth transport; an OAuth bearer without a future `exp` is refused UNSENT; the exchange is never a paid attempt and every actual OAuth request is authorization-probed on the consumer loop and a refusal is terminal for the call, including under STT auto-detect (an admitted POST may still persist its rotated token) |
| Edge full range and the joiner (round-2 N2) | test a full-range (> 600 code points) Edge call with the real ffmpeg joiner and the missing-joiner outcome (`unavailable`/`not_started` before any send, no paid fallback, no silent truncation); concise ≤ 600 code points independent |
| Auxiliary native audio (AUX-audio) | source: wrapped STT preparation (optional probe/trim skipped without a qualified launcher, required CAF/compaction, one-request eligibility), native Silk unsupported (WAV ingress), Gemini converter preflight and wrapped conversion; runtime qualification of the launcher, `afconvert`, codecs and per-spawn latency remains required and unwaived |

Until V1-P passes, V1 alone is not configured-provider acceptance and V2 cannot start. The full fifteen-feature scope is
unchanged.

Qualification follow-up status (source and controller-tool authoring only; this is NOT V1-P completion and activates nothing):
core test `tests/gateway/test_native_voice_profile_integration.py` (sentinel ElevenLabs/STT profile shapes over the real seam, the
installed ElevenLabs SDK, stock config/secret scope and the registered authority; Stop and a revoked grant are proved with a
two-chunk speech whose second chunk is never sent) and controller tools that have not been run (a Darwin 27 arm64 host harness, a
read-only profile-metadata extractor, a shared bootstrap helper and one detach fixture) over a separate, hash-pinned controller
runtime with a pre-execution import-origin admission. Still required and unwaived: the
controller's host and metadata runs, inspection of the ACTUAL initialized plugin registry and tracer provider (M1; static
declarations or a source-registered fixture never close it), provider-real media, the NeuTTS/Kitten/Piper engine runtimes,
other-box qualification at the per-box rollout gate, the remaining stock compatibility of Task 4 and the review of this follow-up.
A first reviewed host run measured one file-descriptor ownership gap of the shared command runner (a reader that outlived the consumer left its stream open after a late EOF); the patch now
includes the minimal owner fix and a real-pipe regression. Host qualification stays OPEN and a changed-head host recheck is a separate controller step.
V2 stays blocked until the mandatory V1-P proof exists.

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
  before accounting; durable row before provider call (crash injection at each §10.2 server row: `queued`/
  `preparing` → `failed(interrupted)` with `not_started`, `transcribing` → `unknown`; a decode failure never
  reports `in_flight`; zero extra provider calls after restart); result long-poll, TTL erasure, discard
  before/after seal; transcript never returned after revoke.
* Log capture (M2): every `relay.voice.*` request and response handled with captured logs/diagnostics shows no
  transcript, audio, path, provider message or key; transcripts appear only in the author's own response frames.
* End-to-end single admission: capture → result → existing `relay.turn.submit` with the transcript → exactly one
  core admission; duplicate `finish`, concurrent `finish`, repeated `result`, reconnect + `result`, cancelled STT
  and lost responses produce zero additional admissions and zero additional provider calls; stock device chat
  untouched (no `on_utterance`, no transcript echo).
* Stock-off positive controls: flags off → `relay.info` methods identical to current; stock `audio.start`/channel
  1 upload still reaches `on_utterance` with its existing permissive semantics; stock welcome unchanged; existing
  relay suites' affected subset unchanged.

Exit: exported patch, fresh pinned-source reapplication, focused report with call counts; ONE review.

### V3 — SDK voice output

**Mandatory core prerequisite BEFORE any V3 consumer relies on `NativeDisplayToken` (round-2 N1):** extend the token
with the `display_kind` (≤ 128 bytes) and compressed-summary scalars (or equally strong sibling-fingerprint semantics)
and prove it with a visible-assistant → hidden-after-read test (token mismatch or `not_found`). V3 does not activate
before this gate; it is a staged prerequisite, not a waiver (design §9.2.3). V3 and user setup also expose the ffmpeg
joiner prerequisite for full-range Edge speech (design §12.1, round-2 N2); the final rollout verifies it on each
supported box.

Scope (PROPOSED): `relay.voice.speak`/`speak.stop`; live selection (journal + core lookup + `message_id`
mapping, author-only, window, one live row including client mode); deliberate `replay`/`more` selection by
canonical `message_id` (verified alias, else core locate) under `relay:read` + membership + current binding for
any enrolled device; exact canonical text via the V1 display read; ranges, styles, provenance and source digest
(design §9.4); server streaming with the batch fence under the send lock; client mode with chunks (existing
client-speech gates); voice policy (`speak_replies`, device `/voice off`); existing-source deliberate playback
after its regression is resolved.

Tests (same registered rig, synthetic V1 TTS provider):
* Live: author-only (other device/subscriber/shared receipt → refused, no provider call); `no_final` for failed/
  cancelled/steered; final not yet visible → `temporarily_unavailable` then success with the same `speech_id`;
  recovered finals, O5 copies, existing-source and task copies never produce a `live` call; voice-off → `off`;
  live window `expired`; second live `conflict` in server and client mode.
* Deliberate (I1): bridge restart, then Replay by `message_id` from a second enrolled device holding `relay:read`
  succeeds from canonical storage; alias evicted (4,096 + 1 aliases) → locate succeeds and re-creates the alias;
  `beyond_index` past the locate bound; deleted/hidden/revised message → `not_found`; revoked membership →
  `permission_denied`; tombstone → `not_found`; user-role message → `not_speakable`; binding to another
  conversation → refused; recovered content still yields zero `live` calls.
* Coverage (I2): for full and concise, request ranges from 0 with More until `exhausted` and assert the
  concatenation equals `text[0:speakable_end_cp]` exactly — over-long unbroken units (ASCII, CJK without
  spaces, emoji ZWJ sequences and skin-tone modifiers, regional-indicator pairs, combining marks), exact cap
  boundaries in code points, UTF-8 bytes and UTF-16 units, empty/whitespace text, sources above 262,144 B
  (`limited`), interrupted-range resume, `start_cp` provenance refusal, source change `conflict`; concise ranges
  never exceed 3 sentences; client chunks concatenate to their range.
* Fences: revoke, rebind, epoch change and stop during synthesis and mid-stream → no further batch after the
  failing check (frame log under the send lock), `aborted`/`stopped` recorded, provider never claimed cancelled.
* Accounting: `preparing` before the canonical read, `in_flight` only via `before_send`; repeated speak = receipt
  read; crash injection → `failed(interrupted)` or `unknown` as designed, no re-synthesis; hourly budget before
  accounting; temp files removed; log capture as V2.
* Fence performance gate (M4): with two concurrently speaking sessions and one stock session on the rig, record
  p99 full-check time and p99 added delay to the stock session's frames; pass only at ≤ 10 ms and ≤ 20 ms with
  the ≤ 200 ms batch shape; otherwise implement the pre-approved per-generation cached grant snapshot (still
  checked per batch) and re-measure; any other relaxation needs a new review.
* Stock-off controls: device-chat auto-TTS, `tts.speak` client speech and stock streaming TTS unchanged.

Exit: exported patch, reapplication, report; ONE review.

### V4 — Native voice codec and pure capture ledger (Android-free protocol module)

Scope (PROPOSED): `RelayMethod` additions with bounds; package `protocol.relay.voice`: `VoiceInfo`,
`CaptureResult`, `SpeechResult`, call builders, channel-5 frame codec (uplink/downlink kinds), pure
`VoiceCaptureLedger` (states §8.4, bounds, scope invalidation) and its snapshot section combined atomically with
`RelayStateSnapshot` (version bump of the aggregate snapshot with `TooMany` paths for voice), the pure range
coverage ledger (§9.4: issued boundaries, completed ranges, next `start_cp`, resume of interrupted ranges), redacted
`toString`, owned bounded collections. Ordering (M7): V4 is reviewed before the Task3 encrypted store is written;
if Task3's store has already landed, V4 adds a preserving v1→v2 snapshot migration that keeps every turn,
including `UNCERTAIN` and `EXPIRED_REPORTED`, and is proven by restore tests.

Tests (JVM only): captured vectors from V2/V3 fixtures (method success/error/state shapes, frame streams) with
provenance; constructed negative vectors labelled; strict refusal of unknown/missing/wrong-typed members and
version ≠ 1; frame sequence/size/kind/direction rules; ledger: capture→request link prepared atomically with
Task2 `prepare`, never re-prepared after restore, `draft_offered` never auto-submits, scope change invalidation,
`TooMany` before persist-before-send; coverage ledger reconstructs exact concatenation from captured V3 range
vectors, never advances past an uncovered range, resumes interrupted ranges at their start; migration preserves
all turns when applicable. Controls: stock `Message`, `BinaryFrame` (channel 5 still ignored unless
enabled), stock negotiation/capabilities, existing `Info` decode with unknown voice method names (old builds
unaffected), all Task1/Task2 suites' affected subset.

Exit: report; ONE review. No controller, Android or transport side effects.

### V5 — Phone voice integration (with/after Task3 phone controller base)

Depends on: Task3 controller base (negotiation, info gating, own enrollment/profile pinning, binding lifecycle,
encrypted durable ledger store with persist-before-send and `TooMany` handling, receipt-first reconcile) and V4.

Scope (PROPOSED): consumes Task3's raw `relay.response` routing in `DirectConnection` (Task3 is its single owner;
V5 does not re-implement it); adds the voice binary sink per socket generation and non-failing voice uplink
backpressure; phone voice controller wiring hold/release/slide-left, START/FINISH/CANCEL cues, recording timer,
input/output waveforms, BYOK client mode via `PhoneClientSpeech` with chunks, playback via existing `PcmPlayback`
plus the PROPOSED preferred-device and muted-until-verified options, `SpeechRoutePolicy` with the per-device
`AUTO`/`EARBUDS_ONLY`/`SPEAKER` setting, deliberate Replay/More/Resume and notification Play (unlock, foreground,
attach, refresh first), walking concise mode and accessible walking controls, every lifecycle row of design §11,
intent window and draft path, truthful billing/unknown notices, `EXPIRED_REPORTED` backoff/recovery UI and
`ABANDON_TRACKING`.

Tests: JVM controller tests against captured vectors and an owned offline loopback of the V2/V3 registered fixture
(no device); every §11 row; process-loss restore from the encrypted store (JVM-level persistence contract; native
durability is V7); routing (I3): the `SpeechRoutePolicy` decision table for each setting × connected outputs, no
speak request without an allowed route, muted-until-verified start, stop-first ordering on noisy/focus/route
change/setting change (generation incremented before stop, no frame offered after), deliberate Resume only on the
newly allowed route, no automatic move to a louder route; coverage ledger and More/Resume offsets; notification
Play never speaks before unlock, foreground and fresh attach; BYOK `endpointId` ↔ Relay profile mapping and
`profile_changed` freeze; chunk failure stays uncovered without automatic re-send; accessibility semantics
(labels, state descriptions, live-region announcements, ≥ 64 dp walking targets) in Compose UI tests; stock-off:
Relay off and non-Relay screens keep stock hold-to-talk byte-for-byte (stock `audio.start`/frames).
Androidtest sources may be added but are not run here.

Exit: report; ONE review.

### V6 — Watch voice integration (with/after Task6 watch base)

Same as V5 for the watch: own enrollment/key/socket, watch-owned TLS through the phone byte relay (phone never
terminates TLS or sees keys), portrait hold-to-talk, horizontal profile swipes, rings, start/stop ticks, light
haptics, foreground lease, watch routing (earbuds preferred, watch speaker fallback, per-watch override, stop-first
on loss), walking controls on the reply surface, deliberate Replay/More of replies initiated on the phone, BYOK
via the existing phone client-speech channel carrying only server-issued chunk text with watch-local output
fencing (the phone may finish a paid chunk after revoke; it is discarded unplayed), no automatic speech of
recovered content. Tests as V5 plus a check that no voice bytes or keys traverse any phone-readable channel other
than the opaque relay pipe and the BYOK text channel.

### V7 — Native fixture acceptance (staged, backed up)

On independently backed-up owned native fixtures (never quarantined guests; no reset/repaint/substitution):
API 29 and current/API 37 phone and watch images where applicable, against an offline registered server fixture
with synthetic providers. Prove encrypted persistence ordering (`recording`/`finishing`/prepare+link) across
process death, no auto speech/submit after restore, audio focus loss, mic permission paths, playback stop on
hide; routing with real `PcmPlayback`: muted-until-verified start, routed-device observation on the speaker,
focus-loss and any platform-permitted becoming-noisy/route-change injection with stop-first ordering (Bluetooth
earbuds cannot be proven on emulators and stay a V8 owner observation); accessibility of walking controls with
TalkBack semantics, 1.3+ font scale, three themes and reduced motion, and of the new disabled/draft/unknown/
stopped states. Each environment operation is a
separately authorized controller step with backup/review; original N6 exact recovery and N10/API 37 preservation
are verified, not assumed.

### V8 — Staged server rollout and authorized physical checks (inside Task8)

Prerequisites: V1–V7 accepted; Task10 N1/N2/M2/O1–O4/N3–N5 and existing server gates; reviewed backup/rollback
(new `relay-voice.sqlite3` is additive; rollback removes flags and the file after backup); per-profile preflight;
one profile first; scoped listener restart only as separately authorized.

Checks: preserving phone/watch updates; Relay off/on compatibility; typed probes; short response playback in
server mode and BYOK; Replay/Stop/More including a Replay on the watch of a phone-initiated reply and a Replay
after a server restart; notification Play after unlock; walking concise speech; cross-device silence for
automatic speech; owner-observed earbuds-preferred routing, speaker fallback, per-device override and
earbud-disconnect stop with no speaker fallthrough.

Paid budget (M8): before any V8 synthesis, the controller reads the owner's paid-test ledger for prior O4/server
use and computes the actually remaining part of the owner's 10 tests / 2,500 characters; V8 uses at most
min(6 tests / 1,500 characters, that remainder). It is a cap inside the original allowance, never a refill; if
the remainder cannot be established or is too small, stop and ask the owner. STT: no automated paid STT run is
authorized; the physical STT evidence is the owner's own normal Hold-to-Talk use observed during V8. Any extra
paid case later needs a concrete bounded proposal and explicit owner approval first. Microphone and earbud checks
are owner-observed and wait for the owner's return (approved decision 10). No completion claim before every
acceptance item of design §15 has its own evidence.

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
| Deliberate playback across devices/history/restart (I1) | V1 display seam, V3, V5/V6, V7, V8 |
| Exact range coverage and concise cap (I2) | V3 rig, V4 JVM, V5/V6 |
| Routing, stop-first loss and walking controls (I3) | V5/V6 JVM, V7 native, V8 owner observation |
| M1 billing evidence (`preparing`, `before_send`) | V1, V2, V3 |
| M2 content-free logs, confined content responses | V2, V3 log capture; N8 |
| M3 provider timeout/retry provenance and measured RED | V1 (covered providers), V1-P (the rest) |
| M2 pre-send credential freshness (no refresh-and-resend) | V1-P |
| M10 exhaustive `(state, provider)` → wire table with a parametrized test (no new wire value) | V2 (STT), V3 (TTS) |
| Round-2 N1 token visibility scalars (`display_kind`, compressed-summary) | core prerequisite before V3 activation |
| Round-2 N2 Edge 600-code-point bound and ffmpeg joiner | V1-P tests, V3/user setup, final rollout |
| M4 output fence cost gate | V3 |
| M5 registry gaps (removed; canonical read) | V1, V3 |
| M6 BYOK live row, chunk limits, watch delegation boundary | V3, V5, V6 |
| M7 single owners and snapshot ordering | Task3, V4, V5 |
| M8 remaining paid budget check; no automated paid STT | V8 |
| Original N6 exact recovery, N10/API 37 preservation | V7/V8, unwaived |

## 4. Proposed amendments to the root plan (controller-owned, not applied here)

* Replace the Task3 prerequisite paragraph's "split reviewable disabled server, codec and native increments" with
  the V1–V8 sequence above and its exit gates.
* Task3: add "microphone disabled inside selected Relay conversations until V5 is reviewed; no stock fallback".
* Task6: same for the watch until V6.
* Task8: include V8, the remaining-budget check and the owner-observed STT/earbud evidence.
* Task4/Task5: deliberate playback from history and notifications consumes V3/V5 (no automatic speech).

## 5. Acceptance map summary

Design §15 is the requirement-to-evidence table. A stage is complete only with its own report, verified hashes,
named RED/controls and ONE fresh review; source/JVM acceptance never implies native, live, provider, physical or
full-goal acceptance.
