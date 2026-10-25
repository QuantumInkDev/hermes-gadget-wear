# Phone Relay controller: transport owner, encrypted ledger, deliberate conversation flow (Task3)

Status: **private source, disabled by default, JVM/replay proven only.** Nothing here ran on a phone, an emulator, a live host
or a real provider. Real AndroidKeyStore behaviour and power-loss durability are NOT proven (gate V7). Canonical voice (V5),
native history durability, the task inbox and notifications (Task5), the watch client, export/purge and the
real-device acceptance remain open and are not waived by this one.

Foundation: app `092cf5b8`, SDK `820db74b`, core `babda6d9` (the admitted disabled V4 source foundation,
`source-foundation.json` sha256 `5e8ecd86…`). Normative inputs read: `relay-native-clients.md` §§1–2 and step 2 (§4),
`relay-canonical-voice-plan.md`, `relay-canonical-voice-design.md` §§8/11/13 and `relay-state-contracts.md`. Where a stale
sentence and an accepted producer disagree, the producer wins (see §9, interpretations).

## 1. What this stage adds

| Piece | Where | Owner of |
| --- | --- | --- |
| `RelayFrameSniffer`, `RelayChannel`, `RelayOptIn`, `RelayTransport` | `protocol/…/relay/` | the ONE raw `relay.response` router of a connection |
| `DirectConnection` opt-in + socket-factory seam | `protocol/…/DirectConnection.kt` | hello capability, welcome, routing, generations |
| `SnapshotCodec`, `StoreBounds`, `RelayStore`, `ControllerSection` | `protocol/…/relay/session/` | the stored document and its finite limits |
| `RelayController`, `RelayRuntime`, `RelayUiState` | `protocol/…/relay/session/` | pin, create/attach, receipt-first reconcile, events |
| `EncryptedRelayStore` | `mobile/…/EncryptedRelayStore.kt` | AES-GCM file, atomic replace, explicit results |
| `PhoneRelay`, `RelayPage`, `PhoneConversation` wiring | `mobile/` | explicit enable, selection UI, typed composer, no microphone |

Stock behaviour is unchanged while Relay is off: no `relay` capability in the hello, no router, the same hold-to-talk,
START/FINISH cues, waveforms, server ElevenLabs/BYOK, Stop and lifecycle invalidation. Both server flags stay false.

## 2. Transport: one owner, explicit opt-in (requirement A)

`DirectConnection` owns the single bounded serial socket queue and the socket generation counter. A connection built with a
`RelayOptIn` (never by default) additionally owns one `RelayChannel`:

1. The hello carries the stock capabilities plus exactly `caps.relay = {"version": 1}` (`RelayNegotiation.withRelay`).
2. The welcome is classified: no `relay` member ⇒ `NotOffered` (Relay unavailable, **not** negotiated, stock continues); a member
   of any other shape ⇒ `Invalid(MALFORMED_WELCOME)`, an error and never a silent fallback; `{version:1}` for a device that is
   not paired ⇒ `Invalid(NEGOTIATED_WITHOUT_PAIRING)`.
3. Only a paired + negotiated welcome sends `relay.info`, the single call allowed before it. `ServerClock.fix` anchors the
   server time on the monotonic reading at receipt; an O5-only info (no `server_time_ms`) is `Failed(NO_SERVER_TIME)`.
4. Afterwards only methods that `relay.info` advertises can be called (`NOT_ADVERTISED`, otherwise `NOT_READY`). A re-read of
   `relay.info` (grant change, expiry) replaces the state and the clock fix.

**Routing and bounds.** Before the permissive stock `Message.parse` (depth 64, 1 MiB) sees a text frame, `RelayFrameSniffer`
scans top-level `type` members, decoding JSON escapes in keys and values (no body tree; short bounded string buffers,
scan bounded by the 1 MiB stock frame limit). Duplicate, non-string or malformed type candidates go to strict Relay
validation; a later Relay member cannot hide behind an earlier stock type. Legitimate stock frames keep their parser. A `relay.response`
never reaches the stock conversation, however late, unknown or large: the channel checks UTF-8 bytes (≤ 65,536; a larger
response is `Failed(OVERSIZE_RESPONSE)` for the Relay session and nothing for the stock one), depth (≤ 8, by the strict parser)
and the correlation, then decodes with the method's own bounds (`RelayCall.interpret`: 8,192 / 65,536 bytes, depth 6).

**Delayed dispatch.** Every queued frame retains its method, socket generation, accepted info serial and foreground scope
fence. Before each token release the channel rechecks current readiness, method advertisement and those fences. A changed
info view or failed info ends obsolete calls with `Refused(SCOPE_REPLACED)` exactly once, removing the whole obsolete set
before callbacks (including reentrant callbacks). DirectConnection also checks scope under the send lock, so select/leave/
discard/stop/review/hide/replacement can withdraw queued effects before release. Deadline and FIFO pacing remain bounded.

**Foreground handoff (fix2 R1).** `PhoneConversation` uses `PhoneRelayChannelHandoff` for the actual opt-in listener.
The channel thread only CAS-enqueues and posts to the Android main Handler; it never enters the foreground monitor.
The posted drain enters that same monitor and checks the captured connection epoch AND foreground lease before the
endpoint/enrollment-qualified `PhoneRelaySession.perform`. Hide/replacement invalidates before closing. Old queued Ready,
failure and close notifications cannot affect a new lease; rendered-action and enrollment checks remain in the adapter.
One owner drain is outstanding, with at most 16 queued plus 16 draining events. Ordinary notifications retain order.
Overflow seals normal input and fails closed as unavailable; queued authority verdicts remain, and only stronger terminal
review/permission-denial verdicts may upgrade it (at most two). A fresh connection gets a new bridge. This is transient only.
The post function must enqueue rather than run inline. Production uses the main Handler, not a shared transport lock.

**Transition ordering (fix2 R2).** Every channel state assignment gets a distinct revision, including open/close/failure
and same-generation/equal-state ABA replacement. Completion callbacks run after assignment; the revision and captured
socket generation are rechecked before obsolete-call processing and notification. A superseded transition is not announced
with a replacement generation. A listener that closes/replaces during LoadingInfo also withdraws welcome's info continuation.
The existing exactly-once pending-set removal and queued authority/method/scope/SEND fences are unchanged.
JVM interleavings use the actual DirectConnection/channel, production foreground bridge and owned monitor over in-memory
WebSocket callbacks plus existing recorded info replay. They establish nonblocking callback return, teardown/drain termination,
epoch/lease suppression, ordered reentrant notifications and no obsolete SEND. AndroidViewModel/Compose/main-Looper scheduling,
native lifecycle, AndroidKeyStore and device/power-loss behaviour remain unexecuted; compilation is not their acceptance.

