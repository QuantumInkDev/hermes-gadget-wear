package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPresentationTest {
    private fun parsed(text: String): ReplyPresentation.Accepted {
        val result = ReplyPresentation.parse(text) as ReplyPresentation.Accepted
        assertEquals(text, result.blocks.joinToString("") { it.source })
        assertTrue(result.blocks.size <= 16)
        return result
    }

    private fun plain(text: String) {
        assertTrue(parsed(text).blocks.all { it is ReplyBlock.Text })
    }

    @Test fun mixedReplyHasTypedCodeAndExactRetainedText() {
        val text = "Hello 🌍\r\n```kotlin\r\nprintln(\"hi\")\r\n```\r\nTail"
        val code = parsed(text).blocks.filterIsInstance<ReplyBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("println(\"hi\")\r\n", code.code)
        assertEquals(code.code, code.action.text)
    }

    @Test fun mixedReplyExposesOnlyDeliberateTypedDescriptors() {
        val text = "Intro\n```sh\necho hi\n```\n" +
            "Read [friendly](https://docs.example.invalid/a?q=1#part).\n" +
            "Address: 1 Example St &q=other#fragment\n- [ ] First\n- [X] Second\nTail"
        val blocks = parsed(text).blocks
        assertEquals("echo hi\n", blocks.filterIsInstance<ReplyBlock.Code>().single().action.text)
        val link = blocks.filterIsInstance<ReplyBlock.Link>().single()
        assertEquals("friendly", link.label)
        assertEquals("docs.example.invalid", link.action.host)
        assertEquals("https://docs.example.invalid/a?q=1#part", link.action.url)
        assertEquals(
            "1 Example St &q=other#fragment",
            blocks.filterIsInstance<ReplyBlock.Address>().single().action.address
        )
        val list = blocks.filterIsInstance<ReplyBlock.Checklist>().single()
        assertEquals(listOf("First", "Second"), list.items.map { it.text })
        assertEquals(listOf(false, true), list.items.map { it.initiallyChecked })
        assertEquals(listOf(0, 1), list.items.map { it.itemIndex })
        assertEquals(blocks.indexOf(list), list.blockIndex)
        assertTrue(list.items.all { it.blockIndex == list.blockIndex })
    }

    @Test fun emptyUnicodeAndAllNewlineFormsRemainExact() {
        listOf("", "é🌍e\u0301\r\n尾\rline\n", "\n\n", "plain\u0000text").forEach(::plain)
        val code = parsed("```\rA\rB\r```\r").blocks.single() as ReplyBlock.Code
        assertEquals("A\rB\r", code.code)
        assertEquals(null, code.language)
        assertEquals("", (parsed("```\n```").blocks.single() as ReplyBlock.Code).code)
    }

    @Test fun fencesShieldLinksCheckboxesAndAddressesEvenWhenUnclosedOrOversized() {
        val body = "https://example.invalid\n- [x] private\nAddress: private\n"
        val code = parsed("```\n$body```\n").blocks.single() as ReplyBlock.Code
        assertEquals(body, code.action.text)
        plain("```\n$body")
        plain("```\n" + "x".repeat(8192) + "\nhttps://example.invalid\n```\n")
        plain("````\n$body```\n")
        plain("```bad label\n$body```\n")
    }

    @Test fun codeAndLanguageExactBoundsFallBackWithoutDroppingTail() {
        val atLimit = "x".repeat(8191) + "\n"
        assertEquals(
            atLimit,
            (parsed("```\n$atLimit```\n").blocks.single() as ReplyBlock.Code).code
        )
        plain("```\n" + "x".repeat(8192) + "\n```\n")
        val language = "l".repeat(32)
        assertEquals(
            language,
            (parsed("```$language\nx\n```").blocks.single() as ReplyBlock.Code).language
        )
        plain("```" + "l".repeat(33) + "\nx\n```\n")
        plain("before ```\ncode\n")
        plain("```\nx\n```trailing")
    }

    @Test fun escapedMarkdownLabelDisplaysLiterallyAndExposesActualDestination() {
        val link = parsed("[friendly\\] \\[name\\] \\\\](https://real.example.invalid/a_(b))")
            .blocks.single() as ReplyBlock.Link
        assertEquals("friendly] [name] " + '\\', link.label)
        assertEquals("real.example.invalid", link.action.host)
        assertEquals("https://real.example.invalid/a_(b)", link.action.url)
        plain("\\[hidden](https://example.invalid)")
        plain("![image](https://example.invalid/p.png)")
    }

    @Test fun isolatedHttpsAllowsValidPortsAndIpv6WithoutGuessingEmbeddedUrls() {
        listOf(
            "https://example.invalid",
            "HTTPS://example.invalid/a?q=1#f",
            "https://example.invalid:1/",
            "https://example.invalid:65535/",
            "https://[2001:db8::1]:443/"
        ).forEach { url ->
            assertEquals(
                url,
                parsed(url).blocks.filterIsInstance<ReplyBlock.Link>().single().action.url
            )
        }
        listOf(
            "prefixhttps://example.invalid",
            "<https://example.invalid>",
            "`https://example.invalid`"
        )
            .forEach(::plain)
    }

    @Test fun dangerousAndMalformedDestinationsNeverBecomeActions() {
        listOf(
            "http://example.invalid",
            "javascript:alert(1)",
            "data:text/html,hi",
            "intent://example.invalid",
            "file:///etc/passwd", "https:example.invalid", "https:///missing", "https://",
            "https://user@example.invalid",
            "https://user:pass@example.invalid",
            "https://@example.invalid",
            "https://example.invalid:0",
            "https://example.invalid:65536",
            "https://example.invalid:-1",
            "https://example.invalid:",
            "https://example.invalid:abc",
            "https://example.invalid:999999999999",
            "https://example.invalid\\@evil.invalid",
            "https://exa_mple.invalid",
            "https://ex ample.invalid",
            "https://example.invalid/\u0000x",
            "https://example.invalid/\tq",
            "https://example.invalid/\u007fq",
            "https://example.invalid/\u00a0q",
            "https://example.invalid/%zz",
            "https://%65xample.invalid"
        ).forEach {
            plain("[safe label]($it)")
            if (it.none { character -> character.isWhitespace() }) plain(it)
        }
    }

    @Test fun markdownAndUrlBoundsKeepOversizedElementsAsPlainText() {
        val base = "https://example.invalid/"
        val maxUrl = base + "a".repeat(2048 - base.length)
        assertEquals(
            maxUrl,
            (parsed("[ok]($maxUrl)").blocks.single() as ReplyBlock.Link).action.url
        )
        plain("[ok](${maxUrl}a)")
        val label = "x".repeat(256)
        assertEquals(label, (parsed("[$label]($base)").blocks.single() as ReplyBlock.Link).label)
        plain("[${label}x]($base)")
        val bare = parsed(maxUrl).blocks.single() as ReplyBlock.Link
        assertEquals(maxUrl, bare.action.url)
        assertTrue(bare.label.length <= 256)
        plain(maxUrl + "a")
    }

    @Test fun boundedBareUrlLabelDoesNotSplitAUnicodeSurrogatePair() {
        val prefix = "https://example.invalid/" + "a".repeat(231)
        assertEquals(255, prefix.length)
        val url = prefix + "🌍tail"
        val link = parsed(url).blocks.single() as ReplyBlock.Link
        assertEquals(prefix, link.label)
        assertEquals(url, link.action.url)
    }

    @Test fun malformedMarkdownAndUnknownSyntaxStayReadableWithoutExtractingInnerActions() {
        listOf(
            "[bad](https://example.invalid",
            "[bad] (https://example.invalid)",
            "[bad](javascript:alert(1))",
            "[bad [nested]](https://example.invalid)",
            "<script>alert('synthetic')</script>",
            "[bad](https://example.invalid \"title\")",
            "[unterminated https://example.invalid",
            "- [y] https://example.invalid"
        ).forEach(::plain)
    }

    @Test fun addressIsExplicitStandaloneBoundedAndNeverPreencoded() {
        val address = "  1 🌍 Example &q=other?x=#fragment%20  "
        assertEquals(
            address,
            (parsed("Address: $address\r\n").blocks.single() as ReplyBlock.Address).address
        )
        assertEquals(
            512,
            (
                parsed(
                    "Address: " + "a".repeat(512)
                ).blocks.single() as ReplyBlock.Address
                ).address.length
        )
        listOf(
            "Address: " + "a".repeat(513),
            "Address: ",
            "Address:    ",
            " address: 1 Example",
            "prefix Address: 1 Example",
            "address: 1 Example"
        ).forEach(::plain)
    }

    @Test fun consecutiveChecklistItemsHaveStableResponseLocalPositions() {
        val text = "intro\n- [ ] one\r\n- [x] two\r\n\n- [X] three"
        repeat(3) {
            val blocks = parsed(text).blocks
            val lists = blocks.filterIsInstance<ReplyBlock.Checklist>()
            assertEquals(2, lists.size)
            assertEquals(listOf(2, 1), lists.map { it.items.size })
            lists.forEach { list ->
                assertEquals(blocks.indexOf(list), list.blockIndex)
                assertEquals((list.items.indices).toList(), list.items.map { it.itemIndex })
                assertTrue(list.items.all { it.blockIndex == list.blockIndex })
            }
        }
    }

    @Test fun checklistItemAndAggregateBoundsPreserveOverflowAsText() {
        val maxItem = "a".repeat(512)
        assertEquals(
            maxItem,
            (parsed("- [ ] $maxItem").blocks.single() as ReplyBlock.Checklist).items.single().text
        )
        plain("- [ ] ${maxItem}a")
        val text = (1..33).joinToString("\n") { "- [ ] item $it" }
        val blocks = parsed(text).blocks
        assertEquals(32, blocks.filterIsInstance<ReplyBlock.Checklist>().sumOf { it.items.size })
        assertTrue(blocks.last() is ReplyBlock.Text)
        assertTrue(blocks.last().source.contains("item 33"))
        listOf("- [ ] ", "- [] bad", "- [Y] bad", "- [x]bad", " - [x] bad").forEach(::plain)
    }

    @Test fun exactReplyBoundIsAcceptedAndLargerInputIsTypedUnsupported() {
        plain("x".repeat(32768))
        val unsupported = ReplyPresentation.parse(
            "SECRET".repeat(10000)
        ) as ReplyPresentation.Unsupported
        assertEquals(60000, unsupported.inputLength)
        assertFalse(unsupported.toString().contains("SECRET"))
        assertTrue(ReplyPresentation.parse("x".repeat(32769)) is ReplyPresentation.Unsupported)
    }

    @Test fun blockBudgetReservesReadableTailAndNeverDropsLaterStructuredContent() {
        val text =
            (1..24).joinToString("\n") { "[link $it](https://example.invalid/$it)" } + "\nTAIL"
        val blocks = parsed(text).blocks
        assertEquals(16, blocks.size)
        assertTrue(blocks.last() is ReplyBlock.Text)
        assertTrue(blocks.last().source.endsWith("\nTAIL"))
        assertTrue(blocks.filterIsInstance<ReplyBlock.Link>().size <= 16)
        val addresses = (1..16).joinToString("\n") { "Address: example $it" }
        assertEquals(16, parsed(addresses).blocks.filterIsInstance<ReplyBlock.Address>().size)
        val overflow = parsed("$addresses\nAddress: tail")
        assertTrue(overflow.blocks.last() is ReplyBlock.Text)
    }

    @Test fun structureMustStartAtOriginalLineBoundaryAfterAnInlineLink() {
        listOf(
            "[ok](https://example.invalid)Address: should stay text",
            "[ok](https://example.invalid)- [x] should stay text",
            "[ok](https://example.invalid)```\nshould stay text\n```"
        ).forEach { text ->
            val blocks = parsed(text).blocks
            assertEquals(1, blocks.filterIsInstance<ReplyBlock.Link>().size)
            assertTrue(
                blocks.none {
                    it is ReplyBlock.Code || it is ReplyBlock.Address || it is ReplyBlock.Checklist
                }
            )
        }
    }

    @Test fun sixteenAdjacentLinksFitAndTheSeventeenthRemainsPlain() {
        val links = (1..16).joinToString("") { "[ok](https://example.invalid/$it)" }
        assertEquals(16, parsed(links).blocks.filterIsInstance<ReplyBlock.Link>().size)
        val overflow = parsed(links + "[tail](https://example.invalid/tail)")
        assertEquals(15, overflow.blocks.filterIsInstance<ReplyBlock.Link>().size)
        assertTrue(overflow.blocks.last() is ReplyBlock.Text)
    }

    @Test fun checklistEntryLimitAppliesAcrossSeparatedBlocks() {
        val first = (1..20).joinToString("\n") { "- [ ] first $it" }
        val second = (1..20).joinToString("\n") { "- [x] second $it" }
        val blocks = parsed("$first\n\n$second").blocks
        assertEquals(
            listOf(20, 12),
            blocks.filterIsInstance<ReplyBlock.Checklist>().map {
                it.items.size
            }
        )
        assertTrue(blocks.last().source.contains("second 13"))
        assertTrue(blocks.last().source.endsWith("second 20"))
    }

    @Test(timeout = 3000)
    fun syntheticSyntaxMixturesAlwaysReconstructEveryAcceptedCharacter() {
        val random = java.util.Random(6)
        val pieces = listOf(
            "\n", "\r\n", "[", "]", "(", ")", "\\", "```", "🌍", "\u0000",
            "https://example.invalid/", "Address: ", "- [x] ", "text "
        )
        repeat(80) {
            val text = buildString {
                repeat(300) { append(pieces[random.nextInt(pieces.size)]) }
            }
            parsed(text)
        }
    }

    @Test fun allContentBearingModelsAndNestedCollectionsRedactToString() {
        val text =
            "SECRET\n```SECRET\nSECRET\n```\n[SECRET](https://secret.example.invalid/SECRET)\n" +
                "Address: SECRET\n- [x] SECRET"
        val result = parsed(text)
        val values = mutableListOf<Any>(result, result.blocks)
        result.blocks.forEach { block ->
            values += block
            when (block) {
                is ReplyBlock.Code -> values += block.action
                is ReplyBlock.Link -> values += block.action
                is ReplyBlock.Address -> values += block.action
                is ReplyBlock.Checklist -> {
                    values.add(block.items)
                    values.addAll(block.items)
                }
                is ReplyBlock.Text -> Unit
            }
        }
        values.forEach {
            assertTrue(it.toString().contains("redacted"))
            assertFalse(it.toString().contains("SECRET"))
            assertFalse(it.toString().contains("secret.example"))
        }
    }

    @Test fun blockAndItemCollectionsCannotBeMutatedThroughJvmAliases() {
        val result = parsed("- [x] one\n- [ ] two")
        assertTrue(runCatching { (result.blocks as MutableList).clear() }.isFailure)
        val list = result.blocks.single() as ReplyBlock.Checklist
        assertTrue(runCatching { (list.items as MutableList).clear() }.isFailure)
        assertEquals(2, list.items.size)
    }

    @Test(timeout = 3000)
    fun adversarialInputIsBoundedNonrecursiveAndDeterministic() {
        listOf(
            "[".repeat(32768),
            "\\".repeat(32768),
            "(".repeat(32768),
            (
                "[a](https://example.invalid/" + "(".repeat(
                    100
                ) + ")".repeat(100) + ")\n"
                ).repeat(120).take(32768),
            "```\n" + "[".repeat(32760) + "\nEND"
        ).forEach { text ->
            val first = parsed(text)
            val second = parsed(text)
            assertEquals(first.blocks.map { it.javaClass }, second.blocks.map { it.javaClass })
            assertEquals(first.blocks.map { it.source }, second.blocks.map { it.source })
        }
    }
}
