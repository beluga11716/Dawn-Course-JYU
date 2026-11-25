package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.SystemCalendarSyncResult
import com.dawncourse.core.domain.model.SystemCalendarSyncStatus
import kotlinx.coroutines.flow.Flow

interface SystemCalendarSyncRepository {
    fun observeStatus(): Flow<SystemCalendarSyncStatus>
    suspend fun syncCurrent(reminderMinutes: Int): SystemCalendarSyncResult
    /** 刷新用户已开启同步的各课表，使用各自当前学期，不会隐式开启其他课表。 */
    suspend fun refreshEnabled(): SystemCalendarSyncResult
    /** 停止同步并移除当前课表由本应用写入的日程。 */
    suspend fun removeCurrent(): SystemCalendarSyncResult
}
