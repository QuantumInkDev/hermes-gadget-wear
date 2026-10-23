# Relay canonical conversation voice: proposed contract (design gate)

Status: **PROPOSED design for one fresh independent design review.** Nothing here is implemented, captured,
tested, installed or activated. Every method, field, channel, record, module, function and number marked
PROPOSED or POLICY does not exist in current source. This document refines the next serial prerequisite of
the native Relay plan; it does not replace O1–O5/N1–N10, narrow Task3/Task6 to text, or accept any native,
server, provider, device or live behaviour. Validation behind it is document/source consistency only.

Revision 1 (correction round 1, same designer): responds to the first full design review (0 Critical, 3 Important,
8 Minor). It separates automatic live speech from deliberate canonical playback across authorized devices,
history, notification taps and server restarts (I1, §9.1–§9.2, §9.6); defines finite walking ranges and exact
code-point continuation (I2, §9.4); adds an output-routing and walking-controls contract (I3, §9.7, §11); and
makes the Minor precision corrections listed in the round-1 report. Unchanged sections keep their meaning.

Revision 2 (V2 input stage): adds the explicit activation-scope amendment (§12.1). No other contract section changes in this
step and no stage is accepted by this revision.

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
* Core `tools/transcription_cloud.py` (af7bdcee…): OpenAI-compatible STT client built with configured `timeout`
  (default 60 s) and `max_retries` (default 1) 43–58; on a 5xx or container rejection, a transcode then one more
  request 165–182; xAI OAuth refresh-and-retry 303–311.
* Core `tools/tts_tool.py` (3f4f8d60…): active-profile config at call time 116–149; `text_to_speech_tool`
  normalizes, chunks, synthesizes sequentially and logs saved paths 457–510.
* Core `tools/tts_tool_providers.py` (d8dd3b16…): the 60 s timeout 153–155 belongs to the streaming
  `requests.post` helper used by other providers, **not** ElevenLabs; `_generate_elevenlabs` builds the SDK
  client with no timeout or retry arguments and the profile `voice_id`/`model_id` 219–229, so its effective
  timeout and retry behaviour are the installed SDK's own. Measured at V1 correction round 1 on the lock-pinned
  wheels, no network, on the STOCK path outside a native call: `elevenlabs==1.59.0` sends once on a 5xx (also on the
  never-patched `7c17e6d` run) or a read timeout, and a stalled transport blocks the stock call (the latter two and
  the Edge case were run at the corrected head, where every stock edit is a no-op without a native policy; 60 s client
  timeout, SDK `max_retries` default 0); `edge-tts==7.2.7` re-handshakes after a 403 (two handshakes).
* SDK `plugin/relay_history_store.py` (ab10412f…): durable message aliases map a display id (and, for history
  rows, a physical id) to the public `message_id`, ≤ 4,096 rows with oldest-first eviction ("deterministically
  recoverable") 57–111; `commit_page` re-creates aliases for every paged row 113–136; `commit_final` 138–190.
* SDK `plugin/relay_history.py` (21a6a14a…): canonical history pages under `output_check(...,'relay:read')`
  through `core.fragment` with the reading device's own actor 126–196.
* SDK `plugin/relay_existing.py` (07546d31…): existing-source message ids are
  `opaque(server, profile, 'existing-message', session_id, row_id)` over the core existing reader 350–384.
* Core `gateway/session_history_segments.py` (99509be8…): `read_native_history_fragment` reads the next visible
  display row **after a position** (watermark/ceiling fingerprint, `sort_id`), 8,192-byte nominal segments, 2 s
  deadline and progress handler; there is no identity-addressed read 27–55, 98–206, 209–331.
* App `protocol/.../ClientSpeech.kt` (c2b71786…): BYOK `SpeechRequest.text` ≤ 4,096 UTF-16 units 12–28.
* App `mobile/.../ElevenLabsApi.kt` (013c1906…): BYOK stream, no retries, output ≤ 3,000 frames of 20 ms (60 s)
  for PCM and Opus, checked while streaming after the provider accepted the request 105–184.
* App `client/.../PcmAudio.kt` (a2192843…) and `PlaybackOutput.kt` (18446dc2…): `PcmPlayback` requests transient
  focus, interrupts on focus loss and on `ACTION_AUDIO_BECOMING_NOISY`, observes the routed device into
  `PlaybackOutput {UNKNOWN, HEADPHONES, SPEAKER, OTHER}`; it has no preferred-device selection 166–386; the
  walking-audio foundation plan (19727773…) records that a preference is not proof of the actual route.
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

Full witness hash lists: `.local/canonical-voice-design/evidence/source-witnesses.txt` and, for revision 1,
`.local/canonical-voice-design-review-fix1/evidence/source-witnesses-r1.txt` (outside Git).

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
   (OpenAI-compatible `max_retries` default 1, a 5xx transcode retry, OAuth refresh retry); the ElevenLabs SDK
   client is built without timeout or retry arguments, so its behaviour is unpinned. Reusing them as-is cannot
   prove "no automatic paid retry". `text_to_speech_tool` logs output paths. [SRC]
8. Latent risk, source-derived and **unproven**: for a logical Relay chat the adapter's auto-TTS probe falls
   through to the global `voice.auto_tts` default; `_should_send_voice_reply` could then synthesize (paid) a reply
   that `_play_file` cannot deliver. This must be confirmed or refuted by a registered fixture (plan V2 RED).
9. No identity-addressed canonical read exists. History is read by position (`read_native_history_fragment`),
   and public message ids are one-way hashes kept in a bounded, oldest-first-evicted alias table. Message-
   addressed deliberate playback therefore needs a minimal reviewed core display seam (§9.2). [SRC]

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
 final shown; foreground initiating request only (AUTOMATIC, once):
          relay.voice.speak(mode=live, request_id) ────► author-only: journal + core final identity
 user taps Replay / More / notification Play (DELIBERATE, any authorized device):
          relay.voice.speak(mode=replay|more, message_id)► relay:read + membership + current binding;
                                                         verified alias or core locate → exact core
                                                         display read (canonical text, ranges, digest)
          both: route chosen locally first (§9.7) ──────► DURABLE paid-attempt record; core
                                                         synthesize_native_once (profile TTS/ElevenLabs)
          binary ch5 kind 0x11/0x12/0x13/0x14 ◄──────── each batch re-authorized under the send lock
 Stop/hide/route loss/... ─ invalidate first, stop, then relay.voice.speak.stop
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
| I. Minimal reviewed core display seam: exact identity-addressed, bounded canonical read and locate | **Chosen (r1)** | Required for approved deliberate Replay/More/notification playback on any authorized device and after restarts (§9.2). Costs one core surface; replaces the volatile registry. |
| J. Model-generated concise summary for walking mode | Not chosen | A second paid LLM call and new content; approved "short spoken answers" is met by a deterministic cap of three whole sentences (§9.4). |
| K. In-memory speakable registry as availability source (original design) | Rejected (r1) | Volatile; lost on restart/eviction; raced `capture` publication; skipped recovered and large finals; could not serve other devices or history. |

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
  client's per-profile speech setting. Replay, More and notification Play are deliberate user actions and are
  allowed whenever server speech output is enabled (or client mode is selected).
* R-5 (revised r1) No volatile registry. Speech text always comes from the canonical store at request time
  (§9.2); availability of retained readable content never depends on server process memory.
* R-6 Live server speech requires the logical-chat auto-TTS guard (§2.8) so no reply is synthesized twice or
  synthesized undeliverably.
* R-7 (r1) Two playback classes (§9.1): automatic `live` is author-only, foreground, one-shot; deliberate
  `replay`/`more` (including notification Play) is available to every enrolled device that currently reads the
  conversation, by canonical `message_id`, with fresh authority and no new permission.
* R-8 (r1) Output routing is decided locally before any speak request; earbuds are preferred, the device speaker
  is the approved initial fallback, the user may override per device, and any route loss/change or focus loss
  stops speech first. Nothing falls through mid-speech to a louder route (§9.7).

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
| P1L | `speak` `live` | `context(session,'relay:read')`; current active binding of the same conversation, epoch match; journal `find_request` item with `device_id`/`key_generation` == ctx (author only, no shared receipt); core exact lookup terminal with `final_display_id`; `message_id(row, display)` equals `message_id` when given; live window; no prior `live` row; voice policy; exact display read (§9.2) |
| P1D | `speak` `replay`/`more` | `context(session,'relay:read')`; current active binding whose conversation is the message's conversation, epoch match; current membership; `message_id` resolved to a display identity by a verified alias or the core locate seam under the device's **own** principal (`session:read`); role `assistant`; exact display read and source digest; for `more`, `source_sha256` and `start_cp` provenance (§9.4) |
| P2 | After the TTS await | Grant, membership, binding identity and core epoch again, the identity still visible and **unrevised**: the scalar `NativeDisplayToken` from `check_native_display` equals the token taken before the first segment read (§9.2.3), attempt not stopped |
| P3 | Each downlink batch | Under `DeviceSession._send_lock`, once per lock hold covering ≤ 200 ms of audio: the full `output_check` closure (grant, membership, binding identity, core epoch) plus attempt-not-stopped; failure → no further bytes, attempt `aborted(revoked/stale)` (§7.3) |
| P4 | `speak.stop` | `context(session,'relay:read')`; row `device_id`/`key_generation` == ctx |

Client (native, PROPOSED controller rules):

| # | Point | Check |
| --- | --- | --- |
| C1 | Press | Visible, foreground, selected `ConversationKey` == binding conversation, binding current for this socket generation, voice negotiated, no unresolved own turn/prompt in the conversation, microphone permission, capacity |
| C2 | Each mic frame | `epoch`, `captureGeneration`, socket generation unchanged |
| C3 | Release | C2 + persisted `finishing` record (§8.4) |
| C4 | Transcript arrival | Same socket generation and binding as at release, continuous foreground selection, capture not cancelled, inside intent window → prepare+persist+submit; otherwise draft only |
| C5L | Before `live` | Foreground, same selection, `request_id` is this foreground session's initiating request, not stopped, live speech enabled, an allowed route exists (§9.7), audio focus obtainable |
| C5D | Before `replay`/`more` | A deliberate user control (Replay, More, notification Play after unlock), foreground, current binding to the message's conversation, an allowed route exists, audio focus obtainable |
| C6 | Each downlink frame | `speechGeneration`, `playbackVersion`, `epoch`, socket generation; output muted until the observed route matches the intended one (§9.7) |
| C7 | Stop/hide/replace/new reply/route or focus loss | Increment `speechGeneration`/`epoch` first, stop playback, then send `speak.stop` |

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
| Canonical selection and text | Live: journal + core `lookup_native_admission` final identity [SRC]. Deliberate: durable aliases [SRC] verified by recomputation, else PROPOSED core `locate_native_display`; text always via PROPOSED core `read_native_display` | PROPOSED addition |
| Server TTS | PROPOSED core `synthesize_native_once`, SDK `VoiceOutput` streaming via PROPOSED `send_binary(authorize=)` | PROPOSED |
| Client BYOK synthesis | Phone `PhoneClientSpeech`/`ElevenLabsApi`; watch `WatchSpeech` → phone [SRC] | reuse |
| Playback, audio focus | `PcmPlayback`/`PlaybackOutput` [SRC] under `speechGeneration` | PROPOSED wiring |
| Output routing (one owner per device) | The phone/watch voice speech controller using a PROPOSED pure `SpeechRoutePolicy` and PROPOSED `PcmPlayback` options (preferred device, muted until route verified) over the existing focus/noisy/route observation [SRC] | PROPOSED |
| Raw `relay.response` routing in `DirectConnection` | Task3 (single owner); V5/V6 consume it and only add the voice binary sink | PROPOSED |

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
| `relay.voice.speak` | `relay:read` (+ authorship for `live`) | 32,768 / 6 |
| `relay.voice.speak.stop` | `relay:read` + own row | 8,192 / 6 |

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
           "concise_sentences":3,"concise_code_points":600,"concise_bytes":2400,"full_code_points":3000,
           "full_bytes":12288,"range_utf16_units":4096,"client_chunk_code_points":450,
           "speakable_bytes":262144}}
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
| `preparing` | Decoding/writing audio for the provider; nothing sent yet | null |
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

