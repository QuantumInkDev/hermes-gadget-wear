package dev.quantumink.hermesgadget.mobile

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import dev.quantumink.hermesgadget.client.Cue
import dev.quantumink.hermesgadget.client.OpusSupport
import dev.quantumink.hermesgadget.client.PcmCapture
import dev.quantumink.hermesgadget.client.PcmPlayback
import dev.quantumink.hermesgadget.client.RecordingCues
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.Protocol
import dev.quantumink.hermesgadget.protocol.RecordingFeedback
import dev.quantumink.hermesgadget.protocol.SpeechRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

data class PhoneConversationState(
    val sessionVersion: Long = 0,
    val playbackVersion: Long = 0,
    val profile: EndpointProfile? = null,
    val page: dev.quantumink.hermesgadget.protocol.CatalogPage? = null,
    val connection: ConnectionState = ConnectionState(),
    val draft: String = "",
    val sending: Boolean = false,
    val speaking: Boolean = false,
    val preparingSpeech: Boolean = false,
    val playbackLevel: Float = 0f,
    val recordingStartedAt: Long? = null,
    val recordingEndVersion: Long = 0,
    val mediaMuted: Boolean = false,
    val visible: Boolean = false,
    val error: String = ""
) {
    override fun toString(): String = "PhoneConversationState(content=redacted)"
}

