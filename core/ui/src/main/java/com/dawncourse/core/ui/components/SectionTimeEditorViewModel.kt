package com.dawncourse.core.ui.components

import androidx.lifecycle.ViewModel
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.usecase.AdjustSectionTimesUseCase
import com.dawncourse.core.domain.usecase.GenerateSectionTimesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject

/** 为所有节次编辑组件提供纯预览，只有调用方确认保存后才更新设置。 */
@HiltViewModel
class SectionTimeEditorViewModel @Inject constructor(
    private val adjustTimes: AdjustSectionTimesUseCase,
    private val generateTimes: GenerateSectionTimesUseCase,
) : ViewModel() {
    fun adjust(times: List<SectionTime>, index: Int, start: String, end: String): Result<List<SectionTime>> =
        runCatching { adjustTimes(times, index, start, end) }

    fun preview(
        existing: List<SectionTime>?,
        maxSections: Int,
        duration: String,
        breakTime: String,
        amStart: LocalTime,
        pmStartSec: String,
        pmStart: LocalTime,
        eveStartSec: String,
        eveStart: LocalTime,
    ): Result<List<SectionTime>> = runCatching {
        // 减少每日节数只截取末尾；增加时保留已有前缀，再补齐生成的时间。
        if (existing != null && existing.size >= maxSections) {
            val times = existing.take(maxSections)
            adjustTimes(times, 0, times.first().startTime, times.first().endTime)
        } else {
            generateTimes(
                maxSections,
                duration.toIntOrNull() ?: throw IllegalArgumentException("请填写有效的每节时长"),
                breakTime.toIntOrNull() ?: throw IllegalArgumentException("请填写有效的课间休息时间"),
                amStart,
                pmStartSec.toIntOrNull() ?: throw IllegalArgumentException("请填写下午起始节次"),
                pmStart,
                eveStartSec.toIntOrNull() ?: throw IllegalArgumentException("请填写晚上起始节次"),
                eveStart,
                existing.orEmpty(),
            )
        }
    }
}
