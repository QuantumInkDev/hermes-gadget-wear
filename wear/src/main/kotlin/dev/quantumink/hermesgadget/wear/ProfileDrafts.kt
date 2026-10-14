package dev.quantumink.hermesgadget.wear

/** Memory-only endpoint drafts. Selection never submits a message. */
class ProfileDrafts {
    private val drafts = linkedMapOf<String, String>()
    private var active: String? = null

    @Synchronized fun select(url: String): String {
        active = url
        return drafts[url].orEmpty()
    }

    @Synchronized fun update(text: String) {
        val url = active ?: return
        if (text.isEmpty()) {
            drafts.remove(url)
        } else {
            check(url in drafts || drafts.size < 32)
            drafts[url] = text.take(4096)
        }
    }

    @Synchronized fun remove(url: String) {
        drafts.remove(url)
    }

    @Synchronized fun clear() {
        drafts.clear()
        active = null
    }
}
