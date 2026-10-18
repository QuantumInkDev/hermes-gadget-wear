# Remote wake implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> with isolated implementation owners and fresh independent task reviews.

**Goal:** Let an enrolled phone or watch deliberately request an allowlisted box wake
through the configured home sender, with honest dispatch and reachability status.

**Architecture:** Optional `wake.*` methods use the existing authenticated Gadget relay
envelope and current enrollment-bound grants. Private server policy selects fixed WOL or
Parallels operations; a bounded durable intent/receipt prevents automatic redispatch.
Native clients select an already enrolled sender and preserve their active conversation.

**Tech Stack:** Existing Python SDK/grants/private storage, UDP, bounded subprocess argv,
Kotlin/JVM protocol, existing TLS transports, encrypted native configuration and Compose.

**Spec:** `docs/superpowers/specs/2026-10-18-relay-expansion-design.md` N9 and
`docs/relay-server-contract.md` remote wake boundary.

## Global constraints

- Use M5 Max only while awake/reachable. No keep-awake helper, power-setting change,
  new listener, administrator token, gateway v2 or arbitrary remote command.
- Existing independent endpoints, enrollment keys and watch-owned TLS remain mandatory.
  Sender selection never creates/replaces an identity or downgrades transport security.
- Methods are `wake.targets {}` and `wake.request {target_id,request_id,expires_at_ms}`.
  Preserve the documented response fields and `accepted|unavailable|unsupported` statuses.
  Accepted means dispatch, never proof that the target booted or its agent is ready.
- At most16 allowlisted targets; labels at most80 Unicode scalars, no control characters;
  canonical opaque UUID target/request IDs; expiry at most30 seconds ahead; one request
  per target/minute, burst one; retain idempotency receipts for ten minutes.
- `wake:read` and `wake:request` are explicit enrollment-bound permissions. Neither
  conversations availability nor O5 retention consent grants/revokes wake authority.
- The client supplies no MAC, VM identity, binary, command, address, arguments or path.
  Configured targets can expose only ID, label, backend, availability and safe reason.
- No automatic wake on app open, swipe, refresh, reconnect, widget/tile launch, restored
  UI state, task notification or server restart. No automatic retry after uncertain dispatch.
- Preserve configuration/art/keys/history and Light/Dark/Electronic Moonlight. Phone API29,
  Wear API33, target API37; protocol remains Android-free and stock SDK v0.2.0 compatible.
- Personal inventory, policies, command paths/hashes and live evidence stay ignored.
  Implementation workers use synthetic fixtures and no actual host/device/power operations.

## Review focus

1. A duplicate tap or lost response cannot send another wake; crash between intent and
   dispatch is uncertain and recoverable without an automatic second side effect.
2. A removed enrollment, changed key/group/target policy or expired grant cannot dispatch
   after waiting on an output lock or backend queue; cached method discovery is no authority.
3. A sleeping sender, missing CLI, unknown VM state or failing diagnostics is unavailable,
   never an invitation to change power settings, restart a VM or execute a fallback command.
4. Removing or replacing a profile cannot silently redirect a saved wake binding to another
   endpoint/identity. Existing conversations and recording remain undisturbed.
5. Imported configuration and a restored pending screen cannot trigger a wake; malformed,
   overlong or secret-bearing backend output cannot leak into logs, DTOs or diagnostics.

## Verified source and foundation gates

`plugin/relay_grants.py::Grants.authorize` derives current key/group/generation from
private local grants. Its permission allowlist currently lacks the two wake permissions.
`plugin/relay_service.py` owns the existing envelope/discovery/outbox dispatch; the
continuity bridge is being corrected separately. Freeze a clean, source-reviewed combined
SDK foundation **after Task3 fix2 acceptance** before Task1 begins. Do not develop on or
export an unaccepted concurrent tree. Capability-off stock behavior is a required control.

The current pure `DirectConnection` owns handshake, transport and a serialized work queue,
but exposes no public relay-request API. `ConnectionObserver` has default extension points.
Phone `ConversationVault.identity(endpoint, create=false)` reads the phone's own enrollment;
watch `IdentityVault` and `GadgetService` own the separate watch connection. Inventory their
actual signatures before Task2/3 and record the exact consumer handoff, not guessed calls.

