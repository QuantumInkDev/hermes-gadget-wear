package dev.quantumink.hermesgadget.mobile.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

abstract class HomeWidgetProvider(private val kind: HomeWidgetPreset) : AppWidgetProvider() {
    private fun submit(context: Context, action: () -> Unit) {
        val pending = goAsync()
        HomeWidgets.enqueue {
            try {
                action()
            } finally {
                pending?.finish()
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        submit(context) {
            HomeWidgets.updateNow(
                context,
                ids.filter {
                    HomeWidgets.preset(context, it) == kind
                }.toIntArray()
            )
        }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle
    ) = onUpdate(context, manager, intArrayOf(id))

    override fun onDeleted(context: Context, ids: IntArray) = submit(context) {
        val store = HomeWidgets.store(context)
        ids.filter { it > 0 && store.config(it)?.preset == kind }.forEach(store::delete)
        HomeWidgets.updateNow(context, intArrayOf())
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) =
        submit(context) {
            if (newIds.all {
                    HomeWidgets.preset(context, it) == kind
                }
            ) {
                HomeWidgets.store(context).restore(oldIds, newIds, kind)
            }
            HomeWidgets.updateNow(context, newIds)
        }
}

class FocusHomeWidget : HomeWidgetProvider(HomeWidgetPreset.FOCUS)
class FourHomeWidget : HomeWidgetProvider(HomeWidgetPreset.FOUR)
class EightHomeWidget : HomeWidgetProvider(HomeWidgetPreset.EIGHT)
class FourStripHomeWidget : HomeWidgetProvider(HomeWidgetPreset.STRIP_FOUR)
class SixteenHomeWidget : HomeWidgetProvider(HomeWidgetPreset.SIXTEEN)
class FiveStripHomeWidget : HomeWidgetProvider(HomeWidgetPreset.STRIP_FIVE)
class TenHomeWidget : HomeWidgetProvider(HomeWidgetPreset.TEN)
