package com.dawncourse.core.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.dawncourse.core.data.repository.SystemCalendarSyncRepositoryImpl
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 仅在有日历权限的主进程等待数据库就绪，然后观察课表变化。 */
@Singleton
class SystemCalendarSyncRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gate: OperationalDataGate,
    private val repository: Provider<SystemCalendarSyncRepositoryImpl>,
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
            !started.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (gate.awaitReadiness(60_000) != OperationalDataReadiness.READY) return@launch
                val repo = repository.get()
                repo.observeChanges().collect { repo.refreshEnabled() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 日历不可用不影响课表主功能；手动同步或下次启动可恢复监听。
            } finally {
                started.set(false)
            }
        }
    }
}
