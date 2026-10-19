# Quantum Relay: canonical conversations and task state

Date: 2026-10-18. Status: execution plan; no implementation or acceptance claimed.
Scope: approved N1/N2, depending on O5 foundations. Server first; a native integration
contract is included. This plan does not authorize deployment, providers or device work.

## Outcome and first delivery boundary

Two independently enrolled devices in one explicitly provisioned sharing group create,
attach to and submit successive turns to **one real Hermes routed conversation**. Both
read the same committed transcript and task admissions after reconnect and server
restart. Compression advances its physical transcript while preserving the logical
admission owner. Separate groups/profiles cannot observe or mutate it. The first slice
ends at Task 6 below, including genuine task state; discovery alone is not a delivery.

Tasks 7–10 complete history/search/metadata/delete, prompts/controls, existing-history
opt-ins, task notifications and the native handoff. Keep capabilities off individually
until their gates pass. Physical phone/watch continuation remains a subsequent native
acceptance gate, not something two synthetic clients prove.

Authority: [approved design](../specs/2026-10-18-relay-expansion-design.md) and
[server contract](../../relay-server-contract.md). All existing contract quotas and
privacy boundaries apply. No client supplies a host, filesystem path, core principal,
profile selector, native provenance, capabilities or administrative operation.

## Source baseline and concrete seams

SDK stock: `323e3303ab68981f810fc3208119cd8a22e64af0`; existing extension patches:
`8a1d8541661a218acb70b8e4dec46fbb30a9eff2`; accepted O5 storage checkpoint: `d097717`.
Consume the reviewed foundations integration head, including its grants/service work,
without developing against a moving working tree. Core HEAD:
`e6f57427c198cfd96854522b7a9159eb007e13c2`. The reviewed runtime source has local
modifications; HEAD alone is not its content identity. Private reports preserve hashes.
Build a new isolated core fixture from the recorded runtime snapshot, retain its baseline,
and export only new hook changes relative to that baseline. Never bundle pre-existing
local edits into an upstream patch. Verify each rollout host's content separately later.

| Actual source seam | Verified behavior and required consumer |
| --- | --- |
| SDK `plugin/adapter.py`, `_source`, `_message_id`, `_session`, `send` | Stock chat/user are the device ID; transport IDs participate in message identity; logical-chat fanout must be a new route, not `_session(logical_chat)` |
| Core `gateway/run_adapters.py`, `_wire_adapter_handlers` | Real adapter callback establishes `native_callback` provenance; use this wiring, never construct provenance in the plugin |
| `gateway/session.py`, `build_session_key` | A DM with chat ID omits user ID from its route key; distinct authenticated senders can share one logical Gadget DM |
| `gateway/session_authority.py`, `register`, `_reserve_route`, `_publish_route`, `logical_owner`, `physical_target` | Reserve/publish the route and identify root/tip; add a public async registration wrapper using `tracked_write` |
| Same file, `_admit_native`, `_admitted` | Native principal is `messaging:` plus compact JSON of `[source.profile, platform, chat_id, thread_id, user_id]`; committed receipt exists but the adapter gets only `_gateway_accepted` |
| `gateway/session_envelope.py`, `prepare_native`, `check_native_route` | Authenticated source, callback provenance, current authorization and route must survive capture and execution; do not replace these with a generic viewer submission |
| `hermes_state_runtime.py`, `admit_session_input`, `find_session_admission`, `get_session_admission` | Transactional exact-identity admission and digest conflict detection; `find_session_admission` alone does not find retired terminal tombstones |
| `hermes_state_terminal.py`, `identity_key`, `terminal_admission`, `retry_terminal_admission` | Retired receipts and stored results are real recovery data; expose a bounded authenticated lookup by original native request identity |
| Authority `attach`, `receipt`, `respond`; `gateway/session_events.py` | Live bounded snapshots/replay; receipt requires original principal or control capability; replay epoch/sequence is not durable identity |
| `gateway/session_pending_controls.py` | Real approval/clarify registration, generation and resolution; `timeout_s` alone does not expose an authoritative absolute deadline or free-text eligibility |
| `hermes_state_messages.py`, `get_messages` | Ancestor reads dedupe display generations but load all ancestor payloads; cannot use that path to promise bounded history |
| `hermes_state_timeline.py`, `get_session_messages_around` | Useful bounded display-row pattern; timeline itself is human prompt previews, not complete user/assistant history |
| `hermes_state_search.py`, `search_messages` | Source/role filters exist, no authorized session-ID set; filtering its global results after pagination is forbidden |
| `gateway/session_mutations.py`, `mutate_session` | Real async revision-guarded rename/delete with receipt/retirement; concrete `SessionAuthority` has no `create` or `mutate` method |
| SDK `plugin/relay_outbox.py`, `Outbox.tx`; core `send_final_ledgered` | Reuse durable O5 storage/transactions and stable final identity; current capture excludes `chat_id != user_id`, so shared routes need explicit integration |

