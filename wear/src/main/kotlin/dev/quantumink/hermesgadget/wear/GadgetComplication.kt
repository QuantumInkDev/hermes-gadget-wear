package dev.quantumink.hermesgadget.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class GadgetComplication : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if (request.complicationType ==
            ComplicationType.SHORT_TEXT
        ) {
            data(this, WatchSurfaces.current.value.status)
        } else {
            null
        }
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) data(this, "Ready") else null
    companion object {
        internal fun data(
            context: android.content.Context,
            status: String
        ): ShortTextComplicationData = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(status.take(7)).build(),
            PlainComplicationText.Builder("Hermes: " + status).build()
        )
            .setTitle(PlainComplicationText.Builder("Hermes").build())
            .setTapAction(WatchSurfaces.launch(context)).build()
    }
}
