package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.TilePinEdit
import dev.quantumink.hermesgadget.protocol.TilePinResult
import dev.quantumink.hermesgadget.protocol.TilePinState
import dev.quantumink.hermesgadget.protocol.TilePins
import java.io.File

/** One process-wide transaction lock covers every provider/UI/Data Layer store instance. */
class TilePinStore(context: Context, private val file: File = File(context.filesDir, "tile-pins")) {
    private val application = context.applicationContext
    fun read(): TilePins = synchronized(lock) { load().pins }
    fun editLocal(focus: String?, grid: List<String?>): TilePins = mutateLocal {
        it.copy(focus = focus, grid = grid)
    }

    /** A field action cannot carry an editor's stale snapshot over a concurrent phone edit. */
    fun editFocus(focus: String?): TilePins = mutateLocal { it.copy(focus = focus) }
    fun editSlot(index: Int, key: String?): TilePins = mutateLocal { current ->
        require(index in 0..3)
        val grid = (current.grid + List(4 - current.grid.size) { null })
            .map { if (key != null && it == key) null else it }.toMutableList()
        grid[index] = key
        current.copy(grid = grid)
    }
    private fun mutateLocal(change: (TilePins) -> TilePins): TilePins = synchronized(lock) {
        val current = load()
        val changed = change(current.pins)
        val next = current.editLocal(changed.focus, changed.grid)
        save(next)
        next.pins
    }
    fun apply(edit: TilePinEdit, knownKeys: Set<String>): TilePinResult = synchronized(lock) {
        val (next, result) = load().apply(edit, knownKeys)
        if (result is TilePinResult.Accepted) save(next)
        result
    }
    private fun load(): TilePinState {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return TilePinState()
        return atomic.openRead().use {
            require(it.channel.size() in 1..TilePinState.MAX_BYTES)
            TilePinState.decode(it.readBytes().toString(Charsets.UTF_8))
        }
    }
    private fun save(state: TilePinState) {
        val bytes = state.encode().toByteArray()
        check(file.parentFile?.let { it.exists() || it.mkdirs() } == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        WatchSurfaces.requestTiles(application)
    }
    companion object {
        private val lock = Any()
    }
}