`relay.voice.speak` params (exact): `{"speech_id":uuid7,"binding_id","authority_epoch","mode":"live"|"replay"|
"more","request_id":uuid7|null,"message_id":uuid|null,"source_sha256":hex64|null,"start_cp":int≥0,
"style":"full"|"concise","output":"server"|"client","format":"pcm16"|"opus"|null}`.

| `mode` | `request_id` | `message_id` | `source_sha256` | `start_cp` | Class |
| --- | --- | --- | --- | --- | --- |
| `live` | required: this device's own durable turn id | optional expected id | null | 0 | automatic, author-only (§9.5) |
| `replay` | null | required canonical id | null | 0 | deliberate (§9.1) |
| `more` | null | required canonical id | required (from the previous range) | an issued boundary (§9.4) | deliberate |

`format` is required for `server` output and must be null for `client`. The binding must be current for the
message's conversation.

Result `SpeechResult` (exact keys):

```json
{"speech_id":"…","message_id":"…","state":"preparing","reason":null,"stream":7,
 "source":{"sha256":"…64 hex…","total_cp":5210,"speakable_end_cp":5210,"limited":false},
 "range":{"start_cp":0,"end_cp":412,"sentences":3,"cut_inside_unit":false,"done":false},
 "chunks":null,"voice":null,"provider":"in_flight"}
```

`chunks` is non-null only in client mode: `[{"start_cp","end_cp","text"}]`, consecutive, covering the range
exactly, each ≤ 450 code points (§9.4). `source`/`range` are null when no range was issued.

| `state` | Meaning |
| --- | --- |
| `preparing` | Accounted; server synthesis being prepared or in flight; frames will follow on `stream` |
| `streaming` / `completed` | Frames are being / were all sent (END frame written) |
| `stopped` | Stopped by `speak.stop` (provider may still have completed) |
| `aborted` | `reason` ∈ `revoked`, `stale`, `disconnected`, `too_long`, `encode_failed` |
| `failed` | `reason` ∈ `tts_unavailable`, `provider_failed` |
| `text` | Client mode: `chunks` carry the exact range text, `voice` the profile voice hint; no server paid call |
| `off` | Live speech suppressed by voice policy (§9.3); not recorded |
| `no_final` | `live` request has no terminal final occurrence (failed/cancelled/steered elsewhere); not recorded |
| `not_speakable` | Message role is not `assistant`, or its text has no speakable code points; not recorded |
| `exhausted` | `more` at `speakable_end_cp`; nothing left; not recorded |
| `unavailable` | `reason` ∈ `beyond_index` (not located within the bounded locate), `source_refused` (core refused the row as too large to project); not recorded |
| `unknown` | Server restarted while synthesis could have been in flight |

