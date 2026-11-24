package com.dawncourse.core.domain.model

/** 从已登录的教务课表页面读取，不以设备当前日期猜测远端学期。 */
data class ZhengFangAcademicTerm(val year: String, val code: String) {
    init { require(ZhengFangWebVpn.validTerm(year, code)) }

    val name: String get() = "$year-${year.toInt() + 1}学年${when (code) {
        "3" -> "第一学期"
        "12" -> "第二学期"
        else -> "第三学期"
    }}"

    fun matches(session: ZhengFangSession?): Boolean = session?.academicYear == year && session.termCode == code
}

/** Cookie 仅在内存中短暂传递，持久化仍由凭据仓库加密处理。 */
data class ZhengFangLoginSnapshot(
    val term: ZhengFangAcademicTerm,
    val cookieHeader: String,
    val userAgent: String,
    /** 教务页面提供或用户明确确认的第一周周一；缺失时禁止猜测。 */
    val startDate: Long? = null,
) {
    override fun toString(): String = "ZhengFangLoginSnapshot(<redacted>)"
}

sealed interface ZhengFangLoginImportResult {
    data class Success(
        val context: ActiveTimetableContext,
        val target: ZhengFangSyncTarget,
        val courseCount: Int,
    ) : ZhengFangLoginImportResult
    data class NeedsStartDate(val term: ZhengFangAcademicTerm) : ZhengFangLoginImportResult
    data class Failure(val code: SyncErrorCode, val message: String) : ZhengFangLoginImportResult
}
