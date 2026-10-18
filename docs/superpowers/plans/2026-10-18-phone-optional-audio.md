# Phone optional Opus and client speech

Status: focused execution plan under approved O3/O4. No new provider call or runtime
acceptance is established by this document. Server defaults and the current hold/release/
slide-left gesture remain the normal experience.

## Global Constraints

- Preserve stock Gadget v0.2.0 and the Android-free protocol module.
- Preserve separate phone/watch enrollments, current themes, artwork, drafts and controls.
- Server speech is the default. Existing per-endpoint shared/profile choices are opt-in.
- Read secrets only from the existing Keystore-backed TtsVault; never send keys to Hermes,
  log keys/text, export them, include them in screenshots or mirror them to the watch.
- Do not replay or automatically retry paid synthesis, recordings, sends or approvals.
- Stop/hide/profile replacement invalidates every pending synthesis and audio callback.
- Synthetic fixtures only in public tests. Workers make no real provider/device/server
  calls. Controller owns the remaining bounded synthesis allowance and live rollout.
- No gateway v2, power changes, signing changes, new subscription or public release.

## Verified seams and references

PhoneConversation currently advertises PCM only and ignores ClientSpeak. PcmCapture
already supports negotiated Opus, codec headers and raw-level callbacks; PcmPlayback
accepts an Opus header and renders real decoded levels. The watch uses these adapters.
Protocol negotiation already treats microphone/speaker formats independently and gates
client TTS on the server's explicit welcome acknowledgement.

TtsVault already stores server/shared/profile choices and voice overrides under an endpoint
digest. ElevenLabsApi already has fixed HTTPS origin, redirect/retry denial, status mapping,
75-second call limit, 3,000-frame bound and streaming PCM/Ogg parsing. RelayService uses
its framed stream for associated-watch synthesis. Reuse this implementation rather than
adding another HTTP client or changing watch-owned TLS forwarding.

