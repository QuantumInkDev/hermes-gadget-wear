# Fresh maintenance observation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the prepared maintenance driver a source-backed way to distinguish fresh whole-gateway work observations from ordinary status updates.

**Architecture:** Add an optional `maintenance_observation` object to the existing local runtime status. Only a successful sample under an actually engaged, unchanged external drain advances its sequence and sample clock. Other status writers preserve that object unchanged; the driver must fail closed on old gateways, stale/invalid samples or unsupported work sources.

**Tech Stack:** Existing Python gateway, runtime-status publisher, external-drain watcher and isolated actual-core tests. No new service, dependency, network port or Gadget method.

**Spec:** `docs/superpowers/specs/2026-10-18-relay-expansion-design.md` staged rollout/preservation contract, and `2026-10-18-m5-maintenance-driver.md`. Independent review confirmed that the old status interface cannot prove the required aggregate freshness.

## Global constraints

- Isolated source development only. Do not install or hot-reload this hook, modify a live marker, stop/start a gateway or change the pinned Stage1 package.
- Preserve normal intake, drain, shutdown, status and capability-OFF behavior. This hook observes work; it does not initiate drain, stop, cancel, admission or execution.
- The hook cannot supply its own bootstrap authority. Actual installation remains a separate gate requiring an independently valid existing pre-stop authority and a reviewed transaction.
- Do not use overall `updated_at`, RPC `answered_at`, elapsed polling time, null diagnostics or the old fail-open counter wrappers as proof of a fresh safe zero.
- Keep personal configuration, source snapshots, private clone paths and evidence ignored. Export only the new source delta from the clean upstream/O5 foundation; exclude pre-existing local changes and N1 development.

## Review focus

1. An unrelated heartbeat retaining zero must not advance sample sequence or sample age.
2. Counter failure, missing source support or malformed counts must never become valid zero.
3. Replaced/expired/foreign drain markers and released drain must invalidate observation.
4. Restart, instance/epoch drift or clock rollback must not make old samples reusable.
5. Real admission/work sources must remain accounted for across drain engagement and the observation-to-stop boundary.

## Task 1: Optional, strict aggregate observation in the existing status path

**Files:** create `gateway/maintenance_observation.py`; modify narrowly `gateway/run_shutdown.py`, `gateway/status.py` and initialization only if needed; add `tests/gateway/test_maintenance_observation.py` and affected status/drain tests. Document the new internal status contract in the existing gateway developer guide. Do not modify the installed checkout.

**Interfaces:**

- `async MaintenanceObserver.sample(runner, *, home: Path) -> dict` in `gateway/maintenance_observation.py`, awaited by the real external-drain watcher after engagement. One observer belongs to one gateway instance; injectable wall/monotonic clocks support deterministic tests. It runs count/identity validation on the owning loop, returns a bounded explicit scalar DTO, and never dispatches agent work or starts/stops a service. Marker reads and process-birth filesystem probes run off that loop using the existing asyncio executor. At most one marker read is in flight per observer; a five-second read timeout or count-to-closing-read bracket expires the observation. Sample clocks are captured at counting, not after I/O.
- Existing status gains optional `maintenance_observation`; absent means unsupported. Its valid version1 object contains `v:1`, `valid`, `instance_id`, `authority_epoch`, `pid`, `start_time`, `hermes_home`, `drain_principal`, `drain_marker_sha256`, `drain_requested_at`, `sequence`, `sampled_at_ms`, `sampled_monotonic_ns`, `counts`, and `active_total`.
- `counts` has exactly five non-boolean, nonnegative integer fields: `agents`, `managed`, `cron`, `api`, `deferred`. They represent the real sources covered by canonical whole-gateway work accounting, including all served authorities. `active_total` is their exact sum. Do not duplicate managed work already represented as an agent; follow the real source's existing counting boundary.
- A successful sample increments a per-instance sequence starting at1. Unrelated status writes never change observation fields. A failed or unverifiable sample publishes `valid:false`, fixed redacted error code, last successful sequence, and null count/total/sample-time fields; it cannot refresh a usable zero. No exception text, session identity, model/tool data, transcript or credential enters it.
- A valid sample requires an actually engaged external drain and a stable, bounded, owner-private regular marker for that same home. Bind the exact marker bytes/digest and expected drain action/principal/request time/epoch; reject malformed/expired/future-invalid markers, symlinks, replacement during sampling and drain release. The observer does not rewrite the marker. Existing ordinary drain semantics remain intact.
- Marker reads are at most16KiB; principal is1–128 UTF-8 bytes; future request times beyond5seconds fail; observation JSON is at most16KiB. Counter and sequence values are integers in0–2^63−1 (successful sequence starts at1). A backward clock or missing independently established process birth/instance identity produces invalid support, never guessed freshness.

