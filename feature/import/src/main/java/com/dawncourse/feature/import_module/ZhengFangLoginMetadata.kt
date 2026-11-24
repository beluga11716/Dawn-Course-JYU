package com.dawncourse.feature.import_module

import com.dawncourse.core.domain.model.ZhengFangAcademicTerm
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.json.JSONTokener

internal data class ZhengFangLoginMetadata(val term: ZhengFangAcademicTerm, val startDate: Long?) {
    companion object {
        /** evaluateJavascript 返回 JSON 编码的字符串；空页面、错误结构均不猜测学期。 */
        fun parse(callback: String?, zone: ZoneId = ZoneId.systemDefault()): ZhengFangLoginMetadata? = runCatching {
            val value = JSONTokener(callback ?: return null).nextValue() as? String ?: return null
            val json = JSONObject(value)
            val year = json.optString("year")
            val code = json.optString("term")
            if (!ZhengFangWebVpn.validTerm(year, code)) return null
            val date = runCatching { LocalDate.parse(json.optString("startDate").replace('/', '-')) }.getOrNull()
                ?.takeIf { it.dayOfWeek == DayOfWeek.MONDAY && it.year in year.toInt()..(year.toInt() + 1) }
            ZhengFangLoginMetadata(ZhengFangAcademicTerm(year, code),
                date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())
        }.getOrNull()
    }
}
