# Quantum Relay server contract v1

Design proposal, 2026-10-18. No method in the `relay.*` namespace is implemented by
this document. The first increment is limited to authenticated capability discovery,
a persistent grant boundary and a real final-text reconnect outbox; the remaining methods are reserved contracts for
separately reviewed adapters. Feature flags remain off until their actual adapter
and compatibility tests pass. The [approved scope](superpowers/specs/2026-10-18-relay-expansion-design.md)
remains the program's acceptance authority.

## Evidence and integration seam

Read against SDK v0.2.0 `323e3303ab68981f810fc3208119cd8a22e64af0`, isolated
three-patch SDK `8a1d8541661a218acb70b8e4dec46fbb30a9eff2`, and Hermes core
`e6f57427c198cfd96854522b7a9159eb007e13c2`. Paths below are relative to those
repositories, not installed service configuration.

| Source | Verified behavior | Consequence |
| --- | --- | --- |
| SDK `plugin/adapter.py:360–388` | Source chat/user are device ID; message ID includes transport session; `/new` enters ordinary routing | Stock text does not select a shared conversation; reconnect IDs are insufficient for durable deduplication |
| SDK `plugin/hub.py:178–207, 531–607` | HMAC enrollment, separate pairing approval, random transport `welcome.session`; `hello.caps` is a dictionary | Keep independent keys and pairing; never reinterpret `welcome.session` as canonical conversation ID |
| SDK `plugin/store.py:20–104` | Persistent devices/pairing JSON; no profile/session grants | Add separate grant state; do not place extension fields or duplicate keys in devices.json |
| Core `gateway/run_adapters.py:1893–1900` | Runner is assigned to `adapter.gateway_runner` before connect | Optional in-process Gadget adapter can access its own runner without dashboard credentials |
| Core `gateway/session_contract.py` | Principal, SessionRef, handle, admission and snapshot types | Version-bound bridge can map accepted operations to these types |
| Core `gateway/session_authority.py:131–167, 257–288, 512–568, 590–600, 854–868` | Profile/capability checks; LOCAL ownership check; attach, submit, receipt and respond | Plugin must enforce narrower device/session grants before every call; a profile capability alone is too broad |
| Core `gateway/session_controls.py:406–450`, `gateway/session_local.py:200–300` | Concrete creation uses stage/commit/restore | The Protocol declares `create`/`mutate`, but concrete authority has neither method; do not call nonexistent `authority.create`/`authority.mutate` |
| Core `gateway/session_events.py:12–90` | In-memory bounded replay with changing replay epoch, sequence and explicit gap | Replay sequence is not a durable cross-restart message ID |
| Core `hermes_cli/web_routers/sessions.py`, `hermes_state_timeline.py:141` | Browse/search/timeline surfaces exist | Reuse bounded state projection functions only after explicit authorization; do not proxy admin HTTP APIs |
| Core `hermes_state_terminal.py`, `hermes_state_runtime.py:231,610` | Terminal results persist with admission settlement | Build final-result recovery from committed state, not socket closure |
| Core `gateway/session_listing.py:9–19`, `gateway/session_kanban.py:13–24,72–75` | Legacy picker and Kanban execution have native transport/owner restrictions | No fake native owner or `session:operator`; old LOCAL conversations require a legitimate delegation seam |
| Core `gateway/session_ingress_media.py:99–160` | Admission accepts image MIME/path references only from its managed staging cache | Remote clients receive upload handles, never a server path; documents require a separate ingestion implementation |

The isolated SDK's existing Opus/pets/client-speech patches are preserved. This audit
neither proves current services use them nor exercises a live provider, DB or gateway.
Core revision compatibility on every rollout target remains a mandatory preflight.

## Identity and grants

Each independent endpoint serves exactly one configured profile. Do not accept a
client-selected host, profile path, database, principal, source, model, CWD or capability.
Persist an opaque random `profile_id` in private plugin state, independent of URL and
Hermes home. Client cache key is `(enrolled endpoint identity, profile_id)`; an unexpected
profile change invalidates pending selections and requires user review.