- [ ] Add RED tests using the real status merge/publisher and watcher/sample call path: explicit zero sampling changes sequence/time; unrelated platform/status updates retain the complete observation unchanged. Exercise the actual local status serializer, preserving IDs/protocol, without inventing a new RPC verb.
- [ ] Add RED coverage of every real count source, queued/pending and managed work, API/cron/deferred activity and multiple served profiles. Audit source-specific registration/admission gates and failure behavior. If a source cannot be read authoritatively, emit invalid/unsupported instead of catch-and-zero. Preserve existing ordinary counter behavior.
- [ ] Add marker/identity tests for engaged/not-engaged/released drain, wrong principal/home/action/epoch, partial/malformed/oversized file, symlink/owner/mode changes, replacement during count reads and expiration. Preserve original marker bytes and existing ordinary fail-safe draining behavior.
- [ ] Add restart/sequence/clock cases: stale previous-instance object, reset sequence with a new instance, same-instance rollback/duplicate observation, failed count read after a prior valid zero, backward wall clock and unchanged unrelated heartbeat. Invalid results cannot satisfy the consumer's future two-sample rule.
- [ ] Implement the minimal optional field and producer with explicit source availability. Keep loop work bounded; no blocking network/provider operation, unbounded history/task scan or background worker/service.
- [ ] Prove the observation is not an admission barrier by itself. Trace and test existing new-work gates during external drain, including cron dispatch, pending claims and asynchronous ingress. If the source does not establish stability through a stop boundary, report the exact limitation; do not claim this hook alone makes live maintenance safe or silently change intake/control behavior.
- [ ] Run canonical warning-strict focused actual-core tests and affected status/drain regressions, compile/check changed source, retain all failed attempts. Test both runtime and clean foundations, prove identical stable deltas, imports and exact dependency/interpreter/source hashes. Do not repeat unrelated broad suites.
- [ ] Commit isolated source and report concrete producer/consumer schema, bounds, commands/counts/failures and unresolved admission/bootstrap limits. Fresh combined review precedes export or consumer implementation.

## Task 2: Resume the offline driver against the reviewed observation contract

Depends on accepted Task1 source and schema. Resume the original driver owner with the accepted lease checkpoint and exact contract. The remaining driver phase machine, marker/lock/crash recovery, source helper, graceful shutdown/start and failure-injection requirements remain those of the existing maintenance-driver plan. Require two separated increasing successful samples from the same instance and owned unchanged marker, fresh in monotonic time; missing/old support fails before planned stop. A synthetic supported hook does not prove installed support.

## Task 3: Separate live bootstrap and stock acceptance

No live action is part of Tasks1–2. Review the exact revised source package, preservation inventory, installation and recovery procedure. Establish independently valid existing pre-stop authority before installing the hook itself. If that authority remains unavailable, record live bootstrap as unverified and continue other features; never initiate a potentially busy stop and use its later clean marker as retroactive approval. Full feature rollout and real stock phone/watch behavior remain separate gates.

## Preflight rulings

Task1 owns producer/status/watcher schema together; Task2 consumes only its reviewed version. Task3 cannot use a capability not yet installed to authorize installing it. All three agree on capability-OFF and no-live-development boundaries. Each of the five review-focus inputs has an explicit Task1 test group. The existing user-approved scoped server development covers this isolated upstreamable change; no repeated approval question is required.

Controller ruling after initial review: the sample interface may be asynchronous to fix
the loop-blocking marker I/O finding. The existing bounded executor call is a technical
read boundary, not a new worker service or an admission authority. Counts and identity
checks remain on the owning loop, with stable marker checks around them and freshness
fixed at the count. A timeout invalidates support; it does not pretend to cancel a
blocked filesystem operation. Independent review must assess the pending-read bound.
The unrelated SQLite cleanup fixture is a separate test-hygiene commit. Report the
feature-only warning failures and the hygiene-enabled results separately; do not export
the fixture as part of the observer or claim its passing run describes a different tree.

Controller ruling after fix1 review: its N1 residual constructor process-birth probe
is a remaining spec gap in I2, even though the reviewer grades its expected frequency
Minor. Do not accept a synchronous first-engagement filesystem probe as an exception
to the explicit off-loop requirement. Correct the producer and cover the first real
watcher engagement, including `io.open`/process-birth access, before export or Task2.
This enters fix round2 as an Important spec gap; it does not reopen unrelated code.
Append the auxiliary-clone and pre-commit test-byte evidence qualifications (review N3)
and the worst-case engaged-tick delay (N2) without rewriting historical records.
