package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.CalendarOccurrence
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.Semester
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** 每次上课单独生成日程，避免重复规则在单双周、调课后偏移。 */
class GenerateCalendarOccurrencesUseCase @Inject constructor() {
    operator fun invoke(
        courses: List<Course>,
        semester: Semester,
        sectionTimes: List<SectionTime>,
    ): List<CalendarOccurrence> {
        val zone = ZoneId.of("Asia/Shanghai")
        val monday = Instant.ofEpochMilli(semester.startDate).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val events = courses.flatMap { course ->
            require(course.semesterId == semester.id) { "课程不属于当前学期" }
            require(course.name.isNotBlank() && course.dayOfWeek in 1..7 && course.startWeek in 1..60 &&
                course.endWeek in course.startWeek..60 && course.weekType in 0..2) { "课程日期或周次无效，请先检查课表" }
            val lastSection = course.startSection.toLong() + course.duration - 1
            require(course.duration > 0 && course.startSection > 0 && lastSection <= sectionTimes.size) {
                "课程节次缺少对应的上下课时间，请先在设置中补全节次时间"
            }
            val startTime = parseTime(sectionTimes[course.startSection - 1].startTime)
            val endTime = parseTime(sectionTimes[lastSection.toInt() - 1].endTime)
            require(endTime > startTime) { "下课时间必须晚于上课时间，请先检查节次设置" }
            (course.startWeek..course.endWeek).filter { week ->
                course.weekType == Course.WEEK_TYPE_ALL ||
                    course.weekType == Course.WEEK_TYPE_ODD && week % 2 == 1 ||
                    course.weekType == Course.WEEK_TYPE_EVEN && week % 2 == 0
            }.map { week ->
                val date = monday.plusWeeks((week - 1).toLong()).plusDays((course.dayOfWeek - 1).toLong())
                val start = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
                val end = date.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
                val identity = "${course.name.length}:${course.name}:$start:$end"
                val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                CalendarOccurrence(key, course.name, course.location, buildString {
                    append("教师：${course.teacher.ifBlank { "未填写" }}")
                    append("\n第 $week 周 · 第 ${course.startSection}—$lastSection 节")
                    if (course.note.isNotBlank()) append("\n${course.note}")
                }, start, end)
            }
        }
        // 教务覆盖导入可能重新分配数据库 ID，身份只依据课程名称和实际时间。
        val grouped = events.groupBy { it.key }
        require(grouped.values.none { it.distinct().size > 1 }) { "同名同时间课程的信息冲突，请先检查重复课程" }
        return grouped.values.map { it.first() }.sortedWith(compareBy({ it.startMillis }, { it.key }))
    }

    private fun parseTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("节次时间格式无效，请先在设置中检查上下课时间")
    }
}
