# QR setup and configuration migration implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> to implement this plan task-by-task with fresh implementation and review agents.

**Goal:** Review connection metadata from a QR code and safely move configuration,
artwork, actions and widget templates to another installation without moving credentials.

**Architecture:** Android-free bounded codecs define QR metadata and an authenticated,
passphrase-encrypted migration archive. The phone explicitly reviews decoded settings,
then merges through the existing stores with a recoverable import journal. Independent
enrollment remains mandatory; Android owns launcher widget placement.

**Tech Stack:** Kotlin/JVM, existing serialization JSON, JCA AES-GCM/PBKDF2, JDK ZIP,
Android document/photo pickers and Compose. QR raster encoding/decoding may add only
the pinned ZXing core dependency after primary-source/version and compatibility checks.

**Spec:** `docs/superpowers/specs/2026-10-18-relay-expansion-design.md`, N10.

## Global constraints

- Phone API29, Wear API33, target API37; Android-free protocol and stock SDK v0.2.0.
- Keep Light/Dark/Electronic Moonlight, artwork/GIFs and rows × columns widget semantics.
- Export no device identity, pairing key, Keystore data, provider key, authorization token,
  history, tasks, microphone recordings, cached approvals or active session receipt.
- Connection metadata is private configuration, never a credential or proof of server
  ownership. No automatic network request, catalog discovery, enrollment or TLS downgrade.
- Imported LAN/tailnet cleartext consent is reset. Existing consent/identity is never
  overwritten. A new installation enrolls independently through normal approval.
- Existing settings/art/actions remain intact on validation/cancellation/failure.
  No launcher widget ID is imported as authority; re-adding widgets may be necessary.
- Private fixtures/art/addresses/credentials stay ignored. Synthetic examples only in git.
- No live server, physical device, provider or quarantined-emulator operation by workers.

## Review focus

1. A valid but maliciously edited QR remains untrusted metadata requiring full host review.
2. Huge/duplicate/traversing ZIP members or excessive declared sizes fail before extraction.
3. Wrong passphrase, modified ciphertext or incomplete archive never mutates local stores.
4. Existing profile/action/widget collisions are previewed; import cannot replace enrollments.
5. Crash/restart between store writes is recoverable without losing pre-import settings/art.

## Verified source boundaries

`ProfileVault` encrypts endpoint metadata separately from `ConversationVault` phone
identities. `CatalogVault` owns sources/pages/fallbacks/watch-picture descriptors and
ordering; `CatalogRepository` publishes reviewed roster/art changes. `PortraitStore`
and existing GIF storage validate bounded content-addressed artwork. `QuickActionStore`
owns encrypted editable actions. `HomeWidgetStore` uses a credential-free atomic instance
map and revision-guarded settings; its launcher IDs are installation-local.

Android document creation/opening and photo picking avoid broad storage permission.
Use the existing native layout and store APIs rather than copying encrypted files or
decoding other stores' private on-disk formats. The actual source map must be checked
before Task2 and amended with exact GIF/appearance APIs, not guessed.

## File map

| Path | Responsibility |
| --- | --- |
| `protocol/.../SetupMetadata.kt` | Strict QR schema, review DTO and expiry rules |
| `protocol/.../MigrationArchive.kt` | Bounded credential-free archive, authenticated envelope and payload validation |
| `protocol/src/test/.../SetupMetadataTest.kt` | QR trust/expiry/bounds/host validation |
| `protocol/src/test/.../MigrationArchiveTest.kt` | Roundtrip, tamper, limits, schema, exclusion and hostile ZIP tests |
| `mobile/.../SetupTransfer*.kt` | Native store snapshot, import preview/journal and document/photo QR flows |
| `mobile/.../MainActivity.kt` | Explicit Settings entry to the transfer screen |
| `mobile/src/androidTest/.../SetupTransferTest.kt` | Real vault/Keystore/file/picker/UI import and rollback fixtures |

### Task 1: Bounded QR metadata and encrypted migration format

**Consumes:** Existing `Endpoint`, `EndpointProfile`, `ProfileCatalog`, `QuickActions`
validation and pure theme/action/catalog types where applicable. No native store files.
**Produces:** Public pure types `SetupMetadata`, `SetupMetadataCodec`,
`MigrationSnapshot`, `MigrationArchive`. Exact field/signature contract is documented
in the task report for Task2 before that consumer begins.

- [ ] Write failing behavioral tests for strict QR metadata parsing, expiry and private
  connection preview, plus real encrypted archive roundtrip and tamper failure.
