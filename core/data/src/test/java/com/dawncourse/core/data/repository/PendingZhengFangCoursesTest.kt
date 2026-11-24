package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.ZhengFangAcademicTerm
import com.dawncourse.core.domain.model.ZhengFangLoginSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingZhengFangCoursesTest {
    private val context = ActiveTimetableContext(TimetableProfile(1L, "profile", "课表", null))
    private val login = ZhengFangLoginSnapshot(ZhengFangAcademicTerm("2026", "3"), "cookie", "ua")

    @Test fun dateConfirmationReusesFetchButChangedSessionTermProfileAndCredentialsDoNot() = runBlocking {
        val cache = PendingZhengFangCourses()
        var calls = 0
        suspend fun read(ctx: ActiveTimetableContext = context, credentials: SyncCredentials? = null,
            snapshot: ZhengFangLoginSnapshot = login) = cache.read(ctx, credentials, snapshot, confirmingStartDate = true) { calls++; emptyList() }
        read()
        read(snapshot = login.copy(startDate = 100L))
        assertEquals(1, calls)
        read(snapshot = login.copy(cookieHeader = "other"))
        read(snapshot = login.copy(term = ZhengFangAcademicTerm("2026", "12")))
        read(ctx = context.copy(profile = context.profile.copy(id = 2L)))
        read(credentials = SyncCredentials(SyncProviderType.WAKEUP, SyncCredentialType.TOKEN, secret = "token"))
        assertEquals(5, calls)
    }

    @Test fun expiredOrDiscardedCoursesAreFetchedAgain() = runBlocking {
        var time = 1L
        val cache = PendingZhengFangCourses { time }
        var calls = 0
        suspend fun read() = cache.read(context, null, login, confirmingStartDate = true) { calls++; emptyList() }
        read()
        time += 300_000_000_000L
        read()
        cache.clear()
        read()
        assertEquals(3, calls)
    }

    @Test fun restartingWebLoginAlwaysFetchesFreshCoursesEvenWithSameSession() = runBlocking {
        val cache = PendingZhengFangCourses()
        var calls = 0
        cache.read(context, null, login, false) { calls++; emptyList() }
        cache.read(context, null, login.copy(startDate = 100L), false) { calls++; emptyList() }
        assertEquals(2, calls)
    }
}
