package com.dawncourse.core.domain.model

import org.junit.Assert.*
import org.junit.Test

class ZhengFangWebVpnTest {
    @Test fun acceptsOnlyExactSchoolHttpsOrigins() {
        assertTrue(ZhengFangWebVpn.isTimetableOrigin(ZhengFangWebVpn.BASE_URL + "/kbcx/"))
        listOf(
            "http://https-jwcjwxt-jyu-edu-cn-443.webvpn.jyu.cn/",
            ZhengFangWebVpn.BASE_URL + ".example.com/",
            "https://user@https-jwcjwxt-jyu-edu-cn-443.webvpn.jyu.cn/",
            ZhengFangWebVpn.BASE_URL + ":444/",
            "https://webvpn.jyu.cn/",
        ).forEach { assertFalse(it, ZhengFangWebVpn.isTimetableOrigin(it)) }
        assertTrue(ZhengFangWebVpn.isSupportedEntry("https://webvpn.jyu.cn/login"))
    }

    @Test fun combinesRequiredCookiesAndDiscardsUnrelatedNames() {
        assertEquals("webvpn-token=a; route=b; JSESSIONID=c", ZhengFangWebVpn.cookieHeader(
            "route=b; JSESSIONID=c; analytics=ignored", "webvpn-token=a",
        ))
        assertNull(ZhengFangWebVpn.cookieHeader("route=b; JSESSIONID=c"))
        assertNull(ZhengFangWebVpn.cookieHeader("webvpn-token=a; route=b; JSESSIONID=c", "route=other"))
        assertNull(ZhengFangWebVpn.cookieHeader("webvpn-token=a\r\nInjected: yes; route=b; JSESSIONID=c"))
    }

    @Test fun secretsAreRedactedFromSessionString() {
        val session = ZhengFangSession("private-cookie", "2026", "3", 1, "agent", 0)
        assertFalse(session.toString().contains("private-cookie"))
        val credentials = SyncCredentials(SyncProviderType.ZF, SyncCredentialType.SESSION,
            "private-user", "private-password", ZhengFangWebVpn.BASE_URL, session)
        assertFalse(credentials.toString().contains("private-"))
    }

    @Test fun acceptsOnlyExplicitSupportedAcademicTerms() {
        assertTrue(ZhengFangWebVpn.validTerm("2026", "3"))
        assertTrue(ZhengFangWebVpn.validTerm("2026", "12"))
        assertTrue(ZhengFangWebVpn.validTerm("2026", "16"))
        assertFalse(ZhengFangWebVpn.validTerm("2026", "1"))
        assertFalse(ZhengFangWebVpn.validTerm("", "3"))
        assertFalse(ZhengFangWebVpn.validTerm("2026&xqm=12", "3"))
    }
}
