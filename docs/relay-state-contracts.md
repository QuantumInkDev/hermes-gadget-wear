# Relay pure state layer: consumer contracts

Package `dev.quantumink.hermesgadget.protocol.relay.state` (pure JVM, no Android, no network, no clock, no randomness
except two explicit seams). It is the temporal/state remainder of native Step 1: the retry ledger, the segment assembler,
the event/O5 delivery state and their snapshots. It sends nothing, schedules nothing and speaks nothing. A later
controller (phone/watch) supplies the authenticated transport, decides when to call, and owns persistence.

**Not proven here and still owed by consumers/later tasks:** that a snapshot is durable before a frame is written,
serialisation and encryption of snapshots, Android cache/export (Task 4), the phone/watch controllers, any real-device,
live-host or platform behaviour. Pure state is not proof of disk durability. All numeric limits below marked POLICY are a
local client choice, not a server rule.

## Time and ids

* `ServerClock.fix(info, monotonicNanos)` anchors on the full `relay.info.server_time_ms`; the O5-only `Info` has none and
  is refused (`NO_SERVER_TIME`). There is no wall-clock fallback. `read(nanos)` gives an ESTIMATE: the sampled server
  time plus the local monotonic time elapsed since the sample. It is NOT guaranteed never to lead (or lag) the server: the
  server's wall clock can step back or drift after the sample while local elapsed time keeps advancing. Negative input,
  a reading before the fix, a server time above `Long.MAX - 691200000` and arithmetic overflow are refused. A stateful
  component also refuses an instant older than one it already recorded (`CLOCK_ROLLBACK`).
* An offset is process-local. Nothing about the clock, a binding or an authority epoch is ever saved.
* `RequestIds.mint(now, entropy)` makes a canonical lowercase UUIDv7 stamped with the server-time estimate and 74 random
  bits. It is called only by `TurnLedger.prepare`; no other method accepts entropy.
* SOURCE rules (inclusive): a NEW id is expired if `timestamp < now - 604800000` or `timestamp > now + 300000`; a NEW turn
  needs `now < expires_at_ms <= now + 300000`. A retained receipt is looked up by form only (`retainedLookupId`).
* Server-issued ids (conversation, binding, message, event, ...) are never parsed, ordered or normalised. O5 final-text
  refs stay exact opaque text (`DedupKey.Opaque`) and are never coerced to a canonical id.

## Scope vs. transient authority

* `EnrollmentScope(RelayScope(endpointKey, profileId), enrollmentKey, reviewEpoch)` is a LOCAL label set by the future
  authenticated consumer; it is not a wire claim and not proof of authority. `ConversationScope` adds the conversation.
  Equal textual ids under another endpoint, profile, enrollment, review epoch or conversation are independent state.
* `RelayAuthority` / `BindingToken` are transient: one current binding per enrollment, replaced by the next attach, never
  serializable, never in a snapshot.

## Turn ledger (`TurnLedger`)

1. `prepare(draft, now, entropy)` is the only way to a new turn: mints one id, fixes `expires_at_ms`, stores the immutable
   text. The consumer persists the snapshot, then plans.
2. `planSubmit` / `planLookup` return a `TurnDispatch` (a typed call + the `CallRef` its reply must name) and, for a
   submit, `PersistBeforeSend`: the snapshot must be DURABLE before the frame is written, otherwise "never sent" cannot be
   claimed after a crash.
3. Receipt first: a turn that may have reached the server (timeout, stale authority, transport loss, transient error,
   process loss, any unexpected answer) is `UNCERTAIN`. `planSubmit` refuses it (`LOOKUP_REQUIRED_FIRST`); `planLookup`
   needs a binding made AFTER the uncertainty (`FRESH_ATTACH_REQUIRED`). Only an `ABSENT_SO_FAR` turn (a lookup said `not_found`)
   may be submitted again, with the same id, text and expiry, and only while the local estimate is before the deadline.
