package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.SpeechRequest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call

/** Bounded foreground worker. Cancellation never replays or switches provider/format. */
internal class PhoneClientSpeech(private val api: ElevenLabsApi) : AutoCloseable {
    private class Work {
        var call: Call? = null
        var future: Future<*>? = null
    }
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) {
        Thread(it, "phone-client-speech").apply { isDaemon = true }
    }
    private val current = AtomicReference<Work?>()
    private val spoken = LinkedHashSet<String>()

    @Synchronized fun speak(
        request: SpeechRequest,
        selected: () -> SpeechSettings,
        sink: SpeechSink,
        failed: (String) -> Unit
    ): Boolean {
        if (worker.isShutdown || !spoken.add(request.id)) return false
        while (spoken.size > 64) spoken.remove(spoken.first())
        cancel()
        val work = Work()
        current.set(work)
        work.future = worker.submit {
            fun live() = current.get() === work && !Thread.currentThread().isInterrupted
            try {
                val settings = selected() // Never retain the connect-time key/choice.
                if (!live()) return@submit
                if (settings.mode == SpeechMode.SERVER || settings.key.isEmpty()) {
                    throw SpeechFailure(
                        "Client speech is no longer enabled or its key is unavailable."
                    )
                }
                api.stream(
                    settings,
                    request,
                    object : SpeechSink {
                        override fun start(format: String, header: ByteArray?) {
                            if (live()) sink.start(format, header)
                        }
                        override fun packet(bytes: ByteArray) {
                            if (live()) sink.packet(bytes)
                        }
                        override fun finish() {
                            if (live()) sink.finish()
                        }
                    }
                ) { call ->
                    synchronized(this) {
                        if (!live()) call.cancel() else work.call = call
                    }
                }
            } catch (failure: Exception) {
                if (live()) {
                    failed(
                        if (failure is SpeechFailure) {
                            failure.message.orEmpty()
                        } else {
                            "Client speech unavailable. Text retained."
                        }
                    )
                }
            } finally {
                synchronized(this) { work.call = null }
            }
        }
        return true
    }

    @Synchronized fun cancel() {
        val work = current.getAndSet(null)
        work?.call?.cancel()
        work?.future?.cancel(true)
        worker.queue.clear()
    }

    @Synchronized override fun close() {
        cancel()
        worker.shutdownNow()
        api.close()
    }
}
