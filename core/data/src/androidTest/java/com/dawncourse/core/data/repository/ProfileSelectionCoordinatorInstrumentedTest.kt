package com.dawncourse.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawncourse.core.data.local.AppDatabase
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
        override suspend fun getCredentials(profileId: Long): SyncCredentials? = saved[profileId]
        override suspend fun saveCredentials(profileId: Long, credentials: SyncCredentials) { saved[profileId] = credentials }
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
