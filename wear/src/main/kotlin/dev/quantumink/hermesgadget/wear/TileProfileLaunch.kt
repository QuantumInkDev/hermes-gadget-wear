package dev.quantumink.hermesgadget.wear

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.TilePins

/** An activity launch is a selection request, never a connection or recording command. */
class TileProfileLaunch {
    var pendingKey by mutableStateOf<String?>(null)
        private set
    var deferred by mutableStateOf(false)
        private set
    var unavailable by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    fun receive(intent: Intent) {
        if (!intent.hasExtra(EXTRA_PAGE_KEY)) return
        val key = runCatching { intent.getStringExtra(EXTRA_PAGE_KEY) }.getOrNull()
        unavailable = key == null || !TilePins.validKey(key)
        pendingKey = key?.takeUnless { unavailable }
        notice = ""
        deferred = false
    }
    fun reconcile(watch: WatchState, select: (String) -> Unit) {
        val key = pendingKey ?: return
        if (watch.loading) return
        val page = ProfilePages.merge(watch.profiles, watch.catalog).singleOrNull { it.key == key }
        if (page == null) {
            pendingKey = null
            unavailable = true
            notice = ""
            return
        }
        val conversation = watch.connection.conversation
        if (watch.saving || watch.selecting || conversation.busy ||
            conversation.prompt != null || conversation.mode == ConversationMode.LISTENING
        ) {
            if (!deferred) notice = "Open ${page.label} after the current turn"
            deferred = true
            return
        }
        select(key)
        pendingKey = null
        deferred = false
        unavailable = false
        notice = ""
    }
    fun dismissNotice() {
        notice = ""
    }
    fun clear() {
        pendingKey = null
        deferred = false
        unavailable = false
        notice = ""
    }
    companion object {
        const val EXTRA_PAGE_KEY = "dev.quantumink.hermesgadget.PAGE_KEY"
    }
}
