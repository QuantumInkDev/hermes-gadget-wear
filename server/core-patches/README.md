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
  the caller-owned directory and streamed under a byte cap; a multi-chunk call proves a real concat under the qualified launcher
  before its first chunk and is refused unsent when that join is missing, broken or unqualified (Task4, below). The 60 s request timeout is **new policy**, not a fact about the ElevenLabs client
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
needs the proved, launcher-wrapped ffmpeg joiner (Task4 below) and is `unavailable`/`not_started` before any handshake without it; a text of 600 code points or fewer is one chunk, needs no joiner and is never truncated. **Token scope.** The
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
group (a `setsid` descendant is not reached). At this Phase1A stage command providers stayed refused; Phase1B (below)
admits them only inside the qualified launcher and only when configured `native: local-sandboxed`. The runner also now
closes the child's pipes after every run (a stock file-object leak).

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
Python 3.14). Managed whisper.cpp runs only with both pinned models verified by `_existing_dest_ok` (never the
downloader). Local routes make no gated send (`not_started`). No real engine, model, cache, sandbox or audio acceptance
is claimed.

**Task3 review fix1 (source only).** The per-call policy records an actual successful local spawn (a prerequisite probe
or a failed spawn is not one): any native local failure after it — a stream or transcript cap, a symlinked or directory
transcript, an unreadable result — is `failed`/`not_started`, never `unavailable`; explicit Stop/deadline/refusal states
are kept. The helper runs as an isolated interpreter (`python -I`, explicit minimal child env, exact request fields)
and, before any preflight or vendor import, requires every module the engine imports at load and each source-backed
distribution at its exact supported version from installed metadata (Piper 1.8.0, KittenTTS 0.8.1, NeuTTS 1.4.1,
huggingface-hub 1.24.0, transformers 5.17.0, neucodec 0.0.4, misaki 0.9.4, phonemizer-fork 3.3.2, espeakng-loader 0.2.4,
num2words 0.5.14; an installed resemble-perth must be 1.0.1): missing is `not_installed`, another version
`unsupported_version`. (Those global rows are the 3.14 lock / KittenTTS set; NeuTTS 1.4.1 validates against its own
engine-scoped rows - see *NeuTTS dependency compatibility* below.) Before any engine, a tiny synthetic WAV→MP3 conversion must succeed
under the same launcher (a missing or broken ffmpeg refuses unsent); each chunk's conversion is the same wrapped,
Stop-aware, WAV-forced, file-only child bounded by the exact remaining deadline. A multi-chunk join was still the shared
stock joiner at this stage (closed by Task4, below). A NeuTTS language is passed only for a local GGUF. The pre-existing faster-whisper local path is
unchanged: cached-only by source (`local_files_only`), not launcher-wrapped and not network-denial proof.

