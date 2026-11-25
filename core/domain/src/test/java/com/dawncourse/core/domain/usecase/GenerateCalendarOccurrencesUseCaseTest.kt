package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.Semester
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GenerateCalendarOccurrencesUseCaseTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val semester = Semester(7, 2, "秋季", LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli())
    private val times = listOf(SectionTime("08:00", "08:45"), SectionTime("08:55", "09:40"))
    private val course = Course(semesterId = 7, name = "高等数学", location = "A101", teacher = "王老师",
        dayOfWeek = 2, startSection = 1, duration = 2, startWeek = 1, endWeek = 4)
    private val generate = GenerateCalendarOccurrencesUseCase()

    @Test fun `星期二开学对应同周且保留完整连堂时间`() {
        val events = generate(listOf(course), semester, times)
        assertEquals(4, events.size)
        assertEquals("2026-09-01T08:00+08:00[Asia/Shanghai]", java.time.Instant.ofEpochMilli(events.first().startMillis).atZone(zone).toString())
        assertEquals(100 * 60_000L, events.first().endMillis - events.first().startMillis)
        assertTrue(events.first().description.contains("王老师"))
    }

    @Test fun `单双周以学期绝对周次判断`() {
        val odd = generate(listOf(course.copy(startWeek = 2, endWeek = 5, weekType = Course.WEEK_TYPE_ODD)), semester, times)
        val even = generate(listOf(course.copy(startWeek = 3, endWeek = 6, weekType = Course.WEEK_TYPE_EVEN)), semester, times)
        assertEquals(listOf("2026-09-15", "2026-09-29"), odd.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toString() })
        assertEquals(listOf("2026-09-22", "2026-10-06"), even.map { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toString() })
    }

    @Test fun `星期日课程不落到下一周`() {
        val event = generate(listOf(course.copy(dayOfWeek = 7, endWeek = 1)), semester, times).single()
        assertEquals(LocalDate.of(2026, 9, 6), java.time.Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate())
    }

    @Test fun `覆盖导入更换数据库ID或修改教室仍使用原日程身份`() {
        val first = generate(listOf(course.copy(id = 8)), semester, times)
        val changed = generate(listOf(course.copy(id = 99, location = "B202")), semester, times)
        assertEquals(first.map { it.key }, changed.map { it.key })
        assertEquals("B202", changed.first().location)
    }

    @Test fun `完全重复课程去重`() {
        assertEquals(4, generate(listOf(course, course.copy(id = 123)), semester, times).size)
    }

    @Test(expected = IllegalArgumentException::class) fun `信息冲突的重复课程拒绝同步`() {
        generate(listOf(course, course.copy(location = "B202")), semester, times)
    }

    @Test(expected = IllegalArgumentException::class) fun `缺少节次时间时整次拒绝而不是漏写课程`() {
        generate(listOf(course), semester, times.take(1))
    }

    @Test(expected = IllegalArgumentException::class) fun `非法时间拒绝同步`() {
        generate(listOf(course), semester, listOf(SectionTime("25:00", "08:45"), times[1]))
    }

    @Test(expected = IllegalArgumentException::class) fun `跨学期课程拒绝同步`() {
        generate(listOf(course.copy(semesterId = 99)), semester, times)
    }

    @Test fun `空课表生成空日程便于移除已删课程`() {
        assertTrue(generate(emptyList(), semester, emptyList()).isEmpty())
    }

    @Test fun `单次调课只生成指定周日程`() {
        assertEquals(1, generate(listOf(course.copy(startWeek = 3, endWeek = 3, isModified = true)), semester, times).size)
    }
}
