# Relay canonical conversation voice: proposed contract (design gate)

Status: **PROPOSED design for one fresh independent design review.** Nothing here is implemented, captured,
tested, installed or activated. Every method, field, channel, record, module, function and number marked
PROPOSED or POLICY does not exist in current source. This document refines the next serial prerequisite of
the native Relay plan; it does not replace O1–O5/N1–N10, narrow Task3/Task6 to text, or accept any native,
server, provider, device or live behaviour. Validation behind it is document/source consistency only.

Labels used throughout:

| Label | Meaning |
| --- | --- |
| [SRC] | Fact read in the pinned current source (witness in §1). |
| [WIRE] | Existing wire fact of current source (stock Gadget v1 or Relay v1). |
| [PROPOSED] | New contract element this design asks a later increment to implement. Not in source. |
| [POLICY] | Proposed finite local/server policy value; tunable later by review, never a wire claim unless it is advertised by a PROPOSED wire field. |

## 1. Source baseline and witnesses

Read-only, no imports or execution. Hashes are SHA-256 of the working files (they equal the pinned values in
`task3-preparation/*source-pins.json` where those name the file).

| Source | Pin |
| --- | --- |
| Admitted app (private clone of) | `c0b808b536b06e900f3d5cd3cb6c32790143af51`, tree `573f32fa147f5a416b05698ee97efae5b5ad166c`, 954 tracked |
| SDK `continuity-sdk` | `286e13482f12a71b1eb81e66c43470df09da2f05`, tree `6fbdf453f23295281227918bdbca990ec3addf64` |
| Runtime core `continuity-core` | `7c17e6d72a3b9ec2974b3566f02dab1ed4b94eae`, tree `680abe7642528e1b5d85a9bf1ba9f3d1d6f5c5a9` |

Key witnesses (path:lines, SHA-256 prefix):

* SDK `plugin/hub.py` (1bd609d4…): `send_json(..., authorize=)` re-authorizes under `_send_lock` 304–311,
  `send_binary` has no authorize hook 313–315; `_on_text` relay path 721–761; stock `_on_binary` routes only
  `CHANNEL_AUDIO` to `session._upload`, PCM gaps only counted, truncation silently capped 814–836;
  `_h_audio_end` always calls `delegate.on_utterance` 838–858; `MIN_UTTERANCE_S=0.25`, `MAX_FRAME_BYTES=1<<20`
  33–34; `AUDIO_FRAME_MS=40`, `PLAYBACK_LEAD_S=0.5` 30–31; welcome builds `audio`/`tts`/`relay` 662–678.
* SDK `plugin/protocol.py` (57d1efa3…): `parse_binary` accepts channels 1–3 plus explicit extras 41–45; channel
  4 is the pet asset channel 30.
* SDK `plugin/adapter.py` (04b32259…): logical `relay-<uuid>` chats have no `DeviceSession` 267–283; stock
  `_source` uses `chat_id=session.device_id` 633–636; `on_utterance` → VOICE `MessageEvent` 658–662; transcript
  echo → `send_transcript` 728–732; `_should_auto_tts_for_chat` falls through to the base default for a logical
  chat 867–877; `_play_file`/streaming TTS need a physical session 879–933; flags 150–167.
* SDK `plugin/relay_conversations.py` (1a08004d…): `context()` derives `RelayContext` and checks key generation
  and `(server_id, profile_id)` namespace 150–159; `output_check` captures epoch/binding until send 203–216;
  `relay.info` method/limits assembly 440–469; dispatch, ≤16 active requests per session, `send_json(...,
  authorize=check)` 421–544; `submit` reserves the journaled intent then core admission 620–665; `receipt`
  667–685.
* SDK `plugin/relay_core.py` (e12753dc…): exact version/signature validation of the core bridge, single-profile
  refusal 62–102; journaled TEXT event rebuild 113–125; command classification 714–722; opaque public ids
  725–774.
* SDK `plugin/relay_shared_delivery.py` (b7ae927b…): final text delivery to a logical chat with **no speech
  fanout** 1–7; `capture` binds text to the exact journaled request, terminal receipt and `final_display_id`
  64–103; live `send` 144–165 and boot `recover` 167–174 both call `capture`.
* SDK `plugin/relay_store.py` (16c29134…): `uuid7` freshness 97–108, `RETENTION_MS`/`FUTURE_MS`/`MAX_REQUESTS`
  17–22, journal identity 728–730, `_intent` 760–778, `reserve_turn` 780–819, `check_admission` 983–1004.
* SDK `plugin/relay_grants.py` (a8afea72…): closed `PERMISSIONS` set 20–22 (an unknown permission invalidates the
  whole document 82–87), `RelayContext` 118–129 (holds `server_id`), `authorize` 189–199.
* SDK `plugin/relay_service.py` (e0d70174…): 20-token/10 s⁻¹ bucket 191–198; `_bounded` 49–72.
* SDK `plugin/relay_events.py` (1b80bbcd…): pull-only binding queue, `authorize` 44–51.
* SDK `tests/relay_bridge_support.py` (9080a8e1…): registered real core runner/`SessionAuthority`/adapter rig
  with only the socket and provider-execution boundary replaced 1–54.
* Core `gateway/session_native_admission.py` (ce8af183…): `NativeAdmissionOptions` 22–27,
  `NativeResultSummary.final_display_id` 30–37, `_text_event` refuses non-TEXT, media, commands, >4000 chars,
  >16384 bytes 83–92.
* Core `gateway/platforms/base.py` (fb494f0a…): ordinary vs trusted admission 3992–3998; auto-TTS default
  2086–2094.
* Core `gateway/run_voice.py` (e31cca61…): `_should_send_voice_reply` 371–422.
* Core `gateway/run_inbound.py` (cf1bcfa5…): configured STT then installed local fallback and empty handling
  1867–1891.
* Core `tools/transcription_tools.py` (bd0402c2…): `transcribe_audio` 566–588, installed-only
  `transcribe_audio_local_fallback` 591–602.
* Core `tools/transcription_cloud.py` (af7bdcee…): OpenAI STT client `max_retries` default 1 43–57; transcode
  then retry once 174–178; xAI OAuth refresh-and-retry 303–311.
* Core `tools/tts_tool.py` (3f4f8d60…): active-profile config at call time 116–149; `text_to_speech_tool`
  normalizes, chunks, synthesizes sequentially and logs saved paths 457–510.
* Core `tools/tts_tool_providers.py` (d8dd3b16…): 60 s provider request timeout 153–155; ElevenLabs client
  219–222 (its SDK retry default is not visible in pinned source).
* App `protocol/.../DirectConnection.kt` (bcd7ed82…): one socket, generation fence, bounded 32-slot executor
  65–162; every text frame enters stock `Message.parse` 293–302; binary frames parsed with only channel 4 as an
  extra 303–334; uplink `queueSize() > 32000` fails the connection 590–599.
* App `protocol/.../Conversation.kt` (0bd97218…): stock `audio.start`/frames/`audio.end`/`audio.cancel`
  475–600, 762–774; PCM frame 2..640 bytes 511; 60 s cap 516/694; 0.25 s minimum (8000 bytes) 583; speaker
  `audio.start` validation 357–380; downlink payload ≤ 32,000 and strict sequence 410–430.
* App `protocol/.../relay/RelayConversationDtos.kt` (5270707a…): `Info` 152–240 keeps unknown method names
  (`unknownMethods` 181) but its `limits` object is exact-key (218–227); no `server_id` field.
* App `protocol/.../relay/RelayNegotiation.kt` (5ed82312…): `NOT_OFFERED`/`NEGOTIATED`/`INVALID` 7–43; method
  gate 49–52; `RelayScope`/`ConversationKey` are labels, never authority 55–90.
* App `protocol/.../relay/RelayMethod.kt` (4da74e62…): per-method response byte/depth bounds 10–37.
* App `protocol/.../relay/state/TurnLedger.kt` (68a5d427…) and `docs/relay-state-contracts.md` (0aa7e80f…):
  `prepare` is the only id mint; `PersistBeforeSend`; receipt-first `UNCERTAIN`, `EXPIRED_REPORTED`,
  `ABSENT_SO_FAR`, `ABANDON_TRACKING`; `SnapshotBuild.TooMany`.
* App `mobile/.../PhoneConversation.kt` (1de93484…): hide increments epoch first 633–661; hold/release/cancel
  562–601; capture/level/playback/BYOK effect handling 231–393; per-instance capabilities 395–427.
