package dev.quantumink.hermesgadget.wear

/** Separate selectable system tile; the existing GadgetTileService stays the Focus component. */
class PinnedProfilesTileService : GadgetTileService() {
    override val grid = true
}