Provision private `relay-grants.json` through local maintainer tooling, mode 0600,
maximum 64 KiB. It contains schema 1, profile UUID, generation (positive integer), and
at most 128 device grants. Each grant binds the enrolled device ID **and SHA-256 of
its current enrollment key**, expiry, allowed operations, and a server-created sharing-group UUID. Deleting/re-enrolling a device does not resurrect its old grant. Never
transmit the subject, key hash, filesystem path, or other devices' grants. Read grants
on every request; invalid/missing configuration denies access and does not reset state.
Atomic local replacement increments generation. Revoke closes extension subscriptions,
clears pending binding and stops further delivery; it does not cancel an admitted task.

The maintainer may explicitly assign separately enrolled phone/watch to one sharing
group within one profile. Each operation still checks the originating device grant.
Create a server-owned logical Gadget DM chat ID `relay-<UUID>`; core's
`gateway/session.py:687–728` keys DMs by chat ID without user ID when chat ID exists.
Each inbound `SessionSource` preserves the actual authenticated device as `user_id`.
This allows one routed conversation without sharing pairing keys or impersonating a
native user. A registry binds group, public conversation UUID, logical chat and real
core lineage. The private registry is the authorization boundary: a random client chat
ID never grants membership. Outbound logical-chat delivery is resolved only to current
granted subscribers; do not call stock `_session(logical_chat)` and assume fanout works.

Grants: `relay:read`, `relay:create`, `relay:submit`, `relay:metadata`, `relay:delete`,
`relay:respond`, `relay:approve`, `relay:control`, `relay:outbox`, `relay:attachments`.
`history:existing` and `tasks:existing` are additional, explicit per-profile opt-ins.
No client method grants any of these. Relay-created conversations/tasks are the default
scope. A registry binds each public conversation ID to its sharing group, canonical
core SessionRef and lineage; unknown IDs fail with `not_found` to avoid enumeration.

Existing non-LOCAL history can be projected only through an explicitly granted
read-only profile filter. Continuing a foreign LOCAL session requires a scoped upstream delegation increment;
until it exists, expose that row as read-only. Do not copy its transcript
and label the new session a continuation. Explicit handoff creates a distinct target
conversation and discloses its selected content/provenance.

## Negotiation and common envelope

Continue WebSocket subprotocol `hermes-gadget.v1` and stock hello/auth/welcome. Client
opts in with `hello.caps.relay = {"version":1}`. Enabled server acknowledges only with
`welcome.relay = {"version":1}`. Absent acknowledgement means no Relay requests; stock
chat/PCM/pets/speech behavior retains its own independent negotiation. Pairing approval
and a current explicit grant are required even after HMAC authentication.

```json
{"type":"relay.request","v":1,"id":"a7b2","method":"relay.info","params":{}}
{"type":"relay.response","v":1,"id":"a7b2","result":{"profile_id":"9e8af651-47eb-411f-b28c-ed292bbfd305","grant_generation":1,"expires_at_ms":1800000000000,"methods":["relay.info"],"core":"unverified"}}
{"type":"relay.response","v":1,"id":"a7b2","error":{"code":"permission_denied"}}
```

Only one of `result`/`error` appears. Unknown fields in v1 requests are rejected;
unknown response fields may be ignored. `id` is `[A-Za-z0-9_-]{1,64}`. Public opaque
IDs are canonical lowercase hyphenated UUID strings; cursors are server-issued
base64url tokens of at most 512 bytes. A public admission ID represents the actual
core UUID: format its 32-hex spelling canonically without allocating a replacement
identity. Internal core receipts retain their original spelling and exact lookup key.
No credentials or arbitrary error details appear in errors. JSON frame limit is
64 KiB UTF-8, depth 8, at most 16 simultaneous extension operations per connection;
rate limit 10 requests/second with burst 20. The first foundation has only small
`relay.info` messages, serial dispatch, and a bounded grant file. Expensive future
operations must implement the advertised concurrency/rate limits before activation.

