package dev.quantumink.hermesgadget.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.relay.RelayOptIn
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.InboxProfile
import dev.quantumink.hermesgadget.protocol.relay.session.InboxRepository
import dev.quantumink.hermesgadget.protocol.relay.session.InboxRoute
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * ONE aggregate writer and runtime for this process, shared by foreground and bounded headless
 * observers.
 */
internal class PhoneInboxOwner(private val app: Application) {
    val scheduler = ExecutorRelayScheduler()
    private val leases = InboxLeases()

    @Volatile private var foreground = 0L

    @Volatile private var actor = 0L
    private val epochs = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var listener: (RelayUiState) -> Unit = {}
    private val repository = InboxRepository(EncryptedInboxFile.create(app))
    private val mutable = MutableStateFlow<InboxSnapshot?>(null)
    val state = mutable.asStateFlow()
    private val statusMutable = MutableStateFlow("Retained / offline")
    val status = statusMutable.asStateFlow()
    val worker = Executors.newSingleThreadExecutor {
        Thread(it, "phone-inbox-work").apply {
            isDaemon =
                true
        }
    }
    val controller = RelayController(
        EncryptedRelayStore.android(app), scheduler,
        SystemClock::elapsedRealtimeNanos, SecureRequestIdEntropy(),
        retainedHistory = PhoneHistoryCache(
            app
        ).retained(),
        writerEpoch = { actor }, inbox = repository,
        inboxUpdates = { snapshot, status ->
            mutable.value = snapshot
            statusMutable.value = status
        }, inboxAlerts = { copies -> InboxNotifications.publish(app, copies) }
    ) { ui ->
        if (leases.isForeground(foreground)) listener(ui)
    }
    fun listener(value: (RelayUiState) -> Unit): RelayController {
        listener = value
        return controller
    }

    @Synchronized fun foreground(withdraw: () -> Unit): Long {
        val id = leases.foreground(withdraw)
        foreground = id
        actor = epochs.incrementAndGet()
        controller.attachTransport(null)
        controller.observeInboxOnly(false)
        return id
    }
    fun release(id: Long) {
        leases.release(id)
    }
    fun stopBackground() {
        leases.cancelBackground()
    }
    fun current(id: Long): Boolean = leases.current(id)
    class Resolved(val route: InboxRoute, val page: CatalogPage, val profile: EndpointProfile)

    /** IO-only lifecycle lookup. No current/default profile fallback, enrollment or connection. */
    fun resolve(handle: String): Resolved? {
        SetupTransferGate.ensureRecovered()
        val route = controller.inboxRouteBlocking(handle) ?: return null
        val catalog = CatalogRepository.get(app)
        catalog.savedPages()
        val found = catalog.currentProfile(route.profile.key) ?: return null
        if (found.first.status == CatalogStatus.WITHDRAWN ||
            found.second.endpoint.url != route.namespace.endpointUrl
        ) {
            return null
        }
        val known =
            (
                ConversationVault(
                    app
                ).lookup(found.second.endpoint) as? PhoneIdentityLookup.Known
                )?.identity
                ?: return null
        if ("phone:" + known.deviceId != route.namespace.enrollmentLabel) return null
        return Resolved(route, found.first, found.second)
    }
    fun load() {
        worker.execute {
            runCatching {
                SetupTransferGate.ensureRecovered()
                mutable.value = controller.inboxBlocking()
                statusMutable.value = controller.inboxStatus
                InboxJobs.configure(app, mutable.value, controller.inboxBackgroundTargetsBlocking())
            }.onFailure { statusMutable.value = "Inbox storage unavailable" }
        }
    }
    fun configure(profile: InboxProfile) {
        stopBackground()
        controller.configureInbox(profile)
        load()
    }
    fun markRead(handle: String) {
        controller.markInboxRead(handle)
    }
    fun retention(days: Int) {
        controller.inboxRetention(days)
        load()
    }
    fun background(on: Boolean) {
        if (!on) stopBackground()
        controller.inboxBackground(on)
        load()
    }

