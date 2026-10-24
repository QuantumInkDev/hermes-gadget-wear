# Session status

Updated 2026-10-24. Keep this file short: current state, next step, blockers. History lives in git.

## Branch

`relay/delivery` is the single working branch. It starts from the furthest relay candidate
(`bfb7124`: relay protocol, phone history, phone inbox, outbox, voice V4 off) plus the pet
factory skill. The interrupted FIX4 inbox edits were dropped. `feature/multi-box-profile-carousel`
is superseded.

Server code lives outside this repo:

- SDK: `~/Projects/hermes-gadget-sdk` branches `relay/continuity`, `relay/attachments`,
  `relay/remote-wake` (all from `v0.2.0`).
- Core: `~/Backups/hermes-watch-agent/core-relay.bundle` (103 commits on Hermes `e6f57427`).

## Baseline

`./gradlew check :wear:assembleDebug :mobile:assembleDebug`: 1,234 tests pass, 1 skipped,
0 failed; both debug APKs build.

## Feature status (spec: docs/superpowers/specs/2026-10-18-relay-expansion-design.md)

| Feature | State |
|---|---|
| N8 diagnostics, N4 quick actions | Built; installed on phone Oct 18 |
| N6 structured replies, N10 QR/migration | Built; not installed |
| O1 pets, O2 expressions | Watch side built; server pet delivery not live |
| O3 Opus | Client built; server patch not live; battery measurement open |
| O4 ElevenLabs | Phone settings built; full voice path present but off |
| O5 outbox, N1 history, N2 inbox | Phone + protocol built; no watch side; server not live |
| N3 sharing + attachments | Server store only; no phone share target |
| N5 walking mode | Audio groundwork only |
| N7 handoff | Not started |
| N9 remote wake | Server only; no client |

## Next

1. Install current phone and watch builds.
2. Deploy server SDK/core changes to one box and smoke test.
3. Build the missing pieces: watch history/inbox, N3, N5, N7, N9 client.
4. One physical test pass with the owner.
