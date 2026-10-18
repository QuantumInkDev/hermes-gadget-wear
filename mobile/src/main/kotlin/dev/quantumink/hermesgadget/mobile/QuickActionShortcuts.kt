package dev.quantumink.hermesgadget.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import dev.quantumink.hermesgadget.client.R as BrandR
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions

/** Launcher state is a best-effort projection, never the action/config source of truth. */
object QuickActionShortcuts {
    const val ACTION_OPEN = "dev.quantumink.hermesgadget.OPEN_QUICK_ACTION"
    private const val PREFIX = "quick-action:"
    fun intent(context: Context, id: String): Intent {
        require(QuickActions.validId(id))
        return Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
            .setData(
                Uri.Builder().scheme("relay-action").authority("review").appendPath(id).build()
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
    }
    fun parse(intent: Intent): String? {
        if (intent.action != ACTION_OPEN) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "relay-action" || uri.authority != "review" || uri.query != null ||
            uri.fragment != null ||
            uri.pathSegments.size != 1
        ) {
            return null
        }
        return uri.lastPathSegment?.takeIf { QuickActions.validId(it) }
    }
    private fun info(context: Context, action: QuickAction, rank: Int): ShortcutInfo =
        ShortcutInfo.Builder(context, PREFIX + action.id)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(action.title)
            .setLongLabel(action.title)
            .setIcon(
                Icon.createWithResource(
                    context,
                    BrandR.mipmap.ic_quantumink_relay
                )
            )
            .setIntent(intent(context, action.id)).setRank(rank).build()

    fun sync(context: Context, actions: List<QuickAction>): String? = runCatching {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val known = actions.associateBy { PREFIX + it.id }
        val pinned = manager.pinnedShortcuts.filter { it.id.startsWith(PREFIX) }
        val removed = pinned.filter { it.id !in known }.map { it.id }
        if (removed.isNotEmpty()) {
            manager.disableShortcuts(
                removed,
                "This action was removed. Open Quick actions to choose another."
            )
        }
        val current = pinned.filter { it.id in known }.map { it.id }
        if (current.isNotEmpty()) manager.enableShortcuts(current)
        val updates = pinned.mapNotNull {
            known[it.id]
        }.mapIndexed { index, action -> info(context, action, index) }
        check(updates.isEmpty() || manager.updateShortcuts(updates)) {
            "Launcher updates are temporarily rate limited."
        }
        val dynamic = actions.take(
            (
                manager.maxShortcutCountPerActivity -
                    manager.manifestShortcuts.size
                ).coerceAtLeast(0)
        )
            .mapIndexed { index, action -> info(context, action, index) }
        check(manager.setDynamicShortcuts(dynamic)) {
            "Launcher updates are temporarily rate limited."
        }
        null
    }.getOrElse { "Actions are saved. Launcher shortcuts could not be refreshed; try again later." }

    fun pin(context: Context, action: QuickAction): String = runCatching {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) {
            return "This launcher does not support adding shortcuts here."
        }
        if (manager.requestPinShortcut(info(context, action, 0), null)) {
            "Shortcut requested. Confirm placement with your launcher."
        } else {
            "The launcher did not accept the shortcut request."
        }
    }.getOrElse { "Could not request a shortcut. Try again later." }
}
