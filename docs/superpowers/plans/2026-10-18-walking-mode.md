# Walking mode implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> with isolated owners and fresh independent task reviews.

**Goal:** Provide deliberate walking mode on phone/watch with brief speech, readable full
answers, preferred earbud output and accessible Record/Replay/Stop/Tell me more controls.

**Architecture:** Extend the accepted current-playback interruption foundation. Optional
server presentation policy shortens only the spoken script, independently of canonical
text/history/outbox. A bounded volatile audio replay belongs to one current reply and
uses its received voice bytes without another synthesis. Native controls retain existing
profile/connection ownership and never create an unattended microphone or reconnect action.

**Tech Stack:** Existing Python SDK/core TTS seams, pure Kotlin/JVM state and codecs,
shared AudioTrack owner, encrypted native preferences and Compose; no new service.

**Spec:** Approved `docs/superpowers/specs/2026-10-18-relay-expansion-design.md` N5;
foundation `docs/superpowers/plans/2026-10-18-walking-audio-foundation.md`.

## Global constraints

- Opt-in walking mode; no always-listening/wake-word/background microphone, new permission,
  foreground-service workaround, Bluetooth pairing or device power/volume setting change.
- Keep hold/release/cancel behavior, recording cues and watch start/end haptics. The portrait
  remains a hold-to-talk target with outward rings; never overlay a microphone/menu on it.
- Preserve full textual answers, canonical history, outbox/delivery identity and ordinary
  stock v0.2.0 behavior. Recovered events never speak, replay, approve or send automatically.
- Server profile voice remains default. Phone BYOK remains opt-in and uses the same selected
  profile voice. No additional model call or paid synthesis for shortening or cached Replay.
- Prefer available earbuds for walking playback, otherwise speaker; observe actual output.
  An observed noisy/focus-loss event stops the current playback and does not resume it.
- Preserve independent endpoints/enrollments/art/GIFs, three themes, native fonts/touch
  accessibility, API29 phone/API33 Wear floors and API37 target. Protocol stays Android-free.
- Workers use synthetic source-bound fixtures only. No actual provider/device/host/service/
  power operation, private artwork, public export, push, merge or release.

## Review focus

1. Brief speech must work for server whole-file and streaming paths as well as client BYOK;
   shortening only `tts.speak` cannot satisfy a server-voice user.
2. Late audio/focus/route callbacks and profile/thread replacement cannot replay or stop
   another reply, overwrite a draft or send Tell me more to another conversation.
3. Byte/time overflow, interrupted or malformed audio disables Replay for that clip without
   degrading the live answer or losing its full text; no partial clip is labeled complete.
4. Missing/unavailable earbuds and real removal differ from a preference/enumeration;
   no automatic continuation onto a speaker after a noisy interruption.
5. Restored state, stock peers and background restrictions remain honest and usable without
   sending, recording, changing system audio mode or claiming unsupported brief speech.

## Actual source and dependency findings

Accepted root foundation is `dc1fb14`. `PcmPlayback` has `onOutputChanged(PlaybackOutput)`
and production owner-fenced interruption/terminal handling. Its routing callback is not
yet wired to either client's visible state. `PhoneConversation` and `GadgetService` own
their own capture/playback/session generations; preserve those rather than introduce a
second audio owner. Phone `playbackFactory` currently takes four callback arguments.
Maintain source compatibility for existing factory fixtures when adding optional controls.

SDK `DeviceSession.speak_reply` emits client `tts.speak`; it does not synthesize ordinary
server voice. Core `BasePlatformAdapter._synthesize_auto_tts(text_content, source)` owns
the whole-file provider path. `GadgetAdapter.supports_streaming_tts(chat_id, format)` is
already per chat; the real `StreamingTTSConsumer` consults it before streaming voice.
An adapter override can select brief final synthesis for walking turns without globally
changing core speech behavior. Verify the full actual call path before committing this
choice. Never truncate an already playing audio stream to imitate brief speech.