The new hooks named below are proposals, not claims that these APIs already exist.
No dashboard proxy, native-owner impersonation, `session:operator`, direct core SQL writes,
monkey patch, or transcript-copy continuation is part of the implementation.

## Rulings to implement

### Identity, grant evolution and storage

Persist one opaque random server UUID in the existing profile-private Relay store. It
names this enrollment authority independently of hostnames and sockets; preserve across
normal restart and rotate on a deliberate authority reset. Keep existing public
`profile_id` and the core profile identity separately mapped. Scope every private record
by server UUID + profile UUID. A group UUID is provisioned locally; a conversation UUID
and `relay-<UUID>` chat are allocated by the server. The registry uniquely binds:

`(server, profile, group, conversation) -> logical_chat, core_profile, logical SessionRef,
physical tip, compression lineage, origin, revision, lifecycle`.

Never take a registry target from the client. Store no other device's key or grant in a
response. Each source keeps the actual enrolled device `user_id`; never substitute the
creator's identity on a later watch turn. `source.profile` is whatever the authenticated
runner establishes, not Relay's public UUID. Registry recovery verifies the route belongs
to this adapter/profile before adopting it. A missing/rotated unrelated route is a gap or
read-only row, never silently a new session under the old conversation ID.

Private grant schema 2 extends foundations schema 1. Schema 1's exact shape is currently
`{schema,profile_id,generation,devices}`, with each device containing
`{key_sha256,expires_at_ms,outbox}`. Read it as **outbox-only**, with no inferred sharing
membership or history privilege. Schema 2 retains enrollment-key binding and adds explicit
server UUID, per-profile existing-history/task opt-ins, group UUID and allowlisted operation
permissions per device. An optional `relay:receipt:shared` permission allows another
current group member to read a receipt, never to impersonate its author. Keep limits of
64 KiB and 128 device grants; reject unknown schema/fields and invalid replacements closed.
Migrate via explicit atomic maintainer tooling with backup and generation increment.
Preserve the old O5 owner hash and valid opt-in/ACK identities; migration must not opt a
device into recovery. Rollback to a schema-1 reader refuses schema 2, without resetting it.
Public negotiation stays optional `hermes-gadget.v1`, `relay.version=1`.

Extend the existing O5 SQLite store through versioned migrations and its transaction/
private-directory code; do not introduce another DB, framework or writer. New bounded
metadata tables: registry/lineage, request journal, message identity aliases, task
projections, durable event identities, mutation intents and scoped history grants. Reuse
existing deliveries/dedup identities. Store canonical text in core; Relay maintains bounded
projections and recovery copies, not a second canonical transcript. Keep O5's existing
36 MiB total disk budget and 16 MiB DB ceiling initially; capacity exhaustion refuses new
mutations before core admission and never evicts unresolved requests or live registry
identity. Reserve space for resolving already admitted operations; exercise SQLITE_FULL
at every phase. Rebuildable message aliases may be pruned only when deterministic IDs
remain recoverable. Any later capacity increase needs documented bounded accounting.

### Admission and crash protocol

1. Authenticate enrollment, pairing, current key hash, schema/generation, operation and
   registry group; validate binding/authority epoch and all request bounds. Reserve an
   intent in the shared Relay DB before calling core. Unique key is
   `(server,profile,device,key_generation,request_id)` across operation and conversation;
   changed operation/target/content is `conflict`. Digest includes immutable text,
   attachment handles, expiry and operation, but excludes ephemeral binding/epoch.
2. Journal the exact server-built source, stable channel context and normalized event
   inputs used for the first admission. Derive native `message_id` as SHA-256 of that
   namespace; keep it unchanged on retry. No transport/session ID participates.
3. Submit through the real Gadget adapter `handle_message` and real runner callback.
   A new public core `NativeAdmissionOptions` dispatch option supplies an in-process
   commit guard and committed-receipt observer, bound to this actual event/adapter.
   It is not a wire field and cannot mint callback provenance. Runner rewrites must
   preserve the option only through their authenticated event carry path.
4. Core composes the guard with its existing admission gate. Immediately before a NEW
   admission insert, recheck expiry, active grant/key generation, registry route and
   intended logical owner. Exact accepted replays can return the old receipt even after
   submission expiry, but still require current read/receipt authorization. `_admitted`
   publishes an immutable committed receipt to the observer before scheduling. An
   observer failure cannot undo admission or lose scheduling/recovery.
