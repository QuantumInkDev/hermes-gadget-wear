package dev.quantumink.hermesgadget.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.quantumink.hermesgadget.client.Cue
import dev.quantumink.hermesgadget.client.PcmCapture
import dev.quantumink.hermesgadget.client.PcmPlayback
import dev.quantumink.hermesgadget.client.RecordingCues
import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.Protocol
import dev.quantumink.hermesgadget.protocol.RecordingFeedback
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
    private val playbackFactory: (android.content.Context, () -> Unit, () -> Unit) -> PcmPlayback =
        { context, interrupted, finished ->
            PcmPlayback(context, onInterrupted = interrupted, onFinished = finished)
        }
) : AndroidViewModel(application) {
    private class Saved(var draft: String = "", var reply: String = "")
    private val saved = LinkedHashMap<String, Saved>()
    private val mutable = MutableStateFlow(PhoneConversationState())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var playbackVersion = 0L
    private var connection: DirectConnection? = null
    private var capture: PcmCapture? = null
    private var playback: PcmPlayback? = null
    private var recordingFeedback: RecordingFeedback? = null
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
        val identity = runCatching { vault.identity(profile.endpoint, create = true) }.getOrElse {
            mutable.value =
                current.copy(error = "Phone identity could not be read. Enrollment was preserved.")
            return
        }
        val version = ++epoch
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
                            feedback.begin(effect.token)
                            microphone.start(
                                onFrame = { bytes ->
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) socket.captured(effect.token, bytes)
                                    }
                                },
                                onFailure = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(
                                                version
                                            )
                                        ) {
                                            socket.captureFailed(
                                                effect.token,
                                                "Microphone unavailable."
                                            )
                                        }
                                    }
                                },
                                onStarted = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) cues.play(Cue.START)
                                    }
                                },
                                onStopped = {
                                    synchronized(this@PhoneConversation) {
                                        if (active(version)) feedback.stopped(effect.token)
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
                        ConversationEffect.CaptureStop -> microphone.stop()
                        is ConversationEffect.PlaybackStart -> {
                            playback?.stop()
                            val stream = ++playbackVersion
                            val speaker = playbackFactory(
                                getApplication(),
                                { socket.report("Playback interrupted.") },
                                {
                                    mutable.update { current ->
                                        if (current.sessionVersion == version && current.visible &&
                                            current.playbackVersion == stream
                                        ) {
                                            current.copy(speaking = false)
                                        } else {
                                            current
                                        }
                                    }
                                }
                            )
                            playback = speaker
                            mutable.update { it.copy(speaking = true, playbackVersion = stream) }
                            speaker.start(effect.rate, effect.opusHeader)
                        }
                        is ConversationEffect.PlaybackData -> playback?.offer(effect.bytes)
                        ConversationEffect.PlaybackFinish -> playback?.finish()
                        ConversationEffect.PlaybackStop -> playback?.stop()
                        else -> Unit // No watch actions or optional client TTS are advertised.
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
                Protocol.pcmCapabilities() + buildJsonObject {
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
        mutable.update { it.copy(error = "", sessionVersion = version) }
        connection?.connect()
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
        if (mutable.value.visible && !mutable.value.sending) connection?.startRecording()
    }

    @Synchronized fun finishRecording() {
        if (mutable.value.visible && capture?.finish() != true) connection?.finishRecording()
    }

    @Synchronized fun cancelRecording() {
        capture?.stop()
        connection?.cancelRecording()
    }

    @Synchronized fun answer(id: String, yes: Boolean) {
        if (mutable.value.visible) connection?.answer(id, yes)
    }

    @Synchronized fun cancel(newSession: Boolean = false) {
        connection?.cancel(newSession)
    }

    @Synchronized fun stopPlayback() {
        playback?.stop()
    }

    @Synchronized fun notice(value: String) {
        if (mutable.value.visible) mutable.update { it.copy(error = value.take(256)) }
    }

    @Synchronized fun hide() {
        ++epoch // Invalidate observers before stopping adapters or closing the socket.
        recordingFeedback?.invalidate()
        recordingFeedback = null
        capture?.stop()
        playback?.stop()
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
            connection = ConnectionState(conversation = ConversationState(reply = reply))
        )
    }
    private fun active(version: Long): Boolean = epoch == version && mutable.value.visible
    override fun onCleared() {
        hide()
        super.onCleared()
    }
}
