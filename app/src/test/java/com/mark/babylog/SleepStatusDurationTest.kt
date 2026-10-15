package com.mark.babylog

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepStatusDurationTest {
    @Test
    fun showsSecondsUntilOneHour() {
        assertEquals("0 мин 0 с", sleepStatusDuration(-1))
        assertEquals("0 мин 0 с", sleepStatusDuration(999))
        assertEquals("0 мин 1 с", sleepStatusDuration(1_000))
        assertEquals("0 мин 59 с", sleepStatusDuration(59_999))
        assertEquals("1 мин 0 с", sleepStatusDuration(60_000))
        assertEquals("59 мин 59 с", sleepStatusDuration(3_599_999))
    }

    @Test
    fun showsHoursAndMinutesFromOneHour() {
        assertEquals("1 ч 0 мин", sleepStatusDuration(3_600_000))
        assertEquals("2 ч 3 мин", sleepStatusDuration(7_380_000))
    }
}
