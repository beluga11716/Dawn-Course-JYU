package com.dawncourse.core.data.network

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.ZhengFangSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ZhengFangWebVpnClientTest {
    private val fixture: String get() = javaClass.classLoader!!.getResource("zf_jyu_sanitized.json")!!.readText()
    private val session = ZhengFangSession(
        "webvpn-token=fake; route=fake-route; JSESSIONID=fake-session", "2026", "3", 9,
        "test-agent", 1,
    )

    @Test fun parsesMixedWeeksWithoutLosingParityOrInventingWeeklyRows() {
        val courses = ZhengFangWebVpnClient.parseResponse(fixture)
        assertEquals(5, courses.size)
        assertEquals("田师616", courses[0].location)
        assertEquals("实验楼 202", courses[1].location)
        assertEquals(7, courses[1].dayOfWeek)
        assertEquals(11, courses[1].startSection)
        assertEquals(2, courses[1].duration)
        assertEquals(listOf(1, 4, 16), courses.filter { it.name == "测试课程乙" }.map { it.startWeek })
        assertEquals(listOf(0, 2, 0), courses.filter { it.name == "测试课程乙" }.map { it.weekType })
        assertEquals(Course.WEEK_TYPE_ODD, courses.last().weekType)
        assertEquals("体育馆", courses.last().location)
    }

    @Test fun invalidDayOrWeekFailsWholeResponseInsteadOfDroppingOneCourse() {
        listOf(
            fixture.replace("\"xqj\":\"1\"", "\"xqj\":\"8\""),
            fixture.replace("1-17周", "无法识别的周次"),
            fixture.replace("1-2节", "2-1节"),
        ).forEach { assertFailure(SyncErrorCode.PARSE_ERROR) { ZhengFangWebVpnClient.parseResponse(it) } }
    }

    @Test fun emptyDataAndNullDoNotBecomeAuthenticationErrorsOrEmptySuccessfulSchedules() {
        assertFailure(SyncErrorCode.PARSE_ERROR) { ZhengFangWebVpnClient.parseResponse("{\"kbList\":[]}") }
        assertFailure(SyncErrorCode.SERVER_ERROR) { ZhengFangWebVpnClient.parseResponse("null") }
        assertFailure(SyncErrorCode.PARSE_ERROR) { ZhengFangWebVpnClient.parseResponse("{\"items\":[]}") }
    }

    @Test fun loginHtmlIsAuthFailureButGatewayErrorPageIsServerError() {
        assertFailure(SyncErrorCode.AUTH_FAILED) { ZhengFangWebVpnClient.parseResponse("<html><input type=\"password\"></html>") }
        assertFailure(SyncErrorCode.SERVER_ERROR) { ZhengFangWebVpnClient.parseResponse("<h1>error</h1>") }
    }

    @Test fun usesExplicitTermAndCookieAndNeverFollowsRedirects() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("2026", request.url.queryParameter("xnm"))
            assertEquals("3", request.url.queryParameter("xqm"))
            assertEquals(session.cookieHeader, request.header("Cookie"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "https://webvpn.jyu.cn/login")
                .body("".toResponseBody()).build()
        }.build()
        assertFailureSuspend(SyncErrorCode.AUTH_FAILED) { ZhengFangWebVpnClient(http).fetch(session) }
        assertEquals(1, calls)
    }

    @Test fun successfulHttpResponseIsReadEvenWhenSmallerThanLimit() = runBlocking {
        assertEquals(5, client(200, fixture).fetch(session).size)
    }

    @Test fun gateway500WithLoginHtmlStillRemainsRetryableNetworkFailure() = runBlocking {
        try {
            client(500, "<input type=\"password\">").fetch(session)
            fail("Expected IOException")
        } catch (_: IOException) { }
    }

    @Test fun unknownRedirectIsNotMislabelledAsExpiredCredentials() = runBlocking {
        assertFailureSuspend(SyncErrorCode.SERVER_ERROR) { client(302, "").fetch(session) }
    }

    @Test fun gatewayPortalRootIsARecognizedLoginRedirect() {
        assertTrue(ZhengFangWebVpnClient.isLoginLocation("https://webvpn.jyu.cn/"))
        assertTrue(ZhengFangWebVpnClient.isLoginLocation("/xtgl/login_slogin.html"))
        assertFalse(ZhengFangWebVpnClient.isLoginLocation("https://example.test/maintenance"))
    }

    @Test fun oversizedResponseIsRejectedBeforeParsing() = runBlocking {
        assertFailureSuspend(SyncErrorCode.PARSE_ERROR) { client(200, "x".repeat(2 * 1024 * 1024 + 1)).fetch(session) }
    }

    private fun client(code: Int, body: String): ZhengFangWebVpnClient = ZhengFangWebVpnClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )

    private fun assertFailure(code: SyncErrorCode, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (failure: ZhengFangSyncException) { assertEquals(code, failure.code) }
    }

    private suspend fun assertFailureSuspend(code: SyncErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (failure: ZhengFangSyncException) { assertEquals(code, failure.code) }
    }
}
