package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.model.ZhengFangSession
import com.dawncourse.core.domain.model.ZhengFangSyncState
import com.dawncourse.core.domain.model.ZhengFangSyncTarget
import kotlinx.coroutines.flow.Flow

/** 嘉应学院 WebVPN 会话同步；Feature 只负责 WebView，HTTP 和落库均留在 data 层。 */
interface ZhengFangSyncRepository {
    val states: Flow<List<ZhengFangSyncState>>

    /** 请求验证通过后，检查捕获时的账号与 binding，再加密保存会话并轮换 binding。 */
    suspend fun captureSession(
        target: ZhengFangSyncTarget,
        expectedCredentials: SyncCredentials,
        session: ZhengFangSession,
    ): TimetableSyncResult

    suspend fun setAutoSyncEnabled(target: ZhengFangSyncTarget, enabled: Boolean)

    /** 自动任务仅接受仍然启用且 binding 相符的目标。 */
    suspend fun sync(target: ZhengFangSyncTarget, automatic: Boolean = false): TimetableSyncResult

    /** 通知实际发布成功后记录，避免同一次会话过期重复提醒。 */
    suspend fun markAuthNotificationSent(target: ZhengFangSyncTarget)
}