Re-sending identical `speak` params with the same `speech_id` is a **receipt read**: it returns the recorded state
and never synthesizes or streams again. Same id with different params → `conflict`. At most one `live` row may
exist per (author device, key generation, `request_id`), including a client-mode `text` issuance; a second
`live` request under a new `speech_id` is `conflict`, so automatic speech of one reply is attempted once.
Deliberate rows are unlimited in kind but bounded by the hourly budget (§7.4).

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
| `invalid_request` | Malformed params, bad UUIDv7 form, format not offered, mode/field combination violating §6.4 |
| `unsupported` | Method/direction/mode not enabled for the caller (after authorization) |
| `permission_denied` | No grant/permission, key/namespace mismatch, `live` by a non-author, revoked membership |
| `not_found` | Unknown capture/speech/request id for this device; message not (or no longer) readable: deleted, hidden, compacted, tombstoned conversation |
| `conflict` | Id reuse with different params or operation, stream id live, finish counts mismatch after seal, second `live`, `source_sha256` changed, `start_cp` not an issued boundary |
| `expired` | New id outside freshness window, `finish` after deadline, `live` after the live window |
| `busy` | Capture/STT/TTS capacity, one live capture/speech per session, voice store full |
| `rate_limited` | Relay token bucket or hourly voice budget, checked before any accounting |
| `temporarily_unavailable` | Store/core/seam unavailable, core read deadline, final not yet terminal or not yet visible |
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
* Output fence shape (M4): the fence is never removed. Each full `output_check` reads the grant document and opens
  a store transaction synchronously (`relay_grants.py` 137–199, `relay_store.py` 717–726), so the normative
  shape is one full check per acquisition of `_send_lock` that then writes a bounded batch of ≤ 200 ms of audio
  (≤ 5 PCM frames or ≤ 10 Opus frames) with no await inside the batch; a failed check writes nothing further.
  Revocation therefore stops the server within one batch; the client may still drain ≤ 0.5 s lead + 0.2 s batch.
  V3 acceptance thresholds [POLICY]: p99 full-check time ≤ 10 ms and p99 added delay to another session's stock
  frames ≤ 20 ms with two concurrently speaking sessions on the registered rig. If missed, the only pre-approved
  alternative is a grant snapshot cached per generation and invalidated on any grant-file, enrollment or core
  epoch change, still checked per batch; anything else needs a new review.

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
| Concise range | ≤ 3 whole sentences and ≤ 600 code points and ≤ 2,400 UTF-8 B | [PROPOSED]; approved "about two or three sentences" |
| Full range | ≤ 3,000 code points and ≤ 12,288 UTF-8 B | [PROPOSED] |
| Any range | ≤ 4,096 UTF-16 units | [PROPOSED]; matches BYOK `SpeechRequest` [SRC] |
| Client chunk | ≤ 450 code points | [POLICY]; keeps BYOK audio well inside its 60 s / 3,000-frame cap [SRC] |
| Speakable source | first 262,144 UTF-8 B of the canonical text (≤ 32 core segment reads) | [POLICY]; beyond it `limited` |
| Message resolution | verified alias scan ≤ 4,096 rows; core locate ≤ 65,536 visible rows newest-first within the existing 2 s read deadline | [SRC] alias bound; [POLICY] locate rows |
| Range provenance retention | 24 h (TTS rows) | [POLICY] |
| Speech audio per attempt | ≤ 300 s, ≤ 15,000 AUDIO frames | [PROPOSED] |
| Output fence batch | ≤ 200 ms audio per full check; p99 check ≤ 10 ms; p99 cross-session delay ≤ 20 ms | [POLICY], V3 gate |
| Route verification | output muted until the observed route class matches, ≤ 500 ms, else stop | [POLICY] |
| Downlink frame | PCM ≤ 40 ms (1,280 B); Opus 20 ms; lead 0.5 s | [WIRE] stock server pacing |
| TTS deadline | 120 s per attempt; provider request timeout 60 s | [POLICY]; [SRC] 60 s |
| Server concurrency | captures 1/session, 4 global; STT 2; speech 1/session, TTS 2 | [POLICY] |
| Hourly per device | 30 STT, 60 TTS attempts, 40,000 TTS input code points | [POLICY] |
| Voice store | ≤ 4,096 rows, ≤ 256 per device, 24 h retention after terminal | [POLICY] |
| Temp sweep | at start and every 60 s; orphan files > 300 s deleted | [POLICY] |
| Client intent window | 120,000 ms from release to transcript | [POLICY] |
| Client voice records | ≤ 16 per enrollment scope, ≤ 64 total | [POLICY] |
| Client uplink queue | 32,000 B | [WIRE] stock native value reused |
| Client receipt backoff | 2, 5, 15, 60 s then manual | [POLICY] |

## 8. Transcription to exactly one TEXT admission (requirement 4)

### 8.1 Server STT

At `finish` (after S3) the bridge, in order: validates the complete capture; inserts the durable row `queued`
(`provider=not_started`, fsync); when an STT slot is free, updates it to `preparing` (still `not_started`);
decodes Opus if needed and writes a private WAV (0600, O_EXCL, voice temp dir) — a failure here is
`failed(audio_decode)` with `not_started`; calls the PROPOSED core seam `transcribe_native_once(path, *, authorize,
before_send, deadline_s)`, whose `before_send` callback durably commits `transcribing`/`provider=in_flight`
(fsync) **immediately before** the provider request and refuses the send if that commit fails; deletes the WAV in
`finally`; recheck S4; stores the bounded transcript with its TTL. `in_flight` is therefore never claimed before
something could actually be sent (M1). The seam (core, PROPOSED):

* uses the runner's own single active profile STT configuration (the bridge already refuses multiplexed
  profiles); no client profile, model, provider, language or path input;
* invokes the configured provider **once** with SDK/client automatic retries disabled and no re-send after a
  request may have reached the provider (pre-send transcode only, no post-rejection retry; a refresh-and-resend after a
  401/403 is NOT performed in a native call — an answered request is never re-sent; the cost is that an expired
  OAuth token yields `failed/failed_after_send` and the local fallback, and pre-send credential freshness belongs to
  V1-P with synthetic transport proof); explicit timeouts replace unpinned SDK defaults; redirects are not followed;
* if the configured attempt does not succeed, tries the **already installed and already cached** local
  `faster-whisper` backend once (`transcribe_audio_local_fallback` semantics under the native context: never
  lazy-install, never download a model — a cache miss is a failure at the model-creation boundary — never run a user
  command template, never another cloud provider);
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
| `queued` | slot | `preparing` (`not_started`; decode/WAV) | yes |
| `preparing` | decode/WAV failure | `failed(audio_decode)` (`not_started`) | yes |
| `preparing` | seam `before_send` | `transcribing` (`provider=in_flight`) | yes, immediately before send |
| `queued` / `preparing` | `discard` | `discarded` (`not_started`) | yes |
| `transcribing` | seam result + S4 ok | `transcribed`/`empty`/`too_long`/`failed` | yes |
| `transcribing` | S4 denied | `revoked` (text never stored) | yes |
| `transcribing` | `discard` | `discard_pending` → on result `discarded` (text never stored) | yes |
| `queued` / `preparing` | process start finds row | `failed(interrupted)`, `provider=not_started` (nothing billed) | yes (startup) |
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

### 9.1 Two playback classes

| Class | Trigger | Who may request | Selection | Automatic |
| --- | --- | --- | --- | --- |
| `live` | Terminal final of the device's own request initiated in the current foreground session | The author device and key only | `request_id` → journal → core final identity | Yes, at most once per request |
| Deliberate (`replay`, `more`, notification Play) | An explicit user control | Any enrolled device whose grant currently holds `relay:read`, is a current member and holds a current binding to that conversation — phone, watch, either author | Canonical `message_id` → verified identity → exact canonical read | Never |

