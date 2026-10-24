package dev.quantumink.hermesgadget.mobile

/** The refresh owner's only entry into assigned DNS/socket/transport work. */
internal object InboxNetworkOwner {
    inline fun <N : Any> run(
        background: Boolean,
        network: N?,
        active: () -> N?,
        begin: (N) -> Unit
    ): Boolean {
        val assigned = if (background) {
            network ?: return false
        } else {
            network ?: active()
                ?: return false
        }
        if (active() != assigned) return false
        begin(assigned)
        return true
    }
}
