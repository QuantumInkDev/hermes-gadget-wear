# Phone Relay controller: transport owner, encrypted ledger, deliberate conversation flow (Task3)

Status: **private source, disabled by default, JVM/replay proven only.** Nothing here ran on a phone, an emulator, a live host
or a real provider. Real AndroidKeyStore behaviour and power-loss durability are NOT proven (gate V7). Canonical voice (V5),
the 90-day history cache and browser (Task4), the task inbox and notifications (Task5), the watch client, export/purge and the
real-device acceptance are later tasks and are not waived by this one.

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

### 4.2 Plaintext (`SnapshotCodec`, format 1)

Big-endian scalars, a `u16` count per collection, an `i32` length per string, no recursion and no JSON:
`"RS3\0" · u16 format=1 · u16 snapshotVersion(1|2)` · controller section · ledger turns · scope decisions · histories ·
deliveries · (version 2) capture section (`i32` version, `u16` count, records) · `"END\0"`. The aggregate always carries its
capture section explicitly: **every write passes the current captures with the turns, histories and deliveries**
(`RelayRuntime.build`); `RelayStateSnapshot.of`'s empty default is never relied on. A version 1 value (no captures) decodes and
restores unchanged, so UNCERTAIN and EXPIRED_REPORTED turns survive the bump (tested).

### 4.3 Bounds, derived from the actual fields

Strings are capped in UTF-8 bytes (identifiers 36, fingerprint 64, enumerations 64, cursors 512, opaque conversation 128,
opaque event 256, scope keys 128 characters × 3, URL 2,048, create title 512, **turn text 16,384**); counts are the state
layer's policy limits (16 enrollments, 128 turns / 1 MiB of turn text in total, 64 scope decisions, 16 histories with
≤ 2,048 known + ≤ 64 refused messages, 16 deliveries with ≤ 4,096 seen keys + ≤ 128 obligations + ≤ 256 acks, 64 captures).
Encoded maxima (bytes; tested to be exact): scope record 824, turn (without text) 1,337, scope decision 893, history
254,673, delivery 1,950,663, capture 1,237, enrollment 3,594, empty document 28.

* **Full capacity** (every collection at its limit, every string at its cap): **36,699,452 bytes ≈ 35.0 MiB**.
* **Deliberate storage cap: 8 MiB** of plaintext (`StoreBounds.PLAINTEXT_CAP_BYTES`). The cap is **below** the full capacity
  (`capIsBelowFullCapacity`); a document above it is refused as `EncodeRefusal.STORAGE_CAP` — named LOCAL backpressure, not
  full capacity. Example: sixteen delivery tables of opaque keys at their caps exceed it; eight tables of canonical keys
  (≈ 3.3 MB) fit.
* A count above its limit is `TOO_MANY(path)`, a longer string `FIELD_TOO_LONG(path)`, an unencodable one `INVALID_VALUE`.
  Nothing is truncated, dropped or silently shortened, and a decode checks every count and length **before** allocating.
* Before any commit the runtime proves the state layer can restore exactly what is about to be stored
  (`RelayStateRestore.restore` dry run); a restore-refused aggregate is never serialised. `RelayStateSnapshot.of` supplies the
  typed `TooMany`. All of this runs before a frame is written.

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
event polling from the binding's baseline (1.5 s while a turn is awaited, 8 s idle). Full 90-day cache, browser, inbox and
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
resume points — **stays owed in memory and on disk until the eviction itself is stored**; downstream history caches are Task4
(Task3 populates none). `profile_changed`: durable `REVIEW_REQUIRED`.

### 5.7 Screen, microphone, lifecycle

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
is gone. Native persistence and broader Task4 caches remain outside this evidence.

## 6. Carried OPEN to V5 (not changed here)

* **B2/N1**: a queued/FINISHING capture whose server row is purged or absent after the retention horizon is **not** proof of no
  charge; absence-only `NOT_SEALED` must not be shown as unbilled and STT is never retried automatically. The pure getter in
  `VoiceCaptureLedger` is untouched; V5 owns the reviewed correction before any canonical voice is exposed.
* **Result-vs-discard correlation**: a `not_found` of `capture.discard` must not masquerade as a result read. V5 owns it.
* M1–M6, N2–N3 and the earlier Minors keep their original scope.

## 7. Not done, not proven

Real AndroidKeyStore, device file systems, power loss (V7); any emulator/device/live host/provider; V5/V6 voice; Task4 cache and
browser, Task5 inbox/notifications, Tasks 6–8; existing-session sources; prompt answering and cancel/stop from the phone; the
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