* App `wear/.../GadgetService.kt` (41e0766a…), `WatchSpeech.kt` (d630d0cf…), `WatchRelay.kt` (eb41ff5f…):
  watch-owned socket through the phone byte relay, watch BYOK via a phone speech channel, rings/haptics.
* App `docs/superpowers/specs/2026-10-18-relay-expansion-design.md` (3328932c…): approved owner decisions
  (server voice default, phone BYOK per profile, walking mode concise speech + full text + Replay/Stop/More,
  no automatic spoken playback of notifications/recovered content, paid-test budget).

Full witness hash list: `.local/canonical-voice-design/evidence/source-witnesses.txt` (outside Git).

## 2. Problem, restated from source

1. Stock hold-to-talk uploads PCM/Opus on channel 1 to the **physical device chat** and always becomes an
   ordinary VOICE turn (`_h_audio_end` → `on_utterance` → `handle_message`). It permits PCM gaps and silent
   truncation. Attaching a Relay binding does not retarget it. [SRC]
2. Relay admission is a **TEXT-only trusted seam** on a **logical route** (`relay.turn.submit` →
   `NativeAdmissionOptions`). Core refuses VOICE, media and commands there. [SRC]
3. The stock post-ingress transcript echo is produced after an ordinary turn already started executing. Using
   it as Relay input would execute speech twice. No transcription-only method exists. [SRC]
4. Shared final delivery is text-only; a logical route has no speaker. Server voice playback needs a physical
   `DeviceSession`. [SRC]
5. `relay.info` has no independent `server_id`; its `limits` object is exact-key on the native decoder, so a new
   limits member would break current native clients. Unknown method names are tolerated. [SRC]
6. Grant documents have a closed permission set; adding a new permission string makes an older SDK refuse the
   whole grant document. [SRC]
7. Existing STT/TTS helpers resolve the active profile at call time but contain internal automatic retries
   (OpenAI `max_retries=1`, transcode retry, OAuth refresh retry); ElevenLabs SDK retry behaviour is not visible
   in pinned source. Reusing them as-is cannot prove "no automatic paid retry". `text_to_speech_tool` logs output
   paths. [SRC]
8. Latent risk, source-derived and **unproven**: for a logical Relay chat the adapter's auto-TTS probe falls
   through to the global `voice.auto_tts` default; `_should_send_voice_reply` could then synthesize (paid) a reply
   that `_play_file` cannot deliver. This must be confirmed or refuted by a registered fixture (plan V2 RED).

## 3. Transaction choice, alternatives and rulings

### 3.1 Chosen transaction (PROPOSED)

```
 device (phone/watch, own enrollment+socket)            server (SDK bridge + core seam)
 ─────────────────────────────────────────────          ────────────────────────────────────────────
 hold ──► relay.voice.capture.start ───────────────────► authorize(relay:submit, current binding)
          binary ch5 kind 0x01/0x02 frames ────────────► strict assembly (no stock _upload, no on_utterance)
 release ─ persist capture "finishing" (encrypted) ─┐
          relay.voice.capture.finish ──────────────────► full recheck; DURABLE paid-attempt record; seal
                                                         core transcribe_native_once (configured STT, once;
                                                         installed local fallback, free) — NOT an admission
          relay.voice.capture.result (long-poll) ◄─────── transcript (bounded, TTL) / truthful state
 TurnLedger.prepare(transcript) + capture→request link
   persisted atomically (encrypted)
          relay.turn.submit (EXISTING, unchanged) ─────► existing journal → core TEXT admission (exactly one)
          relay.turn.receipt (EXISTING, receipt-first)
 final shown; foreground initiating request only:
          relay.voice.speak(request_id, mode=live) ────► author-only exact final (journal+core lookup);
                                                         DURABLE paid-attempt record; core
                                                         synthesize_native_once (profile TTS/ElevenLabs);
          binary ch5 kind 0x11/0x12/0x13/0x14 ◄──────── every frame re-authorized under the send lock
 Stop/hide/... ─ local stop first, relay.voice.speak.stop
```

Capture identity (`capture_id`) and admission identity (`request_id`) are distinct UUIDv7 values with distinct
jobs: the first names one paid-or-not transcription, the second the single durable TEXT admission.

### 3.2 Alternatives considered

| Option | Verdict | Reason / cost |
| --- | --- | --- |
| A. Transcription-only capture, client-persisted immutable TEXT turn through existing `relay.turn.submit` (chosen) | Chosen | Reuses Task2 ledger and the unchanged TEXT seam; transcript is persisted before submit; receipts settle uncertainty. Cost: one extra round trip and new server STT/TTS methods. |
| B. Server chains STT directly into `reserve_turn`/admission with a client-supplied request id | Rejected | The client cannot persist immutable text before submit; Stop/hide/selection change after release could not prevent admission; mixes capture and admission identities; new server admission entry point. |
| C. Android recognizer / platform TTS on device | Rejected | Replaces the profile's configured server STT/voice (approved requirement 3); not equivalent acceptance. |
| D. Extra field on stock `audio.start`/channel 1 | Rejected | Stock servers ignore unknown fields and execute an ordinary device-chat turn: unsafe silent fallback. Inherits permissive gaps/truncation. |
| E. Broaden `NativeAdmissionOptions` to VOICE/media | Rejected | Weakens the reviewed TEXT-only trusted seam. |
| F. Reuse the post-ingress transcript echo | Rejected | The utterance is already executing; resubmission duplicates execution. |
| G. Base64 audio inside `relay.request` frames | Rejected | 33 % overhead, shares the RPC rate bucket, 64 KiB frame chunking; existing bounded binary framing exists. |
| H. Speech text supplied by the client and digest-verified | Rejected for now | Live and history text normalisation may differ; adds a durable digest table; server-held exact text is simpler. |
| I. New core `read_native_final` seam for speakable text | Deferred enhancement | Would allow Replay after a server restart; extra core surface not needed for approved behaviour (§9.6). |
| J. Model-generated concise summary for walking mode | Not chosen (isolated) | A second paid LLM call and new content; approved "short spoken answers" is met by deterministic leading sentences (§9.4). Choosing a summary is a separate owner decision. |

### 3.3 Rulings (routine, within approved scope; reviewer may challenge)

* R-1 No new grant permission. Capture/STT requires the existing `relay:submit`; speech requires `relay:read`
  and journal authorship. A new `relay:voice` string would make older SDKs deny every Relay request (§2.6).
  Paid exposure equals stock (any paired speaker device already triggers STT/TTS).
* R-2 Auto-submit of a transcript happens only on the **same** transport generation and binding, with
  continuous foreground selection, inside the client intent window. Any reconnect, rebind, hide, selection or
  profile change, or process loss between release and transcript turns the transcript into an **unsent
  editable draft**; sending it is a new deliberate turn with a new request id.
* R-3 The admitted text is the plain transcript (as if typed), not stock's quoted voice-note form. Relay
  admission is TEXT, so the runner's `voice_only` mode is not triggered; Relay speech is the explicit path below.
* R-4 Live (automatic) speech honours server `speak_replies`, the device's stock chat `/voice off`, and the
  client's per-profile speech setting. Replay and More are deliberate user actions and are allowed when server
  speech is enabled at all.
* R-5 Speakable text is held only in a bounded in-memory server registry filled at the authoritative live
  final capture; a server restart makes speech of older replies `unavailable` (truthful; text unaffected).
* R-6 Live server speech requires the logical-chat auto-TTS guard (§2.8) so no reply is synthesized twice or
  synthesized undeliverably.

## 4. Optional negotiation and compatibility (requirement 1)

### 4.1 Layers (all must hold; any failure = voice unavailable for that socket generation)

1. Stock handshake unchanged [WIRE]: hello `caps.relay={"version":1}` only from Relay-capable builds; welcome
   `relay` exactly `{"version":1}` → `NEGOTIATED`; absent → `NOT_OFFERED`; other → `INVALID` (never downgraded).
2. `relay.info` (unchanged shape) lists the PROPOSED voice method names in `methods` only when the server flags
   and core seam are present and the caller's grant holds the needed permission (§12.1). No new `limits` member
   (exact-key native decoder).
3. `relay.voice.info` [PROPOSED] returns an exact-key DTO with `version: 1`. A client accepts voice only if
   `version == 1` exactly, every member decodes strictly, and at least one input format and (for server output)
   one output format intersects its own probed set.
4. Binary channel 5 is enabled for the socket (native parser `additionalChannels += 5`, server routing) only
   after layer 3 succeeded **on this socket generation**. Before that, both ends drop channel-5 frames.

