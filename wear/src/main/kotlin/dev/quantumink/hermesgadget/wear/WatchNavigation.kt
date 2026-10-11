package dev.quantumink.hermesgadget.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.quantumink.hermesgadget.protocol.TransportPreference

/** Activity-owned memory survives replacement of the interactive composition by ambient. */
class WatchNavigation {
    val route = mutableStateOf("home")
    val editing = mutableStateOf<EndpointProfile?>(null)
    val newProfile = mutableStateOf(false)
    val imported = mutableStateOf(false)
    val page = mutableStateOf("")
    private var setupKey: Pair<String?, Boolean>? = null
    private var setup: SetupDraft? = null
    fun setupFor(profile: EndpointProfile?, imported: Boolean): SetupDraft {
        val key = profile?.endpoint?.url to imported
        if (setupKey != key || setup == null) {
            setupKey = key
            setup = SetupDraft(profile, imported)
        }
        return requireNotNull(setup)
    }
    fun clearSetup() {
        setupKey = null
        setup = null
    }
}

class SetupDraft(profile: EndpointProfile?, imported: Boolean) {
    var url by mutableStateOf(profile?.endpoint?.url.orEmpty())
    var label by mutableStateOf(profile?.label ?: "My Hermes")
    var token by mutableStateOf(profile?.accessToken.orEmpty())
    var local by mutableStateOf(profile?.endpoint?.privateNetwork ?: false)
    var accepted by mutableStateOf(!imported && (profile?.endpoint?.cleartextAccepted ?: false))
    var showToken by mutableStateOf(token.isNotEmpty())
    var transport by mutableStateOf(profile?.transport ?: TransportPreference.AUTO)
    override fun toString(): String = "SetupDraft(redacted)"
}
