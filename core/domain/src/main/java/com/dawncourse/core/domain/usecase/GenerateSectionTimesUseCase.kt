package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.SectionTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 按上午、下午、晚上的起始节点生成可逐节微调的当天时间表。 */
class GenerateSectionTimesUseCase @Inject constructor() {
    operator fun invoke(
        maxSections: Int,
        duration: Int,
        breakTime: Int,
        amStart: LocalTime,
        pmStartSec: Int,
        pmStart: LocalTime,
        eveStartSec: Int,
        eveStart: LocalTime,
        existing: List<SectionTime> = emptyList(),
    ): List<SectionTime> {
        require(maxSections in 1..60) { "每天节数无效" }
        require(duration in 1..240) { "每节时长应为 1—240 分钟" }
        require(breakTime in 0..180) { "课间休息应为 0—180 分钟" }
        require(pmStartSec in 2..60 && eveStartSec in (pmStartSec + 1)..61) { "下午和晚上起始节次应依次递增" }
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        var endMinutes = 0
        val times = (1..maxSections).map { section ->
            existing.getOrNull(section - 1)?.let { saved ->
                val end = parseSectionTime(saved.endTime)
                endMinutes = end.hour * 60 + end.minute
                return@map saved
            }
            val anchor = when (section) {
                1 -> amStart
                pmStartSec -> pmStart
                eveStartSec -> eveStart
                else -> null
            }
            val startMinutes = anchor?.let { it.hour * 60 + it.minute } ?: (endMinutes + breakTime)
            endMinutes = startMinutes + duration
            require(startMinutes in 0 until 1440 && endMinutes in 1 until 1440) { "生成的节次超出当天，请调整时长或起始时间" }
            SectionTime(LocalTime.of(startMinutes / 60, startMinutes % 60).format(formatter),
                LocalTime.of(endMinutes / 60, endMinutes % 60).format(formatter))
        }
        validateSectionTimes(times)
        return times
    }
}