`DirectConnection.updateSensors(JsonObject)` serializes the existing paired `state` frame;
SDK `_h_state` merges its `sensors`. Add a bounded optional walking presentation value
there, gated by explicit server welcome support, rather than create another socket/RPC
stack. Current source inventory is frozen privately; freeze the latest accepted combined
SDK/core before Task1 dispatch, never a concurrent worker's WIP.

Task2 consumes accepted Task1 wire behavior and the accepted N10 mutation/recovery seam
before adding persistent settings. Bind any available N1 conversation/receipt identity
from its accepted API; if that consumer is not yet integrated, preserve current stock
conversation generation and explicitly carry the cross-device invalidation requirement
to controller integration. Do not claim full N5 across an untested N1 combination.

## File map and interfaces

SDK/core paths are relative to their isolated clones; Kotlin uses existing module packages.

| File | Responsibility |
| --- | --- |
| SDK `plugin/walking.py` | Pure bounded spoken-script selection and per-turn presentation snapshots |
| SDK `plugin/hub.py`, `adapter.py` | Optional welcome/state support, actual client/server speech selection, streaming gate |
| SDK `tests/test_walking*.py` | Actual session/core speech-path, stock/default and concurrency controls |
| `protocol/.../WalkingMode.kt`, `DirectConnection.kt` | Pure mode/output-preference enum/negotiation state and ordered existing-state publication |
| `client/.../ReplyAudioCache.kt`, `WalkingPlayback.kt` | Bounded current-reply replay ownership and typed output preference |
| `client/.../PcmAudio.kt`, `PlaybackOutput.kt` | Preferred available output on the existing AudioTrack; actual route observation |
| `mobile/.../WalkingPreferences.kt`, `ConversationPage.kt`, `PhoneConversation.kt` | Explicit mode/settings, larger native controls and current phone state |
| `wear/.../WalkingPreferences.kt`, `WatchScreen.kt`, `GadgetService.kt` | Explicit walking view, portrait recording and current watch state |
| Existing speech/native fixture files plus focused walking tests | Production PCM/Opus/replay/route/lifecycle and accessible layouts |

### Task 1: Brief spoken script on actual server and client speech paths

**Consumes:** Frozen accepted SDK/core speech, current authenticated DeviceSession and
existing state/publication hooks. **Produces:** `walking_spoken_text(text: str) -> str`,
immutable per-turn walking presentation snapshots and optional welcome
`walking: {version:1}`. A supported client publishes
`state.sensors.relay_walking_mode: boolean` through its existing connection queue.
The report freezes the exact SDK snapshot/lifetime API and all wire/error semantics.

- [ ] Write focused failing tests for full text plus brief server voice, client BYOK
  request text, ordinary unshortened controls, two simultaneous independent sessions,
  missing/disabled support and a preference change during an active/queued utterance.
  Use actual Hub/adapter/core synthesis/streaming selection, fake only model/provider
  leaf output. Retain true RED, exact source/import binding and commands.
- [ ] Add default-OFF `enable_walking_mode`. Validate exact boolean/version support and
  current pairing/enrollment before honoring the optional state value. A stock or disabled
  server ignores it and preserves current speech. Unpaired/stale-key messages cannot set
  it. Do not derive wake/history permissions or mutate unrelated sensor values/grants.
- [ ] Freeze presentation at the owning turn's speech decision; later mode changes apply
  to subsequent turns and cannot restart synthesis. Avoid global mutable chat/profile
  context. Bound and clear per-turn/session snapshots on normal completion, cancellation,
  disconnect and key/profile replacement. Shared recipients retain their own presentation.
