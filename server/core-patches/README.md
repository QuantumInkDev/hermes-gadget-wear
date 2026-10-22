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
delta of the series' core commits (the V1 seams, two corrections, the Mistral enablement and its option assertions, the REST TTS / xAI freshness enablement, the Task3 Phase1A native-once plugin route, bounded command runner and terminal STT refusals, and the Task3 Phase1B launcher-qualified local engines, command adapters and managed whisper.cpp, all below); it depends on everything already in that runtime baseline (the Relay
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
is called and must produce exactly one gated send, else the result is `failed` with `provider='unknown'` — on the
success path AND when the invocation, once entered, raises or answers a failure with zero gated sends (a configured
STT failure of that kind runs no local fallback); only failures before the invocation is entered stay
`unavailable`/`not_started`. The consumer's `authorize`/`before_send` are fenced on the owning loop against close, cancellation and the deadline.

**Edge bound.** A native Edge chunk is at most 600 code points (one gated handshake); a full or multi-chunk range
needs the usable ffmpeg joiner and is `unavailable`/`not_started` before any send without it. **Token scope.** The
display token proves membership, physical row and content revision, not universal visibility: extending it with
`display_kind` and the compressed-summary scalar is a mandatory prerequisite before any V3 consumer relies on it.

**Stage coverage (explicit amendment).** This stage covers a prerequisite provider set: STT `openai`, `groq`,
`deepinfra` (pinned model), `xai`, `elevenlabs`, `mistral` and the cached local fallback; TTS `edge`, `elevenlabs`,
`openai`, `deepinfra` (pinned model), `mistral`, `minimax`, `xai`, `gemini`. Every other configured provider — STT user
command providers not configured `native: local-sandboxed`, the env/PATH `local_command` templates and plugin providers
that did not opt in; TTS command providers not so configured and plugins that did not opt in — is refused UNSENT
(`unavailable`). Local execution (below) is refused unsent on every host that is not qualified. They are the remaining
mandatory V1-P provider-parity gates of the plan, which must all pass before V2; no category's source is
configured-provider acceptance by itself.

**Native-once v1 plugin route (V1-P Task3 Phase1A, source only).** A registered plugin STT/TTS provider is reachable
from a native call only when it overrides the non-abstract `transcribe_native_once_v1` / `synthesize_native_once_v1`
(`agent.native_once`), checked statically before any of its code runs. One scoped registry lookup, no plugin
discovery, and the held instance sends its one request through the seam's `NativeTransport` (an httpx client with the
send gate and the answer bound). The override is the plugin author's opt-in to trusted execution, not proof of one
send, no network or no download, and nothing contains hostile code: installed plugins are trusted code; the seam
accounts only the gated sends (a result without exactly one is `unknown`). Built-in > command > plugin precedence,
served-profile options and every stock path are unchanged; a configured custom provider is never replaced by another
or by the local fallback. A native STT call never runs plugin discovery (provider lookup or hook delivery); a profile
with an ordinary registered `pre_transcription` callback is refused UNSENT (`unavailable`) as an explicitly unsupported
native combination until a bounded hook contract exists. A recorded `closed`/`timeout` credential refusal is terminal
(`timeout`, or `cancelled` before the deadline; no fallback). Inside a native call only, the shared command runner is
bounded by the call's absolute deadline, Stop and per-stream/transcript-file caps, killing the same-session process
group (a `setsid` descendant is not reached); command providers themselves stay refused until an OS network-deny
launcher is admitted. The runner also now closes the child's pipes after every run (a stock file-object leak).

**Launcher-qualified local execution (V1-P Task3 Phase1B, source only).** Every native local child — the Piper,
KittenTTS and NeuTTS engines, a command provider configured `native: local-sandboxed`, and the managed whisper.cpp
`local_command` — runs only inside the OS network-deny launcher qualified for this exact host
(`tools/native_local_launcher.py`: Darwin 27.0.0 arm64, `/usr/bin/sandbox-exec` by sha256, profile
`(version 1) (allow default) (deny network*)`, from a controller host probe of a direct child and a `setsid` descendant).
The prefix is built from that table and the binary is re-hashed per use; every other host, architecture, release or
binary refuses unsent. This is not a hostile-code sandbox and the process-group kill does not reach detached
descendants. Engines run one new-session helper child per chunk (`tools/native_tts_engine_helper.py`): text on stdin
only, output descriptors pointed at null before any vendor import, one bounded JSON reply, `RLIMIT_FSIZE` and a live
WAV watch, and a complete-cache preflight before inference (Piper espeak/text/hebrew incl. Arabic Tashkeel; KittenTTS
through `try_to_load_from_cache` and the lower `KittenTTS_1_Onnx`, never the downloading wrapper; NeuTTS a local GGUF,
the stock Torch neucodec with its nested w2v-bert encoder files and the Perth resources, refused before any import on
Python 3.14). ffmpeg must be usable before any engine and converts WAV to MP3. Managed whisper.cpp runs only with both
pinned models verified by `_existing_dest_ok` (never the downloader). Local routes make no gated send (`not_started`); a
failure after a local invocation is `failed`. No real engine, model, cache, sandbox or audio acceptance is claimed.