**Native response/audio pipeline (V1-P Task4 + fix round 1, source only).** *Response bounds.* Every native STT answer, success or
error, is read under the seam's 1 MiB cap BEFORE the SDK or a JSON layer buffers it: OpenAI/Groq/DeepInfra through a response
hook on the injected httpx client (it runs before httpx's eager read), xAI/ElevenLabs through a streamed `requests` call
read from the raw urllib3 body 64 KiB at a time, undecoded, in a Requests RESPONSE HOOK: Requests dispatches it before its own
redirect step, which would otherwise read and decode a 3xx-with-`Location` body whole even with `allow_redirects=False`. An advertised `Content-Length` over the cap is refused with zero
body reads; an absent or false length stops at the crossing; the response is closed on every exit. The same hook now guards the
OpenAI/ElevenLabs TTS clients (the chunk audio cap), the plugin transport and Mistral, and the REST TTS response hook checks the encoding.
The bound is chunk-granular (kept data is at most the cap plus the one underlying read that crosses it): it is **not** an
allocation-hard heap limit, and a declared length is only an early-rejection aid. Stop and the deadline are checked between
reads (no callback, no send); a vendor read that is already blocked in the stream or socket is NOT preempted - it ends when the
peer sends or at the client's IO timeout, after which the reader stops at the next chunk and produces nothing. No immediate
socket teardown is claimed. *Identity is enforced, not assumed.* Native
requests ask for `Accept-Encoding: identity` but a peer may ignore that, so any nonidentity `Content-Encoding` is refused on the
headers before a decoder or parser sees the body. Compatibility cost: a peer that ignores identity is unusable as a bounded
native response; its send is already counted, so the call is `failed_after_send` with source `None` (never retried, replaced or
accepted). For STT this and a response that crossed the bound are **terminal**: the installed-only local fallback is NOT run
even when available, whereas an ordinary known gated failure (a 5xx after a counted send) keeps the single documented fallback. Stock calls and the Edge WebSocket audio
are unchanged. *Usable join.* A multi-chunk call, remote or local, first runs a real concat on tiny synthetic files (a 0.1 s WAV
to MP3, then that MP3 concatenated with itself) under the qualified launcher; a missing, present-but-broken (or concat-incapable)
tool, or an unqualified host, is `unavailable`/`not_started` with zero chunk requests, engine children, Edge handshakes or
`before_send` calls and nothing left behind. The probe is not a send or an engine launch. The real join is the same wrapped,
Stop-aware child bounded by the exact remaining float deadline (no artificial minimum: once nothing remains no child starts and
the call is a `timeout`), local-file protocols only, a staged output under a size watch that ends the child, renamed only after it
is checked to be a usable join: an MP3 head AND exactly the compressed audio packets of its inputs, counted by the real ffprobe
under the same launcher (`-count_packets`; ID3 tags and the Info/Xing frame are not packets). The packet count is input-derived
and format-independent: a stream-copy concat drops each later input's tag and Info frame, a byte amount that varies with bitrate,
sample rate, tag size and whether an Info frame exists (measured 236 B at 24 kHz/48 kbps, 670 B at 44.1 kHz/192 kbps stereo, 1004 B
at 48 kHz/320 kbps stereo, thousands with a large tag, and negative without an Info frame), so no fixed byte allowance is right, while
every measured format kept every audio packet (the DTS diagnostics ffmpeg prints at the boundaries of inputs that carry an Info
frame did not coincide with any lost packet). A join that kept only the first input or lost only a short final chunk is refused.
The caller's output directory is checked for eligibility before any payment, and a join problem after payment, or after a local
engine chunk was produced, is `failed`, never `unavailable`. A local engine helper's own "unavailable" reply is the documented unsent
refusal only while NO chunk of the call has completed; the same reply for a later chunk, after completed local work, is `failed`/`not_started`
(the next chunk is not attempted, every file is removed). A multi-chunk native call therefore needs a qualified launcher (on this
release: Darwin 27.0.0 arm64) AND a usable `ffprobe` on `PATH` (a new native-host prerequisite of the multi-chunk join only,
proved by an actual launcher-wrapped packet count BEFORE any chunk; missing or broken is `unavailable`/`not_started`); single-chunk
speech (every Edge text of 600 code points or fewer) and the stock shared concat never need it. Elsewhere multi-chunk is unavailable,
which is the stated cost of not joining outside the launcher. *Managed whisper.cpp.* A non-WAV
input is normalized by the real ffmpeg under the same launcher with Stop, the exact deadline, capped streams and a capped,
checked WAV (missing converter: unavailable; a converter that ran and failed or left no WAV: `failed`); already-native formats need
no converter, the model/language rules, cached-hash verification and the no-download/no-PATH-whisper rules are unchanged.
*Failure map.* A Stop or deadline refusal that a probe, join or normalizer notices before the outer fence stays
`timeout`/`cancelled` and never becomes `unavailable`; a failed spawn is not a launch (`unavailable`/`not_started`). The Mistral
client supplies an inert async client, so the SDK's own unused one is never created or finalized on a loop. Not claimed: any
real engine, host sandbox, live Edge/provider, current-profile, global-telemetry or usable-parity result; the NeuTTS runtime
qualification (source rows corrected below, no runtime), legacy faster-whisper isolation, detached `setsid` descendants and the roughly 4 s refusal-reader join of the shared
runner remain open. *Auxiliary native audio (AUX-audio, source only).* The cloud STT preparation and the Gemini conversion that the earlier stages left on the
stock unwrapped runner are now native-wrapped. **STT preparation** (duration probe, silence trim, CAF -> WAV, full-recording compaction)
runs only as the same qualified-launcher, Stop-aware, exact-remainder, stdin-less, local-file-protocol (`-protocol_whitelist file`),
64 KiB-stream, size-watched children as the managed normalizer above; the stock runner and the fixed 30/120/300 s timeouts are never
reached natively. Authorization is asked before each spawn (never `before_send`). *Optional work* (the duration probe that gates the trim
and the duration cap, the trim itself): when the tool is missing or this host has no qualified launcher it is skipped with NO recorded
refusal and the unchanged full recording is uploaded exactly as stock does without those tools; an ordinary failure, junk or non-finite
duration also keeps the full upload and later accounting stays truthful (a subsequent 5xx is still `failed`/`failed_after_send`); an
unknown duration is left to the vendor in the one counted request. Stop, deadline, lost authority and output-bound violations are
terminal and are never swallowed into "upload the original" or another child. Cost: unqualified or tool-less hosts lose the optional trim and
the duration measurement, not the cloud provider. *Required work* (CAF conversion, compaction of an over-limit recording): the same two CAF
candidates in the same order (ffmpeg, then `afconvert`) with the stock conversion arguments, each wrapped, bounded and checked for a real WAV (a
partial output is removed before the next candidate); a missing capability is `unavailable`/`not_started`, work that ran and failed is
`failed`/`not_started`, and the installed-and-cached local fallback stays as permitted as for any ordinary builtin failure (no new cloud,
custom or command fallback). An over-limit recording is compacted as the FULL recording and sent once when that fits; when it still needs
several requests the call is refused before any silence split, piece or send (no paid first part, no partial transcript, no truncation). That
path is reachable from the standalone core seam (a real compressed recording under the 2 MiB cap can exceed a duration cap such as the default
`whisper-1` 600 s) and not from the planned 60 s, 16 kHz mono private-WAV ingress. An output bound (64 MiB per preparation file, the existing
normalized-WAV bound; an exceptionally long standalone CAF whose WAV expansion exceeds it is refused terminally, no resample is invented) ends the
child while it runs, is `failed` with no fallback and is never reported as an oversized transcript. STT tool discovery is unchanged (Homebrew
directories first, then `PATH`, as the accepted normalizer) while native TTS resolves `PATH` only; the wrapped tool's identity is not pinned by the launcher
table, and `afconvert` has no file-protocol option, so its fixed local-file argv runs behind the same network denial. **Native format boundary:**
the voice ingress packages WAV; a native call does not offer Silk. `.silk` is refused as an unsupported format before any lazy install, `pilk` import
or in-process decode (unavailable/`not_started`); stock Silk (QQ/WeChat) is unchanged outside a native call. No source reading proves that an
installation ever happened or that a decoder exists. **Gemini:** a native call, single chunk included, first proves a usable qualified WAV -> MP3
converter (the capability preflight, no request, no `before_send`; a multi-chunk call also runs the independent concat/packet preflight and accepts one
redundant tiny conversion). A paid PCM answer is written whole (small header, no extra copy, odd byte counts rejected) to a private 0600 file and
converted by the same wrapped, size-watched converter; a vanished or failing converter, malformed PCM or non-MP3 output is `failed`/`failed_after_send`,
an empty output keeps the existing `failed`/`completed` accounting, there is no retry, and the stock raw-WAV-under-`.mp3` fallback is never reached
natively. Cost: native Gemini needs a qualified launcher and a working ffmpeg (as multi-chunk Edge and the local engines already do) and is
otherwise `unavailable`/`not_started`; the single-chunk output is validated by its MP3 head only (no ffprobe prerequisite is added for single chunks).
*Fix1 clarifications.* (1) A non-zero child exit that left an output over its bound (the 64 MiB preparation file, the chunk cap) is the same terminal
`too_long` as a watched runaway - the optional trim does not then upload the original, the CAF does not try the next candidate and a paid Gemini chunk is
`too_long`; an under-cap non-zero exit stays an ordinary failure. (2) The named command-STT provider configured `native: local-sandboxed` with
`normalize: true` already reached the shared transcode: it now needs the qualified converter as REQUIRED work and counts as a local launch, so a normalization
that ran followed by an unsent command refusal is `failed`/`not_started`, while a missing capability runs nothing (reported `failed`/`not_started` by the
unchanged local-route map). (3) NM-A deadline label: when the call's outer deadline wins after a send may have left, the existing bounded gate reports
provider `unknown` (conservative, never authorizes a retry); a runner-noticed deadline can retain `completed`; Stop raises and carries no label.
(4) Coverage limit: unbound existing native-voice test modules run as an unqualified host (hermetic default; explicit stand-in bindings win), so qualified
preparation is exercised only by the three AUX test files and the command-STT controls.
Not claimed: actual launcher, `afconvert`, codec, platform or sandbox qualification, real provider output, per-spawn latency under the real launcher,
or any runtime result.

*NeuTTS dependency compatibility (source only).* The helper had validated NeuTTS against the global lock/Kitten rows (transformers 5.17.0,
phonemizer-fork 3.3.2), which no installation consistent with NeuTTS 1.4.1's own metadata (`transformers==5.1.0`, the normal `phonemizer`
distribution) can satisfy, while an upstream-inconsistent set was accepted. NeuTTS now uses engine-scoped rows: transformers 5.1.0 and the NORMAL
phonemizer 3.3.0 or 3.4.0 (the two inspected releases; their decimal-numeral phonemization differs upstream, neither is forced or rewritten). The
Piper/KittenTTS rows, the global tables and chains, the weights/cache logic and the 3.14 refusal (parent and child, before any import) are unchanged.
Normal AND fork phonemizer metadata together is ambiguous (both own the `phonemizer` package directory, so metadata cannot say whose code loads) and is
refused as `unsupported_version` (fork-only is `not_installed`); a transformers 5.17.0 / fork-only NeuTTS environment the old rows accepted is now
refused. The known phonemizer load requirements are probed by metadata and spec only, with no version guessed: `joblib`, `typing_extensions`, `dlinfo`
(not on Windows) and `segments` (3.3.0 only; 3.4.0 imports it lazily). Cost: a hand-damaged environment missing one of them is `unavailable` before
the engine instead of `failed` after it; presence is not dependency closure, ABI or runtime health. A usable NeuTTS still needs a separately
provisioned supported interpreter below 3.14 that hosts the whole core (there is no selector; core viability on 3.13 is unevidenced), its closure
(torch, llama-cpp-python, torchtune and torchao status unstaged), a platform wheel (NeuTTS ships macOS arm64, Linux x86_64 and Windows only) and complete
offline caches; Kitten (transformers 5.17.0) and NeuTTS (5.1.0) cannot share one interpreter at the admitted rows. Not claimed: any installed or usable
engine, genuine 3.13, model, cache, closure, platform, media, network-denial, lifetime or profile result.