- [ ] Implement version1 QR metadata: random 128-bit setup ID, issued/expires epoch times,
  lifetime at most10 minutes, bounded human label and endpoint/catalog metadata. Exact
  object keys and JSON primitive types; <=4KiB UTF-8, depth<=6, no arbitrary extra fields.
  At decode require sane injected current time, no >30s future issue, unexpired interval.
  Reject URI credentials, fragments, query strings, unsupported schemes and malformed hosts.
  Return an explicitly untrusted review object; do not grant consent or enroll a peer.
  Replay spending is a durable installation-local Task2 concern at confirmed import;
  Task1 exposes the immutable ID and expiry, never claims cryptographic QR authenticity.
- [ ] Define a version1 credential-free snapshot with bounded profile/catalog metadata,
  ordering, theme choices, actions, widget templates, static/GIF artwork references and
  byte members. Reuse existing endpoint/catalog/action limits, and document any tighter
  archive-specific cap. At most64 profiles,128 actions,128 widget templates and128 artwork
  entries; each reference must resolve exactly once. Maximum JSON metadata512KiB,
  artwork member8MiB, aggregate uncompressed32MiB, archive/envelope40MiB,256 ZIP entries.
  Reject unsupported versions/fields, invalid IDs, duplicates and unsupported theme/layout
  values. Widget templates contain preset/ordered profile references/theme, never launcher
  IDs or Android PendingIntents. No raw native settings dump or opaque extra map.
- [ ] Implement ZIP serialization with fixed allowed member paths, content-addressed
  artwork names and exact manifest sizes/SHA-256. Streaming reads enforce actual quotas
  even with absent/false ZIP size metadata; reject duplicates, directories, absolute,
  traversal/backslash/NUL names, symlinks where detectable, unexpected or trailing members,
  missing/mismatched members, corrupt/overlapping payloads and unreferenced artwork.
  No filesystem extraction API. JSON duplicate-key handling must be explicit and reject
  ambiguous inputs rather than accept last-key-wins at security boundaries.
- [ ] Encrypt the complete archive in an explicit bounded binary envelope using
  PBKDF2-HMAC-SHA256 (v1 fixed600,000 iterations, fixed bounded decoder policy), random16-byte
  salt, AES256-GCM random12-byte nonce/128-bit tag and version/header AAD. Passphrase
  >=12 NFC-normalized Unicode code points; no trimming/case folding and no persisted key/passphrase. Reject malformed
  Unicode before normalization and keep the bounded input length. Validate header lengths/algorithm/KDF
  bounds before expensive allocation or work. Wrong password and tamper yield one safe
  typed failure and no partially returned snapshot. State honestly that password entropy
  controls offline attack resistance. Clear owned mutable plaintext/key buffers when done;
  do not claim guaranteed erasure of immutable JVM strings/crypto internals.
- [ ] Test real hostile archives/envelopes and credential-sentinel exclusion, including
  wrong password, flipped header/body/tag, truncation, compression bomb, dishonest lengths,
  ambiguous JSON, unknown fields, expired/future QR and URI-secret cases. Do not use tests
  that merely mirror a second codec implementation. Retain true initial failures.
- [ ] Run warning-strict affected JVM tests, protocol Android-import check, compile and
  formatting. Commit only scoped pure source/tests, append exact schema/API/evidence report.
  No new dependency is needed for this format task; QR image work belongs to Task2.

### Task 2: Native reviewed QR and export/import workflows

**Consumes:** Reviewed Task1 codecs plus actual stores listed above.
**Produces:** A working phone Settings destination for displaying/importing metadata QR,
encrypted export and reviewed non-destructive import, with durable replay/import journal.

- [ ] Inventory exact store APIs, GIF validation, appearance settings and widget mappings;
  record the source map and collision/rollback strategy before production edits. Keep
  current enrollment/provider stores outside the exported/imported allowlist.
- [ ] Add render/import QR through bounded user-selected photo/document content; optional
  camera scan must use a supported native API and explicit permission. A photo QR path
  must work without camera permission. Check the pinned upstream ZXing dependency/API
  from primary sources before adding it. Bound bitmap dimensions/pixels before decoding.
- [ ] Preview full scheme/host/port/path/label and untrusted origin; explicit add confirms
  metadata only. Durable bounded replay journal (expiry cleanup, fail-closed corrupt state)
  spends an ID only with successful commit. Cancel, expired or replayed codes do not add.
- [ ] Snapshot allowlisted store values/art through owned APIs on IO workers, show exactly
  what export contains/excludes, confirm passphrase twice, and use ACTION_CREATE_DOCUMENT.
  Explain that exported configuration contains private host names/art; it is encrypted.
