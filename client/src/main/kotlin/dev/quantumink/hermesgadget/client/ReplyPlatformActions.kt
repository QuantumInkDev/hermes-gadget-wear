package dev.quantumink.hermesgadget.client

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.net.toUri
import dev.quantumink.hermesgadget.protocol.ReplyAction

/** Only called by deliberate native controls. No text-to-intent parsing or remote preview. */
class ReplyPlatformActions(
    private val context: Context,
    private val canOpen: (
        Intent
    ) -> Boolean = { it.resolveActivity(context.packageManager) != null }
) {
    fun copyCode(action: ReplyAction.CopyCode): Boolean = copyResponse(action.text)

    fun copyResponse(text: String): Boolean = try {
        val clip = ClipData.newPlainText("Response", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    } catch (_: SecurityException) {
        false
    }

    fun open(action: ReplyAction): Boolean {
        val uri = when (action) {
            is ReplyAction.OpenHttps -> action.url.toUri()
            is ReplyAction.OpenAddress -> ("geo:0,0?q=" + Uri.encode(action.address)).toUri()
            is ReplyAction.CopyCode -> return false
        }
        // Construct both intents ourselves. The descriptor supplies only validated data.
        val view = Intent(Intent.ACTION_VIEW, uri)
        val chooser = Intent.createChooser(view, "Open response").addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )
        return try {
            if (!canOpen(view)) return false
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