4. Local expiry is not evidence, and neither is the server's `expired`. **This supersedes the earlier assumption (and the
   earlier `NOT_ADMITTED` / `SERVER_EXPIRED` proof) that an `expired` answer permanently proves non-admission.** The
   shared `expired` code also answers a NEW intent whose UUIDv7 timestamp or deadline lies too far in the FUTURE of the
   server clock (`relay_store.py` `_intent` / `reserve_turn`), and the registered SDK/core fixture shows that the EXACT
   SAME params are admitted after the server clock advances by one millisecond (controller proof
   `.local/expansion/native-relay-protocol/receipt-expiry-proof-r1/report.md`, read-only). The receipt path also reads a
   different clock sample than a new admission does. So `expired` (to a submit or to a lookup) is an observation: the
   turn stays `UNCERTAIN` (`EXPIRED_REPORTED`, `lastErrorCode` = `expired`) with its original id, text, fingerprint and
   deadline; receipt-first order holds (no resubmit; a lookup needs a fresh attach); a later receipt settles it, also
   after a restore. There is **no** "not admitted" ending any more: `TurnClosure.NotAdmitted`, `NotAdmittedProof`,
   `TurnPhase.NOT_ADMITTED` and `TurnStatus.NOT_ADMITTED` were removed, and a snapshot that still carries a
   `not_admitted:*` closure is refused (`INVALID_FIELD`, `ledger.turns[n].closure`), never revived. A turn ends only by
   a receipt, by a server refusal before any effect (`invalid_request` / `unsupported`), or by the user's explicit
   `ABANDON_TRACKING` (the truthful recovery for a turn that never appears; a new turn then needs a new explicit prepare).
   A `not_found` receipt (also the answer for a journaled intent the server has not yet expired) is likewise only an
   observation: `ABSENT_SO_FAR`, at any local time. The local estimate may only refuse a resubmit (`DEADLINE_PASSED`) or
   propose a request; it never renews the deadline, never mints an id and never proves what the server holds. No lookup
   or resubmit is automatic: the consumer paces explicit lookups with backoff (later controller/UI work, not proven
   here). A found receipt settles only its exact turn and only forward; unknown stays unknown; a conflicting,
   regressing or inconsistent receipt keeps what is known and surfaces an anomaly. A known admission never regresses to
   absence or to `expired` (`ABSENT_AFTER_ADMISSION`).
   One source-valid exception to "a generation never vanishes": the core projects a queued admission handed to a running
   claim as `STARTED(that claim's generation)` (the transfer is hidden), requeues it (`queued`, generation NULL) when the
   text never reached the claim's agent, and can then cancel it: terminal `cancelled`, generation NULL, same admission
   and authority epoch. Exactly that shape (STARTED, then TERMINAL `cancelled` with no generation, same admission and
   epoch, for the call that is outstanding) settles the turn and remembers the start in
   `AdmissionFacts.priorStartedGeneration`; every other lost or lower generation, any other outcome without a
   generation, another admission or epoch, or a receipt for another request stays a conflict that keeps what is known.
   A `queued` observation between the start and the cancel is a `STATUS_REGRESSION` anomaly that keeps the start (the
   later cancel still settles).
5. `permission_denied` stops the scope and owes content eviction; `profile_changed` needs user review; both invalidate
   runtime authority. `busy` / `rate_limited` / `temporarily_unavailable` never stop a scope. Server answers that settle
   nothing hold the turn for a person.
6. Local limits (POLICY): 128 turns, 1 MiB of turn text, 256 remembered correlation ids. A full ledger refuses a new turn
   (`Backpressure`); nothing is dropped. Correlation ids must be unique within the ledger's window.

## History assembler (`HistoryAssembly`)

* Wire facts: a plain-text row is cut at nominal 8,192-byte UTF-8 boundaries moved forward to a code point end (a fragment
  may hold 8,195 bytes); a message's `segment_count` is ceil(bytes/8192) and may be huge; the SDK bounds one fragment at
  32,768 bytes and a page at 65,536; a page may be empty and still carry a cursor; cursors are opaque, HMAC-bound and
  expire after 15 minutes. **8,192 bytes is not a limit on a whole message.**
* A message is emitted only when every index 0..count-1 arrived consistently, in the authenticated page order (never by
  parsing ids or sorting timestamps). Identical duplicates are idempotent; a mismatched duplicate, count, role or
  timestamp, a skipped index, interleaving, a continuation that never began or a listing that ends mid-message
  invalidates the listing. Cursor echoes, short cycles and long empty streaks are refused.
* Local quotas (POLICY): 128 segments and 1 MiB per message (checked before allocation; an over-quota message is refused
  and recorded, so the listing may then be complete only "with local refusals"), 256 / 4 MiB uncommitted, 2,048 known
  identities, 64 refused messages, 64 consecutive empty pages, a 16-cursor memory. A full quota is `Backpressure`: the page
  is not consumed and its request stays outstanding.
* A refused message is read THROUGH, not skipped: its remaining fragments must arrive in order with the metadata of the
  first one (bounded state: id, role, timestamp, count and the next expected index; no text is kept), and each advancing
  index is cursor progress, so a refused message spanning more than 64 legal `limit` 1 pages no longer stalls the
  listing. A skipped or out-of-order index (`MISSING_SEGMENT`), changed metadata (`MISMATCHED_METADATA`), another message
  beginning first (`INTERLEAVED`) or the listing ending inside it (`INCOMPLETE_AT_END`) still invalidates; a repeated
  already-seen index is an idempotent duplicate; pages of only duplicates still stall. A page ending inside a refused
  message is never a resume point. After an abort or a restore the message is read again from its first fragment.
  **Quota-driven invalidations are permanent for that stream until the policy changes:** more than 64 refused messages
  invalidates the listing as `TOO_MANY_REFUSED`, and an explicit rebuild reads the same stream and reaches the same
  place; a message over the local segment or byte quota is never delivered (it stays absent, never truncated).
