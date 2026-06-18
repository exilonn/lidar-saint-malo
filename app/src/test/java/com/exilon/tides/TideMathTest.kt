package com.exilon.tides

import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideMath
import com.exilon.tides.data.model.TideType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TideMathTest {

    private val h = 3_600_000L // one hour in millis

    // low 0.5m @0h, high 4.5m @6h, low 0.5m @12h, high 4.5m @18h
    private val extremes = listOf(
        TideExtreme(0 * h, TideType.LOW, 0.5),
        TideExtreme(6 * h, TideType.HIGH, 4.5),
        TideExtreme(12 * h, TideType.LOW, 0.5),
        TideExtreme(18 * h, TideType.HIGH, 4.5),
    )

    @Test fun `height equals extreme height at an extreme time`() {
        assertEquals(0.5, TideMath.heightAt(extremes, 0)!!, 1e-9)
        assertEquals(4.5, TideMath.heightAt(extremes, 6 * h)!!, 1e-9)
        assertEquals(0.5, TideMath.heightAt(extremes, 12 * h)!!, 1e-9)
    }

    @Test fun `half-cosine midpoint is the average of neighbouring extremes`() {
        // Exactly halfway (3h) between low 0.5 and high 4.5 -> mean = 2.5
        assertEquals(2.5, TideMath.heightAt(extremes, 3 * h)!!, 1e-9)
    }

    @Test fun `interpolated height stays within the bracketing extremes`() {
        val mid = TideMath.heightAt(extremes, 2 * h)!!
        assertTrue(mid in 0.5..4.5)
    }

    @Test fun `rising between a low and the following high`() {
        assertEquals(true, TideMath.isRising(extremes, 3 * h))
    }

    @Test fun `falling between a high and the following low`() {
        assertEquals(false, TideMath.isRising(extremes, 9 * h))
    }

    @Test fun `next high and next low are selected by type`() {
        assertEquals(6 * h, TideMath.nextExtreme(extremes, 1 * h, TideType.HIGH)?.timeMillis)
        assertEquals(12 * h, TideMath.nextExtreme(extremes, 1 * h, TideType.LOW)?.timeMillis)
        // Nearest of any type after 7h is the 12h low.
        assertEquals(12 * h, TideMath.nextExtreme(extremes, 7 * h)?.timeMillis)
    }

    @Test fun `previous extreme is the most recent at or before the time`() {
        assertEquals(6 * h, TideMath.previousExtreme(extremes, 7 * h)?.timeMillis)
    }

    @Test fun `curve samples cover the window inclusively`() {
        val samples = TideMath.curveSamples(extremes, 0, 18 * h, samples = 18)
        assertEquals(19, samples.size)
        assertEquals(0L, samples.first().timeMillis)
        assertEquals(18 * h, samples.last().timeMillis)
        assertEquals(0.5, samples.first().heightMeters, 1e-9)
    }

    @Test fun `curve samples pin every in-window extreme exactly`() {
        // Coarse grid (step 3.6h) so the 6h high and 12h low fall *between* grid points; each must
        // still appear as an exact sample, at its true peak/trough height, in ascending time order.
        val samples = TideMath.curveSamples(extremes, 0, 18 * h, samples = 5)
        assertTrue(samples.any { it.timeMillis == 6 * h })
        assertTrue(samples.any { it.timeMillis == 12 * h })
        assertEquals(4.5, samples.first { it.timeMillis == 6 * h }.heightMeters, 1e-9)
        assertEquals(0.5, samples.first { it.timeMillis == 12 * h }.heightMeters, 1e-9)
        assertTrue(samples.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
    }

    @Test fun `empty extremes yield no height and no samples`() {
        assertNull(TideMath.heightAt(emptyList(), 0))
        assertTrue(TideMath.curveSamples(emptyList(), 0, h).isEmpty())
        assertFalse(TideMath.curveSamples(extremes, 10, 5).isNotEmpty()) // end before start
    }
}
