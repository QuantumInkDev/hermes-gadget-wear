package dev.quantumink.hermesgadget.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryNamespace
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Exact persisted constraint comparison on the supported API29+ job contract.
@Suppress("DEPRECATION")
internal object InboxJobs {
    private const val ID = 19051
    private const val INTERVAL = 15 * 60 * 1000L
    private const val FLEX = 5 * 60 * 1000L
    private val mutable = MutableStateFlow("Background updates off")
    val status = mutable.asStateFlow()

    /**
     * Called only after a confirmed settings read/change on the IO worker. Unchanged jobs are not
     * rescheduled.
     */
    fun configure(context: Context, snapshot: InboxSnapshot?, eligible: Set<HistoryNamespace>) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val on =
            snapshot?.let {
                it.background &&
                    it.profiles.any { p -> p.enabled && p.namespace in eligible }
            } ==
                true
        val old = scheduler.getPendingJob(ID)
        if (!on) {
            if (old != null) scheduler.cancel(ID)
            mutable.value = "Background updates off"
            return
        }
        if (old != null && old.intervalMillis == INTERVAL && old.flexMillis == FLEX &&
            old.isPersisted &&
            old.networkType == JobInfo.NETWORK_TYPE_ANY
        ) {
            mutable.value = "Scheduled / Android may delay updates"
            return
        }
        val job = JobInfo.Builder(ID, ComponentName(context, InboxJobService::class.java))
            .setRequiredNetworkType(
                JobInfo.NETWORK_TYPE_ANY
            ).setPeriodic(INTERVAL, FLEX).setPersisted(true).build()
        mutable.value = if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
            "Scheduled / Android may delay updates"
        } else {
            "Background scheduling failed"
        }
    }
}

/**
 * No main-thread store/crypto/network. Stop, network replacement and finish share one generation
 * path.
 */
class InboxJobService : JobService() {
    private var serial = 0L

    @Volatile private var active: Pair<Long, JobParameters>? = null
    private val main = Handler(Looper.getMainLooper())
    override fun onStartJob(params: JobParameters): Boolean {
        val owner = (application as CompanionApplication).inbox
        if (active != null) {
            active = null
            owner.stopBackground()
        }
        val generation = ++serial
        active = generation to params
        owner.refresh(params.network, background = true, alive = { active?.first == generation }) {
            main.post {
                if (active?.first == generation && active?.second === params) {
                    active = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        if (active?.second === params) {
            active = null
            serial++
            (application as CompanionApplication).inbox.stopBackground()
        }
        return false
    }
    override fun onNetworkChanged(params: JobParameters) {
        if (active?.second === params) {
            active = null
            serial++
            (application as CompanionApplication).inbox.stopBackground()
            jobFinished(params, false)
        }
    }
}