* Staged vs committed: `accept` stages messages; only `commit(through)` (after the consumer stored them) moves the resume
  point to the latest clean page boundary at or before `through`. `abortUncommitted` rewinds. After an invalidation only
  an explicit `restart` claims a new beginning and records why (`ListingProvenance.after`).

## Event and O5 delivery (`EnrollmentDelivery`)

* Events are applied only to the current token's exact conversation, binding id and replay epoch; a stale binding, foreign
  epoch/event, stale sequence or unexplained sequence jump is rejected or drops the session before state changes. A gap
  or delete tombstone may skip sequences.
* `stageEvents` stages; the read cursor (`committedSequence`, what the next `relay.events.read` acknowledges), the dedup
  table and the owed refreshes change only on `commitEvents` after the consumer stored the content. `abortEvents` leaves
  everything as it was.
* A gap, `snapshot_required`, a delete, an epoch the server does not recognise, a larger or unknown O5 `gap_generation`
  or a superseded copy owe a history/task refresh; none is an execution effect. Every attach owes a refresh too.
* O5 copies are display only. Stored deliveries are owed an acknowledgement only after `commitOutbox`; `planAck` carries
  at most 50 and repeating it is safe; only acknowledged ids that the request carried are removed.
* Local limits (POLICY): 4,096 identities, 256 unacknowledged ids, 128 refresh obligations (widened to enrollment-wide,
  never dropped). An ordinary full table is `Backpressure`; only an explicit `retireOlderThan` relaxes duplicate detection.
  After complete current-page validation, a single exact conversation tombstone can stage its existing deletion signal
  even at that bound. `commitEvents` still refuses any accounting growth above 4,096; the controller consumes the signal
  through its already durable selected identity, without committing new seen IDs, cursor or ACK facts. Seen duplicate
  terminal identities retain the existing page-level meaning. Nested conversation/task payload targets must match their
  event scope. Mixed terminal pages are rejected as producer-shape hardening: the current registered producer replaces
  its backlog with ONE tombstone. This does not add a new wire field or require `snapshot_required` for legacy singles.
* The controller owns at most one validated ordinary capacity reply and its exact read/token/session/flow. Zero eligible
  keys leaves explicit unconsumed capacity, without repeated no-op writes or another event read. Existing seven-day age
  policy makes room through a confirmed aggregate proposal, then the same reply is restaged by the polling owner. A
  refused/failed proposal retains the original answer and all keys. Wake timing follows existing key ages and Info
  maintenance; pending-turn reconciliation remains separate. Session/flow replacement discards the held page and aborts
  only its exact transient read (no cursor/seen/ACK change). Same-effective Info readiness schedules a fresh current read
  after LoadingInfo has invalidated the old one. No progress is promised while no safe room exists.
* Event DTOs carry no execution or authority generation, so the generation guards implemented are the listing generation
  (history), the O5 gap generation, and the binding token's identity and replay epoch.

## Snapshots

`RelayStateSnapshot` (version 1) is immutable, bounded, and holds only durable committed state in wire spelling. Restore
(`RelayStateRestore.restore(snapshot, freshInstant, emptyAuthority)`) re-validates everything and refuses with a reason
and a field path (never a value): a request id must carry exactly the server-time stamp it was prepared with, text must
match its fingerprint, evidence must be consistent, limits hold. A restored turn that may have reached the server is
`UNCERTAIN(PROCESS_LOSS)`. A history listing with committed content but no cursor is invalidated, not re-read from its top.
Never saved: bindings, clocks/offsets, calls/correlations, staged content, fetched prompts/controls, send/playback flags.

Bounds are enforced twice and never by shortening. `RelayStateSnapshot.of(ledger, histories, deliveries)` returns a
`SnapshotBuild`: `Built(snapshot)` or `TooMany(path, limit)` for more than 16 histories (`histories`), 16 deliveries
(`deliveries`) or 64 ledger scope decisions (`ledger.scopes`), reading the caller's collections element by element and at
most one past the limit before anything is mapped or copied. The live ledger never drops a scope decision (a `STOPPED`
scope that owes content eviction stays so); when it holds more than 64 the consumer must reactivate or evict some before a
snapshot can be built. The public snapshot constructors keep at most limit + 1 entries (an explicit invalid-oversize
value) and `RelayStateRestore.restore` refuses `size > limit` with `TOO_MANY` and the same paths, so no restorable prefix
of a longer input exists. `AdmissionFactsSnapshot.priorStartedGeneration` (default null) was added; the snapshot version
stays 1 (plain values, no serialisation format exists yet), and a non-null value is accepted only for a terminal
`cancelled` fact without a generation.
