package dev.quantumink.hermesgadget.client

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class PcmCapture(private val context: Context) {
    private class Capture(val generation: Long) {
        val stopped = AtomicBoolean(false)
        val finishing = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val startup = CaptureStartGate()

        @Volatile var recorder: AudioRecord? = null
    }
    private var active: Capture? = null
    private val generations = AtomicLong()

    @Synchronized
    fun start(
        onFrame: (ByteArray) -> Unit,
        onFailure: () -> Unit,
        format: String = "pcm16",
        onHeader: (ByteArray) -> Unit = {},
        onLevel: (ByteArray) -> Unit = {},
        onFinished: () -> Unit = {},
        onStarted: () -> Unit = {},
        onStopped: (Boolean) -> Unit = {}
    ) {
        stop()
        val capture = Capture(generations.incrementAndGet())
        active = capture
        Thread({
            var encoder: OpusEncoder? = null
            try {
                if (format == "opus") encoder = OpusEncoder()
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    if (!capture.stopped.get()) onFailure()
                    return@Thread
                }
                val minimum = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                check(minimum in 1..32000)
                val recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                    )
                    .setBufferSizeInBytes(max(minimum, 3200)).build()
                capture.recorder = recorder
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                if (capture.stopped.get()) return@Thread
                if (!capture.startup.start {
                        recorder.startRecording()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        capture.started.set(true)
                    }
                ) {
                    return@Thread
                }
                onStarted()
                if (capture.stopped.get() || capture.finishing.get()) return@Thread
                // Drop the samples recorded while the start cue ran, before forwarding audio.
                val queued = ByteArray(max(minimum, 3200))
                var drained = 0
                while (drained++ < 8 &&
                    recorder.read(queued, 0, queued.size, AudioRecord.READ_NON_BLOCKING) > 0
                ) {
                    if (capture.stopped.get() || capture.finishing.get()) return@Thread
                }
                val bytes = ByteArray(640)
                while (!capture.stopped.get() && !capture.finishing.get()) {
                    val count = recorder.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) {
                        if (!capture.stopped.get() && !capture.finishing.get()) onFailure()
                        break
                    }
                    if (count > 0 && !capture.stopped.get()) {
                        val frame = bytes.copyOf(count)
                        if (encoder == null) {
                            onFrame(frame)
                        } else {
                            onLevel(frame)
                            encoder.push(frame, onHeader, onFrame)
                        }
                    }
                }
            } catch (_: Exception) {
                if (!capture.stopped.get()) onFailure()
            } finally {
                if (capture.finishing.get() && !capture.stopped.get()) {
                    runCatching { encoder?.finish(onHeader, onFrame) }.onFailure { onFailure() }
                }
                encoder?.close()
                capture.recorder?.let { recorder ->
                    runCatching { recorder.stop() }
                    recorder.release()
                }
                capture.recorder = null
                synchronized(this@PcmCapture) { if (active === capture) active = null }
                if (capture.started.get() && capture.generation == generations.get()) {
                    onStopped(capture.finishing.get() && !capture.stopped.get())
                }
                if (capture.finishing.get() && !capture.stopped.get() &&
                    capture.generation == generations.get()
                ) {
                    onFinished()
                }
            }
        }, "gadget-microphone").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun finish(): Boolean {
        val capture = active ?: return false
        if (capture.finishing.get()) return true
        if (!capture.startup.finish()) {
            active = null
            capture.stopped.set(true)
            capture.recorder?.let { runCatching { it.stop() } }
            return false
        }
        capture.finishing.set(true)
        capture.recorder?.let { runCatching { it.stop() } }
        return true
    }

    @Synchronized
    fun stop() {
        val capture = active ?: return
        active = null
        capture.stopped.set(true)
        capture.startup.cancel()
        capture.recorder?.let { runCatching { it.stop() } }
    }
}

