package com.dawncourse.core.data.network

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.ZhengFangSession
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 错误文本只包含预定义描述，不携带响应、Cookie 或账号。 */
internal class ZhengFangSyncException(val code: SyncErrorCode, message: String) : Exception(message)

@Singleton
class ZhengFangWebVpnClient internal constructor(private val client: OkHttpClient) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build())

    suspend fun fetch(session: ZhengFangSession): List<Course> = withContext(Dispatchers.IO) {
        if (!ZhengFangWebVpn.validTerm(session.academicYear, session.termCode) ||
            ZhengFangWebVpn.cookieHeader(session.cookieHeader) == null
        ) throw ZhengFangSyncException(SyncErrorCode.AUTH_FAILED, "会话不完整，请重新登录并保存")
        val url = (ZhengFangWebVpn.BASE_URL + ZhengFangWebVpn.TIMETABLE_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("gnmkdm", "N2151")
            .addQueryParameter("xnm", session.academicYear)
            .addQueryParameter("xqm", session.termCode)
            .build()
        val request = Request.Builder().url(url)
            .header("Cookie", session.cookieHeader)
            .header("Referer", ZhengFangWebVpn.BASE_URL + "/")
            .header("User-Agent", session.userAgent.ifBlank { "Mozilla/5.0 DawnCourse" })
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            // 不跟随跳转，避免把 Cookie 带到其他域；网关 5xx 必须先于 HTML 判断。
            if (response.code >= 500 || response.code == 429) {
                throw IOException("WebVPN temporarily unavailable")
            }
            if (response.code == 401 || response.code == 403 ||
                response.code in 300..399 && isLoginLocation(response.header("Location").orEmpty())
            ) throw ZhengFangSyncException(SyncErrorCode.AUTH_FAILED, "课表会话已过期，请重新登录")
            if (!response.isSuccessful) {
                throw ZhengFangSyncException(SyncErrorCode.SERVER_ERROR, "教务系统返回异常状态，请稍后重试")
            }
            val body = response.body ?: throw IOException("Empty response")
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw ZhengFangSyncException(SyncErrorCode.PARSE_ERROR, "课表响应过大，已保留原课表")
            }
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) {
                throw ZhengFangSyncException(SyncErrorCode.PARSE_ERROR, "课表响应过大，已保留原课表")
            }
            parseResponse(source.buffer.readUtf8())
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        internal fun isLoginLocation(location: String): Boolean {
            val value = location.lowercase()
            val redirect = (ZhengFangWebVpn.BASE_URL + "/").toHttpUrl().resolve(location)
            val portal = ZhengFangWebVpn.PORTAL_URL.toHttpUrlOrNull()
            if (redirect != null && portal != null && redirect.scheme == portal.scheme &&
                redirect.host == portal.host && redirect.port == portal.port && redirect.encodedPath == "/"
            ) return true
            return listOf("/login", "/cas/", "/authserver/", "/sso/", "login_slogin").any(value::contains)
        }

        internal fun parseResponse(text: String): List<Course> {
            val trimmed = text.trim().removePrefix("\uFEFF")
            if (trimmed.startsWith("<")) {
                val lower = trimmed.lowercase()
                if (lower.contains("type=\"password\"") || lower.contains("type='password'") ||
                    lower.contains("login_slogin") || lower.contains("authserver/login")
                ) throw ZhengFangSyncException(SyncErrorCode.AUTH_FAILED, "课表会话已过期，请重新登录")
                throw ZhengFangSyncException(SyncErrorCode.SERVER_ERROR, "网关返回异常页面，已保留原课表")
            }
            if (trimmed == "null" || trimmed.isBlank()) {
                throw ZhengFangSyncException(SyncErrorCode.SERVER_ERROR, "所选学年学期暂无有效数据，已保留原课表")
            }
            return try {
                val root = JsonParser.parseString(trimmed).asJsonObject
                val rows = root.getAsJsonArray("kbList") ?: error("Missing kbList")
                if (rows.size() == 0) {
                    throw ZhengFangSyncException(SyncErrorCode.PARSE_ERROR, "远端课表为空，已保留原课表，请手动核对")
                }
                rows.flatMap { element ->
                    val row = element.asJsonObject
                    fun field(name: String) = row.get(name)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    val name = field("kcmc").takeIf { it.isNotBlank() } ?: error("Missing name")
                    val day = field("xqj").toInt().also { require(it in 1..7) }
                    val sections = ranges(field("jcs"), "节", allowParity = false)
                    val weeks = ranges(field("zcd"), "周", allowParity = true)
                    val room = field("cdmc")
                    val building = field("lh")
                    val location = when {
                        building.isBlank() || room.contains(building) -> room
                        room.isBlank() || building.contains(room) -> building
                        else -> "$building $room"
                    }
                    weeks.flatMap { week ->
                        sections.map { section ->
                            Course(
                                name = name, teacher = field("xm"), location = location,
                                dayOfWeek = day, startSection = section.start,
                                duration = section.end - section.start + 1,
                                startWeek = week.start, endWeek = week.end, weekType = week.parity,
                            )
                        }
                    }
                }.distinct()
            } catch (failure: ZhengFangSyncException) {
                throw failure
            } catch (_: Exception) {
                throw ZhengFangSyncException(SyncErrorCode.PARSE_ERROR, "课表格式无法完整识别，已保留原课表")
            }
        }

        private data class Range(val start: Int, val end: Int, val parity: Int)

        /** 每段分别解释单双周，不能让后缀“双”污染前面的普通周次。 */
        private fun ranges(raw: String, unit: String, allowParity: Boolean): List<Range> {
            val normalized = raw.replace('（', '(').replace('）', ')')
                .replace('，', ',').replace('、', ',').replace('－', '-').replace('—', '-')
                .replace(Regex("\\s+"), "")
            require(normalized.isNotBlank())
            return normalized.split(',').map { segment ->
                val match = Regex("^(\\d+)(?:-(\\d+))?$unit(?:\\(([单双])(?:周)?\\))?$")
                    .matchEntire(segment)
                    ?: Regex("^(\\d+)(?:-(\\d+))?(?:\\(([单双])(?:周)?\\))?$").matchEntire(segment)
                    ?: error("Invalid range")
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].ifBlank { start.toString() }.toInt()
                val parity = when (match.groupValues[3]) {
                    "单" -> Course.WEEK_TYPE_ODD
                    "双" -> Course.WEEK_TYPE_EVEN
                    else -> Course.WEEK_TYPE_ALL
                }
                require(start >= 1 && end >= start && end <= if (allowParity) 60 else 30)
                require(allowParity || parity == Course.WEEK_TYPE_ALL)
                require(parity == 0 || (start..end).any { it % 2 == if (parity == 1) 1 else 0 })
                Range(start, end, parity)
            }
        }
    }
}
