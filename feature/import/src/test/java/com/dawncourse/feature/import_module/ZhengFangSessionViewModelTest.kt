package com.dawncourse.feature.import_module

import androidx.lifecycle.ViewModelStore
import com.dawncourse.core.domain.model.*
import com.dawncourse.core.domain.repository.*
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

/** 从首次打开同步页开始验证账号、登录和同步，仓库边界使用内存存储。 */
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

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel(): ZhengFangSessionViewModel =
        ZhengFangSessionViewModel(profiles, accounts, accounts, sync).also { store.put("sync", it) }

    @Test fun firstTimeUserCanSaveAndOpenLoginWithoutVisitingSettings() {
        val vm = viewModel()
        assertTrue(vm.initialized.value)
        assertFalse(vm.canLogin.value)
        vm.saveAccount("20260001", "school-password")
        assertEquals("20260001", vm.loginAccount.value)
        assertTrue(vm.canLogin.value)
        assertTrue(vm.loginRequested.value)
        assertEquals(ZhengFangWebVpn.BASE_URL, accounts.saved?.endpointUrl)
        vm.consumeLoginRequest()
        assertFalse(vm.loginRequested.value)
    }

    @Test fun existingUserKeepsSessionAutoSyncAndBindingWhenOpeningLogin() {
        accounts.saved = saved
        val state = ZhengFangSyncState(ZhengFangSyncTarget(1L, 8L, "original"), autoSyncEnabled = true)
        sync.states.value = listOf(state)
        val vm = viewModel()
        assertEquals(state, vm.state.value)
        vm.saveAccount("20260001", "")
        assertSame(saved, accounts.saved)
        assertEquals("original", accounts.binding)
        assertEquals(state, vm.state.value)
        assertTrue(vm.loginRequested.value)
    }

    @Test fun newAccountInvalidatesOldSessionBeforeNewLogin() {
        accounts.saved = saved
        sync.states.value = listOf(ZhengFangSyncState(ZhengFangSyncTarget(1L, 8L, "original"), autoSyncEnabled = true))
        val vm = viewModel()
        vm.saveAccount("20260002", "new-password")
        assertEquals("20260002", accounts.saved?.username)
        assertNull(accounts.saved?.zhengFangSession)
        assertNull(vm.state.value)
        assertTrue(vm.loginRequested.value)
    }

    @Test fun rejectedAccountSaveDoesNotOpenLoginOrReplaceCredentials() {
        val vm = viewModel()
        accounts.rejectSave = true
        vm.saveAccount("20260001", "school-password")
        assertNull(accounts.saved)
        assertFalse(vm.canLogin.value)
        assertFalse(vm.loginRequested.value)
    }

    @Test fun changedSelectionOrCredentialsRejectsStaleForm() {
        val vm = viewModel()
        profiles.active.value = ActiveTimetableContext(profile.copy(activeSemesterId = 9L), semester.copy(id = 9L))
        vm.saveAccount("20260001", "school-password")
        assertNull(accounts.saved)
        assertFalse(vm.loginRequested.value)
        profiles.active.value = context
        accounts.saved = saved
        vm.saveAccount("20260002", "other-password")
        assertSame(saved, accounts.saved)
        assertFalse(vm.loginRequested.value)
    }

    @Test fun missingLocalSemesterCannotCreateAccountBinding() {
        profiles.active.value = ActiveTimetableContext(profile.copy(activeSemesterId = null))
        val vm = viewModel()
        vm.saveAccount("20260001", "school-password")
        assertNull(accounts.saved)
        assertFalse(vm.canLogin.value)
    }

    @Test fun selectionChangeWhileSaveIsPendingCannotRebindAnotherSemester() {
        val vm = viewModel()
        accounts.beforeMutation = {
            profiles.active.value = ActiveTimetableContext(profile.copy(activeSemesterId = 9L), semester.copy(id = 9L))
        }
        vm.saveAccount("20260001", "school-password")
        assertNull(accounts.saved)
        assertFalse(vm.loginRequested.value)
        assertEquals("original", accounts.binding)
    }

    @Test fun credentialsChangeWhileSaveIsPendingCannotBeOverwrittenByOldForm() {
        val vm = viewModel()
        accounts.beforeMutation = { accounts.saved = saved }
        vm.saveAccount("20260002", "new-password")
        assertSame(saved, accounts.saved)
        assertFalse(vm.loginRequested.value)
    }

    @Test fun credentialsChangeWhileUnbindIsPendingCannotEraseAnotherAccount() {
        accounts.saved = saved
        val vm = viewModel()
        val other = saved.copy(username = "20260002")
        accounts.beforeMutation = { accounts.saved = other }
        vm.clearAccount()
        assertSame(other, accounts.saved)
    }

    @Test fun unbindingRemovesSavedCredentialsAndStopsPageSync() {
        accounts.saved = saved
        val vm = viewModel()
        vm.clearAccount()
        assertNull(accounts.saved)
        assertNull(vm.state.value)
        assertFalse(vm.canLogin.value)
        assertEquals("", vm.loginAccount.value)
        vm.syncNow()
        assertNull(sync.lastSyncTarget)
    }

    @Test fun syncAfterSetupUsesCapturedProfileSemesterAndNewBinding() {
        val vm = viewModel()
        vm.saveAccount("20260001", "school-password")
        vm.saveSession("webvpn-token=a; route=b; JSESSIONID=c", "ua", "2026", "3")
        vm.syncNow()
        assertEquals(ZhengFangSyncTarget(1L, 8L, "captured"), sync.lastSyncTarget)
        assertEquals("school-password", accounts.saved?.secret)
    }

    private class Accounts(private val activeContext: () -> ActiveTimetableContext?) : CredentialsRepository, SyncSourceBindingRepository {
        var saved: SyncCredentials? = null
        var binding = "original"
        var rejectSave = false
        var beforeMutation: () -> Unit = {}
        private fun matches(profileId: Long, expected: CredentialBindingExpectation?): Boolean =
            expected != null && activeContext()?.profile?.id == profileId &&
                activeContext()?.semester?.id == expected.semesterId && expected.credentials == saved
        override suspend fun getCredentials(profileId: Long) = saved
        override suspend fun saveCredentials(profileId: Long, credentials: SyncCredentials) { saved = credentials }
        override suspend fun copyCredentials(sourceProfileId: Long, targetProfileId: Long) = error("unused")
        override suspend fun clearCredentials(profileId: Long) { saved = null }
        override fun observeBoundProvider(profileId: Long) = flowOf(saved?.provider)
        override suspend fun ensureIfStillActive(profileId: Long, semesterId: Long, provider: SyncProviderType) = binding
        override suspend fun saveCredentialsAndRebindIfActive(profileId: Long, credentials: SyncCredentials, expected: CredentialBindingExpectation?): CredentialBindingMutationResult {
            beforeMutation()
            if (rejectSave || !matches(profileId, expected)) return CredentialBindingMutationResult.Rejected("test rejected")
            saved = credentials
            binding = "replacement"
            return CredentialBindingMutationResult.Success(binding)
        }
        override suspend fun clearCredentialsAndUnbindIfActive(profileId: Long, expected: CredentialBindingExpectation?): CredentialBindingMutationResult {
            beforeMutation()
            if (!matches(profileId, expected)) return CredentialBindingMutationResult.Rejected("test rejected")
            saved = null
            binding = "unbound"
            return CredentialBindingMutationResult.Success(null)
        }
    }

    private inner class Sync : ZhengFangSyncRepository {
        override val states = MutableStateFlow<List<ZhengFangSyncState>>(emptyList())
        var lastSyncTarget: ZhengFangSyncTarget? = null
        override suspend fun captureSession(target: ZhengFangSyncTarget, expectedCredentials: SyncCredentials, session: ZhengFangSession): TimetableSyncResult {
            accounts.saved = expectedCredentials.copy(type = SyncCredentialType.SESSION, zhengFangSession = session)
            accounts.binding = "captured"
            states.value = listOf(ZhengFangSyncState(target.copy(sourceBindingId = "captured")))
            return TimetableSyncResult.Success(1, "saved")
        }
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