### 4.2 Behaviour table

| Situation | Hold-to-talk inside a selected Relay conversation | Stock device-chat voice |
| --- | --- | --- |
| Relay off (`NOT_OFFERED`, stock v0.2.0 server or flag off) | No Relay conversation is selectable; stock UI unchanged | Unchanged stock v0.2.0 |
| Relay `INVALID` welcome | Relay unavailable, error shown | Unchanged |
| Relay negotiated, voice methods not listed | Microphone disabled with "Voice isn't available for this conversation — type instead" | Unchanged (only when no Relay conversation is selected) |
| `relay.voice.info` error, malformed, `version != 1`, no common format | Same disabled state; nothing uploaded | Unchanged |
| Voice negotiated | Canonical voice path only | Unchanged when not in a Relay conversation |

There is **no** silent fallback from a selected Relay conversation to stock `audio.start` or to the device
chat. Malformed or unsupported voice answers are refusals, never success, and never trigger a stock turn.

### 4.3 Exact refusal rules

* Unknown/missing/wrong-typed members, extra members, out-of-range numbers, non-UUIDv7 ids, unknown enum tokens
  in any voice DTO → native `RelayOutcome.Invalid`; the action is not performed and voice is disabled for the
  socket generation until a fresh `relay.voice.info` succeeds.
* Server: any voice request with extra/missing params → `invalid_request`; voice method not advertised for the
  caller → `unsupported` **after** authorization (authorization precedes discovery, as today).
* Existing stock parsers, the 64-depth stock `Message.parse`, stock binary channels 1–4 and every stock limit are
  unchanged. Channel 5 never reaches `Conversation.binary` or the stock `_upload`.

## 5. Identity, authority and ownership (requirement 2)

### 5.1 Evidence actually available

| Fact | Client side | Server side |
| --- | --- | --- |
| Endpoint | Configured `Endpoint` (TLS URL); watch traffic is watch-owned TLS through the phone byte relay [SRC] | Listener |
| Enrollment/key | Per-device, per-endpoint `DeviceIdentity` (phone `ConversationVault`, watch `IdentityVault`); keys never leave the device [SRC] | `session.auth_key_sha256` == `ctx.key_generation` [SRC] |
| Server identity | **Not on the wire** (Info has no `server_id`) | `ctx.server_id`, pinned by store namespace [SRC] |
| Profile | `relay.info.profile_id` (opaque) [WIRE]; catalog/display ids are labels, never authority | `ctx.profile_id`, namespace check [SRC] |
| Grant | `relay.info.grant_generation`, `expires_at_ms` [WIRE] | `RelayContext` re-derived per request [SRC] |
| Conversation | Server-issued `conversation_id` under the client `ConversationScope` label [SRC] | Registry membership [SRC] |
| Binding | Transient `binding_id` + `authority_epoch`, never persisted [SRC] | `bindings[id(session)]` identity [SRC] |
| Core authority epoch | `Binding.authorityEpoch` [WIRE] | `core.authority.epoch` [SRC] |
| Execution generation | Receipt/binding `execution_generation` [WIRE] | Core receipt [SRC] |
| Local callback | Phone `epoch`, `playbackVersion`; `DirectConnection.generation`; PROPOSED `captureGeneration`, `speechGeneration` | `DeviceSession` object identity |

The client's local scope label is Task2's `EnrollmentScope(RelayScope(endpointKey, profileId), enrollmentKey,
reviewEpoch)`. A change of `profile_id` for an endpoint, an enrollment replacement or a review-epoch change
invalidates every voice record of the old scope locally (no auto-submit, no speech). The client never invents a
server identity; the server never trusts a client profile, catalog id or path.

### 5.2 Authority checkpoints

Server (SDK, PROPOSED module `relay_voice`, re-using `Conversations.context/recheck/binding/output_check`):

| # | Point | Check |
| --- | --- | --- |
| S1 | `capture.start` | `context(session,'relay:submit')`; binding is the session's current active binding; `authority_epoch` equals binding epoch; binding conversation is a current member under `relay:submit`; capture id fresh; no live capture on session; capacity |
| S2 | Each uplink frame (hub loop) | Session object is the capture owner; capture live; the session's current binding object is still the capture's binding; core epoch unchanged (cheap fence, no I/O) |
| S3 | `capture.finish` | S1 again in full (fresh grant/key/namespace/membership/binding/epoch) **before** the durable paid-attempt record and before any provider call |
| S4 | After the STT await | Re-derive context for the record's device; if denied, key changed or namespace differs → state `revoked`, transcript erased, never returned |
| S5 | `capture.result` / `discard` | `context(session,'relay:submit')`; record `(server_id, profile_id, device_id, key_generation)` equals ctx; conversation still a member; binding **not** required (receipt-like) |
| S6 | Response send | `send_json(reply, authorize=check)` re-runs S5 under the send lock |
| P1 | `speak` | `context(session,'relay:read')`; current active binding of the same conversation, epoch match; journal `find_request` item with `device_id`/`key_generation` == ctx (author only, no shared receipt); core exact lookup terminal with `final_display_id`; `message_id(row, display)` equals `expected_message_id` when given; speakable registry entry for that `message_id` and author |
| P2 | After the TTS await | P1 again in full, plus attempt not stopped |
| P3 | Every downlink frame | Under `DeviceSession._send_lock`: the full `output_check` closure (grant, membership, binding identity, core epoch) plus attempt-not-stopped; failure → no further bytes, attempt `aborted(revoked/stale)` |
| P4 | `speak.stop` | `context(session,'relay:read')`; record author == ctx |

Client (native, PROPOSED controller rules):

| # | Point | Check |
| --- | --- | --- |
| C1 | Press | Visible, foreground, selected `ConversationKey` == binding conversation, binding current for this socket generation, voice negotiated, no unresolved own turn/prompt in the conversation, microphone permission, capacity |
| C2 | Each mic frame | `epoch`, `captureGeneration`, socket generation unchanged |
| C3 | Release | C2 + persisted `finishing` record (§8.4) |
| C4 | Transcript arrival | Same socket generation and binding as at release, continuous foreground selection, capture not cancelled, inside intent window → prepare+persist+submit; otherwise draft only |
| C5 | Before `speak` | Foreground, same selection, `request_id` is this foreground session's initiating request, not stopped, speech enabled, audio focus obtainable |
| C6 | Each downlink frame | `speechGeneration`, `playbackVersion`, `epoch`, socket generation |
| C7 | Stop/hide/replace/new reply | Increment `speechGeneration`/`epoch` first, stop playback, then send `speak.stop` |

### 5.3 Effect ownership

| Effect | Owner component | Status |
| --- | --- | --- |
| Microphone, level meter, rings/ticks/haptics/waveforms | Phone `PhoneConversation` / watch `GadgetService` via a PROPOSED voice input controller that reuses `PcmCapture`, `RecordingFeedback`, `RecordingCues`, `pcmLevel` | PROPOSED wiring over [SRC] parts |
| Uplink framing/correlation | PROPOSED pure `protocol.relay.voice` codec + `DirectConnection` socket | PROPOSED |
| Capture assembly/validation | PROPOSED SDK `relay_voice.Captures` in the hub loop | PROPOSED |
| Paid STT | PROPOSED core `gateway.session_native_voice.transcribe_native_once`, called by the SDK bridge | PROPOSED |
| Paid-attempt accounting | PROPOSED SDK `VoiceStore` (`relay-voice.sqlite3`) | PROPOSED |
| Transcript → immutable turn | Task2 `TurnLedger` [SRC] + PROPOSED `VoiceCaptureLedger` in the Task3 encrypted store | PROPOSED wiring |
| Admission | Existing `relay.turn.submit` → `Conversations.submit` → core TEXT dispatch | [SRC], unchanged |
| Final identity | Core `lookup_native_admission` + `SharedDelivery.capture` [SRC] + PROPOSED speakable registry | PROPOSED addition |
| Server TTS | PROPOSED core `synthesize_native_once`, SDK `VoiceOutput` streaming via PROPOSED `send_binary(authorize=)` | PROPOSED |
| Client BYOK synthesis | Phone `PhoneClientSpeech`/`ElevenLabsApi`; watch `WatchSpeech` → phone [SRC] | reuse |
| Playback, audio focus | `PcmPlayback`/`PlaybackOutput` [SRC] under `speechGeneration` | PROPOSED wiring |

## 6. Wire contract (PROPOSED)

