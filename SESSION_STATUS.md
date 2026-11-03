# Session status

Updated 2026-11-02 (night). Keep this file short: current state, next step, blockers. History lives in git.

## 2026-11-03 (12:15 am): phone 0.2.2-beta

- Phone 20004 on Play internal testing and on the phone (commit `a2504f3`): client speech uses `eleven_v3`. With
  `eleven_multilingual_v2`, Kitana's Voice Design voice sounded like an older British woman (owner-confirmed by A/B).
- Phone client speech restored after the reinstall: shared ElevenLabs key saved; all 21 profiles set to "shared BYOK key"
  (speech keys are never in the export).

## 2026-11-02 (night): 0.2.1-beta on Play internal testing

- Phone 20002 and watch 20003 are "Available to internal testers" (released 11:04 and 11:05 pm). Commit
  `ff2e166`: watch fallback crash (NetworkOnMainThreadException in `WatchRelay.close()`), and the 1x tile
  preview now uses the launcher icon. Bundles: `.local/releases/0.2.1-beta/`.
- Both devices run the Play-signed builds (phone installed by Play; watch from the bundle explorer's universal
  APK via `pm install -i com.android.vending`). All 21 profiles were re-paired on 11/2.
- Watch rotation is a device setting (`accelerometer_rotation 0`, `user_rotation 0`), not the app.
- Open: the Play Console Wear OS track reads "Inactive" even with a live release and testers attached (the
  watch Play Store says "Item not found"); the m5 profile gadget plugins were 10/11 copies until synced on 11/2.

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

2026-10-25: watch reinstalled with the pet-cache quota fix (whole-pet LRU, 64 MiB) and watch
history/inbox. All 11 m5 profile pets were also installed straight into the watch cache.
Server pet delivery fixed the same day (plugin `be3ccf4`): the hub resolved every profile's pet
against the root profile (Cthulhu) because the multiplexed gateway scopes a profile only per turn;
a pet.request sent before pairing approval is now answered on approval; failures are logged.

## Feature status (spec: docs/superpowers/specs/2026-10-18-relay-expansion-design.md)

| Feature | State |
|---|---|
| O1 pets, O2 expressions | Live; Akuma verified on the watch (listening, talking, full speech) |
| O3 Opus | Live; battery measurement still open |
| O4 ElevenLabs | Phone client speech live on all profiles |
| O5 outbox, N1 history, N2 inbox | Server live on 4 profiles; phone and watch built and installed |
| N3 sharing | Text/links done; images/documents next (server store is live) |
| N4, N6, N8, N10 | Built and installed |
| N5 walking mode | Built and installed (phone + watch) |
| N7 handoff | Built and installed (phone) |
| N9 remote wake | Server live on root; phone built and installed |

## Beta launch kit (2026-10-25)

`docs/launch/` holds the beta plan, plugin-catalog strategy, announcement copy and shot list;
`docs/install.md` and `docs/manual.md` are the tester guides. Blockers: relay features need the
Hermes core-seams patch (catalog rule 9), our plugin is a fork of the listed `gadget` (rule 16),
and owner devices are debug-signed. Nothing posted or published.

## Next

0. Owner test: Arrange sheet (phone installed 2026-10-25 19:17) and watch order follow.
1. Owner test: phone Relay history/inbox on the 4 profiles; wake from Cthulhu's Relay section.
2. Watch: surface "catalog offline" (phone Tailscale off) instead of silent still portraits.
3. N3 images/documents; Opus battery measurement.
5. Roll the Gadget relay plugin and the core seams patch to gpu5090 and arm (Windows leg 2 patch list;
   the plugin's Windows storage-privacy verifier already exists). Owner wants all three boxes eventually.