5. Add `lookup_native_admission` on the authenticated adapter/authority bridge: exact
   native principal + request identity + intended lineage, checking live rows and retired
   terminal identities. It returns receipt/digest match or definitive absent/conflict;
   never read a receipt by arbitrary unscoped client admission ID. It must survive
   compression and canonical retirement. This is essential even with a receipt callback.
6. On receipt, atomically record core admission/ref/generation and initial task/event
   projection in Relay DB, then answer. If the write/response fails after core commit,
   leave the intent unresolved. Reconcile it by exact core lookup on retry/startup.
   If a commit remains in flight, return `temporarily_unavailable`/unknown, not absent.
   If definitively absent and unexpired, only a deliberate same-ID retry may admit it.
   Expired/unknown requests are never automatically resubmitted. Socket closure detaches
   the client waiter; it never cancels the tracked core admission or generation.

Use UUIDv7 request IDs for the new durable mutation methods. Amend the reserved method
contract to state this before native implementation; they are UUID strings within v1,
but this is a new validation requirement. Reject new IDs older than seven days, dates
more than five minutes ahead, or a changed expiry under the same ID. A fresh turn still
requires expiry after server time and at most five minutes ahead. Retain tombstones for
at least seven days after terminal settlement; an old timestamp remains expired after
purge. Receipt reads can report a retained old admission. This closes the old UUIDv4
re-use ambiguity without retaining an unbounded spent-ID set. Return optional server time
in discovery for native clock checks. Creation/metadata mutations use UUIDv7 creation time
for their replay-age bound even though their existing DTO has no expiry field.

Reject command-classified text before the generic handler can run `/new`, `/stop` or
another side-effecting command outside durable admission. Use core's actual command
classifier (ordinary `/tmp/...` text need not be a command). Actions and controls use
explicit methods; this slice admits only reviewed text and empty attachment lists.

### Binding, events and task semantics

One binding per authenticated transport. `attach` checks current membership, creates
an actual authority subscription with a server-derived Principal for that device, and
returns the contract Binding. Use only the capabilities required for the operation and
never issue them to the client. Register a bounded `write`/`close` transport in
`authority.events`; it projects safe events and retires a binding on overflow. Bound it
to 256 frames and 1 MiB, with a reserved gap indication. Core attach currently materializes
history; Task 2 adds a bounded snapshot option, used by this bridge instead of loading a
full history. Detach calls authority detach with no cancellation/end reason and removes
only that device's subscription.

Live delivery resolves logical chat through the registry and **current** subscribed
device grants before every emission. Stable final identity comes from core's ledgered
final hook; associate it with the canonical message/task event so live, history and O5
recovery do not invent unrelated message IDs. No live frame contains raw core event,
tool output, reasoning, system messages, paths or another device's identity. No audio,
action invocation or approval response enters replay. Shared route text does not silently
trigger legacy `send` TTS on every member; explicit foreground playback belongs to later
native audio integration. Stock device-chat behavior remains unchanged.

Durable message identity uses server/profile, logical lineage and the canonical display
row identity, with persisted aliases where compression preserves a display row in a new
physical segment. Never dedupe by content/time. Repeated equal messages are distinct.
Event UUIDs are persisted/deterministically reconstructed from their source identity;
core replay epoch/sequence is only a bounded cursor. On restart, compression, eviction or
gap, reattach and page a fresh snapshot, merging by durable IDs. Bindings do not survive
reconnect. Receipt identity does. Do not return full raw history in an attach result.

One Relay Task corresponds to a real committed core admission. Its run identity is null
while queued, then an opaque deterministic mapping of the actual execution stamp
`(core profile, logical session, owner_epoch, execution_generation, admission_id)`.
This is a contract clarification: Task.run_id must be nullable before a run exists.
Never fabricate a running ID to fill that field. Preserve any separate real worker/task
provenance when projecting existing native tasks; do not relabel it as Relay-origin.

| Evidence from core | Relay state |
| --- | --- |
| Queued or started, no live supported prompt | `working` (queued/running detail may be shown, no percentage) |
| Current-generation pending approval/clarify in actual controls | `needs_input` |
| Settled terminal `completed` | `finished` |
| Settled `failed`/`rejected` | `failed` |
| Confirmed terminal `cancelled`/`interrupted` | `finished` with explicit cancelled/interrupted outcome, never success copy |
| Unknown execution or unresolved recovery/settlement evidence | `unknown` |

