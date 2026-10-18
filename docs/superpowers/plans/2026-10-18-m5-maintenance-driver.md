# Bounded M5 maintenance driver implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the reviewed private source-swap procedure into a bounded, recoverable maintenance operation before the approved staged server rollout.

**Architecture:** One private driver owns a maintenance lease from preflight through drain, planned shutdown, the existing journaled file swap, verification and controlled startup. It consumes the reviewed package and current source-bound host evidence. Existing gateway controls and the accepted file-swap helper remain the only control and source-mutation paths; the driver does not create another installer.

**Tech Stack:** Python 3.14, standard-library Unix sockets/filesystem/locks/subprocess, existing psutil, launchd, and pytest with real temporary filesystem and process fixtures.

**Spec:** `docs/superpowers/specs/2026-10-18-relay-expansion-design.md`, scoped staged rollout and preservation requirements. The private accepted rollout procedure supplies concrete host bindings; personal paths, endpoints and receipts remain ignored.

**Source-backed amendment:** partial implementation/review established that overall status timestamps cannot prove aggregate sample freshness. The separate [maintenance observation plan](2026-10-18-maintenance-observation.md) owns the producer prerequisite; the full driver must consume that reviewed contract and fail closed on old support. The original timestamp-only idle requirement below is insufficient and must not be implemented as authority. Live bootstrap remains separate.

## Global constraints

- This plan authorizes isolated implementation/tests, followed by independent review. Its worker performs no live control, marker, source, service, device or provider operation.
- Stage 1 installs only the already reviewed runtime package with all four optional capabilities OFF. N1/N2 development is excluded from this package.
- Preserve enrollment, profile configuration, artwork, service-plist bytes, dependencies, canonical history, credentials, signing and power settings. Never restore canonical databases over newer work.
- Use the existing drain marker and planned-stop watcher. No SIGUSR1/update restart, SIGKILL, bootout, service reinstallation or broad updater.
- Unknown/stale/malformed authority or ownership fails closed. A null diagnostic work list never means zero work.
- The whole maintenance lease coordinates cooperating invocations of the agreed package. It cannot exclude arbitrary external source writers or manual service starters; retain repeated identity/hash guards and document that boundary truthfully.
- Keep every failed receipt. Never infer successful shutdown or recovery from a timeout, matching content alone, a fresh transport timestamp or a later passing run.

## Review focus

1. A second process or borrowed expired lease must not interleave maintenance or source recovery.
2. Fresh RPC answers containing stale or invalid aggregate data must never authorize stopping.
3. A crash around marker publication must not remove a foreign marker or expose partial JSON.
4. A respawn, PID reuse, incomplete wrapper exit or occupied listener must prevent a swap.
5. Failed startup must never cause rollback beneath a live replacement or overwrite foreign source.

## File map

All runtime files are in the worker's private clone and later copied by the controller only after review:

- Modify `apply-reviewed-files.py`: add a validated, borrowable whole-maintenance lease around its existing operation journal. Keep receipt-bound swaps and recovery intact.
- Create `maintenance_driver.py`: phase machine, immutable limits/baseline, marker ownership and durable driver receipts; call the existing helper for writes.
- Create `maintenance_host.py`: bounded actual local control-socket, process/listener and launchd probes, plus the one controlled start operation.
- Create `test_maintenance_driver.py`: real temporary files, lock contenders, crash/fault injection and an actual local socket protocol fixture; replace only host control/probe boundaries.
- Modify `m5-rollout-procedure.md`: exact commands, results, recovery and unsupported concurrency boundary.

## Task 1: Offline-tested maintenance transaction

**Interfaces:**

- `maintenance_lease(base: Path)` yields an active lease bound to canonical BASE, current process and held lock object. The source helper acquires it by default; the driver passes that exact live lease to sequential `run_swap(..., maintenance_lease=lease)` calls. Forged, closed, wrong-base and inherited-after-fork leases fail. Existing per-operation journals and operation lock remain separate and nested in one consistent order.
- `perform_rollout(package: Path, baseline: Path, *, host: HostOperations, limits: Limits) -> RolloutReceipt` is the tested production phase machine. `HostOperations` supplies the actual socket, process, listener and launchd boundaries; tests do not substitute a second phase machine or installer.
- `Limits` defaults: 3-second RPC/individual command bound, 300-second drain bound, 90-second graceful-stop bound, 90-second startup bound, 1-second sample spacing, two consecutive distinct fresh idle aggregates. All bounds are finite positive values; monotonic time governs deadlines.
- CLI defaults to read-only `verify`. Mutating `execute` requires explicit package and baseline paths and emits the exact durable operation ID. A later explicit `recover --receipt ID` uses that receipt; there is no automatic guessed recovery on startup.

