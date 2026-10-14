package dev.quantumink.hermesgadget.mobile.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

object HomeWidgetLaunch {
    fun parse(intent: Intent): WidgetLaunchRequest? {
        if (intent.action != HomeWidgets.ACTION_OPEN) return null
        return runCatching {
            val request =
                WidgetLaunchRequest(
                    intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
                    intent.getIntExtra(HomeWidgets.EXTRA_SLOT, -1),
                    requireNotNull(intent.getStringExtra(HomeWidgets.EXTRA_KEY)),
                    intent.getLongExtra(HomeWidgets.EXTRA_REVISION, -1)
                )
            val uri = requireNotNull(intent.data)
            require(
                uri.scheme == "quantumrelay-widget" && uri.authority == "profile" &&
                    uri.query == null &&
                    uri.fragment == null
            )
            require(
                uri.pathSegments ==
                    listOf(
                        request.widgetId.toString(),
                        request.slot.toString(),
                        request.revision.toString(),
                        request.key
                    )
            )
            request
        }.getOrNull()
    }

    fun valid(context: Context, request: WidgetLaunchRequest): Boolean = request.matches(
        HomeWidgets.store(context).config(request.widgetId),
        HomeWidgets.preset(context, request.widgetId)
    )

    fun bundle(request: WidgetLaunchRequest): Bundle = Bundle().apply {
        putInt("id", request.widgetId)
        putInt("slot", request.slot)
        putString("key", request.key)
        putLong("revision", request.revision)
    }
    fun restore(bundle: Bundle?): WidgetLaunchRequest? = bundle?.let { value ->
        runCatching {
            WidgetLaunchRequest(
                value.getInt("id"),
                value.getInt("slot", -1),
                requireNotNull(value.getString("key")),
                value.getLong("revision", -1)
            )
        }.getOrNull()
    }
}