**Correlations** are generation-tagged (`r<generation>-<n>`), never reused (a 256-entry memory plus the pending set), bounded to
four calls in flight (queued or waiting), and every call ends exactly once: the typed outcome, `TimedOut`, `Closed` (socket
generation ended: close, failure, disconnect, cancel) or `Refused` (not ready, not advertised, frame refusal, stale
generation, reused correlation, too many, queue full, socket backpressure). An answer that names no outstanding call (late,
repeated, foreign, from an older socket) is ignored and counted. An id-less error fails every call in flight as a link
failure (`Uncorrelated`). Pacing is a bucket of 15 tokens refilled at 9 per second (below the server's 20 / 10 s⁻¹): beyond it a
request WAITS in order under its own deadline, it is never refused for the rate.

This router is the single owner. V5 adds the binary sink and its backpressure here; it does not add a second router.

## 3. Identity, pinning and review (requirement A)

There is **no `server_id` on the wire** and none is invented. The pin is exactly:

| Bound value | Source | Stored in |
| --- | --- | --- |
| canonical endpoint | `Endpoint.url` (`wss` only; TLS stays required) and its SHA-256 `ep1:<hex>` = `RelayScope.endpointKey` | `EnrollmentRecord.endpointUrl/endpointKey` |
| THIS enrollment label | `phone:` + the public, key-derived `DeviceIdentity.deviceId` (never the secret) of the existing `ConversationVault` identity, reused without rotation or copy | `enrollmentKey` |
| profile | `relay.info.profile_id`, pinned at the first negotiation AFTER the user's explicit enable | `pinnedProfileId` |
| local review epoch | advanced by each deliberate review | `reviewEpoch` |

Catalog and display ids are never identity or authority. Watch and setup identities are never touched. A different profile on
a later negotiation sets `Standing.REVIEW_REQUIRED` (durable) with the observed id and sends nothing; so does a `profile_changed`
answer. `acceptReview` is possible only against a fresh negotiation on the live socket whose profile is the one shown; it
advances the epoch (old state becomes another scope, never sent under the new one), drops the selection and any create intent,
and retires the old delivery state. Earlier-epoch turns stay visible, can only be forgotten, and are never looked up or sent.
`permission_denied` sets `Standing.STOPPED` (durable) and owes the eviction (§5.6). Neither standing is cleared by a restart.

## 4. The stored document and its finite limits (requirement B)

### 4.1 Container (`state.v1`, own alias `quantumink.relay.phone.state.v1`)

`[1][12][iv(12)][sequence u64][AES-GCM ciphertext ‖ tag(16)]`, at most 8 MiB + 38 bytes. AAD =
`hermes-gadget|phone-relay-state|container-v1|<alias>|<sequence>`. The IV is random per commit. The plaintext is parsed only
after authentication. Separate from the identity vault (alias `quantumink.relay.phone.identity.v1`, 256-byte format) and from
`PhoneHistoryCache`. Files: `state.v1`, `state.pending`, `initialized` (the marker proves a vanished record is **not** an empty
store). One store serves Task3, V5 and Tasks 4/5.

### 4.2 Plaintext (`SnapshotCodec`, document format 4; reads legacy formats 1, 2 and 3)

Big-endian scalars, a `u16` count per collection, an `i32` UTF-8 length per string, no recursion and no JSON:

`"RS3\0" · u16 documentFormat=4 · u16 snapshotVersion(1|2)` · controller enrollments · ledger turns · scope decisions ·
histories · deliveries · (aggregate version2 only) capture section (`i32` captureSectionVersion=1, `u16` count, records) ·
metadata intents (`u16` count, records) · ordinary history evictions (`u16` count, records) ·
`u8 emergencyDeletionPresent` · optional ONE exact DELETED eviction · one `u8 selectionDeleted` per controller enrollment (same order) · `"END\0"`.

The aggregate always carries current captures explicitly (`RelayRuntime.build`). Aggregate1 has no capture section;
aggregate2 has capture-section1. Legacy document1 omits metadata/evictions and the emergency flag; document2 includes the
two feature counts/records but omits only the emergency flag/record. They decode with missing features empty, preserving
every aggregate section. Legacy document3 includes the emergency flag/record but omits the per-enrollment deletion flags; formats1/2/3 decode those flags as false, never guessed deletion evidence. Older readers fail closed on document4. Cache plaintext schema3, the state container1,
capture-section1 and HistoryArchive1 are separate version domains and are unchanged by this document-format correction.

### 4.3 Bounds, derived from the actual fields

UTF-8 byte caps: scope keys384 (128 UTF-16 characters ×3), URL2048, UUID36, fingerprint64, enum64, cursor512,
opaque conversation128/event256, create title512, raw metadata title4096, turn text16384. Counts:16 enrollments,
128 turns with1MiB text total,64 scope decisions,16 histories (2048 known/64 refused each),16 deliveries
(4096 seen/128 obligations/256 ACKs each),64 captures,16 metadata intents and64 ordinary history evictions.

One separate emergency deletion slot is reserved for exact canonical DELETED namespace/conversation evidence at the
ordinary64 limit. It cannot contain CACHE_ONLY, REVOKED or a null conversation; a second distinct overflow is typed
CAPACITY. No ordinary cap is increased. RETIRED immutable metadata intents also remain exact durable deletion evidence
until confirmed removal. Format4 reserves both coreCommitted presence/value bytes even while the result is absent (false padding); formats2/3 retain conditional presence/value decoding. PENDING/UNKNOWN to RETIRED therefore does not grow its existing immutable intent. Genuine permission denial may use durable STOPPED itself as scope-wide REVOKED authority at
capacity. STOPPED never authorizes wider erasure for DELETED or CACHE_ONLY.

Every admitted attach first confirms a format4 record with its exact selected identity and a fixed deletion flag. A current
attach/bound deletion flips that byte while keeping the same namespace and selected ID: no new slot, target string or
aggregate bytes are allocated. Until confirmed removal clears both ID and flag, selection/create/retry/review replacement
refuse; Leave/disable preserve the marker. Other eviction/metadata/cache-review allocations cannot consume this pre-encoded
representation. Metadata results have their already durable immutable intent target. Terminal events record deletion before
expanding seen-event accounting. Deterministic aggregate representation refusals withdraw the observer; actual unconfirmed
disk writes stay honestly unsaved and do not establish power-loss durability.

Let `S(n)=4+n` and `N(n)=1+S(n)` for required/nullable strings. Field-cap upper bounds (bytes, computed/tested) are:
scope824; turn fixed1337 (excluding its text length/data); scope decision893; history254673; delivery1950663;
capture1237; enrollment3595 (including its fixed deletion flag). Namespace=`2*S(384)+S(2048)+S(36)+8`=2876.
Metadata intent=`namespace+2*S(36)+8+N(4096)+2+8+S(64)+2`=7145.
History eviction=`namespace+N(36)+S(64)`=2985 (the reserved slot uses that same bounded encoding).

Empty document4/aggregate2=`header8+trailer4+fiveCounts10+captureVersion4+captureCount2+featureCounts4+emergencyFlag1`
=**33 bytes**. Legacy document3/aggregate2 empty=33; legacy document2/aggregate2 empty=32; legacy document1/aggregate2 empty=28. Aggregate1 omits the6 capture bytes.

Full field-cap upper bound=`33+16*3595+128*1337+1048576+128*4+64*893+16*254673+16*1950663+64*1237+16*7145+65*2985`
=**37,007,818 bytes**. The last65 is64 ordinary records plus ONE reserved exact deletion, not a widened ordinary quota.
Document3's corresponding bound was37,007,802; document2 was37,004,816. These are structural field-cap upper bounds: actual enum spellings and
validated namespace values can be shorter, so no legal maximum-size writer or device latency claim follows.

The deliberate plaintext cap remains **8MiB** (`StoreBounds.PLAINTEXT_CAP_BYTES`), below the structural bound.
Above it: `EncodeRefusal.STORAGE_CAP`, explicit local backpressure. File cap remains8MiB+38 container bytes.
Count/string/encoding refusals remain `TOO_MANY(path)`/`FIELD_TOO_LONG(path)`/`INVALID_VALUE`; nothing is truncated.
The decoder checks counts and lengths before allocation. Runtime commits still dry-run exact restoration with captures
explicitly supplied. Extra metadata/cache-review edits catch construction CAPACITY at the owner before any SEND/erase.

### 4.4 Commit contract and failure handling

`commit(document, base)`: encode → inspect and authenticate/decode the EXISTING record using the expected alias/key,
format and bounds → compare its authenticated sequence with base → seal (random IV, sequence in the AAD) → write `state.pending` + fsync → read it back,
authenticate and decode it → `ATOMIC_MOVE` over `state.v1` → write the marker (first time) → fsync the directory. A late
writer whose `base` is not the on-disk sequence gets `Stale` and publishes nothing. Results are explicit: `Committed`,
`Refused(reason)`, `Failed(problem, uncertain)`, `Stale`; read problems are `KEY_UNAVAILABLE`, `STORAGE_UNAVAILABLE`,
`RECORD_MISSING`, `CORRUPT`, `UNSUPPORTED_VERSION`, `TOO_LARGE`, `UNRESTORABLE`. **Unreadable or not-yet-restorable bytes are kept
exactly as they are**: no key is generated while any byte exists (the key is asked to *create* only for a store that never
held one), no reset or erase on a failed read, no empty substitute, and there is no discard operation in this stage.
A failure before the replace leaves the previous record (certain); a failure after it is **uncertain**, never success.

`AtomicFile`/`ATOMIC_MOVE` + fsync is **not** proof of power-loss durability on a device; that is the later native gate (V7).

### 4.5 Persist-before-send, in-memory truth, late callbacks

Derive the next immutable runtime → build the complete aggregate (captures explicit) → dry-run restore → encrypt + commit →
only then write the frame. A failed, refused or `TooMany` commit refuses the send: no frame, no turn in memory, the draft stays.
State advanced by a server answer is evidence and is applied in memory even if its commit fails (`unsaved`); the in-memory
state is never behind the disk and the next successful commit rewrites everything. After an uncertain failure whose sequence is
exactly the next one, the base moves on; any other sequence is never adopted. Every callback is tagged with the controller's
session serial (socket/session change, hide, review) and flow serial (selection/attach) and is dropped before it can change or
send anything when it is older.

Restore needs a **fresh server time and a fresh authority**: the stored bytes are decoded when the screen opens, but restored
only with the clock of the first `relay.info` of a negotiated socket. Until then create/select/send do nothing and write
nothing; enable/disable rewrite only the controller section, carrying the stored aggregate unchanged (an empty store gets
the first, empty-with-explicit-captures document). Device wall time is never used.

### 4.6 What is never stored

No binding, `authority_epoch` of a binding, replay epoch, event offset/cursor, correlation, action or prompt intent, audio,
transcript, draft, stream or playback state. `authorityEpoch` exists in the snapshot only as the server's receipt evidence of an
admission; the epoch a request carries always comes from the live binding. The history snapshot keeps only a resume point and
message identities (no text); the transcript on screen is rebuilt from the history read after every attach.

## 5. The deliberate conversation flow (requirement C)

### 5.1 Enable and states

`setEnabled(true)` is the only way in (per profile, `wss` only, at most 16 enrollments); it is stored before it is used and a
live connection restarts, because the capability is negotiated per socket. Phases: `OFF`, `CHECKING`, `STORAGE_PROBLEM`,
`CONNECTING`, `UNAVAILABLE(reason)`, `REVIEW_REQUIRED`, `STOPPED`, `READY`, `ATTACHING`, `SELECTED`; a notice enumeration names
every transient condition (storage full / write failed / uncertain, clock, busy, history incomplete, anomaly, …).

### 5.2 Create (idempotent)

`create(title)` mints a UUIDv7 from the server-time estimate, stores `CreateIntent{request_id, title}` **before**
`relay.conversation.create` is written, and retries the SAME id and title on timeout, reconnect and process loss (never another
conversation). `invalid_request`/`unsupported` spend the intent; `expired` and unknown answers halt it for the user (who may
discard it; nothing is deleted); busy/rate/unavailable back off. Every dispatch and callback must still match the current
durable request, title, prepared time and local create operation. A successful discard invalidates that operation, its
retry timer and queued frames; a later new create is independent. Current intent reconnect recovery retains the same ID.
Success stores the conversation identity and attaches.

### 5.3 Select / attach

Selection stores only the conversation identity, then `relay.conversation.attach`. A new attach retires the old binding
(`RelayAuthority`), starts the event session, and is followed, in this order, by: receipt lookups of unresolved turns, the
advertised history (≤ 24 pages of 50, ≤ 64 total requests including retries/restarts, `HistoryAssembly`), the advertised
task snapshot (enrollment-wide pages of 25, ≤ 24 pages with ≤ 6 retries per page, ≤ 100 selected tasks), then
event polling from the binding's baseline (1.5 s while a turn is awaited, 8 s idle). Task4 now adds retained cache/browsing below; inbox and
notifications are Tasks 4–5 — a refresh obligation is not proof they exist. After a **process restore nothing attaches by
itself**: the stored selection is offered as "Resume". A reconnect inside the same foreground session re-attaches to the
selection the user already chose.

Attach backoff is bound to the initiating session, flow, selection and scope. A new selection or leave cannot revive an
old attach, and a returned binding must name the still-selected conversation before adoption. History and tasks have
separate completion results: only completed kinds clear their durable refresh obligations. Empty cursored task pages are
progress, repeated cursors and finite caps stop with partial/unknown state. Failure/retry exhaustion leaves owed work on
disk across process restore; it never labels the view current. Task counts remain unknown until the full bounded scan
completes. Retry refresh deliberately reattaches the selected conversation and refreshes again; no inbox/cache expansion.

### 5.4 Typed turns (receipt-first)

`TurnLedger.prepare` + `planSubmit` are ONE stored write, then the frame (same immutable id, text and expiry; server clock
only). After a timeout, reconnect, `stale_epoch` or `stale_binding` the controller attaches again and looks up the receipt
FIRST; only an `ABSENT_SO_FAR` turn is written again, with the same id/text/expiry, before its deadline, for turns written in
**this** process and at most three times. Restored turns are only looked up; "Send again" is the user's. An admission receipt is
not a final answer; unknown/UNCERTAIN/EXPIRED_REPORTED are never shown or stored as success; changed content is a new turn with a
new id by a new explicit send. Replies, events and receipts apply only to the exact endpoint/enrollment/profile/review/
conversation/binding/generation (`BindingToken`, `CallRef`, session serial). Actions, prompts and outbox copies never execute by
themselves; a prompt event is only noted ("waiting on another device").

### 5.5 Receipt permission denial is not evidence of absence

The registered SDK (`relay_store.find_request`) currently answers `permission_denied` for any request id that is not this
device's own journaled turn when the device lacks `relay:receipt:shared`; the default phone grant lacks that permission.
Thus a turn lost before admission can receive the same error as a revoked receipt authority. A successful history read
cannot resolve that ambiguity: read and receipt permissions are distinct, and it cannot prove that an earlier turn was
never admitted. The controller passes the actual denial unchanged to the ledger: **stop the scope, retire authority and
retain the content-eviction obligation until its durable completion**. It emits no read probe or automatic submit.

The original stopped worker's probe-to-`not_found` workaround violated this rule and was removed after a semantic RED
regression. Replay covers the registered producer's default-grant denial and verifies no further request; a separate
registered shared-receipt-grant capture proves the true `not_found` same-ID path. No additional grant is silently added.
The default-grant lost-before-admission compatibility issue requires a reviewed SDK contract change before live automatic
recovery is claimed. SDK production is unchanged in this Task3 stage; this limit remains open for later server integration.

### 5.6 Errors, backoff, stop and review

`busy`/`rate_limited`/`temporarily_unavailable`: the same request after 1, 2, 4, 8, 16, 30 s (at most six retries, then the user).
`stale_binding`/`stale_epoch`/`snapshot_required` and every gap (a gap event is part of NORMAL operation — every captured turn's
events carry one): `relay.info` is read again, the session attaches again and history is read again; nothing is resubmitted.
`permission_denied`: durable `STOPPED`, authority retired, then the eviction obligation — turn texts, delivery state and history
resume points and the Task4 retained cache — **stays owed in memory and on disk until confirmed removal is stored**. `profile_changed`: durable `REVIEW_REQUIRED`. These effective authority actions take precedence even when an error also carries
`snapshot_required`; only an effective refresh action may restart a cursored listing. Review withdraws the cursor/retry owner.

### 5.7 Reconnect request ordering

A same-connection reconnect withdraws the previous attachment scope before queueing the profile-list refresh and the fresh
attach. Those two requests share the replacement transport scope; attach must not withdraw its own sibling refresh. Explicit
selection and attach retries keep their existing withdrawal and exact-selection guards. No binding or authority fence is
removed, and no recording or server permission is changed to accommodate timing.

`RelayReconnectOrderingTest` holds the actual connection owner at the reconnect Ready notification after posting to the real
controller's manual owner. Both requests queue before the socket owner resumes. The unchanged registered recording verifies
list then attach then receipt, and its default-grant denial still stops without a history probe or another submit. This is a
controlled production-controller/connection replay, not Android/native or live producer execution.

### 5.8 Screen, microphone, lifecycle

The existing phone screen gains one "Relay conversations" section (off by default) and, inside a selected Relay conversation, a
typed composer in place of the stock controls. **The microphone is unavailable there** (`InputRoute.UNAVAILABLE`): audio is never
routed to the physical-device stock chat and an executing stock transcript is never resubmitted. Hiding or replacing the screen
withdraws the `PhoneRelaySession` foreground lease before `RelayController.hide()`: timers and requests stop, the socket closes, nothing re-attaches by itself. Themes, art and the
stock controls are untouched.

The production-used `PhoneRelaySession` adapter binds pending enable/disable to the displayed endpoint and foreground
lease, resolving only that endpoint's own existing/new-on-deliberate-connect vault identity before any settings write.
A prepared enrollment cannot be replaced within a lease. Hide/open withdraw unrelated pending requests. Controller UI
carries its prepared identity and foreground lease; stale UI/preparation/channel callbacks and rendered controls are
qualified before use. The existing ConversationVault keys and alias are reused unchanged. JVM adapter tests drive the
real controller/file store; AndroidViewModel construction, Compose lifecycle and native Keystore were not executed.

Storage notices distinguish a pre-send refusal from an unsaved server observation (requests may already have reached the
server). STOPPED exposes pending versus confirmed durable ledger-content eviction; failed eviction never claims content
is gone. Native persistence remains outside this evidence; Task4 JVM cache ordering is described below.

## 6. Carried OPEN to V5 (not changed here)

* **B2/N1**: a queued/FINISHING capture whose server row is purged or absent after the retention horizon is **not** proof of no
  charge; absence-only `NOT_SEALED` must not be shown as unbilled and STT is never retried automatically. The pure getter in
  `VoiceCaptureLedger` is untouched; V5 owns the reviewed correction before any canonical voice is exposed.
* **Result-vs-discard correlation**: a `not_found` of `capture.discard` must not masquerade as a result read. V5 owns it.
* M1–M6, N2–N3 and the earlier Minors keep their original scope.

## 7. Not done, not proven

Real AndroidKeyStore, device file systems, power loss (V7); any emulator/device/live host/provider; V5/V6 voice; native history/cache acceptance, Task5 inbox/notifications, Tasks 6–8; existing-session sources; prompt answering and cancel/stop from the phone; the
watch. Original N6/N10 exact recovery stays REQUIRED, unrestored and unwaived (no quarantine was opened, scanned or reset).

## 8. Tests, fixtures and provenance

* `RelayChannelRoutingTest` (real `DirectConnection`, in-memory socket factory): opt-in, welcome, info gating, bounds, late/unknown
  correlation, generations, pacing. `SnapshotCodecTest`: round trips, v1 → v2, exact maxima, hostile input. `EncryptedRelayStoreTest`
  (JVM, software key): AAD, authenticate-before-parse, bytes kept, atomic replace, uncertainty. Controller suites (scripted
  transport, REAL envelope/DTO decode, real codec): flow, durability, reconnect, review/stop, enrollments, probe paths.
* `relay-task3-v1/` — **captured** sessions: the exact requests the controller emits, driven by `tests/test_native_relay_task3_sessions.py`
  through the registered rig (`relay_bridge_support`: real GatewayRunner, SessionAuthority, wiring, ledger, SQLite; synthetic
  socket and provider boundary only), with the exact response frames. They are **replayed** by `RelayReplaySessionTest`: the
  production controller over the production `DirectConnection`; every request must equal the recorded one (everything but the
  correlation id, the only member re-addressed). Replay is not live integration. The gap is forced with the registered binding's
  own bound (the accepted SDK test's method).
* Not executed here: `EncryptedRelayStoreNativeTest` (androidTest source, compiled only).

## 9. Interpretation register

1. `authority_epoch` "not persisted" means the **binding's**; the admission fact in the accepted snapshot is receipt evidence.
2. Enable/disable before the clock exists rewrite the controller section only and carry the stored aggregate unchanged; every
   other mutation waits for the fresh clock (the brief's "block mutation" applies to the aggregate).
3. A restored turn is looked up but never resent without the user; a same-process absent turn is resent by itself (same id).
4. A lookup `permission_denied` is passed unchanged to the ledger (§5.5); history reads never establish receipt absence.
5. History resume points are kept per scope (newest 8); an over-limit one only means "read that listing from its top".
6. The channel queues rather than refuses on its pacing bucket (a refusal would have failed a flow that merely had to wait).

## Optional own-receipt observation

The controller prefers `relay.turn.receipt.own` only when the current connection's fresh `relay.info.methods` advertises it. `TurnLedger.planLookup` receives a transient typed choice and builds the actual call with exactly `conversation_id` and `request_id`. Unchanged planner callers default to the legacy receipt method, which remains part of the required Relay baseline. SDK version and restored state confer no method authority; a new SDK with incompatible core proof support hides the optional method.

Both operations use the existing bounded Receipt/error decoder, CallRef/outstanding correlation, enrollment/conversation/binding fences and final queued SEND capability checks. Reconnect obtains fresh Info and attaches before lookup. Withdrawal or scope replacement invalidates queued work. A failed own observation does not fall back to legacy/shared/history evidence or enlarge a grant. The ordinary attach history/task snapshot remains separate and never establishes absence.

Only the current applied, correlated `not_found` lookup may release an automatic retry through the existing ledger and per-process attempt, current binding, clock, expiry and persist-before-SEND gates. Historical ABSENT_SO_FAR can survive a failed lookup, but that retained bit alone does not release new work. The tested old-production schedules with exhausted retry budget were already safe controls, not a reproduced stale-absence SEND defect. Original request ID, text and expiry never change; found admission never submits again, and denial still stops and evicts. Absence does not establish a final outcome, no charge, purged execution or permission to replay STT. Canonical microphone remains OFF.

The separately captured `relay-own-native-v1` sessions exercise the actual registered producer and production controller/DirectConnection replay seams. Their socket/provider boundaries are synthetic; Android tests are compiled separately from JVM execution, and native/live/physical/battery acceptance remains open.

## Task4 phone history, deliberate search and metadata

The existing Relay screen now separates online authorized human-text/title search
and bookmark filtering from local retained-text browsing/search. Retained results
are labelled retained/partial/offline and read-only. Selecting cached text never
attaches, grants control or routes the stock composer to a different conversation.
Current authenticated selection continues typed messages in the same conversation.
Existing-source history remains separately opt-in per profile and is not included.
All actions retain the existing endpoint/enrollment and foreground lease callbacks.

Controller rows retain actual public revision, bookmark and metadata state. Search
query/filter/limit/cursor and operation generation stay together. Empty progress
pages advance; repeated cursors, page/request and restart budgets stop with partial
status. Delayed old query/retry replies cannot append to a new query/profile.

Rename and bookmark use only current advertised conversation.update permission and
writable listed rows. A UUIDv7 and immutable expected public revision/body are
committed in the shared RelayDocument before SEND. Lost replies/restart retain that
intent. Retry is deliberate with the same ID/body/revision; refresh never rebases it.
Accepted, superseded, retired/core_committed, stale snapshot, route unavailable,
transient, expired and denied states stay distinct. Returned sanitized metadata is
shown exactly, without an optimistic Saved title/bookmark. Stop tracking is explicit;
it cannot undo a server mutation. No metadata retry submits a turn or replays speech.

SnapshotCodec document format 4 retains bounded metadata intents and history eviction obligations
in the same aggregate and adds the reserved exact deletion slot. Format 1 remains readable without inventing either section;
voice captures and all pre-existing sections are carried explicitly on every write.
Older document-format1/2/3 readers fail closed on document4; formats1/2/3 remain readable by this writer. Text cache schema 3 and reviewed legacy
handling are documented in history-export-format.md. No server production, protocol
method, permission or transport implementation change was needed for Task4.

New registered SDK captures use actual enrollment, grants, Gadget registration,
GatewayRunner/SessionAuthority, SQLite and canonical metadata/search owners behind
synthetic socket/provider/pairing/clock boundaries. Native replay uses the actual
RelayController and DirectConnection. Peer.send acceptance observes the matching
committed request_id/text/expiry and full metadata body. Independent failed, refused,
stale and uncertain commit cases and fresh positive controls qualify the earlier
Minor1 check; the frozen old generations remain unchanged. Android tests are compiled
only. Task5 inbox/O5, Task6 watch, Task7 SAF/delete acceptance, Task8 native/physical/
live/battery and full15 remain open. Canonical microphone remains OFF. Original
Task3 raw evidence and N6/N10 recovery requirements remain unrestored and unwaived.

Retained-view routing also gates the controller's typed-send entry itself. Closing
the local view restores deliberate continuation using the existing authenticated
binding; turning Relay off clears the local selection and restores stock input.
These paths are covered separately from socket acceptance and from native UI tests.


### Event admission at the seen-table bound (Task4 FIX3)

The complete correlated current event page is checked before terminal classification: current Info, endpoint/enrollment/
profile/review scope and flow, authority token, replay epoch, every binding/conversation/sequence, and nested payload target.
A validated single selected tombstone reaches the existing format4 selected deletion flag before ordinary dedup admission
can reject it. Every old seen ID/age, pending ACK and delivery obligation remains exact. No event accounting is committed
for this deletion; durable marker confirmation still precedes cache/ledger removal and confirmed final clearance. Failure
keeps the represented removal fence. DELETED and CACHE_ONLY do not confer STOPPED authority.

Ordinary capacity uses one bounded transient decoded reply, owned by its exact outstanding read and current controller
lifetime. Pruning only age-eligible keys is a proposed aggregate commit; after confirmation the actual poll owner restages
the original answer. Failed/refused pruning keeps all old keys and the held answer. No-room remains explicit capacity,
with no repeated no-op commits, extra read, synthetic ACK, create/submit/reattach or discarded pending work. Wakes use the
existing seven-day ages and Info maintenance rather than tight polling. Flow/session replacement clears the transient
reply/read, and readiness after a same-effective Info refresh resumes current polling. No unlimited progress is claimed.
Mixed terminal pages are rejected as current producer-shape hardening (registered tombstone backlog has ONE event), not
as a newly registered producer capture or universal new wire requirement; existing single tombstones without the snapshot
flag retain their contract. Snapshot/format4/legacy1/2/3, ordinary16/64 and emergency representation stay unchanged.


## Phone task inbox (Task5 source candidate)

The companion application owns one application-lifetime controller and aggregate writer. Foreground connections
and bounded headless observers share that runtime, including applied unsaved observations and all pending typed,
voice, capture, history, metadata and delivery sections. A different identity defers while unsaved evidence or an
undurable exact-removal fence remains. Same-identity handover retains those values. A new foreground lifetime
must confirm its own selected reservation; no binding, held canonical reply or prepared identity is transferred.
Foreground claim withdraws the prior connection synchronously, then controller teardown/preparation runs on its
serial executor. Atomic writer/observer/input generations fence late callbacks, timers and paced final SEND.
The path lock and sequence CAS protect replacement, never merge independently held documents.

The Inbox tab shows Relay tasks across saved reviewed phone profiles, exact box/profile/review/source and
conversation, outcomes and retained replies. Foreground refresh is deliberate. Last full sync is retained per
profile; missing rows/pages, offline boxes and partial traversal do not imply completion or deletion. Existing
Hermes tasks and history have separate per-profile opt-ins and current method/grant/task-purpose delegation.
Exact existing-target NOT_FOUND is target/source unavailability: encrypted copies remain, routes refuse,
and other Relay authority remains ACTIVE. Temporary source unavailability is not global revocation. Empty O5/missing rows alone create no revocation. A successful exact current
getter clears the availability fence. Actual permission/profile errors retain their registered authority action.

### Separate authenticated display format

The aggregate remains document4/aggregate2, including fixed selected-deletion markers and the fixed nullable
metadata-result reservation. No admitted old byte is reinterpreted or migrated. New display-only schema1 uses
`phone-inbox/inbox.v1`, one-byte `initialized=1`, distinct alias `quantumink.relay.phone.inbox.v1` and AES-GCM.
Its container is `[1][12][iv12][revision i64][ciphertext + tag16]`, plaintext plus38 bytes. AAD is
`hermes-gadget|phone-inbox|container1|<alias>|<revision>`. Authenticate before decode. Unknown version, malformed
UTF8/data, missing marker/record, unresolved pending bytes and absent existing key refuse without reset/repair.
Writes sync pending bytes, atomically replace, then sync marker/directory. Promotion uncertainty is never success.
Real Keystore, filesystem and power-loss durability remain native acceptance gates.

Plaintext schema1 is big-endian: magic H G I B, i32 version1, i64 revision, u8 background, i32 retention days;
i32 profile count followed by namespace and bounded key/label/box, seven strict u8 booleans, high-water and
last-sync i64; i32 copy count followed by namespace, exact existing OutboxItem wire JSON, receipt i64, handle
and attempt/suppression u8 and unread u8; i32 task count followed by namespace, existing u8, current task wire JSON with no
prompt, receipt i64 and handle; i32 history count followed by namespace/conversation/existing u8/receipt i64,
i32 messages and each id/role u8/created i64/text; i32 suppression count with namespace/event/receipt; i32
unavailable count with namespace/conversation/history u8; i32 round-robin cursor0..15; reverse magic B I G H.
Every string is an i32 UTF8 byte length plus strict bytes. Namespace is endpoint key, canonical TLS endpoint,
public enrollment label, profile UUID, review i64. Existing DTO decoders retain wire/null/provenance bounds.
There is no pending action/voice/ACK shadow journal. Local route handles are random256-bit opaque hints.

Limits: 8MiB plaintext;16 profiles;4096 copies plus event suppression rows together;512 current task projections;
64 complete retained histories with256 assembled messages each;128 exact source-unavailability markers.
Generic string cap65536 bytes; namespace/key/label/box decode caps512/8192/512/64 and2048 as appropriate;
history text cap1MiB, still within aggregate display byte cap. Constructor character limits and existing wire
field bounds also apply. At capacity nothing is silently dropped; retention/confirmed seven-day seen pruning
or deliberate settings changes may free room. The legal4096-row/16-profile test reaches within4096 bytes of
8MiB and refuses the next legal row-size, separately from structural-count rejection.

Default text retention90 days, configurable0..365. First receipt age and exact server created/expiry remain
separate. Duplicate rereads and lost-ACK retries preserve age, identity, route and server expiry. Persisted local
high water prevents backward-clock renewal after writes/pruning; forward expiry can remove local text. Reads
do not renew it. Bodies still owed to aggregate ACKs are protected from local retention. Expired server debt
can retire only from exact retained bodies, under fresh server time and confirmed full aggregate replacement;
this is local expiry, never server ACK success. Seven-day event suppressions share the copy bound.

### Copy, aggregate, ACK and notification order

Actual stageOutbox fresh AND duplicate bodies enter the authenticated display cache first. Then commitOutbox
facts enter the complete aggregate. A seen key alone never proves a payload copy. Before ACK, authenticate and
confirm every exact owed body, then confirm the current complete aggregate again. Fresh Info, enabled ACTIVE
reviewed namespace, effective removal, source opt-in/method and current observer/transport lease all remain
required at final SEND. Same-ID retry after lost response is deliberate/idempotent; no new identity or expiry.
Failed/refused/stale/uncertain writes retain truthful copies/debt and cannot ACK. Callback IO failure withdraws
observer authority. Full4096 seen and256 pending ACK quotas remain; no terminal-event capacity exemption is
borrowed. One bounded reply may be restaged only after a confirmed eligible seven-day prune within that run.
No room or failed prune ends partial without no-op commits/spin; deliberate later refresh obtains a new read.
A gap/superseded copy clears its refresh debt only after all bounded permitted canonical task/conversation/
history and opted-in existing chains complete. Partial paging or unavailable targets leave debt owed.

Per-profile quiet completion/input controls default off. One event-specific attempt/suppression decision is
persisted before OS publication. This provides at-most-one attempted alert, with possible lost alert on crash
between decision and OS call, never exactly once. OS permission/channel/publication failure retains unread
content and has no ACK authority. Each copy has a durable unread bit; deliberate Mark read changes only that
local bit through the serial exact-route owner. A tap/launch does not mark read or perform a server action. Notifications use a quiet low channel, generic private/public lock-screen
wording, stable exact-identity/event tag and immutable PendingIntent carrying only the opaque local handle.
There is no automatic speech, playback, answer, attach, retry or provider call from refresh or tap.

### Jobs and actual routes

One stable persisted inexact periodic JobScheduler job uses15-minute interval/5-minute flex and network ANY,
only with global background updates plus reviewed enabled targets. Unchanged configuration is not rescheduled.
Scheduling failure and Android delay are separate status. Setup-transfer recovery completes off main before
state or identity access. Typed read-only phone identity lookup never uses AtomicFile recovery, marker repair,
key generation, enrollment or watch keys. Headless Info admission cannot pin an unknown profile, reauthorize,
attach/create, resubmit or retry metadata. Sequential durable round-robin work is bounded by16 targets,55s
job time,22s wait per target and controller20s/64requests/24pages per chain. Stop/replacement/network loss use
one completion generation. Assigned Network owns both DNS and sockets; a different effective active VPN/
tailnet refuses work. Default DirectConnection DNS and private cleartext-host policy remain unchanged.
No process network binding, VPN bypass, push account, FGS, alarm or power exemption is added.

Notification cold/warm/onNewIntent routes resolve the exact durable scope/event/target after current catalog
and non-mutating identity lookup; no current/default-bot fallback. Same-profile other-conversation opens its
actual read-only detail, independently of profile-only widget IGNORE. Busy recording/selection/input retains
only the intended handle and repeats lookup on release. Lifecycle/intent replacement invalidates old lookups.
A distinct Continue action selects/attaches the exact canonical conversation through the prepared foreground
controller. Opaque final refs remain read-only, including UUID-looking strings. Existing current-input attach
uses its registered owner without enabling the stock composer. Get current input uses only fresh registered
task/current-prompt getter; list/events/O5/saved callbacks remain display only. A separate deliberate Answer
consumes one exact current binding/generation/prompt token, commits the complete aggregate and sends once.
No durable answer retry journal or automatic paid retry is created; loss is uncertain and requires fresh input.

Recorded JVM/Peer/software-key tests and registered synthetic producer captures support scoped source work.
Android UI, notification permission/lock screen, job/Doze/assigned network, real Keystore/Looper/fsync/power loss,
near-cap device latency and native/physical/live/provider/billing acceptance remain separate OPEN gates.

Writer handover qualification: foreground selection first reserves a generation and synchronously withdraws the
old transport/callback/timer authority. Actual foreground preparation is a serialized barrier before connection
or actions, so an already-running old file commit can physically settle before prepared replacement admission.
Its returned complete aggregate remains in the same runtime/sequence; old writer publication, new writes, paced
SEND and completion callbacks refuse after withdrawal. Delayed tasks also capture writer epoch, not only serial.
The old bounded job wait is released by preemption. This is not cancellation of an in-progress filesystem write,
not a stale-document merge and not a promise of uncommitted observation recovery after process loss.

Canonical exact-target deletion fences/removes canonical change copies and task/history projections only.
Opaque final conversation/event/message references are never reclassified by UUID shape or string equality.
They remain read-only and survive an unrelated exact canonical deletion, while genuine whole namespace
revocation and local retention still apply. Current delivery refresh kinds are HISTORY and TASKS; clearing
both still requires the complete bounded conversation enumeration/history/task and opted-in source joins.

Application owner construction also defers aggregate/inbox backend factories until off-main blocking callbacks;
it does not request storage paths or keys on main. Factory failures map to unavailable/failed results through
the same stores, with no reset. Existing PhoneHistoryCache already defers its factory. Native Looper/Context/
Keystore execution is still a separate unexecuted acceptance gate.

A replacement onStartJob withdraws the prior active background lease before installing its own generation;
changing a service alive predicate alone is not transport/controller withdrawal. Old completion cannot finish
the replacement job. Actual platform callback ordering/Doze/permission behavior still requires native acceptance.

### Task5 FIX1 authority, network and full-sync corrections

Current canonical task and existing prompt getters, and the separate deliberate answer callback, apply correlated
permission_denied / profile_changed before UI completion, including snapshot_required combinations. Their existing
STOP/review owners synchronously invalidate pending transport before any fallible aggregate/cache write. These
deliberate input paths never attach, resubmit, answer or retry automatically. Exact existing NOT_FOUND remains a
target-only availability fence; temporary unavailable is neither deletion nor enrollment-wide withdrawal.

An InboxRun captures the immutable Info expiry and immutable anchored ServerClock that admitted its read. Final
guarded ACK SEND requires that lease alongside writer/observer atomics and existing transport socket/Ready/method/
scope checks. Transport threads read no actor-owned current()/Info/runtime fields. A thread-safe monotonic high
water and sticky refusal reject rollback, null/invalid samples, checked estimate overflow and expiry. The estimate
uses the upper millisecond to refuse conservatively at fractional boundaries; it is not proof of server acceptance.
Expired authority retains exact confirmed bodies and owed delivery IDs. Only fresh actual Info plus a deliberate
new refresh can retry those same IDs under a new lease; an expired lease never revives.

Background network admission invokes downstream DNS/socket/transport construction only with the non-null Network
assigned to that job and matching the effective active network. Null assignment finishes through the existing
finally/generation path, without activeNetwork fallback. Explicit manual foreground work may select activeNetwork;
the prepared foreground-connection lease path and all later mismatch/network-loss checks remain unchanged.
The portable mobile admission owner is used by PhoneInboxOwner itself, not a second model of that branch.

Last full sync advances only after every required refresh chain completes, the complete aggregate debt-clear
proposal commits, and no refresh obligation remains. Missing/partial/unavailable sources keep the old timestamp
and debt. Failed/refused/stale/uncertain aggregate proposals retain their prior runtime. Timestamp publication
itself also requires a Committed display write. This owner masks an unconfirmed timestamp promotion until a later
complete refresh confirms it; physical rollback, restart durability and fsync/power-loss behavior are not claimed.
No schema, attempt timestamp or UI label change is introduced. Native/platform/job routing acceptance remains OPEN.


### Task5 FIX2 observer, input deadline and completeness boundaries

Existing list, existing task-list and existing history callbacks apply their correlated permission/profile action
before source-availability persistence or publication. Deliberate existing Continue uses the same synchronous
exact-scope withdrawal/review owners. A current correlated denial still applies after the observer's local time
lease expires; successful data requires current time as well as the observer lifetime. Exact-target NOT_FOUND
remains local unavailability and temporary failure remains non-revoking. Continue already ends its prior session
before attaching; it does not preserve that old typed queue.

Each deliberate current-input getter captures the exact immutable Info/ServerClock lease that admits it. The
getter's final paced SEND and acceptance after route IO require that lease. A transient accepted input retains the
same clock/Info lease and the actually fetched prompt's deadline. Bounded deadlines require a valid supplied expiry;
registered unlimited prompts retain Info expiry, while unknown-deadline prompts remain display-only. A deliberate
answer uses that captured clock for its request identity and checks the captured Info/prompt deadline at final SEND
after persistence or pacing. Transport threads read immutable lease data and atomics only. A later Info cannot
lend time to an old input.

Expiry consumes/refuses the stale one-shot action and completes the current caller. Callback lifetime fences are
separate from the time-varying SEND permit, preserving replacement/cancellation and a correlated completion already
posted after a valid SEND. The phone's existing Answer path clears its displayed input before dispatch and reports
an unconfirmed action by requiring fresh current input. A separate deliberate Get current input/action is required;
fresh actual Info is also required when the captured Info lease expires. No automatic getter, answer, attach,
resubmit or retry is introduced. Local refusal is not server execution, acceptance, billing or no-charge proof.
The earlier ACK lease and deliberate same-ID lost-response controls remain distinct and unchanged.

Required source completeness is independent of an empty retained target set. Missing required existing-source
methods keep the exact aggregate refresh debt and previous confirmed Last full sync. Existing task reads require
exact conversation targets, and there is no registered exhaustive task-purpose discovery method. The independent
history-purpose catalog cannot prove that every task-readable-only target was visited. Existing-task updates
therefore remain useful bounded progress with honest Partial status; they do not clear enrollment-wide gap debt
or advance Last full sync. No target IDs, conversations, history grants or new API are invented. Complete canonical
and eligible history-purpose traversals still advance Last full sync only after confirmed aggregate and display
writes. The owner-local uncertain timestamp mask is preserved; no physical rollback or crash recovery is claimed.

Source demo: explicitly refresh the Inbox, inspect retained progress and Partial/Last full sync, then use deliberate
Continue, Get current input and Answer as separate actions. Expired input refuses without automatic replacement.
Native UI, jobs, storage, network and physical acceptance remain separate open gates.

### Task5 FIX3 O5/ACK authority and current action completion

O5 and ACK callbacks use the same lifetime-first registered authority handling as the common observer reader.
A correlated permission denial posted under the current observer, writer, controller, namespace and transport
lifetime still withdraws that exact scope when actor delay crosses local Info expiry. Synchronous STOP/review
precedes fallible persistence, publication and refresh completion. Completion remains tied to that observer's
withdrawal transition and callback identity across reentrancy. An actual replacement suppresses the old callback.
The unchanged O5 producer supplies permission_denied; these controls claim no new profile_changed producer case.

ACK retains its immutable Info/clock expiry predicate at final paced SEND after exact body and aggregate
confirmation. Its callback permit is separately lifetime-based. Expired successful or ordinary O5/ACK results
finish Partial / fresh authority required without consuming a page or clearing ACK debt. A fresh deliberate
refresh still needs fresh authority. Final-SEND expiry refusal differs from denial after a valid SEND.

Eligible current-input acceptance and deliberate Answer handle authenticated route-read failure locally. They
retain observer withdrawal and storage-unavailable publication, consume/refuse the transient input conservatively,
and complete the still-current action once with null/false. The fault's own observer-epoch change is distinguished
from cancellation, a new input action, writer replacement or other lifetime change, including during publication.
Eligibility still precedes answer route IO. There is no reset, automatic getter, answer, attach, remint or retry.

Source demo: explicitly refresh; a current denial withdraws access even after local lease expiry. Get current input
and Answer remain separate deliberate actions; a storage fault reports an unavailable/unconfirmed current action.
Peer/JVM controls establish local owner ordering only. Native UI, storage, jobs, network, physical and full15
acceptance remain OPEN. The terminal FIX2 report's incomplete COMPLETE-review gate is not retrospectively closed.

## Remote wake (N9, experimental, server default off)

Inside the Relay section of the chosen profile, a collapsed "Wake a box" entry appears once Relay is READY. Opening it
reads `wake.targets` once; each Wake is confirmed and sends exactly one `wake.request` with a fresh UUIDv7 and an
`expires_at_ms` 20 s after the `ServerClock` estimate (`WakeCalls`, cap 30 s; never the device clock). Both methods are
called only when this connection's `relay.info` lists them and answer with the nine O5 error codes only. Nothing is
stored, retried or sent on connect/reconnect; a lost answer is shown as unconfirmed and a new tap is a new request.
Wake errors, including `permission_denied`, are shown and never stop, review or evict the conversation scope. `accepted`
means the host dispatched the wake, not that the box is up. The O5 `relay.info` form now decodes with the additive
`server_time_ms` a wake-enabled server adds. A wake-only grant (no `limits`) is not supported by the phone client.
