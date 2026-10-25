package dev.quantumink.hermesgadget.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.quantumink.hermesgadget.client.EncryptedInboxFile
import dev.quantumink.hermesgadget.client.EncryptedRelayStore
import dev.quantumink.hermesgadget.client.ExecutorRelayScheduler
import dev.quantumink.hermesgadget.client.failClosed
import dev.quantumink.hermesgadget.protocol.relay.RelayOptIn
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.InboxCopy
import dev.quantumink.hermesgadget.protocol.relay.session.InboxRepository
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The watch's own Relay mode: ONE controller and aggregate writer for this process over watch-local encrypted stores
 * (own directories and Keystore aliases, never the phone's), the watch's own enrollment per profile, foreground only.
 * Nothing is enrolled, created or connected until the user turns Relay on for a profile; while it is off the
 * connection is built exactly as before.
 */
internal class WatchRelayMode(
    context: Context,
    private val ui: (RelayUiState) -> Unit,
    private val inbox: (InboxSnapshot?, String) -> Unit
) : AutoCloseable {
    private val app = context.applicationContext
    private val scheduler = ExecutorRelayScheduler()
    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "watch-inbox").apply {
            isDaemon =
                true
        }
    }
    private val sessions = AtomicLong()
    private val repository = InboxRepository(
        EncryptedInboxFile.create(app, INBOX_DIRECTORY, INBOX_ALIAS).failClosed()
    )

    @Volatile private var current: Pair<EnrollmentIdentity, Long>? = null
    val controller = RelayController(
        EncryptedRelayStore.android(app, File(app.noBackupFilesDir, STATE_DIRECTORY), STATE_ALIAS)
            .failClosed(),
        scheduler,
        SystemClock::elapsedRealtimeNanos,
        SecureRequestIdEntropy(),
        inbox = repository,
        inboxUpdates = inbox,
        inboxAlerts = { rows -> WatchInboxAlerts.publish(app, rows, repository.peek()) }
    ) { state ->
        val open = current
        if (open != null && state.identity == open.first &&
            state.foregroundSession == open.second
        ) {
            ui(state)
        }
    }

    /** Shows the saved setting of [who] for a newly selected profile; returns its foreground session. */
    fun open(who: EnrollmentIdentity?, key: String, label: String): Long {
        val session = sessions.incrementAndGet()
        if (who == null) {
            current = null
            controller.hide()
            ui(RelayUiState())
            return session
        }
        if (current?.first != who) ui(RelayUiState(phase = RelayPhase.CHECKING))
        current = who to session
        controller.inboxTarget(key.take(512), label.take(512), "Saved on watch")
        controller.prepare(who, session)
        return session
    }

    fun current(who: EnrollmentIdentity?, session: Long): Boolean = current == (who to session)

    /** Blocks the caller (never main): true when Relay is on and usable for [who] in [session]. */
    fun enabledBlocking(who: EnrollmentIdentity, session: Long): Boolean =
        controller.preparedBlocking(who, session) && controller.enabledBlocking() &&
            current(who, session)

    /** The opt-in of ONE connection; its reports reach the controller only while [live] holds. */
    fun optIn(live: () -> Boolean): RelayOptIn = RelayOptIn(
        { state, generation -> if (live()) controller.onChannel(state, generation) },
        SystemClock::elapsedRealtimeNanos
    )

    fun loadInbox() {
        runCatching {
            worker.execute {
                val snapshot = runCatching { controller.inboxBlocking() }.getOrNull()
                inbox(snapshot, controller.inboxStatus)
            }
        }
    }

    /** A deliberate refresh over the live connection of the selected profile only (no background polling). */
    fun refreshInbox() = controller.refreshInbox { loadInbox() }

    override fun close() {
        current = null
        controller.hide()
        worker.shutdownNow()
        scheduler.close()
    }

    companion object {
        const val STATE_DIRECTORY = "watch-relay-state"
        const val STATE_ALIAS = "quantumink.relay.watch.state.v1"
        const val INBOX_DIRECTORY = "watch-inbox"
        const val INBOX_ALIAS = "quantumink.relay.watch.inbox.v1"
    }
}

/** Private, quiet task notifications: the lock screen sees only a generic line; no reply text, no speech. */
internal object WatchInboxAlerts {
    const val ACTION = "dev.quantumink.hermesgadget.wear.INBOX"
    const val ROUTE = "inbox_route"
    private const val CHANNEL = "private-inbox-v1"

    fun handle(intent: Intent?): String? = runCatching {
        intent?.takeIf { it.action == ACTION }?.getStringExtra(ROUTE)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()

    fun publish(context: Context, rows: List<InboxCopy>, snapshot: InboxSnapshot?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Private task updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        for (row in rows.filter { it.alert }) {
            runCatching {
                val label = snapshot?.profiles?.firstOrNull { it.namespace == row.namespace }?.label
                val text = WatchRelayModel.alert(row, label)
                val tag = MessageDigest.getInstance("SHA-256").digest(
                    (
                        row.namespace.endpointKey + "|" + row.namespace.enrollmentLabel + "|" +
                            row.namespace.profileId + "|" + row.namespace.reviewEpoch + "|" +
                            row.eventIdentity
                        ).toByteArray()
                ).joinToString("") { "%02x".format(it) }
                val open = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).setAction(ACTION)
                        .putExtra(ROUTE, row.handle).setIdentifier(row.handle)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val public = Notification.Builder(
                    context,
                    CHANNEL
                ).setSmallIcon(R.drawable.ic_gadget)
                    .setContentTitle(text.publicTitle).setContentText(text.publicText).build()
                manager.notify(
                    tag,
                    1,
                    Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_gadget)
                        .setContentTitle(text.title).setContentText(text.text)
                        .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
                        .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_STATUS).build()
                )
            }
        }
    }
}
