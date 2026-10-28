package net.pendzialek.closeby.device

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.data.AppMode

/**
 * Keeps a relative's phone up to date without anyone opening the app: every few
 * hours on Wi-Fi, while CloseBy is not on screen and no call is going on, a
 * newer release is installed silently (Android 12+, once CloseBy may install
 * apps). Without that, the contact screen offers the update instead.
 */
class UpdateJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as CloseByApp
        // nobody may be using the app, neither now nor once the download is done
        val unused = { !app.uiVisible && !app.inCall() }
        if (app.mode.value != AppMode.CONTACT || !unused() || !app.updater.canInstallSilently()) return false
        work = app.scope.launch {
            try {
                val release = app.updater.newerRelease()
                if (release != null) Log.i("CloseBy", "update: ${app.updater.install(release, unused)}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // try again at the next period
        return false
    }

    companion object {
        private const val JOB_ID = 4711
        private const val PERIOD_MS = 6 * 60 * 60_000L

        /** Idempotent: an already scheduled job keeps its period. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
