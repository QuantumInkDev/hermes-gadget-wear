# Catalog rollout preparation

Status: preparation is authorized by the approved carousel design. Live installation,
HTTPS publication, artwork sharing and new endpoint pairing retain that design's review gate.
Preparation is verified; live source deployment remains pending review. Evidence is recorded
in [progress](../../progress.md#live-catalog-rollout-preparation--2026-10-11).

## Deliverable

Prepare private, per-source packages using the existing standalone catalog publisher.
Include an explicit mapping, existing portrait references, persistent random profile IDs,
hashed offline Pillow wheels, a separate catalog credential generated during installation,
and an owner-context service definition. Installation must create a separate directory and
virtual environment; it must preserve Hermes configuration, processes and pairing records.
Preparation must not register/start services or publish network listeners.

The controller will refuse an existing installation directory, occupied listener or changed
Serve configuration. Publish only a new catalog HTTPS port through loopback. Removal must
check that the specific route belongs to this package, remove only that listener/job, and
retain private state/credentials. Prepare a separate owner-scoped policy proposal and guarded
apply/removal commands; validate it without applying it. Do not reset all Serve/policy state.

## Verification

1. Read current profile/portrait hashes, Python runtimes, listeners and Serve configuration.
   Normalize copies of existing portraits privately, checking bounded dimensions/length/hash
   and the metadata projection. Missing Gadget mappings remain explicit setup-needed cards.
2. Test the package's actual publisher with synthetic profiles over loopback; validate its
   credential enforcement, assets, input preservation and occupied-directory/port guards.
   Parse the launchd definition and Windows PowerShell on the respective host. Exercise an
   isolated synthetic macOS job's startup/removal. Real source services, Windows job execution
   and HTTPS publication remain untested until the reviewed live rollout.
3. Re-read original profile/portrait hashes after preparation. Record software evidence and
   remaining live gates in progress and session status, and retain personal files only in
   ignored maintainer storage.
