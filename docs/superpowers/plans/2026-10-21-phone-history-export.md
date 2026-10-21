# Encrypted selected-history export — private native successor

Spec: approved 2026-10-18 expansion, configurable encrypted history with optional
encrypted export. Base f4da724 (cache candidate, review still pending). Full review
starts at c91f012 and must cover both cache and export. No integration or activation.

Ruling: continue independent source work serially while delegated-provider quota is
exhausted; retain a fresh whole-branch review rather than creating more pending child
jobs. Cost if wrong: private interface rework. No device/AVD/live/provider/power/art/
enrollment action or new dependency. Preserve all old evidence and recovery gates.

## Task 1 — Portable archive

Produces Android-free HistoryArchive DTOs/codec. Explicit allowlist: public server and
profile UUIDs, display source label, conversation UUID, public metadata revision, title,
bookmark, complete user/assistant message ID/text/time, export time. Exclude endpoints,
device/enrollment/provider keys, grants, bindings, attachment bytes and configuration.
No use of EndpointProfile/CatalogSource or migration payloads for transcript storage.

- Versioned binary plaintext, strict UTF-8 and canonical UUIDs; defensive collections
  and validation immediately before sealing. At most256 conversations/4096 messages/
  1024 per conversation/256KiB UTF-8 per message/8MiB total. Reject duplicates and
  overflow without truncation. No filesystem paths or decoding before authentication.
- Distinct QRHIST01 envelope magic;52-byte authenticated header, AES-256-GCM16-byte tag,
  random16-byte salt/12-byte nonce, fixed PBKDF2-HMAC-SHA256600000 using current migration
  codec's strict NFC password-byte policy and minimum12 code points/1024 UTF-16 maximum.
  Share its validation internally without altering migration wire bytes or behavior.
  Do not adopt the stale210000 memory note; authoritative source uses600000.
- Entire header authenticated; reject unsupported algorithms/versions, hostile lengths,
  work factors, truncated/trailing bytes before expensive work/allocation where possible.
  Wrong password/tamper/malformed input share a typed Invalid result. No password/key
  persistence, plaintext temporary files or raw-content diagnostics. Caller owns streams.
- Write tests first. Expected RED: encrypting a valid selection through a missing-feature
  scaffold fails the round-trip assertion. Then implement. Verify selected tamper/format/
  Unicode/quota/copy/output-error behaviors and full protocol suite offline. No retries.
- Commit Task1 and ledger actual command/counts. Expected GREEN: protocol tests pass with
  the five existing actual-SDK integration skips explicitly retained.

## Task 2 — Phone selection and publication boundary

Consumes HistoryArchive from Task1 and existing PhoneHistoryCache.read/scope/local revision.
Produces a suspend exporter requiring exact reviewed conversation IDs, scope and revision,
an explicit source display label, caller-owned output, passphrase and a thread-safe
review-cancellation token. Never infer all profiles, auto-send, connect or open a document picker.

- Read exact scoped cache, require unchanged revision and every selected ID still present;
  serialize only selected conversations. Copy caller lists/password before dispatch;
  prepare/derive/write off caller thread. Clear owned password/envelope buffers afterward.
- Guard current selection before read, after prepare/KDF and each output chunk. A cancelled
  or replaced selection yields no publication success. No automatic retry. A provider
  failure propagates; caller must finish/close its document before showing Saved.
- Write deterministic regressions first using actual encrypted files and a gated encoder
  wrapping real crypto; invalidate while gated and prove output remains empty. Also test
  exact subset/decrypt, stale revision/missing selection, caller mutation and output fault.
  Expected RED: stale/current guard missing. Then implement and run full offline repository
  check plus Android instrumentation compilation. Expected GREEN: no failures/warnings;
  inherited optional SDK skips and unexecuted native gates remain explicit.
- Commit, pin complete base/head/tree/source hashes and patches, record retained REDs and
  invalid probes, prepare one fresh independent review. No author self-acceptance.

## Review focus and remaining scope

Review ciphertext-only publication, incomplete provider writes, cancellation during KDF,
future password-policy/version compatibility, raw byte vs scalar limits, exact IDs/scopes,
defensive copying at asynchronous boundaries, migration regression and memory/CPU limits.
UI/SAF integration, human passphrase entry/review, native filesystem/Keystore/API29/current
OS and physical testing remain required later. This is not full N1/15-feature acceptance.
Keep original N6 exact recovery/N10 preservation OPEN/unwaived and retain workspace/evidence.