    /**
     * The assigned network owns BOTH DNS and sockets. Refuse a different active VPN/tailnet
     * instead of falling back.
     */
    fun refresh(
        network: Network?,
        background: Boolean = false,
        alive: () -> Boolean = { true },
        finished: () -> Unit = {}
    ) {
        worker.execute {
            var link: DirectConnection? = null
            var client: OkHttpClient? = null
            var lease: Long? = null
            try {
                SetupTransferGate.ensureRecovered()
                if (!alive()) return@execute
                if (!background && leases.isForeground(foreground)) {
                    controller.refreshInbox { status ->
                        statusMutable.value = status
                        load()
                    }
                    return@execute
                }
                val snapshot = controller.inboxBlocking() ?: return@execute
                if (background && !snapshot.background) return@execute
                val connectivity = app.getSystemService(ConnectivityManager::class.java)
                val assigned = network ?: connectivity.activeNetwork ?: return@execute
                if (connectivity.activeNetwork != assigned) return@execute
                val id = leases.background {
                    link?.relayTransport?.invalidatePending()
                    actor = epochs.incrementAndGet()
                    controller.attachTransport(null)
                    link?.close()
                }
                    ?: run {
                        statusMutable.value = "Foreground connection owns updates"
                        return@execute
                    }
                lease = id
                actor = epochs.incrementAndGet()
                val catalog = CatalogRepository.get(app)
                catalog.savedPages()
                val eligible = controller.inboxBackgroundTargetsBlocking()
                val targets = snapshot.profiles.filter {
                    it.enabled && it.namespace in eligible
                }.take(16)
                val cursor = controller.nextInboxRoundRobinBlocking() ?: return@execute
                val start = if (targets.isEmpty()) 0 else cursor % targets.size
                val deadline = SystemClock.elapsedRealtime() + 55_000
                for (p in targets.drop(start) + targets.take(start)) {
                    if (!alive() || !leases.current(
                            id
                        ) || SystemClock.elapsedRealtime() >= deadline ||
                        connectivity.activeNetwork != assigned
                    ) {
                        break
                    }
                    val found = catalog.currentProfile(p.key) ?: continue
                    if (found.first.status == CatalogStatus.WITHDRAWN ||
                        found.second.endpoint.url != p.namespace.endpointUrl
                    ) {
                        continue
                    }
                    val identity =
                        (
                            ConversationVault(
                                app
                            ).lookup(found.second.endpoint) as? PhoneIdentityLookup.Known
                            )?.identity
                            ?: continue
                    val who = EnrollmentIdentity(
                        found.second.endpoint.url,
                        "phone:" + identity.deviceId
                    )
                    if (who.enrollmentLabel != p.namespace.enrollmentLabel) continue
                    controller.observeInboxOnly(true)
                    controller.prepare(who, id)
                    if (!controller.preparedBlocking(who, id) || !controller.enabledBlocking() ||
                        !controller.inboxAdmissionBlocking(p.namespace) ||
                        !leases.current(id) ||
                        !alive()
                    ) {
                        continue
                    }
                    val complete = CountDownLatch(1)
                    controller.observeInboxOnly(true) { complete.countDown() }
                    client = OkHttpClient.Builder().socketFactory(assigned.socketFactory).build()
                    val current =
                        DirectConnection(
                            found.second.endpoint,
                            identity,
                            object : ConnectionObserver {
                                override fun stateChanged(state: ConnectionState) {}
                                override fun effect(effect: ConversationEffect, generation: Long) {}
                            },
                            accessToken = found.second.accessToken,
                            httpClient = client,
                            relay = RelayOptIn({ state, generation ->
                                if (alive() &&
                                    leases.current(id)
                                ) {
                                    controller.onChannel(state, generation)
                                }
                            }, SystemClock::elapsedRealtimeNanos),
                            dns = object : Dns {
                                override fun lookup(hostname: String): List<java.net.InetAddress> {
                                    if (!leases.current(id) ||
                                        connectivity.activeNetwork != assigned
                                    ) {
                                        throw java.net.UnknownHostException()
                                    }
                                    return assigned.getAllByName(hostname).toList()
                                }
                            }
                        )
                    link = current
                    if (!alive() || !leases.current(id)) {
                        current.close()
                        break
                    }
                    controller.attachTransport(current.relayTransport)
                    current.connect()
                    complete.await(
                        minOf(22_000L, maxOf(1L, deadline - SystemClock.elapsedRealtime())),
                        TimeUnit.MILLISECONDS
                    )
                    current.relayTransport?.invalidatePending()
                    current.close()
                    link = null
                    if (leases.current(id)) controller.attachTransport(null)
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                    client =
                        null
                    mutable.value = controller.inboxBlocking()
                    statusMutable.value = controller.inboxStatus
                }
            } catch (
                _: Exception
            ) {
                statusMutable.value = "Partial / updates unavailable"
            } finally {
                link?.relayTransport?.invalidatePending()
                link?.close()
                lease?.let { leases.release(it) }
                client?.dispatcher?.executorService?.shutdown()
                client?.connectionPool?.evictAll()
                finished()
            }
        }
    }
}