Add optional `outcome` to Task, preserving Receipt's real outcome; existing v1 readers
ignore new response fields. Keep `steering`/`steered` provenance; no synthetic completion
from socket closure, timeouts, final-looking prose or idle state. Reconcile durable task
projections from admissions/results on startup and before returning terminal state.
Prompt expiry removes actionable NeedsInput, then recompute from actual admission state.

## Execution tasks and ownership

Each owner below is a logical implementation responsibility; controller schedules work
and review. No concurrent writes to the same SDK/core files. Each task starts with the
named failing tests, implements the stated boundary, then runs its targeted gate. All
checkboxes remain open until evidence is recorded.

### Task 1 — SDK owner: evolve private state and immutable request identity

Depends on reviewed O5 foundations grants/service/storage integration.

- [ ] Add migrations and repository operations to `plugin/relay_outbox.py` or a small
  `plugin/relay_store.py` facade using the same `Outbox.tx`; keep O5 API compatibility.
  Add `plugin/relay_registry.py`, extend `relay_grants.py`, `relay_service.py`, and the
  existing local setup/CLI tooling for schema 2. No device-key duplication.
- [ ] Implement registry allocate/reserve/ready/tombstone states, request intent/digest,
  UUIDv7 age rules, namespace migration and admission-completion capacity reservation.
  Creation crash between registry intent and core registration must recover the same
  server-created logical chat; never allocate a second UUID on retry.
- [ ] Add `tests/test_relay_registry.py`, `test_relay_grants_v2.py`,
  `test_relay_request_journal.py`, `test_relay_store_migration.py`. Cover legacy schema,
  enrollment replacement, corrupt file, concurrent reservation, changed payload/target,
  disk-full rollback, seven-day tombstones and preservation of O5 ACK/opt-in records.
- [ ] Gate: targeted SDK tests with warnings as errors; full existing O5 suites remain
  passing. Windows support remains disabled without actual private-directory/file ACL
  inheritance/other-user denial evidence; POSIX chmod does not satisfy that gate.

### Task 2 — core owner: supported admission, bounded read and snapshot hooks

Depends on Task 1's exact bridge contract; can prepare core fixtures separately.

- [ ] Implement public async `register_native_route(source)` around existing reserve /
  `tracked_write` / publish. Require source to belong to the registered adapter and the
  owning profile; retain normal admission/retirement gates. Consumer is Relay create.
- [ ] Add the trusted `NativeAdmissionOptions` path to `gateway/platforms/base.py`, runner
  handler wiring and `session_authority.py`. Implement before-insert guard, commit receipt
  observer and exact native lookup including retired receipts. Keep ordinary adapters
  behaviorally identical when absent. Do not call private admission methods from plugin.
- [ ] Add bounded attach/history projection in `gateway/session_authority.py` and a small
  `gateway/session_read_projection.py` over existing state display-row machinery. Return
  stable row identity, role/text/created time, lineage watermark and next cursor position.
  Filter user/assistant display rows before limit; omit compression summary/reasoning/tool
  rows and preserve identical separate human turns. Fetch bounded payload pages; do not
  use `get_messages(include_ancestors=True)`'s unbounded materialization. Query-byte limit
  and cancellation/deadline apply even to long histories.
- [ ] Read-only receipt/task projections must authorize current registry grants first.
  Shared receipt reads use the bridge's narrow exact-record projection after explicit
  `relay:receipt:shared`; do not grant `session:control` merely to bypass authority's
  original-author check, or construct a Principal impersonating that author.
- [ ] Add `tests/gateway/test_relay_native_admission.py`,
  `test_relay_committed_receipt.py`, `test_relay_bounded_projection.py`. Real runner,
  authority, registered Gadget adapter and tmp-path SessionDB are required; fault only
  external execution/provider boundary and commit/response timing. Assert guard executes
  for NEW commits, not expired exact receipt reads; delayed commit after disconnect,
  retired receipts, duplicate concurrent submit and changed target cannot rerun work.
- [ ] Gate: core canonical runner commands below, actual Gadget inheritance/import test
  (not a fake `gateway` module), and capability-off existing adapter regression suites.
  Export one focused core patch against the private runtime baseline; test clean pinned
  compatibility separately, never discard runtime local changes to make it apply.

### Task 3 — SDK bridge owner: create, attach, submit, receipt and authorized delivery

Depends on Tasks 1–2 and O5's actual-core final-recovery hook acceptance.

