package dev.quantumink.hermesgadget.mobile.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.quantumink.hermesgadget.mobile.MainActivity
import dev.quantumink.hermesgadget.protocol.CatalogPage
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Local-only worker, independent of watch publication and all connection/identity owners. */
object HomeWidgets {
    const val ACTION_OPEN = "dev.quantumink.hermesgadget.OPEN_WIDGET_PROFILE"
    const val EXTRA_SLOT = "widget_slot"
    const val EXTRA_KEY = "widget_profile_key"
    const val EXTRA_REVISION = "widget_revision"
    private val worker = Executors.newSingleThreadExecutor()
    private val latest = AtomicReference<Pair<Context, List<WidgetProfile>>?>()
    private val scheduled = AtomicBoolean(false)

    fun store(context: Context) = HomeWidgetStore(File(context.filesDir, "home-widgets"))
    fun pictures(context: Context) =
        HomeWidgetPictures(File(context.filesDir, "home-widgets/portraits"))
    fun enqueue(action: () -> Unit) {
        worker.execute { runCatching(action) }
    }

    fun preset(context: Context, id: Int): HomeWidgetPreset? {
        if (id <= 0) return null
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return null
        return HomeWidgetPreset.entries.singleOrNull {
            info.provider ==
                ComponentName(context.packageName, it.className)
        }
    }

    fun instances(context: Context): List<Pair<Int, HomeWidgetPreset>> {
        val manager = AppWidgetManager.getInstance(context)
        return HomeWidgetPreset.entries.flatMap { value ->
            manager.getAppWidgetIds(ComponentName(context.packageName, value.className)).map {
                it to
                    value
            }
        }
    }

    fun publish(context: Context, pages: List<CatalogPage>) {
        val profiles = pages.map {
            WidgetProfile(
                it.key,
                it.profile.name,
                it.sourceLabel,
                it.status,
                it.profile.portrait?.sha256
            )
        }
        latest.set(context.applicationContext to profiles)
        drain()
    }

    private fun drain() {
        if (!scheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (true) {
                    val (context, profiles) = latest.getAndSet(null) ?: break
                    runCatching {
                        store(context).saveProfiles(profiles)
                        updateNow(context, instances(context).map { it.first }.toIntArray())
                    }
                }
            } finally {
                scheduled.set(false)
                if (latest.get() != null) drain()
            }
        }
    }

    fun update(context: Context, ids: IntArray) {
        val app = context.applicationContext
        val owned = ids.toSet().toIntArray()
        enqueue { updateNow(app, owned) }
    }

    fun save(context: Context, id: Int, draft: WidgetConfig): CompletableFuture<WidgetConfig> {
        val result = CompletableFuture<WidgetConfig>()
        val app = context.applicationContext
        worker.execute {
            runCatching {
                require(preset(app, id) == draft.preset) { "Widget was removed." }
                val saved = store(app).save(id, draft, draft.revision)
                updateNow(app, intArrayOf(id))
                saved
            }.fold(result::complete, result::completeExceptionally)
        }
        return result
    }

    internal fun updateNow(context: Context, ids: IntArray) {
        val store = store(context)
        val profiles = store.profiles()
        val cache = pictures(context)
        val selected = instances(context).flatMap { (id, value) ->
            store.config(id)?.takeIf {
                it.preset ==
                    value
            }?.slots.orEmpty().filterNotNull()
        }.toSet()
        val hashes = profiles.filter { it.key in selected }.mapNotNull { it.portraitHash }.toSet()
        cache.retain(hashes)
        hashes.forEach { hash ->
            cache.prepare(hash, File(context.filesDir, "catalog-portraits/$hash.png"))
        }
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            val value = preset(context, id) ?: return@forEach
            val config = store.config(id)?.takeIf { it.preset == value } ?: WidgetConfig(value)
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                value.columns * 73 - 16
            ).coerceAtLeast(1)
            val height = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                value.rows * 118 - 16
            ).coerceAtLeast(1)
            val view = HomeWidgetRenderer.render(
                context,
                id,
                config,
                profiles,
                cache,
                width.toFloat(),
                height.toFloat()
            )
            val current = store.config(id)?.takeIf { it.preset == value } ?: WidgetConfig(value)
            if (current == config && preset(context, id) == value) manager.updateAppWidget(id, view)
        }
    }

    fun editIntent(context: Context, id: Int): Intent = Intent().setClassName(
        context.packageName,
        "dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetConfigActivity"
    )
        .setData(Uri.parse("quantumrelay-widget://edit/$id"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun editPendingIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        editIntent(context, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun profilePendingIntent(
        context: Context,
        id: Int,
        slot: Int,
        key: String,
        revision: Long
    ): PendingIntent {
        require(id > 0 && slot in 0..15 && validWidgetKey(key) && revision >= 0)
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
            .setData(
                Uri.Builder().scheme(
                    "quantumrelay-widget"
                ).authority(
                    "profile"
                ).appendPath(
                    id.toString()
                ).appendPath(
                    slot.toString()
                ).appendPath(revision.toString()).appendPath(key).build()
            )
            .putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                id
            ).putExtra(EXTRA_SLOT, slot).putExtra(EXTRA_KEY, key).putExtra(EXTRA_REVISION, revision)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
