package dev.quantumink.hermesgadget.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local snapshot only. A Tile never starts a socket or microphone. */
open class GadgetTileService : TileService() {
    protected open val grid = false
    private val snapshots = LinkedHashMap<String, ProfileTileSnapshot>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private fun <T : Any> future(block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completion ->
            try {
                val work = worker.submit {
                    try {
                        completion.set(block())
                    } catch (
                        failure: Exception
                    ) {
                        completion.setException(failure)
                    }
                }
                completion.addCancellationListener({ work.cancel(true) }, { it.run() })
            } catch (failure: Exception) {
                completion.setException(failure)
            }
            "Hermes local tile"
        }
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = future {
        val snapshot = ProfileTileSnapshot.read(this)
        snapshots[snapshot.version] = snapshot
        while (snapshots.size > 2) snapshots.remove(snapshots.keys.first())
        ProfileTileLayout.tile(this, snapshot, grid)
    }
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = future {
        val current = snapshots[requestParams.version] ?: ProfileTileSnapshot.read(this)
        require(requestParams.version.isEmpty() || current.version == requestParams.version) {
            WatchSurfaces.requestTiles(this)
            "Tile configuration changed; request a fresh layout"
        }
        ProfileTileLayout.resources(current)
    }
    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