Historical private readiness found a GPU WOL candidate and a matching local Parallels VM.
These are not current reachability or sleep-to-awake acceptance. Primary references:
[Microsoft wake-event contract](https://learn.microsoft.com/en-us/windows-hardware/drivers/network/network-wake-up-events)
defines magic-packet wake and explicitly permits adapters with no wake capability;
[Parallels CLI reference, power operations](https://download.parallels.com/desktop/v18/docs/en_US/Parallels%20Desktop%20Command-Line%20Reference.pdf)
documents start/resume/status; the
[Parallels-maintained update script](https://github.com/Parallels/prlctl-scripts/blob/main/scripts/windows_vms_update.sh)
uses state-dependent start/resume. Verify the installed version/help before live use;
the older manual does not prove the installed CLI or this VM is ready.

## File map and interfaces

SDK paths below are relative to its isolated clone. Kotlin paths use the existing
`dev/quantumink/hermesgadget` package under the named module.

| File | Responsibility |
| --- | --- |
| `plugin/relay_wake.py` | Strict private policy, current target authorization, request orchestration and safe public DTOs |
| `plugin/relay_wake_store.py` | Private bounded durable intent/receipt and target cooldown transactions |
| `plugin/relay_wake_backends.py` | Fixed WOL packet and state-dependent Parallels start/resume; injectable leaf I/O |
| `plugin/relay_grants.py`, `relay_service.py`, `adapter.py` | Explicit permissions, optional setup, discovery and existing authenticated dispatch |
| `tests/test_relay_wake*.py` | Actual dispatch/grants/storage, race, crash and backend-boundary tests |
| `protocol/.../RelayRequests.kt`, `Wake.kt`, `DirectConnection.kt` | Bounded request correlation and typed wake contract on the existing socket |
| `protocol/src/test/.../RelayRequestsTest.kt`, `WakeTest.kt` | Stock/timeout/epoch/parser/request-lifecycle controls |
| `client/.../WakeBindings.kt` | Bounded credential-free sender/target association and deliberate-action state |
| `mobile/.../WakeSettings.kt`, `WakeVault.kt`, `PhoneConversation.kt`, `PhoneSettingsPage.kt` | Sender/target review, encrypted persistence and native wake controls |
| `wear/.../WakeControls.kt`, `GadgetService.kt`, `WatchCatalogController.kt` | Associated-device binding sync and deliberately borrowed sender connection |
| Existing native migration/diagnostics tests | Preserve bindings through reviewed migration without credentials or pending operations |

### Task 1: Authenticated sender, durable receipts and fixed backends

**Consumes:** Frozen accepted SDK; current `RelayContext`, grant/private-file APIs and
the actual socket authorization callback. No Hermes core admission API is required.
**Produces:** async `WakeService.request(session, msg) -> bool` (handled),
`WakeService.methods(session) -> list[str]`, `WakePolicy.load(path) -> WakePolicy`,
`WakeStore.reserve(context: RelayContext, target: WakeTargetPolicy, request_id: str,
expires_at_ms: int) -> WakeReservation`, and async
`dispatch(target: WakeTargetPolicy) -> WakeDispatch`. Policy/reservation types are
server-private immutable values; dispatch has the three documented public statuses.
The report freezes their exact fields and Python
signatures and safe failure mapping for the native consumer.

- [ ] Write and run focused failing cases for allowed target discovery, actual key-bound
  denial, duplicate request across restart, conflicting target, expired request, global
  per-target cooldown and capability-OFF stock behavior. Retain the initial failures.
- [ ] Add explicit permissions and separate default-OFF `enable_relay_wake`. Private
  policy is strict versioned JSON, at most64KiB, duplicate keys rejected, with at most16
  canonical target IDs and explicit allowed sharing-group UUIDs. Use existing private
  file/ancestry checks; invalid/missing authority refuses without clearing O5 or receipts.
  Policy/backend fields remain local. No endpoint, key, group or hardware identity leaks.
- [ ] Extend aggregate discovery/dispatch without depending on conversation availability
  or outbox consent. Check paired transport, authenticated current key, exact grant and
  target group both before admission and immediately before leaf dispatch. Recheck on
  final socket output too; withheld output does not undo or redispatch committed work.
- [ ] Persist intent and consume target cooldown atomically before external I/O. Cap the
  receipt store at2048 records and8MiB, retaining active ten-minute receipts. Duplicate
  same request/target returns the stored result without dispatch; different target is
  conflict. Unknown owner cannot read another owner's receipt. Crash after intent leaves
  an uncertain unavailable result and never retries. Corruption, full disk, clock rollback,
  invalid time or inability to persist intent refuses before a side effect; never evict
  unexpired records to admit another request. Bind the original expiry and target policy.
- [ ] WOL emits the canonical102-byte magic payload to a server-configured fixed IPv4
  broadcast/port. Validate the six-byte MAC and private configuration; no DNS/client
  address selection. Tests capture exact bytes at an owned injected socket boundary.
  Parallels uses only a pinned absolute installed CLI, canonical locally configured VM
  UUID and fixed status/start/resume argv with `shell=False`. Running is a no-op;
  stopped/suspended/paused dispatch follows verified installed semantics. Unknown state,
  missing permission, diagnostics, timeout or nonzero status is safely unavailable.
  No stop/suspend/reset/restart/delete, guest exec, shell expansion or power configuration.
- [ ] Test held-lock revocation/policy replacement, concurrent peers/processes, crash at
  every persistence/dispatch boundary, unchanged O5 bodies on wake denial, exact quota/
  expiry boundaries, corrupt/symlink config, command-injection strings, huge/stderr output,
  unknown VM states and backend timeout. Use real grants/SQLite and owned AF_UNIX fixtures;
  fake only hardware/process leaf boundaries. No actual WOL/VM/service call in the worker.
- [ ] Run affected strict SDK tests and stock/default guards with retries disabled. Update
  SDK protocol/integration docs, commit scoped changes and provide net review packages,
  source/import hashes, distinct cases and complete retained failures. Fresh review gates
  source acceptance; no live setup, public patch export or activation follows automatically.

### Task 2: Bounded native relay requests and typed wake protocol

**Consumes:** Source-reviewed Task1 envelope, discovery, expiry and error contract.
**Produces:** `DirectConnection.relayRequest(method: String, params: JsonObject):
CompletableFuture<JsonObject>` and pure `WakeTarget`, `WakeRequest`, `WakeResult` codecs.
This is the shared request seam for subsequent native continuity, not a second socket stack.

- [ ] Write failing real WebSocket tests for stock peers, negotiated authorized discovery,
  response correlation, wrong ID/version, malformed/oversize DTO, timeout and a late reply
  after disconnect/reconnect. A stock server remains usable for ordinary speech.
- [ ] Implement bounded request correlation on the existing serialized connection queue:
  at most8 pending requests, safe64-byte transport IDs and64KiB response envelope. Require
  paired state/current server discovery, discard unrelated responses and fail pending work
  on epoch/identity changes. Each request has a10-second monotonic timeout. A timeout is
  uncertain; the caller may deliberately retry the same request ID within its validity,
  never create a hidden second mutation. Do not route relay DTOs as chat/tool actions.
- [ ] Implement strict typed target/request/result validation using the server contract,
  injected wall time and the original30-second expiry. Discovery has explicit unsupported,
  permission and sender-unavailable states; server dispatch accepted remains distinct from
  separately observed endpoint availability. No key, MAC, VM UUID or argv in public types.
- [ ] Run affected pure warning-strict tests, stock compatibility and Android-import/lint/
  formatting checks; commit and provide a frozen consumer handoff for a fresh review.

### Task 3: Native binding setup and deliberate phone/watch wake controls

**Consumes:** Reviewed Task2 API and actual native profile/identity/transport APIs.
**Produces:** Explicit phone sender/target setup and one-tap wake on a bound offline profile,
with watch synchronization, safe status and conversation preservation.

- [ ] Inventory exact current APIs after N10 source acceptance/continuity integration;
  consume the reviewed import-journal/mutator contract rather than changing its live WIP.
  Record stable page,
  endpoint and identity binding, encrypted store and associated Data Layer paths before
  editing. `WakeBinding(profileKey, senderEndpointId, targetId)` stores no credential or
  hardware identity. At most32 bindings; sync over versioned `/hermes/wake/v1` only from
  the associated phone. Removing/replacing an endpoint invalidates its bindings visibly.
- [ ] Phone Settings lets the owner choose an already approved/enrolled TLS sender, fetch
  authorized targets and explicitly bind a target to a profile/box. No implicit enrollment
  or unchecked URL from catalog/QR becomes authority. Show sender-unavailable and unsupported
  states, clear/unbind controls and the M5-awake requirement in normal product language.
- [ ] Add a labeled Wake box control below profile content, with no portrait overlay.
  A tap names the target and configured sender and owns one logical request ID. Repeated
  taps while pending do not send again. Result wording says wake request sent, unavailable
  or unsupported; existing catalog refresh separately establishes agent availability.
  Saved pending state, app resume, a swipe and network recovery never send automatically.
- [ ] Borrow an existing idle authorized sender connection or temporarily open it using
  that device's existing enrollment. Never replace/disconnect a talking/recording session
  or generate an identity. A busy sender waits for an explicit later tap. Preserve selected
  profile, drafts, scroll and in-flight reply; watch transport remains watch-owned TLS
  through the phone byte relay. Closing the control surface releases its owned resources.
- [ ] Extend reviewed migration through an explicit compatible format revision if needed:
  older archives load with no binding; new references require import review/remapping;
  no pending wake, enrollment or grant is portable. Do not silently append unknown fields
  to strict v1. Redacted diagnostic export never includes hardware/command details.
- [ ] Test real encrypted stores, wrong associated sender, stale mappings, cancellation,
  busy active speech, background/rotation/reconnect, duplicate taps and import restoration.
  Use API29/API37 phone and API33/modern Wear fixtures, all three themes/large font and
  preserving APK/data/settings procedures. No quarantined or physical device worker use.
  Commit, report exact results and obtain fresh combined native review before integration.

### Task 4: Controller integration and supported live acceptance

- [ ] Reapply reviewed SDK changes to the accepted combined foundation, verify all touched
  source parity/stock guards and integrate reviewed native changes. Keep unrelated optional
  capabilities off and include new private wake state in backup/rollback documentation.
- [ ] Revalidate M5 CLI/version/VM identity and GPU NIC/network support read-only. Prepare
  private allowlist/grants with exact selected sender/targets and current enrollments.
  Use approved staged installation only after its independent maintenance prerequisites.
- [ ] Exercise actual authorized wake of an already sleeping supported target, preserving
  all previous settings and live work. Do not put a running box/VM to sleep or stop it for
  a test without specific authorization. Record dispatch, independently observed host/agent
  return, duplicate suppression and phone/watch action routing. Missing actual sleep-to-
  awake evidence remains open, not a software pass or presumed unsupported hardware.
- [ ] Verify away access with M5 awake, and honest unavailable behavior while the sender
  cannot be reached. No keep-awake change. Record remaining hardware/physical evidence
  in progress/status and keep N9/full goal open until its required gates are proved.

## Preflight rulings

Ruling: reuse the existing relay envelope and enrollment-bound grants, with an independent
default-OFF wake capability. Why: temporary conversation unavailability must not revoke
unread responses or hardware authorization. Cost if wrong: revise the unshipped dispatch
seam before rollout; never add a dashboard token or alternate administrative listener.

Ruling: a durable uncertain intent is unavailable and never automatically retried. Why:
storage and a hardware side effect cannot be committed atomically. Cost if wrong: a user
may need a later deliberate request; the app must not claim that an uncertain target woke.

Ruling: existing WOL/VM readiness is historical candidate evidence only. Actual installed
semantics and supported sleep-to-awake behavior must be proved before claiming acceptance.