/** A foreground phone client with its own socket and identities, independent of RelayService. */
class PhoneConversation @JvmOverloads constructor(
    application: Application,
    private val vault: ConversationVault = ConversationVault(application),
    private val httpClient: OkHttpClient? = null,
    private val playbackFactory: (
        android.content.Context,
        () -> Unit,
        (Float) -> Unit,
        () -> Unit
    ) -> PcmPlayback =
        { context, interrupted, level, finished ->
            PcmPlayback(
                context,
                onInterrupted = interrupted,
                onLevel = level,
                onFinished = finished
            )
        },
    private val opusProbe: () -> Boolean = OpusSupport::probe,
    private val selectedSpeech: (String) -> SpeechSettings = { TtsVault(application).selected(it) },
    private val speechApi: () -> ElevenLabsApi = { ElevenLabsApi() }
) : AndroidViewModel(application) {
    private class Saved(var draft: String = "", var reply: String = "")
    private val saved = LinkedHashMap<String, Saved>()
    private val mutable = MutableStateFlow(PhoneConversationState())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var playbackVersion = 0L
    private val preparation = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(1)
    ) { Thread(it, "phone-connect-prepare").apply { isDaemon = true } }
    private var preparing: java.util.concurrent.Future<*>? = null
    private var speech: PhoneClientSpeech? = null
    private var clientAudio = false
    private var connection: DirectConnection? = null
    private var capture: PcmCapture? = null
    private var playback: PcmPlayback? = null
    private var recordingFeedback: RecordingFeedback? = null
    private var recordingToken: String? = null
    private var recordingRequested = false
    private val cues = RecordingCues(application)

    @Synchronized fun open(
        profile: EndpointProfile,
        page: dev.quantumink.hermesgadget.protocol.CatalogPage? = null
    ) {
        hide()
        if (profile.endpoint.url !in saved && saved.size >= 32) saved.remove(saved.keys.first())
        val previous = saved.getOrPut(profile.endpoint.url) { Saved() }
        mutable.value = PhoneConversationState(
            sessionVersion = epoch,
            profile = profile,
            page = page,
            draft = previous.draft,
            visible = true,
            mediaMuted = mediaMuted(),
            connection = ConnectionState(conversation = ConversationState(reply = previous.reply))
        )
    }

    @Synchronized fun draft(value: String) {
        val profile = mutable.value.profile ?: return
        val bounded = value.take(8192)
        saved.getValue(profile.endpoint.url).draft = bounded
        mutable.update { it.copy(draft = bounded) }
    }

    /** Called only by the reviewed endpoint's Connect control. No discovery enrollment. */
    @Synchronized fun connect() {
        val current = mutable.value
        val profile = current.profile ?: return
        if (!current.visible) return
        if (connection != null) {
            if (current.connection.status !in
                setOf(ConnectionStatus.ERROR, ConnectionStatus.OFFLINE)
            ) {
                return
            }
            open(profile, current.page)
        }
        if (!profile.endpoint.isTls) {
            mutable.value = current.copy(error = "Phone Talk/Chat requires TLS (wss).")
            return
        }
        val version = ++epoch
        preparing?.cancel(true)
        preparation.queue.clear()
        mutable.update {
            it.copy(
                error = "",
                sessionVersion = version,
                connection = ConnectionState(
                    status = ConnectionStatus.CONNECTING,
                    conversation = it.connection.conversation
                )
            )
        }
        preparing = preparation.submit {
            val opus = runCatching { opusProbe() }.getOrDefault(false)
            var notice = ""
            val clientSpeech = runCatching {
                val selected = selectedSpeech(ClientSpeech.endpointId(profile.endpoint))
                selected.mode != SpeechMode.SERVER && selected.key.isNotEmpty()
            }.getOrElse {
                notice =
                    "Speech settings unavailable. Using server speech; saved settings preserved."
                false
            }
            // Hide/replacement can invalidate expensive preparation without waiting for it.
            synchronized(this) {
                if (!active(version) || mutable.value.profile != profile) return@submit
            }
            val identity = runCatching { vault.identity(profile.endpoint, create = true) }
            synchronized(this) {
                if (!active(version) || mutable.value.profile != profile) return@submit
                identity.fold({
                    startConnection(profile, version, it, opus, clientSpeech, notice)
                }, {
                    mutable.update { current ->
                        current.copy(
                            connection = ConnectionState(),
                            error = "Phone identity could not be read. Enrollment was preserved."
                        )
                    }
                })
            }
        }
    }

    private fun startConnection(
        profile: EndpointProfile,
        version: Long,
        identity: DeviceIdentity,
        opus: Boolean,
        clientSpeech: Boolean,
        notice: String
    ) {
        val microphone = PcmCapture(getApplication())
        capture = microphone
        val feedback = RecordingFeedback { finished ->
            synchronized(this) {
                if (active(version)) cues.play(if (finished) Cue.FINISH else Cue.CANCEL)
            }
        }
        recordingFeedback = feedback
        val observer = object : ConnectionObserver {
            override fun stateChanged(state: ConnectionState) =
                synchronized(this@PhoneConversation) {
                    if (active(version)) {
                        val previous = mutable.value.connection
                        if ((
                                clientAudio &&
                                    state.conversation.reply != previous.conversation.reply
                                ) ||
                            (
                                state.conversation.mode == ConversationMode.THINKING &&
                                    previous.conversation.mode != ConversationMode.THINKING
                                ) ||
                            state.status in
                            setOf(
                                ConnectionStatus.OFFLINE,
                                ConnectionStatus.RETRYING,
                                ConnectionStatus.ERROR
                            )
                        ) {
                            stopPlayback()
                        }
                        saved.getValue(profile.endpoint.url).reply =
                            state.conversation.reply.take(32768)
                        mutable.update { it.copy(connection = state) }
                    }
                }
            override fun effect(effect: ConversationEffect, generation: Long) =
                synchronized(this@PhoneConversation) {
                    if (!active(version)) return@synchronized
                    val socket = connection ?: return@synchronized
                    when (effect) {
                        is ConversationEffect.CaptureStart -> {
                            if (!recordingRequested) {
                                socket.cancelRecording()
                                return@synchronized
                            }
                            stopPlayback()
                            recordingToken = effect.token
                            mutable.update { it.copy(recordingStartedAt = null) }
                            feedback.begin(effect.token)
                            microphone.start(
                                onFrame = { bytes ->
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) {
                                            if (effect.format ==
                                                "opus"
                                            ) {
                                                socket.capturedOpus(effect.token, bytes)
                                            } else {
                                                socket.captured(effect.token, bytes)
                                            }
                                        }
                                    }
                                },
                                format = effect.format,
                                onHeader = { bytes ->
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) socket.codecHeader(effect.token, bytes)
                                    }
                                },
                                onLevel = { bytes ->
                                    synchronized(this@PhoneConversation) {
                                        if (active(
                                                version
                                            )
                                        ) {
                                            socket.captureLevel(effect.token, bytes)
                                        }
                                    }
                                },
                                onFailure = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(
                                                version
                                            )
                                        ) {
                                            endRecordingAttempt()
                                            socket.captureFailed(
                                                effect.token,
                                                "Microphone unavailable."
                                            )
                                        }
                                    }
                                },
                                onStarted = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(version) && recordingToken == effect.token) {
                                            mutable.update {
                                                it.copy(
                                                    recordingStartedAt = SystemClock.uptimeMillis()
                                                )
                                            }
                                            cues.play(Cue.START)
                                        }
                                    }
                                },
                                onStopped = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) {
                                            if (recordingToken == effect.token) {
                                                recordingToken = null
                                                mutable.update {
                                                    it.copy(recordingStartedAt = null)
                                                }
                                            }
                                            feedback.stopped(effect.token)
                                        }
                                    }
                                },
                                onFinished = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) socket.finishRecording(effect.token)
                                    }
                                }
                            )
                        }
                        is ConversationEffect.RecordingOutcome ->
                            feedback.outcome(effect.token, effect.finished)
                        ConversationEffect.CaptureStop -> {
                            endRecordingAttempt()
                            microphone.stop()
                        }
                        is ConversationEffect.PlaybackStart -> {
                            stopPlayback()
                            startPlayback(version, effect.rate, effect.opusHeader)
                        }
                        is ConversationEffect.ClientSpeak -> {
                            if (!mutable.value.connection.clientSpeech) return@synchronized
                            val engine =
                                speech ?: PhoneClientSpeech(speechApi()).also { speech = it }
                            stopPlayback()
                            val stream = playbackVersion
                            val request = SpeechRequest(
                                ClientSpeech.endpointId(profile.endpoint),
                                effect.id,
                                effect.text,
                                effect.voice,
                                mutable.value.connection.conversation.speakerFormat
                            )
                            fun live() = active(version) && playbackVersion == stream
                            val accepted = engine.speak(
                                request,
                                {
                                    selectedSpeech(request.endpointId)
                                },
                                object : SpeechSink {
                                    override fun start(format: String, header: ByteArray?) {
                                        synchronized(this@PhoneConversation) {
                                            if (live()) {
                                                startPlayback(
                                                    version,
                                                    16000,
                                                    header,
                                                    stream
                                                )
                                            }
                                        }
                                    }
                                    override fun packet(bytes: ByteArray) {
                                        synchronized(this@PhoneConversation) {
                                            if (live()) playback?.offer(bytes)
                                        }
                                    }
                                    override fun finish() {
                                        synchronized(this@PhoneConversation) {
                                            if (live()) playback?.finish()
                                        }
                                    }
                                },
                                { reason ->
                                    synchronized(this@PhoneConversation) {
                                        if (live()) {
                                            stopPlayback()
                                            notice(reason)
                                        }
                                    }
                                }
                            )
                            if (accepted) {
                                clientAudio = true
                                mutable.update { it.copy(preparingSpeech = true) }
                            }
                        }
                        is ConversationEffect.PlaybackData -> playback?.offer(effect.bytes)
                        ConversationEffect.PlaybackFinish -> playback?.finish()
                        ConversationEffect.PlaybackStop -> stopPlayback()
                        else -> Unit // Phone actions stay independent of the watch.
                    }
                }
        }
        connection = DirectConnection(
            profile.endpoint,
            identity,
            observer,
            accessToken = profile.accessToken.takeIf { it.isNotBlank() },
            availableActions = emptySet(),
            capabilities = JsonObject(
                audioCapabilities(opus, clientSpeech) + buildJsonObject {
                    put(
                        "display",
                        buildJsonObject {
                            put("width", 320)
                            put("height", 320)
                            put("charset", "utf8")
                            put("color", true)
                            put("text_cols", 32)
                            put("text_rows", 12)
                            put(
                                "image",
                                buildJsonObject {
                                    put("width", 320)
                                    put("height", 320)
                                    put("format", "rgb565")
                                }
                            )
                        }
                    )
                    put("inputs", JsonArray(listOf("talk", "cancel").map(::JsonPrimitive)))
                }
            ),
            httpClient = httpClient,
            previousReply = saved.getValue(profile.endpoint.url).reply
        )
        mutable.update { it.copy(error = notice, sessionVersion = version) }
        connection?.connect()
    }

    private fun startPlayback(
        version: Long,
        rate: Int,
        header: ByteArray?,
        speechStream: Long? = null
    ) {
        val stream = speechStream ?: ++playbackVersion
        val speaker = playbackFactory(
            getApplication(),
            {
                // Native callbacks may hold the playback lock. Re-enter the owner
                // asynchronously, then revalidate before cancelling any newer work.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    synchronized(this@PhoneConversation) {
                        if (active(version) && playbackVersion == stream) {
                            stopPlayback()
                            notice("Playback interrupted.")
                        }
                    }
                }
            },
            { level ->
                mutable.update { current ->
                    if (current.sessionVersion == version && current.visible &&
                        current.playbackVersion == stream && current.speaking
                    ) {
                        current.copy(
                            playbackLevel = if (level.isFinite()) {
                                level.coerceIn(
                                    0f,
                                    1f
                                )
                            } else {
                                0f
                            }
                        )
                    } else {
                        current
                    }
                }
            },
            {
                mutable.update { current ->
                    if (current.sessionVersion == version && current.visible &&
                        current.playbackVersion == stream
                    ) {
                        current.copy(speaking = false, playbackLevel = 0f)
                    } else {
                        current
                    }
                }
            }
        )
        playback = speaker
        mutable.update {
            it.copy(
                speaking = true,
                preparingSpeech = false,
                playbackVersion = stream,
                playbackLevel = 0f,
                mediaMuted = mediaMuted()
            )
        }
        speaker.start(rate, header)
    }

    internal companion object {
        fun audioCapabilities(opus: Boolean, clientSpeech: Boolean): JsonObject {
            val caps = Protocol.pcmCapabilities().toMutableMap()
            run {
                val formats =
                    JsonArray(
                        (
                            if (opus) {
                                listOf(
                                    "opus",
                                    "pcm16"
                                )
                            } else {
                                listOf("pcm16")
                            }
                            ).map(::JsonPrimitive)
                    )
                listOf("mic", "speaker").forEach { name ->
                    caps[name] =
                        JsonObject((caps.getValue(name) as JsonObject) + ("formats" to formats))
                }
            }
            if (clientSpeech) caps["tts"] = JsonPrimitive("client")
            return JsonObject(caps)
        }
    }

    @Synchronized fun send() {
        val current = mutable.value
        val socket = connection ?: return
        if (!current.visible || current.sending || current.draft.isBlank() ||
            current.connection.status != ConnectionStatus.PAIRED ||
            current.connection.conversation.busy
        ) {
            return
        }
        val version = epoch
        val text = current.draft
        mutable.update { it.copy(sending = true, error = "") }
        socket.text(text).whenComplete { accepted, failure ->
            synchronized(this) {
                if (active(version)) {
                    if (accepted == true && failure == null &&
                        mutable.value.draft == text
                    ) {
                        draft("")
                    }
                    mutable.update {
                        it.copy(
                            sending = false,
                            error = if (accepted == true &&
                                failure == null
                            ) {
                                ""
                            } else {
                                "Message was not sent. Draft retained."
                            }
                        )
                    }
                }
            }
        }
    }

    @Synchronized fun startRecording() {
        val current = mutable.value
        if (recordingRequested) return
        if (current.visible && !current.sending &&
            current.connection.status == ConnectionStatus.PAIRED &&
            !current.connection.conversation.busy &&
            current.connection.conversation.mode != ConversationMode.LISTENING
        ) {
            recordingRequested = true
            mutable.update { it.copy(error = "") }
            connection?.startRecording()
        } else {
            endRecordingAttempt()
        }
    }

    @Synchronized fun finishRecording() {
        endRecordingAttempt()
        if (mutable.value.visible && capture?.finish() != true) connection?.finishRecording()
    }

    @Synchronized fun cancelRecording() {
        endRecordingAttempt()
        capture?.stop()
        connection?.cancelRecording()
    }

    /** Permission never auto-starts a stale press; the UI receives a terminal attempt signal. */
    @Synchronized fun recordingUnavailable(reason: String) {
        cancelRecording()
        notice(reason)
    }

    private fun endRecordingAttempt() {
        recordingRequested = false
        recordingToken = null
        mutable.update {
            it.copy(recordingStartedAt = null, recordingEndVersion = it.recordingEndVersion + 1)
        }
    }

    @Synchronized fun answer(id: String, yes: Boolean) {
        if (mutable.value.visible) connection?.answer(id, yes)
    }

    @Synchronized fun cancel(newSession: Boolean = false) {
        connection?.cancel(newSession)
    }

    @Synchronized fun stopPlayback() {
        // Invalidate synthesis, interruption, level and drain callbacks before stopping adapters.
        ++playbackVersion
        speech?.cancel()
        clientAudio = false
        val previous = playback
        playback = null
        mutable.update {
            it.copy(
                preparingSpeech = false,
                speaking = false,
                playbackLevel = 0f,
                playbackVersion = playbackVersion
            )
        }
        previous?.stop()
    }

    @Synchronized fun notice(value: String) {
        if (mutable.value.visible) mutable.update { it.copy(error = value.take(256)) }
    }

    @Synchronized fun hide() {
        ++epoch // Invalidate observers before stopping adapters or closing the socket.
        recordingToken = null
        recordingRequested = false
        recordingFeedback?.invalidate()
        recordingFeedback = null
        capture?.stop()
        preparing?.cancel(true)
        preparing = null
        preparation.queue.clear()
        stopPlayback()
        speech?.close()
        speech = null
        connection?.close()
        capture = null
        playback = null
        connection = null
        val current = mutable.value
        val reply = current.profile?.let { saved[it.endpoint.url]?.reply }.orEmpty()
        mutable.value = current.copy(
            sessionVersion = epoch,
            visible = false,
            sending = false,
            speaking = false,
            playbackLevel = 0f,
            recordingStartedAt = null,
            connection = ConnectionState(conversation = ConversationState(reply = reply))
        )
    }
    fun refreshMediaVolume() {
        val muted = mediaMuted()
        mutable.update { if (it.visible) it.copy(mediaMuted = muted) else it }
    }

    private fun mediaMuted(): Boolean {
        val manager = getApplication<Application>().getSystemService(
            android.media.AudioManager::class.java
        )
        return manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0 ||
            manager.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
    }

    private fun active(version: Long): Boolean = epoch == version && mutable.value.visible
    override fun onCleared() {
        hide()
        preparation.shutdownNow()
        super.onCleared()
    }
}
