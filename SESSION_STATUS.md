# Session status

Updated 2026-10-24 (evening). Keep this file short: current state, next step, blockers. History lives in git.

## Branch

`relay/delivery` is the single working branch (`feature/multi-box-profile-carousel` is superseded).

Server code:

- Gadget plugin: `~/Projects/hermes-gadget-sdk` branch `relay/combined` (continuity + attachments +
  remote wake + the edit-stream speech fix). Installed at `~/.hermes/plugins/gadget` on the m5.
- Hermes core seams: ported to v0.22.5 (`1c0cd66774`); bundle
  `~/Backups/hermes-watch-agent/core-relay-0.22.5.bundle`. Live on the m5 through the mesh patch
  `~/.hermes/scripts/patch-relay-core-seams.py` (re-applied by the mac fleet-update leg).

## Live on the m5 (2026-10-24)

- All 11 profiles + root: pets, Opus, client speech.
- Root (Cthulhu), personal (Kitana), critic (Akuma), biz (C. Viper): relay history, task inbox,
  outbox, prompts, controls, task outbox, attachments (grants in each `plugin-data/gadget/relay-grants.json`).
- Root: remote wake (`plugin-data/gadget/wake-policy.json`: arm Parallels VM, gpu5090 Wake-on-LAN).

## Devices

Phone and watch updated in place on 2026-10-24; backups in `.local/expansion/device-baselines/`.
Phone has sharing, walking mode, handoff and wake. Watch has walking mode.

2026-10-25: watch reinstalled with the pet-cache quota fix (`PetCacheQuota.kt`: whole-pet LRU, 64 MiB;
the old 16 MiB per-file eviction deleted manifests first). Uncommitted. All 11 m5 profile pets are in the watch cache.
Pets still reach the watch only reliably by direct cache install; server delivery after a late pairing
approval is not retried until reconnect.

## Feature status (spec: docs/superpowers/specs/2026-10-18-relay-expansion-design.md)

| Feature | State |
|---|---|
| O1 pets, O2 expressions | Live; Akuma verified on the watch (listening, talking, full speech) |
| O3 Opus | Live; battery measurement still open |
| O4 ElevenLabs | Phone client speech live on all profiles |
| O5 outbox, N1 history, N2 inbox | Server live on 4 profiles; phone built; watch side in progress |
| N3 sharing | Text/links done; images/documents next (server store is live) |
| N4, N6, N8, N10 | Built and installed |
| N5 walking mode | Built and installed (phone + watch) |
| N7 handoff | Built and installed (phone) |
| N9 remote wake | Server live on root; phone built and installed |

## Next

1. Owner test: phone Relay history/inbox on the 4 profiles; wake from Cthulhu's Relay section.
2. Watch history/inbox (in progress), then install.
3. N3 images/documents; Opus battery measurement.
4. SDK: 3 history-retirement edge cases fail against v0.22.5 (external delete while listed).
