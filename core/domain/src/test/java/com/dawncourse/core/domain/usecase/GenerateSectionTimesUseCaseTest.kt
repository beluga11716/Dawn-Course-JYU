package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.SectionTime
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class GenerateSectionTimesUseCaseTest {
    private fun generate(duration: Int = 45, breakTime: Int = 10, pm: LocalTime = LocalTime.of(14, 0),
        eve: LocalTime = LocalTime.of(19, 0), pmSec: Int = 5, eveSec: Int = 9) = GenerateSectionTimesUseCase()(
        12, duration, breakTime, LocalTime.of(8, 0), pmSec, pm, eveSec, eve)

    @Test fun `上午八点开始并保留午晚时间节点`() {
        val times = generate()
        assertEquals(12, times.size)
        assertEquals(SectionTime("08:00", "08:45"), times[0])
        assertEquals(SectionTime("08:55", "09:40"), times[1])
        assertEquals(SectionTime("14:00", "14:45"), times[4])
        assertEquals(SectionTime("19:00", "19:45"), times[8])
    }

    @Test(expected = IllegalArgumentException::class) fun `时长为零时拒绝生成`() { generate(duration = 0) }
    @Test(expected = IllegalArgumentException::class) fun `输入过大时拒绝生成`() { generate(duration = Int.MAX_VALUE) }
    @Test(expected = IllegalArgumentException::class) fun `午后节点与上午重叠时拒绝生成`() { generate(pm = LocalTime.of(9, 0)) }
    @Test(expected = IllegalArgumentException::class) fun `生成跨午夜时拒绝而不是回绕`() { generate(eve = LocalTime.of(23, 0)) }
    @Test(expected = IllegalArgumentException::class) fun `重复起始节次时拒绝生成`() { generate(pmSec = 5, eveSec = 5) }

    @Test fun `减少每日节数保留此前逐节调整`() {
        val existing = AdjustSectionTimesUseCase()(generate(), 2, "09:55", "10:40")
        val resized = GenerateSectionTimesUseCase()(10, 45, 10, LocalTime.of(8, 0), 5,
            LocalTime.of(14, 0), 9, LocalTime.of(19, 0), existing)
        assertEquals(existing.take(10), resized)
    }

    @Test fun `增加每日节数保留已有前缀并根据最后下课时间补齐`() {
        val existing = listOf(SectionTime("08:00", "08:45"), SectionTime("08:55", "09:50"))
        val resized = GenerateSectionTimesUseCase()(12, 45, 10, LocalTime.of(8, 0), 5,
            LocalTime.of(14, 0), 9, LocalTime.of(19, 0), existing)
        assertEquals(existing, resized.take(2))
        assertEquals(SectionTime("10:00", "10:45"), resized[2])
        assertEquals(12, resized.size)
    }
}
