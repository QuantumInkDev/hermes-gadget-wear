package dev.quantumink.hermesgadget.mobile.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.CatalogRepository
import dev.quantumink.hermesgadget.mobile.DirectoryPortrait
import dev.quantumink.hermesgadget.mobile.PhoneAppearance
import dev.quantumink.hermesgadget.mobile.PhoneBrandHeader
import dev.quantumink.hermesgadget.mobile.PhoneScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeWidgetConfigActivity : ComponentActivity() {
    private var loaded by mutableStateOf<Pair<WidgetConfig, CatalogRepository>?>(null)
    private var unavailable by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var error by mutableStateOf("")
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        load()
        setContent {
            val preferences = remember { AppearancePreferences(this@HomeWidgetConfigActivity) }
            val mode by preferences.mode.collectAsStateWithLifecycle()
            PhoneAppearance(mode) {
                BackHandler { if (!saving) finish() }
                PhoneScaffold(topBar = {
                    Column(Modifier.statusBarsPadding()) { PhoneBrandHeader() }
                }) { padding ->
                    val value = loaded
                    if (value != null) {
                        val pages by value.second.pages.collectAsStateWithLifecycle()
                        HomeWidgetEditor(value.first, pages, saving, error, ::save, {
                            finish()
                        }, ::load, Modifier.fillMaxSize().padding(padding)) { page, modifier ->
                            DirectoryPortrait(value.second, page, modifier)
                        }
                    } else {
                        Column(Modifier.padding(padding).padding(24.dp)) {
                            Text(
                                if (unavailable) "Widget unavailable" else "Loading widget…",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            if (unavailable) {
                                Text(
                                    "Add this widget from your home screen, or edit an existing Quantum Relay widget."
                                )
                                TextButton({ finish() }) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun load() {
        error = ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val preset =
                        requireNotNull(HomeWidgets.preset(this@HomeWidgetConfigActivity, id))
                    val saved = HomeWidgets.store(this@HomeWidgetConfigActivity).config(id)
                    val config =
                        saved?.takeIf { it.preset == preset }
                            ?: WidgetConfig(preset, revision = saved?.revision ?: 0)
                    val repository = CatalogRepository.get(this@HomeWidgetConfigActivity)
                    repository.savedPages()
                    config to repository
                }
            }
            result.fold({
                loaded = it
                unavailable = false
            }, {
                unavailable = true
                loaded = null
            })
        }
    }

    private fun save(draft: WidgetConfig) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { HomeWidgets.save(this@HomeWidgetConfigActivity, id, draft).get() }
                }
            saving = false
            result.fold({
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }, {
                error =
                    "Could not finish saving. The widget may have changed or been removed. Reload its settings before retrying."
            })
        }
    }
}
