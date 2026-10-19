# Android sharing and attachment ingestion implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> with isolated implementation owners and fresh independent task reviews.

**Goal:** Share text, links, images and supported documents from Android into an explicitly
reviewed agent conversation, with verified file delivery and safe retry/cancellation.

**Architecture:** A separate phone share activity stages bounded encrypted drafts and shows
the selected box, profile, conversation and content before Send. Optional authenticated
attachment RPCs mint scoped handles; the existing native admission path captures their
verified bytes before committing a turn. History contains public attachment metadata,
never client URIs or server paths. The phone's temporary copies have independent retention.

**Tech Stack:** Android intents/ContentResolver, native Compose, existing encrypted vaults,
Kotlin/JVM relay codecs, Python SDK/grants/SQLite and Hermes native media capture.

**Spec:** Approved `docs/superpowers/specs/2026-10-18-relay-expansion-design.md` N3,
`docs/relay-server-contract.md` attachments and native admission boundaries.

## Global constraints

- Preserve separate endpoint/enrollment identities and watch-owned TLS. No gateway v2,
  new administrative listener, cloud upload service, URL fetcher or shared pairing key.
- Share receipt, URI staging, profile selection, rotation, app resume and reconnect never
  send automatically. A visible Send confirms the exact recipient/content snapshot.
- Use `relay.attachment.begin`, `.chunk`, `.finish`, `.delete` and existing
  `relay.turn.submit` with scoped `attachment_ids`; require current `relay:attachments`
  plus the existing turn permissions at their respective boundaries. No client paths.
- Chunk maximum24,576 decoded bytes, strict base64, sequential offsets; identical offset
  retry succeeds only for identical bytes. Incomplete uploads expire after ten minutes.
  Completed temporary copies default to seven days, configurable within declared bounds.
- At most4 attachments/turn,5MiB/file,20MiB/device,100MiB/profile; effective batch size
  also respects the actual configured core media cap. Never silently discard a file.
- Names at most128 Unicode scalars and presentation-only. Server-generated storage names
  derive from opaque IDs/type; display names cannot become paths, command arguments or URLs.
- Initial images are PNG/JPEG, at most4096 pixels on either axis and16 million pixels.
  Verify actual bytes/MIME/dimensions before decoding. PDF and strict UTF-8 text documents
  are advertised separately only after Task2 proves their real bounded ingestion path.
- Canonical committed admission media outlives temporary upload copies while queued or
  otherwise held. Delete/expiry/revocation of a temporary handle cannot break accepted work.
- Default-OFF attachment service, stock SDK v0.2.0 controls, Android-free protocol;
  phone API29 minimum/target37, three existing themes and large-font accessibility.
- No sensitive text, filenames, URIs, art, hosts, keys or PDF contents in public fixtures,
  logs or screenshots. Use synthetic owned files and isolated homes. No runtime automatic
  dependency installation, external converters or document upload during ingestion.

## Review focus

1. A malicious or slow content provider lies about size/MIME, blocks, changes bytes or
   completes after navigation; only a bounded stable owned draft reaches the review screen.
2. A grant/key/group/profile changes during a chunk, finish, submit or output-lock wait;
   authority is checked again and another enrollment cannot take over or inspect the upload.
3. A crash or lost reply between file completion and turn admission cannot submit twice,
   lose accepted immutable media or silently replace the reviewed content on retry.
4. A shared file contains a forged command, filename/path, PDF action or huge expanded
   content; data remains data and advertised document support requires real bounded reads.
5. A second share arrives during recording, pending speech or another share; it cannot
   overwrite the first draft, change the active agent or send to a stale selection.

## Actual source findings and ordering

The frozen private audit records accepted continuity SDK/core heads and file hashes.
Before Task1 dispatch, freeze the latest independently accepted combined SDK/core again;
Task4 history work is currently active in a separate checkout. Never build on its WIP.

The current SDK `RelayStore.reserve_turn` rejects nonempty attachment IDs, and
`CoreBridge.event` produces text-only events. Core `session_native_admission._text_event`
also explicitly rejects media. Changing only the RPC upload store cannot provide delivery.
Core `session_envelope.prepare_native` already freezes provenance/content, captures media
off-loop under a capture lease and commits `native_text_v1.media`; its public dispatch
seam must gain an explicit, default-denied media authorization without weakening text controls.

Core hosted-room attachments separately retain documents and name server-owned retained
paths in prompts. That implementation is useful evidence, but its room/member authority
cannot be fabricated or borrowed by Relay. Its path-only document lifetime also differs
from normal native media references. Relay must use actual native capture/retry/release.