All JSON travels in existing Relay v1 envelopes `{type:"relay.request",v:1,id,method,params}` →
`{type:"relay.response",v:1,id,result|error}` [WIRE]. Error objects use only the existing 14 stable codes
[WIRE]. Voice outcomes that are not protocol/authority failures are `state` values inside a success result.

### 6.1 Methods

| Method | Permission (server) | Response bound (bytes/depth) |
| --- | --- | --- |
| `relay.voice.info` | any of read/create/submit (as `relay.info`) | 8,192 / 6 |
| `relay.voice.capture.start` | `relay:submit` | 8,192 / 6 |
| `relay.voice.capture.finish` | `relay:submit` | 32,768 / 6 |
| `relay.voice.capture.result` | `relay:submit` | 32,768 / 6 |
| `relay.voice.capture.discard` | `relay:submit` | 8,192 / 6 |
| `relay.voice.speak` | `relay:read` + authorship | 32,768 / 6 |
| `relay.voice.speak.stop` | `relay:read` + authorship | 8,192 / 6 |

Request ids for every method are envelope correlation ids [WIRE]; `capture_id` and `speech_id` are durable
client-minted UUIDv7 values (RFC 9562 version 7, lowercase canonical), minted from the server-time estimate like
Task2 `RequestIds.mint`. The freshness window (§7.4) applies only when an id is **new** (`capture.start`, first
`speak`); an existing record stays readable by `result`/`discard`/`speak`/`stop` until its row is retired, as
retained turn receipts are today. A `capture_id`, `speech_id` and turn `request_id` must be distinct values; reuse
of one value across kinds is `conflict`.

### 6.2 `relay.voice.info`

Params `{}`. Result (exact keys; `input`/`output` may be `null` when that direction is unavailable):

```json
{"version":1,
 "input":{"formats":["opus","pcm16"],"rate":16000,"frame_ms":20,"min_ms":250,"max_ms":60000,
          "max_frames":3000,"max_pcm_bytes":1920000,"max_opus_bytes":1000000,"channel":5,
          "local_fallback":true},
 "output":{"formats":["opus","pcm16"],"rate":16000,"modes":["server","client"],"channel":5,
           "max_ms":300000},
 "limits":{"id_past_ms":600000,"id_future_ms":300000,"result_wait_ms":20000,"transcript_ttl_ms":600000,
           "transcript_code_points":4000,"transcript_bytes":16384,"live_window_ms":120000,
           "full_code_points":4000,"concise_code_points":600}}
```

`formats` lists only formats this server can actually process (`opus` only when Opus is enabled and its codec is
available). `modes` contains `client` only when client speech is enabled, allowed for this device and the
device advertised `tts:"client"` [SRC gate]. `local_fallback` is a boolean only; no provider names, keys, models
or paths are disclosed.

### 6.3 Capture

`relay.voice.capture.start` params (exact): `{"capture_id":uuid7,"binding_id":uuid,"authority_epoch":int≥0,
"conversation_id":uuid,"stream":1..255,"format":"pcm16"|"opus"}`. `conversation_id` must equal the binding's.
Result: `{"capture_id","stream","deadline_ms"}` where `deadline_ms` is the server time by which `finish` must
arrive (`start + max_ms + 10000`).

Binary uplink on channel 5 (`[0x05, stream, seq_lo, seq_hi]` + payload) [PROPOSED kinds]:

| Kind (payload byte 0) | Body | Rules |
| --- | --- | --- |
| `0x01` OPUS_HEADER | 19-byte mono OpusHead | Opus only; exactly once; must be `seq 0` and first |
| `0x02` AUDIO | PCM16 LE 2..640 bytes (even) or one 20 ms Opus packet 1..1275 bytes | `seq` strictly consecutive from 0 (PCM) or 1 (Opus); no wrap |

`relay.voice.capture.finish` params: `{"capture_id","stream","frames":int≥1,"duration_ms":int}` where `frames`
counts AUDIO frames and `duration_ms` equals `frames × 20` (Opus) or `bytes/32` (PCM, stock formula).
Result: `CaptureResult`.

`relay.voice.capture.result` params: `{"capture_id","wait_ms":0..20000}` (long-poll; returns as soon as the state
is terminal or `wait_ms` elapses). Result: `CaptureResult`.

`relay.voice.capture.discard` params: `{"capture_id"}`. Before `finish`: drops the audio; no record. After
`finish`: queued-but-not-started work is removed (`provider:"not_started"`); started work cannot be cancelled and
its transcript is erased on arrival; a stored transcript is erased now. Result: `CaptureResult`.

`CaptureResult` (exact keys):

```json
{"capture_id":"…","conversation_id":"…","state":"transcribed","reason":null,
 "transcript":"…","transcript_expires_at_ms":1767225600000,"source":"configured",
 "provider":"completed","sealed_at_ms":1767225540000}
```

| `state` | Meaning | `transcript` |
| --- | --- | --- |
| `queued` | Sealed, accounted, waiting for an STT slot | null |
| `transcribing` | Provider call in flight (uncancellable) | null |
| `transcribed` | Non-empty bounded transcript stored | text until TTL |
| `empty` | Provider returned no words | null |
| `too_long` | Transcript exceeds 4,000 code points or 16,384 bytes | null |
| `failed` | `reason` ∈ `audio_incomplete`, `sequence`, `too_short`, `too_long_audio`, `too_fast`, `audio_format`, `audio_decode`, `stt_unavailable`, `provider_failed`, `interrupted` (server restarted before the provider was called) | null |
| `discarded` | Client discarded | null |
| `expired` | Transcript erased after TTL or after `discard` following a read | null |
| `revoked` | Authority lost before delivery; transcript erased | null |
| `unknown` | Server restarted while the provider call could have been in flight | null |

`source` ∈ `configured`, `local_fallback`, `null`. `provider` ∈ `not_started`, `in_flight`, `completed`,
`failed_after_send`, `unknown` (truthful billing evidence: anything except `not_started` may have been billed).

### 6.4 Speech

`relay.voice.speak` params (exact): `{"speech_id":uuid7,"binding_id","authority_epoch","request_id":uuid7,
"expected_message_id":uuid|null,"mode":"live"|"replay"|"more","style":"full"|"concise","start_unit":int≥0,
"output":"server"|"client","format":"pcm16"|"opus"|null}`. `request_id` is this device's own durable turn id;
`format` is required for `server` and must be null for `client`; `start_unit` must be 0 for `live`/`replay`.

Result `SpeechResult` (exact keys):

```json
{"speech_id":"…","message_id":"…","state":"preparing","reason":null,"stream":7,
 "range":{"start_unit":0,"end_unit":3,"unit_count":9,"cut":false},
 "text":null,"voice":null,"provider":"in_flight"}
```

| `state` | Meaning |
| --- | --- |
| `preparing` | Accounted; server synthesis in flight; frames will follow on `stream` |
| `streaming` / `completed` | Frames are being / were all sent (END frame written) |
| `stopped` | Stopped by `speak.stop` (provider may still have completed) |
| `aborted` | `reason` ∈ `revoked`, `stale`, `disconnected`, `too_long`, `encode_failed` |
| `failed` | `reason` ∈ `tts_unavailable`, `provider_failed` |
| `text` | Client mode: `text` holds the exact range text, `voice` the profile voice hint; no server paid call |
| `off` | Live speech suppressed by voice policy (§9.3); not recorded |
| `no_final` | The request has no terminal final occurrence for speech (failed/cancelled/steered elsewhere); not recorded |
| `unavailable` | Final exists but its speakable text is not held (restart/eviction/oversize); not recorded |
| `unknown` | Server restarted while synthesis could have been in flight |

Re-sending identical `speak` params with the same `speech_id` is a **receipt read**: it returns the recorded state
and never synthesizes or streams again. Same id with different params → `conflict`. At most one `live` row may
exist per (author device, key generation, `request_id`); a second `live` request under a new `speech_id` is
`conflict`, so automatic speech of one reply can be attempted once.

`relay.voice.speak.stop` params `{"speech_id"}` → `SpeechResult` after the stop flag is set.

