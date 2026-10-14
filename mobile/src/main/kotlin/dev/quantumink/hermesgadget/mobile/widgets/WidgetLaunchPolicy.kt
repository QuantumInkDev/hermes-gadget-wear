package dev.quantumink.hermesgadget.mobile.widgets

import dev.quantumink.hermesgadget.mobile.PhoneConversationState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode

enum class LaunchDecision { IGNORE, OPEN, DEFER, UNAVAILABLE }

fun decideLaunch(
    key: String,
    activeKey: String?,
    busy: Boolean,
    available: Boolean
): LaunchDecision = when {
    !available -> LaunchDecision.UNAVAILABLE
    key == activeKey -> LaunchDecision.IGNORE
    busy -> LaunchDecision.DEFER
    else -> LaunchDecision.OPEN
}

fun phoneSelectionLocked(state: PhoneConversationState): Boolean = state.visible && (
    state.sending || state.speaking || state.connection.status == ConnectionStatus.PAIRING ||
        state.connection.conversation.let {
            it.busy || it.prompt != null || it.mode == ConversationMode.LISTENING ||
                it.mode == ConversationMode.THINKING
        }
    )
data class WidgetLaunchRequest(
    val widgetId: Int,
    val slot: Int,
    val key: String,
    val revision: Long
) {
    init {
        require(widgetId > 0 && slot in 0..15 && validWidgetKey(key) && revision >= 0)
    }
    fun matches(config: WidgetConfig?, owned: HomeWidgetPreset?): Boolean = config != null &&
        owned == config.preset && revision == config.revision && config.slots.getOrNull(slot) == key
    override fun toString(): String = "WidgetLaunchRequest(content=redacted)"
}
