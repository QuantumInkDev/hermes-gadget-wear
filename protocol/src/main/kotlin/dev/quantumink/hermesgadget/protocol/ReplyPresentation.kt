package dev.quantumink.hermesgadget.protocol

import java.net.URI
import java.util.Collections

/**
 * Presentation only. Parsing never performs any action or changes protocol state.
 * Bounds count UTF-16 characters, matching Conversation. Unsupported retains only input length.
 * Join Accepted.blocks' source slices to reconstruct the full original accepted response.
 */
sealed class ReplyPresentation {
    class Accepted internal constructor(blocks: List<ReplyBlock>) : ReplyPresentation() {
        val blocks: List<ReplyBlock> = Collections.unmodifiableList(blocks.toList())
        override fun toString(): String = "ReplyPresentation.Accepted(content=redacted)"
    }

    class Unsupported internal constructor(val inputLength: Int) : ReplyPresentation() {
        override fun toString(): String = "ReplyPresentation.Unsupported(content=redacted)"
    }

    companion object {
        const val MAX_TEXT = 32768
        const val MAX_BLOCKS = 16
        const val MAX_LINKS = 16
        const val MAX_ITEMS = 32
        const val MAX_CODE = 8192
        const val MAX_URL = 2048
        const val MAX_LABEL = 256
        const val MAX_ADDRESS = 512
        const val MAX_ITEM = 512

        // Conservative single-token fence labels; unsupported info strings remain plain text.
        const val MAX_LANGUAGE = 32

        fun parse(text: String): ReplyPresentation =
            if (text.length > MAX_TEXT) Unsupported(text.length) else ReplyParser(text).parse()
    }
}

/** Source slices partition the original accepted text, including all delimiters and newlines. */
sealed class ReplyBlock(open val source: String) {
    class Text internal constructor(override val source: String) : ReplyBlock(source) {
        override fun toString(): String = "ReplyBlock.Text(content=redacted)"
    }

    class Code internal constructor(
        override val source: String,
        val language: String?,
        val action: ReplyAction.CopyCode
    ) : ReplyBlock(source) {
        val code: String get() = action.text
        override fun toString(): String = "ReplyBlock.Code(content=redacted)"
    }

    class Link internal constructor(
        override val source: String,
        val label: String,
        val action: ReplyAction.OpenHttps
    ) : ReplyBlock(source) {
        override fun toString(): String = "ReplyBlock.Link(content=redacted)"
    }

    class Address internal constructor(
        override val source: String,
        val action: ReplyAction.OpenAddress
    ) : ReplyBlock(source) {
        val address: String get() = action.address
        override fun toString(): String = "ReplyBlock.Address(content=redacted)"
    }

    class Checklist internal constructor(
        override val source: String,
        val blockIndex: Int,
        items: List<ReplyChecklistItem>
    ) : ReplyBlock(source) {
        val items: List<ReplyChecklistItem> = Collections.unmodifiableList(items.toList())
        override fun toString(): String = "ReplyBlock.Checklist(content=redacted)"
    }
}

/** Zero-based block/item positions are stable within this response; checked state stays local. */
class ReplyChecklistItem internal constructor(
    val blockIndex: Int,
    val itemIndex: Int,
    val text: String,
    val initiallyChecked: Boolean
) {
    override fun toString(): String = "ReplyChecklistItem(content=redacted)"
}

/** Deliberate user actions for a later platform adapter; never protocol Action/approval messages. */
sealed class ReplyAction {
    class CopyCode internal constructor(val text: String) : ReplyAction() {
        override fun toString(): String = "ReplyAction.CopyCode(content=redacted)"
    }

    class OpenHttps internal constructor(val url: String, val host: String) : ReplyAction() {
        override fun toString(): String = "ReplyAction.OpenHttps(content=redacted)"
    }

    class OpenAddress internal constructor(val address: String) : ReplyAction() {
        override fun toString(): String = "ReplyAction.OpenAddress(content=redacted)"
    }
}

/** Each scanner advances monotonically. Failed/oversized syntax is consumed as plain source. */
private class ReplyParser(private val text: String) {
    private val blocks = mutableListOf<ReplyBlock>()
    private var plainStart = 0
    private var cursor = 0
    private var links = 0
    private var items = 0

