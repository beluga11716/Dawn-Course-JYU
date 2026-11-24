package com.dawncourse.feature.import_module

import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.ZhengFangSession
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import org.junit.Assert.*
import org.junit.Test

class ZhengFangAccountSetupTest {
    private val saved = SyncCredentials(
        SyncProviderType.ZF, SyncCredentialType.SESSION, "20260001", "academic-password",
        ZhengFangWebVpn.BASE_URL, ZhengFangSession("secret-cookie", "2026", "3", 8L, "ua", 1L),
    )

    @Test fun firstAccountNeedsNoManuallyEnteredUrl() {
        val result = ZhengFangAccountSetup.prepare(" 20260002 ", "school-password", null)!!
        assertEquals("20260002", result.username)
        assertEquals("school-password", result.secret)
        assertEquals(ZhengFangWebVpn.BASE_URL, result.endpointUrl)
        assertEquals(SyncCredentialType.PASSWORD, result.type)
        assertNull(result.zhengFangSession)
    }

    @Test fun unchangedAccountRetainsExistingSession() {
        assertSame(saved, ZhengFangAccountSetup.prepare("20260001", "", saved))
        assertSame(saved, ZhengFangAccountSetup.prepare("20260001", "academic-password", saved))
    }

    @Test fun changingAccountRequiresItsOwnPasswordAndDiscardsOldSession() {
        assertNull(ZhengFangAccountSetup.prepare("20260002", "", saved))
        val result = ZhengFangAccountSetup.prepare("20260002", "other-password", saved)!!
        assertEquals("other-password", result.secret)
        assertNull(result.zhengFangSession)
        assertEquals(SyncCredentialType.PASSWORD, result.type)
    }

    @Test fun changingPasswordDiscardsOldSession() {
        val result = ZhengFangAccountSetup.prepare("20260001", "new-password", saved)!!
        assertEquals("new-password", result.secret)
        assertNull(result.zhengFangSession)
    }

    @Test fun neverReusesAnotherProvidersOrSchoolsPassword() {
        assertNull(ZhengFangAccountSetup.prepare("20260001", "", saved.copy(provider = SyncProviderType.QIDI)))
        assertNull(ZhengFangAccountSetup.prepare("20260001", "", saved.copy(endpointUrl = "https://jw.example.edu.cn")))
        assertNull(ZhengFangAccountSetup.prepare(" ", "password", saved))
    }

    @Test fun academicPasswordNeverAutofillsTheWebVpnPortalEvenForLegacyPortalBinding() {
        assertFalse(ZhengFangAccountSetup.canAutofill(ZhengFangWebVpn.PORTAL_URL, ZhengFangWebVpn.PORTAL_URL))
        assertFalse(ZhengFangAccountSetup.canAutofill(ZhengFangWebVpn.BASE_URL, ZhengFangWebVpn.PORTAL_URL))
        assertFalse(ZhengFangAccountSetup.canAutofill(ZhengFangWebVpn.PORTAL_URL, ZhengFangWebVpn.BASE_URL))
        assertFalse(ZhengFangAccountSetup.canAutofill(ZhengFangWebVpn.BASE_URL, "https://evil.example/login"))
        assertTrue(ZhengFangAccountSetup.canAutofill(ZhengFangWebVpn.BASE_URL, ZhengFangWebVpn.BASE_URL + "/xtgl/login_slogin.html"))
    }
}