Binary downlink on channel 5, stream chosen by the server (unique among the session's live output streams):

| Kind | Body |
| --- | --- |
| `0x11` OPUS_HEADER | 19-byte mono OpusHead, `seq 0`, Opus only |
| `0x12` AUDIO | PCM16 LE 2..1,280 bytes even (≤40 ms) or one 20 ms Opus packet 1..1,275 bytes |
| `0x13` END | 4-byte big-endian count of AUDIO frames sent |
| `0x14` ABORT | 1 byte: 1 stopped, 2 revoked, 3 stale, 4 too_long, 5 encode_failed, 6 provider_failed |

Uplink kinds (`0x0_`) and downlink kinds (`0x1_`) are disjoint; a frame of the wrong direction is invalid.

### 6.5 Error codes

| Code | Voice use |
| --- | --- |
| `invalid_request` | Malformed params, bad UUIDv7 form, format not offered, `start_unit` misuse |
| `unsupported` | Method/direction/mode not enabled for the caller (after authorization) |
| `permission_denied` | No grant/permission, key/namespace mismatch, not the request author |
| `not_found` | Unknown capture/speech/request id for this device, unknown conversation |
| `conflict` | Id reuse with different params or operation, stream id live, finish counts mismatch after seal |
| `expired` | Id outside freshness window, `finish` after deadline, `live` after the live window |
| `busy` | Capture/STT/TTS capacity, one live capture/speech per session, voice store full |
| `rate_limited` | Relay token bucket or hourly voice budget, checked before any accounting |
| `temporarily_unavailable` | Store/core/seam unavailable; final not yet terminal or not yet captured |
| `stale_binding` / `stale_epoch` / `snapshot_required` / `profile_changed` | As existing binding/core rules |

No error leaves a paid attempt implied: every code is returned before accounting, or the result carries the
recorded `provider` state.

## 7. Audio framing, resources and bounds (requirement 3)

### 7.1 Uplink validation (server; any violation fails the capture with no provider call)

* Correlation: frames belong to the session's live capture whose `stream` matches; frames for other streams,
  after seal, or before `start` are dropped and counted (no state change).
* Sequence: strictly consecutive; a gap, duplicate, reorder or wrap → `failed(sequence)`.
* Format: PCM frame length even 2..640; Opus header first and exactly once, packets validated as single 20 ms
  packets (`opus.packet`); otherwise `failed(audio_format)`.
* Truncation: `finish.frames`/`duration_ms` must equal what was received → else `failed(audio_incomplete)`.
* Size/time: AUDIO frames ≤ 3,000; PCM bytes ≤ 1,920,000; Opus bytes ≤ 1,000,000; duration 250–60,000 ms
  (`too_short`, `too_long_audio`); received audio may not exceed wall time since `start` by more than 3,000 ms
  (`too_fast`).
* Deadline: no `finish` by `deadline_ms`, socket close, enrollment change or binding retirement before seal →
  capture dropped (no record, no provider call).
* Stream reuse: a `start` naming a stream that is live on the session → `conflict`; after a capture is sealed,
  failed or dropped its stream id may be reused only by a new successful `start`.
* Opus decode (ffmpeg, existing helper) happens before any provider call; failure → `failed(audio_decode)`.
* Reporting: a capture that fails before seal creates **no** `VoiceStore` row; its `failed` state is returned only
  in the `finish` reply. A later `result`/`discard` for that id answers `not_found`, which always means nothing
  was sealed or billed.
* Stock channels 1–4, stock `_upload`, `on_utterance` and stock limits are untouched.

### 7.2 Downlink rules (client)

Frames are accepted only for the stream named by the current `SpeechResult`, in strict sequence from 0, with the
current `speechGeneration`; header first for Opus; AUDIO payload bounds as §6.4; END count must equal received
AUDIO frames. Any violation → local stop, notice "Speech interrupted", `speak.stop` best effort. Frames for other
or retired streams are ignored. Channel 5 never reaches stock `Conversation.binary`.

### 7.3 Concurrency, allocation, backpressure

* Server memory: ≤ 1 live capture per session, ≤ 4 live or sealed-unprocessed captures globally (≈ 8 MiB worst
  PCM), ≤ 2 concurrent STT provider jobs with a queue bounded by the 4 capture slots, ≤ 1 speech per session and
  ≤ 2 concurrent TTS jobs globally; decoded speech PCM ≤ 300 s (9.6 MB) per attempt. Excess → `busy` before any
  accounting. Voice jobs use their own bounded task set, not `Conversations.MAX_TRACKED`.
* Rate: every voice RPC consumes the existing per-session token bucket and ≤16 active-request bound; long-polls
  hold one slot. Hourly per-device budgets (§7.4) are checked before accounting.
* Client uplink: before writing a frame, if `socket.queueSize() > 32000` the capture is stopped locally and
  discarded (`discard` sent); unlike stock, the socket is kept.
* Downlink pacing: server sends ≤ 0.5 s ahead of real time (stock `PLAYBACK_LEAD_S`).

### 7.4 Budgets and provenance

| Budget | Value | Provenance |
| --- | --- | --- |
| Relay frame / depth | 65,536 B / 8 | [WIRE] `relay_service._bounded`, hub 753 |
| DTO default | 8,192 B / 6 | [WIRE] `relay_dto.bounded` |
| Voice response bounds | 32,768 B for capture finish/result and speak; 8,192 B others | [PROPOSED] |
| Token bucket; active requests | 20 / 10 s⁻¹; 16 per session | [WIRE] |
| Binary frame | ≤ 1 MiB, `<BBH>` header | [WIRE] |
| Channel 5 and kinds | §6.3/§6.4 | [PROPOSED] |
| Input audio | 16 kHz mono; PCM frame 2..640 B; Opus 20 ms 1..1,275 B; OpusHead 19 B | [WIRE] stock formats |
| Capture duration | 250–60,000 ms | [WIRE] stock min/max reused |
| Capture frames/bytes | 3,000; PCM 1,920,000; Opus 1,000,000 | [PROPOSED] (PCM = stock cap; Opus bound new) |
| Too-fast slack | 3,000 ms | [POLICY] |
| Finish grace | 10,000 ms after `max_ms` | [POLICY] advertised via `deadline_ms` |
| Id freshness (capture/speech) | −600,000 / +300,000 ms server clock | [PROPOSED] (turn ids keep 7 d / 5 min) |
| Transcript | ≤ 4,000 code points and ≤ 16,384 B | [WIRE] admission limits reused |
| Transcript TTL | 600,000 ms after completion | [PROPOSED] |
| Result long-poll | ≤ 20,000 ms | [PROPOSED] |
| STT wait deadline | 90 s (record stays in flight until completion/crash) | [POLICY] |
| Live speech window | 120,000 ms after `final_created_at_ms` | [PROPOSED] |
| Spoken range | full ≤ 4,000 code points; concise ≤ 600 | [PROPOSED]; 4,000 matches stock client speech cap |
| Speech audio per attempt | ≤ 300 s, ≤ 15,000 AUDIO frames | [PROPOSED] |
| Downlink frame | PCM ≤ 40 ms (1,280 B); Opus 20 ms; lead 0.5 s | [WIRE] stock server pacing |
| TTS deadline | 120 s per attempt; provider request timeout 60 s | [POLICY]; [SRC] 60 s |
| Server concurrency | captures 1/session, 4 global; STT 2; speech 1/session, TTS 2 | [POLICY] |
| Hourly per device | 30 STT, 60 TTS attempts, 40,000 TTS input code points | [POLICY] |
| Voice store | ≤ 4,096 rows, ≤ 256 per device, 24 h retention after terminal | [POLICY] |
| Speakable registry | in memory, ≤ 4 per device, ≤ 256 total, 30 min TTL | [POLICY] |
| Temp sweep | at start and every 60 s; orphan files > 300 s deleted | [POLICY] |
| Client intent window | 120,000 ms from release to transcript | [POLICY] |
| Client voice records | ≤ 16 per enrollment scope, ≤ 64 total | [POLICY] |
| Client uplink queue | 32,000 B | [WIRE] stock native value reused |
| Client receipt backoff | 2, 5, 15, 60 s then manual | [POLICY] |

## 8. Transcription to exactly one TEXT admission (requirement 4)

### 8.1 Server STT

At `finish` (after S3) the bridge, in order: validates the complete capture; inserts the durable row `queued`
(`provider=not_started`, fsync); when an STT slot is free, updates it to `transcribing` (`provider=in_flight`,
fsync); decodes Opus if needed; writes a private WAV (0600, O_EXCL, voice temp dir); calls the PROPOSED core seam
`transcribe_native_once(path, *, authorize, deadline_s)`; deletes the WAV in `finally`; recheck S4; stores the
bounded transcript with its TTL. The seam (core, PROPOSED):

* uses the runner's own single active profile STT configuration (the bridge already refuses multiplexed
  profiles); no client profile, model, provider, language or path input;
