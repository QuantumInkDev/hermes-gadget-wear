package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.R as ClientR
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Layout fixtures use synthetic levels; actual capture/playback is covered by stock TLS tests. */
class PhoneMessagingGalleryTest {
    @Test fun liveRecordingLayoutRendersAllThreeThemes() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        Appearance.entries.forEach { appearance ->
            NativePhoneFixture().use { ui ->
                val state = mutableStateOf(
                    PhoneConversationState(
                        visible = true,
                        profile = EndpointProfile(
                            Endpoint.parse("wss://orbit.example.invalid/gadget"),
                            "Orbit",
                            ""
                        ),
                        recordingStartedAt = SystemClock.uptimeMillis() - 7000,
                        connection = ConnectionState(
                            status = ConnectionStatus.PAIRED,
                            conversation = ConversationState(mode = ConversationMode.LISTENING)
                        )
                    )
                )
                ui.show(appearance) { modifier ->
                    ConversationContent(state.value, null, {
                    }, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, modifier)
                }
                for (level in listOf(.2f, .5f, .7f, .4f, .1f, .8f, .6f, .3f)) {
                    state.value = state.value.copy(
                        connection = state.value.connection.copy(
                            conversation = state.value.connection.conversation.copy(level = level)
                        )
                    )
                    SystemClock.sleep(100)
                }
                ui.await { ui.find("Cancel recording", true) != null }
                assertNull(ui.find("What would you like to work on?"))
                ui.capture("messaging-recording-${appearance.name}")
            }
        }
    }

    @Test fun courierUsesTheActualSystemAdaptiveAndThemedLayers() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            val adaptive = ui.context.getDrawable(
                ClientR.mipmap.ic_quantumink_relay
            ) as AdaptiveIconDrawable
            val source = adaptive.foreground.constantState!!.newDrawable().mutate()
            val themed = ui.context.getDrawable(ClientR.drawable.quantumink_relay_monochrome)!!
            fun render(kind: String): Bitmap {
                val image = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                if (kind == "System") {
                    adaptive.setBounds(0, 0, 144, 144)
                    adaptive.draw(canvas)
                } else {
                    val shape = Path().apply {
                        if (kind == "Round") {
                            addCircle(72f, 72f, 72f, Path.Direction.CW)
                        } else {
                            addRoundRect(RectF(0f, 0f, 144f, 144f), 36f, 36f, Path.Direction.CW)
                        }
                    }
                    canvas.clipPath(shape)
                    canvas.drawColor(
                        if (kind ==
                            "Themed"
                        ) {
                            Color.rgb(46, 50, 74)
                        } else {
                            Color.rgb(6, 23, 61)
                        }
                    )
                    val foreground = if (kind == "Themed") themed else source
                    foreground.setBounds(-36, -36, 180, 180)
                    foreground.draw(canvas)
                }
                return image
            }
            val images = listOf("System", "Round", "Squircle", "Themed").map { it to render(it) }
            ui.show(Appearance.MOONLIGHT, destination = "settings") { modifier ->
                Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Courier · launcher artwork", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        images.forEach { (name, image) ->
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Image(image.asImageBitmap(), name, Modifier.size(48.dp))
                                Text(name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            ui.capture("courier-native-masks")
            images.forEach { it.second.recycle() }
        }
    }
}
