# Connection and request diagnostics implementation plan

> Execute with fresh task implementers and scoped combined review. The approved expansion
> design is the authority; this plan implements N8 without a new server API.

**Goal:** Explain whether a request is connecting, awaiting enrollment, uploading,
waiting for Hermes, receiving text or playing audio, with a safe report users can share.
An elapsed-time warning must never claim a task failed or cancel it without evidence.

## Global Constraints

- Preserve stock Gadget v0.2.0 behavior and the Android-free protocol module.
- Preserve separate phone/watch identities, current artwork/GIFs and all three themes.
- Never cover watch artwork; diagnostics belongs below normal conversation controls.
- Reports contain only allowlisted enums, bounded counts and relative durations: no
  endpoint/host/IP, source/profile name, device ID or hash, credentials, pairing text,
  conversation text, server status text, artwork, raw exceptions or file paths.
- No microphone capture, automatic speech, submission, pairing or task cancellation
  may be initiated by opening or exporting diagnostics.
- No installed server changes, provider calls or physical-device tests by workers.
- Personal configuration and receipts remain ignored under `.local/`.

## Source seams and dependencies

`DirectConnection.kt` owns generation-guarded WebSocket events and the protocol clock;
it already classifies TLS/address-policy failures without logging the exception.
`Conversation.kt` validates turn-bound messages and owns upload/playback state.
`PhoneConversation.kt` and `GadgetService.kt` expose connection state to native clients.
`PhoneSettingsPage.kt`, `ConversationPage.kt` and `WatchScreen.kt` host the controls.

The quick-action worker owns its isolated `mobile/MainActivity.kt` integration. Task 1
does not modify that file. Integrate Task 2 only after the quick-action commit is merged
locally, so navigation is reviewed against its actual final source. No generic persistent
logger is needed. In-memory connection-local telemetry is sufficient for this increment.

## Task 1: Typed bounded telemetry and actual protocol hooks

Owned files: new `protocol/.../ConnectionDiagnostics.kt`, matching JVM tests; focused
hooks in `DirectConnection.kt` and `Conversation.kt` with protocol integration tests.
No native UI or client-service changes in this task.

- [ ] Add typed enums for transport (direct/phone relay/unknown), connection stage
  (offline/connecting/socket open/authenticating/pairing/ready/retrying/failed), request
  stage (idle/recording/uploading/waiting/transcribed/receiving/complete/cancelled/failed),
  and failure category (none/TLS/address policy/reachability/auth/protocol/timeout/
  overload/disconnected). No caller-provided labels or raw messages enter telemetry.
- [ ] Keep an immutable snapshot in ConnectionState: current stages, failure category,
  elapsed milliseconds, last-progress age, stalled boolean and up to 96 typed transition
  events. Events use monotonic relative durations clamped to 0..604800000 ms. Reject or
  coalesce duplicate transitions; do not emit events for every media packet or clock tick.
  A clock regression cannot create negative durations or grow the ring.
- [ ] Track actual observed events. A successful WebSocket open precedes authentication;
  a challenge observes authentication; welcome/paired observes ready; an unpaired server
  observes pairing. Do not call a socket open authenticated. A text/audio send is only
  locally submitted; it is not proof of durable core admission.
- [ ] Use the existing validated conversation transitions for recording/upload/waiting,
  transcript/reply/end/prompt/audio. Ignore mismatched-turn messages and callbacks from
  old socket generations. Never derive progress by parsing tool names or server text.
  A status message can record a generic progress observation only after turn validation;
  do not store its text. Distinguish response text from playback; do not claim server
  processing time can be decomposed into STT/tool/model timings from stock frames.
- [ ] Mark a waiting request as taking longer after 30 seconds without observed progress.
  Keep waiting and expose elapsed time; only existing protocol timeout/failure semantics
  may end the request. Clear the warning on progress/completion/disconnect. A replayed
  old reply or preserved previousReply does not start/complete a new request.
- [ ] Determine direct versus phone relay from the actual configured connection proxy;
  no host/port is stored. Leave unobserved native route information unknown for Task 2.
- [ ] Add an allowlisted report encoder with schema 1. Its inputs are typed snapshots
  only. Limit output to 16 KiB; select newest events if necessary and indicate omitted
  count. Include no wall-clock times, raw strings or arbitrary key/value maps. Human
  labels can be fixed constants mapped from enums. Snapshot/report toString must be safe.
- [ ] Write meaningful failing tests first: challenge/welcome distinction, stale generation,
  wrong-turn progress, 30-second warning without cancellation, old-reply preservation,
  ring/time/report bounds and reset across reconnect. A hostile transcript/status/error/
  endpoint containing secrets must never appear in exported diagnostics. Use existing
  synthetic local socket fixtures; do not create physical/provider dependencies.
- [ ] Run affected JVM checks with warnings treated as errors and `git diff --check`.
  Record commands/results, exact counts/skips and known unobserved stages in
  `.superpowers/sdd/2026-10-18-connection-diagnostics/task-1-report.md`. Commit exact owned
  files only; no push. Controller performs independent review before native integration.

## Task 2: Native health views and explicit safe export

Depends on reviewed Task 1 and locally integrated quick actions. Owned files: focused
phone/watch state and conversation UI changes; new native diagnostics composables and
instrumentation tests, phone navigation integration if needed. No server/protocol changes
except a concrete Task 1 integration defect approved by the controller.

- [ ] Add an accessible connection-health entry to the phone conversation detail and watch
  footer, with current connection/request stage and elapsed waiting hint. Use existing
  theme tokens, bounded scrolling and large text. Keep the clear portrait and horizontal
  swipes; do not reintroduce a floating menu/microphone badge or another previous/next row.
- [ ] Show route, enrollment state, generic stage/failure category and last progress age.
  Offer a clear explanation that stock server internals are not individually measured.
  Healthy views must not be dominated by alarming error styling or technical details.
- [ ] Phone can preview/copy/share a report through explicit controls and Android's share
  sheet. Watch can display its report locally; if adding phone transfer, authenticate and
  bound it using the associated Wear node and never include raw state. Do not claim phone
  reports include watch telemetry unless an actual transfer is implemented/tested.
- [ ] Reports export only the Task 1 encoder output. Clear temporary exported files on a
  bounded expiry and use FileProvider/private cache grants if needed. No writable public
  log directory, automatic background collection or new network permission.
- [ ] Verify native phone/watch layouts in three themes/large fonts, copy/share content,
  empty/offline/pairing/waiting/retrying/TLS states and process recreation. Only use owned
  emulators through device_list/device_open; never reset another worker's AVD.
- [ ] Run affected native checks and strict builds; preserve receipts and synthetic UI
  exports. Controller reviews and performs authorized preserving physical delivery.

## Preflight rulings

| Tasks | Shared interface | Finding/ruling |
| --- | --- | --- |
| 1 / 2 | ConnectionState diagnostics snapshot and allowlisted encoder | Native UI consumes typed safe values; it must not export ConnectionState/error/status objects. |
| 1 internal | Protocol frames vs claimed timing | Stock frames cannot prove durable acceptance or STT/tool/model durations; expose observed stages only. |
| 2 internal | Watch state vs phone report | Do not imply telemetry transfer by observing the phone relay socket; explicit implementation is required. |

Owner-approved isolated parallel work can overlap unrelated server/pet work. Actual
physical/server/provider acceptance remains separate from synthetic telemetry/UI checks.
