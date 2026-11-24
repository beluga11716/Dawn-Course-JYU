package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.ZhengFangLoginSnapshot

/** 只暂存在仓库内存中，供一次开学日期确认复用；调用者持有同步互斥锁。 */
internal class PendingZhengFangCourses(private val now: () -> Long = System::nanoTime) {
    private var context: ActiveTimetableContext? = null
    private var credentials: SyncCredentials? = null
    private var snapshot: ZhengFangLoginSnapshot? = null
    private var courses: List<Course>? = null
    private var expiresAt = 0L

    suspend fun read(
        expectedContext: ActiveTimetableContext,
        expectedCredentials: SyncCredentials?,
        login: ZhengFangLoginSnapshot,
        confirmingStartDate: Boolean,
        fetch: suspend () -> List<Course>,
    ): List<Course> {
        val key = login.copy(startDate = null)
        val saved = courses
        if (confirmingStartDate && login.startDate != null && saved != null && now() < expiresAt && context == expectedContext &&
            credentials == expectedCredentials && snapshot == key) return saved
        clear()
        return fetch().also {
            context = expectedContext
            credentials = expectedCredentials
            snapshot = key
            courses = it
            expiresAt = now() + 300_000_000_000L
        }
    }

    fun clear() {
        context = null
        credentials = null
        snapshot = null
        courses = null
        expiresAt = 0L
    }
}