**Disclosures that bind this stage (carried review findings).** (1) *Process-global telemetry (M1):* the pinned Mistral
SDK's own key-carrying trace exporter is switched off for a native call, but a process-global OpenTelemetry tracer
provider (one that a plugin installs, for instance) is not touched: it can still record request URLs and provider error
messages as spans and inject trace headers into native Mistral requests. This residual is open until the current-profile
integration gate inspects the enabled plugins and the actual tracer-provider type; no privacy acceptance is claimed.
(2) *Telemetry proof (M2):* the `config.telemetry = False` read-back proves nothing (the pinned configuration object reads
back whatever was assigned); the proof is the SDK's own `getattr` of the switch plus the behavioural exporter test, which
must run on any SDK change. (3) *Stock `stt.prompt` (M9):* the pinned SDK's transcription endpoint has no `prompt`
parameter, so the ordinary (non-native) Mistral STT path, which forwards a configured `stt.prompt`, fails with an error
result; this stock defect is not changed here, and a native call refuses such a profile UNSENT. (4) *Bound scope (M9, superseded by Task4):*
the 1 MiB `MAX_STT_RESPONSE_BYTES` answer bound now covers every native STT backend (OpenAI/Groq/DeepInfra and Mistral through an
httpx response hook, xAI/ElevenLabs through a Requests response hook), see the Task4 paragraph. (5) *Allocation (M5, superseded
by Task4):* the bounds are chunk-granular (the cap plus the one underlying read that crosses it), not an allocation-hard cap. A
nonidentity `Content-Encoding` is no longer merely discouraged by a request header: it is refused on the headers before any
decoder or parser, so there is no decoded inflation to bound. (6) *Logs (M3):* the native
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
one send; the first failed chunk stops the call and every file is removed. The routing/accounting REST multi-chunk tests join with a labelled byte-wise stand-in; the real per-chunk Gemini PCM-to-mp3
conversion AND the real launcher-wrapped ffmpeg join of real REST chunks (valid MP3, summed length, tones in chunk order) are
proved separately (Task4, below). "Zero hidden retries" at the
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