Discovery reports only tested-and-enabled **method names**, intersected with the
current device grant. It is not a promise that all designed methods exist. Every
request rechecks grants; client-cached capabilities confer no authority. Unadvertised
methods return `unsupported`; expired/revoked grants return `permission_denied`.

Conversation-capable discovery may add `server_time_ms` (UTC milliseconds) and
`limits.dto_bytes=8192`, `limits.dto_depth=6`. These limits cover each complete
serialized conversation response/event, using the actual JSON sender encoding, except
the bounded history-page method below. Task4 advertises optional
`limits.history_page_bytes=65536` and `limits.history_text_bytes=32768` when its tested
history implementation is enabled: that method's complete response is at most64 KiB,
and each text segment at most32 KiB UTF-8. Other conversation responses/events keep
the8-KiB limit. History segmentation also accounts for JSON escaping and the complete
response envelope; a32-KiB text allowance is not permission to exceed the wire bound.
Legacy O5 keeps its separate64-KiB response limit and existing text/page limits.
Optional discovery fields confer no permission and old clients may ignore them.

Common errors: `invalid_request`, `unsupported`, `permission_denied`, `not_found`,
`profile_changed`, `stale_epoch`, `stale_binding`, `conflict`, `expired`, `busy`,
`rate_limited`, `snapshot_required`, `temporarily_unavailable`, `unsupported_media`,
`projection_too_large`. The latter refuses a body that cannot fit its bounded
projection; it never authorizes truncation or successful delivery acknowledgment.
Error responses may include only `retry_after_ms` (0–60000) and `snapshot_required`
(boolean), where applicable. Foundation invalid-ID responses omit `id` (the stock JSON sender omits null values).

## Reserved conversation and task methods

The table is the exact proposed adapter contract. Not advertised by foundation v1.
Fields shown with `?` are optional; all other fields are required. `Page<T>` is
`{items:[T],next_cursor:string|null}` with limit 1–50 (default 25); cursors bind subject,
profile, grant generation, query and expiry (15 minutes). Authorization precedes
pagination/search, so counts and snippets cannot reveal unauthorized rows.

| Method | Params | Result and permission |
| --- | --- | --- |
| `relay.conversations.list` | `{cursor?,limit?,query?,bookmarked?}`; query ≤256 UTF-8 bytes | `Page<Conversation>`; `relay:read` |
| `relay.conversation.create` | `{request_id,title?}`; title ≤120 Unicode scalar values/512 bytes | `Conversation`; `relay:create` |
| `relay.conversation.attach` | `{conversation_id}` | `Binding`; `relay:read`, ownership registry check |
| `relay.conversation.detach` | `{binding_id}` | `{detached:true}`; detach own transport only, no cancel |
| `relay.history.page` | `{conversation_id,cursor?,limit?}` | `Page<Message>`; `relay:read` |
| `relay.events.read` | `{binding_id,replay_epoch,after_sequence,limit?}` | `{events:[Event],replay_epoch,last_sequence,snapshot_required}`; `relay:read` |
| `relay.turn.submit` | `{binding_id,authority_epoch,request_id,expires_at_ms,text,attachment_ids?}` | `Receipt`; `relay:submit` (+attachments when nonempty) |
| `relay.turn.receipt` | `{conversation_id,request_id}` | `Receipt` or `not_found`; only original device or explicit shared receipt grant |
| `relay.conversation.update` | `{conversation_id,request_id,expected_revision,title?,bookmarked?}` | `Conversation`; `relay:metadata`; at least one update field |
| `relay.conversation.delete` | `{conversation_id,request_id,expected_revision,scope:"canonical"}` | `{deleted:true}`; `relay:delete`, no running/unknown admission |
| `relay.tasks.list` | `{cursor?,limit?,state?}` | `Page<Task>`; `relay:read`; existing tasks also require `tasks:existing` |
| `relay.task.get` | `{task_id}` | `Task`; same checks |
| `relay.prompt.respond` | `{binding_id,authority_epoch,generation,prompt_id,response,request_id}` | `{accepted:true}`; `relay:respond` for clarification or `relay:approve` for approval |
| `relay.turn.cancel` | `{binding_id,authority_epoch,admission_id,generation,request_id}` | `Receipt`; `relay:control`, only if adapter advertises exact queued/running control |