Source-backed amendment: Task3 preflight reproduced two missing producer seams. Its
isolated owner may add narrowly scoped authenticated scalar canonical-final identity
to exact receipt lookup and trusted final-delivery context, resolving the actual
persisted-turn reference rather than content/time/latest-row guesses. Unrecoverable
identity remains explicitly unavailable; no raw result is exposed. The same owner
may align delivery-obligation eligibility with the actual command classifier so
admitted ordinary `/tmp/...` text retains its final ledger, while real commands and
ephemeral messages remain excluded. Review these core deltas together with the SDK
bridge before integration. No installed source, live capability or broader core
control change is authorized by this amendment.

- [ ] Add `plugin/relay_core.py` (version-bound in-process adapter),
  `plugin/relay_conversations.py` and `plugin/relay_events.py`. Register capability names
  only when exact required hooks are present and tested. Expose create/attach/detach,
  submit/receipt/history page and bounded event read. Reject nonempty attachment IDs as
  unsupported until the attachment adapter is implemented.
- [ ] Resolve the owning `gateway_runner.session_authority`, use actual core profile
  mapping, and reserve the logical route through Task 2. Persist actual root/tip/ref
  returned by core; attach through actual authority subscription. Maintain generation /
  epoch / binding guards and revoke subscriptions when grants change.
- [ ] Dispatch the recorded event through real adapter wiring and capture its receipt;
  run admitted work under tracked lifetime independent of the WebSocket. Recover intent
  uncertainty by exact lookup before retry. Use real core receipt on the submitting
  device; explicit shared-read projection for the other device.
- [ ] Extend `adapter.py` logical-route send/draft/final dispatch carefully. Recheck each
  recipient, emit scoped safe events only, and integrate canonical final capture with
  O5's existing `send_final_ledgered` consumer. Current stock capture's
  `chat_id == user_id` guard must remain for stock lanes and get a separate explicit
  registry-owned path; do not simply remove it. Durable offline recipients require their
  own current enrollment/grant/O5 opt-in. Failure must preserve core delivery obligation.
- [ ] Add SDK `tests/test_relay_conversations.py`, `test_relay_bindings.py`,
  `test_relay_shared_delivery.py` for route isolation, independent keys, fanout and stock
  behavior; integration tests in Task 6 must prove the actual core path.

### Task 4 — projection owner: durable history identity and reconciliation

Depends on Tasks 1–3.

- [ ] Implement `plugin/relay_history.py`: project Task 2 bounded reads into Message DTOs;
  persist canonical row aliases/final-ledger association and watermark in shared storage.
  Serve up to 50 items, ≤32 KiB text segments, final envelope ≤64 KiB after JSON escaping.
  Segment cursor resumes inside a message without dropping tail text. An oversize single
  DTO is a named failure; never silent truncation. Authorization precedes any read/limit.
  History is the explicit method-specific exception to the Task3 general8-KiB DTO limit.
  Advertise `history_page_bytes=65536`/`history_text_bytes=32768` only with the tested
  implementation; retain8 KiB for every other conversation response/event. Segments use
  the same canonical `message_id` plus `segment_index`/`segment_count`; compute a stable
  partition against the actual escaped envelope before paging, rather than silently
  resizing a prior segment when the remaining page space changes.
- [ ] Cursors bind profile, server, subject/key generation, grant generation, conversation,
  query, lineage watermark, position and 15-minute expiry; MAC them with a private server
  secret. Cap encoded cursor at 512 bytes. Compression or deletion invalidates stale
  cursors with `snapshot_required`; return no unauthorized count/snippet.
- [ ] Reconcile before live publishing: commit event/message identities first, then send.
  If observation was missed, scan bounded core admissions/message pages from the durable
  watermark. A core replay gap cannot fabricate a gap-free event sequence. Keep transcript
  message IDs stable when O5 delivery is ACKed/expired or Relay projection is rebuilt.
- [ ] Add `tests/test_relay_history.py`, `test_relay_projection_recovery.py`, covering large
  multibyte/escaped bodies, repeated equal text, lineage rotation, row aliasing, same event
  via live/history/outbox, stale cursors, revocation mid-page and restart mid-projection.

### Task 5 — task owner: actual admissions and generation-bound task projection

Depends on Tasks 2–4.

- [ ] Add `plugin/relay_tasks.py`, expose tasks.list/get for Relay-origin admissions only.
  Map committed queued/start/unknown/settlement and existing controls snapshot to the table
  above. Preserve real admission and run identities; no polling heuristics from chat text.
- [ ] Add bounded persisted task/event snapshots and reconciliation watermark in the shared
  store. Cross-box inbox remains a native union of independently authorized profile
  results; no host-wide service or cross-profile principal is introduced.
