package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.mobile.widgets.phoneSelectionLocked
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions

/** Final synchronous gate after async catalog lookup, before replacing a foreground draft. */
internal object QuickActionNavigation {
    fun prompt(
        reviewed: QuickAction,
        inputs: Map<String, String>,
        latest: List<QuickAction>,
        resolved: Pair<CatalogPage, EndpointProfile>?,
        state: PhoneConversationState
    ): String? {
        if (phoneSelectionLocked(state) ||
            latest.singleOrNull { it.id == reviewed.id } != reviewed
        ) {
            return null
        }
        val (page, profile) = resolved ?: return null
        if (QuickActions.resolve(reviewed, listOf(page)) !== page || !profile.endpoint.isTls ||
            page.profile.gadgetUrl != profile.endpoint.url
        ) {
            return null
        }
        return runCatching { QuickActions.expand(reviewed, inputs) }.getOrNull()
    }
}
