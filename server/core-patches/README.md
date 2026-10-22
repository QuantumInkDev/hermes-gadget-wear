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
  `NativeSpeech(state, path, code_points, source, provider)` and
  `NativeDisplayLocation(identity, physical_id, role, created_at_ms, segment_count, size_bytes)`.
* `transcribe_native_once(path, *, authorize, before_send, deadline_s)` — the profile's configured
  STT, read at call time, sent at most once: explicit timeout (≤ 60 s and the deadline), zero SDK
  retries, no transcode or OAuth-refresh retry after a provider answer, `authorize` before every send
  and after each await, `before_send` immediately before the transport send (a raise refuses it),
  one installed-only local fallback after a configured failure (never an install, never another
  cloud provider). Providers it can hold to one request: OpenAI-compatible (`openai`, `groq`,
  `deepinfra` with a pinned model), `xai`, `elevenlabs` and local backends; anything else is
  refused unsent (`unavailable`).
* `synthesize_native_once(text, *, output_dir, authorize, before_send, deadline_s)` — the profile's
  configured voice, one request per core chunk, stop at the first failed chunk, audio only inside
  the caller-owned directory and streamed under a byte cap. The 60 s request timeout is **new
  policy**, not a fact about the current ElevenLabs client. Voices it can hold to one request per
  chunk: `elevenlabs`, `openai`, `deepinfra` (pinned model) and `edge`; others are `unavailable`.
* `speakable_text(text)` — the reviewed spoken-text normalization of `text_to_speech_tool`, uncapped.
* `SessionAuthority.locate_native_display`, `read_native_display`, `check_native_display` and the
  existing-source analogs `locate_existing_display` / `read_existing_display` — identity-addressed
  reads over the existing visible-row projection and `HistoryFragment` segments with `session:read`
  proved before and after, the existing 2 s deadline and progress handler, newest-first locate bounded
  at 65,536 rows and hydrating only the matched row. Both locate bounds (rows, deadline) end with
  `RuntimeStoreError('read_budget')` whose `field` is `locate_rows` / `locate_deadline`: only a
  completed search reports absence. The existing-source analogs also take `adapter` and `source`
  (like every existing-source reader) so the actual device principal is proved; the design text
  omitted them. The Relay SDK's 8,192/65,536 response-bound regression for existing-source history
  is not addressed here.

Outcomes are stable tokens only: results carry no provider message, exception text, credential or
path except the audio file a successful synthesis wrote. `provider` is billing evidence about the
configured remote provider (`not_started`, `completed`, `failed_after_send`, `unknown`).

Focused evidence is in the stage report, not in this directory: warning-strict canonical-runner suites over the
real OpenAI SDK/httpx client and `requests` with only the network transport replaced, the registered
core owner for display reads, and stock-off controls. The ElevenLabs and Edge SDKs are not installed
in the owned offline environment, so those two engines are exercised through labelled stand-ins that
prove what the seam passes and what its transport guard refuses, not the real SDK's defaults. No
provider, SDK, native, live or physical acceptance is claimed.
