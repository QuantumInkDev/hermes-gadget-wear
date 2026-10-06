package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class PcmCapture(private val context: Context) {
    private class Capture {
        val stopped = AtomicBoolean(false)
        val finishing = AtomicBoolean(false)

        @Volatile var recorder: AudioRecord? = null
    }
    private var active: Capture? = null

    @Synchronized
    fun start(
        onFrame: (ByteArray) -> Unit,
        onFailure: () -> Unit,
        format: String = "pcm16",
        onHeader: (ByteArray) -> Unit = {},
        onLevel: (ByteArray) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        stop()
        val capture = Capture()
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
                recorder.startRecording()
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
                if (capture.finishing.get() && !capture.stopped.get()) onFinished()
            }
        }, "watch-microphone").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun finish(): Boolean {
        val capture = active ?: return false
        capture.finishing.set(true)
        capture.recorder?.let { runCatching { it.stop() } }
        return true
    }

    @Synchronized
    fun stop() {
        val capture = active ?: return
        active = null
        capture.stopped.set(true)
        capture.recorder?.let { runCatching { it.stop() } }
    }
}

class PcmPlayback(context: Context, private val onInterrupted: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private sealed interface Packet {
        class Data(val bytes: ByteArray) : Packet
        data object End : Packet
    }
    private class Playback {
        val stopped = AtomicBoolean(false)
        val queuedBytes = AtomicInteger()
        val queue = LinkedBlockingQueue<Packet>(64)

        @Volatile var track: AudioTrack? = null
        var focus: AudioFocusRequest? = null
    }
    private var active: Playback? = null

    @Synchronized
    fun start(rate: Int, opusHeader: ByteArray? = null) {
        stop()
        require(rate == 16000)
        val outputRate = if (opusHeader == null) rate else 48000
        val playback = Playback()
        active = playback
        Thread({
            var decoder: OpusDecoder? = null
            try {
                if (opusHeader != null) decoder = OpusDecoder(opusHeader)
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { change ->
                        if (!playback.stopped.get() && (
                                change == AudioManager.AUDIOFOCUS_LOSS ||
                                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                )
                        ) {
                            stop(playback)
                            onInterrupted()
                        }
                    }.build()
                playback.focus = focus
                if (playback.stopped.get()) return@Thread
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                val minimum = AudioTrack.getMinBufferSize(
                    outputRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                check(minimum in 1..96000)
                val track = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(
                            outputRate
                        ).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                    )
                    .setBufferSizeInBytes(max(minimum, 3200))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                playback.track = track
                check(track.state == AudioTrack.STATE_INITIALIZED)
                if (playback.stopped.get()) return@Thread
                track.play()
                var samples = 0L
                fun writePcm(bytes: ByteArray) {
                    var offset = 0
                    while (offset < bytes.size && !playback.stopped.get()) {
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
                while (!playback.stopped.get()) {
                    when (val packet = playback.queue.poll(1, TimeUnit.SECONDS)) {
                        null -> Unit
                        Packet.End -> {
                            decoder?.finish(::writePcm)
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                            while (!playback.stopped.get() && System.nanoTime() < deadline &&
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
                if (!playback.stopped.get()) onInterrupted()
            } finally {
                decoder?.close()
                playback.stopped.set(true)
                playback.track?.let { track ->
                    runCatching { track.stop() }
                    track.release()
                }
                playback.track = null
                playback.focus?.let(manager::abandonAudioFocusRequest)
                playback.queue.clear()
            }
        }, "watch-speaker").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun offer(bytes: ByteArray) {
        val playback = active ?: return
        if (playback.stopped.get()) return
        val size = playback.queuedBytes.addAndGet(bytes.size)
        if (size > 32000 || !playback.queue.offer(Packet.Data(bytes.copyOf()))) {
            playback.queuedBytes.addAndGet(-bytes.size)
            stop(playback)
            onInterrupted()
        }
    }

    @Synchronized
    fun finish() {
        val playback = active ?: return
        if (!playback.queue.offer(Packet.End)) {
            stop(playback)
            onInterrupted()
        }
    }

    @Synchronized
    fun stop() {
        active?.let(::stop)
        active = null
    }

    private fun stop(playback: Playback) {
        playback.stopped.set(true)
        playback.queue.clear()
        playback.track?.let { track ->
            runCatching {
                track.pause()
                track.flush()
            }
        }
    }
}
