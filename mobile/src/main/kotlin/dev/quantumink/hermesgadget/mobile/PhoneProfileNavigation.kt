package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.mobile.widgets.WidgetLaunchRequest
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhoneProfileNavigation(
    private val scope: CoroutineScope,
    private val lookup: (String, WidgetLaunchRequest?) -> Pair<CatalogPage, EndpointProfile>?,
    private val current: (CatalogPage) -> Boolean
) {
    private var generation = 0L
    private var job: Job? = null
    fun request(
        key: String,
        widget: WidgetLaunchRequest?,
        ready: () -> Boolean,
        complete: (Pair<CatalogPage, EndpointProfile>?) -> Unit
    ) {
        cancel()
        val version = generation
        job = scope.launch {
            val resolved = try {
                withContext(Dispatchers.IO) { lookup(key, widget) }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (version == generation && ready()) complete(resolved?.takeIf { current(it.first) })
        }
    }
    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
}
