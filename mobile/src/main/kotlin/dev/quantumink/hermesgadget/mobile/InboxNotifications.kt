package dev.quantumink.hermesgadget.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.quantumink.hermesgadget.protocol.relay.session.InboxCopy
import java.security.MessageDigest

/**
 * Every supplied row already has a confirmed durable attempt/suppression decision. No retry on an
 * OS failure.
 */
internal object InboxNotifications {
    const val ACTION = "dev.quantumink.hermesgadget.mobile.INBOX"
    const val ROUTE = "inbox_route"
    private const val CHANNEL = "private-inbox-v1"
    fun handle(intent: Intent?): String? = try {
        if (intent?.action != ACTION) {
            null
        } else {
            intent.getStringExtra(ROUTE)?.takeIf {
                it.matches(Regex("[0-9a-f]{64}"))
            }
        }
    } catch (_: RuntimeException) {
        null
    }
    fun publish(context: Context, rows: List<InboxCopy>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Private inbox updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility =
                    Notification.VISIBILITY_PRIVATE
            }
        )
        for (row in rows) {
            if (row.alert) {
                runCatching {
                    val tag = MessageDigest.getInstance("SHA-256").digest(
                        (
                            row.namespace.endpointKey + "|" +
                                row.namespace.enrollmentLabel + "|" +
                                row.namespace.profileId + "|" +
                                row.namespace.reviewEpoch +
                                "|" + row.eventIdentity
                            ).toByteArray()
                    ).joinToString("") { "%02x".format(it) }
                    val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
                        .putExtra(ROUTE, row.handle).setIdentifier(row.handle)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val pending = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val public = Notification.Builder(
                        context,
                        CHANNEL
                    ).setSmallIcon(R.drawable.ic_gadget)
                        .setContentTitle(
                            "Inbox update"
                        ).setContentText("Open the app to review.").build()
                    manager.notify(
                        tag,
                        1,
                        Notification.Builder(context, CHANNEL)
                            .setSmallIcon(R.drawable.ic_gadget).setContentTitle("Inbox update")
                            .setContentText(
                                "Open the app to review."
                            ).setVisibility(Notification.VISIBILITY_PRIVATE)
                            .setPublicVersion(
                                public
                            ).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                            .build()
                    )
                }
            }
        }
    }
}