`Conversation = {conversation_id,title,revision,bookmarked,updated_at_ms,can_continue}`.
`Binding = {binding_id,conversation_id,authority_epoch,execution_generation,revision,
replay_epoch,last_sequence,execution_state}`. One binding per transport at a time;
attach explicitly retires the previous binding. Binding UUID is transport-local and
never survives reconnect. A late event from an old binding is discarded.
`Message = {message_id,role,text,created_at_ms,attachment_ids}`. Roles are `user` or
`assistant`; text ≤32 KiB UTF-8, larger bodies use subsequent paged segments with
`message_id`, `segment_index`, `segment_count`; never silently truncate canonical text.
Tool/reasoning/raw system rows are not exposed as messages.
`Receipt = {request_id,admission_id,conversation_id,status,outcome,authority_epoch,
execution_generation}`. Status is `queued|started|unknown|terminal`; outcome nullable.
`Task = {task_id,conversation_id,admission_id,run_id,state,updated_at_ms,prompt?,outcome?}`.
`run_id` is null while queued before a real execution exists; otherwise it is an
opaque mapped real core execution identity, never a client-generated busy indicator.
Optional `outcome` is null before terminal settlement and reflects the actual
terminal receipt afterwards. State is `working|needs_input|finished|failed|unknown`; unknown remains
visibly uncertain instead of being misreported as failed. `prompt` contains opaque ID,
kind `approval|clarify`, ≤4096-byte text, up to 8 ≤256-byte choices, generation and
server expiry. An expired prompt is display-only. Response is `{choice:string}` with
choice drawn from those server options, or `{text:string}` ≤4000 UTF-8 bytes for a
clarification explicitly permitting free text. Approval responses never enter outbox.
`Event = {event_id,conversation_id,binding_id,sequence,replay_epoch,kind,payload}`;
kind is `message|task|prompt|conversation|gap`. Payload is its corresponding bounded
DTO; gap payload is `{snapshot_required:true}`.
Events are pulled through `relay.events.read`; this contract adds no unsolicited
`relay.event` wire frame. Detach or subscription overflow invalidates the binding
before any subsequent successful output under the socket send lock.

Canonical message IDs derive from a persisted mapping of `(profile, transcript lineage,
row ID)`; admission/task IDs map real core receipts. Event IDs are persisted projection
IDs. Core `(replay_epoch,seq)` remains a distinct, ephemeral ordering cursor. On core
restart, compression, replay eviction or gap: drop incremental cursor, fetch a bounded
snapshot, merge using durable IDs, and preserve pending admission receipts. An authority
epoch mismatch rejects a new mutation; client refreshes attach, then queries the prior
request receipt **before** deciding to retry. It never invents a new request ID merely
because the socket changed. Never claim exactly-once external tool side effects.

New durable mutation requests use UUIDv7 `request_id`, retained by the client until
resolved. New IDs older than seven days or more than five minutes ahead of server
time are refused. Creation/metadata requests use the ID timestamp for this replay-age
bound; retained receipt reads can still recover an older accepted admission. Exact
accepted replay does not become a new admission after submission expiry.
Text is 1–4000
Unicode scalar values and at most 16 KiB UTF-8. Client submission expiry must be after
server time and no more than five minutes ahead. Plugin receipt key is `(profile,
subject,device-key-generation,request_id)`; canonical payload digest includes operation,
conversation and content. Identical retry returns the original receipt; changed content
or target returns `conflict`. Do not use transport ID in this key. Core request ID is a
SHA-256 encoding of that namespace, and is passed unchanged on retry. New admission,
expiry check and durable identity must be coordinated at the core commit boundary;
a crash after acceptance is reconciled via the same core identity. Expired/unresolved
requests are never auto-resubmitted. Retain idempotency tombstones for at least seven
days after terminal settlement; after that return `expired` for the old timestamped
request, never treat it as new.

