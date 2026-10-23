# Staging, profile application and Relay delivery

Discover actual tools, Hermes homes and selected profiles first; never treat display names as authority. This skill doesn't grant unrelated fleet changes. Keep homes/endpoints/art/key material private. A profile/box may be offline: stage its complete approved pack and retain a pending target rather than assigning another profile as a substitute.

## Plan

Write install-plan.json with target box/profile/home, current config/SOUL/active-pet hashes, proposed file list+hashes and exact diff, intended pet slug/voice_id, card mappings and Portrait/Pet selection, supported reload, backup location and rollback. No signing/key changes, global defaults, model setup, server-wide power changes or experimental Relay voice activation is needed to install art. Ask for explicit approval of this exact plan after art/motion/SOUL/voice approvals. Revalidate source/target hashes immediately before applying; changed targets require a new diff.

## Local or remote Hermes profile

Back up existing SOUL/config/pet before mutation. Prefer a new versioned slug/directory; preserve the former slug and source atlas. Copy the **complete** bundle (pet.json, spritesheet, companion and optional Gadget base), retaining companion metadata; stock `register_local_pet` can rewrite pet.json and drop those extensions, so do not use it blindly. Confine all relative asset paths to the pet directory. Use filesystem identity to keep staging outside source directories, including APFS case aliases; never solve containment by lowercasing paths.

Apply only approved SOUL content and profile-local `display.pet.slug` / approved TTS keys using the target's actual configuration interface. Use atomic writes and verify reads; if a multi-file apply fails, restore the backups and report partial/rolled-back status. Don't restart unrelated listeners. Use the configured profile's real resolver/load_pet and desktop client to verify slug, atlas and companion, then switch profiles and back to prove selection changes. Voice IDs/config must follow the selected profile too.

For a remote host, use its existing authorized management channel and the exact approved home/profile. Stage/validate there before changing live files. A copied skill/bundle or SSH exit0 is not a profile-selection check. Keep per-target receipts/backup hashes and independent rollback. Do not ship private keys or create enrollments.

## Quantum Relay

Where the project tools exist, stage into a NEW output directory using:

```text
python tools/pet-companion.py --metadata <pet.json> --companion <gadget-sheet.png> --output-dir <new-stage> --mouth-box L T R B
```

Use `--expression-overlay L T R B` instead of --mouth-box only for an approved supported overlay. If present, also supply `--gadget-base <gadget-base.png>`; metadata already naming it requires explicit staging. Existing outputs and source/destination aliases must be refused. Check actual CLI help/source first in case it evolved.

Confirm the selected profile's already-supported pet producer/catalog resolves the new pack, authenticated asset transfer validates exact hashes/counts/PNG bounds, and the watch cache is keyed by that endpoint/profile. Never overwrite encrypted app preferences or copy pairing keys to force art. Import cards through supported catalog/picture UI. Apply a watch-specific override only if approved; keep desktop, imported profile card and watch override independently identifiable.

Don't silently toggle a profile from Portrait to Pet because art arrived. If Pet was approved, select it deliberately; otherwise keep the new pet available with the existing mode unchanged. Verify every state on the actual watch when connected and record offline/cached fallback, profile switching, talking amplitude behavior and reduced-motion/ambient separately. Technical package validation doesn't establish physical delivery or aesthetic approval.

## Delivery manifest

Include artifact hashes, file sizes/dimensions/row counts, source references and generation/backend usage, user approval messages+hashes, SOUL diff, selected voice ID/settings and auditions, and per-target installed/verified/pending/rolled-back status. Rollback restores the old profile art/SOUL/voice config without deleting the new approved source pack. Export the skill without its .venv/cache or any personal run/art/credentials for another machine; install under that machine/profile's discoverable skills root.
