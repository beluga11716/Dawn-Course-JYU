package com.dawncourse.core.domain.model

/** 系统日历只接收课程安排，不包含教务账号或登录会话。 */
data class CalendarOccurrence(
    val key: String,
    val title: String,
    val location: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
)

data class SystemCalendarSyncStatus(
    val semesterName: String = "",
    val enabled: Boolean = false,
    val reminderMinutes: Int = 10,
    val eventCount: Int = 0,
    val lastSyncAt: Long = 0,
    val permissionGranted: Boolean = false,
    val problem: String = "",
)

data class SystemCalendarSyncResult(val success: Boolean, val message: String)

val CALENDAR_REMINDER_OPTIONS = listOf(5, 10, 15, 30)