## Optional outbox and attachments

The first increment advertises `limits.outbox_cursor=false`, `page_items=50`,
`text_bytes=16384`: each delivery must also fit the 64-KiB serialized response envelope;
an unframeable escaped body is refused before capture without truncation. Actual-core
integration must retain its canonical delivery obligation. `read` supports only `limit`, returns oldest unacknowledged items
within a 64-KiB response and includes persistent `gap_generation`. Its pull-only finals
come from `send_final_ledgered`, not guessed transcript timestamps. No live server TTS
replay occurs on a read. The informational conversation ID for existing stock lanes is
not attachable until the real session registry is installed.

Outbox methods: `relay.outbox.read {cursor?,limit?}` → `Page<Delivery>` and
`relay.outbox.ack {delivery_ids:[UUID]}` (1–50) → `{acknowledged:[UUID]}`; `relay:outbox`.
`Delivery = {delivery_id,conversation_id,event_id,created_at_ms,expires_at_ms,payload}`.
Persist completed text/task notifications only, maximum 24 hours, 256 deliveries/device,
1 MiB/device and 8 MiB/profile; oldest-first eviction yields an explicit gap marker.
Transactional dedup key includes profile, device key generation and event ID. ACK commits
before response, identical ACK succeeds after restart, and ACK of another device's ID
fails. No recording, action, approval answer, microphone PCM or paid synthesis job is
stored/replayed. Recovery is silent: no auto-speech or re-triggered task notification.
Canonical history retention is independent of these delivery copies.

Attachment methods: `relay.attachment.begin {request_id,mime,size,sha256,name}` →
`{upload_id,chunk_bytes:24576,expires_at_ms}`; `relay.attachment.chunk
{upload_id,offset,data_base64}` → `{next_offset}`; `relay.attachment.finish {upload_id}`
→ `{attachment_id,mime,size,expires_at_ms}`; `relay.attachment.delete {attachment_id}`
→ `{deleted:true}`. All require `relay:attachments`. Base64 is strict, chunks ≤24 KiB
before encoding, sequential offsets; identical retry at the same offset succeeds only
for matching bytes. Ten-minute incomplete-upload expiry; completed temporary copies
seven days; 4 attachments/turn, 5 MiB each, 20 MiB/device, 100 MiB/profile. Name ≤128
Unicode scalar values, presentation-only; never used as a path. Images initially PNG/JPEG,
≤4096×4096 and ≤16 million pixels, decoded only after magic/byte/dimension validation.
Grant change aborts upload. Finish checks exact byte count/hash and MIME. Server-generated
staging paths enter `admit_attachments`; client sends only scoped handles. Core immutable
admission media has its own release rules; temporary-copy deletion cannot erase bytes
still required by a queued admission. PDF/text documents remain unadvertised until a
separate bounded real ingestion path is tested. No URL fetching or arbitrary local path.

## Adapter interfaces and activation gates

Plugin-side `RelayContext` is `{profile_id,group_id,device_id,key_generation,grant_generation,
transport_id,permissions}`. These are server-derived, never trusted from a wire DTO.
Interfaces are Python async methods returning the DTO dictionaries above:

```python
class RelaySessionAdapter:
    async def create(self, ctx, request_id: str, title: str | None) -> dict: ...
    async def attach(self, ctx, conversation_id: str) -> dict: ...
    async def detach(self, ctx, binding_id: str) -> dict: ...
    async def history(self, ctx, conversation_id: str, cursor: str | None, limit: int) -> dict: ...
    async def submit(self, ctx, params: dict) -> dict: ...
    async def receipt(self, ctx, conversation_id: str, request_id: str) -> dict: ...
    async def events(self, ctx, params: dict) -> dict: ...
class RelayTaskAdapter:
    async def list(self, ctx, cursor: str | None, limit: int, state: str | None) -> dict: ...
    async def get(self, ctx, task_id: str) -> dict: ...
    async def respond(self, ctx, params: dict) -> dict: ...
    async def cancel(self, ctx, params: dict) -> dict: ...
class RelayAttachmentAdapter:
    async def begin(self, ctx, params: dict) -> dict: ...
    async def chunk(self, ctx, params: dict) -> dict: ...
    async def finish(self, ctx, upload_id: str) -> dict: ...
    async def delete(self, ctx, attachment_id: str) -> dict: ...
```