* invokes the configured provider **once** with SDK/client automatic retries disabled and no re-send after a
  request may have reached the provider (transcode-retry is allowed only before any provider send; OAuth refresh
  retry only after a definite authentication refusal that billed nothing);
* if the configured attempt does not succeed, tries the **already installed** local backend once
  (`transcribe_audio_local_fallback` semantics: never lazy-install, never another cloud provider);
* returns `NativeTranscription(state, transcript, source, provider)` with stable enums only; never provider
  messages, credentials, paths or exception text; adds no log lines containing them.

Empty/whitespace transcript → `empty`; over-limit → `too_long` (text discarded, never truncated); command-like
text is **not** filtered here (core classification refuses it at admission, §8.3).

### 8.2 Client persistence and admission order (normative)

1. Press: mint `capture_id`; persist `VoiceCaptureRecord{capture_id, scope, conversation_id, created_server_ms,
   state=recording}` (encrypted Task3 store) **before** `capture.start` is written.
2. Release: persist `state=finishing` (PersistBeforeSend semantics) **before** `capture.finish` is written. If the
   combined snapshot cannot be built (`SnapshotBuild.TooMany` or storage failure) the client sends `discard`, not
   `finish`: nothing is billed and the user sees "Can't record until pending items are reviewed".
3. `finish` answer or later `result`: `transcribed` and C4 holds → in **one** encrypted write: Task2
   `TurnLedger.prepare(TurnDraft(conversation, transcript, ttl))` (mints `request_id`, fixes `expires_at_ms`
   from the server-time estimate, stores immutable text) **and** `record.linkedRequest=request_id,
   state=admitting`. Then `planSubmit` → `PersistBeforeSend` → `relay.turn.submit`.
4. After the snapshot is durable the client may send `capture.discard` to erase the server transcript early.
5. Receipt-first thereafter is exactly Task2 (`UNCERTAIN`, fresh attach before lookup, `ABSENT_SO_FAR` resubmit
   with same id/text/expiry, `EXPIRED_REPORTED` stays uncertain, `ABANDON_TRACKING` only by the user).

A capture record with `linkedRequest` never prepares again. A capture whose transcript was offered as a draft
(`state=draft_offered`) never auto-submits. Both transitions are persisted before their effects. Admission count
per capture is therefore ≤ 1 by construction, and = 1 only through the existing journal/core admission.

### 8.3 Cases

| Case | Outcome |
| --- | --- |
| Transcript within C4 | Exactly one prepared+persisted TEXT turn, submitted once; uncertainty by receipt |
| C4 fails (late, reconnect/rebind, hide, selection/profile change, process loss) | Transcript fetched only to show an unsent editable draft in the original conversation; no auto submit |
| User edits and sends the draft | A new deliberate typed turn (`prepare` mints a new `request_id`); capture stays `draft_offered` |
| `empty` | Notice "Didn't catch that"; nothing admitted |
| `too_long` | Notice; nothing admitted; user may type |
| `failed` | Truthful notice by reason; `provider` says whether billing may have happened |
| Lost `finish`/`result` response | Re-call `capture.result` (receipt); never a new capture, never re-transcription |
| `unknown` | "Transcription outcome unknown (may have been billed)"; record again by a new deliberate press |
| Submit refused `unsupported` (command-like transcript) | Turn closed by refusal before effect; transcript shown as draft for editing |
| Submit `expired`/timeout | Task2 `EXPIRED_REPORTED`/`UNCERTAIN`; receipt lookups with backoff; never re-transcribe or new id |

Turn expiry is fixed at `prepare` (after transcription), so long STT never consumes the five-minute admission
window; the separate intent window bounds how stale a spoken intent may be when auto-submitted.

### 8.4 State tables

Server capture record (`VoiceStore`, PROPOSED):

| From | Event | To | Durable before effect |
| --- | --- | --- | --- |
| (memory) live | valid `finish`, S3 ok, capacity | `queued` | yes (row insert, fsync) |
| `queued` | slot | `transcribing` (`provider=in_flight`) | yes |
| `queued` | `discard` | `discarded` (`not_started`) | yes |
| `transcribing` | seam result + S4 ok | `transcribed`/`empty`/`too_long`/`failed` | yes |
| `transcribing` | S4 denied | `revoked` (text never stored) | yes |
| `transcribing` | `discard` | `discard_pending` → on result `discarded` (text never stored) | yes |
| `queued` | process start finds row | `failed(interrupted)`, `provider=not_started` (nothing billed) | yes (startup) |
| `transcribing` | process start finds row | `unknown` | yes (startup) |
| `transcribed` | TTL or `discard` | `expired` (text erased, digest kept) | yes |
| any terminal | 24 h after terminal | row deleted | yes |

Fields: identity `sha256(server_id, profile_id, device_id, key_generation, capture_id)` (same derivation as the
journal), `kind=stt`, `state`, `reason`, `provider`, `source`, `conversation_id`, `group_id`, `grant_generation`,
`format`, `frames`, `duration_ms`, `sealed_at_ms`, `terminal_at_ms`, `transcript` (≤16,384 B, nullable),
`transcript_sha256`, `transcript_expires_at_ms`. No audio, no paths, no provider text.

Client capture record (`VoiceCaptureLedger`, PROPOSED pure state + Task3 encrypted persistence):

| State | Meaning | Allowed next |
| --- | --- | --- |
| `recording` | `start` may be sent; nothing billed | `finishing`, `cancelled`, `lost` |
| `finishing` | `finish` may be in flight; billing possible | `awaiting_result` |
| `awaiting_result` | Receipt reads allowed | `admitting`, `draft_offered`, `ended` |
| `admitting` | `linkedRequest` set; Task2 ledger owns the turn | `ended` when the turn settles or is abandoned |
| `draft_offered` | Text given to the user as a draft; never auto-submits | `ended` |
| `cancelled` / `lost` / `ended` | Terminal; retained ≤ 24 h for truthful history of billing unknowns | removed |

Never saved: socket generation, binding, streams, audio, waveform levels, playback flags.

## 9. Exact reply selection and speech (requirement 5)

### 9.1 Final selection (server, authoritative)

The client names only its own `request_id` (and optionally the `message_id` it displays). The server:
`find_request(ctx, request_id, conversation_id)` must return the journal item authored by this device and key;
`core.lookup(None, row, item, …)` must be `found` with a terminal receipt and `result.final_display_id`;
`message_id(row, display)` [SRC mapping] is the canonical occurrence; it must equal `expected_message_id` if
given; the speakable registry must hold that occurrence for this author. Arbitrary client text, guessed message
ids, another device's requests, shared receipts, existing-source sessions and O5/task copies are never inputs.

### 9.2 Speakable registry (SDK, PROPOSED)

`SharedDelivery.send` (live path only, not `recover`) registers `{message_id, request message_id, author
device_id, key_generation, conversation_id, logical_ref, final_created_at_ms, speakable_text}` after its
successful `capture`, where `speakable_text` is the PROPOSED core `speakable_text(text)` normalisation (the
existing spoken-text cleaner). Bounded per §7.4; cleared on enrollment/grant revocation, canonical delete, binding
tombstone and process exit. Content larger than the existing 16,384-byte final capture bound is never registered.

### 9.3 Voice policy

* Server mode: the profile's configured TTS provider and voice (default ElevenLabs where configured, otherwise
  the configured/default provider) resolved by the core seam from the runner's own profile at call time.
* Client mode (phone BYOK, per profile): the server returns the exact range text and the profile voice hint; the
  phone synthesizes with its own per-profile key via the existing bounded, no-retry client path; the watch sends
  the range text to its phone over the existing client-speech channel. No server paid call.
* Live mode is `off` when server `speak_replies` is false, when the device's stock chat has `/voice off`, or when
  server voice output is disabled; the client additionally suppresses live speech when its per-profile speech
  setting is off or media is muted by the user. Replay/More are explicit and allowed when server speech is
  enabled (or client mode is selected).

### 9.4 Ranges: full text, walking concise, More, Replay

Units are sentences of `speakable_text` (boundary after `.`, `!`, `?`, `…` plus whitespace, or a newline). A
range greedily packs whole units from `start_unit` up to the style limit (full 4,000 code points, concise 600);
a single unit over the limit is cut at the last whitespace within the limit (`cut:true`). The stored and displayed
answer is always the complete text; speech never replaces it. Walking mode uses `concise`; "Tell me more" is
`mode=more` with `start_unit` = previous `end_unit`; Replay is `mode=replay`, `start_unit=0`. Each speak call is
one deliberate attempt with a new `speech_id` except the single automatic `live` call.

