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
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
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

// Re-plans the periodic work whenever GroupManager mutates a group (registered
// in SagerNet.onCreate for both processes): a new subscription group has to be
// scheduled, an update may switch a group between subscription and basic, and
// a deletion may leave nothing to schedule. reconfigureLocked recomputes from
// the DB and enqueues with UPDATE, which keeps the period, so the extra runs
// on postUpdate(group) (e.g. after a subscription finished updating) are cheap.
object SubscriptionUpdater : GroupManager.Listener {

    private const val WORK_NAME = "SubscriptionUpdater"

    private val schedulingMutex = Mutex()

    // subscription groups with auto update on, paired with their non-null settings
    private suspend fun autoUpdateSubscriptions(): List<Pair<ProxyGroup, SubscriptionBean>> =
        SagerDatabase.groupDao.subscriptions().mapNotNull { group ->
            group.subscription?.takeIf { it.autoUpdate }?.let { group to it }
        }

    suspend fun reconfigureUpdater() = schedulingMutex.withLock {
        reconfigureLocked()
    }

    override suspend fun groupAdd(group: ProxyGroup) {
        if (group.type == GroupType.SUBSCRIPTION) reconfigureUpdater()
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        reconfigureUpdater()
    }

    override suspend fun groupRemoved(groupId: Long) {
        reconfigureUpdater()
    }

    // a reload only, the row is unchanged
    override suspend fun groupUpdated(groupId: Long) {}

    private suspend fun reconfigureLocked() {
        val workManager = RemoteWorkManager.getInstance(app)

        val subscriptions = autoUpdateSubscriptions().map { it.second }
        if (subscriptions.isEmpty()) {
            workManager.cancelUniqueWork(WORK_NAME).awaitSchedulingCompletion()
            return
        }

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay = subscriptions.minOf { it.autoUpdateDelay }.toLong()
        val now = System.currentTimeMillis() / 1000L
        // Seconds until the soonest-due subscription, each against its own
        // autoUpdateDelay; an overdue one makes this negative, so no initial
        // delay is set and the update runs immediately.
        val minInitDelay =
            subscriptions.minOf { it.lastUpdated + it.autoUpdateDelay * 60L - now }
        if (minDelay < 15) minDelay = 15

        // 排期由哪个进程发起都行：listener 在主进程和 :bg 都注册，:bg 每跑完一次
        // 订阅更新就会经 GroupUpdater.finishUpdate → postUpdate(id) 走到这里，
        // RemoteWorkManager 本就是跨进程 API，UPDATE 策略保证重复入队不重置周期。
        // 但 UpdateTask 必须落在 :bg —— 只有该进程的 ServiceRegistry.state 跟着
        // 服务走。下面两个参数让 WorkManager 把请求转交给 RemoteWorkerService
        // （manifest 里钉在 :bg）；不加的话这个 work 排上了也什么都不做
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
                var subscriptions = autoUpdateSubscriptions()
                if (!ServiceRegistry.state.connected) {
                    Logs.d("work: not connected")
                    subscriptions = subscriptions.filter { (_, subscription) -> !subscription.updateWhenConnectedOnly }
                }

                for ((profile, subscription) in subscriptions) {
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
