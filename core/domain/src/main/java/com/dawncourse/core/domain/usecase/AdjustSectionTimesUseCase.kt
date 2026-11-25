package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.SectionTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 修改一节的时间，后续所有节次按该节下课时间的差值平移，保留课间间隔。 */
class AdjustSectionTimesUseCase @Inject constructor() {
    operator fun invoke(times: List<SectionTime>, index: Int, start: String, end: String): List<SectionTime> {
        require(index in times.indices) { "节次已变化，请重新选择" }
        val delta = minutes(end) - minutes(times[index].endTime)
        val adjusted = times.mapIndexed { position, time ->
            when {
                position < index -> time
                position == index -> SectionTime(start, end)
                else -> SectionTime(shift(time.startTime, delta), shift(time.endTime, delta))
            }
        }
        validateSectionTimes(adjusted)
        return adjusted
    }

    private fun shift(time: String, delta: Int): String {
        val shifted = minutes(time) + delta
        require(shifted in 0 until 24 * 60) { "调整后有节次超出当天，请缩小调整幅度" }
        return LocalTime.of(shifted / 60, shifted % 60).format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun minutes(time: String): Int = parseSectionTime(time).let { it.hour * 60 + it.minute }
}

internal fun parseSectionTime(value: String): LocalTime = try {
    LocalTime.parse(value)
} catch (_: Exception) {
    throw IllegalArgumentException("节次时间格式无效，请重新设置")
}

internal fun validateSectionTimes(times: List<SectionTime>) {
    var previousEnd: LocalTime? = null
    times.forEachIndexed { index, time ->
        val start = parseSectionTime(time.startTime)
        val end = parseSectionTime(time.endTime)
        require(end > start) { "第 ${index + 1} 节下课时间必须晚于上课时间" }
        require(previousEnd?.let { start >= it } != false) { "第 ${index + 1} 节与前一节时间重叠，请调整上课时间" }
        previousEnd = end
    }
}
