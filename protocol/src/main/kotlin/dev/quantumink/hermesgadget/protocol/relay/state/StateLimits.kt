package dev.quantumink.hermesgadget.protocol.relay.state

/**
 * Bounds of the pure temporal/state layer.
 *
 * Two kinds of value live here and are never mixed:
 *  - SOURCE values mirror the accepted SDK/core source (each names its origin); they are protocol facts.
 *  - POLICY values are a LOCAL client choice made for this layer. They are not server-mandated, they may be
 *    tightened or relaxed by a reviewed change, and hitting one is always a typed refusal or backpressure result,
 *    never a silent truncation, deletion or conversion to success.
 */
object StateLimits {
    // -- SOURCE ------------------------------------------------------------------------------------------

    /** `relay_store.RETENTION_MS`: a NEW request id older than this is `expired`. */
    const val ID_RETENTION_MS = 604_800_000L

    /** `relay_store.FUTURE_MS`: a NEW request id this far ahead of server time is `expired`. */
    const val ID_FUTURE_MS = 300_000L

    /** `reserve_turn`: `now < expires_at_ms <= now + FUTURE_MS`. */
    const val TURN_EXPIRY_MAX_AHEAD_MS = ID_FUTURE_MS

    /** `relay_store._clock`: the server refuses a clock within 8 days of the int64 limit. */
    const val SERVER_TIME_MAX_MS = Long.MAX_VALUE - 691_200_000L

    /** A UUIDv7 carries a 48-bit millisecond timestamp. */
    const val UUID_TIMESTAMP_MAX_MS = (1L shl 48) - 1

    /** `session_history_segments.SEGMENT_BYTES`: the NOMINAL cut of a plain-text row, before continuation bytes. */
    const val SEGMENT_NOMINAL_BYTES = 8_192

    /** A nominal boundary is moved forward past UTF-8 continuation bytes: at most three of them. */
    const val SEGMENT_BOUNDARY_SLACK_BYTES = 3

    // -- POLICY: clock and ids ---------------------------------------------------------------------------

    /** Default lifetime of a new user turn; the caller may choose any value in 1..[TURN_EXPIRY_MAX_AHEAD_MS]. */
    const val DEFAULT_TURN_TTL_MS = 120_000L

    /** Random bytes consumed per generated request id (74 random bits are used of 80). */
    const val REQUEST_ID_ENTROPY_BYTES = 10

    // -- POLICY: turn ledger -----------------------------------------------------------------------------

    /** Turns the ledger retains (pending, uncertain, admitted and settled-but-unreleased). */
    const val MAX_RETAINED_TURNS = 128

    /** UTF-8 bytes of turn text the ledger retains in total. */
    const val MAX_RETAINED_TURN_TEXT_BYTES = 1_048_576

    /** Correlation ids remembered to refuse reuse by a later call. */
    const val MAX_REMEMBERED_CORRELATIONS = 256

    // -- POLICY: history assembly ------------------------------------------------------------------------

    /**
     * Segments of ONE canonical message the client will assemble. The wire allows a `segment_count` up to
     * Int.MAX_VALUE and the core cuts every 8,192 bytes, so this is what bounds memory, not the server.
     */
    const val MAX_MESSAGE_SEGMENTS = 128

    /** UTF-8 bytes of ONE assembled message (128 nominal segments). */
    const val MAX_MESSAGE_UTF8_BYTES = 1_048_576

    /** Completed-but-uncommitted messages / bytes held at once; beyond this the page is refused (backpressure). */
    const val MAX_UNCOMMITTED_MESSAGES = 256
    const val MAX_UNCOMMITTED_UTF8_BYTES = 4_194_304

    /** Message identities remembered for duplicate detection in one history scope. */
    const val MAX_KNOWN_MESSAGES = 2_048

    /** Locally refused (over-quota) messages recorded for one listing before the listing itself is refused. */
    const val MAX_REFUSED_MESSAGES = 64

    /** Cursored pages without a single fragment in a row before the listing is called stalled. */
    const val MAX_CONSECUTIVE_EMPTY_PAGES = 64

    /** Recently issued cursors remembered to refuse a repeated one. */
    const val CURSOR_RING = 16

    /** History scopes tracked at once. */
    const val MAX_HISTORY_SCOPES = 16

    // -- POLICY: events and O5 ---------------------------------------------------------------------------

    /** Event identities remembered per conversation. */
    const val MAX_SEEN_EVENT_IDS = 1_024

    /** Conversations / enrollments with event or O5 state tracked at once. */
    const val MAX_EVENT_SCOPES = 64

    /** O5 event keys remembered per enrollment (final-text refs are opaque text of up to 256 bytes). */
    const val MAX_SEEN_OUTBOX_KEYS = 1_024

    /** O5 deliveries stored but not yet acknowledged per enrollment. */
    const val MAX_PENDING_ACKS = 256

    // -- snapshots ---------------------------------------------------------------------------------------

    const val SNAPSHOT_VERSION = 1
}