**Disclosures that bind this stage (carried review findings).** (1) *Process-global telemetry (M1):* the pinned Mistral
SDK's own key-carrying trace exporter is switched off for a native call, but a process-global OpenTelemetry tracer
provider (one that a plugin installs, for instance) is not touched: it can still record request URLs and provider error
messages as spans and inject trace headers into native Mistral requests. This residual is open until the current-profile
integration gate inspects the enabled plugins and the actual tracer-provider type; no privacy acceptance is claimed.
(2) *Telemetry proof (M2):* the `config.telemetry = False` read-back proves nothing (the pinned configuration object reads
back whatever was assigned); the proof is the SDK's own `getattr` of the switch plus the behavioural exporter test, which
must run on any SDK change. (3) *Stock `stt.prompt` (M9):* the pinned SDK's transcription endpoint has no `prompt`
parameter, so the ordinary (non-native) Mistral STT path, which forwards a configured `stt.prompt`, fails with an error
result; this stock defect is not changed here, and a native call refuses such a profile UNSENT. (4) *Bound scope (M9):* the
1 MiB `MAX_STT_RESPONSE_BYTES` answer bound is enforced by the Mistral STT hook only; the OpenAI-SDK and REST STT answers are
not bounded by it, which belongs to their own integration boundaries. (5) *Allocation (M5):* the Mistral wire bound is checked
per decoded chunk, so it is not an allocation-hard cap (a compressed answer can transiently exceed it by one chunk's
inflation); native Mistral and REST requests therefore ask for `Accept-Encoding: identity`. (6) *Logs (M3):* the native
quiet filter now also names the credential-pool and Mistral SDK hook loggers, so their provider-controlled text (an OAuth
refresh failure body, the deprecation date) is not logged during a native call. A dropped record is dropped for every
handler: during a native call it is available in no local log either. Ordinary calls keep logging exactly as before.

**Mistral (V1-P category 1).** Configured Mistral STT and TTS run through the stock pipeline on the real pinned
`mistralai` 2.4.8 SDK. The policy builds the SDK client itself — explicit timeout (≤ 60 s and the deadline), redirects
not followed, the request hook on the very `httpx` client the SDK sends through (proven before the invocation),
`retries=None` on every call, no SDK debug logger and the SDK's opt-in trace exporter switched off (a second outbound
path that carries the key) — and inspects the installed endpoint signatures first: a field the SDK cannot carry is
refused UNSENT. The pinned STT endpoint has **no `prompt` parameter**, so a configured `stt.prompt` makes a native Mistral
STT call `unavailable`/`not_started` rather than being dropped or discovered as a failure after the invocation (the
ordinary, non-native path still forwards it). The provider's answer is bounded as it is read — 1 MiB for STT, the
base64 envelope of the 16 MiB chunk cap for TTS — whether or not a length is declared; a native call installs
nothing. Accounting uses the existing entry rules, with the documented trade-off: a failure after the invocation
was entered with no gated send is `failed`/`unknown` and runs **no** STT fallback — also when the true failure was
local (an unbuildable request, for instance), because the seam cannot tell it from a send it never saw. Failures
before entry (no key, no SDK, an SDK that cannot be told "no retry", an unproven client) stay `unavailable`/`not_started`.

**REST TTS — MiniMax, xAI, Gemini (V1-P Task 2).** These three have no SDK: they send through the one stock helper
`tts_tool_providers._post_json` (`requests.post`, streamed), which is therefore the actual HTTP boundary. Inside a native
call the consumer's gate runs immediately before that `requests.post` (the same adjacency the REST STT multipart helper
already has), the timeout is the smaller of 60 s and the call's bound, redirects are not followed (a 3xx is a counted,
unusable answer, never a second POST) and the body is requested uncompressed. The answer is read in 64 KiB reads and refused
beyond the 16 MiB chunk cap (a declared length over the cap is refused before the body is read; for the JSON-wrapped
MiniMax/Gemini answers the cap applies to the whole body, which is stricter than the audio). This is chunk-granular, not an
allocation-hard cap. The stock generators run unchanged: voice, model, language, persona, explicit tags, chunking and
request options are the profile's. A failure before the gate (no key, a bad region, a composed Gemini prompt over the
provider limit) is `unavailable`/`not_started`; once the request passed the gate, a failure (HTTP 4xx/5xx, timeout, redirect,
truncated, malformed, empty or oversize answer, a conversion error after a paid answer) is `failed_after_send` with exactly
one send; the first failed chunk stops the call and every file is removed. The REST multi-chunk tests join the chunk files with a byte-wise stand-in: the real per-chunk Gemini PCM-to-mp3
conversion is proved, the real ffmpeg join of REST chunks is not (it belongs to the Edge/joiner gate). "Zero hidden retries" at the
HTTP-adapter level rests on the `requests` library default (`max_retries=0`), because the test transport replaces the adapter;
redirects and second sends in one session are tested behaviourally. An answer that is empty audio keeps the existing
`failed`/`completed` accounting. There is no hidden retry, no second request and no other provider.

