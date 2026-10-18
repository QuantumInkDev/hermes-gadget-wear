# Preserve desktop pet art with an optional Gadget base

Status: implementation plan under the approved O1/O2 and private custom-pet scope.
No implementation, source acceptance, live assignment or rollout is claimed.

## Outcome and concrete source

A regular Hermes pet keeps its original `spritesheetPath` and file bytes. Optional
`gadgetBasePath` in the same `pet.json` selects a separately prepared Gadget base.
The existing SDK emits its ordinary hash-bound PNG base/companion manifest. There is
no new Android message, capability, listener or global active-pet setting.

Current `plugin/pets.py::package` always packages its supplied regular spritesheet.
`active_package` supplies the actual active Hermes pet's regular path. The accepted
producer is frozen in the ignored foundation manifest; its overlay review is clean.
`agent/pet/store.py::_resolve_spritesheet/load_pet` use `spritesheetPath`, ignoring
unknown metadata. The existing companion stager copies the regular base and registers
against it. All three seams must be checked against actual source, not assumed APIs.

Cthulhu's preserved full-detail base exceeds the existing 2-MiB delivery ceiling.
Its phase-calibrated alternate passes packaging and actual Android decoder/playback.
A standalone watch-only bundle does not yet provide one regular pet directory that
retains the desktop source and is directly consumable by `active_package`.

## Global constraints

- Stock SDK v0.2.0 compatibility and the existing optional pet negotiation remain.
  Absent `gadgetBasePath` retains existing behavior. Keep the 2-MiB delivered PNG,
  8-MiB bounded source, 64-KiB metadata and 3.2-million-pixel decode limits unchanged.
- `gadgetBasePath`, when present, is a nonempty relative string of at most512 UTF-8
  bytes. Reject wrong types, absolute/drive/UNC paths, traversal and escaping symlinks;
  use the existing confinement boundary and cover both POSIX/Windows spellings.
  Invalid alternate metadata refuses that package; it never silently chooses another
  pet/base. Existing chat isolation and extension-OFF behavior remain unchanged.
- The alternate passes the existing base validator; it does not expand frame/dimension
  support. It may have different artwork; do not demand content equivalence to the
  desktop original. Preserve ordinary base/companion order and overlay negotiation.
- Preserve regular metadata's `spritesheetPath`, regular atlas bytes and row count.
  Companion registration uses the selected Gadget base when an alternate exists.
  Staging uses a fresh outside directory, bounded inputs, source hash rechecks and
  distinct output filenames. Inputs/live assignments are never edited.
- No live host, provider, device, activation, global pet install, public export/push/
  merge or signing operation belongs to the implementation worker. Personal artwork
  stays ignored. Use original synthetic fixtures in committed tests.
- One implementation owner handles SDK selection and stager output together. Fresh
  independent review precedes integration. Controller owns private art and rollout.

## Task 1: SDK alternate selection and preserving staging

**Consumes:** exact accepted SDK and root-tool source from the private foundation.
**Produces:** backwards compatible package selection, stager option, tests and docs.

- [ ] Clone the frozen private SDK independently without alternates/hardlinks; verify
  clean exact head/tree and source hashes. Leave originals read-only. Root-tool edits
  occur only in the worker's T3-isolated worktree.
- [ ] Add bounded optional `gadgetBasePath` selection using actual `package` /
  `active_package` seams. Parse metadata once per package, confine the alternate and
  keep existing PNG/hash/rows/overlay validation. No wire DTO change or quota increase.
- [ ] Add optional trailing stager argument and CLI `--gadget-base`. With it, preserve
  the regular source at its normal filename and emit a distinct `gadget-base` file
  plus `gadgetBasePath`; register/validate companion delivery against that alternate.
  Include every source/output hash in the receipt. Preserve default call behavior and
  prepare-grid argument rules. Reject output-name collisions before copying/writing.
- [ ] RED/GREEN tests exercise a synthetic regular source whose encoded delivery
  exceeds2MiB with a valid alternate below the ceiling; actual SDK packaging/sender
  succeeds only with valid metadata and retains original bytes. Cover absent-field
  parity, unsafe/malformed references, invalid alternate image/geometry/oversize,
  stager registration/hash/collision failures and capability fallback. Existing pet /
  companion tests remain passing; no personal fixture is committed.
- [ ] Use the real isolated Hermes `load_pet` on a temporary profile to prove it still
  resolves the regular file while Gadget packaging selects the alternate. No installed
  home/config imports or provider/network calls; record actual module origins.
- [ ] Update `docs/pets.md` for the optional field and stager usage, retaining explicit
  live/physical limits. Append a report with exact commits/trees, commands, distinct
  cases, failures/skips, preserved-source hashes and concerns. Commit scoped changes
  and return for a combined SDK/tool independent review; no self-acceptance/export.

## Task 2: Controller integration and unified private bundle

- [ ] Generate complete net SDK/tool review packages and resolve blocking findings.
  Integrate reviewed tool source; apply the SDK delta to the current combined private
  extension foundation and run meaningful affected checks before any export.
- [ ] Stage a unified private Cthulhu directory retaining the regular full-detail base,
  its regular metadata path, the validated Gadget alternate and registered companion.
  Real isolated Hermes loading must select the regular base. Real SDK output must
  match the native-tested alternate assets/rows/overlay byte-for-byte where expected;
  rerun native acceptance only if its inputs/semantics differ.
- [ ] Keep all source art, earlier candidates, failing attempts and evidence. No live
  assignment follows. Incorporate this optional producer delta into the later reviewed
  per-profile rollout and physical O1/O2 acceptance; do not close those gates here.

## Preflight rulings

Ruling: optional local metadata is the smallest compatible seam that preserves the
regular art and existing wire contract. Cost if wrong: remove/revise the unshipped
field and stager integration before live activation. A separate watch-only pet must not
silently replace the regular active desktop art.
