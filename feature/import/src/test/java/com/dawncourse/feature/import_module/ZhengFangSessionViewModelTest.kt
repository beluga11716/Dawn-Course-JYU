package com.dawncourse.feature.import_module

import androidx.lifecycle.ViewModelStore
import com.dawncourse.core.domain.model.*
import com.dawncourse.core.domain.repository.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** 从首次打开同步页开始验证网页登录、日期确认和同步，仓库边界使用内存存储。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZhengFangSessionViewModelTest {
    private val profile = TimetableProfile(1L, "profile", "主课表", 8L)
    private val semester = Semester(8L, 1L, "2026 秋", 1L)
    private val context = ActiveTimetableContext(profile, semester)
    private val profiles = Profiles(context)
    private val accounts = Accounts { profiles.active.value }
    private val sync = Sync()
    private val store = ViewModelStore()
    private val saved = SyncCredentials(SyncProviderType.ZF, SyncCredentialType.SESSION, "20260001", "school-password",
        ZhengFangWebVpn.BASE_URL, ZhengFangSession("private-cookie", "2026", "3", 8L, "ua", 1L))
    private val snapshot = ZhengFangLoginSnapshot(ZhengFangAcademicTerm("2026", "3"), "private-cookie", "ua")

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel(): ZhengFangSessionViewModel =
        ZhengFangSessionViewModel(profiles, accounts, accounts, sync).also { store.put("sync", it) }

    @Test fun firstTimeUserCanOpenWebsiteWithoutBindingCredentials() {
        val vm = viewModel()
        assertTrue(vm.initialized.value)
        assertTrue(vm.canLogin.value)
        assertNull(accounts.saved)
    }

    @Test fun localContextArrivingAfterPageOpensEnablesLogin() {
        profiles.active.value = null
        val vm = viewModel()
        assertFalse(vm.canLogin.value)
        profiles.active.value = context
        assertTrue(vm.canLogin.value)
    }

    @Test fun existingUserKeepsSessionAutoSyncAndBindingWhenOpeningPage() {
        accounts.saved = saved
        val state = ZhengFangSyncState(ZhengFangSyncTarget(1L, 8L, "original"), autoSyncEnabled = true)
        sync.states.value = listOf(state)
        val vm = viewModel()
        assertSame(saved, accounts.saved)
        assertEquals(state, vm.state.value)
        assertEquals("original", accounts.binding)
        assertTrue(vm.canLogin.value)
    }

    @Test fun firstLoginImportsDetectedTermWithoutPrecreatedSemester() {
        profiles.active.value = ActiveTimetableContext(profile.copy(activeSemesterId = null))
        val vm = viewModel()
        assertTrue(vm.canLogin.value)
        vm.importFromLogin(snapshot.copy(startDate = 1L))
        assertEquals("2026-2027学年第一学期", vm.semesterName.value)
        assertEquals(8L, vm.state.value?.target?.semesterId)
        assertNull(sync.lastExpectedCredentials)
        assertEquals("", accounts.saved?.secret)
        assertTrue(vm.hasSession.value)
    }

    @Test fun missingSchoolStartDateRequestsOnlyDateAndCanFinishWithoutAccountReentry() {
        val vm = viewModel()
        sync.needsStartDate = true
        vm.importFromLogin(snapshot)
        assertEquals(snapshot.term, vm.startDateRequired.value)
        vm.confirmImportStartDate("2026-09-01")
        assertNull(vm.startDateRequired.value)
        assertEquals("2026-2027学年第一学期", vm.semesterName.value)
        val monday = LocalDate.of(2026, 8, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(monday, sync.lastSnapshot?.startDate)
    }

    @Test fun invalidOrOutOfAcademicYearDateCannotCommit() {
        val vm = viewModel()
        sync.needsStartDate = true
        vm.importFromLogin(snapshot)
        listOf("", "2026-02-30", "2025-09-01").forEach { date ->
            vm.confirmImportStartDate(date)
            assertNotNull(vm.startDateRequired.value)
            assertNull(accounts.saved)
        }
    }

    @Test fun cancelledDateConfirmationDropsPendingSessionAndDoesNotImportLater() {
        val vm = viewModel()
        sync.needsStartDate = true
        vm.importFromLogin(snapshot)
        vm.cancelPendingImport()
        vm.confirmImportStartDate("2026-09-01")
        assertNull(accounts.saved)
        assertNull(vm.startDateRequired.value)
    }

    @Test fun failedDateConfirmationClosesCalendarAndShowsFailure() {
        val vm = viewModel()
        sync.needsStartDate = true
        vm.importFromLogin(snapshot)
        sync.rejectImport = true
        vm.confirmImportStartDate("2026-09-01")
        assertNull(vm.startDateRequired.value)
        assertEquals("test rejected", vm.message.value)
        assertNull(accounts.saved)
    }

    @Test fun otherProvidersCredentialsRemainUntilSuccessfulWebImport() {
        val other = SyncCredentials(SyncProviderType.WAKEUP, SyncCredentialType.TOKEN, secret = "token")
        accounts.saved = other
        val vm = viewModel()
        assertTrue(vm.canLogin.value)
        assertSame(other, accounts.saved)
        sync.rejectImport = true
        vm.importFromLogin(snapshot)
        assertSame(other, accounts.saved)
        assertSame(other, sync.lastExpectedCredentials)
    }

    @Test fun changedCredentialsCannotBeErasedByStaleUnbind() {
        accounts.saved = saved
        val vm = viewModel()
        val other = saved.copy(username = "20260002")
        accounts.saved = other
        vm.clearAccount()
        assertSame(other, accounts.saved)
    }

    @Test fun unbindingStopsSyncAndLeavesWebLoginAvailable() {
        accounts.saved = saved
        val vm = viewModel()
        vm.clearAccount()
        assertNull(accounts.saved)
        assertNull(vm.state.value)
        assertTrue(vm.canLogin.value)
        assertFalse(vm.hasSession.value)
        vm.syncNow()
        assertNull(sync.lastSyncTarget)
    }

    @Test fun syncUsesImportedBinding() {
        val vm = viewModel()
        vm.importFromLogin(snapshot.copy(startDate = 1L))
        vm.syncNow()
        assertEquals(ZhengFangSyncTarget(1L, 8L, "imported"), sync.lastSyncTarget)
    }

    private class Accounts(private val active: () -> ActiveTimetableContext?) : CredentialsRepository, SyncSourceBindingRepository {
        var saved: SyncCredentials? = null
        var binding = "original"
        override suspend fun getCredentials(profileId: Long) = saved
        override suspend fun saveCredentials(profileId: Long, credentials: SyncCredentials) { saved = credentials }
        override suspend fun copyCredentials(sourceProfileId: Long, targetProfileId: Long) = error("unused")
        override suspend fun clearCredentials(profileId: Long) { saved = null }
        override fun observeBoundProvider(profileId: Long) = flowOf(saved?.provider)
        override suspend fun ensureIfStillActive(profileId: Long, semesterId: Long, provider: SyncProviderType) = binding
        override suspend fun saveCredentialsAndRebindIfActive(profileId: Long, credentials: SyncCredentials, expected: CredentialBindingExpectation?) = error("unused")
        override suspend fun clearCredentialsAndUnbindIfActive(profileId: Long, expected: CredentialBindingExpectation?): CredentialBindingMutationResult {
            if (expected == null || active()?.profile?.id != profileId || active()?.semester?.id != expected.semesterId || saved != expected.credentials)
                return CredentialBindingMutationResult.Rejected("stale")
            saved = null
            return CredentialBindingMutationResult.Success(null)
        }
    }

    private inner class Sync : ZhengFangSyncRepository {
        override val states = MutableStateFlow<List<ZhengFangSyncState>>(emptyList())
        var lastSyncTarget: ZhengFangSyncTarget? = null
        var lastExpectedCredentials: SyncCredentials? = null
        var lastSnapshot: ZhengFangLoginSnapshot? = null
        var needsStartDate = false
        var rejectImport = false
        override suspend fun importFromLogin(expectedContext: ActiveTimetableContext, expectedCredentials: SyncCredentials?,
            snapshot: ZhengFangLoginSnapshot, confirmingStartDate: Boolean): ZhengFangLoginImportResult {
            lastExpectedCredentials = expectedCredentials
            lastSnapshot = snapshot
            if (rejectImport) return ZhengFangLoginImportResult.Failure(SyncErrorCode.UNKNOWN, "test rejected")
            if (needsStartDate && snapshot.startDate == null) return ZhengFangLoginImportResult.NeedsStartDate(snapshot.term)
            val importedContext = context.copy(semester = semester.copy(name = snapshot.term.name))
            accounts.saved = SyncCredentials(SyncProviderType.ZF, SyncCredentialType.SESSION, snapshot.username, "",
                ZhengFangWebVpn.BASE_URL, ZhengFangSession(snapshot.cookieHeader, snapshot.term.year, snapshot.term.code, 8L, "ua", 1L))
            val target = ZhengFangSyncTarget(1L, 8L, "imported")
            states.value = listOf(ZhengFangSyncState(target))
            return ZhengFangLoginImportResult.Success(importedContext, target, 5)
        }
        override suspend fun captureSession(target: ZhengFangSyncTarget, expectedCredentials: SyncCredentials, session: ZhengFangSession) = error("unused")
        override suspend fun setAutoSyncEnabled(target: ZhengFangSyncTarget, enabled: Boolean) {
            states.value = states.value.map { if (it.target == target) it.copy(autoSyncEnabled = enabled) else it }
        }
        override suspend fun sync(target: ZhengFangSyncTarget, automatic: Boolean): TimetableSyncResult {
            lastSyncTarget = target
            return TimetableSyncResult.Success(1, "synced")
        }
        override suspend fun markAuthNotificationSent(target: ZhengFangSyncTarget) = Unit
    }

    private class Profiles(context: ActiveTimetableContext) : TimetableProfileRepository {
        val active = MutableStateFlow<ActiveTimetableContext?>(context)
        override fun observeActiveContext() = active
        override fun observeProfiles() = flowOf(listOf(active.value!!.profile))
        override fun observeSemesters(profileId: Long) = flowOf(listOfNotNull(active.value?.semester))
        override fun observeProfileSummaries(): Flow<List<TimetableProfileSummary>> = flowOf(emptyList())
        override suspend fun switch(profileId: Long): ProfileMutationResult = error("unused")
        override suspend fun setActiveSemester(profileId: Long, semesterId: Long?): ProfileMutationResult = error("unused")
        override suspend fun create(request: ProfileCreationRequest): ProfileMutationResult = error("unused")
        override suspend fun createSemester(profileId: Long, semester: NewSemesterSpec): ProfileMutationResult = error("unused")
        override suspend fun rename(profileId: Long, name: String): ProfileMutationResult = error("unused")
        override suspend fun previewDeletion(profileId: Long): ProfileDeletionImpact? = error("unused")
        override suspend fun delete(profileId: Long): ProfileMutationResult = error("unused")
    }
}
