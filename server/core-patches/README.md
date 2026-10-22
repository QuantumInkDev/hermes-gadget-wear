# Hermes final-delivery context hook

Base: Hermes Agent `e6f57427c198cfd96854522b7a9159eb007e13c2`.

The [patch](0001-feat-preserve-adapter-final-delivery-context.patch) adds generic adapter
final-delivery context hook version 2. It preserves accepted enrollment provenance through
atomic turn markers, queued/redirected terminal identity changes, canonical delivery rows
and recovery. Context-free adapters keep their existing behavior. The Gadget-specific
consumer is SDK patch 4 in the [SDK series](../README.md).

Apply only to a fresh isolated checkout at the pinned commit, checking with `git apply
--check` before `git apply`. This directory is separate from `server/patches`, whose
contents apply to the Gadget SDK. `tools/setup-extensions.py` applies only SDK patches;
it does not patch installed Hermes or enable a live capability.

Do not overwrite unrelated local core changes. The maintainer's runtime compatibility
fixture and its pre-existing modifications remain private; only the clean upstream delta
is exported here. Existing locally modified installations need their own verified patch
application and rollback preparation.

The reviewed implementation has 100 passing warning-strict actual-core checks per clean
and private-runtime fixture, including stock behavior, failed writes, real process restart,
queued/redirected identities and rotated enrollments. This is focused software evidence.
Broader existing core suites have disclosed unclosed SQLite warning debt and are not
claimed green. Fresh exported-patch checks and live rollout are tracked in project progress.

The hook does not rerun model/tool work. A context-bearing interrupted turn without a
canonical final stays held for explicit resolution. Old version-1 adapter contexts are
refused rather than reassigned to a current enrollment. No upstream PR, maintainer
agreement or live deployment is claimed.

# Native conversation voice seams (patch 0002, V1 core)

Runtime baseline: the maintainer's Hermes runtime core `7c17e6d72a3b9ec2974b3566f02dab1ed4b94eae`
(tree `680abe7642528e1b5d85a9bf1ba9f3d1d6f5c5a9`), **not** upstream `e6f57427…`. The
[patch](0002-feat-add-native-conversation-voice-seams.patch) is the complete `7c17e6d..HEAD`
delta of one core commit; it depends on everything already in that runtime baseline (the Relay
native session seams and their prior core changes), none of which this stage exports. Patch 0001
plus patch 0002 alone do **not** apply to a clean upstream `e6f57427`; the full clean-series
export and a clean-core compatibility check are a later stage. Apply only to a fresh isolated
checkout at the baseline commit, `git apply --check` first. It is generic, upstreamable source:
it imports nothing from the Gadget SDK and names no type of this repository.

What it adds, all inert unless a consumer calls it (no model tool, listener, auto-TTS change or
admission route; `NativeAdmissionOptions` stays TEXT-only; ordinary voice/STT/TTS is unchanged):

* `gateway.session_native_voice` (`NATIVE_VOICE_VERSION = 1`, also on `SessionAuthority`) with the
  frozen results `NativeTranscription(state, transcript, source, provider)`,
  `NativeSpeech(state, path, code_points, source, provider)`,
  `NativeDisplayLocation(identity, physical_id, role, created_at_ms, segment_count, size_bytes)` and
  `NativeDisplayToken(identity, physical_id, revision)`.
* `transcribe_native_once(path, *, authorize, before_send, deadline_s)` — the profile's configured
  STT, read at call time, sent at most once: explicit timeout (≤ 60 s and the deadline), zero SDK
  retries, redirects not followed, no transcode or OAuth-refresh retry after a provider answer,
  `authorize` before every send and after each await, `before_send` immediately before the transport
  send (a raise refuses it), one installed-and-cached local `faster-whisper` fallback after a configured
  failure (never an install, never a model download, never a user command, never another cloud provider).
* `synthesize_native_once(text, *, output_dir, authorize, before_send, deadline_s)` — the profile's
  configured voice, one request per core chunk, stop at the first failed chunk, audio only inside
  the caller-owned directory and streamed under a byte cap; a multi-chunk call is refused unsent when the
  join tool is missing. The 60 s request timeout is **new policy**, not a fact about the ElevenLabs client
  (lock-pinned 1.59.0 measured: no retry, 60 s client timeout, without it).
* `speakable_text(text)` — the reviewed spoken-text normalization of `text_to_speech_tool`, uncapped.
* `SessionAuthority.locate_native_display`, `read_native_display`, `check_native_display` and the
  existing-source analogs `locate_existing_display` / `read_existing_display` / `check_existing_display` —
  identity-addressed reads over the existing visible-row projection and `HistoryFragment` segments with
  `session:read` proved before and after, the existing 2 s deadline and progress handler, newest-first locate
  bounded at 65,536 rows and hydrating only the matched row. The check is scalar: it returns the
  `NativeDisplayToken` (physical row id and content-revision stamp) and reads no content; the consumer brackets
  the read with equal tokens (design §9.2.3). Both locate bounds end with `RuntimeStoreError('read_budget')` whose
  `field` is `locate_rows` / `locate_deadline`: only a completed search reports absence. The existing-source
  analogs also take `adapter` and `source`, as every existing-source reader does. The Relay SDK's 8,192/65,536
  response-bound regression for existing-source history is not addressed here.

Send accounting is fail-closed: a hooked SDK is proven to send through the client that carries the gate before it
is called and must produce exactly one gated send, else the result is `failed` with `provider='unknown'`; the
consumer's `authorize`/`before_send` are fenced on the owning loop against close, cancellation and the deadline.

**Stage coverage (explicit amendment).** This stage covers a prerequisite provider set: STT `openai`, `groq`,
`deepinfra` (pinned model), `xai`, `elevenlabs` and the cached local fallback; TTS `edge`, `elevenlabs`, `openai`,
`deepinfra` (pinned model). Every other configured provider — STT Mistral, user command (including the
`local_command` template) and plugin providers; TTS MiniMax, xAI, Gemini, Mistral, NeuTTS/Kitten/Piper, command and
plugin providers — is refused UNSENT (`unavailable`). They are the mandatory V1-P provider-parity gate of the plan,
which must pass before V2; V1 alone is not configured-provider acceptance.

Outcomes are stable tokens only: results carry no provider message, exception text, credential or
path except the audio file a successful synthesis wrote, and the stock provider helpers' log lines are suppressed
during a native call. `provider` is billing evidence about the configured remote provider (`not_started`,
`completed`, `failed_after_send`, `unknown`).

Focused evidence is in the stage report, not in this directory: warning-strict canonical-runner suites over the
real OpenAI SDK, the real lock-pinned ElevenLabs 1.59.0 and Edge 7.2.7 SDKs and `requests` with only the network
transport (for Edge, the socket) replaced, the real ffmpeg on local synthetic audio, the registered core owner for
display reads, and stock-off controls. No provider, SDK-in-production, native, live or physical acceptance is claimed.
