package com.dawncourse.core.data.repository

import com.dawncourse.core.data.calendar.CalendarProviderStore
import com.dawncourse.core.data.calendar.SystemCalendarSyncRuntime
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.domain.model.CALENDAR_REMINDER_OPTIONS
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.SystemCalendarSyncResult
import com.dawncourse.core.domain.model.SystemCalendarSyncStatus
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.SystemCalendarSyncRepository
import com.dawncourse.core.domain.usecase.GenerateCalendarOccurrencesUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 在 Profile 选择锁和恢复门内解析目标，确保切换课表不会写错系统日历。 */
@Singleton
class SystemCalendarSyncRepositoryImpl @Inject constructor(
    private val coordinator: ProfileSelectionCoordinator,
    private val courses: CourseDao,
    private val settings: SettingsRepository,
    private val store: CalendarProviderStore,
    private val generate: GenerateCalendarOccurrencesUseCase,
    private val runtime: SystemCalendarSyncRuntime,
) : SystemCalendarSyncRepository {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private val problems = mutableMapOf<String, String>()
    private var operationProfile: String? = null

    override fun observeStatus(): Flow<SystemCalendarSyncStatus> = combine(
        coordinator.observeActiveContext(), revision,
    ) { _, _ ->
        mutex.withLock {
            coordinator.withResolvedActiveContext { context ->
                val permission = store.hasPermission()
                val calendar = if (permission) store.find(context.profile.uuid) else null
                SystemCalendarSyncStatus(
                    semesterName = context.semester?.name.orEmpty(), enabled = calendar?.reminderMinutes != null,
                    reminderMinutes = calendar?.reminderMinutes ?: 10,
                    eventCount = if (calendar != null) store.eventCount(context.profile.uuid) else 0,
                    lastSyncAt = calendar?.lastSyncAt ?: 0, permissionGranted = permission,
                    problem = problems[context.profile.uuid].orEmpty(),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(FlowPreview::class)
    internal fun observeChanges(): Flow<Unit> = combine(
        coordinator.observeProfileSummaries(), settings.settings.map { it.sectionTimes }.distinctUntilChanged(),
    ) { _, _ -> Unit }.debounce(700)

    override suspend fun syncCurrent(reminderMinutes: Int): SystemCalendarSyncResult {
        if (reminderMinutes !in CALENDAR_REMINDER_OPTIONS) return SystemCalendarSyncResult(false, "请选择有效的提醒时间")
        val result = perform { coordinator.withResolvedActiveContext { syncLocked(reminderMinutes, it) } }
        if (result.success) runtime.start()
        return result
    }

    override suspend fun refreshEnabled(): SystemCalendarSyncResult {
        val results = coordinator.observeProfiles().first().map { profile ->
            perform {
                coordinator.withResolvedProfileContext(profile.id) { context ->
                    if (context == null) SystemCalendarSyncResult(true, "课表已移除") else syncLocked(null, context)
                }
            }
        }
        return results.firstOrNull { !it.success } ?: SystemCalendarSyncResult(true, "已刷新开启同步的课程日历")
    }

    private suspend fun syncLocked(requestedMinutes: Int?, context: ActiveTimetableContext): SystemCalendarSyncResult {
        operationProfile = context.profile.uuid
        if (!store.hasPermission()) return SystemCalendarSyncResult(false, "请先允许读取和写入日历")
        val minutes = requestedMinutes ?: store.find(context.profile.uuid)?.reminderMinutes
            ?: return SystemCalendarSyncResult(true, "当前课表未开启系统日历同步")
        if (minutes !in CALENDAR_REMINDER_OPTIONS) return SystemCalendarSyncResult(false, "日历提醒配置无效，请重新选择提醒时间并同步")
        val semester = context.semester
        if (semester == null && requestedMinutes != null) return SystemCalendarSyncResult(false, "请先导入课程并选择学期")
        // 全量校验成功后才进行任何系统日历写入；空课表用于移除已删除课程的日程。
        val desired = if (semester == null) emptyList() else generate(courses.getCoursesBySemesterOnce(semester.id).map { it.toDomain() },
            semester, settings.settings.first().sectionTimes)
        // Provider 分批写入和完成标记不被协程取消打断，异常可通过相同身份安全重试。
        val count = withContext(NonCancellable) {
            store.reconcile(context.profile.uuid, desired, minutes,
                context.profile.name + (semester?.let { " · ${it.name}" } ?: ""))
        }
        return SystemCalendarSyncResult(true, "已同步 $count 次课程到系统日历，上课前 $minutes 分钟提醒")
    }

    override suspend fun removeCurrent(): SystemCalendarSyncResult = perform {
        coordinator.withResolvedActiveContext { context ->
            operationProfile = context.profile.uuid
            if (!store.hasPermission()) return@withResolvedActiveContext SystemCalendarSyncResult(false, "请先允许读取和写入日历")
            withContext(NonCancellable) { store.remove(context.profile.uuid) }
            SystemCalendarSyncResult(true, "已停止同步并移除本课表写入的日程")
        }
    }

    private suspend fun perform(action: suspend () -> SystemCalendarSyncResult): SystemCalendarSyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            operationProfile = null
            try {
                val result = try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    SystemCalendarSyncResult(false, "日历权限不可用，请在系统设置中允许后重试")
                } catch (invalid: IllegalArgumentException) {
                    SystemCalendarSyncResult(false, invalid.message ?: "课程信息无效，请检查课表")
                } catch (_: Exception) {
                    SystemCalendarSyncResult(false, "系统日历同步未完成，请稍后重试；已写入的日程不会重复添加")
                }
                operationProfile?.let { profile ->
                    if (result.success) problems.remove(profile) else problems[profile] = result.message
                }
                result
            } finally {
                revision.value += 1
            }
        }
    }
}
