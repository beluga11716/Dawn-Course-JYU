package com.dawncourse.feature.import_module

import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.ZhengFangWebVpn

/** 同步页只保存教务密码；WebVPN 密码由用户在可见网页登录窗口中输入。 */
internal object ZhengFangAccountSetup {
    fun prepare(username: String, password: String, saved: SyncCredentials?): SyncCredentials? {
        val account = username.trim().takeIf { it.isNotBlank() } ?: return null
        val existing = saved?.takeIf {
            it.provider == SyncProviderType.ZF && ZhengFangWebVpn.isSupportedEntry(it.endpointUrl) &&
                it.username == account
        }
        val secret = password.ifBlank { existing?.secret.orEmpty() }
        if (secret.isBlank()) return null
        // 只是再次打开登录窗口时，不轮换绑定、不丢弃有效会话和自动同步配置。
        if (existing != null && secret == existing.secret) return existing
        return SyncCredentials(
            provider = SyncProviderType.ZF,
            type = SyncCredentialType.PASSWORD,
            username = account,
            secret = secret,
            endpointUrl = ZhengFangWebVpn.BASE_URL,
        )
    }

    fun canAutofill(savedEndpoint: String?, currentUrl: String?): Boolean =
        ZhengFangWebVpn.isTimetableOrigin(savedEndpoint) &&
            ZhengFangWebVpn.isTimetableOrigin(currentUrl) &&
            WebViewCredentialAutofillPolicy.canAutoFill(savedEndpoint, currentUrl)
}
