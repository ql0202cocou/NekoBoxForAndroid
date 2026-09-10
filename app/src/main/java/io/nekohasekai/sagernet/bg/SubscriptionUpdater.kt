package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_CLASS_NAME
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_PACKAGE_NAME
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    private val schedulingMutex = Mutex()

    suspend fun reconfigureUpdater() = schedulingMutex.withLock {
        reconfigureLocked()
    }

    private suspend fun reconfigureLocked() {
        val workManager = RemoteWorkManager.getInstance(app)

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription?.autoUpdate == true }
        if (subscriptions.isEmpty()) {
            workManager.cancelUniqueWork(WORK_NAME).awaitSchedulingCompletion()
            return
        }

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay =
            subscriptions.minByOrNull { it.subscription!!.autoUpdateDelay }!!.subscription!!.autoUpdateDelay.toLong()
        val now = System.currentTimeMillis() / 1000L
        // Seconds until the soonest-due subscription, each against its own
        // autoUpdateDelay; an overdue one makes this negative, so no initial
        // delay is set and the update runs immediately.
        val minInitDelay =
            subscriptions.minOf { it.subscription!!.lastUpdated + it.subscription!!.autoUpdateDelay * 60L - now }
        if (minDelay < 15) minDelay = 15

        // Scheduling stays in the main process, but UpdateTask has to run in :bg —
        // the only process whose DataStore.serviceState tracks the service. These two
        // arguments make WorkManager delegate the request to RemoteWorkerService
        // (manifest-pinned to :bg); without them the work would never do anything.
        val remoteArgs = Data.Builder()
            .putString(ARGUMENT_PACKAGE_NAME, app.packageName)
            .putString(ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                .setInputData(remoteArgs)
                .apply {
                    if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                }
                .build()
        ).awaitSchedulingCompletion()
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : RemoteCoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // POST_NOTIFICATIONS is requested in MainActivity; when denied, notify()
        // is silently dropped by the system — no SecurityException to handle
        @SuppressLint("MissingPermission")
        override suspend fun doRemoteWork(): Result {
            try {
                var subscriptions =
                    SagerDatabase.groupDao.subscriptions().filter { it.subscription?.autoUpdate == true }
                if (!DataStore.serviceState.connected) {
                    Logs.d("work: not connected")
                    subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
                }

                if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                    val subscription = profile.subscription!!

                    if ((System.currentTimeMillis() / 1000 - subscription.lastUpdated) < subscription.autoUpdateDelay * 60L) {
                        Logs.d("work: not updating " + profile.displayName())
                        continue
                    }
                    Logs.d("work: updating " + profile.displayName())

                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    nm.notify(2, notification.build())

                    GroupUpdater.executeUpdate(profile, false)
                }
            } finally {
                // also runs when the work is cancelled, so the progress
                // notification never lingers
                nm.cancel(2)
            }

            return Result.success()
        }
    }

}

// Do not release the scheduling mutex while an already-submitted Binder
// operation is pending. Cancelling its local Future does not undo remote work.
internal suspend fun ListenableFuture<*>.awaitSchedulingCompletion(): Unit = suspendCoroutine { continuation ->
    addListener({
        try {
            get()
            continuation.resume(Unit)
        } catch (e: ExecutionException) {
            continuation.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }, { command -> command.run() })
}
