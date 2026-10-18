package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.PcmPlayback
import dev.quantumink.hermesgadget.client.PlaybackOutput
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foreground, production AudioTrack with silence only. No microphone/server/provider. */
class PlaybackOutputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = NoisyContext(instrumentation.targetContext)

    @Test fun silenceDrainsAndReportsActualTrackOutput() = foreground {
        val outputs = CopyOnWriteArrayList<PlaybackOutput>()
        val levels = CopyOnWriteArrayList<Float>()
        val interrupted = AtomicInteger()
        val finished = AtomicInteger()
        val playback = PcmPlayback(
            context,
            { interrupted.incrementAndGet() },
            { levels.add(it) },
            { finished.incrementAndGet() },
            {
                outputs.add(it)
                Log.i("N5Playback", "typed output=$it")
            }
        )
        try {
            playback.start(16000)
            await { track(playback)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            val track = requireNotNull(track(playback))
            playback.offer(ByteArray(6400))
            await { track.routedDevice != null }
            val expected = if (track.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                PlaybackOutput.SPEAKER
            } else {
                PlaybackOutput.OTHER
            }
            Log.i("N5Playback", "observed AudioTrack routeType=${track.routedDevice?.type}")
            await("production callback must observe the playing AudioTrack") {
                outputs.contains(expected)
            }
            val drainAt = SystemClock.elapsedRealtime()
            playback.offer(ByteArray(16000))
            playback.finish()
            await { finished.get() == 1 }
            val elapsed = SystemClock.elapsedRealtime() - drainAt
            Log.i("N5Playback", "real PCM drain elapsedMs=$elapsed; silentFrames=8000")
            assertTrue("Completion must wait for actual silence drain", elapsed >= 350)
            assertEquals(0, interrupted.get())
            assertEquals(0f, levels.last())
            assertEquals(PlaybackOutput.UNKNOWN, outputs.last())
            assertTrue(track.playState != AudioTrack.PLAYSTATE_PLAYING)
            playback.finish()
            playback.stop()
            assertEquals(1, finished.get())
        } finally {
            playback.stop()
        }
    }

    @Test fun becomingNoisyInterruptsOnceAndResetsLevelAndCompletion() = foreground {
        val interruptions = AtomicInteger()
        val completions = AtomicInteger()
        val levels = CopyOnWriteArrayList<Float>()
        val playback = PcmPlayback(
            context,
            { interruptions.incrementAndGet() },
            { levels.add(it) },
            { completions.incrementAndGet() }
        )
        try {
            playback.start(16000)
            await { track(playback)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            val original = requireNotNull(track(playback))
            playback.offer(ByteArray(6400))
            // This injects the platform signal; it does not simulate a physical headset.
            repeat(2) { noisy() }
            await("becoming noisy must terminate production playback") { completions.get() == 1 }
            assertEquals(1, interruptions.get())
            assertEquals(0f, levels.last())
            await { original.state == AudioTrack.STATE_UNINITIALIZED }
            assertTrue(context.receivers.isEmpty())
            playback.stop()
            playback.finish()
            assertEquals(1, interruptions.get())
            assertEquals(1, completions.get())
        } finally {
            playback.stop()
        }
    }

    @Test fun competingNativeFocusInterruptsAndDoesNotAutomaticallyResume() = foreground {
        val done = AtomicInteger()
        val lost = AtomicInteger()
        val player =
            PcmPlayback(context, {
                lost.incrementAndGet()
            }, onFinished = { done.incrementAndGet() })
        val manager = context.getSystemService(AudioManager::class.java)
        val focus = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener({
            }, android.os.Handler(android.os.Looper.getMainLooper())).build()
        try {
            player.start(16000)
            await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            val original = requireNotNull(track(player))
            player.offer(ByteArray(3200))
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
            await { done.get() == 1 }
            assertEquals(1, lost.get())
            manager.abandonAudioFocusRequest(focus)
            await { original.state == AudioTrack.STATE_UNINITIALIZED }
            instrumentation.waitForIdleSync()
            assertEquals(null, track(player))
            assertEquals(1, done.get())
        } finally {
            manager.abandonAudioFocusRequest(focus)
            player.stop()
        }
    }

    @Test fun oldRoutingFocusAndReceiverCallbacksCannotAffectReplacement() = foreground {
        val outputs = CopyOnWriteArrayList<PlaybackOutput>()
        val done = AtomicInteger()
        val lost = AtomicInteger()
        val player =
            PcmPlayback(
                context,
                {
                    lost.incrementAndGet()
                },
                onFinished = { done.incrementAndGet() },
                onOutputChanged = {
                    outputs.add(it)
                    Log.i("N5Playback", "typed output=$it")
                }
            )
        try {
            player.start(16000)
            await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            player.offer(ByteArray(3200))
            val owner = requireNotNull(field(player, "active"))
            val oldTrack = requireNotNull(track(player))
            val focus = field(owner, "focusListener") as AudioManager.OnAudioFocusChangeListener
            val routing = field(
                owner,
                "routing"
            ) as android.media.AudioRouting.OnRoutingChangedListener
            val receiver = field(owner, "receiver") as android.content.BroadcastReceiver
            player.start(16000)
            await {
                track(player)?.let {
                    it !== oldTrack &&
                        it.playState == AudioTrack.PLAYSTATE_PLAYING
                } ==
                    true
            }
            val replacement = requireNotNull(track(player))
            player.offer(ByteArray(3200))
            await { replacement.routedDevice != null }
            instrumentation.waitForIdleSync()
            val count = outputs.size
            instrumentation.runOnMainSync {
                focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                routing.onRoutingChanged(oldTrack as android.media.AudioRouting)
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, lost.get())
            assertEquals(0, done.get())
            assertEquals(count, outputs.size)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, replacement.playState)
            assertTrue(outputs.zipWithNext().none { it.first == it.second })
            player.finish()
            await { done.get() == 1 }
            assertEquals(PlaybackOutput.UNKNOWN, outputs.last())
        } finally {
            player.stop()
        }
    }

    @Test fun concurrentStopNoisyAndFocusHaveOneTerminalNotification() = foreground {
        val done = AtomicInteger()
        val lost = AtomicInteger()
        val levels = CopyOnWriteArrayList<Float>()
        val player =
            PcmPlayback(context, {
                lost.incrementAndGet()
            }, { levels.add(it) }, { done.incrementAndGet() })
        try {
            player.start(16000)
            await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            val owner = requireNotNull(field(player, "active"))
            val original = requireNotNull(track(player))
            val focus = field(owner, "focusListener") as AudioManager.OnAudioFocusChangeListener
            val receiver = field(owner, "receiver") as android.content.BroadcastReceiver
            val gate = java.util.concurrent.CountDownLatch(1)
            val jobs = listOf<() -> Unit>(
                { player.stop() },
                { receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) },
                { focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
            ).map { action ->
                Thread {
                    gate.await()
                    action()
                }.apply { start() }
            }
            gate.countDown()
            jobs.forEach {
                it.join(2000)
                assertTrue(!it.isAlive)
            }
            await { done.get() == 1 }
            await { original.state == AudioTrack.STATE_UNINITIALIZED }
            instrumentation.waitForIdleSync()
            assertTrue(lost.get() in 0..1) // Deliberate Stop may win.
            assertEquals(1, done.get())
            assertEquals(0f, levels.last())
        } finally {
            player.stop()
        }
    }

    @Test fun reentrantInterruptionStartsReplacementWithoutOldResetOrCompletion() = foreground {
        val done = AtomicInteger()
        val levels = CopyOnWriteArrayList<Float>()
        val outputs = CopyOnWriteArrayList<PlaybackOutput>()
        lateinit var player: PcmPlayback
        player =
            PcmPlayback(
                context,
                {
                    player.start(16000)
                },
                { levels.add(it) },
                { done.incrementAndGet() },
                {
                    outputs.add(it)
                    Log.i("N5Playback", "typed output=$it")
                }
            )
        try {
            player.start(16000)
            await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            val old = requireNotNull(track(player))
            player.offer(ByteArray(3200))
            await { outputs.isNotEmpty() }
            levels.clear()
            outputs.clear()
            noisy()
            await {
                track(player)?.let { it !== old && it.playState == AudioTrack.PLAYSTATE_PLAYING } ==
                    true
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, done.get())
            assertTrue(levels.isEmpty())
            assertTrue(!outputs.contains(PlaybackOutput.UNKNOWN))
            player.offer(ByteArray(3200))
            player.finish()
            await { done.get() == 1 }
            assertEquals(0f, levels.last())
        } finally {
            player.stop()
        }
    }

    @Test fun queueOverflowAndInvalidDecoderEachInterruptOnce() = foreground {
        for (invalidHeader in listOf(false, true)) {
            val done = AtomicInteger()
            val lost = AtomicInteger()
            val levels = CopyOnWriteArrayList<Float>()
            val player =
                PcmPlayback(context, {
                    lost.incrementAndGet()
                }, { levels.add(it) }, { done.incrementAndGet() })
            try {
                player.start(16000, if (invalidHeader) byteArrayOf(1) else null)
                if (!invalidHeader) {
                    await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
                    player.offer(ByteArray(32002))
                    player.finish()
                }
                await { done.get() == 1 }
                assertEquals(1, lost.get())
                assertEquals(0f, levels.last())
                player.finish()
                player.stop()
                instrumentation.waitForIdleSync()
                assertEquals(1, done.get())
            } finally {
                player.stop()
            }
        }
    }

    @Test fun stopBeforeInitializationDoesNotRegisterResources() = foreground {
        val registrations = AtomicInteger()
        val fixture = RegistrationContext { registrations.incrementAndGet() }
        val done = AtomicInteger()
        val lost = AtomicInteger()
        val player =
            PcmPlayback(fixture, {
                lost.incrementAndGet()
            }, onFinished = { done.incrementAndGet() })
        instrumentation.runOnMainSync {
            player.start(16000)
            player.stop()
            player.stop()
            player.finish()
        }
        await { done.get() == 1 }
        assertEquals(0, registrations.get())
        assertEquals(0, lost.get())
        assertEquals(null, track(player))
    }

    @Test fun stopDuringRegistrationCleansUpWithoutPlayingTheCanceledTrack() = foreground {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = AtomicInteger()
        val lost = AtomicInteger()
        val fixture = RegistrationContext {
            entered.countDown()
            check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        val player =
            PcmPlayback(fixture, {
                lost.incrementAndGet()
            }, onFinished = { done.incrementAndGet() })
        var stopper: Thread? = null
        try {
            player.start(16000)
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val original = requireNotNull(track(player))
            stopper = Thread { player.stop() }.apply { start() }
            await {
                (
                    field(
                        player,
                        "requested"
                    ) as java.util.concurrent.atomic.AtomicReference<*>
                    ).get() ==
                    null
            }
            release.countDown()
            stopper.join(2000)
            assertTrue(!stopper.isAlive)
            await { done.get() == 1 && fixture.unregistered.get() == 1 }
            await { original.state == AudioTrack.STATE_UNINITIALIZED }
            assertEquals(0, lost.get())
            assertEquals(1, done.get())
        } finally {
            release.countDown()
            stopper?.join(2000)
            player.stop()
        }
    }

    @Test fun actualTrackPlayFailureUnregistersReceiverAndReleasesTrack() = foreground {
        val done = AtomicInteger()
        val lost = AtomicInteger()
        lateinit var player: PcmPlayback
        var original: AudioTrack? = null
        val fixture = RegistrationContext {
            original = track(player)
            requireNotNull(original).release() // Native play now throws; no fake AudioTrack.
        }
        player =
            PcmPlayback(fixture, {
                lost.incrementAndGet()
            }, onFinished = { done.incrementAndGet() })
        try {
            player.start(16000)
            await { done.get() == 1 }
            await { fixture.unregistered.get() == 1 }
            assertEquals(1, lost.get())
            assertEquals(AudioTrack.STATE_UNINITIALIZED, original?.state)
            player.stop()
            instrumentation.waitForIdleSync()
            assertEquals(1, done.get())
        } finally {
            player.stop()
        }
    }

    @Test fun receiverRegistrationFailureReleasesConstructedTrack() = foreground {
        val done = AtomicInteger()
        val lost = AtomicInteger()
        var original: AudioTrack? = null
        lateinit var player: PcmPlayback
        val fixture = object : android.content.ContextWrapper(context) {
            private fun fail(): Nothing {
                original = track(player)
                throw IllegalStateException("Synthetic registration failure")
            }
            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter
            ): Intent? = fail()
            override fun registerReceiver(
                receiver: android.content.BroadcastReceiver?,
                filter: android.content.IntentFilter,
                flags: Int
            ): Intent? = fail()
        }
        player =
            PcmPlayback(fixture, {
                lost.incrementAndGet()
            }, onFinished = { done.incrementAndGet() })
        try {
            player.start(16000)
            await { done.get() == 1 }
            await { original?.state == AudioTrack.STATE_UNINITIALIZED }
            assertEquals(1, lost.get())
        } finally {
            player.stop()
        }
    }

    @Test fun platformOpusSilenceDrainsAndInterruptsThroughSameOwner() = foreground {
        val format = android.media.MediaFormat.createAudioFormat(
            android.media.MediaFormat.MIMETYPE_AUDIO_OPUS,
            16000,
            1
        )
        org.junit.Assume.assumeTrue(
            "Platform Opus decoder unavailable",
            android.media.MediaCodecList(
                android.media.MediaCodecList.REGULAR_CODECS
            ).findDecoderForFormat(format) !=
                null
        )
        val header =
            byteArrayOf(79, 112, 117, 115, 72, 101, 97, 100, 1, 1, 0, 0, -128, 62, 0, 0, 0, 0, 0)
        val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte()) // RFC 6716 silence.
        val done = AtomicInteger()
        val lost = AtomicInteger()
        val levels = CopyOnWriteArrayList<Float>()
        val player =
            PcmPlayback(context, {
                lost.incrementAndGet()
            }, { levels.add(it) }, { done.incrementAndGet() })
        try {
            player.start(16000, header)
            await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            repeat(15) {
                player.offer(packet)
                SystemClock.sleep(20)
            }
            player.finish()
            await { done.get() == 1 }
            assertEquals(0, lost.get())
            assertTrue(levels.isNotEmpty())
            assertTrue(levels.all { it < .001f })
            player.start(16000, header)
            await { track(player)?.playState == AudioTrack.PLAYSTATE_PLAYING }
            repeat(5) {
                player.offer(packet)
                SystemClock.sleep(20)
            }
            noisy()
            await { done.get() == 2 }
            assertEquals(1, lost.get())
            assertEquals(0f, levels.last())
        } finally {
            player.stop()
        }
    }

    private inner class RegistrationContext(private val registered: () -> Unit) :
        android.content.ContextWrapper(context) {
        val unregistered = AtomicInteger()
        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter
        ): Intent? {
            check(
                filter.countActions() == 1 &&
                    filter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            return super.registerReceiver(
                receiver,
                android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ).also { registered() }
        }
        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter,
            flags: Int
        ): Intent? = super.registerReceiver(receiver, filter, flags).also { registered() }
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {
            super.unregisterReceiver(receiver)
            unregistered.incrementAndGet()
        }
    }

    private fun noisy() {
        // AUDIO_BECOMING_NOISY is protected even from shell on the API37 user image.
        // Inject into the actual dynamically registered receiver, on its native delivery looper.
        // This proves reaction/cleanup, not physical unplug or framework broadcast delivery.
        instrumentation.runOnMainSync {
            val registered = context.receivers.toList()
            Log.i("N5Playback", "injected noisy into registered receiver count=${registered.size}")
            registered.forEach {
                it.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }
        }
    }

    private class NoisyContext(base: android.content.Context) :
        android.content.ContextWrapper(base) {
        val receivers = CopyOnWriteArrayList<android.content.BroadcastReceiver>()
        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter
        ): Intent? {
            check(
                filter.countActions() == 1 &&
                    filter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            return super.registerReceiver(
                receiver,
                android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
                .also { if (receiver != null) receivers.add(receiver) }
        }
        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter,
            flags: Int
        ): Intent? = super.registerReceiver(receiver, filter, flags).also {
            if (receiver !=
                null
            ) {
                receivers.add(receiver)
            }
        }
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {
            super.unregisterReceiver(receiver)
            receivers.remove(receiver)
        }
    }

    private fun track(playback: PcmPlayback): AudioTrack? {
        val owner = field(playback, "active") ?: return null
        return field(owner, "track") as AudioTrack?
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun foreground(test: () -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            instrumentation.runOnMainSync {
                activity.setTurnScreenOn(true)
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
            instrumentation.waitForIdleSync()
            test()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(message: String = "Native playback condition", condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue(message, condition())
    }
}