Relay-owned creation uses `SessionAuthority.register(source)` with the server-created
logical Gadget DM and actual device `user_id`. Its synchronous reserve writes route
state; use a narrow upstream async wrapper for `_reserve_route` through `tracked_write`
and `_publish_route`, following `admit_native` at `session_authority.py:402–432`.
Submission enters normal `GadgetAdapter.handle_message` with the same logical chat,
actual sender and a stable device-qualified message ID. Core's `admit_native` derives
its durable principal from `[profile,platform,chat_id,thread_id,user_id]` and commits
before scheduling. Preserve native callback provenance from the real runner handler;
do not manufacture it or invoke private admission methods from an unauthenticated task.
A submit response needs an upstream supported receipt hook exposing that committed
admission to the adapter; `_gateway_accepted` alone is only a boolean. Add and test that
small hook in a separate exported core patch if no supported hook is available. Never
return an admission ID inferred from a socket or local task.

Attach/receipt/respond can call real authority methods after registry/grant checks;
use a server-derived Principal corresponding to the real native admission identity,
with only the necessary session capabilities. Event subscription must implement the
bounded transport expected by `authority.events`; map actual core identity precisely
before enabling controls. LOCAL session creation is an alternative requiring the
stage/commit/restore path, not nonexistent `authority.create`. It is not needed for
Relay-owned routed conversations. Existing-history reads use profile-bound SessionDB
projection functions after filtering; foreign LOCAL continuation needs explicit scoped
delegation added upstream. These are concrete integration dependencies, not reasons to
drop the approved feature scope. No native-owner bootstrap, operator capability, direct
SQL writes to core state, monkey-patch or exposed administrative API is acceptable.

First foundation: authenticated discovery and durable final-text outbox on the actual
`BasePlatformAdapter.send_final_ledgered` hook, including offline delivery, restart,
ACK loss, deduplication and bounded retention. It stores only final text and never calls
TTS during replay. Next independent increment: server-owned logical Gadget DM creation,
real native admission receipt hook, bounded event delivery and two separately enrolled
clients continuing the same canonical session in an isolated real-core fixture. Then
history/task projection, control expiry, metadata/deletion, broader outbox event kinds
and attachment ingestion activate after their own tests. Older-history continuation's
scoped delegation patch and document ingestion remain explicit program dependencies.

## Remote wake boundary (subsequent increment)

Methods `wake.targets {}` → `{targets:[{target_id,label,backend,available,reason?}]}`
and `wake.request {target_id,request_id,expires_at_ms}` → `{request_id,status}` run on
the explicitly configured enrolled wake-sender profile. Grant `wake:read` lists only
allowed opaque targets; `wake:request` requests a wake. At most 16 targets, ≤80-scalar
labels; UUID request/target IDs; expiry ≤30 seconds ahead; one request per target/minute,
burst one. Status `accepted|unavailable|unsupported` describes dispatch, not proven boot.
Keep an idempotency receipt for ten minutes; duplicate ID/different target is conflict.

The private wake-enabled candidate adds optional `server_time_ms` to `relay.info`,
including wake-only and outbox clients, so the client can form that expiry against
server time. A missing or invalid clock is not permission to extend an expiry or
silently retry a wake. Wake-disabled discovery retains its existing fields. This
additive field is source-reviewed; native integration and live wake remain unverified.

Backends are server-allowlisted `wol` and `vm_start_resume`; mappings contain the private
MAC or VM UUID and platform command. Client cannot supply a MAC, UUID, command, binary,
address or arguments. Other profile endpoints may report wake unavailable and direct
the client to its already reviewed sender configuration. No administrative token or new
listener is required. WOL settings and VM identity are readiness evidence; a real
sleep-to-awake check remains required before claiming wake acceptance.
