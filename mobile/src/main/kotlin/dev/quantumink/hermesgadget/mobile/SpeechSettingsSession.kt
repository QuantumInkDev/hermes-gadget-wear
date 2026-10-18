package dev.quantumink.hermesgadget.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SpeechNotice {
    NONE,
    LOADED,
    SAVED,
    SYNC_PENDING,
    DELETED,
    FAILED,
    QUOTA,
    MISSING_KEY
}

internal data class SpeechSettingsState(
    val endpoint: String? = null,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val mode: SpeechMode = SpeechMode.SERVER,
    val voice: String = "",
    val sharedVoice: String = "",
    val ready: Boolean = false,
    val sharedReady: Boolean = false,
    val notice: SpeechNotice = SpeechNotice.NONE,
    val remaining: Long? = null,
    val sharedVersion: Long = 0,
    val profileVersion: Long = 0
)

/** Foreground lease. Secrets stay in operation-local values, outside published state. */
internal class SpeechSettingsSession(
    private val scope: CoroutineScope,
    private val read: (String) -> SpeechSettings,
    private val save: (String, SpeechSettings) -> Unit,
    private val publish: () -> Unit,
    private val quota: (String) -> Long?,
    private val allowed: () -> Boolean = { true }
) {
    private val mutable = MutableStateFlow(SpeechSettingsState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var active = false

    @Synchronized fun open(endpoint: String?) {
        close()
        active = true
        mutable.value = SpeechSettingsState(endpoint = endpoint)
        operation(clearShared = true, clearProfile = true) { lease ->
            snapshot(endpoint, SpeechNotice.LOADED, lease)
        }
    }

    @Synchronized fun close() {
        active = false
        generation++
        job?.cancel()
        job = null
    }

    @Synchronized private fun current(v: Long): Boolean = active && v == generation && allowed()

    private fun snapshot(
        endpoint: String?,
        notice: SpeechNotice,
        version: Long
    ): SpeechSettingsState {
        val shared = read("shared")
        val profile = endpoint?.let(read) ?: SpeechSettings(SpeechMode.SERVER, "", "")
        check(current(version))
        return SpeechSettingsState(
            endpoint = endpoint,
            loaded = true,
            mode = profile.mode,
            voice = profile.voice,
            sharedVoice = shared.voice,
            ready = when (profile.mode) {
                SpeechMode.SERVER -> false
                SpeechMode.SHARED -> shared.key.isNotEmpty()
                SpeechMode.PROFILE -> profile.key.isNotEmpty()
            },
            sharedReady = shared.key.isNotEmpty(),
            notice = notice
        )
    }

    @Synchronized private fun operation(
        clearShared: Boolean = false,
        clearProfile: Boolean = false,
        block: (Long) -> SpeechSettingsState
    ) {
        if (!active || !allowed() || mutable.value.busy) return
        val version = generation
        val before = mutable.value
        mutable.value = before.copy(busy = true, notice = SpeechNotice.NONE)
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(version) } }
            synchronized(this@SpeechSettingsSession) {
                if (current(version)) {
                    mutable.value = result.getOrElse { before.copy(notice = SpeechNotice.FAILED) }
                        .copy(
                            busy = false,
                            sharedVersion =
                            before.sharedVersion + if (result.isSuccess &&
                                clearShared
                            ) {
                                1
                            } else {
                                0
                            },
                            profileVersion =
                            before.profileVersion +
                                if (result.isSuccess && clearProfile) 1 else 0
                        )
                }
            }
        }
    }

    fun saveShared(key: String, voice: String) = change("shared", false) { old ->
        SpeechSettings(SpeechMode.SHARED, key.trim().ifEmpty { old.key }, voice.trim())
    }

    fun deleteShared() = change("shared", true) { old ->
        SpeechSettings(old.mode, "", old.voice)
    }

    fun saveProfile(mode: SpeechMode, key: String, voice: String) {
        val endpoint = state.value.endpoint ?: return
        change(endpoint, false) { old ->
            SpeechSettings(mode, key.trim().ifEmpty { old.key }, voice.trim())
        }
    }

    fun deleteProfile() {
        val endpoint = state.value.endpoint ?: return
        change(endpoint, true) { old -> SpeechSettings(old.mode, "", old.voice) }
    }

    private fun change(
        id: String,
        deleting: Boolean,
        settings: (SpeechSettings) -> SpeechSettings
    ) {
        if (!state.value.loaded) return
        val endpoint = state.value.endpoint
        operation(clearShared = id == "shared", clearProfile = id != "shared") { version ->
            val updated = settings(read(id))
            // A delayed read must never become a write after leaving/replacing the target.
            synchronized(this) {
                check(current(version))
                save(id, updated)
            }
            val synced = if (current(version)) runCatching(publish).isSuccess else false
            snapshot(
                endpoint,
                if (!synced) {
                    SpeechNotice.SYNC_PENDING
                } else if (deleting) {
                    SpeechNotice.DELETED
                } else {
                    SpeechNotice.SAVED
                },
                version
            )
        }
    }

    fun checkQuota(shared: Boolean, typedKey: String = "") {
        if (!state.value.loaded) return
        val before = state.value
        operation { version ->
            val key = if (shared) {
                typedKey.trim().ifEmpty { read("shared").key }
            } else {
                val profile = read(requireNotNull(before.endpoint))
                when (profile.mode) {
                    SpeechMode.SERVER -> ""
                    SpeechMode.SHARED -> read("shared").key
                    SpeechMode.PROFILE -> profile.key
                }
            }
            check(current(version))
            if (key.isEmpty()) {
                before.copy(notice = SpeechNotice.MISSING_KEY)
            } else {
                before.copy(notice = SpeechNotice.QUOTA, remaining = quota(key))
            }
        }
    }
}

internal fun speechSettingsLocked(state: PhoneConversationState): Boolean =
    dev.quantumink.hermesgadget.mobile.widgets.phoneSelectionLocked(state) ||
        state.visible && state.preparingSpeech
