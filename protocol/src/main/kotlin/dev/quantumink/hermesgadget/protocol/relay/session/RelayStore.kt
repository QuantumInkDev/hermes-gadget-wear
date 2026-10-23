package dev.quantumink.hermesgadget.protocol.relay.session

/**
 * Why a stored document cannot be used right now. In every case the stored bytes are KEPT: nothing here regenerates a key
 * over existing bytes, resets, erases or replaces them with an empty document, and there is no discard operation in this
 * stage. A later explicit user decision (and its own review) owns any discard.
 */
enum class StoreProblem {
    /** The platform key is missing or unusable while stored bytes exist. */
    KEY_UNAVAILABLE,

    /** The directory or file cannot be read or written (I/O, permissions, symlink, wrong type). */
    STORAGE_UNAVAILABLE,

    /** The store was initialised but its record is gone: this is NOT an empty store. */
    RECORD_MISSING,

    /** Authentication failed or the plaintext is structurally impossible. */
    CORRUPT,

    /** A container or format version this build does not know. */
    UNSUPPORTED_VERSION,

    /** The stored file is larger than the deliberate local cap. */
    TOO_LARGE,

    /** The bytes decode, but the state layer refuses to restore them (path in the detail). Still kept untouched. */
    UNRESTORABLE
}

sealed interface StoreLoad {
    /** Nothing was ever stored (no record AND no initialisation marker). Not a failure. */
    data object Empty : StoreLoad

    class Loaded(val document: RelayDocument, val sequence: Long) : StoreLoad {
        override fun toString(): String = "Loaded(seq=$sequence, $document)"
    }

    /** The bytes exist but cannot be used now. They are untouched. */
    class Unreadable(val problem: StoreProblem, val path: String = "") : StoreLoad {
        override fun toString(): String = "Unreadable($problem)"
    }
}

sealed interface StoreCommit {
    class Committed(val sequence: Long) : StoreCommit {
        override fun toString(): String = "Committed(seq=$sequence)"
    }

    /** The document is not storable (too many, field too long, above the local cap). Nothing was written. */
    class Refused(val reason: EncodeRefusal, val path: String) : StoreCommit {
        override fun toString(): String = "Refused($reason, $path)"
    }

    /**
     * Nothing can be claimed durable. [uncertain] is true when the new bytes MAY nevertheless be on disk (a failure
     * after the atomic replace); false when the previous record is certainly unchanged. A `Failed` is never success.
     */
    class Failed(val problem: StoreProblem, val uncertain: Boolean) : StoreCommit {
        override fun toString(): String = "Failed($problem, uncertain=$uncertain)"
    }

    /** Another writer advanced the sequence since the caller's last view: this (late) document is NOT published. */
    data object Stale : StoreCommit
}

/**
 * The one encrypted store of Relay state, shared by the typed controller, canonical voice (V5) and the cache and
 * inbox tasks (4/5): there is no second pending/voice store. Implementations encrypt with AES-GCM, authenticate before
 * parsing, bound the file and the plaintext, replace atomically and keep unreadable bytes. All methods block on disk and
 * crypto and must be called off the main thread.
 */
interface RelayStore {
    /** Reads the document without creating, resetting or substituting anything. */
    fun load(): StoreLoad

    /**
     * Atomically replaces the document. [base] is the sequence the caller last loaded or committed (0 when the store was
     * empty); a different on-disk sequence answers [StoreCommit.Stale].
     */
    fun commit(document: RelayDocument, base: Long): StoreCommit
}
