package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState

/**
 * Production ViewModel adapter: one foreground lease binds settings, identity preparation and controller UI.
 * Android identity lookup stays outside; this seam never creates, copies or rotates an enrollment key.
 */
internal class PhoneRelaySession(
    factory: ((RelayUiState) -> Unit) -> RelayController,
    private val listener: (RelayUiState) -> Unit
) {
    private var live: () -> Boolean = { true }
    fun setLeaseGuard(guard: () -> Boolean) {
        live = guard
    }
    private data class Lease(val endpoint: String, val session: Long)
    private data class Pending(val lease: Lease, val identity: EnrollmentIdentity?, val on: Boolean)
    private var lease: Lease? = null
    private var prepared: EnrollmentIdentity? = null
    private var pending: Pending? = null
    private val holder = lazy { factory(::onUi) }
    val controller: RelayController get() = holder.value

    @Synchronized fun open(endpoint: String, session: Long) {
        lease = Lease(endpoint, session)
        prepared = null
        pending = null
    }

    @Synchronized fun hide() {
        val previous = lease
        lease = null // withdraw callbacks/settings before controller teardown
        prepared = null
        pending = null
        if (holder.isInitialized()) controller.hide(previous?.session)
    }

    @Synchronized
    fun enable(on: Boolean, endpoint: String? = lease?.endpoint, session: Long? = lease?.session) {
        if (!live()) return
        val current = lease ?: return
        if (current.endpoint != endpoint || current.session != session) return
        val identity = prepared
        if (identity == null) {
            pending = Pending(current, null, on)
        } else {
            // prepare and settings enqueue in this same lock; replacement cannot interpose a new identity.
            controller.prepare(identity, current.session)
            controller.setEnabled(on)
            pending = null
        }
    }

    fun prepare(endpoint: String, session: Long, identity: EnrollmentIdentity): Boolean {
        synchronized(this) {
            if (!live()) return false
            val current = lease ?: return false
            if (current != Lease(endpoint, session) ||
                identity.endpointUrl != endpoint
            ) {
                return false
            }
            if (prepared != null && prepared != identity) return false
            prepared = identity
            controller.prepare(identity, session)
            val wanted = pending
            pending = null
            if (wanted != null && wanted.lease == current &&
                (wanted.identity == null || wanted.identity == identity)
            ) {
                controller.setEnabled(wanted.on)
            }
        }
        val accepted = controller.preparedBlocking(identity, session)
        check(accepted) { "Relay identity handover deferred: unsaved state is retained." }
        val enabled = controller.enabledBlocking()
        return synchronized(this) {
            live() && lease == Lease(endpoint, session) && prepared == identity &&
                enabled
        }
    }

    @Synchronized fun perform(endpoint: String, session: Long, action: (RelayController) -> Unit) {
        if (live() && lease == Lease(endpoint, session) &&
            prepared?.endpointUrl == endpoint
        ) {
            action(controller)
        }
    }

    @Synchronized private fun onUi(ui: RelayUiState) {
        if (!live()) return
        val current = lease ?: return
        if (ui.foregroundSession == current.session && ui.identity == prepared &&
            prepared?.endpointUrl == current.endpoint
        ) {
            listener(ui)
        }
    }
}