    fun parse(): ReplyPresentation.Accepted {
        while (cursor < text.length) {
            val line = line(cursor)
            val content = text.substring(cursor, line.contentEnd)
            val lineStart = cursor == 0 || text[cursor - 1] == '\n' || text[cursor - 1] == '\r'
            when {
                lineStart && content.startsWith("```") -> fence(line, content)
                lineStart && content.startsWith("Address:") -> address(line, content)
                lineStart && content.startsWith("- [") -> checklist(line, content)
                else -> inline(line)
            }
        }
        if (plainStart < text.length || blocks.isEmpty()) {
            blocks += ReplyBlock.Text(text.substring(plainStart))
        }
        return ReplyPresentation.Accepted(blocks)
    }

    private fun emit(start: Int, end: Int, block: (Int) -> ReplyBlock) {
        val pending = if (plainStart < start) 1 else 0
        val tail = if (end < text.length) 1 else 0
        if (blocks.size + pending + 1 + tail > ReplyPresentation.MAX_BLOCKS) {
            cursor = text.length // Reserve the final block for the entire remaining source.
            return
        }
        if (pending == 1) blocks += ReplyBlock.Text(text.substring(plainStart, start))
        blocks += block(blocks.size)
        plainStart = end
        cursor = end
    }

    private fun fence(open: Line, content: String) {
        val start = cursor
        var next = open.end
        while (next < text.length) {
            val close = line(next)
            if (text.substring(next, close.contentEnd) == "```") {
                val language = content.substring(3)
                if (language.length <= ReplyPresentation.MAX_LANGUAGE &&
                    language.all {
                        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                            it in "_+.-"
                    } &&
                    next - open.end <= ReplyPresentation.MAX_CODE
                ) {
                    emit(start, close.end) {
                        ReplyBlock.Code(
                            text.substring(start, close.end),
                            language.ifEmpty { null },
                            ReplyAction.CopyCode(text.substring(open.end, next))
                        )
                    }
                } else {
                    cursor = close.end
                }
                return
            }
            next = close.end
        }
        cursor = text.length // An unclosed fence shields all of its contents.
    }

    private fun address(line: Line, content: String) {
        val start = cursor
        val value = content.takeIf { it.startsWith("Address: ") }?.substring(9)
        if (value != null && value.isNotBlank() && value.length <= ReplyPresentation.MAX_ADDRESS) {
            emit(start, line.end) {
                ReplyBlock.Address(text.substring(start, line.end), ReplyAction.OpenAddress(value))
            }
        } else {
            cursor = line.end
        }
    }

    private fun checklist(first: Line, content: String) {
        val start = cursor
        if (itemText(content) == null || items == ReplyPresentation.MAX_ITEMS) {
            cursor = first.end
            return
        }
        val entries = mutableListOf<Pair<String, Boolean>>()
        var next = start
        while (next < text.length && items + entries.size < ReplyPresentation.MAX_ITEMS) {
            val line = line(next)
            val value = text.substring(next, line.contentEnd)
            val item = itemText(value) ?: break
            entries += item to (value[3] != ' ')
            next = line.end
        }
        emit(start, next) { blockIndex ->
            ReplyBlock.Checklist(
                text.substring(start, next),
                blockIndex,
                entries.mapIndexed { index, (value, checked) ->
                    ReplyChecklistItem(blockIndex, index, value, checked)
                }
            )
        }
        items += entries.size
    }

    private fun itemText(content: String): String? {
        if (content.length < 7 || !content.startsWith("- [") || content[3] !in " xX" ||
            content[4] != ']' || content[5] != ' '
        ) {
            return null
        }
        return content.substring(6).takeIf {
            it.isNotBlank() &&
                it.length <= ReplyPresentation.MAX_ITEM
        }
    }

