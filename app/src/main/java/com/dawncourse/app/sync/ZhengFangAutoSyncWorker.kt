package com.dawncourse.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dawncourse.app.MainActivity
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.model.ZhengFangSyncTarget
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import com.dawncourse.core.domain.repository.ZhengFangSyncRepository
import com.dawncourse.core.domain.repository.SystemCalendarSyncRepository
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class ZhengFangAutoSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val dataGate: OperationalDataGate,
    private val repository: Lazy<ZhengFangSyncRepository>,
    private val calendarRepository: Lazy<SystemCalendarSyncRepository>,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        syncCapturedTarget()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 本地存储或任务调度暂不可用时保留旧课表，也不误发过期提醒。
        if (runAttemptCount < 3) Result.retry() else Result.success()
    }

    private suspend fun syncCapturedTarget(): Result {
        when (dataGate.readiness()) {
            OperationalDataReadiness.STARTING -> return Result.retry()
            OperationalDataReadiness.RECOVERY_REQUIRED -> return Result.success()
            OperationalDataReadiness.READY -> Unit
        }
        val target = ZhengFangSyncTarget(
            inputData.getLong("profileId", -1), inputData.getLong("semesterId", -1),
            inputData.getString("bindingId") ?: return Result.success(),
        )
        if (target.profileId <= 0 || target.semesterId <= 0) return Result.success()
        val repo = repository.get()
        return when (val result = repo.sync(target, automatic = true)) {
            is TimetableSyncResult.Success -> {
                calendarRepository.get().refreshEnabled()
                ZhengFangSyncNotification.cancel(applicationContext, target)
                Result.success()
            }
            is TimetableSyncResult.Failure -> when (result.code) {
                SyncErrorCode.AUTH_FAILED -> {
                    val state = repo.states.first().firstOrNull { it.target == target }
                    if (state?.autoSyncEnabled == true && !state.authNotificationSent &&
                        ZhengFangSyncNotification.show(applicationContext, target)
                    ) repo.markAuthNotificationSent(target)
                    Result.success()
                }
                SyncErrorCode.NETWORK_ERROR, SyncErrorCode.SERVER_ERROR -> {
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                }
                else -> Result.success()
            }
        }
    }
}

/** 只有启用目标集合变化时调度；不能让每次状态更新都重置首次延迟。 */
object ZhengFangAutoSyncScheduler {
    private const val TAG = "ZhengFangAutoSync"
    private fun name(target: ZhengFangSyncTarget) =
        "$TAG:${target.profileId}:${target.semesterId}:${target.sourceBindingId}"

    suspend fun schedule(context: Context, targets: List<ZhengFangSyncTarget>) = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val wanted = targets.map(::name).toSet()
        ZhengFangSyncNotification.cancelObsolete(context, targets)
        manager.getWorkInfosByTag(TAG).get().flatMap { it.tags }.toSet()
            .filter { it.startsWith("$TAG:") && it !in wanted }
            .forEach(manager::cancelUniqueWork)
        targets.forEach { target ->
            val uniqueName = name(target)
            val request = PeriodicWorkRequestBuilder<ZhengFangAutoSyncWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(
                    "profileId" to target.profileId, "semesterId" to target.semesterId,
                    "bindingId" to target.sourceBindingId,
                ))
                .addTag(TAG).addTag(uniqueName)
                .build()
            manager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** 每个不可变绑定一条低干扰通知；Intent 只带目标身份，不带任何凭据。 */
internal object ZhengFangSyncNotification {
    const val ACTION_RELOGIN = "com.dawncourse.app.ZF_RELOGIN"
    private const val CHANNEL = "zhengfang_session"
    private fun tag(target: ZhengFangSyncTarget) = "zf_session:${target.sourceBindingId}"

    fun show(context: Context, target: ZhengFangSyncTarget): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "课表会话", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_RELOGIN)
            .setData(Uri.parse("dawncourse://zf-session/${target.profileId}/${target.semesterId}"))
            .putExtra("zfProfileId", target.profileId)
            .putExtra("zfSemesterId", target.semesterId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("课表需要重新登录")
            .setContentText("教务会话已过期，点此登录后继续自动同步")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .build()
        return runCatching { manager.notify(tag(target), 1, notification); true }.getOrDefault(false)
    }

    fun cancel(context: Context, target: ZhengFangSyncTarget) {
        context.getSystemService(NotificationManager::class.java).cancel(tag(target), 1)
    }

    fun cancelObsolete(context: Context, targets: List<ZhengFangSyncTarget>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val wanted = targets.map(::tag).toSet()
        manager.activeNotifications.filter { it.tag?.startsWith("zf_session:") == true && it.tag !in wanted }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