- [ ] Open encrypted archive via ACTION_OPEN_DOCUMENT, bound streamed bytes, request
  passphrase, fully validate/decode before any store mutation, and display a merge preview
  with explicit selection/collision choices. Default to preserving existing entries; never
  change identity/provider/consent stores. Fresh imported references must be remapped
  consistently across actions/art/widgets. Missing profiles leave templates unbound safely.
- [ ] Implement recoverable staging/journal and original-value backups; validate writable
  capacity before commit, serialize import against normal store mutations, and recover
  safely after each interrupted phase. Publish catalog/watch/widget refresh only after
  successful commit. Do not claim a multi-store transaction without tested crash handling.
- [ ] Keep unplaced widget templates for selection when adding a launcher widget. Explain
  launcher placement limits; do not pretend imported IDs place widgets on the home screen.
- [ ] Test real Android stores/Keystore before/after import, encrypted-file roundtrip,
  collision/cancel/tamper/quota/failure/restart behavior, QR raster decode and all three
  themes at normal/large font. Use independent API29/API37 synthetic fixtures, preserving
  APK/data/settings. No launcher reinstall/uninstall/reset or quarantined-device access.
- [ ] Exercise the largest supported realistic artwork archive on a constrained-memory
  API29 fixture; measure actual heap/time and native crypto/ZIP interoperability. Keep all
  KDF/archive/decode work off the main thread. A source allocation estimate is not proof
  of low-memory usability. Read failures must use safe generic wording, not assert that
  every failure is a wrong password; never log raw URL-bearing construction exceptions.
- [ ] Carry the source review's Minor platform questions into native validation: test
  ownership when a non-standard collection exposes its own array on API29, and use
  stable privately owned ciphertext through in-place decryption. Record the caller
  no-concurrent-mutation contract. If the native collection copy aliases data, correct
  the narrow format seam with an owned copy and a failing platform regression before
  consuming it. Retain the initial source review's untested-platform qualification.
- [ ] Run affected strict tests/lint/formatting/all four APK compilation, commit focused
  source/test/evidence and report. Controller obtains fresh combined review before integration.

### Task 3: Independent fresh-install migration and delivered-device acceptance

**Consumes:** Reviewed integrated Task2 implementation.
**Produces:** Source/APK-bound native and authorized physical evidence, honest remaining gates.

- [ ] In separately owned fresh synthetic installations, export all seven widget layout
  templates plus profiles/actions/theme/PNG/GIF and import after independent enrollment.
  Prove no identity/provider key transfer, exact supported art bytes and safe unbound items.
- [ ] Exercise interrupted import/restart and existing-install conflicts without a reset;
  capture explicit review/cancel/replay/expiry and launcher re-add behavior.
- [ ] Controller performs authorized preserving phone update and owner/device tests when
  connected. Watch receives normal reviewed metadata/art publication; its identity remains
  unchanged. No physical assumption is substituted with emulator screenshots.
- [ ] Update status/progress with exact tests and limits. Full N10 completes only after
  the required software/native/physical gates; no public release is authorized.

## Preflight boundary scan

| Pair / internal seam | Ruling |
| --- | --- |
| Task1/2 QR | Untrusted metadata + expiry/ID, never credential, authenticity or automatic host access |
| Task1/2 archive | Strict credential-free DTO + authenticated bounded envelope; no native opaque store dump |
| Task2/3 identity | Import preserves/excludes keys; new installation enrolls independently |
| Task2 widgets / launcher | Portable templates only; Android IDs and placement remain local |
| Task2 multi-store commit | Journal, captured originals and serialized writes, tested crash recovery |
| N1/N2 versus N10 | No history/tasks/receipts transfer and no edits to their private SDK/core clones |
| All five review focuses | Owned by Task1 hostile-input tests or Task2 real-store conflict/crash tests |

Source mapping is provisional only where explicitly assigned to Task2 preflight. Task1
is independently usable/testable pure code; Task2 may require a recorded schema amendment
after actual store inventory, before consumer implementation. No live authority is added.

## Controller resolution before the first real archive — Task1 review

The first review found mutable collection aliases could invalidate a previously checked
snapshot and still produce an unreadable export. Fix1 must own immutable defensive
copies, revalidate the exact stable manifest before sealing, and own artwork bytes through
validation/serialization. Tests must mutate original/nested collections and artwork inputs.

Settle the draft v1 passphrase policy now: NFC-normalized strict UTF-8 and exactly600,000
PBKDF2-HMAC-SHA256 rounds. [OWASP's password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2)
lists600,000 for this PBKDF2 variant. Applying that work factor to this passphrase-derived
archive key is our implementation choice; it makes no FIPS claim. The earlier210,000
candidate and its evidence remain historical, and no real archive or client has shipped.
The increased work and Unicode policy require new independent vectors and later Android
timing/interop tests. Future policy changes need an explicit format-version decision.
