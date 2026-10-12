package dev.quantumink.hermesgadget.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.quantumink.hermesgadget.client.GifDecoder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AnimatedPortrait(
    file: File?,
    fallback: Bitmap,
    description: String,
    animated: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember(context, lifecycle) { mutableStateOf(false) }
    var drawable by remember(file) { mutableStateOf<Drawable?>(null) }
    DisposableEffect(context, lifecycle) {
        fun update() {
            allowed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !context.getSystemService(PowerManager::class.java).isPowerSaveMode &&
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) >
                0f
        }
        val events = LifecycleEventObserver { _, _ -> update() }
        val power = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                update()
            }
        }
        val motion = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                update()
            }
        }
        lifecycle.addObserver(events)
        ContextCompat.registerReceiver(
            context,
            power,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            motion
        )
        update()
        onDispose {
            lifecycle.removeObserver(events)
            context.unregisterReceiver(power)
            context.contentResolver.unregisterContentObserver(motion)
        }
    }
    val running = animated && allowed && file != null
    LaunchedEffect(file, running) {
        drawable = if (running) {
            withContext(Dispatchers.IO) {
                runCatching { GifDecoder.drawable(requireNotNull(file).readBytes()) }.getOrNull()
            }
        } else {
            null
        }
    }
    val picture = drawable
    if (running && picture is AnimatedImageDrawable) {
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            modifier = modifier.clip(CircleShape),
            onRelease = { view ->
                (view.drawable as? AnimatedImageDrawable)?.stop()
                view.setImageDrawable(null)
            },
            update = { view ->
                if (view.drawable !== picture) {
                    (view.drawable as? AnimatedImageDrawable)?.stop()
                    view.setImageDrawable(picture)
                }
                view.contentDescription = description
                picture.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                picture.start()
            }
        )
    } else {
        Image(
            remember(fallback) {
                fallback.asImageBitmap()
            },
            description,
            modifier.clip(CircleShape)
        )
    }
}