Reviewed primary references on 2026-10-18:
[Android supported formats](https://developer.android.com/media/platform/supported-formats),
[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), and
[ElevenLabs streaming speech](https://elevenlabs.io/docs/api-reference/text-to-speech/stream).
Device codec support is established by a local codec probe, not OS-version assumptions.

## Task 1: Foreground phone audio integration

Owned files: focused PhoneConversation changes; a small PhoneClientSpeech engine;
ElevenLabsApi callback sink/wrapper if required; affected native/unit audio tests. Reuse
existing protocol/client codecs. Avoid wear/GadgetService, MainActivity/navigation and
unrelated presentation changes. No live key/settings changes.

- [ ] Add failing tests for phone capabilities and negotiated capture. Off-main preparation
  probes the existing codec implementation and reads the endpoint's selected speech settings.
  Publish only for the still-current visible connection request. A late result after hide,
  profile replacement or another connect cannot open a socket or change its capabilities.
  Preserve the existing explicit Connect/enrollment boundary; no UI entry auto-connects.
- [ ] Advertise Opus only after a successful runtime probe; retain pcm16 in both lists.
  Each direction still follows the server-selected format independently. Wire CaptureStart
  format, codecHeader, capturedOpus and raw captureLevel to the existing validated token.
  Keep the real waveform and recording-start/finish semantics. Probe failure uses PCM
  without losing a recording or retrying an already submitted turn.
- [ ] Offer client TTS only for an explicit non-SERVER endpoint setting with a nonempty
  readable key. Vault failure preserves bytes and uses normal server speech with a concise
  notice; never silently change the stored mode. No provider call occurs during connect.
  If the stock server does not acknowledge client TTS, normal server audio remains usable.
- [ ] Implement one bounded, cancellable foreground PhoneClientSpeech owner around the
  existing ElevenLabsApi. Prefer a typed header/packet/end sink with the current framed
  OutputStream wrapper preserved for watch channels. No unbounded pipe, second synthesis
  implementation, raw response logging or automatic format/provider retry. Keep current
  parsing, byte/frame/time limits and fixed-origin checks on both consumers.
- [ ] Handle only validated ClientSpeak effects for the current socket/profile/turn.
  Resolve current selected settings immediately before a deliberate live synthesis;
  SERVER/deleted key now denies that call with readable failure rather than using a stale
  key. Use override voice or the validated server-provided voice as existing API specifies.
  Dedup effects before API invocation. A stale callback cannot start audio for another
  session; Stop, capture start, new reply, hide and teardown cancel in-flight HTTP/work.
- [ ] Route synthesized audio through the same PcmPlayback owner and real waveform as
  server speech. Track preparing versus speaking honestly; completion waits for native
  playback drain. Stop during pending synthesis must work. Preserve audio focus/muted hint,
  user draft and complete textual answer on provider error/quota/cancellation. Never fall
  back to a second paid call or automatically ask Hermes to synthesize again.
- [ ] Use synthetic local TLS fixtures for SERVER/no-ack, shared/profile voice precedence,
  missing/corrupt key, 401/403/429, redirects, oversized/empty/malformed PCM/Ogg, cancellation
  before/during response and delayed old-profile callbacks. Prove one request per accepted
  ClientSpeak and no call from reconnect/previousReply. Existing watch framed API tests
  remain green; do not claim a stubbed native sink proves actual sound.
- [ ] Run actual owned-emulator MediaCodec encode/decode with synthetic PCM and the real
  PhoneConversation capture/playback adapters where supported. Capability absence is
  reported and PCM fallback tested; it is not an Opus pass. Use device_list/device_open,
  no other worker's AVD or physical microphone. Preserve source/APK-bound receipts.
- [ ] Run affected native tests and strict mobile APK/instrumentation builds; serialize
  ktlint after compilation. Commit exact owned paths, no push. Full report with genuine
  RED/GREEN, commands/counts/skips and limits to the task report. Independent review gates
  integration; physical speech/STT, provider playback and battery comparison remain open.

## Task 2: Profile-aware speech settings and all-profile capacity

Depends on reviewed Task 1. Own focused SpeechSettingsPage/TtsVault, phone profile/detail
entry, labels/navigation and native tests. Coordinate MainActivity ownership after quick
actions; do not overlap diagnostics/navigation work.

- [ ] Offer Speech providers for phone and watch, with a selected profile entry opening
  that exact endpoint's settings. Show readable profile/voice-mode context without entering
  a URL again. Retain explicit reviewed manual-endpoint settings for existing users.
  Changing settings never connects/pairs/sends/synthesizes. Settings are inaccessible from
  a stale mounted profile or locked active turn without preserving its state.
- [ ] Preserve existing sealed filenames/AAD/key alias and choices. Raise bounded endpoint
  capacity from 16 to 64 so the approved multi-profile catalog is not partly excluded.
  Enumerate exact valid sealed names, reject capacity before a new write and keep all old
  records. Existing corrupt/missing-key behavior remains non-destructive. No auto-pruning
  offline profiles or implicit new mode on saving a shared key.
- [ ] Keep save/delete distinct, mask key entry, never display saved keys, and guard delayed
  load/save/quota results with endpoint/generation. Publish only nonsensitive readiness/mode
  to associated watches through the existing channel. Local phone settings changes apply
  only to later deliberate requests. Use explanatory cost/voice text without new purchase.
- [ ] Verify all three themes, narrow/large font, IME-safe controls, exact profile selection,
  independent shared/profile/server choices, 64-entry boundary, corruption preservation,
  late navigation and process recreation. All tests use synthetic credentials and local
  TLS fixtures. A quota check is explicit and never itself synthesizes.
- [ ] Strict affected checks and APKs, synthetic source-bound UI captures, independent
  review, then controller-preserving physical install and bounded live checks. Keep the
  owner-only subjective voice/routing/recording checks explicit rather than marked passed.

## Preflight rulings

| Producer / consumer | Resolution |
| --- | --- |
| API framed stream / phone native sink | One provider request/decoder implementation with an adapter wrapper preserves the existing watch contract. |
| Connect / blocking codec and vault reads | Off-main preparation has a connection-generation lease; opening Talk alone does no network work. |
| Token capture / compressed packets and waveform | Negotiated bytes use capturedOpus; original PCM uses captureLevel, never compressed-packet amplitude. |
| Provider failure / cost and text retention | Keep full answer and explicit error; no automatic paid retry or repeated synthesis after reconnect. |
| Existing vault capacity / multi-profile catalog | Bounded 64 entries with unchanged encryption format; no offline record garbage collection. |

This plan completes the missing phone software path. O3/O4 still require reviewed live
server flags, real watch/phone/relay playback, same-phrase comparison and physical evidence.