`tools/file_tools.py::_read_extracted_document` and `tools/read_extract.py` provide real
document readers. PDF extraction currently involves an optional local converter and a
package-manager path that may attempt installation. Task2 must select and pin an existing
local, offline reader, test it and refuse unavailable support; merely sniffing `%PDF-` or
appending a path is not proof the agent can read the document. Full N3 acceptance requires
both working PDF and UTF-8 text documents, not an image-only implementation.

Native transport depends on the reviewed `DirectConnection.relayRequest` seam from the
remote-wake plan. Native conversation selection/receipts depend on accepted N1 consumers.
Inventory their final APIs before Task3 dispatch; do not introduce a second socket stack.
Task2 follows accepted relevant continuity server work so its registry/history changes
have one integration owner. Task1 storage can be reviewed independently while disabled.

Android's official [sharing receiver guide](https://developer.android.com/develop/ui/compose/sharing/receive)
documents intent filters, typed extras, asynchronous heavy work and confirmation/editing
before use. The [file information guide](https://developer.android.com/training/secure-file-sharing/retrieve-info)
describes provider MIME/name/size queries. Treat metadata as hints and enforce byte limits
while reading; the declared size never grants an allocation. These platform references
guide our implementation; they are not evidence of app behavior.

## File map and interfaces

SDK/core paths are relative to their isolated clones. Native paths use the existing
`dev/quantumink/hermesgadget` package under the named module.

| File | Responsibility |
| --- | --- |
| SDK `plugin/relay_attachments.py` | Strict RPC shapes, current authorization, safe metadata and upload orchestration |
| SDK `plugin/relay_attachment_store.py` | Private transactional quota/offset/digest/expiry/handle ownership and file lifecycle |
| SDK `plugin/relay_service.py`, `adapter.py` | Default-OFF setup and gated discovery/dispatch |
| Core `gateway/session_native_admission.py` | Explicit media authorization bound to registered source, route and current native options |
| Core `gateway/session_ingress_media.py`, `session_envelope.py` | Immutable validated native capture/retry/release, unchanged text-only defaults |
| SDK `plugin/relay_core.py`, `relay_store.py`, `relay_conversations.py` | Server-owned handle resolution and exact turn/receipt/history association |
| Core `tools/read_extract.py` or a small adjacent local reader seam | Explicit offline document capability and bounded real extraction without package installation |
| SDK/core focused attachment tests | Actual store/grants/admission/reader/race/crash controls |
| `protocol/.../Attachments.kt` | Strict DTOs and chunk codec on existing relay requests |
| `mobile/.../sharing/ShareActivity.kt`, `ShareIntake.kt` | Exported narrow intent receiver, typed parsing, bounded provider reads |
| `mobile/.../sharing/ShareDraftVault.kt`, `ShareUpload.kt` | Encrypted owned drafts, expiry, explicit upload/submit state and stable retry identity |
| `mobile/.../sharing/ShareReviewPage.kt`, `AndroidManifest.xml` | Reviewed recipient/content, deliberate Send/Cancel and correct share registration |
| Native/protocol sharing tests | Hostile owned providers, actual sharesheet and selected-recipient behavior |

### Task 1: Private scoped attachment store and authenticated byte RPCs

**Consumes:** Frozen accepted `RelayContext`, current grants/private-files/SQLite APIs and
existing authenticated relay envelope. **Produces:**
`AttachmentService.request(session, msg) -> bool`,
`AttachmentService.methods(session) -> list[str]`,
`AttachmentStore.begin(ctx, request_id, mime, size, sha256, name) -> dict`,
`chunk(ctx, upload_id, offset, data: bytes) -> int`,
`finish(ctx, upload_id) -> dict`, `delete(ctx, attachment_id) -> bool`, and
`resolve(ctx, attachment_ids) -> tuple[VerifiedAttachment, ...]`.
`VerifiedAttachment` freezes opaque ID, MIME, size, digest and an owned read/capture lease;
its internal path is never serialized. Report exact ownership/cleanup signatures for Task2.

- [ ] Write `test_relay_attachments.py` / `test_relay_attachment_store.py` failing cases:
  exact begin/chunk/finish/delete shapes, byte/hash mismatch, same-offset conflict,
  reconnect/restart duplicate begin/finish, cross-key/group denial and capability-OFF stock.
  Run the frozen canonical strict runner, one worker and retries disabled; retain RED.
- [ ] Implement the store with private ancestry/regular-file checks and server-generated
  names. Persist intent/offset/committed hash before acknowledging; interrupted append
  reconciles to its durable offset without accepting ambiguous bytes. Account active
  reservations plus finished bytes transactionally across processes. Cap32 incomplete
  uploads/device and128/profile in addition to byte quotas; expiry collection never
  evicts unexpired or admission-leased data to make room. Fail closed on corruption/full disk.
- [ ] Bind requests to exact profile/group/device/key/grant generations and original
  request body. Reauthorize before reading/writing and before socket output; grant changes
  invalidate temporary handles. Concurrent delete/finish/resolve cannot expose partial data.
  Finish validates exact size/digest/MIME and bounded image metadata before any decode.
  Document types may be stored for disabled Task2 tests, but cannot be advertised yet.
- [ ] Add deterministic tests for all exact quota boundaries, expiry/clock rollback,
  symlink/hardlink/path replacement, revoke-under-lock, crash after each durable transition,
  partial chunk append, duplicate final chunk and cancellation. Use actual grants/SQLite/
  owned files; fake only time and socket leaf output. Verify unrelated O5 bodies remain exact.
- [ ] Run covering strict tests and stock/default controls, commit and return source/import
  hashes, net package, commands/results and exact distinct counts. Fresh review gates this
  storage component only; no public export, activation, live installation or delivery claim.

### Task 2: Actual core image/document admission and attachment-bound receipts

**Consumes:** Reviewed Task1 API and accepted continuity server registry/receipt/history
foundation. **Produces:** Explicit `NativeMediaAuthorization` in-process value plus
`NativeAdmissionOptions.media` defaulting to `None`; only approved server-owned verified
inputs can populate it. `CoreBridge.event(item, *, attachments=())` consumes verified
Task1 inputs. Text-only event/digest behavior remains unchanged. Freeze exact public
core signatures and SDK ownership handoff in the report before the native consumer.

- [ ] Write actual runner/Gadget/SQLite failures proving PNG/JPEG and PDF/UTF-8 document
  submission captures the chosen bytes into a durable admission. Test command-like text,
  media-only input, mixed4-file batches, unknown handles and actual configured core batch
  cap. Demonstrate current text-only refusal first; do not bypass `handle_message`, source
  provenance, native FIFO or registered route identity with a synthetic admission result.
- [ ] Extend the narrow native seam: validate the media authorization against the exact
  event/source/route and fresh grant before capture and immediately before commit. Freeze
  each descriptor's MIME/digest/size against its owned bytes. Use existing capture leases,
  immutable native references and retry reconciliation, including media-only turns with
  no invented user text. Refuse controls/commands as before; do not grant arbitrary paths.
- [ ] Implement server handle resolution before admission with a durable request binding:
  original ordered handles/content hashes are part of the request fingerprint. Accepted
  exact retries return the original receipt even after temporary copies expire. A changed
  handle/body is conflict. A crash before receipt projection reconciles actual core state
  rather than resubmitting. History joins the real canonical user message to safe attachment
  metadata/IDs; never emit private native paths or infer identity from equal text/time.
- [ ] Add bounded offline document capability: strict UTF-8/no NUL for text; PDF magic plus
  actual structural/readability check using a pinned locally available reader. PDF support
  requires at most100 pages, ten-second conversion deadline and2MiB extracted UTF-8 output;
  over-limit, malformed, encrypted or unreadable documents return explicit reasons without
  silent truncation. Run parsing off-loop with killable owned worker/output bounds. Reuse
  real core readers through an explicit no-install mode where compatible; no automatic
  download, OCR, PDF action/embedded-file execution or generic archive/document expansion.
- [ ] Prove real agent-visible consumption offline with synthetic documents containing
  unpredictable markers: actual native runner and actual file reader return those markers
  through the same retained paths the admitted turn receives. A scripted model leaf may
  choose the read; the reader, extraction, native admission and retained bytes are real.
  Test installed-reader absence, timeout, malformed/encrypted PDF,100/101-page boundaries,
  output cap, invalid UTF-8, modified/deleted source after commit and sandbox path mapping.
- [ ] Test permission loss during capture/queue wait/output, crash-before/after core commit,
  two-process retry, delete/expiry while queued, terminal release and history retention.
  Default-OFF or unsupported core advertises no attachment methods. After fresh review,
  discovery may expose `limits.attachment_mimes` (unique tested MIME strings),
  `attachment_batch_bytes` (effective integer cap), `document_max_pages:100`,
  `document_extract_bytes:2097152` and `document_timeout_ms:10000`. The latter three
  appear only with tested PDF support. Add these optional fields and optional
  `Message.attachments` (at most4 summaries with `attachment_id,mime,size,name`) to
  the contract before native implementation; retain required `attachment_ids` and
  existing serialized history bounds. Unavailable metadata uses an honest placeholder.
- [ ] Run covering strict SDK/core tests on both supported foundations, existing text-only
  controls and actual image/document fixtures. Commit upstreamable source separately,
  retain full failures and packages, get fresh combined review. No live/provider activation.

### Task 3: Native share review, encrypted drafts and deliberate upload/send

**Consumes:** Reviewed Task2 limits/errors/handle lifecycle, reviewed generic relay-request
API and N1 profile/conversation/receipt APIs. **Produces:**
`ShareIntake.stage(intent, resolver, cancellation) -> ShareDraft`,
`ShareDraftVault` encrypted create/read/delete/expiry operations and
`ShareUpload.send(review: ConfirmedShare) -> ShareSendOutcome`.
`ConfirmedShare` freezes draft generation, exact profile/endpoint/enrollment/conversation,
ordered content hashes and one request UUID; no caller URL can create an endpoint/identity.

- [ ] Write pure codec/intent failures, then owned-provider native failures on API29/API37:
  real ACTION_SEND text+URL/image/document and ACTION_SEND_MULTIPLE, false/null size,
  revoked permission, blocked read, huge stream, MIME spoof, duplicate URI and late callback.
  Use typed extras and content URIs only; never resolve `_data`, `file://`, HTTP or a client
  filesystem path. Deduplicate exact repeated URI references without dropping distinct files.
- [ ] Implement a narrow exported share activity with accepted MIME filters. Heavy reads
  run off the UI thread under cancellation/deadline and bounded byte counters; copy once
  into authenticated encrypted private drafts with at most20MiB active content and32 drafts.
  Preserve granted URI access only for the staging operation; later review/upload uses the
  stable owned bytes. Display safe names, sizes/types/thumbnails and explicit errors.
- [ ] Build a native themed review screen with box/profile portrait, explicit existing/new
  conversation choice, editable prompt, selected files/remove controls and Send/Cancel.
  Text uses existing4000-character/16KiB turn bounds; links remain plain text and are not
  fetched. Oversize content needs owner editing/removal, never silent clipping. Preserve
  prior recording/conversation/scroll; a second share opens a separate draft or visible choice.
- [ ] Send authorizes using that profile's existing enrollment; unsupported/unpaired profiles
  show normal setup/support guidance. Upload sequential bounded chunks only after confirmed
  Send, then submit exactly once with ordered handles. Repeated taps/lost replies recover
  by original request/receipt, never automatic new mutation. Cancel before admission releases
  owned temporary uploads best-effort; after uncertain admission query receipt and describe
  uncertainty, never claim cancellation erased accepted work.
- [ ] Lifecycle-test screen-generation leases, rotate/background/process death/new Intent,
  identity/profile replacement, connection loss after each chunk/finish/submit, failure of
  one file among4 and explicit retry of the same reviewed content. Recovered drafts never
  upload or send. Delete/expiry controls remove only their owned temporary copies; retained
  conversation media follows server policy. Exclude drafts/handles from N10 migration and logs.
- [ ] Run strict affected protocol/JVM/lint/format/APK checks and real sharesheet/provider
  tests on API29/API37 with all three themes/large font. Preserve every owned fixture's
  APK/data/settings/signers before each install and restore exactly; never use quarantined
  fixtures or physical devices. Commit and get fresh combined native review before integration.

### Task 4: Controller integration, private rollout and actual-device acceptance

- [ ] Reapply reviewed SDK/core changes to the current accepted combined foundation, inspect
  all source parity and stock text/speech controls, integrate reviewed native commits and
  document optional capability setup/permissions/dependencies/retention/upstream series.
- [ ] Follow approved backups/review/tests/staged rollout prerequisites. Enable only the
  explicitly selected profile grants/capabilities; demonstrate denied/unsupported cases and
  actual core document-reader readiness per box. An upload-only server is not ready.
- [ ] On the authorized physical phone share a harmless synthetic text, link, PNG/JPEG,
  UTF-8 document and PDF through the real Android sharesheet, confirm each recipient and
  compare agent observations to unpredictable file content. Test4-file failure/retry and
  away-network delivery; inspect watch continuation without automatic playback or resend.
- [ ] Verify physical enrollment/art/settings preservation and temporary deletion/retention,
  record live/physical results separately, and leave N3/full goal open until every required
  gate passes. No public push/merge/release follows from this plan.

## Preflight rulings

Ruling: reuse native media capture rather than a forged hosted-room identity or prompt-only
path convention. Why: existing capture leases and committed references own retry/lifetime.
Cost if wrong: revise the unshipped in-process seam before SDK integration.

Ruling: include working PDF and UTF-8 text documents in full N3 acceptance, with explicit
document limits and no automatic parser installation. Why: upload success alone does not
show that an agent can consume a document. Cost if wrong: additional isolated reader work;
never advertise support on an unproved box or silently reduce N3 to images.

Ruling: a share opens a separate reviewed draft, not a mutation of the active conversation.
Why: receiving content is not recipient/send authority. Cost if wrong: one explicit choice
to reuse the active thread, while preserving the original recording and draft.