- [ ] Select up to three complete spoken sentences from the normalized final answer, at
  most1200 Unicode code points; if a single sentence exceeds the cap, use a safe word/code
  point boundary and explicit brief-reading metadata. Do not call another model, invent
  facts, alter displayed text or include reasoning/code markup. Unreadable/code-only output
  gets an honest short instruction to read the answer. Cover Unicode/CJK punctuation,
  abbreviations/URLs/decimals, unpunctuated text, Markdown/code and empty output.
- [ ] Apply the same selected script to client `tts.speak` and actual ordinary server
  synthesis. Gate streaming off only for a walking utterance that will receive the brief
  final script; leave ordinary streaming untouched. Reuse current profile provider/voice
  and PCM/Opus behavior. Preserve original full-text TTS identity/dedup and no-speech recovery.
- [ ] Test actual core whole-file fallback and streaming-consumer decisions, synthesized
  input/voice, two-device shared delivery, cancel during provider work, repeated final,
  lost socket and silent outbox recovery. Any missing real shared speech path is a source
  dependency to implement/test, not permission to advertise client-only support.
- [ ] Run covering strict SDK/core/default tests on both supported foundations with no
  retries, preserve failures and dependency provenance, commit upstreamable deltas and
  complete net review packages. Fresh combined review before native consumption/activation.

### Task 2: Native walking controls, exact-voice Replay and preferred output

**Consumes:** Reviewed Task1 wire contract, accepted current playback owner, actual native
preferences/recovery and conversation APIs. **Produces:** pure
`WalkingModeState(enabled, speechSupported, outputPreference)`,
`WalkingOutputPreference { SYSTEM, HEADPHONES_FIRST, SPEAKER }` and
`ReplyAudioCache` with explicit `begin/offer/complete/discard/read` operations bound to an
immutable current profile/enrollment/connection/conversation/reply key. Freeze exact types
and caller contracts in the report; existing default constructors remain compatible.

- [ ] Write failing pure tests for stale reply ownership, profile/epoch replacement,
  interrupted/oversize replay, alias-safe byte ownership, duplicate actions and unknown
  welcome support. Write owned native production-playback failures before editing audio/UI.
- [ ] Add explicit mode/output preferences through actual encrypted store APIs and the
  accepted mutation/recovery gate. Default mode OFF; walking output HEADPHONES_FIRST.
  Recreation may restore appearance/preferences but never microphone/replay/send. Keep
  these device-local audio choices excluded and named in migration exclusions; do not
  append unknown fields to strict v1 or migrate pending operations/device audio identities.
- [ ] Publish the optional mode on the existing paired queue before each newly initiated
  walking turn and after an explicit mode change. Unsupported peers retain larger controls
  and normal audio; show brief speech unavailable honestly. A mode toggle cannot cancel a
  request, reconnect, pair, synthesize, record or speak by itself.
- [ ] Wire actual output callbacks into both clients with current-owner fences. Request a
  preferred available device on the existing AudioTrack: connected headphones first,
  otherwise speaker; SYSTEM leaves OS choice, SPEAKER deliberately requests built-in
  speaker. Respect API availability, maintain USAGE_MEDIA/CONTENT_TYPE_SPEECH, and never
  use SCO/communication mode, device addresses/names, Bluetooth permission or global volume.
  A rejected preference is not a routed-device success. Noisy/focus loss stops current
  playback; only explicit Replay can start another clip after interruption.
- [ ] Keep at most one completed reply clip/device, volatile only, at most4MiB encoded
  data,8192 packets,4096-byte header and120seconds of decoded samples at its declared
  rate; compressed byte size or wall time is not decoded duration. Tap Replay uses
  the exact cached PCM/Opus/voice, never calls provider/server or sends another agent turn.
  Capture only a complete current live stream. Interrupted, malformed, over-limit or
  superseded clips are unavailable with a readable reason; live playback/full text survive.
  Clear on profile/identity/conversation replacement, explicit disconnect/disposal and
  five minutes after completion. Phone leaving Talk stops/clears as before. Watch ambient
  retains an already requested live turn under its existing bounded foreground lease;
  it must not cancel that answer or clear its active cache merely because the wrist lowers.
  No automatic replay, new microphone or lease extension follows. Recovered history starts
  without a clip. Do not store audio in history, migration, logs or notification data.