class PcmPlayback(
    private val context: Context,
    private val onInterrupted: () -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onFinished: () -> Unit = {},
    private val onOutputChanged: (PlaybackOutput) -> Unit = {}
) {
    private val manager = context.getSystemService(AudioManager::class.java)

    // Lifecycle notifications are serialized without holding a monitor across client code.
    private val callbacks = Handler(Looper.getMainLooper())
    private val generations = AtomicLong()
    private val requested = AtomicReference<Playback?>()
    private var reportedOutput = PlaybackOutput.UNKNOWN
    private sealed interface Packet {
        class Data(val bytes: ByteArray) : Packet
        data object End : Packet
    }
    private class Playback(val generation: Long) {
        val stopped = AtomicBoolean(false)
        val interruptionQueued = AtomicBoolean(false)
        val finishing = AtomicBoolean(false)
        val queuedBytes = AtomicInteger()
        val queue = LinkedBlockingQueue<Packet>(64)
        var completed = false

        @Volatile var track: AudioTrack? = null
        var focus: AudioFocusRequest? = null
        var focusListener: AudioManager.OnAudioFocusChangeListener? = null
        var routing: AudioRouting.OnRoutingChangedListener? = null
        var receiver: BroadcastReceiver? = null
    }

    // Only the callback looper changes this owner. The worker alone releases its resources.
    @Volatile private var active: Playback? = null

    fun start(rate: Int, opusHeader: ByteArray? = null) {
        require(rate == 16000)
        val (playback, previous) = synchronized(requested) {
            val next = Playback(generations.incrementAndGet())
            next to requested.getAndSet(next)
        }
        previous?.let(::cancel)
        val header = opusHeader?.copyOf()
        callbacks.post {
            if (requested.get() !== playback || playback.stopped.get()) return@post
            active?.let { complete(it, false) }
            if (requested.get() === playback && !playback.stopped.get()) {
                active = playback
                Thread({ play(playback, rate, header) }, "gadget-speaker")
                    .apply { isDaemon = true }.start()
            }
        }
    }

    private fun play(playback: Playback, rate: Int, opusHeader: ByteArray?) {
        var decoder: OpusDecoder? = null
        try {
            if (playback.stopped.get()) return
            if (opusHeader != null) decoder = OpusDecoder(opusHeader)
            val outputRate = if (opusHeader == null) rate else 48000
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    interrupt(playback)
                }
            }
            playback.focusListener = focusListener
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, callbacks).build()
            playback.focus = focus
            if (playback.stopped.get()) return
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            if (playback.stopped.get()) return
            val minimum = AudioTrack.getMinBufferSize(
                outputRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum in 1..96000)
            val track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(outputRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(max(minimum, 3200))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            playback.track = track
            check(track.state == AudioTrack.STATE_INITIALIZED)
            val routing = AudioRouting.OnRoutingChangedListener { source ->
                // AudioTrack also exposes an older listener overload; use AudioRouting explicitly.
                if (source === track) observe(playback, track)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        interrupt(playback)
                    }
                }
            }
            // Cancellation and startup share a small resource gate, never a client callback/release.
            synchronized(playback) {
                if (playback.stopped.get()) return
                playback.routing = routing
                (track as AudioRouting).addOnRoutingChangedListener(routing, callbacks)
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= 33) {
                    // This protected system action can originate outside the application UID.
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                playback.receiver = receiver
                if (playback.stopped.get() || requested.get() !== playback) return
                track.play()
            }
            observe(playback, track)
            var samples = 0L
            var levelAt = 0L
            fun writePcm(bytes: ByteArray) {
                if (playback.stopped.get() || playback.interruptionQueued.get()) return
                val now = System.nanoTime()
                if (now - levelAt >= TimeUnit.MILLISECONDS.toNanos(100)) {
                    var sum = 0.0
                    for (index in 0 until bytes.size - 1 step 2) {
                        val value = (
                            (bytes[index].toInt() and 255) or
                                (bytes[index + 1].toInt() shl 8)
                            ).toShort().toDouble() / 32768.0
                        sum += value * value
                    }
                    val level = kotlin.math.sqrt(sum / (bytes.size / 2).coerceAtLeast(1))
                        .toFloat().coerceIn(0f, 1f)
                    callbacks.post { if (current(playback)) onLevel(level) }
                    levelAt = now
                }
                var offset = 0
                while (offset < bytes.size && !playback.stopped.get() &&
                    !playback.interruptionQueued.get()
                ) {
                    val count = track.write(
                        bytes,
                        offset,
                        bytes.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    check(count > 0)
                    offset += count
                    samples += count / 2
                }
            }
            while (!playback.stopped.get() && !playback.interruptionQueued.get()) {
                when (val packet = playback.queue.poll(1, TimeUnit.SECONDS)) {
                    null -> Unit
                    Packet.End -> {
                        decoder?.finish(::writePcm)
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (!playback.stopped.get() && !playback.interruptionQueued.get() &&
                            System.nanoTime() < deadline &&
                            (track.playbackHeadPosition.toLong() and 0xffffffffL) < samples
                        ) {
                            Thread.sleep(10)
                        }
                        break
                    }
                    is Packet.Data -> {
                        if (decoder ==
                            null
                        ) {
                            writePcm(packet.bytes)
                        } else {
                            decoder.push(packet.bytes, ::writePcm)
                        }
                        playback.queuedBytes.addAndGet(-packet.bytes.size)
                    }
                }
            }
        } catch (_: Exception) {
            interrupt(playback)
        } finally {
            runCatching { decoder?.close() }
            playback.stopped.set(true)
            playback.receiver?.let { runCatching { context.unregisterReceiver(it) } }
            playback.receiver = null
            playback.track?.let { track ->
                playback.routing?.let { listener ->
                    runCatching { (track as AudioRouting).removeOnRoutingChangedListener(listener) }
                }
                runCatching { track.stop() }
                playback.track = null
                runCatching { track.release() }
            }
            playback.routing = null
            playback.focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
            playback.focus = null
            playback.focusListener = null
            playback.queue.clear()
            callbacks.post { complete(playback, false) }
        }
    }

    private fun observe(playback: Playback, track: AudioTrack) {
        callbacks.post {
            if (current(playback) && playback.track === track) {
                val output = playbackOutputForDeviceType(
                    runCatching { track.routedDevice?.type }.getOrNull(),
                    Build.VERSION.SDK_INT
                )
                if (output != reportedOutput) {
                    reportedOutput = output
                    onOutputChanged(output)
                }
            }
        }
    }

    fun offer(bytes: ByteArray) {
        val playback = requested.get() ?: return
        val failed = synchronized(playback) {
            if (playback.stopped.get() || playback.interruptionQueued.get() ||
                playback.finishing.get()
            ) {
                return
            }
            val size = playback.queuedBytes.addAndGet(bytes.size)
            if (size > 32000 || !playback.queue.offer(Packet.Data(bytes.copyOf()))) {
                playback.queuedBytes.addAndGet(-bytes.size)
                true
            } else {
                false
            }
        }
        if (failed) interrupt(playback)
    }

    fun finish() {
        val playback = requested.get() ?: return
        val failed = synchronized(playback) {
            if (playback.stopped.get() || playback.interruptionQueued.get() ||
                !playback.finishing.compareAndSet(false, true)
            ) {
                return
            }
            !playback.queue.offer(Packet.End)
        }
        if (failed) interrupt(playback)
    }

    fun stop() {
        val playback = requested.getAndSet(null) ?: return
        cancel(playback)
        callbacks.post { complete(playback, false) }
    }

    private fun interrupt(playback: Playback) {
        if (requested.get() !== playback ||
            !playback.interruptionQueued.compareAndSet(false, true)
        ) {
            return
        }
        callbacks.post {
            if (active !== playback || requested.get() !== playback ||
                playback.completed
            ) {
                return@post
            }
            cancel(playback)
            complete(playback, true)
        }
    }

    private fun cancel(playback: Playback) {
        synchronized(playback) {
            playback.stopped.set(true)
            playback.queue.clear()
            playback.queue.offer(Packet.End)
            playback.track?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                }
            }
        }
    }

    private fun current(playback: Playback): Boolean =
        active === playback && requested.get() === playback &&
            !playback.stopped.get() && !playback.interruptionQueued.get()

    private fun complete(playback: Playback, interrupted: Boolean) {
        if (playback.completed) return
        playback.completed = true
        if (active === playback) active = null
        requested.compareAndSet(playback, null)
        // A client callback may itself start another stream. Fence each subsequent notification.
        fun latest(): Boolean = generations.get() == playback.generation
        if (interrupted && latest()) onInterrupted()
        if (latest()) onLevel(0f)
        if (latest() && reportedOutput != PlaybackOutput.UNKNOWN) {
            reportedOutput = PlaybackOutput.UNKNOWN
            onOutputChanged(PlaybackOutput.UNKNOWN)
        }
        if (latest()) onFinished()
    }
}