Approved behaviour requires both classes (expansion spec decisions 7–8, N1, N5; voice requirements "Replay is
deliberate and must refresh exact source authority", "explicit Replay remains usable"). Neither class grants new
authority, copies keys, accepts client text or broadcasts audio: output goes only to the requesting socket.
Copies — O5 final/task copies, notifications, cached history, task inbox entries — are at most a `message_id` hint;
selection and text always come from the canonical store at request time. Recovered or other-device content is
never spoken automatically; it may be played only by a deliberate control on a device that can read it now.

### 9.2 Canonical resolution and text source (server)

Actual source (§1): aliases durably map display ids to public `message_id`s but are bounded to 4,096 rows with
oldest-first eviction, and are re-created whenever history is paged; canonical text is readable only by position
(`read_native_history_fragment`, watermark/ceiling fence, 8,192-byte nominal segments, 2 s deadline). There is no
identity-addressed read, and `message_id` is a one-way hash. An unbounded position scan is not a selection method.
The design therefore adds the minimal reviewed core display seam (PROPOSED, §12.1) and resolves as follows:

1. **Live identity**: `found.result.final_display_id` from the exact journal lookup (P1L).
2. **Deliberate identity**: (a) scan the alias table (≤ 4,096 fixed rows, off-loop) for the binding's
   conversation; accept a candidate only if `message_id(row, display_id)` recomputed from the current conversation registry
   row equals the request and its `logical_ref` is the current route — the alias is a verified hint, never
   authority; (b) on a miss, call `locate_native_display(actor, ref, *, authorize, matches, max_rows=65536)`,
   which streams only identity scalars of currently visible display rows newest-first (no content), evaluates the
   SDK's recompute predicate, and stops at the first match, the row bound or the existing 2 s read deadline. A
   hit re-writes the alias through the existing `_alias` path (cache only). Either budget — the row bound or the
   2 s deadline — ends the search with `RuntimeStoreError('read_budget')` whose `field` is `locate_rows` or
   `locate_deadline`; the bridge maps both to `unavailable(beyond_index)` (a search that did not complete never
   reports absence, and there is no endless same-work `temporarily_unavailable` loop). A completed search without a
   match returns `None` → `not_found`. A field-less `read_budget` (the matched or requested row is too large to
   project) maps to `unavailable(source_refused)`.
3. **Text**: `read_native_display(actor, ref, *, authorize, identity, segment=0, max_bytes=65536)` returns exactly the
   `HistoryFragment` shape and segmentation of the existing reader for that one visible identity. The bridge reads
   segments 0..k until the speakable bound (262,144 UTF-8 B, ≤ 32 calls, each within the 2 s deadline), computes
   `source.sha256` over exactly the bytes read through `speakable_end_cp`, `total_cp` and `limited`. Live and
   deliberate playback use this same source, so offsets agree across devices and restarts.
   **Unrevised fence (P2).** `check_native_display(actor, ref, *, authorize, identity)` is synchronous and
   scalar-only: it returns `NativeDisplayToken(identity, physical_id, revision)` from the visible-row projection and
   the row's existing content-revision stamp (`display.content.revision.v1.<physical>`), reads no content column
   and is fail-closed on a malformed stamp (`temporarily_unavailable`). The bridge takes token **A** immediately before
   the first segment read, token **B** after the last, and token **C** at P2: `A != B` means the row changed during
   the read (a torn read, `conflict`); `A != C` means it changed after (`conflict`); equal tokens mean the digested text
   is the text now. An edit before token A is not an inconsistency: the read sees the revised text, which is the
   current canonical text. A compaction that republishes the row changes `physical_id` and is refused the same way
   (deliberate Replay repeats it). Existing sources use `check_existing_display(actor, target, *, adapter, source,
   authorize, row_id)` with the same token.

   **Mandatory V3 CORE PREREQUISITE (visibility, round-2 N1).** The V1 token proves projection membership,
   physical row and content revision only. It is NOT universal visibility proof: a row the canonical normalizer
   hides (`display_kind` `hidden`/`compression_summary`/`context_summary`, a compressed-summary carrier) stays in the
   projection, and an in-place writer (`set_latest_matching_message_display_kind`) can stamp a row hidden after the
   read without a content change, leaving the token equal. Before ANY V3 consumer relies on the token, a core change
   must add the `display_kind` (≤ 128 bytes) and compressed-summary scalars the sibling `_fingerprint` already selects
   (or equally strong actual sibling fingerprint semantics) to the token, covered by a test that reads a visible
   assistant row, stamps it hidden, and sees a token mismatch or `not_found`. Until that gate passes V3 must not
   activate: the bridge's "read before fence" only covers rows that were already hidden at read time. This is a
   staged prerequisite, not a waiver.
4. **Actor and fences**: every core call uses `core.actor(ctx.device_id, row, ...)` (the reading device's own
   principal with `session:read`) and the request's `output_check` as `authorize`; nothing is cached across
   requests except the verified alias.
5. **Truthful outcomes**: identity no longer visible (deleted, hidden, compacted, revised away) → `not_found`;
   tombstoned conversation → `not_found` under existing tombstone rules; revoked grant/membership →
   `permission_denied`; route/profile mismatch → `profile_changed`; core refuses projection (oversized carrier)
   → `unavailable(source_refused)`; role ≠ `assistant` or no speakable code points → `not_speakable`. Canonical
   deletion also purges matching range rows (§13).
6. **Existing-source sessions** (opted-in non-Relay history): the same contract under `history:existing` and the
   existing binding, with ids recomputed as `opaque(server, profile, 'existing-message', session_id, row_id)`
   (`relay_existing.py` 370), through PROPOSED `locate_existing_display`/`read_existing_display` analogs over the
   existing core reader. It inherits the retained existing-history 8,192/65,536 regression; it is required, and
   accepted only with its own V3 registered test after that regression is resolved.

This is new wire semantics (message-addressed selection) defined now; the earlier claim that it could be added
later without a wire change is withdrawn.

### 9.3 Voice policy

* Server mode: the profile's configured TTS provider and voice (ElevenLabs `voice_id`/`model_id` where
  configured, otherwise the configured/default provider) resolved by the core seam from the runner's own profile
  at call time.
* Client mode (phone BYOK, per profile): the server returns the exact range as `chunks` and the profile voice
  hint; the phone synthesizes with its own per-profile key via the existing bounded, no-retry client path; the
  watch sends each chunk to its phone over the existing client-speech channel. No server paid call (§9.8).
* Live mode is `off` when server `speak_replies` is false, when the device's stock chat has `/voice off`, or when
  server voice output is disabled; the client additionally suppresses live speech when its per-profile speech
  setting is off or media is muted by the user. Deliberate playback is allowed whenever server speech output is
  enabled (or client mode is selected).

### 9.4 Ranges, walking concision and exact continuation

* **Coordinates**: Unicode code-point offsets into the exact canonical display text returned by the core read —
  no Unicode normalization or markdown stripping before offsets are computed; half-open `[start_cp, end_cp)`.
  Spoken-text cleanup (`speakable_text`) is applied only to each range's text at synthesis or issuance and never
  moves an offset. `source.sha256` pins the exact source; any change is `conflict`.
* **Units**: a sentence ends after `.`, `!`, `?`, `…`, `。`, `！`, `？` (plus any closing `"'’”)]}`) followed by
  whitespace or end of text; a newline also ends a unit; trailing whitespace belongs to the preceding unit.
* **Packing** from `start_cp`: append whole units while the unit count is below the style's sentence cap and the
  range stays within every cap — concise: 3 sentences, 600 code points, 2,400 UTF-8 B; full: 3,000 code points,
  12,288 UTF-8 B; both: 4,096 UTF-16 units and never past `speakable_end_cp`.
* **Cut inside an over-long unit** (`cut_inside_unit:true`): end = the largest offset within the caps whose
  preceding code point is whitespace and which is > `start_cp`; else the cap offset moved backward while the code
  point at `end` is a continuation (general category Mn/Mc/Me, U+200D, U+FE00–U+FE0F, U+E0100–U+E01EF,
  U+1F3FB–U+1F3FF, or the second of a regional-indicator pair); if that reaches `start_cp`, move forward past
  continuations instead. Every issued range therefore holds ≥ 1 code point and never splits a code point.
* **End markers**: `done` ⇔ `end_cp == speakable_end_cp`; `limited` ⇔ canonical text continues beyond the
  speakable bound (it stays fully displayed, and the UI says speech stopped there). A range counts as covered only
  after the client played it completely (END received and playback drained); `end_cp` never claims the user
  heard anything.
* **Continuation**: More uses `start_cp` = `end_cp` of the last completely played range; after Stop, route or
  focus loss, or an abort, the client may resume at the interrupted range's `start_cp` (that range only is
  re-spoken). The server accepts `start_cp` only if it is 0 or the `start_cp`/`end_cp` of a range previously
  issued to this device and key for the same `message_id` and `source_sha256` (24 h provenance); otherwise
  `conflict`. `start_cp == speakable_end_cp` → `exhausted`. Empty or whitespace-only text → `not_speakable`.
* **Guarantees**: concatenating consecutive completed ranges starting at 0 reproduces exactly
  `text[0:speakable_end_cp]` — no gaps, no overlaps; each request advances ≥ 1 code point, so More terminates in
  at most `speakable_end_cp` requests; the full answer is never replaced or truncated on screen.
* **Walking mode** uses `concise`; "Tell me more" is `more` with `concise`; Replay is `replay` (offset 0) in the
  user's current style. Outside walking mode, `live` uses `full`.
* **Client chunks**: the server splits each client-mode range into consecutive chunks ≤ 450 code points with the
  same unit and cut rules; the range is covered only if every chunk played.

### 9.5 Automatic live playback

The client issues at most one `live` call per request it initiated **in the current foreground session**, only
when the receipt is terminal, the final is displayed, C5L holds and the live window has not passed. The server
enforces authorship, the window and the single `live` row (also for client mode). A typed initiating request
qualifies like a spoken one, matching stock speaker devices that speak replies to typed messages (adapter
`_should_auto_tts_for_chat`); §9.3 voice-off rules decide both. If the final row is not yet visible the answer is
`temporarily_unavailable`; the client retries the same `speech_id` with backoff only inside the window. There is
no fanout from a logical route to any speaker.