- [ ] Write RED tests for lease contention across real processes, lock-path replacement, expired/foreign/forked lease borrowing and no release gap between apply, after-hash verification, startup and any recovery. Test the actual accepted helper and retain all its existing cases.
- [ ] Implement the smallest lease extension without duplicating the existing source writer or journal. Persist driver header/entry before any marker or write; bind package/protected manifests, source/control-code hashes, baseline identity and operation ID. Use private canonical paths and held directory descriptors consistent with the accepted helper.
- [ ] Write RED phase tests for exact PID/birth/home/served-set/socket ownership; mismatched response IDs/protocol, oversized/truncated socket records, unavailable process birth, stale status, booleans/floats/strings/null aggregates, changing instance, malformed work detail and busy timeouts. Require persisted `updated_at` after this owned drain and two distinct separated fresh zero aggregates; a valid aggregate with null detail may pass. Status `answered_at` alone cannot pass.
- [ ] Implement actual bounded identify/status requests against the existing owner-only socket. Establish process and wrapper birth identities independently, the complete reviewed listener set and exact launchd/plist binding. Revalidate protected hashes and capability-OFF configuration before mutation. No raw environment, script-body argv, transcript, key or artwork bytes enter logs.
- [ ] Write RED marker tests for preexisting symlinks/files, concurrent publication, partial writes, death before/after publication, inode replacement with identical bytes, TTL expiration and failed fsync. Publish complete exclusive mode600 JSON with durable ownership recorded before exposure; remove only the exact owned object with exact bytes. Never guess ownership after an ambiguous receipt.
- [ ] Implement the existing drain schema and planned-stop schema without changing gateway source. Use a unique drain principal and fresh verified target PID/birth. The stopper PID is not a liveness lock; the existing planned-stop TTL is 60 seconds. Keep the drain and lease until the exact safe transition is established.
- [ ] Write RED stopped-state tests for gateway disappearance without clean completion, old wrappers alive, PID reuse/respawn, last exit nonzero, occupied listeners, remaining socket/pointer, stale clean marker and stop timeout. Require a fresh successful clean-shutdown marker and stopped runtime receipt, known original gateway/wrapper exit, registered job waiting without PID and successful last exit, and the complete listener set closed before calling the existing swap helper.
- [ ] Implement apply, explicit after-hash/protected-state verification, removal of only owned markers and one `launchctl kickstart` without `-k` for the unchanged registered job. A new instance must match home/served set and reviewed source bytes, and regain the complete expected listener topology. Do not treat the Git commit string as proof of installed content. Do not activate optional features.
- [ ] Write RED recovery tests for death at every phase, swapped foreign bytes/identities, failed startup, failed journal/fsync, cancellation, unrelated new instance, busy replacement and stop failure. Source rollback uses the original apply receipt only after the replacement is independently and gracefully stopped. Ambiguous ownership or live state leaves the service stopped when already stopped and reports manual recovery; never force-kill, overwrite foreign files or restore databases. Before a stop, timeout/cancellation may release only its safely owned drain; do not label an uncertain shutdown aborted-and-running.
- [ ] Run warning-strict existing helper and new driver tests against the exact private source bytes. Preserve first failures and distinguish unique cases from repeated executions. Run source compilation/static checks required by applicable guidance. Hash the final source, manifests and evidence; commit only the private implementation. No live dry-run is necessary for this worker.
- [ ] Append the SDD report with APIs, exact base/head, test commands/counts/failures, state-transition table and unsupported boundaries. Return for a fresh combined specification/quality review. Source acceptance is separate from an actual maintenance operation.

## Task 2: Controller-owned staged operation and stock acceptance

Depends on Task 1 being accepted by independent review. This is an operation, not another worker implementation task.

- [ ] Copy only reviewed driver/helper bytes into the agreed private package; retain originals and rebind its artifact manifest. Refresh all running identity, source, configuration/enrollment/art and capability-OFF evidence and backups. Any drift requires reconciliation before execution.
- [ ] Confirm no competing known rollout/updater/start operation, acquire the reviewed maintenance lease and execute the bounded driver once. Record drain, shutdown, swap, protected hashes, controlled startup and topology receipts. Follow its exact recovery outcome if any stage fails.
- [ ] Verify fresh stock phone/watch reconnect, profile isolation, harmless typed replies and existing voice behavior. Keep optional features OFF until their individual capability/native/physical gates are ready. Device absence does not count as a stock acceptance pass.
- [ ] Record verified results and remaining gates in `SESSION_STATUS.md` and `docs/progress.md`. Do not close the full fifteen-feature goal on source installation alone.

## Preflight rulings and self-review

Task1 owns driver/helper lease and test/procedure interfaces together, avoiding a nested-lock or unprotected-gap split. Task2 consumes the reviewed exact driver and explicit receipt; it does not implement a second operational path. Both tasks preserve the same capability-OFF and no-database-restore contract. Each review-focus failure has a named Task1 test group. Physical stock behavior remains explicitly Task2.

Ruling: the already approved staged rollout and parallel reviewed implementation cover this concrete offline driver task. No repeated approval prompt is needed. Persistent service definition, credentials, power policy and optional feature activation remain outside this driver.
