package com.androidharness.app.ui.chat.rich

import org.junit.Assert.*
import org.junit.Test

class ResponseChartAxisTest {
    @Test fun aSharedLineAxisSpansTheDataRatherThanZero() {
        val axis = ResponseChartAxis.from(28.0, 42.0, bars = false)
        assertEquals(listOf("25", "30", "35", "40", "45"), axis.labels)
        assertEquals(.15f, axis.position(28.0), .00001f)
        assertEquals(.85f, axis.position(42.0), .00001f)
        val window = ResponseChartAxis.from(33.0, 36.0, bars = false)
        assertEquals(listOf("33", "34", "35", "36"), window.labels)
        assertEquals(listOf("-45", "-40", "-35", "-30", "-25"), ResponseChartAxis.from(-42.0, -28.0, false).labels)
    }

    @Test fun positiveNegativeAndMixedBarsHaveAnActualZeroBaseline() {
        assertEquals(0f, ResponseChartAxis.from(28.0, 42.0, true).position(0.0), 0f)
        assertEquals(1f, ResponseChartAxis.from(-42.0, -28.0, true).position(0.0), 0f)
        val mixed = ResponseChartAxis.from(-2.0, 3.0, true)
        assertTrue("0" in mixed.labels)
        assertTrue(mixed.position(-2.0) < mixed.position(0.0) && mixed.position(0.0) < mixed.position(3.0))
        assertEquals(0f, ResponseChartAxis.from(0.0, 0.0, true).position(0.0), 0f)
    }

    @Test fun flatAndFractionalReadingsHaveDistinctReadableTicks() {
        for (value in listOf(0.0, -7.0, 36.0)) {
            val axis = ResponseChartAxis.from(value, value, bars = false)
            assertTrue(axis.position(value) > 0f && axis.position(value) < 1f)
            assertTrue(axis.labels.size in 3..6)
        }
        val tiny = ResponseChartAxis.from(0.0012, 0.0018, false)
        assertEquals(listOf("0.0012", "0.0014", "0.0016", "0.0018"), tiny.labels)
    }
}