- [ ] Add `tests/test_relay_tasks.py` and actual-core
  `tests/gateway/test_relay_task_lifecycle.py`: queued → started → terminal completed,
  failed/rejected, unknown across owner loss, recovered result, prompt appearing/resolving,
  and no cancellation on socket close. Never map an unknown task to failed to clear UI.
- [ ] Gate: real core settlement evidence behind each task state, source-bound IDs in test
  receipts, and permission/read filtering before pagination. Prompts may be display-only
  until Task 8's authoritative deadline and response tests pass.

### Task 6 — integration owner: complete first two-device slice

Depends on Tasks 1–5. This is the first release gate for N1/N2 server methods.

- [ ] Add `tests/gateway/test_relay_two_device_continuation.py` to the isolated core fixture.
  Import the actual SDK GadgetAdapter after registering its platform as plugin setup
  requires. Initialize actual GatewayRunner / SessionAuthority and actual hub enrollment
  validation with two synthetic independent keys. Use temporary HERMES_HOME before imports,
  tmp-path DB/files, no ambient credentials/services, and no provider calls.
- [ ] Use the real runner's adapter wiring and dispatch, not manually supplied
  `native_callback` or direct private `_admit_native`. At the provider/turn boundary use a
  deterministic executor that records its received history and writes through real
  transcript/result APIs. Run real claim/settle/publication/delivery paths.
- [ ] Phone creates C, attaches and submits A. Watch attaches C, reads A/final and submits B.
  Assert B's executor reads A's prior canonical history and both admissions have the exact
  same logical owner/physical target before compression. Inspect actual persisted native
  envelopes: A.user_id is phone, B.user_id is watch; principals/keys are distinct. A copied
  transcript in a second session fails this test even if UI text matches.
- [ ] Restart the fixture with preserved temporary core+Relay state. Reattach both, retrieve
  same durable messages/tasks; trigger actual `publish_compression_child` and routing
  `advance_compression_session` publication with synthetic summary content (no provider).
  Next turn uses that physical child and the original logical admission owner; full human
  history retains stable IDs across the lineage. Check branch/reset is not mislabeled
  compression continuation.
- [ ] Kill/fault after core commit but before Relay journal update and before response;
  retry same UUID after restart and prove one admission and one execution. Delay receipt
  delivery beyond expiry: accepted result remains readable. Change text/target with same
  UUID: conflict. Delay first admission past expiry: no commit. Lose ACK: no duplicate final.
- [ ] Negative cases: third enrolled outsider, same group UUID on different profile,
  revoked/re-enrolled key, grant change during queued operation, stolen binding/cursor,
  stale epoch/generation, overflow/gap, server store unavailable and canonical route reset.
  Exercise Working/NeedsInput/Finished/Failed/Unknown from real state transitions.
- [ ] Publish fixture receipt with exact hashes, physical lineage IDs (synthetic only),
  admission IDs and execution counts. Independently review crash/auth paths. Do not mark
  physical acceptance, existing-history support, metadata/delete or controls complete here.

### Task 7 — history owner: list, search, bookmarks, titles and canonical delete

Depends on Task 6.

- [ ] Complete conversations.list with query/bookmarked filters. Add a core read projection
  search API accepting the **server-derived authorized session/lineage set**. Apply that
  set in the query before counts, ranking, snippets and pagination; reuse FTS/display
  normalization without logging private query content. Do not post-filter global search.
- [ ] Titles and bookmarks are shared group metadata. Title changes use real
  `gateway.session_mutations.mutate_session` with `operation=rename`; bookmark lives in
  Relay store. Public revision is a monotonic registry revision; keep a separate captured
  core runtime revision. Serialize per-conversation mutation intents, call core using its
  exact revision/idempotent receipt, then finalize Relay metadata/revision. Recover a
  core-committed/Relay-uncommitted mutation before accepting another update. Return conflict
  on stale external core revision; never hide lost updates with unconditional writes.
- [ ] Canonical delete requires `relay:delete`, registry ownership, expected revision and
  no queued/running/unknown admission or outstanding unresolved intent. Use actual core
  `mutate_session(operation=delete)` route retirement, not direct SQL or archive.
  Reconcile core deletion receipt before tombstoning registry; retain spent request IDs,
  purge matching Relay payloads/outbox/search projections, retire subscriptions and emit
  tombstone invalidation. Crash/retry cannot recreate the route. Canonical retention
  policy otherwise remains unchanged. Device-cache deletion is a distinct native action.
- [ ] Add SDK `test_relay_search.py`, `test_relay_metadata.py`,
  `test_relay_canonical_delete.py` plus core `test_relay_scoped_search.py` and
  `test_relay_routed_mutations.py`; cover hidden unauthorized best matches, stale revision,
  cross-member concurrent edits, live/unknown deletion refusal, compression descendants,
  external core edits and both sides of the core/Relay mutation crash window.

