package com.dawncourse.core.domain.model

import java.net.URI

/** 首批只适配经过验证的嘉应学院入口，不从任意网页采集或转发会话。 */
object ZhengFangWebVpn {
    const val BASE_URL = "https://https-jwcjwxt-jyu-edu-cn-443.webvpn.jyu.cn"
    const val PORTAL_URL = "https://webvpn.jyu.cn/"
    const val TIMETABLE_PATH = "/kbcx/xskbcx_cxXsgrkb.html"
    private val requiredCookies = listOf("webvpn-token", "route", "JSESSIONID")

    fun isTimetableOrigin(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == URI(BASE_URL).host &&
            uri.port in listOf(-1, 443) && uri.userInfo == null
    }.getOrDefault(false)

    fun isSupportedEntry(url: String?): Boolean = isTimetableOrigin(url) || runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "webvpn.jyu.cn" &&
            uri.port in listOf(-1, 443) && uri.userInfo == null
    }.getOrDefault(false)

    /** 只收集所需的三个名字，拒绝重复的冲突值以及换行等非法头字符。 */
    fun cookieHeader(vararg headers: String?): String? {
        val values = linkedMapOf<String, String>()
        for (part in headers.filterNotNull().flatMap { it.split(';') }) {
            val name = part.substringBefore('=').trim()
            if (name !in requiredCookies || !part.contains('=')) continue
            val value = part.substringAfter('=').trim()
            if (value.isEmpty() || value.any { it.code !in 0x21..0x7e || it == ';' }) return null
            if (values[name]?.let { it != value } == true) return null
            values[name] = value
        }
        if (!values.keys.containsAll(requiredCookies)) return null
        return requiredCookies.joinToString("; ") { "$it=${values.getValue(it)}" }
    }

    fun validTerm(year: String, term: String): Boolean =
        year.toIntOrNull() in 2000..2100 && term in setOf("3", "12", "16")
}
