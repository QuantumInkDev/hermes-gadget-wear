package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.relay.session.Dto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual monitored channel, real pending/obsolete completions, including same-generation equal-state ABA. */
class RelayTransitionOrderingTest {
    private class Rig {
        val sent = ArrayList<String>()
        val notifications = ArrayList<Pair<RelayChannelState, Long>>()
        var notify: (RelayChannelState, Long) -> Unit = { _, _ -> }
        val channel = RelayChannel({ state, generation ->
            notifications.add(state to generation)
            notify(state, generation)
        }, { 5_000_000_000L })
        val send: (String) -> Boolean = {
            sent.add(it)
            true
        }
        val welcome = Message.parse("""{"type":"welcome","relay":{"version":1}}""")!!
        fun id(): String =
            Json.parseToJsonElement(sent.last()).jsonObject["id"]!!.jsonPrimitive.content
        fun reply(body: String) = channel.frame(
            """{"type":"relay.response","v":1,"id":"${id()}",$body}"""
        )
        fun ready() {
            channel.open(1)
            channel.welcome(welcome, true, 1000, send)
            reply("\"result\":" + Dto.info().toWire())
            assertTrue(channel.state is RelayChannelState.Ready)
            notifications.clear()
        }
        fun receipt(done: (RelayCallResult<Receipt>) -> Unit) = channel.call(
            channel.correlation(),
            RelayCalls.receipt(
                Dto.conversation,
                RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
            ),
            10000,
            1000,
            send,
            done
        )
    }

    private fun obsoleteCompletion(replace: Boolean, sameGeneration: Boolean = false) {
        val rig = Rig()
        rig.ready()
        repeat(14) {
            rig.receipt {}
            rig.reply("\"error\":{\"code\":\"not_found\"}")
        }
        var refreshCompletions = 0
        rig.channel.refreshInfo(1000, rig.send) { refreshCompletions++ }
        val completions = ArrayList<RelayCallResult<Receipt>>()
        rig.channel.call(
            rig.channel.correlation(),
            RelayCalls.submit(
                Dto.binding(1).ref,
                RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!,
                Dto.SERVER_TIME + 1000,
                "must remain queued"
            ),
            10000,
            1000,
            rig.send
        ) {
            completions.add(it)
            if (sameGeneration) {
                rig.channel.open(1)
            } else {
                rig.channel.close(2)
                if (replace) rig.channel.open(3)
            }
        }
        assertEquals(15, rig.sent.size)
        rig.channel.tick(1112, rig.send)
        assertEquals(16, rig.sent.size) // refreshed info sent; mutation still actually waiting
        rig.reply("\"result\":" + Dto.info(profile = Dto.otherProfile).toWire())
        rig.channel.tick(3000, rig.send)
        assertEquals(16, rig.sent.size) // no obsolete submit, including after reentrant replacement
        assertEquals(1, completions.size)
        assertEquals(
            RelayRefusal.SCOPE_REPLACED,
            (completions.single() as RelayCallResult.Refused).reason
        )
        assertEquals(1, refreshCompletions)
        assertEquals(0, rig.channel.outstanding)
        val expected = when {
            sameGeneration -> listOf(RelayChannelState.AwaitingWelcome to 1L)
            replace -> listOf(
                RelayChannelState.Closed to 2L,
                RelayChannelState.AwaitingWelcome to 3L
            )
            else -> listOf(RelayChannelState.Closed to 2L)
        }
        assertEquals(expected.last().first, rig.channel.state)
        assertEquals(expected.last().second, rig.channel.currentGeneration)
        assertEquals(
            "superseded Ready must not be attributed to a replacement",
            expected,
            rig.notifications
        )
    }

    @Test fun obsoleteCompletionCloseSuppressesSupersededReady() = obsoleteCompletion(false)

    @Test fun obsoleteCompletionReplacementSuppressesSupersededReady() = obsoleteCompletion(true)

    @Test fun obsoleteCompletionSameGenerationReplacementSuppressesReady() =
        obsoleteCompletion(true, true)