**Auxiliary speech tags are omitted in a native call (accepted cost).** The optional xAI `auto_speech_tags` and Gemini
`audio_tags` rewrites ask an auxiliary language model (`agent.auxiliary_client.call_llm`, task `tts_audio_tags`), a second,
ungated paid request on another provider. A native call makes **zero** such calls and uses the stock fallback script: xAI
keeps its local `[pause]` pass (explicit tags are untouched), Gemini sends the unchanged transcript. Saved profile settings
are not altered and an ordinary (non-native) call still rewrites exactly as before. The spoken script and the voice
performance of a profile with those options are therefore **not** identical to the ordinary script.

**xAI OAuth pre-send freshness (V1-P, M2).** The stock resolver and credential pool already refresh an expiring or expired
OAuth token before returning it and do not return one whose refresh failed (verified with the real resolver, pool and
token store over a synthetic OAuth transport). What they cannot vouch for is a bearer with no decodable JWT `exp`; stock STT
covers that by refreshing after a 401 and resending, which a native call must never do. A native xAI call therefore refuses
UNSENT (`unavailable`/`not_started`; STT may still use only the cached local fallback) unless an OAuth bearer carries an
`exp` later than the request window, so an **opaque OAuth bearer is refused** in a native call (conservative; an explicit
`XAI_API_KEY` has no expiry and is unaffected, and ordinary calls are unchanged). The OAuth exchange is not a paid attempt: it
never reaches the consumer's `before_send` or the provider accounting. It has its own **authorization-only credential
phase**: immediately before EACH actual OAuth discovery and refresh HTTP request (an `httpx` request hook on the clients the
stock auth module builds in a native call, so no request can follow an unchecked one) the worker asks the consumer's
`authorize` on the consumer's own event loop, under the same close/abandon/deadline fence as the paid gate; the worker never
runs the callback itself and a callback still queued after the call returned or was stopped does nothing. The phase never calls
`before_send`, never commits and never counts a send. A refusal, Stop or deadline stops the next OAuth request and every audio
send, is reported as the existing unsent vocabulary (`refused`/`cancelled`/`timeout`/`unavailable`, `not_started`) and never
triggers the STT fallback; it does not bench the account (the stop is a `BaseException` that the stock pool and resolver do
not swallow, converted to an unsent refusal at the resolver boundary). The refusal is **terminal for the call**: it closes the
send policy (keeping the first truthful reason), so it holds even where a stock caller swallows the converted exception — the
STT auto-detect (no explicit `stt.provider`) probes xAI through a quiet availability function that absorbs exceptions, and
before this rule it could fall through to another cloud provider; now a refusal recorded during provider selection ends the
attempt unsent, nothing else is probed, transcribed or sent, and the consumer is not asked again even if it would authorize
(regressions run the real stock selection, resolver, pool and token store with a usable second-provider key; a mid-call
Stop was already safe). The same rule means no STT fallback follows a credential refusal. OAuth request timeouts are bounded by the call. A POST
that was **already admitted** may finish after Stop or a lost authority and persist its rotated refresh token (the stock
code requires that and the token is not discarded); this is not remote cancellation, and no further request or audio send
starts afterwards. Nothing is refreshed or resent after a 401/403, no other account is retried and no other provider is used.

Outcomes are stable tokens only: results carry no provider message, exception text, credential or
path except the audio file a successful synthesis wrote, and the stock provider helpers' log lines are suppressed
during a native call. `provider` is billing evidence about the configured remote provider (`not_started`,
`completed`, `failed_after_send`, `unknown`).

Focused evidence is in the stage reports, not in this directory: warning-strict canonical-runner suites over the
real OpenAI SDK, the real lock-pinned ElevenLabs 1.59.0, Edge 7.2.7 and Mistral 2.4.8 SDKs, `requests` (adapter replaced), the real
xAI resolver, credential pool and token store (OAuth HTTP transport replaced) with only the network transport (for Edge, the socket) replaced, the real ffmpeg on local synthetic audio, the registered core
owner for display reads, and stock-off controls. No provider, SDK-in-production, native, live or physical acceptance
is claimed.