### 9.5 Foreground initiating-request-only playback

The client issues at most one `live` call per initiating request it submitted **in the current foreground
session**, only when that request's receipt is terminal, its final is displayed, C5 holds and the live window has
not passed (server enforces the window and authorship; it records one `live` attempt per `request_id`). Output is
streamed only to the requesting socket. Recovered history, reconnect replays, O5 final/task copies, task inbox
entries, notifications and another device's or subscriber's turns never produce a `live` call, and the server
has no broadcast path: there is no fanout from a logical route to any speaker. A typed initiating request
qualifies like a spoken one, matching stock speaker devices that speak replies to typed messages (§2, adapter
`_should_auto_tts_for_chat`); the voice-off rules of §9.3 decide both.

### 9.6 Known limitation

After a server restart (or registry eviction/expiry) older replies answer `unavailable` for speech; text stays
complete. A reviewed core read seam (alternative I) can lift this later without changing the wire.

## 10. Paid attempts, deduplication, crash semantics, privacy (requirement 6)

### 10.1 Accounting rules

* A paid provider call happens only after a durable `VoiceStore` row for that `capture_id`/`speech_id` is
  committed with `provider=in_flight` (SQLite `synchronous=FULL`, fsync). Volatile state is never billing
  evidence.
* One row ⇒ at most one configured-provider call (plus at most one free installed local STT fallback). A
  repeated `finish`/`result`/`speak` with the same id reads the row; it never calls a provider.
* No automatic paid retry anywhere: not in the seam (retries disabled), not in the bridge, not in clients. A new
  paid attempt requires a new deliberate user action that mints a new id (a new press, Replay, More).
* `provider` is reported truthfully; `unknown`/`failed_after_send`/`in_flight` are shown as "may have been
  billed".
* Hourly budgets are enforced before accounting; refusal costs nothing.

### 10.2 Crash and loss matrix

| Moment | Server state after restart | Client behaviour |
| --- | --- | --- |
| Before `finish` arrives | No row; audio gone | `finishing` lookup → `not_found` → "Recording lost, not sent" |
| Row `queued` committed, provider not called | `failed(interrupted)`, `not_started` | "Not transcribed, not billed"; new press needed |
| Row `transcribing` committed, before/during provider send | `unknown` (conservative) | Truthful unknown; no auto retry |
| During provider call | `unknown` | Same |
| Provider returned, before transcript stored | `unknown` (transcript lost; paid) | Same |
| Transcript stored, response lost | `transcribed` | `result` receipt returns it; C4 decides auto vs draft |
| Client dies after `finish` written | Unaffected | On restart: `result` receipt; transcript only as draft |
| Client dies after transcript, before ledger write | Unaffected | Restart: draft only (no `linkedRequest`) |
| Client dies after ledger+link write, before submit | Unaffected | Restart: Task2 restored turn `UNCERTAIN(PROCESS_LOSS)` → receipt first; never re-prepare |
| Submit response lost | Journal/core per existing rules | Task2 receipt-first |
| TTS row committed, crash before/during synthesis | `unknown` | No auto speech after restart; text shown |
| Speak response lost | Row state readable by same params | Client may re-read once; no new speech id automatically |
| Stop races provider completion | `stopped` with `provider=completed` | "Stopped"; not claimed cancelled at the provider |

### 10.3 Privacy and cleanup obligations

* Raw microphone audio: client memory only (never written to disk, never in history/diagnostics); server memory
  plus one private temporary WAV per STT job deleted in `finally`; startup and periodic sweeps delete orphans.
  No audio is retained as history or attachment by default.
* TTS output files live in the same private voice temp directory (passed to the seam by the SDK, never derived
  from the wire) and are deleted after decoding/streaming.
* Transcripts: server ≤ 600 s TTL or earlier `discard`; client only inside the encrypted ledger (as turn text) or
  a memory-only draft.
* Logs/wire/diagnostics: enum states, counts and durations only; no transcript/reply text, audio, paths,
  provider names/messages, keys or credentials. N8 redacted reports exclude voice content.
* Platforms: POSIX 0700 directory/0600 files with `O_EXCL`; Windows: the new SQLite file, its sidecars and the
  temp directory join the existing same-object privacy verifier (SDK patch 6); Android/Wear: no new disk audio,
  existing Keystore-backed encrypted store for records; macOS/Linux hosts as POSIX.

## 11. Lifecycle transitions (requirement 7)

Order inside each client row is normative (invalidate first, then stop adapters, then wire).

| Event | Client effects | Wire | Server effect | Never |
| --- | --- | --- | --- | --- |
| Hold (press) | C1; persist `recording`; start mic, START cue, rings/ticks/haptic, input waveform; stop any playback | `speak.stop` if speaking; `capture.start`; frames | S1; live capture | Stock `audio.start` |
| Release | Stop mic, FINISH cue; persist `finishing` | `capture.finish` | S3; account; STT | Second finish with other counts |
| Slide-left cancel | Stop mic, CANCEL cue; `cancelled` | `capture.discard` | Drop audio, no row | Any provider call |
| Mic failure / permission loss | As cancel with reason notice | `discard` | Drop | Partial upload sealed |
| Too short | As stock "Recording too short" | `discard` | Drop | Paid call |
| Stop button | `speechGeneration++`, stop playback | `speak.stop` | Stop flag; abort frame | Claim provider cancelled |
| Hide / background | `epoch++` first, then stop mic, playback, synthesis, close socket (existing order) | socket closes | Live capture dropped; sealed STT continues; speech aborted on disconnect | Late playback; auto submit later |
| Profile replacement | As hide; old scope voice records frozen | — | — | Cross-profile transcript or audio |
| Conversation selection change | Withdraw auto-submit (draft in original conversation); stop playback | `discard` if recording; `speak.stop` | — | Submit into the new conversation |
| New reply arrives while speaking | Stop current speech (it belongs to an older request) | `speak.stop` | — | Speak a reply not initiated in foreground |
| New capture while speaking | Stop speech first | `speak.stop`, then `start` | Session speech aborted | Overlapping capture and output |
| Audio focus loss | Stop playback | `speak.stop` | — | Resume automatically |
| Rebind (re-attach) | Captures before seal lost; transcript after rebind → draft | — | Old binding retired; S2/S3 fail for old | Auto submit across binding |
| Revoke (`permission_denied`) | Stop all, evict scope content, disable voice | — | S4 erases transcripts; P3 stops frames | Delivering audio/text after revoke |
| `profile_changed` / enrollment replacement | Review flow; voice disabled for scope | — | Context refused | Using old key/records |
| Core authority epoch change | Speech stops (P3 `stale`); captures before seal fail at finish | — | `stale_epoch` | Output under old epoch |
| Disconnect/reconnect | Unsealed capture lost; sealed result via receipt → draft | `result` after reattach | Unaffected | New capture/turn minted automatically |
| Phone process loss | Restore: records/ledger; no auto speech, no auto submit | Receipts only | — | Recovered speech or second admission |
| Server restart | Results may be `unknown`; speech `unavailable` | — | Startup marks in-flight rows `unknown`, sweeps temp | Retry of an `unknown` attempt |
| Canonical delete | Both devices invalidate; drafts kept local only | — | Registry entries purged | Speech of deleted content |

Watch specifics: portrait hold/release/slide gestures, `RecordingPortrait` rings, `RecordingCues` with watch light
haptics, foreground lease and screen-turn notices stay; the watch uses its own enrollment, socket (TLS through the
phone byte relay, never terminated on the phone) and its own `VoiceCaptureLedger`. Phone specifics: input level
history/waveform from PCM frames, output waveform from playback level callbacks, recording elapsed timer.

## 12. Entry points, version validation and registered fixtures (requirement 8)

### 12.1 Server activation gate (all required; otherwise no voice method is listed)

* Adapter flags (PROPOSED, default false): `enable_relay_voice_input`, `enable_relay_voice_output`; both also
  require `enable_relay_conversations` and the existing bridge.
* Core seam (PROPOSED module `gateway.session_native_voice`, constant `NATIVE_VOICE_VERSION == 1` on the module
  and on `SessionAuthority`), validated like `CoreBridge`: exact dataclass fields
  `NativeTranscription(state, transcript, source, provider)`,
  `NativeSpeech(state, path, code_points, source, provider)`; signatures
  `transcribe_native_once(path, *, authorize, deadline_s)`,
  `synthesize_native_once(text, *, output_dir, authorize, deadline_s)`, `speakable_text(text)`; single-profile
  runner (existing refusal of `multiplex_profiles`).