    @Test fun reentrantOpenCompletionCannotAnnounceEqualAwaitingWelcomeAgain() {
        val rig = Rig()
        rig.ready()
        var completed = 0
        rig.receipt {
            assertEquals(RelayCallResult.Closed, it)
            completed++
            rig.channel.close(1)
            rig.channel.open(1)
        }
        rig.channel.open(1)
        assertEquals(1, completed)
        assertEquals(RelayChannelState.AwaitingWelcome, rig.channel.state)
        assertEquals(
            listOf(RelayChannelState.Closed to 1L, RelayChannelState.AwaitingWelcome to 1L),
            rig.notifications
        )
        assertEquals(0, rig.channel.outstanding)
    }

    @Test fun reentrantCloseCompletionCannotAnnounceEqualClosedAgain() {
        val rig = Rig()
        rig.ready()
        var completed = 0
        rig.receipt {
            assertEquals(RelayCallResult.Closed, it)
            completed++
            rig.channel.open(1)
            rig.channel.close(1)
        }
        rig.channel.close(1)
        assertEquals(1, completed)
        assertEquals(RelayChannelState.Closed, rig.channel.state)
        assertEquals(
            listOf(RelayChannelState.AwaitingWelcome to 1L, RelayChannelState.Closed to 1L),
            rig.notifications
        )
        assertEquals(0, rig.channel.outstanding)
    }

    @Test fun reentrantFailureCompletionCannotAnnounceEqualFailureAgain() {
        val rig = Rig()
        rig.ready()
        var completed = 0
        val oversize = "x".repeat(RelayLimits.FRAME_BYTES + 1)
        rig.receipt {
            assertEquals(RelayCallResult.Closed, it)
            completed++
            rig.channel.open(1)
            rig.channel.frame(oversize)
        }
        rig.channel.frame(oversize)
        val failed = RelayChannelState.Failed(RelayFailure.OVERSIZE_RESPONSE)
        assertEquals(1, completed)
        assertEquals(failed, rig.channel.state)
        assertEquals(
            listOf(RelayChannelState.AwaitingWelcome to 1L, failed to 1L),
            rig.notifications
        )
        assertEquals(0, rig.channel.outstanding)
    }

    @Test fun loadingListenerCloseDoesNotContinueWelcomeIntoInfoOrFailure() {
        val rig = Rig()
        rig.channel.open(1)
        rig.notifications.clear()
        rig.notify = { state, _ ->
            if (state == RelayChannelState.LoadingInfo) rig.channel.close(2)
        }
        rig.channel.welcome(rig.welcome, true, 1000, rig.send)
        assertTrue(rig.sent.isEmpty())
        assertEquals(RelayChannelState.Closed, rig.channel.state)
        assertEquals(
            listOf(RelayChannelState.LoadingInfo to 1L, RelayChannelState.Closed to 2L),
            rig.notifications
        )
    }

    @Test fun ordinaryTransitionsKeepGenerationOrderAndValidCurrentSend() {
        val rig = Rig()
        rig.channel.open(7)
        rig.channel.welcome(rig.welcome, true, 1000, rig.send)
        rig.reply("\"result\":" + Dto.info().toWire())
        assertEquals(3, rig.notifications.size)
        assertEquals(listOf(7L, 7L, 7L), rig.notifications.map { it.second })
        assertEquals(RelayChannelState.AwaitingWelcome, rig.notifications[0].first)
        assertEquals(RelayChannelState.LoadingInfo, rig.notifications[1].first)
        assertTrue(rig.notifications[2].first is RelayChannelState.Ready)
        var completed = 0
        rig.receipt { completed++ }
        rig.reply("\"error\":{\"code\":\"not_found\"}")
        rig.channel.close(8)
        assertEquals(1, completed)
        assertEquals(2, rig.sent.size)
        assertEquals(RelayChannelState.Closed to 8L, rig.notifications.last())
        assertEquals(0, rig.channel.outstanding)
    }
}
