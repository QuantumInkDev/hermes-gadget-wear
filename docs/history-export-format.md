# Selected history export, version 1

Private source candidate; Task4 adds phone retained browsing. Independent and native review remain open.
This format contains selected complete conversation text. It is separate from setup
migration and never carries endpoints, device enrollment/grants, provider credentials,
transport bindings, artwork, attachment bytes or configuration. Text entered by a user
can itself contain sensitive content; it is preserved exactly inside encryption.

The big-endian 52-byte envelope header contains: eight ASCII bytes `QRHIST01`; bytes
version=1, KDF=1, cipher=1, reserved=0; four-byte KDF work factor 600000; four-byte
plaintext length; four-byte ciphertext-plus-tag length;16-byte random salt;12-byte random
nonce. The whole header is AES-256-GCM AAD; the16-byte authentication tag follows the
ciphertext. The lengths must agree exactly and the complete input must end there.
Only this work factor/algorithm/version is accepted. Maximum plaintext is8MiB; maximum
envelope is8MiB+68bytes. No compression, paths or archive members are interpreted.

PBKDF2-HMAC-SHA256 uses the current migration codec's explicit password-byte policy:
strict Unicode, NFC, UTF-8, at least12 code points after normalization and at most1024
input UTF-16 units. No trimming, case folding or compatibility normalization. Its fixed
work factor is600000. A future password-policy change requires deliberate compatibility
handling for this format; sharing the helper is not permission to reinterpret old files.

Authenticated plaintext is a fixed-field binary sequence: four-byte schema=1; eight-byte
export time in milliseconds; four-byte conversation count. Each conversation contains
source server UUID, profile UUID, display source label, conversation UUID, eight-byte
public metadata revision, title, one-byte bookmark boolean, four-byte message count.
Each message contains message ID, one-byte role (0=user,1=assistant), text, eight-byte
creation time. Strings are four-byte byte-length-prefixed strict UTF-8. UUIDs use canonical
lowercase strings. Timestamps/revisions are nonnegative integers at most2^53-1. Extra bytes,
unknown values, duplicate conversation identities or duplicate message IDs are invalid.

Limits:256 conversations,4096 total messages,1024 per conversation,256KiB per message,
256-byte message IDs and source labels,512-byte titles with at most120 Unicode code points.
IDs are nonempty and contain no control characters. Collections are copied and the whole
selection is validated again before sealing; overflow is refused without truncation.

Wrong password, tamper, malformed/unsupported data and read failure share `Invalid`.
Authenticated readable text is not server authority, a pairing grant or permission to
import or send. A caller owns the streams and password; the codec creates no plaintext
files. Mutable owned buffers are cleared, while immutable strings, JCA and accumulator internals cannot
be guaranteed erased. Only explicitly owned mutable arrays are cleared; this is not a
guarantee that every plaintext copy has been erased. Writers propagate provider failures and do not close caller streams.

The phone coordinator requires exact scoped conversation IDs and a reviewed local cache
revision. A cross-thread cancellation token invalidates replaced/hidden/edited reviews.
Cache revision is checked before encryption, afterward and before acknowledgment. Output
is checked between bounded encrypted chunks; cancellation cannot undo a chunk a provider
has already accepted. Failed/cancelled output can retain ciphertext and is not announced
as saved. The future UI must own and close/finish its destination successfully before a
Saved message; provider cleanup, chooser/passphrase UI and native acceptance remain open.


## Private cache ordering boundary

The at-rest cache plaintext is schema 3, retaining the existing authenticated wrapper,
AAD and `history.v1` filename. Schemas 1 and 2 return typed `LEGACY_UNBOUND`; other
versions return `UNSUPPORTED`. Old identities lack a review lineage and cannot be
migrated by guessing. Opening a legacy, unreadable or missing-key record never resets
it, returns empty history, changes its marker or creates a replacement key. Old bytes
remain exact until a user confirms an exact reviewed discard. New synthetic tests
are not restoration of any installed or quarantined fixture.

Schema 3 owns an endpoint namespace (digest of the canonical TLS endpoint), that
canonical endpoint, the phone's public enrollment label, pinned Info profile UUID
and deliberate review epoch. It contains no secret keys or invented server UUID.
Catalog/display IDs confer no authority. Local revisions are distinct from public
metadata revisions. Stored SDK 120-scalar/512-byte and Core 100-character sanitized
titles fit the existing bounds; raw rename input limits do not enlarge this format.

`put` requires a typed `Beginning` or `After(messageId)` anchor. Production receives
complete owned `HistoryAssembly` emissions directly at the authenticated page
acceptance boundary, with exact enrollment/conversation/source/review scope, listing
generation, page, flow and cursor-chain proof. The bounded UI transcript is not the
feed. A beginning is accepted only for an actual authenticated initial/rebuild chain.
An After predecessor must have been traversed in this same listing; merely being in
an older cached tail is insufficient. Positional overlap must match exactly. Wire
order is retained even for equal or decreasing creation times. No opaque ID/time sort
or truncation is presented as complete.

Pruning precedes replay and append. Default retention is 90 days, configurable 0..365.
A persisted receipt-clock high water prevents backward wall-clock movement from
renewing ages. Exact retries do not renew receipt age. Expiry marks a retained
fragment incomplete and requiring rebuild, even after all its text expires. These
content-free per-thread markers occupy the same bounded 256-thread capacity; they
are never silently evicted to make room for new identities. Expired IDs cannot be
silently re-added. Capacity and size refusals remain explicit. Retention zero writes
no retained text and advances no durable text resume point.

Conflicts distinguish local revision, stale public metadata, missing anchor,
sequence, capacity, invalid value, unreadable/key/missing/unsupported record,
legacy-unbound, uncertain write and rebuild-required states. A deliberate rebuild
requires an exact review of namespace/conversation, record version/revision/size,
record/marker/pending hashes. Unreadable global bytes or a global retention setting
are labelled as affecting all profiles, not one conversation. The descriptor is
rechecked at mutation. No automatic erase, marker repaint or catch-and-empty exists.
A cache-only reviewed rebuild does not delete canonical Hermes history or clear
unresolved typed/voice/metadata intents. Canonical delete remains a distinct operation.

Text is encrypted with AES-GCM/AndroidKeyStore, authenticated before decode, written
off main through atomic replacement and directory fsync. Cache promotion precedes
the aggregate resume commit. Failure/uncertainty leaves progress unacknowledged.
RelayDocument alone owns pending operations, resumes and eviction obligations. A
revocation/deletion obligation is committed before removal, and cleared only after
confirmed cache and aggregate content removal. Repeating after a failed final commit
is idempotent; removal-pending stays visible. Existing-source Hermes retention is
unchanged and is not silently included in Relay-only local search.

Archive v1 is unchanged: its server UUID is separate export provenance, unavailable
from the current Relay wire. The adapter requires independently reviewed provenance
or returns `UNBOUND_PROVENANCE`. Incomplete/rebuild fragments return `REBUILD_REQUIRED`.
Message array order is canonical order and may have non-monotonic timestamps. Task7
owns actual SAF selection, provider close/commit and any reviewed archive-version
change; no profile UUID or endpoint digest is fabricated as export provenance.

API29 element-ownership and real AndroidKeyStore tests are compiled only in this
source task. JVM software-key and callback schedules establish neither native
filesystem/power-loss durability nor physical/live acceptance.