- [ ] Provide explicit walking views on phone/watch with large Record/Replay/Stop/Tell me
  more controls, current profile/route and readable answer state. Keep ordinary portrait
  pages' current sizing/position, artwork and swipe navigation. Dedicated walking layout
  may make room for controls; the portrait remains unobstructed and hold-to-talk, with
  recording rings/haptics. Place menus below content. Full answer remains reachable.
- [ ] Stop cancels current playback/preparation idempotently without canceling the agent
  turn. Tell me more is a visible deliberate follow-up on the same current conversation,
  never a paid replay. Freeze the selected answer/recipient and allow only when it remains
  the latest complete answer and no recording/prompt/request is active; invalidate on
  another device's new event or binding/epoch change. Use an ordinary confirmed turn with
  a readable follow-up preview; no hidden whole-history or arbitrary tool action. Preserve
  draft/scroll and disable stale pending taps instead of redirecting them.
- [ ] Test real PCM and supported Opus replay, exact received bytes/voice, completion before
  late callbacks, cache boundary/time/header errors, noisy/focus/Stop races, profile switch,
  process death, stock peer and supported server. Exercise three themes/large fonts on
  API29/API37 phone and API33/modern Wear using only owned preserving fixtures. Verify
  current actual route separately from injected preferences; do not claim Bluetooth hardware.
- [ ] Run affected strict JVM/lint/format/app+test APK checks, preserve all original fixture
  APK/data/settings/signers before each install and exact restoration. No quarantine or
  physical device use. Commit/report source/evidence hashes, exact counts/failures and
  playable UI captures. Fresh combined native review before root/device integration.

### Task 3: Controller integration and actual walking acceptance

- [ ] Integrate reviewed source onto current accepted combined foundations; source parity,
  stock speech and N1 cross-device reply invalidation must pass. Complete reviewed live
  rollout prerequisites, preserving profiles/keys/art/flags/config and no power helper.
- [ ] Install preserving updates on authorized phone/watch. Verify their actual speakers
  and connected earbuds, initial fallback, removal/noisy response, call/focus interruption,
  foreground restrictions and explicit Replay after interruption. Record actual routes;
  enumerate/prefer/synthetic broadcast alone does not prove earbud acceptance.
- [ ] Use short approved live prompts on the configured profiles to compare full text,
  brief spoken script and each original server/optional client voice. Cached Replay must
  use no additional synthesis; respect the remaining bounded ElevenLabs test allowance.
  Verify record cues/haptics, Tell me more recipient and away-network behavior with owner.
- [ ] Measure the requested physical latency/payload/power comparison under documented
  conditions; label device/radio/codec/route differences. Do not substitute emulator
  allocation or a synthetic silent tone for walking battery/speech acceptance. Keep N5 and
  the whole goal open until all required source/live/device gates are proved.

## Preflight rulings

Ruling: walking brief speech is an extractive spoken reading, with full text retained,
not another model request. Why: bounded predictable speech without extra model cost or
invented summary facts. Cost if wrong: refine the unshipped script selector with fixtures;
never shorten canonical history or truncate live audio.

Ruling: use per-chat streaming eligibility and the actual final-synthesis path before
claiming server-voice support. Why: changing only the BYOK event leaves ordinary voice
users hearing the full response. Cost if wrong: a narrow public core hook may be required;
freeze/review that seam before native consumption, never silently omit server voices.

Ruling: Replay is volatile exact received audio; Tell me more is an explicit reviewed
follow-up. Why: repetition should not incur hidden paid synthesis or silently redirect
a stale answer. Cost if wrong: some old/long/interrupted answers have no replay; full
text remains and the UI says why. Actual N1 invalidation remains an integration gate.
