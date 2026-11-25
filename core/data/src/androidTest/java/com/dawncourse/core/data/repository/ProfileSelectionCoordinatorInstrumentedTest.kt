package com.dawncourse.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.network.ZhengFangWebVpnClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.dawncourse.core.data.local.entity.CourseEntity
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.SemesterEntity
import com.dawncourse.core.data.local.entity.TimetableProfileEntity
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.model.ImportCommitRequest
import com.dawncourse.core.domain.model.ImportCommitResult
import com.dawncourse.core.domain.model.ImportDestination
import com.dawncourse.core.domain.model.ProfileCreationRequest
import com.dawncourse.core.domain.model.ProfileMutationResult
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.ZhengFangAcademicTerm
import com.dawncourse.core.domain.model.ZhengFangLoginSnapshot
import com.dawncourse.core.domain.model.ZhengFangLoginImportResult
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.CredentialBindingExpectation
import com.dawncourse.core.domain.repository.CredentialBindingMutationResult
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 关键事务结论使用真实 Room，而不是 JVM fake DAO。 */
@RunWith(AndroidJUnit4::class)
class ProfileSelectionCoordinatorInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var preferencesFile: File
    private lateinit var activeStore: ActiveProfileSelectionStore
    private lateinit var failingDataStore: WriteThenThrowDataStore
    private lateinit var coordinator: ProfileSelectionCoordinator
    private lateinit var credentials: FakeCredentialsRepository
    private lateinit var mutationGate: OperationalDataMutationGate

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferencesFile = File(context.cacheDir, "profile-selection-${UUID.randomUUID()}.preferences_pb")
        val delegate = PreferenceDataStoreFactory.create(scope = scope) { preferencesFile }
        failingDataStore = WriteThenThrowDataStore(delegate)
        activeStore = ActiveProfileSelectionStore(failingDataStore)
        credentials = FakeCredentialsRepository()
        mutationGate = OperationalDataMutationGate()
        coordinator = ProfileSelectionCoordinator(
            database = database,
            profileDao = database.timetableProfileDao(),
            semesterDao = database.semesterDao(),
            courseDao = database.courseDao(),
            bindingDao = database.syncSourceBindingDao(),
            activeSelectionStore = activeStore,
            legacySemesterSelectionStore = SemesterSelectionStore(failingDataStore),
            credentialsRepository = credentials,
            mutationGate = mutationGate,
        )
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
        preferencesFile.delete()
    }

    @Test
    fun invalidSelectionSelfHealsAndDeletionUsesStableNextFallback() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val third = insertProfile("C", 2)
        activeStore.selectProfile(999L)

        assertEquals(first, coordinator.observeActiveContext().firstValue()?.profile?.id)
        coordinator.switch(second)
        val result = coordinator.delete(second)

        assertTrue(result is ProfileMutationResult.Success)
        assertEquals(third, (result as ProfileMutationResult.Success).activeContext.profile.id)
        assertEquals(third, activeStore.activeProfileId.firstValue())
    }

    @Test
    fun calendarRefreshUpdatesInactiveEnabledProfileWithoutChangingSelectionOrEnablingOthers() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR).forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        val first = insertProfile("日历 A", 0)
        val second = insertProfile("日历 B", 1)
        val disabled = insertProfile("未开启日历", 2)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        database.courseDao().insertCourse(course(semester, 0L, false).copy(location = "新教室"))
        activeStore.selectProfile(second)
        val firstUuid = database.timetableProfileDao().getProfileById(first)!!.uuid
        val secondUuid = database.timetableProfileDao().getProfileById(second)!!.uuid
        val disabledUuid = database.timetableProfileDao().getProfileById(disabled)!!.uuid
        val store = com.dawncourse.core.data.calendar.CalendarProviderStore(context)
        val calendarId = store.ensureCalendar(firstUuid, "日历 A").id
        try {
            store.reconcile(firstUuid, emptyList(), 10, "日历 A")
            val gate = object : com.dawncourse.core.domain.repository.OperationalDataGate {
                override fun readiness() = com.dawncourse.core.domain.repository.OperationalDataReadiness.READY
                override suspend fun awaitReadiness(timeoutMillis: Long) = readiness()
            }
            lateinit var repository: SystemCalendarSyncRepositoryImpl
            val runtime = com.dawncourse.core.data.calendar.SystemCalendarSyncRuntime(context, gate, javax.inject.Provider { repository })
            val settings = object : com.dawncourse.core.domain.repository.SettingsRepository by SettingsRepositoryImpl(context) {
                override val settings = flowOf(com.dawncourse.core.domain.model.AppSettings(sectionTimes = listOf(
                    com.dawncourse.core.domain.model.SectionTime("08:00", "08:45"),
                    com.dawncourse.core.domain.model.SectionTime("08:55", "09:40"),
                )))
            }
            repository = SystemCalendarSyncRepositoryImpl(coordinator, database.courseDao(), settings, store,
                com.dawncourse.core.domain.usecase.GenerateCalendarOccurrencesUseCase(), runtime)
            val refreshed = repository.refreshEnabled()
            assertTrue(refreshed.message, refreshed.success)
            assertEquals(16, store.eventCount(firstUuid))
            database.courseDao().deleteCoursesBySemester(semester)
            val cleared = repository.refreshEnabled()
            assertTrue(cleared.message, cleared.success)
            assertEquals(0, store.eventCount(firstUuid))
            assertEquals(second, activeStore.activeProfileId.first())
            assertEquals(null, store.find(secondUuid))
            assertEquals(null, store.find(disabledUuid))
        } finally {
            val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Calendars.CONTENT_URI, calendarId)
                .buildUpon().appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, "${context.packageName}.course-calendar")
                .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun createSemesterIsAtomicAndCrossProfileActivationIsRejected() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        activeStore.selectProfile(first)
        val created = coordinator.createSemester(first, NewSemesterSpec("2026 秋", 1L, 20))
        val semesterId = (created as ProfileMutationResult.Success).activeContext.semester?.id

        assertEquals(semesterId, database.timetableProfileDao().getProfileById(first)?.activeSemesterId)
        val rejected = coordinator.setActiveSemester(second, semesterId)
        assertTrue(rejected is ProfileMutationResult.Rejected)
        assertEquals(null, database.timetableProfileDao().getProfileById(second)?.activeSemesterId)
    }

    @Test
    fun cloneRemapsOnlyOriginsThatExistInsideSourceProfile() = runBlocking {
        val source = insertProfile("来源", 0)
        val sourceSemester = insertSemester(source)
        database.timetableProfileDao().updateActiveSemesterId(source, sourceSemester)
        val base = database.courseDao().insertCourse(course(sourceSemester, originId = 0L, modified = false))
        database.courseDao().insertCourse(course(sourceSemester, originId = base, modified = true))

        val cloned = coordinator.create(ProfileCreationRequest.Clone("副本", source)) as ProfileMutationResult.Success
        val clonedCourses = database.courseDao().getCoursesByProfileOnce(cloned.activeContext.profile.id)
        val clonedBase = clonedCourses.single { !it.isModified }
        val clonedFragment = clonedCourses.single { it.isModified }
        assertEquals(clonedBase.id, clonedFragment.originId)
    }

    @Test
    fun cloneRejectsDanglingOriginWithoutCreatingPartialProfile() = runBlocking {
        val source = insertProfile("来源", 0)
        val sourceSemester = insertSemester(source)
        database.timetableProfileDao().updateActiveSemesterId(source, sourceSemester)
        database.courseDao().insertCourse(course(sourceSemester, originId = 777L, modified = true))

        val before = database.timetableProfileDao().countProfiles()
        val rejected = coordinator.create(ProfileCreationRequest.Clone("非法副本", source))
        assertTrue(rejected is ProfileMutationResult.Rejected)
        assertEquals(before, database.timetableProfileDao().countProfiles())
    }

    @Test
    fun cloneRejectsOriginFromAnotherProfileWithoutCreatingPartialProfile() = runBlocking {
        val source = insertProfile("来源", 0)
        val foreign = insertProfile("其他", 1)
        val sourceSemester = insertSemester(source)
        val foreignSemester = insertSemester(foreign)
        database.timetableProfileDao().updateActiveSemesterId(source, sourceSemester)

        val foreignCourse = database.courseDao().insertCourse(course(foreignSemester, originId = 0L, modified = false))
        database.courseDao().insertCourse(course(sourceSemester, originId = foreignCourse, modified = true))
        val before = database.timetableProfileDao().countProfiles()
        val rejected = coordinator.create(ProfileCreationRequest.Clone("非法副本", source))
        assertTrue(rejected is ProfileMutationResult.Rejected)
        assertEquals(before, database.timetableProfileDao().countProfiles())
    }

    @Test
    fun dataStoreWriteThenThrowRestoresExactSelectionBeforeRejectingSwitchAndDelete() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        activeStore.selectProfile(first)

        failingDataStore.failNextWrite = IOException("after write")
        assertTrue(coordinator.switch(second) is ProfileMutationResult.Rejected)
        assertEquals(first, activeStore.rawActiveProfileId.first())

        failingDataStore.failNextWrite = IOException("after write")
        assertTrue(coordinator.delete(first) is ProfileMutationResult.Rejected)
        assertEquals(first, activeStore.rawActiveProfileId.first())
        assertEquals(2, database.timetableProfileDao().countProfiles())
    }

    @Test
    fun dataStoreWriteThenCancelRestoresSelectionBeforePropagatingCancellation() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        activeStore.selectProfile(first)
        failingDataStore.failNextWrite = CancellationException("after write")
        var cancellationObserved = false

        try {
            coordinator.switch(second)
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(first, activeStore.rawActiveProfileId.first())
        assertEquals(0L, database.timetableProfileDao().getProfileById(second)?.lastUsedAt)
    }

    @Test
    fun capturedBindingCommitsAfterUiSwitchAndProviderRebindInvalidatesOldTask() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val firstSemester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, firstSemester)
        activeStore.selectProfile(first)
        val oldBinding = requireNotNull(
            coordinator.ensureSourceBindingIfStillActive(first, firstSemester, SyncProviderType.WAKEUP),
        )

        coordinator.switch(second)
        assertTrue(
            coordinator.replaceCoursesForCapturedBinding(
                profileId = first,
                semesterId = firstSemester,
                provider = SyncProviderType.WAKEUP,
                sourceBindingId = oldBinding,
                courses = emptyList(),
            ),
        )
        assertEquals(second, activeStore.activeProfileId.first())

        coordinator.switch(first)
        val rebind = coordinator.saveCredentialsAndRebindIfActive(
            first,
            SyncCredentials(
                provider = SyncProviderType.ZF,
                type = com.dawncourse.core.domain.model.SyncCredentialType.PASSWORD,
                username = "user",
                secret = "secret",
                endpointUrl = "https://example.test",
            ),
        )
        assertTrue(rebind is com.dawncourse.core.domain.repository.CredentialBindingMutationResult.Success)
        val newBinding = database.syncSourceBindingDao().getBySemesterOnce(firstSemester)
        assertTrue(newBinding != null && newBinding.sourceBindingId != oldBinding)
        assertTrue(
            !coordinator.replaceCoursesForCapturedBinding(
                profileId = first,
                semesterId = firstSemester,
                provider = SyncProviderType.WAKEUP,
                sourceBindingId = oldBinding,
                courses = emptyList(),
            ),
        )

        val firstZfBinding = requireNotNull(newBinding).sourceBindingId
        val sameProviderRebind = coordinator.saveCredentialsAndRebindIfActive(
            first,
            SyncCredentials(
                provider = SyncProviderType.ZF,
                type = com.dawncourse.core.domain.model.SyncCredentialType.PASSWORD,
                username = "other-user",
                secret = "other-secret",
                endpointUrl = "https://example.test",
            ),
        )
        assertTrue(sameProviderRebind is com.dawncourse.core.domain.repository.CredentialBindingMutationResult.Success)
        val secondZfBinding = requireNotNull(database.syncSourceBindingDao().getBySemesterOnce(firstSemester))
        assertTrue(secondZfBinding.sourceBindingId != firstZfBinding)
        assertTrue(
            !coordinator.replaceCoursesForCapturedBinding(
                profileId = first,
                semesterId = firstSemester,
                provider = SyncProviderType.ZF,
                sourceBindingId = firstZfBinding,
                courses = emptyList(),
            ),
        )
    }

    @Test
    fun savedSessionRotatesBindingAndKeepsPasswordWhileOldTaskCannotWrite() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        activeStore.selectProfile(first)
        val password = zfCredentials("student-a")
        val initial = coordinator.saveCredentialsAndRebindIfActive(first, password)
            as com.dawncourse.core.domain.repository.CredentialBindingMutationResult.Success
        val oldBinding = requireNotNull(initial.sourceBindingId)
        val session = password.copy(
            type = com.dawncourse.core.domain.model.SyncCredentialType.SESSION,
            zhengFangSession = com.dawncourse.core.domain.model.ZhengFangSession(
                "webvpn-token=fake; route=fake; JSESSIONID=fake", "2026", "3", semester, "test", 1L,
            ),
        )

        val result = coordinator.saveSessionForCapturedBinding(first, semester, oldBinding, password, session)
            as com.dawncourse.core.domain.repository.CredentialBindingMutationResult.Success
        val replacement = requireNotNull(result.sourceBindingId)
        assertTrue(oldBinding != replacement)
        assertEquals(session, credentials.getCredentials(first))
        assertEquals(password.secret, credentials.getCredentials(first)?.secret)
        assertFalse(coordinator.isCapturedBindingValid(first, semester, oldBinding))
        coordinator.switch(second)
        val courses = listOf(course(semester, 0L, false).toDomain())
        assertFalse(coordinator.replaceCoursesForCapturedBinding(first, semester, SyncProviderType.ZF, oldBinding, courses))
        assertTrue(coordinator.replaceCoursesForCapturedBinding(first, semester, SyncProviderType.ZF, replacement, courses))
        assertEquals(second, activeStore.activeProfileId.first())
        assertEquals(1, database.courseDao().getCoursesByProfileOnce(first).size)
        assertTrue(database.courseDao().getCoursesByProfileOnce(second).isEmpty())
    }

    @Test
    fun sessionCaptureRejectsChangedSelectionCredentialsAndOldBinding() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        activeStore.selectProfile(first)
        val original = zfCredentials("student-a")
        val initial = coordinator.saveCredentialsAndRebindIfActive(first, original)
            as com.dawncourse.core.domain.repository.CredentialBindingMutationResult.Success
        val binding = requireNotNull(initial.sourceBindingId)
        val rejected = com.dawncourse.core.domain.repository.CredentialBindingMutationResult.Rejected::class.java
        coordinator.switch(second)
        assertTrue(rejected.isInstance(coordinator.saveSessionForCapturedBinding(first, semester, binding, original, original)))
        coordinator.switch(first)
        val other = zfCredentials("student-b")
        credentials.saveCredentials(first, other)
        assertTrue(rejected.isInstance(coordinator.saveSessionForCapturedBinding(first, semester, binding, original, original)))
        assertEquals(other, credentials.getCredentials(first))
        coordinator.saveCredentialsAndRebindIfActive(first, other)
        assertTrue(rejected.isInstance(coordinator.saveSessionForCapturedBinding(first, semester, binding, other, original)))
        assertEquals(other, credentials.getCredentials(first))
    }

    private fun zfCredentials(username: String) = SyncCredentials(
        SyncProviderType.ZF, com.dawncourse.core.domain.model.SyncCredentialType.PASSWORD,
        username, "fake-password", com.dawncourse.core.domain.model.ZhengFangWebVpn.BASE_URL,
    )

    @Test
    fun accountSaveWaitingForSelectionLockRejectsSemesterChangedBeforeCommit() = runBlocking {
        val first = insertProfile("A", 0)
        val firstSemester = insertSemester(first)
        val secondSemester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, firstSemester)
        activeStore.selectProfile(first)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val selecting = async(Dispatchers.Default) {
            coordinator.executeActiveAction(first, firstSemester) {
                entered.complete(Unit)
                release.await()
                database.timetableProfileDao().updateActiveSemesterId(first, secondSemester)
            }
        }
        entered.await()
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.saveCredentialsAndRebindIfActive(
                first, zfCredentials("student-a"), CredentialBindingExpectation(firstSemester, null),
            )
        }
        assertFalse(saving.isCompleted)
        release.complete(Unit)
        selecting.await()
        assertTrue(saving.await() is CredentialBindingMutationResult.Rejected)
        assertEquals(null, credentials.getCredentials(first))
        assertEquals(null, database.syncSourceBindingDao().getBySemesterOnce(firstSemester))
        assertEquals(null, database.syncSourceBindingDao().getBySemesterOnce(secondSemester))
    }

    @Test
    fun accountSaveAndUnbindRejectStaleCredentialsInsideSelectionLock() = runBlocking {
        val first = insertProfile("A", 0)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        activeStore.selectProfile(first)
        val original = zfCredentials("student-a")
        val initial = coordinator.saveCredentialsAndRebindIfActive(
            first, original, CredentialBindingExpectation(semester, null),
        ) as CredentialBindingMutationResult.Success
        val other = zfCredentials("student-b")
        val stale = CredentialBindingExpectation(semester, null)
        assertTrue(coordinator.saveCredentialsAndRebindIfActive(first, other, stale) is CredentialBindingMutationResult.Rejected)
        assertTrue(coordinator.clearCredentialsAndUnbindIfActive(first, stale) is CredentialBindingMutationResult.Rejected)
        assertEquals(original, credentials.getCredentials(first))
        assertEquals(initial.sourceBindingId, database.syncSourceBindingDao().getBySemesterOnce(semester)?.sourceBindingId)
        assertTrue(coordinator.clearCredentialsAndUnbindIfActive(
            first, CredentialBindingExpectation(semester, original),
        ) is CredentialBindingMutationResult.Success)
        assertEquals(null, credentials.getCredentials(first))
    }

    @Test
    fun activeActionGateRejectsOldProfileAfterSwitch() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        activeStore.selectProfile(first)
        var calls = 0

        coordinator.executeActiveAction(first, semester) { calls++ }
        coordinator.switch(second)
        val rejected = coordinator.executeActiveAction(first, semester) { calls++ }

        assertEquals(1, calls)
        assertEquals(null, rejected)
    }

    @Test
    fun activeActionGateHoldsSelectionLockUntilSystemActionFinishes() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        activeStore.selectProfile(first)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val action = async(Dispatchers.Default) {
            coordinator.executeActiveAction(first, semester) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val switch = async(Dispatchers.Default) { coordinator.switch(second) }

        delay(50)
        assertFalse(switch.isCompleted)
        release.complete(Unit)
        action.await()
        assertTrue(switch.await() is ProfileMutationResult.Success)
        assertEquals(second, activeStore.activeProfileId.first())
    }

    @Test
    fun automaticImportCommitsCapturedProfileWithoutChangingCurrentSelection() = runBlocking {
        val first = insertProfile("A", 0)
        val second = insertProfile("B", 1)
        val semester = insertSemester(first)
        database.timetableProfileDao().updateActiveSemesterId(first, semester)
        activeStore.selectProfile(first)
        val bindingId = requireNotNull(
            coordinator.ensureSourceBindingIfStillActive(first, semester, SyncProviderType.WAKEUP),
        )
        coordinator.switch(second)
        val repository = importCommitRepository()

        val result = repository.commit(
            ImportCommitRequest(
                destination = ImportDestination.OverwriteSemester(first, semester),
                semester = NewSemesterSpec("学期", 1L, 20),
                courses = listOf(course(semester, originId = 0L, modified = false).toDomain()),
                expectedSourceBindingId = bindingId,
            ),
        )

        assertTrue(result is ImportCommitResult.Success)
        assertEquals(second, activeStore.activeProfileId.first())
        assertEquals(1, database.courseDao().getCoursesBySemesterOnce(semester).size)
    }

    @Test
    fun importSelectionWriteThenThrowRestoresSelectionAndRoomPreimage() = runBlocking {
        val original = insertProfile("A", 0)
        activeStore.selectProfile(original)
        val before = database.timetableProfileDao().countProfiles()
        failingDataStore.failNextWrite = IOException("after write")

        val result = importCommitRepository().commit(
            ImportCommitRequest(
                destination = ImportDestination.NewProfile,
                newProfileName = "临时课表",
                semester = NewSemesterSpec("学期", 1L, 20),
                courses = emptyList(),
            ),
        )

        assertTrue(result is ImportCommitResult.Rejected)
        assertEquals(original, activeStore.rawActiveProfileId.first())
        assertEquals(before, database.timetableProfileDao().countProfiles())
    }

    @Test
    fun firstJyuLoginCreatesSemesterCoursesAndSessionWithoutPrecreatedSemester() = runBlocking {
        val profileId = insertProfile("首次登录", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        val result = coordinator.importZhengFangLogin(context, null, jyuSnapshot(), listOf(course(0L, 0L, false).toDomain()))
        assertTrue(result is ZhengFangLoginImportResult.Success)
        result as ZhengFangLoginImportResult.Success
        assertEquals("2026-2027学年第一学期", result.context.semester?.name)
        assertEquals(result.target.semesterId, credentials.getCredentials(profileId)?.zhengFangSession?.semesterId)
        assertEquals(1, database.courseDao().getCoursesBySemesterOnce(result.target.semesterId).size)
        assertEquals(result.target.semesterId, coordinator.observeActiveContext().first()?.semester?.id)
        assertEquals("", credentials.getCredentials(profileId)?.secret)
        assertEquals(SyncProviderType.ZF, credentials.getCredentials(profileId)?.provider)
    }

    @Test
    fun dateConfirmationUsesOneHttpFetchAndRechecksCredentialsBeforeCommit() = runBlocking {
        val profileId = insertProfile("网页直接登录", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"kbList":[{"kcmc":"测试课程","xqj":"1","jcs":"1-2节","zcd":"1-16周"}]}""".toResponseBody()).build()
        }.build()
        val repository = ZhengFangSyncRepositoryImpl(ApplicationProvider.getApplicationContext(), credentials,
            coordinator, ZhengFangWebVpnClient(http))
        val snapshot = jyuSnapshot().copy(startDate = null)
        assertTrue(repository.importFromLogin(context, null, snapshot) is ZhengFangLoginImportResult.NeedsStartDate)
        assertEquals(1, requests)
        assertEquals(0, database.semesterDao().countByProfile(profileId))
        val changed = jyuAccount().copy(username = "new-account")
        credentials.saveCredentials(profileId, changed)
        assertTrue(repository.importFromLogin(context, null, snapshot.copy(startDate = 1788105600000L), confirmingStartDate = true)
            is ZhengFangLoginImportResult.Failure)
        assertEquals(1, requests)
        assertEquals(changed, credentials.getCredentials(profileId))
        assertEquals(0, database.semesterDao().countByProfile(profileId))
        credentials.clearCredentials(profileId)
        assertTrue(repository.importFromLogin(context, null, snapshot) is ZhengFangLoginImportResult.NeedsStartDate)
        val imported = repository.importFromLogin(context, null, snapshot.copy(startDate = 1788105600000L),
            confirmingStartDate = true)
        assertTrue(imported is ZhengFangLoginImportResult.Success)
        assertEquals(2, requests)
        assertEquals("", credentials.getCredentials(profileId)?.secret)
        val target = (imported as ZhengFangLoginImportResult.Success).target
        repository.setAutoSyncEnabled(target, true)
        assertTrue(repository.sync(target, automatic = true) is com.dawncourse.core.domain.model.TimetableSyncResult.Success)
        assertEquals(3, requests)
    }

    @Test
    fun directWebLoginCanReplaceOtherProviderOnlyAfterSuccessfulImport() = runBlocking {
        val profileId = insertProfile("已有其他来源", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        val other = SyncCredentials(SyncProviderType.WAKEUP, SyncCredentialType.TOKEN, secret = "test-token")
        credentials.saveCredentials(profileId, other)
        val courses = listOf(course(0L, 0L, false).toDomain())
        assertTrue(coordinator.importZhengFangLogin(context, other, jyuSnapshot().copy(startDate = null), courses)
            is ZhengFangLoginImportResult.NeedsStartDate)
        assertEquals(other, credentials.getCredentials(profileId))
        credentials.failNextSaveAfterWrite = true
        assertTrue(coordinator.importZhengFangLogin(context, other, jyuSnapshot(), courses)
            is ZhengFangLoginImportResult.Failure)
        assertEquals(other, credentials.getCredentials(profileId))
        assertTrue(coordinator.importZhengFangLogin(context, other, jyuSnapshot(), courses)
            is ZhengFangLoginImportResult.Success)
        assertEquals(SyncProviderType.ZF, credentials.getCredentials(profileId)?.provider)
        assertEquals("", credentials.getCredentials(profileId)?.secret)
    }

    @Test
    fun failedFirstWebImportRestoresNoCredentialsAndNoSemester() = runBlocking {
        val profileId = insertProfile("尚未绑定", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        credentials.failNextSaveAfterWrite = true
        val result = coordinator.importZhengFangLogin(context, null, jyuSnapshot(), listOf(course(0L, 0L, false).toDomain()))
        assertTrue(result is ZhengFangLoginImportResult.Failure)
        assertEquals(null, credentials.getCredentials(profileId))
        assertEquals(0, database.semesterDao().countByProfile(profileId))
        assertEquals(null, coordinator.observeActiveContext().first()?.semester)
    }

    @Test
    fun missingFirstMondayDoesNotCreateSemesterOrPersistSession() = runBlocking {
        val profileId = insertProfile("首次登录", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        val account = jyuAccount()
        credentials.saveCredentials(profileId, account)
        val result = coordinator.importZhengFangLogin(context, account, jyuSnapshot().copy(startDate = null),
            listOf(course(0L, 0L, false).toDomain()))
        assertTrue(result is ZhengFangLoginImportResult.NeedsStartDate)
        assertEquals(0, database.semesterDao().countByProfile(profileId))
        assertEquals(account, credentials.getCredentials(profileId))
    }

    @Test
    fun repeatedLoginReusesRemoteSemesterAndCrossTermKeepsHistoricalCourses() = runBlocking {
        val profileId = insertProfile("历史课表", 0)
        activeStore.selectProfile(profileId)
        credentials.saveCredentials(profileId, jyuAccount())
        suspend fun importCurrent(snapshot: ZhengFangLoginSnapshot): ZhengFangLoginImportResult.Success {
            return coordinator.importZhengFangLogin(requireNotNull(coordinator.observeActiveContext().first()),
                requireNotNull(credentials.getCredentials(profileId)), snapshot,
                listOf(course(0L, 0L, false).toDomain())) as ZhengFangLoginImportResult.Success
        }
        val first = importCurrent(jyuSnapshot())
        val repeated = importCurrent(jyuSnapshot().copy(startDate = null))
        assertEquals(first.target.semesterId, repeated.target.semesterId)
        assertTrue(first.target.sourceBindingId != repeated.target.sourceBindingId)
        val next = importCurrent(jyuSnapshot().copy(term = ZhengFangAcademicTerm("2026", "12")))
        assertTrue(first.target.semesterId != next.target.semesterId)
        assertEquals(2, database.semesterDao().countByProfile(profileId))
        assertEquals(1, database.courseDao().getCoursesBySemesterOnce(first.target.semesterId).size)
        assertEquals(1, database.courseDao().getCoursesBySemesterOnce(next.target.semesterId).size)
    }

    @Test
    fun staleFirstLoginCannotImportIntoChangedSelectionOrCredentials() = runBlocking {
        val profileId = insertProfile("A", 0)
        val otherId = insertProfile("B", 1)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        credentials.saveCredentials(profileId, jyuAccount())
        coordinator.switch(otherId)
        val courses = listOf(course(0L, 0L, false).toDomain())
        assertTrue(coordinator.importZhengFangLogin(context, jyuAccount(), jyuSnapshot(), courses)
            is ZhengFangLoginImportResult.Failure)
        coordinator.switch(profileId)
        credentials.saveCredentials(profileId, jyuAccount().copy(username = "other"))
        assertTrue(coordinator.importZhengFangLogin(context, jyuAccount(), jyuSnapshot(), courses)
            is ZhengFangLoginImportResult.Failure)
        assertEquals(0, database.semesterDao().countByProfile(profileId))
    }

    @Test
    fun credentialWriteFailureRollsBackNewSemesterAndRestoresPreviousAccount() = runBlocking {
        val profileId = insertProfile("首次登录", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        val account = jyuAccount()
        credentials.saveCredentials(profileId, account)
        credentials.failNextSaveAfterWrite = true
        val result = coordinator.importZhengFangLogin(context, account, jyuSnapshot(), listOf(course(0L, 0L, false).toDomain()))
        assertTrue(result is ZhengFangLoginImportResult.Failure)
        assertEquals(0, database.semesterDao().countByProfile(profileId))
        assertEquals(account, credentials.getCredentials(profileId))
        assertEquals(null, coordinator.observeActiveContext().first()?.semester)
    }

    @Test
    fun cancellationDuringImportKeepsCommittedSemesterBindingAndCredentialsConsistent() = runBlocking {
        val profileId = insertProfile("首次登录", 0)
        activeStore.selectProfile(profileId)
        val context = requireNotNull(coordinator.observeActiveContext().first())
        val account = jyuAccount()
        credentials.saveCredentials(profileId, account)
        val credentialWritten = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        credentials.afterNextSave = {
            credentialWritten.complete(Unit)
            finishWrite.await()
        }
        val importing = async(Dispatchers.Default) {
            coordinator.importZhengFangLogin(context, account, jyuSnapshot(), listOf(course(0L, 0L, false).toDomain()))
        }
        credentialWritten.await()
        importing.cancel()
        finishWrite.complete(Unit)
        importing.join()
        val semester = requireNotNull(coordinator.observeActiveContext().first()?.semester)
        assertEquals(1, database.semesterDao().countByProfile(profileId))
        assertEquals(semester.id, credentials.getCredentials(profileId)?.zhengFangSession?.semesterId)
        assertTrue(database.syncSourceBindingDao().getBySemesterOnce(semester.id) != null)
        assertEquals(1, database.courseDao().getCoursesBySemesterOnce(semester.id).size)
    }

    private fun jyuAccount() = SyncCredentials(SyncProviderType.ZF, SyncCredentialType.PASSWORD,
        "test-student", "test-password", ZhengFangWebVpn.BASE_URL)

    private fun jyuSnapshot() = ZhengFangLoginSnapshot(ZhengFangAcademicTerm("2026", "3"),
        "webvpn-token=test; route=test; JSESSIONID=test", "test-agent", 1788710400000L)

    private suspend fun insertProfile(name: String, order: Int): Long = database.timetableProfileDao().insert(
        TimetableProfileEntity(uuid = UUID.randomUUID().toString(), name = name, sortOrder = order),
    )

    private suspend fun insertSemester(profileId: Long): Long = database.semesterDao().insertSemester(
        SemesterEntity(profileId = profileId, name = "学期", startDate = 1L, weekCount = 20),
    )

    private fun course(semesterId: Long, originId: Long, modified: Boolean) = CourseEntity(
        semesterId = semesterId, name = "课程", teacher = "", location = "", dayOfWeek = 1,
        startSection = 1, duration = 2, startWeek = 1, endWeek = 16, weekType = 0,
        color = "#000000", isModified = modified, originId = originId,
    )

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    private fun importCommitRepository() = ImportCommitRepositoryImpl(
        database = database,
        profileDao = database.timetableProfileDao(),
        semesterDao = database.semesterDao(),
        courseDao = database.courseDao(),
        bindingDao = database.syncSourceBindingDao(),
        activeSelectionStore = activeStore,
        profileSelectionCoordinator = coordinator,
        mutationGate = mutationGate,
    )

    private class FakeCredentialsRepository : CredentialsRepository {
        private val saved = mutableMapOf<Long, SyncCredentials>()
        var failNextSaveAfterWrite = false
        var afterNextSave: (suspend () -> Unit)? = null
        override suspend fun getCredentials(profileId: Long): SyncCredentials? = saved[profileId]
        override suspend fun saveCredentials(profileId: Long, credentials: SyncCredentials) {
            saved[profileId] = credentials
            val afterSave = afterNextSave
            afterNextSave = null
            afterSave?.invoke()
            if (failNextSaveAfterWrite) { failNextSaveAfterWrite = false; throw IOException("test write failure") }
        }
        override suspend fun copyCredentials(sourceProfileId: Long, targetProfileId: Long) {
            saved[sourceProfileId]?.let { saved[targetProfileId] = it }
        }
        override suspend fun clearCredentials(profileId: Long) { saved.remove(profileId) }
        override fun observeBoundProvider(profileId: Long): Flow<SyncProviderType?> = flowOf(null)
    }

    /** 模拟 DataStore 已提交 updateData 后才向调用方抛错。 */
    private class WriteThenThrowDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        var failNextWrite: Throwable? = null

        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = delegate.updateData(transform)
            failNextWrite?.let { failure ->
                failNextWrite = null
                throw failure
            }
            return updated
        }
    }
}