* Hub: PROPOSED `DeviceSession.send_binary(data, *, authorize=None)` mirroring `send_json`; channel-5 routing in
  `_on_binary` before stock handling, active only for a session that completed `relay.voice.info`.
* Logical-chat auto-TTS guard (R-6): `_should_auto_tts_for_chat` returns false for `_is_logical_chat` routes.
* Opus directions require the existing `enable_opus` and codec availability; client mode requires the existing
  `enable_client_tts`, `client_speech_allowed` and the device's `tts:"client"` capability.

### 12.2 Native entry points (PROPOSED)

`protocol.relay.RelayMethod` gains the seven names with their bounds; a pure `protocol.relay.voice` package holds
`VoiceInfo`, `CaptureResult`, `SpeechResult` DTOs, call builders, the channel-5 frame codec and
`VoiceCaptureLedger`. `DirectConnection` gains: raw `relay.response` routing before stock parsing (Task3), a
voice binary sink enabled per socket generation, and a non-failing voice uplink backpressure check. The phone
and watch controllers wire gestures/feedback/playback to these without changing stock paths.

### 12.3 Registered fixtures (no handwritten authority)

* SDK/core fixtures extend `tests/relay_bridge_support.py` with core `tests.gateway.relay_native_support.rig`:
  real `GatewayRunner`, `SessionAuthority`, registered `GadgetAdapter`, `RelayService`/`Conversations`, journal,
  grants v2 fixture and `VoiceStore`; only the WebSocket and the provider execution boundary (inside the core
  voice seam) are replaced by labelled synthetic providers that count calls, inject latency, failure, empty or
  oversize output, and can block until released. Run through core `scripts/run_tests.sh`, synthetic homes, no
  network or credentials.
* Native vectors are captured from those fixtures into `protocol/src/test/resources/relay-v1/voice/` with
  provenance, plus constructed negative vectors labelled as such. Native controller tests drive the real
  `DirectConnection` against captured vectors or an owned offline loopback of the registered fixture.
* Synthetic seams prove routing, counts, cancellation and fences only; never provider quality, voice identity or
  device audio.

## 13. Persistent fields and ordering summary

Server `VoiceStore` (`relay-voice.sqlite3`, private directory, namespace row pinned to the RelayStore
`(server_id, profile_id, core_profile)`, server time read from the existing RelayStore clock high-water): rows as
§8.4 plus TTS rows (`kind=tts`, `speech_id` identity, `mode`, `style`, `range`, `message_id`, `request message_id`,
`code_points`, `state`, `reason`, `provider`, timestamps). Older SDKs never open this file, so rollback leaves the
existing journal schema untouched.

Client: `VoiceCaptureLedger` snapshot section (version 1) inside the same atomic encrypted record as the Task2
`RelayStateSnapshot`, so `prepare` + `linkedRequest` commit together; bounded (§7.4) with a `TooMany` refusal
checked before any persist-before-send step.

Ordering invariants: record before `start`; `finishing` before `finish`; server row before provider; ledger
prepare+link in one write before `submit`; registry entry only after live `capture` success; TTS row before
provider; invalidate counters before stopping adapters.

## 14. Compatibility and failure behaviour

| Peer | Result |
| --- | --- |
| New client, stock v0.2.0 server | Relay `NOT_OFFERED`; stock voice unchanged |
| New client, Relay server without voice | Voice methods absent; mic disabled in Relay conversations; typed Relay works |
| Old native client, voice-enabled server | Unknown method names ignored; no channel 5 traffic; nothing changes |
| Voice server, core without the seam | Methods unlisted; `unsupported` if called |
| Opus unavailable on either side | PCM16 per direction |
| Client mode not allowed | `modes` lacks `client`; server mode or text only |
| Server rollback to older SDK | `relay-voice.sqlite3` ignored; no journal schema change; voice disappears cleanly |

## 15. Requirements-to-acceptance map

| Requirement | Design section | Acceptance evidence (plan stage) |
| --- | --- | --- |
| 1 Negotiation/refusal/stock-off | §4 | V2 fixture: unlisted without flag/seam, `unsupported`, info DTO; V4 native decode refusals; stock-off controls on both sides |
| 2 Identity/authority/ownership | §5 | V2/V3 fixtures: revoke, key replacement, grant change, profile mismatch, detach/rebind, epoch change at each S/P point; V5/V6 controller tests |
| 3 Framing/bounds/concurrency | §6–7 | V2 fixture: gaps, duplicates, truncation, oversize, overlong, too fast, stream reuse, cancel, busy; V4 codec vectors; V7 native |
| 4 STT → one TEXT admission | §8 | V2 fixture: exactly one admission per spoken intent, lost response receipt, no second admission on reconnect/cancel/duplicate finish, local fallback, empty/too long; V5 process-loss with encrypted store |
| 5 Exact final, voice policy, foreground only | §9 | V3 fixture: author-only, other subscriber silent, recovered/O5 never live, voice off, client mode, ranges; V5/V6 foreground rules |
| 6 Paid accounting/crash/privacy | §10 | V1/V2/V3: provider call counts under crash injection, no retry, unknown states, temp cleanup, no-log assertions; Windows privacy check |
| 7 Lifecycle | §11 | V5/V6 controller tests per row; V7 native emulator fixtures; V8 owner-observed physical |
| 8 Entry points/fixtures | §12 | V1/V2 signature/version refusal tests; registered rig only |

Approved voice behaviours of `voice-integration-requirements.md`:

| Behaviour | Where met |
| --- | --- |
| 1 Hold/release one deliberate turn; slide/cancel, rings, ticks, waveforms, locks; Relay off = stock | §4, §5.2 C1, §11 |
| 2 No ordinary turn then resubmission; immutable text/id/expiry; receipt-first | §3, §8 |
| 3 Profile server STT and ElevenLabs/server voice; BYOK per profile; no platform replacement | §8.1, §9.3 |
| 4 Foreground initiating request/device only; no recovered/other-device speech; deliberate Replay | §9.5, §11 |
| 5 Independent Opus/PCM per direction; watch-owned TLS; negotiated framing only; no stock fallback | §4, §6, §7 |
| 6 Bounds, accounting, no paid retry, no durable PCM, truthful unsent/unknown | §7, §10 |
| 7 Authority rechecks before upload acceptance, after awaits, before admission and before output | §5.2 |

## 16. Retained obligations carried (not changed by this design)

Task1 M1–M6 and R1-M1..M4; Task2 M1–M6 and R1-M1..M3 (bounded refused-message work budget, durable
retirement of erased enrollment/epoch decisions, formatting/readability). Consumers must handle
`SnapshotBuild.TooMany` before persist-before-send (§8.2 applies it to voice), define truthful backoff/recovery
for `EXPIRED_REPORTED` (§8.3), and retire voice records together with an authorized enrollment retirement without
reactivating revoked authority. Existing-history 8,192/65,536 regression, native encrypted cache/export
N-M1..M3, retention/anchors/rebuild/erasure/SAF/durability, Task10 N1/N2/M2/O1–O4/N3–N5, missing captures and all
native/Windows/platform/live gates remain. Original N6 exact synthetic app-data/identity/Keystore recovery and
N10/API 37 preservation are REQUIRED and unwaived; no reset, repaint, substitution or reopening of quarantined
guests.

## 17. Risks and open points for review

| Risk | Mitigation / status |
| --- | --- |
| Logical-chat auto-TTS paid waste (§2.8) | Source-derived, unproven; V2 registered RED then guard |
| Provider SDK hidden retries (ElevenLabs) | Seam must pin retries off and prove call counts; review the installed SDK version at V1 |
| Per-frame grant recheck cost | Normative full recheck; measure at V3; any relaxation needs review and a bound |
| Speakable text lost on restart | Truthful `unavailable`; alternative I deferred |
| Auto-submit withheld after flaky reconnect | Deliberate draft; UX cost accepted for safety |
| Transcript privacy window on server | 600 s TTL, early discard, no logs |
| Hourly budgets too tight/loose | POLICY, owner-visible, adjustable by review |
| Opus decode/encode depends on ffmpeg | Existing dependency; PCM fallback per direction |

No material owner decision is required to implement this design within the approved scope. Two choices are
isolated in case the owner wants them differently: model-summarized concise speech (alternative J) and Replay
after server restart (alternative I).