### Task 8 — controls owner: current prompt answers and real cancellation

Depends on Task 6; separate optional methods, not a prerequisite to history reads.

- [ ] Extend core PendingControls registration/snapshot to expose authoritative absolute
  expiry and whether clarification accepts free text. Derive deadline from the underlying
  waiter at registration, preserve it across snapshots, and enforce at response under
  the same resolving lock; never reset timeout on attach. Secret/sudo and unsupported
  multi-select/edit prompts remain native/display-only. This small core addition has the
  actual Relay prompt consumer and does not broaden privilege.
- [ ] Project at most eight choices, 4096-byte prompt/256-byte choices, 4000-byte free-text
  response. A prompt that cannot fit is non-actionable, not silently changed. Bind response
  to current transport subscription, epoch, conversation, actual generation, prompt ID,
  current `relay:respond`/`relay:approve`. Map clarify `{text}` or selected `{choice}` to
  core `{answer}` only when the current prompt allows it; approval uses `{choice}`.
- [ ] Call authority.respond with actual device Principal and exact kind; reject expired,
  stale or unsupported prompts. `already_resolved` is not proof this answer was accepted.
  Keep only bounded response fingerprint/result receipts for duplicate/conflict detection,
  never response payload in outbox or an automatic resend queue. After a crash with an
  uncertain response, fetch current prompt state; do not replay an approval/action.
- [ ] Enable turn.cancel only after exact queued cancellation and running interruption
  paths are tested against authority.cancel_queued/interrupt with admission and generation
  fencing. No blanket `/stop`, no cancelling the next generation on a late click.
- [ ] Add `test_relay_prompt_controls.py`, `test_relay_cancel.py`, and core
  `test_relay_prompt_deadlines.py`: competing devices/native responses, expiry boundary,
  reconnect, revoked permission, mismatched kind, lost response and delayed generation.

### Task 9 — provenance owner: explicit existing-history and task opt-ins

Depends on Tasks 6–7; keep default filters Relay-origin only.

- [ ] Add explicit per-profile maintainer opt-in and per-device `history:existing` /
  `tasks:existing`, plus server-defined permitted sources/session scope. No enrollment or
  group membership silently grants these. Disabling opt-in immediately withdraws rows,
  subscriptions, notification eligibility and cached-scope authorization.
- [ ] Project supported existing sources through bounded read-only state adapters. Preserve
  source/origin and real task/execution identifiers as optional DTO fields; do not claim
  imported history ran through Relay. Existing LOCAL and unsupported/nondelegated native
  sources report `can_continue=false`. Attach may return a read-only history binding;
  submit/respond/control remain denied. Exact source provenance is internal and sanitized
  for display, never client authority.
- [ ] Implement a separate upstream per-session delegation primitive before enabling any
  foreign LOCAL continuation: owner-issued, profile+session+device-key subject, capability
  allowlist, expiry/generation, revoke, and checks at attach/admission/response/control and
  restart. Preserve source ownership. Test legitimate owner issuance using synthetic local
  owner fixture, wrong owner/child/branch/profiles, compression and revocation. If the core
  cannot support a source, retain read-only; an explicit handoff is a distinct conversation.
- [ ] Native task listings (including Kanban) retain their actual adapter permissions and
  provenance. Do not call dispatch/run helpers requiring native_owner by fabricating it.
  Add `test_relay_existing_scope.py`, `test_relay_existing_tasks.py`, and core
  `test_relay_session_delegation.py`; opt-in reads can ship before delegation methods.

### Task 10 — O5/native owners: task outbox and exact client handoff

Depends on Task 6 for contract handoff; task-notification integration after Tasks 5/8.

- [ ] Extend the existing O5 delivery payload union with bounded task-change notification
  snapshots, using the same transaction/dedup/ACK/gap machinery. One stable source event
  ID across live and recovered delivery; current enrollment key/group/permissions and
  explicit device O5 opt-in required. Enforce 24-hour, 256 items/device, 1 MiB/device,
  8 MiB/profile and existing DB ceilings. Do not persist actionable approval controls in
  the outbox. On read, reauthorize and refresh/invalidate stale task references.
- [ ] Add `test_relay_task_outbox.py`: restart/lost ACK, duplicate terminal event, revoked
  group, recovered prompt expiry, eviction/gap and no repeated notification/audio/action.
