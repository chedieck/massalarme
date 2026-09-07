package org.example.lanalarm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Retries a failed ontoplano sync when there is a network again.
 *
 * A weigh-in captured with the phone offline has to reach ontoplano eventually,
 * and the honest options are polling or asking the system to wake us. Polling is
 * what put this app at the top of the battery screen, so this is the other one:
 * one job, a network constraint, and no process running in between. The system
 * batches it with whatever else it was going to wake the device for.
 *
 * Nothing is lost if the job never runs — the queue is durable and every other
 * sync trigger drains it too. This is the backstop for a phone that was in
 * flight mode all morning, not the main path.
 */
class SyncRetryJob : JobService() {

    companion object {
        private const val TAG = "SyncRetryJob"
        private const val JOB_ID = 0x4D41

        /**
         * Do not stampede back the moment a captive-portal wifi appears.
         * A weigh-in that lands ten minutes late is indistinguishable from one
         * that lands on time.
         */
        private const val MIN_LATENCY_MS = 10 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return

            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncRetryJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(MIN_LATENCY_MS)
                // Survives a reboot: the readings do too, and a phone restarted
                // overnight should not need the app opened to catch up.
                .setPersisted(true)
                .setBackoffCriteria(MIN_LATENCY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()

            runCatching { scheduler.schedule(job) }
                .onSuccess { Log.i(TAG, "Retry job scheduled") }
                .onFailure { Log.w(TAG, "Could not schedule retry: ${it.message}") }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }

    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        worker = Thread {
            val outcome = OntoplanoSync.run(this)
            Log.i(TAG, "Retry: ${outcome.uploaded} up, ${outcome.pending} pending")
            // Reschedule only while something is still waiting, so a permanent
            // rejection does not become a permanent job.
            jobFinished(params, !outcome.ok && outcome.pending > 0)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.interrupt()
        // The queue is durable, so being cut short costs nothing but the wait.
        return true
    }
}
