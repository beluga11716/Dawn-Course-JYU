package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.SectionTime
import org.junit.Assert.*
import org.junit.Test

class AdjustSectionTimesUseCaseTest {
    private val adjust = AdjustSectionTimesUseCase()
    private val times = listOf(SectionTime("08:00", "08:45"), SectionTime("08:55", "09:40"),
        SectionTime("09:50", "10:35"), SectionTime("14:00", "14:45"), SectionTime("19:00", "19:45"))

    @Test fun `第二节延后十分钟后续所有节次一起延后第一节不变`() {
        assertEquals(listOf(times[0], SectionTime("09:05", "09:50"), SectionTime("10:00", "10:45"),
            SectionTime("14:10", "14:55"), SectionTime("19:10", "19:55")), adjust(times, 1, "09:05", "09:50"))
    }

    @Test fun `只延长第二节十分钟后续同样延后十分钟`() {
        val result = adjust(times, 1, "08:55", "09:50")
        assertEquals(SectionTime("08:55", "09:50"), result[1])
        assertEquals(SectionTime("10:00", "10:45"), result[2])
        assertEquals(SectionTime("19:10", "19:55"), result.last())
    }

    @Test fun `提前和多次调整累计生效且不修改输入`() {
        val first = adjust(times, 1, "09:05", "09:50")
        val second = adjust(first, 2, "09:55", "10:40")
        assertEquals(SectionTime("09:05", "09:50"), second[1])
        assertEquals(SectionTime("14:05", "14:50"), second[3])
        assertEquals(SectionTime("08:55", "09:40"), times[1])
    }

    @Test fun `修改最后一节不影响前面`() {
        assertEquals(times.dropLast(1) + SectionTime("19:10", "19:55"), adjust(times, 4, "19:10", "19:55"))
    }

    @Test(expected = IllegalArgumentException::class) fun `后续跨午夜时拒绝整次修改`() {
        adjust(times + SectionTime("23:10", "23:55"), 1, "09:05", "09:50")
    }

    @Test(expected = IllegalArgumentException::class) fun `和前一节重叠时拒绝修改`() {
        adjust(times, 1, "08:40", "09:25")
    }

    @Test(expected = IllegalArgumentException::class) fun `结束不晚于开始时拒绝修改`() {
        adjust(times, 1, "09:40", "09:40")
    }

    @Test(expected = IllegalArgumentException::class) fun `节次越界时拒绝修改`() {
        adjust(times, 5, "08:00", "08:45")
    }
}
