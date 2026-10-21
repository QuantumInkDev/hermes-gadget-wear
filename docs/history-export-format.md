# Selected history export, version 1

Private source candidate; UI integration and independent/native review remain open.
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
files. Mutable owned buffers are cleared, while immutable strings/JCA internals cannot
be guaranteed erased. Writers propagate provider failures and do not close caller streams.

The phone coordinator requires exact scoped conversation IDs and a reviewed local cache
revision. A cross-thread cancellation token invalidates replaced/hidden/edited reviews.
Cache revision is checked before encryption, afterward and before acknowledgment. Output
is checked between bounded encrypted chunks; cancellation cannot undo a chunk a provider
has already accepted. Failed/cancelled output can retain ciphertext and is not announced
as saved. The future UI must own and close/finish its destination successfully before a
Saved message; provider cleanup, chooser/passphrase UI and native acceptance remain open.


## Private cache ordering boundary

The at-rest cache's encrypted plaintext schema is now 2; its authenticated record
wrapper remains version 1 (`history.v1`). Schema 1 is refused without replacing the
record: its timestamp/opaque-ID sort discarded canonical ordering and cannot be
reconstructed from that record. This private candidate has never been activated.
No automatic migration, deletion or reset is performed.

`put(scope, thread, expectedRevision, afterMessageId)` accepts a contiguous ordered
canonical page. A null anchor means the beginning of the cached conversation; a
non-null anchor names the cached message immediately preceding this page. Every
overlapping position must match exactly. New messages extend the tail. Unknown
anchors, gaps, changed messages or reordered pages are refused before persistence.
Timestamps and opaque IDs never determine ordering. Earlier pages can be replayed
when their canonical positions are known; an unplaced later page must wait for its
predecessor instead of being guessed into position. Replays do not renew retention.

The future authenticated consumer must assemble complete segmented messages in
wire order, maintain a current canonical snapshot/cursor, and supply the anchor.
It must explicitly refetch/rebuild if deletion, retention or snapshot replacement
invalidates that sequence; this cache is not canonical deletion or server authority.
No input SDK DTO, transport, picker or history-screen consumer is enabled here.

The API29 ownership regression uses an exposed caller array; until its native test
runs on a safe fixture, only compilation is established. The JVM bulk-conversion
tripwire proves the fixed constructors use element-wise copies on the host runtime.