    private fun inline(line: Line) {
        var next = cursor
        while (next < line.contentEnd) {
            val character = text[next]
            when {
                character == '\\' -> next = (next + 2).coerceAtMost(line.contentEnd)
                character == '[' -> {
                    val candidate = markdown(next, line.contentEnd)
                    if (candidate.action != null && links < ReplyPresentation.MAX_LINKS) {
                        val start = next
                        emit(start, candidate.end) {
                            ReplyBlock.Link(
                                text.substring(start, candidate.end),
                                candidate.label,
                                candidate.action
                            )
                        }
                        links++
                        return
                    }
                    next = candidate.end
                }
                (character == 'h' || character == 'H') &&
                    (next == 0 || text[next - 1].isWhitespace()) &&
                    text.regionMatches(next, "https://", 0, 8, ignoreCase = true) -> {
                    val start = next
                    while (next < line.contentEnd && !text[next].isWhitespace()) next++
                    val url = text.substring(start, next)
                    val action = safeHttps(url)
                    if (action != null && links < ReplyPresentation.MAX_LINKS) {
                        val end = next
                        emit(start, end) {
                            ReplyBlock.Link(
                                text.substring(start, end),
                                bareLabel(url),
                                action
                            )
                        }
                        links++
                        return
                    }
                }
                else -> next++
            }
        }
        cursor = line.end
    }

    private fun bareLabel(url: String): String {
        var end = url.length.coerceAtMost(ReplyPresentation.MAX_LABEL)
        if (end < url.length && url[end - 1].isHighSurrogate() && url[end].isLowSurrogate()) end--
        return url.substring(0, end)
    }

    private fun markdown(start: Int, limit: Int): LinkCandidate {
        var next = start + 1
        var depth = 1
        var malformed = start > 0 && text[start - 1] == '!'
        val label = StringBuilder()
        while (next < limit && depth > 0) {
            val character = text[next++]
            when {
                character == '\\' && next < limit -> {
                    val escaped = text[next++]
                    if (label.length <= ReplyPresentation.MAX_LABEL) {
                        if (escaped !in "[]()\\") label.append('\\')
                        label.append(escaped)
                    }
                }
                character == '[' -> {
                    depth++
                    malformed = true
                }
                character == ']' -> depth--
                label.length <= ReplyPresentation.MAX_LABEL -> label.append(character)
            }
        }
        if (depth != 0 || next == limit || text[next] != '(') return LinkCandidate(next)
        val urlStart = ++next
        depth = 1
        while (next < limit && depth > 0) {
            when (text[next++]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        if (depth != 0) return LinkCandidate(next)
        val urlLength = next - 1 - urlStart
        if (malformed || label.isBlank() || label.length > ReplyPresentation.MAX_LABEL ||
            urlLength > ReplyPresentation.MAX_URL
        ) {
            return LinkCandidate(next)
        }
        return LinkCandidate(next, label.toString(), safeHttps(text.substring(urlStart, next - 1)))
    }

    private fun safeHttps(url: String): ReplyAction.OpenHttps? {
        if (url.length > ReplyPresentation.MAX_URL || url.any {
                it.isWhitespace() || it.isISOControl() || Character.isSpaceChar(it)
            }
        ) {
            return null
        }
        val uri = try {
            URI(url)
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque ||
            uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null
        ) {
            return null
        }
        val authority = uri.rawAuthority ?: return null
        val suffix = if (authority.startsWith('[')) {
            authority.substringAfter(']', missingDelimiterValue = ":invalid")
        } else {
            authority.substring(uri.host.length)
        }
        if (suffix.isNotEmpty() &&
            (
                !suffix.startsWith(':') || suffix.length == 1 || suffix.drop(1).any {
                    it !in '0'..'9'
                } ||
                    uri.port !in 1..65535
                )
        ) {
            return null
        }
        return ReplyAction.OpenHttps(url, uri.host)
    }

    private fun line(start: Int): Line {
        var end = start
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val contentEnd = end
        if (end < text.length && text[end++] == '\r' && end < text.length &&
            text[end] == '\n'
        ) {
            end++
        }
        return Line(contentEnd, end)
    }

    private class Line(val contentEnd: Int, val end: Int)
    private class LinkCandidate(
        val end: Int,
        val label: String = "",
        val action: ReplyAction.OpenHttps? = null
    ) {
        override fun toString(): String = "LinkCandidate(content=redacted)"
    }
}
