package com.dawncourse.feature.import_module

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZhengFangLoginMetadataTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun callback(year: String, term: String, date: String = "") =
        JSONObject.quote(JSONObject().put("year", year).put("term", term).put("startDate", date).toString())

    @Test fun readsActualTermInsteadOfDeviceCurrentYear() {
        val result = ZhengFangLoginMetadata.parse(callback("2025", "12"), zone)!!
        assertEquals("2025-2026学年第二学期", result.term.name)
        assertNull(result.startDate)
    }

    @Test fun validSchoolFirstMondayCanAvoidManualDatePrompt() {
        val result = ZhengFangLoginMetadata.parse(callback("2026", "3", "2026-09-07"), zone)!!
        assertEquals(LocalDate.of(2026, 9, 7).atStartOfDay(zone).toInstant().toEpochMilli(), result.startDate)
    }

    @Test fun tuesdaySchoolStartUsesSameWeeksMonday() {
        val result = ZhengFangLoginMetadata.parse(callback("2026", "3", "2026-09-01"), zone)!!
        assertEquals(LocalDate.of(2026, 8, 31).atStartOfDay(zone).toInstant().toEpochMilli(), result.startDate)
    }

    @Test fun missingInvalidOrOutOfYearDateIsNeverGuessed() {
        listOf("", "not-a-date", "2023-09-04").forEach {
            assertNull(ZhengFangLoginMetadata.parse(callback("2026", "3", it), zone)!!.startDate)
        }
    }

    @Test fun absentMalformedAndUnsupportedTermCannotBecomeCurrentTerm() {
        listOf(null, "null", "undefined", "{}", JSONObject.quote("{}"), callback("", "3"),
            callback("2026", "1"), callback("1999", "3")).forEach {
            assertNull(ZhengFangLoginMetadata.parse(it, zone))
        }
    }
}