- [ ] Update server protocol docs and native implementation plan with the sequence below,
  nullable run ID, outcome/origin fields, UUIDv7 age rules, server clock, cursor and error
  contracts. Add protocol DTO/vector tests without Android imports when that work begins.
- [ ] Record per-method software evidence and remaining native/physical gates in
  docs/progress.md, docs/upstream.md and SESSION_STATUS.md; controller owns these updates
  during integration. Do not equate plan completion with approved feature completion.

## Native API and retention handoff

1. Each phone/watch uses its own enrollment/key. Negotiate Relay v1; use only advertised
   methods. Profile selection maps to that endpoint's opaque profile UUID. Unexpected
   profile/server identity change invalidates pending bindings and requires review.
2. List or create a conversation with a durable UUIDv7 request ID; attach deliberately.
   Save conversation identity, never transport Binding across reconnect. New attach
   retires old binding. Fetch paged history and current task snapshot.
3. Submit includes current binding, authority epoch, original request ID, immutable text
   and five-minute maximum expiry. Preserve the request locally until a receipt resolves
   it. On timeout/stale epoch: attach again, query receipt first; never generate a new UUID
   simply because the connection changed. Changed content is a new explicit user turn.
4. Apply incoming/recovered content only for matching endpoint/profile/conversation/
   binding/generation. Dedup by persistent message/event IDs; assemble segments exactly.
   On gap, refresh history/task snapshot, not automatic turn submission. User-visible
   pending/unknown stays uncertain until core proves settlement.
5. Prompt actions require current fetched prompt/binding/epoch/generation and deliberate
   input. Never retry from offline outbox. Quiet task notification opens the exact profile,
   conversation and task; opening refreshes state. No automatic speech or replayed action.
6. Phone text cache: encrypted, configurable default 90 days. Temporary attachment copies:
   7 days. Encrypted export and device-only purge are separate native tasks; canonical
   delete calls the explicit server method and invalidates both devices' cached lineage.
   Neither phone cache expiry nor 24-hour O5 expiry deletes canonical Hermes history.
7. Native acceptance later requires preserving installs on actual phone/watch, successive
   turns into the same real session/lineage, reconnect/process loss, task refresh and
   private quiet notifications. Current server fixture is necessary but insufficient.

## Acceptance commands and evidence

Commands below are acceptance recipes for the future implementation, not tests run by
this planning task. CORE_FIXTURE and SDK_FIXTURE point only to newly isolated writable
copies; TEST_HOME is a new synthetic temporary directory. Preflight fails if either path
resolves to installed Hermes, current services or the preserved baseline snapshots.
Fixture harness sets temporary HERMES_HOME before importing core and blocks all provider
networking. Use core's wrapper, never bare pytest inside the core tree.

```sh
cd "$SDK_FIXTURE"
python -m pytest -W error -q tests/test_relay_grants.py tests/test_relay_outbox.py tests/test_relay_service.py tests/test_relay_registry.py tests/test_relay_grants_v2.py tests/test_relay_request_journal.py tests/test_relay_store_migration.py
python -m pytest -W error -q tests/test_relay_conversations.py tests/test_relay_bindings.py tests/test_relay_shared_delivery.py tests/test_relay_history.py tests/test_relay_projection_recovery.py tests/test_relay_tasks.py
python -m pytest -W error -q tests/test_protocol.py tests/test_opus.py tests/test_pets.py tests/test_client_tts.py
cd "$CORE_FIXTURE"
HERMES_HOME="$TEST_HOME" scripts/run_tests.sh tests/gateway/test_relay_native_admission.py tests/gateway/test_relay_committed_receipt.py tests/gateway/test_relay_bounded_projection.py tests/gateway/test_relay_two_device_continuation.py tests/gateway/test_relay_task_lifecycle.py -W error
HERMES_HOME="$TEST_HOME" scripts/run_tests.sh tests/gateway/test_native_fifo_compression_identity.py tests/gateway/test_session_events_overflow.py tests/gateway/test_session_authority_cancel_settlement.py -W error
```

After their tasks land, run the named search/metadata/delete/prompt/delegation/task-outbox
files using the same SDK/core runners. Run stock capability-off suites, then affected
lint/type checks required by each repository. Native tests/builds belong to the later
native plan. No live listener or provider test is hidden in these commands.

Evidence receipt must include exact source and patch hashes, real import/inheritance,
synthetic endpoint/principal counts, route/root/tip equality, stored per-device user IDs,
admission/execution counts under crash, task transition evidence, authorization matrix,
byte/quota/gap results and test commands/status. Add actual Windows ACL evidence before
activation there. Remaining host-content comparison, rollout backup/review and real device
acceptance are explicit gates; this plan performs none of them.
