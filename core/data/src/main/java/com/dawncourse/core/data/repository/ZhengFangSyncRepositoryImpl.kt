package com.dawncourse.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dawncourse.core.data.network.ZhengFangSyncException
import com.dawncourse.core.data.network.ZhengFangWebVpnClient
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.model.ZhengFangSession
import com.dawncourse.core.domain.model.ZhengFangSyncState
import com.dawncourse.core.domain.model.ZhengFangSyncTarget
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.ZhengFangLoginSnapshot
import com.dawncourse.core.domain.model.ZhengFangLoginImportResult
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.CredentialBindingMutationResult
import com.dawncourse.core.domain.repository.ZhengFangSyncRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.zhengFangSyncDataStore by preferencesDataStore(name = "zhengfang_sync_state")

/** 任务与前台同步共用互斥锁；每次写入仍由 Profile 选择锁复核不可变 binding。 */
@Singleton
class ZhengFangSyncRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialsRepository: CredentialsRepository,
    private val coordinator: ProfileSelectionCoordinator,
    private val client: ZhengFangWebVpnClient,
) : ZhengFangSyncRepository {
    private val store = context.zhengFangSyncDataStore
    private val gson = Gson()
    private val mutex = Mutex()
    private val key = stringPreferencesKey("states")
    private val stateType = object : TypeToken<List<ZhengFangSyncState>>() {}.type

    override val states: Flow<List<ZhengFangSyncState>> = store.data.map { prefs ->
        prefs[key]?.let { gson.fromJson<List<ZhengFangSyncState>>(it, stateType) } ?: emptyList()
    }

    override suspend fun importFromLogin(
        expectedContext: ActiveTimetableContext,
        expectedCredentials: SyncCredentials,
        snapshot: ZhengFangLoginSnapshot,
    ): ZhengFangLoginImportResult = mutex.withLock {
        try {
            if (expectedCredentials.provider != SyncProviderType.ZF ||
                !ZhengFangWebVpn.isSupportedEntry(expectedCredentials.endpointUrl)) {
                return@withLock ZhengFangLoginImportResult.Failure(SyncErrorCode.NO_CREDENTIALS, "请先保存教务账号")
            }
            val session = ZhengFangSession(snapshot.cookieHeader, snapshot.term.year, snapshot.term.code,
                expectedContext.semester?.id ?: 0L, snapshot.userAgent, System.currentTimeMillis())
            val courses = client.fetch(session)
            currentCoroutineContext().ensureActive()
            val previousStates = states.first()
            val result = coordinator.importZhengFangLogin(expectedContext, expectedCredentials, snapshot, courses)
            if (result is ZhengFangLoginImportResult.Success) {
                val previous = previousStates.firstOrNull {
                    it.target.profileId == result.target.profileId && it.target.semesterId == result.target.semesterId
                }
                val now = System.currentTimeMillis()
                // 课程已经提交，状态存储故障不能被误报成“未导入”并诱使用户重复创建学期。
                try {
                    saveState(ZhengFangSyncState(result.target, autoSyncEnabled = previous?.autoSyncEnabled == true,
                        lastAttemptAt = now, lastSuccessAt = now, message = "已导入 ${result.courseCount} 条课程安排"),
                        disableOtherSemesters = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* 页面会明确提示状态未保存，课程与加密会话仍保留。 */ }
            }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ZhengFangSyncException) {
            ZhengFangLoginImportResult.Failure(failure.code, failure.message.orEmpty())
        } catch (_: IOException) {
            ZhengFangLoginImportResult.Failure(SyncErrorCode.NETWORK_ERROR, "网络或 WebVPN 暂时不可用，已保留原课表")
        } catch (_: Exception) {
            ZhengFangLoginImportResult.Failure(SyncErrorCode.UNKNOWN, "读取课表未完成，已保留原课表，请重试")
        }
    }

    override suspend fun captureSession(
        target: ZhengFangSyncTarget,
        expectedCredentials: SyncCredentials,
        session: ZhengFangSession,
    ): TimetableSyncResult = mutex.withLock {
        guarded {
            if (expectedCredentials.provider != SyncProviderType.ZF ||
                !ZhengFangWebVpn.isSupportedEntry(expectedCredentials.endpointUrl) ||
                target.semesterId != session.semesterId
            ) return@guarded rejected()
            // P0 验证就是实际的纯 HTTP 请求，未通过验证绝不替换原凭据。
            val courses = client.fetch(session)
            currentCoroutineContext().ensureActive()
            val credentials = expectedCredentials.copy(type = SyncCredentialType.SESSION, zhengFangSession = session)
            val mutation = coordinator.saveSessionForCapturedBinding(
                target.profileId, target.semesterId, target.sourceBindingId, expectedCredentials, credentials,
            )
            val newBinding = when (mutation) {
                is CredentialBindingMutationResult.Success -> mutation.sourceBindingId ?: return@guarded rejected()
                is CredentialBindingMutationResult.Rejected -> return@guarded rejected()
                is CredentialBindingMutationResult.Inconsistent -> return@guarded TimetableSyncResult.Failure(
                    SyncErrorCode.RECOVERY_REQUIRED, "凭据恢复未完成，请停止同步并重新绑定账号",
                )
            }
            val previous = states.first().firstOrNull { it.target == target }
            val replacement = target.copy(sourceBindingId = newBinding)
            saveState(ZhengFangSyncState(
                target = replacement, autoSyncEnabled = previous?.autoSyncEnabled ?: false,
                message = "会话已验证：${courses.size} 条课程安排；立即同步后写入课表",
            ))
            TimetableSyncResult.Success(courses.size, "会话已加密保存，可立即同步")
        }
    }

    override suspend fun setAutoSyncEnabled(target: ZhengFangSyncTarget, enabled: Boolean) = mutex.withLock {
        val state = states.first().firstOrNull { it.target == target } ?: return@withLock
        if (!valid(target)) return@withLock
        saveState(state.copy(autoSyncEnabled = enabled))
    }

    override suspend fun sync(target: ZhengFangSyncTarget, automatic: Boolean): TimetableSyncResult = mutex.withLock {
        val state = states.first().firstOrNull { it.target == target } ?: return@withLock rejected()
        if (automatic && !state.autoSyncEnabled) return@withLock rejected()
        val result = guarded {
            if (!valid(target)) {
                saveState(state.copy(autoSyncEnabled = false))
                return@guarded rejected()
            }
            val credentials = credentialsRepository.getCredentials(target.profileId)
            val session = credentials?.zhengFangSession
            if (credentials?.provider != SyncProviderType.ZF || credentials.type != SyncCredentialType.SESSION ||
                session == null || session.semesterId != target.semesterId ||
                !ZhengFangWebVpn.isSupportedEntry(credentials.endpointUrl)
            ) return@guarded TimetableSyncResult.Failure(SyncErrorCode.NO_CREDENTIALS, "请登录教务系统并保存会话")
            val courses = client.fetch(session).map { it.copy(semesterId = target.semesterId) }
            currentCoroutineContext().ensureActive()
            val committed = coordinator.replaceCoursesForCapturedBinding(
                target.profileId, target.semesterId, SyncProviderType.ZF, target.sourceBindingId, courses,
            )
            if (!committed) return@guarded rejected()
            TimetableSyncResult.Success(courses.size, "已更新 ${courses.size} 条课程安排")
        }
        val now = System.currentTimeMillis()
        // 已被清除或重绑的旧任务不覆盖新 binding 的状态。
        if (valid(target)) {
            saveState(state.copy(
                lastAttemptAt = now,
                lastSuccessAt = if (result is TimetableSyncResult.Success) now else state.lastSuccessAt,
                message = when (result) {
                    is TimetableSyncResult.Success -> result.message
                    is TimetableSyncResult.Failure -> result.message
                },
                errorCode = (result as? TimetableSyncResult.Failure)?.code,
                authNotificationSent = if (result is TimetableSyncResult.Success) false else state.authNotificationSent,
            ))
        }
        result
    }

    override suspend fun markAuthNotificationSent(target: ZhengFangSyncTarget) = mutex.withLock {
        val state = states.first().firstOrNull { it.target == target } ?: return@withLock
        if (state.errorCode == SyncErrorCode.AUTH_FAILED) saveState(state.copy(authNotificationSent = true))
    }

    private suspend fun valid(target: ZhengFangSyncTarget): Boolean = coordinator.isCapturedBindingValid(
        target.profileId, target.semesterId, target.sourceBindingId,
    )

    private suspend fun saveState(state: ZhengFangSyncState, disableOtherSemesters: Boolean = false) {
        store.edit { prefs ->
            val current = prefs[key]?.let { gson.fromJson<List<ZhengFangSyncState>>(it, stateType) }.orEmpty()
            val retained = current.filterNot {
                it.target.profileId == state.target.profileId && it.target.semesterId == state.target.semesterId
            }.map { if (disableOtherSemesters && it.target.profileId == state.target.profileId)
                it.copy(autoSyncEnabled = false) else it }
            prefs[key] = gson.toJson(retained + state)
        }
    }

    private suspend fun guarded(block: suspend () -> TimetableSyncResult): TimetableSyncResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ZhengFangSyncException) {
        TimetableSyncResult.Failure(failure.code, failure.message.orEmpty())
    } catch (_: IOException) {
        TimetableSyncResult.Failure(SyncErrorCode.NETWORK_ERROR, "网络或 WebVPN 暂时不可用，稍后自动重试")
    } catch (_: Exception) {
        TimetableSyncResult.Failure(SyncErrorCode.UNKNOWN, "同步未完成，已保留原课表，请稍后重试")
    }

    private fun rejected() = TimetableSyncResult.Failure(
        SyncErrorCode.NO_CREDENTIALS, "同步目标或来源已变化，请重新打开同步页面",
    )
}