### 9.6 Notifications and history

A notification or history entry never speaks by itself. A tap (or a notification "Play" action) first brings the
app to the foreground with the device unlocked, attaches to the exact profile and conversation, refreshes history
and task state, and only then may the deliberate `replay` run for the canonical `message_id` that the refreshed
view shows. If the message is no longer readable the user sees the truthful outcome (§9.2.5), never stale copy
audio. Task-change notifications carry no message; their tap opens the task, and Play is offered on the final
message once it is displayed.

### 9.7 Output routing and walking controls (native)

Per-device setting (PROPOSED, stored with that device's preferences, never synced): `AUTO` (default),
`EARBUDS_ONLY`, `SPEAKER`. Phone and watch each own their routing through their voice speech controller; the
pure `SpeechRoutePolicy` (client module) decides, `PcmPlayback` executes and observes.

1. **Before any speak request** (no paid call without an allowed route): enumerate outputs and classify them with
   the existing `playbackOutputForDeviceType`. `AUTO`: a connected headphones-class output → intended
   `HEADPHONES` (preferred device set on the track); none → intended `SPEAKER` (the approved initial fallback:
   watch speaker on the watch, phone speaker on the phone). `EARBUDS_ONLY` with none connected → no request,
   notice "Connect earbuds or change output". `SPEAKER` → intended `SPEAKER`.
2. **Start**: the track plays muted until the observed routed class equals the intended class (≤ 500 ms), then
   unmutes; a mismatch or timeout stops first and leaves the range uncovered. A preference is never treated as
   proof of the actual route.
3. **During speech**: becoming-noisy, focus loss (`LOSS`, `LOSS_TRANSIENT`), any change of the observed route
   class (either direction), a change of the user's setting, an incoming capture press, Stop, hide or selection
   change → increment `speechGeneration` first, then stop the track, then send `speak.stop`. The interrupted range
   stays uncovered. Ducking (`LOSS_TRANSIENT_CAN_DUCK`) does not stop.
4. **After loss**: the user sees why speech stopped and deliberate choices — Resume (re-speak the interrupted range
   on the now-allowed route), Replay, or change output. Nothing resumes or moves to another route automatically;
   earbud disconnect never redirects private speech aloud.
5. **Walking controls**: large Stop (primary), Replay, More and Output controls; phone touch targets ≥ 64 dp in
   walking mode with TalkBack labels, state descriptions ("Speaking on earbuds", "Stopped: earbuds disconnected")
   and live-region announcements of stop reasons; the watch keeps portrait hold-to-talk, horizontal profile swipes,
   recording rings, start/stop ticks and light haptics, adding the same controls on the reply surface; large-font
   (1.3+), reduced-motion and no color-only state. Phone input/output waveforms stay.

### 9.8 Client (BYOK) mode boundaries

Client-mode authority ends at P1L/P1D text issuance: the response carries the exact chunks to the authorized
requesting device over its own socket. Output fencing after that is device-local (`speechGeneration`,
`playbackVersion`, and on the watch the `WatchSpeech` epoch); the watch-to-phone speech channel carries only
`(endpointId, id, text, voice, format)` and no Relay binding or authority proof. A phone may complete a paid BYOK
synthesis after a revoke, Stop or route loss; its audio is then discarded unplayed and the chunk stays uncovered.
Chunks ≤ 450 code points stay inside the existing BYOK limits (≤ 4,096 UTF-16 units; ≤ 60 s audio, enforced only
after the provider accepted the request); an over-limit chunk is a truthful `client_failed(output_limit)` and is
never re-sent automatically. The per-profile key is selected by `endpointId` = SHA-256 of the endpoint URL; that
this maps one-to-one onto the Relay profile is an assumption to test in V5/V6 (a `profile_changed` freezes BYOK
use for that endpoint until review).

## 10. Paid attempts, deduplication, crash semantics, privacy (requirement 6)

### 10.1 Accounting rules

* A paid provider call happens only after a durable `VoiceStore` row for that `capture_id`/`speech_id` exists and
  the seam's `before_send` callback has committed `provider=in_flight` (SQLite `synchronous=FULL`, fsync)
  immediately before the request. Preparation (audio decode, canonical read, normalization, range packing) runs
  while the row says `not_started`. Volatile state is never billing evidence.
* One row ⇒ at most one configured-provider call (plus at most one free installed local STT fallback). A
  repeated `finish`/`result`/`speak` with the same id reads the row; it never calls a provider.
* No automatic paid retry anywhere: not in the seam (retries disabled), not in the bridge, not in clients. A new
  paid attempt requires a new deliberate user action that mints a new id (a new press, Replay, More, Resume).
  Deliberate playback by any authorized device follows exactly the same row, budget and fence rules as `live`.
* `provider` is reported truthfully; `unknown`/`failed_after_send`/`in_flight` are shown as "may have been
  billed".
* Hourly budgets are enforced before accounting; refusal costs nothing.

### 10.2 Crash and loss matrix

| Moment | Server state after restart | Client behaviour |
| --- | --- | --- |
| Before `finish` arrives | No row; audio gone | `finishing` lookup → `not_found` → "Recording lost, not sent" |
| Row `queued` committed, provider not called | `failed(interrupted)`, `not_started` | "Not transcribed, not billed"; new press needed |
| Row `preparing` (decode/WAV), nothing sent | `failed(interrupted)`, `not_started` | "Not transcribed, not billed" |
| Row `transcribing` committed by `before_send`, before/during provider send | `unknown` (conservative) | Truthful unknown; no auto retry |
| During provider call | `unknown` | Same |
| Provider returned, before transcript stored | `unknown` (transcript lost; paid) | Same |
| Transcript stored, response lost | `transcribed` | `result` receipt returns it; C4 decides auto vs draft |
| Client dies after `finish` written | Unaffected | On restart: `result` receipt; transcript only as draft |
| Client dies after transcript, before ledger write | Unaffected | Restart: draft only (no `linkedRequest`) |
| Client dies after ledger+link write, before submit | Unaffected | Restart: Task2 restored turn `UNCERTAIN(PROCESS_LOSS)` → receipt first; never re-prepare |
| Submit response lost | Journal/core per existing rules | Task2 receipt-first |
| TTS row `preparing` (canonical read/ranges), nothing sent | `failed(interrupted)`, `not_started` | Nothing billed; no auto speech |
| TTS row `in_flight`, crash before/during synthesis | `unknown` | No auto speech after restart; text shown; deliberate Replay still possible |
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
* Logs, diagnostics and N8 redacted reports: enum states, counts and durations only; no transcript/reply text,
  audio, paths, provider names/messages, keys or credentials, for every `relay.voice.*` request and response.
* Content-bearing wire responses are expected and confined (M2): `CaptureResult.transcript` goes only to the
  capturing author device and key; client-mode `chunks` text goes only to the authorized requesting device
  (§9.1); server-mode audio goes only to the requesting socket. Each leaves under `send_json`/`send_binary`
  re-authorization on that device's own authenticated socket, never to another device or a log.
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
| Stop button | `speechGeneration++`, stop playback; range uncovered | `speak.stop` | Stop flag; abort frame | Claim provider cancelled |
| Replay / More / Resume (deliberate) | C5D; route decided first (§9.7) | `speak` `replay`/`more` | P1D; account; synthesize | Automatic start; arbitrary offsets |
| Notification tap / Play action | Unlock, foreground, attach, refresh, then deliberate Replay (§9.6) | attach, history, `speak` | P1D | Speech from the copy itself |
| Hide / background | `epoch++` first, then stop mic, playback, synthesis, close socket (existing order) | socket closes | Live capture dropped; sealed STT continues; speech aborted on disconnect | Late playback; auto submit later |
| Profile replacement | As hide; old scope voice records frozen | — | — | Cross-profile transcript or audio |
| Conversation selection change | Withdraw auto-submit (draft in original conversation); stop playback | `discard` if recording; `speak.stop` | — | Submit into the new conversation |
| New reply arrives while speaking | Stop current speech (it belongs to an older request) | `speak.stop` | — | Speak a reply not initiated in foreground |
| New capture while speaking | Stop speech first | `speak.stop`, then `start` | Session speech aborted | Overlapping capture and output |
| Audio focus loss (`LOSS`/`LOSS_TRANSIENT`) | `speechGeneration++` first, stop; range uncovered; deliberate Resume offered | `speak.stop` | Stop flag | Resume automatically |
| Earbuds disconnect / becoming noisy | `speechGeneration++` first, stop; "Stopped: earbuds disconnected"; deliberate Resume/Replay/output choice | `speak.stop` | Stop flag | Continue aloud on the speaker |
| Observed route class change (either direction) | Same stop-first; deliberate Resume on the new route | `speak.stop` | Stop flag | Silent mid-speech fallthrough |
| User changes output setting while speaking | Same stop-first; Resume follows the new setting | `speak.stop` | Stop flag | — |
| No allowed route at start (`EARBUDS_ONLY`, none) | No request; notice | — | — | Paid call |
| Route mismatch at start | Track muted until verified; mismatch/timeout → stop-first | `speak.stop` | Stop flag | Audible output on the unintended route |
| Rebind (re-attach) | Captures before seal lost; transcript after rebind → draft | — | Old binding retired; S2/S3 fail for old | Auto submit across binding |
| Revoke (`permission_denied`) | Stop all, evict scope content, disable voice | — | S4 erases transcripts; P3 stops frames | Delivering audio/text after revoke |
| `profile_changed` / enrollment replacement | Review flow; voice disabled for scope | — | Context refused | Using old key/records |
| Core authority epoch change | Speech stops (P3 `stale`); captures before seal fail at finish | — | `stale_epoch` | Output under old epoch |
| Disconnect/reconnect | Unsealed capture lost; sealed result via receipt → draft | `result` after reattach | Unaffected | New capture/turn minted automatically |
| Phone process loss | Restore: records/ledger; no auto speech, no auto submit | Receipts only | — | Recovered speech or second admission |
| Server restart | Results may be `unknown`; no automatic speech resumes; deliberate Replay works from canonical storage | — | Startup marks `in_flight` rows `unknown`, `preparing` rows `failed(interrupted)`, sweeps temp | Retry of an `unknown` attempt |
| Canonical delete | Both devices invalidate; drafts kept local only | — | Range rows for the conversation purged; reads answer `not_found` | Speech of deleted content |

Watch specifics: portrait hold/release/slide gestures, `RecordingPortrait` rings, `RecordingCues` with watch light
haptics, foreground lease and screen-turn notices stay; the watch uses its own enrollment, socket (TLS through the
phone byte relay, never terminated on the phone), its own `VoiceCaptureLedger` and its own output routing (watch
speaker fallback, earbuds preferred). Phone specifics: input level history/waveform from PCM frames, output
waveform from playback level callbacks, recording elapsed timer, phone routing with phone speaker fallback.

## 12. Entry points, version validation and registered fixtures (requirement 8)

### 12.1 Server activation gate (all required; otherwise no voice method is listed)

* Adapter flags (PROPOSED, default false): `enable_relay_voice_input`, `enable_relay_voice_output`; both also
  require `enable_relay_conversations` and the existing bridge.
* Core seam (PROPOSED module `gateway.session_native_voice`, constant `NATIVE_VOICE_VERSION == 1` on the module
  and on `SessionAuthority`), validated like `CoreBridge`: exact dataclass fields
  `NativeTranscription(state, transcript, source, provider)`,
  `NativeSpeech(state, path, code_points, source, provider)`,
  `NativeDisplayLocation(identity, physical_id, role, created_at_ms, segment_count, size_bytes)` (`physical_id` is an
  int equal to `HistoryFragment.physical_id`) and `NativeDisplayToken(identity, physical_id, revision)`; ABI, with
  parameter kinds and defaults exactly as implemented (the consumer validates `inspect.signature`):
  `transcribe_native_once(path, *, authorize, before_send, deadline_s)` and
  `synthesize_native_once(text, *, output_dir, authorize, before_send, deadline_s)` (both coroutines),
  `speakable_text(text)`, and on `SessionAuthority`:
  `locate_native_display(actor, ref, *, authorize, matches, max_rows=65536)` (coroutine; `matches(identity: str) -> bool`),
  `read_native_display(actor, ref, *, authorize, identity, segment=0, max_bytes=65536)` (coroutine, returns the existing
  `HistoryFragment`), `check_native_display(actor, ref, *, authorize, identity)` (sync, returns the token), and the
  existing-source analogs, which also take `adapter` and `source` like every existing-source reader because they prove
  the actual device principal: `locate_existing_display(actor, target, *, adapter, source, authorize, matches,
  max_rows=65536)`, `read_existing_display(actor, target, *, adapter, source, authorize, row_id, segment=0,
  max_bytes=65536)` and `check_existing_display(actor, target, *, adapter, source, authorize, row_id)`; single-profile
  runner (existing refusal of `multiplex_profiles`). Display functions authorize `session:read` for the actor before and
  after the read, use the existing visible-row projection, 2 s deadline and progress handler, never hydrate other
  rows' content, and raise the existing `RuntimeStoreError` reasons.
* **Result vocabulary and accounting (the mapping V2/V3 consume).** STT `state` ∈ `transcribed, empty, too_long,
  unavailable, failed, timeout, refused, cancelled`; TTS adds `synthesized` in place of `transcribed`. `provider` ∈
  `not_started` (no request left, and the consumer's `before_send` did not commit), `completed` (every sent request was
  answered), `failed_after_send`, `unknown` (a request may have left, or audio/transcript came back through a path the
  gate never saw: reported as `failed`/`unknown`, never `synthesized`/`not_started`; this includes an engine that,
  after the injected client was proven and the SDK invoked, RAISES or answers a failure with zero gated sends in that
  scope — the exception path is as ambiguous as the success path, so it is `failed`/`unknown`, a configured-STT
  failure of that kind runs NO local fallback, and only failures before the invocation is entered, or recorded policy
  refusals, stay `unavailable`/`not_started`). `refused` with `completed` means
  authority was lost after the provider answered: the result is withheld, the attempt stays billed. `source` is
  `configured` once the configured provider was used, `local_fallback`, or `null`. `cancelled` is returned only when a
  callback raises `asyncio.CancelledError`; a real cancellation of the awaiting task propagates `CancelledError` and
  fences the worker (no later callback, send or file), so the consumer's durable `before_send` row is the only
  record of a possible send. Exceptions: `speakable_text` propagates a normalizer failure (no silent `strip()`
  fallback); an exception from the consumer's `matches` predicate escapes `locate_native_display` unchanged; neither
  is mapped by the core. The mapping from each `(state, provider)` pair to the wire `state`/`reason` is V2/V3's
  reviewed contract: no new wire value is introduced.
* **V1 prerequisite provider coverage (stage amendment, plan V1/V1-P).** The seam holds to one request: STT
  `openai`, `groq`, `deepinfra` (pinned model), `xai`, `elevenlabs`, `mistral`, cached local `faster-whisper`
  fallback; TTS `edge`, `elevenlabs`, `openai`, `deepinfra` (pinned model), `mistral`, `minimax`, `xai`, `gemini`. Everything else is refused
  **unsent** (`unavailable`) — never replaced by another provider — until its V1-P gate passes. This is not the final
  provider scope and is not configured-provider acceptance. **Mistral (V1-P Task 1, source delivered, source dependency admitted,
  qualification open):** the real pinned `mistralai` 2.4.8 SDK is held to one gated request per STT call / TTS chunk (client
  built by the policy, identity proven before the invocation, `retries=None`, explicit timeout, no redirects, SDK
  trace exporter and debug logger off, the answer bounded as it is read); an endpoint signature that cannot carry a
  configured field is refused unsent — the pinned STT endpoint has no `prompt`, so a configured `stt.prompt`
  makes native Mistral STT `unavailable`. The conservative-unknown rule stands and its consequence is accepted: a
  failure after an entered invocation with no gated send is `failed`/`unknown` with no STT fallback, also when the
  true failure was local; V2's wire mapping must not promise otherwise. The remaining mandatory V1-P provider-parity
  gates (plan V1-P, mirrored here) are: STT Mistral — real installed SDK transport, one gated send, zero hidden
  retries (delivered as above); STT command
  and plugin (including `local_command`) — native-once capability adapter, no implicit install/download, explicit
  cancellation and output bounds, incapable custom implementations refuse UNSENT; TTS NeuTTS/Kitten/Piper — installed
  AND cached assets only, no downloads, bounded local execution, truthful `not_started` remote accounting, no
  paid-provider fallback; TTS command and plugin — the same native-once capability contract (an arbitrary program
  cannot be certified single-attempt by a provider-name allowlist); full-range Edge and the missing-joiner behaviour
  below. **REST TTS MiniMax/xAI/Gemini and xAI OAuth pre-send freshness (V1-P Task 2, source delivered, source dependency admitted,
  qualification open):** these three send through one stock helper, `tts_tool_providers._post_json`, which inside a native call is
  the HTTP boundary — the gate runs immediately before `requests.post` (the REST STT adjacency), the timeout is
  min(60 s, call bound), redirects are not followed (a 3xx is a counted unusable answer), the body is requested
  uncompressed and read in 64 KiB reads under the 16 MiB chunk cap (chunk-granular, not an allocation-hard cap). Failures
  before the gate are `unavailable`/`not_started`; after it, `failed_after_send` with exactly one send; the first
  failed chunk stops the call. **Auxiliary speech tags are omitted:** a native call makes zero
  `call_llm(tts_audio_tags)` requests — xAI keeps its local `[pause]` pass, Gemini the unchanged transcript — so a
  profile with `auto_speech_tags`/`audio_tags` does NOT speak the same script as an ordinary call. **xAI OAuth freshness:**
  the stock resolver/pool refreshes an expiring token before returning it; a native call additionally refuses UNSENT an
  OAuth bearer without a future JWT `exp` (opaque bearers are refused), never refreshes or resends after a 401/403, and
  the OAuth exchange is never a paid attempt (no `before_send`, no accounting) and carries its own authorization-only
  credential phase — the consumer's `authorize` is asked on its loop, under the paid gate's fence, immediately before each
  actual OAuth discovery/refresh request; a Stop/deadline/lost authority stops the next request and every send (no account
  bench, no fallback), while an already-admitted POST may finish and persist its rotated token (not remote cancellation). The refusal is terminal for the call (the policy is closed, first reason kept), so it also holds under the STT auto-detect, whose quiet availability probe swallows exceptions: no other cloud provider, fallback or further consumer callback follows. Carried Mistral review findings that bind this stage: the process-global OpenTelemetry provider residual
  (M1, open until the current-profile gate inspects plugins and the tracer provider), the telemetry read-back is not proof
  (M2; the behavioural exporter test is), the stock Mistral STT path forwards `stt.prompt` that the pinned SDK rejects (M9),
  the 1 MiB STT answer bound is enforced by the Mistral hook only (M9), the Mistral wire bound is chunk-granular (M5), and
  the native log filter now covers the credential-pool and Mistral hook loggers (M3; a dropped record is dropped for every
  handler).
* **Edge speech bound and its join dependency (round-2 N2).** The Edge library sends one websocket message set per
  4,096 escaped bytes, so a native Edge chunk is capped at 600 code points: one chunk = one gated handshake. Concise
  ranges (≤ 600 code points) are independent of any joiner. A full or multi-chunk Edge range is permitted only with
  the usable existing ffmpeg joiner (the same `ffmpeg` the stock concat uses); a missing joiner is
  `unavailable`/`not_started` BEFORE any send, never a paid fallback and never a silently truncated text. Hosts
  without ffmpeg must install that normal Hermes runtime prerequisite for full Edge speech; V1-P tests full-range
  Edge and the missing-joiner outcome, V3/user setup must expose the prerequisite, and the final rollout verifies it
  on each supported box. Unsupported hosts are not claimed to pass.
* **Auxiliary native audio: format boundary and prerequisites (AUX-audio, source only).** The native STT ingress packages a private **WAV**
  (§8.1); the standalone core seam accepts any path whose suffix the stock pipeline supports, but a native call does **not** offer Silk: `.silk`
  is refused as an unsupported format (`unavailable`/`not_started`) before any lazy install, `pilk` import or in-process decode, while stock
  Silk (QQ/WeChat) is unchanged. Cloud STT preparation (duration probe, silence trim, CAF conversion, full-recording compaction) runs only as
  launcher-wrapped, bounded children; the optional probe and trim are skipped (no refusal, the full recording is uploaded) on a host without a
  qualified launcher or the tool, and an unknown duration is left to the vendor in the one counted request. A recording whose full compaction
  still needs several requests is refused before any piece or send (reachable from the standalone seam, not from the 60 s capture ingress); it is
  `failed`/`not_started` after the compaction ran and `unavailable`/`not_started` when the capability is absent, and the installed cached-local
  fallback stays available as for any ordinary failure. Native Gemini needs the qualified launcher and a working ffmpeg, proved by a converter
  preflight before the first request (single chunk included), and never writes a WAV under an `.mp3` name. Costs: unqualified or tool-less hosts
  lose the optional trim/measurement (STT) or Gemini speech (`unavailable`/`not_started`); a standalone CAF expanding beyond the 64 MiB
  preparation bound is refused terminally, also when the child exits non-zero after leaving the oversized file. A command-STT provider configured
  `native: local-sandboxed` with `normalize: true` needs the same qualified converter (required work: it counts as a local launch). When the outer
  deadline wins after a send may have left, the existing gate reports provider `unknown`, not `completed`. Not evidence of any installation, decoder,
  launcher, `afconvert` or runtime behaviour.
* **NeuTTS dependency rows (source only).** The native NeuTTS engine is validated against its own metadata (transformers 5.1.0 and the normal
  phonemizer 3.3.0 or 3.4.0, never the Kitten lock's transformers 5.17.0 / phonemizer-fork); the managed 3.14 interpreter stays unsupported (NeuTTS
  requires Python below 3.14) and is refused before any import. Costs: normal and fork phonemizer metadata together is refused as ambiguous; a
  previously accepted upstream-inconsistent environment is refused; Kitten and NeuTTS cannot share one interpreter at the admitted rows; only the two
  inspected phonemizer releases are source-qualified (their decimal phonemization differs upstream, neither is forced). A usable NeuTTS still needs a
  separately provisioned supported interpreter below 3.14, its closure, a platform wheel and complete offline caches; none of that is provisioned or
  evidenced here.
* **Qualification follow-up (source and tooling only).** Profile-shaped ElevenLabs/STT controls run the real seam with sentinels (the
  installed ElevenLabs SDK over synthetic network bytes, stock config and `.env` scope, the registered authority as `authorize`); they
  are source coverage, not current-profile proof. The controller's metadata path must label raw declarations, resolved config,
  credential-presence origin, static plugin declarations and the ACTUAL initialized plugin registry and tracer provider
  separately; YAML is read with the stock safe helper and `.env` data with the pure python-dotenv parser as RAW declarations only (the
  stock resolution of `${VAR}`, layering and sanitization is not reproduced, so resolved availability stays UNKNOWN); an unobtainable
  runtime fact is recorded UNKNOWN with its remaining action, and M1 stays open until it is inspected.
  The host harness is Darwin 27 arm64 only; other boxes are qualified at the per-box rollout gate and are not claimed here.
* **Activation scope (V2 input stage amendment).** Source implementation, both flags default false, may proceed on this
  admitted seam without runtime-qualifying every optional backend. Broad optional-provider qualification is separate from
  per-profile activation. Before a profile or direction is enabled, a later reviewed rollout must prove the CURRENT exact
  resolved provider/config/runtime compatibility (initialized plugin registry and tracer included, M1) and supported media
  for THAT profile and direction; unknown or unavailable configuration answers truthfully (`unsupported`, `stt_unavailable`,
  `tts_unavailable`) and keeps text and stock conversations usable. Hugging Face/NeuTTS and Mistral accounts, model installs
  and paid calls are NOT prerequisites of an ElevenLabs-only profile; the no-install, once-only, transport and launcher
  gates, no provider substitution and no paid fallback stay. No wire schema, provider roster in a user DTO or bespoke
  account UI is added. Historical V1-P reports stay as written; V1-P is not recorded as passed.
* Voice output is listed only with the complete seam (TTS **and** display read): there is no reduced
  live-only mode that would narrow approved deliberate playback.
* Hub: PROPOSED `DeviceSession.send_binary(data, *, authorize=None)` mirroring `send_json`; channel-5 routing in
  `_on_binary` before stock handling, active only for a session that completed `relay.voice.info`.
* Logical-chat auto-TTS guard (R-6): `_should_auto_tts_for_chat` returns false for `_is_logical_chat` routes.
* Opus directions require the existing `enable_opus` and codec availability; client mode requires the existing
  `enable_client_tts`, `client_speech_allowed` and the device's `tts:"client"` capability.

### 12.2 Native entry points (PROPOSED)

`protocol.relay.RelayMethod` gains the seven names with their bounds; a pure `protocol.relay.voice` package holds
`VoiceInfo`, `CaptureResult`, `SpeechResult` DTOs, call builders, the channel-5 frame codec,
`VoiceCaptureLedger` and a pure range-coverage ledger (issued boundaries, completed ranges, source digest per
message). `DirectConnection` keeps Task3's raw `relay.response` routing (Task3 is its single owner) and gains a
voice binary sink enabled per socket generation and a non-failing voice uplink backpressure check. The client
module gains the pure `SpeechRoutePolicy` and `PcmPlayback` options for a preferred output device and
muted-until-verified start. The phone and watch voice speech controllers own gestures, feedback, routing and
playback without changing stock paths.

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
* The display seam is tested on the same rig with real core history rows: large lineages (up to the 65,536-row
  locate bound), hidden/compacted/deleted/revised rows, tombstones, other-device readers and restarts.
* Synthetic seams prove routing, counts, cancellation and fences only; never provider quality, voice identity or
  device audio.

## 13. Persistent fields and ordering summary

Server `VoiceStore` (`relay-voice.sqlite3`, private directory, namespace row pinned to the RelayStore
`(server_id, profile_id, core_profile)`, server time read from the existing RelayStore clock high-water): rows as
§8.4 plus TTS rows (`kind=tts`, `speech_id` identity, `mode`, `style`, `output`, `message_id`, `conversation_id`,
`source_sha256`, `start_cp`, `end_cp`, `chunks`, `request message_id` for `live`, `code_points`, `state`
(`preparing` → `in_flight` → terminal), `reason`, `provider`, timestamps; unique index on live rows per
(device, key generation, request)). Range rows are the 24 h provenance for More (§9.4) and are purged with
canonical deletion or enrollment retirement. No canonical text is stored here. Older SDKs never open this file,
so rollback leaves the existing journal schema untouched.

Client: `VoiceCaptureLedger` snapshot section (version 1) inside the same atomic encrypted record as the Task2
`RelayStateSnapshot`, so `prepare` + `linkedRequest` commit together; bounded (§7.4) with a `TooMany` refusal
checked before any persist-before-send step. Order with Task3 (M7): V4 defines this section and the aggregate
snapshot version before the Task3 encrypted store is written; if Task3's store lands first, V4 ships a
preserving v1→v2 migration that keeps every turn, including `UNCERTAIN` and `EXPIRED_REPORTED`, byte-identical
in meaning, proven by restore tests. The range-coverage ledger and the per-device output setting are memory-only
and preferences respectively; neither is a durable billing record.

Ordering invariants: record before `start`; `finishing` before `finish`; server row before provider; ledger
prepare+link in one write before `submit`; TTS row (`preparing`) before the canonical read and `in_flight` before
the provider send; route decided before any speak request; invalidate counters before stopping adapters.

## 14. Compatibility and failure behaviour

| Peer | Result |
| --- | --- |
| New client, stock v0.2.0 server | Relay `NOT_OFFERED`; stock voice unchanged |
| New client, Relay server without voice | Voice methods absent; mic disabled in Relay conversations; typed Relay works |
| Old native client, voice-enabled server | Unknown method names ignored; no channel 5 traffic; nothing changes |
| Voice server, core without the seam (or without its display functions) | Methods unlisted; `unsupported` if called |
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
| 5 Exact final, voice policy, foreground only | §9 | V3 fixture: live author-only, other subscriber silent, recovered/O5 never live, voice off, client mode; V5/V6 foreground rules |
| 5a Deliberate playback (I1) | §9.1, §9.2, §9.6 | V1 display seam tests; V3 rig: bridge restart then Replay by `message_id` from a second enrolled device with `relay:read`; alias evicted → locate; deleted/hidden/revoked/tombstone outcomes; zero `live` calls for recovered content; existing-source analog after its regression; V5/V6 notification-tap flow; V8 owner observation |
| 5b Ranges and continuation (I2) | §9.4 | V3 rig + V4 JVM: concatenated completed ranges reproduce `text[0:speakable_end_cp]` for full and concise, over-long unbroken units (ASCII, CJK, emoji ZWJ/modifiers, regional indicators, combining marks), empty/whitespace, exact cap boundaries, `limited` sources, interrupted-range resume, provenance refusal, `exhausted`, source change `conflict`; concise ≤ 3 sentences |
| 5c Routing and walking controls (I3) | §9.7, §11 | V5/V6 JVM: `SpeechRoutePolicy` table, stop-first ordering (generation before stop, no frame after), no request without allowed route, deliberate resume; V7 native: real `PcmPlayback` focus loss, muted-until-verified start, routed-device observation, platform-permitted noisy/route events, accessibility checks; V8 owner-observed earbuds/speaker |
| 6 Paid accounting/crash/privacy | §10 | V1/V2/V3: provider call counts under crash injection, no retry, unknown states, temp cleanup, no-log assertions; Windows privacy check |
| 7 Lifecycle | §11 | V5/V6 controller tests per row; V7 native emulator fixtures; V8 owner-observed physical |
| 8 Entry points/fixtures | §12 | V1/V2 signature/version refusal tests; registered rig only |

Approved voice behaviours of `voice-integration-requirements.md`:

| Behaviour | Where met |
| --- | --- |
| 1 Hold/release one deliberate turn; slide/cancel, rings, ticks, waveforms, locks; Relay off = stock | §4, §5.2 C1, §11 |
| 2 No ordinary turn then resubmission; immutable text/id/expiry; receipt-first | §3, §8 |
| 3 Profile server STT and ElevenLabs/server voice; BYOK per profile; no platform replacement | §8.1, §9.3 |
| 4 Foreground initiating request/device only for automatic speech; no recovered/other-device automatic speech; deliberate Replay with fresh authority | §9.1, §9.5, §9.6, §11 |
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
| Provider SDK hidden retries (ElevenLabs, Edge) | The seam proves the SDK sends through the client it hooked BEFORE calling it and requires exactly one gated send AFTER; a bypass is `failed`/`unknown`. V1 round 1 ran the real lock-pinned SDKs (ElevenLabs 1.59.0, Edge 7.2.7) with a synthetic transport; stock counts recorded in the V1 report |
| Output fence cost on the hub loop (M4) | Full check per ≤ 200 ms batch under the lock; V3 thresholds; one pre-approved cached-snapshot fallback (§7.3) |
| Display seam cost on very large lineages | Locate ≤ 65,536 rows within the 2 s deadline, newest-first, identity scalars only; V1 measurement; `beyond_index`/`temporarily_unavailable` are truthful |
| Alias eviction (4,096 global rows) | Alias is only a verified hint; locate fallback re-creates it |
| Content revision between ranges | `source_sha256` pinning; `conflict` → deliberate Replay |
| Route class observation latency/platform limits | Muted-until-verified start; stop-first on any change; emulator cannot prove Bluetooth, so earbuds stay a V8 owner observation after V5–V7 controls |
| BYOK `endpointId` ↔ profile mapping | Assumption; V5/V6 test; `profile_changed` freezes BYOK use |
| Existing-source playback depends on the existing-history regression | Required; gated on its resolution and its own V3 test |
| Auto-submit withheld after flaky reconnect | Deliberate draft; UX cost accepted for safety |
| Transcript privacy window on server | 600 s TTL, early discard, no logs |
| Hourly budgets too tight/loose | POLICY, owner-visible, adjustable by review |
| Opus decode/encode depends on ffmpeg | Existing dependency; PCM fallback per direction |

No material owner decision is required to implement this design within the approved scope. Deliberate playback
across devices, history, notification taps and restarts, concise two-to-three-sentence speech with exact More,
and earbuds-preferred routing are approved behaviour and are specified here as required, not optional. A
model-summarized concise mode (alternative J) is not part of this design.
