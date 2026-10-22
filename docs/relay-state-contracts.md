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
4. Local expiry is not evidence. `NOT_ADMITTED` needs the server's own `expired` answer, which compares the intent's expiry
   with the server's clock. A `not_found` receipt (also the answer for a journaled intent the server has not yet expired)
   is only an observation: the turn becomes `ABSENT_SO_FAR` and stays unresolved, at any local time, with its original
   id, text, fingerprint and deadline, and a later receipt or `expired` still resolves it (also after a restore). The
   local estimate may only refuse a resubmit (`DEADLINE_PASSED`) or propose a request; it never renews the deadline,
   never mints an id and never proves what the server holds. No lookup or resubmit is automatic: the consumer paces
   explicit lookups with backoff. A found receipt settles only its exact turn and only forward; unknown stays unknown; a
   conflicting, regressing or inconsistent receipt keeps what is known and surfaces an anomaly. A known admission never
   regresses to absence.
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
  never dropped). A full table is `Backpressure`; only an explicit `retireOlderThan` relaxes duplicate detection.
* Event DTOs carry no execution or authority generation, so the generation guards implemented are the listing generation
  (history), the O5 gap generation, and the binding token's identity and replay epoch.

## Snapshots

`RelayStateSnapshot` (version 1) is immutable, bounded, and holds only durable committed state in wire spelling. Restore
(`RelayStateRestore.restore(snapshot, freshInstant, emptyAuthority)`) re-validates everything and refuses with a reason
and a field path (never a value): a request id must carry exactly the server-time stamp it was prepared with, text must
match its fingerprint, evidence must be consistent, limits hold. A restored turn that may have reached the server is
`UNCERTAIN(PROCESS_LOSS)`. A history listing with committed content but no cursor is invalidated, not re-read from its top.
Never saved: bindings, clocks/offsets, calls/correlations, staged content, fetched prompts/controls, send/playback flags.