*Qualification follow-up (source and controller tooling only).* The patch now also carries
`tests/gateway/test_native_voice_profile_integration.py`: sentinel ElevenLabs/STT single-profile shapes over the real seam, the
installed ElevenLabs SDK (network bytes synthetic), stock config/secret scope and the registered authority as `authorize`. It is
source coverage, not current-profile, plugin-registry, tracer-provider or runtime proof; its Stop and revoked-grant controls use a
two-chunk speech joined through the existing launcher stand-in (synthetic, not media qualification). Controller host and metadata
tools, their shared helper and a separate pinned controller runtime were authored beside it but are not part of this patch and have
not been run.
The command runner (`tools/tts_command_provider.py`) now closes a stream from its own reader thread at late EOF, so a consumer that returned while a finite detached writer still
held the pipe leaves no descriptor open once that writer ends; `tests/tools/test_command_runner_pipe_ownership.py` is the real-pipe regression. Nothing else in the runner changed.

Focused evidence is in the stage reports, not in this directory: warning-strict canonical-runner suites over the
real OpenAI SDK, the real lock-pinned ElevenLabs 1.59.0, Edge 7.2.7 and Mistral 2.4.8 SDKs, `requests` (adapter replaced), the real
xAI resolver, credential pool and token store (OAuth HTTP transport replaced) with only the network transport (for Edge, the socket) replaced, the real ffmpeg on local synthetic audio, the registered core
owner for display reads, and stock-off controls. No provider, SDK-in-production, native, live or physical acceptance
is claimed.
