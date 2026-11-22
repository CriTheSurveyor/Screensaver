package dev.standby.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerTest {
    @Test fun decimalsFollowTheChargeCounter() {
        val p = PrecisePercent()
        assertEquals(50.0, p.update(50, 2_000_000), 0.001)
        assertEquals(50.495, p.update(50, 2_020_000), 0.001)  // 40 000 µAh per percent, half way
        assertEquals(50.99, p.update(50, 2_100_000), 0.001)   // never shows the next whole percent early
        assertEquals(51.0, p.update(51, 2_100_000), 0.001)    // new tick, new anchor
        assertEquals(37.0, p.update(37, 0), 0.001)            // phone without a charge counter
    }

    @Test fun currentUnitsAndSign() {
        assertEquals(1850, currentMilliamps(1_850_000))   // µA, as the spec says
        assertEquals(1850, currentMilliamps(-1_850_000))  // negative while charging on some makers
        assertEquals(1850, currentMilliamps(1850))        // already mA (some Samsung)
        assertEquals(null, currentMilliamps(Int.MIN_VALUE))
    }

    @Test fun voltageUnits() {
        assertEquals(4.21, batteryVolts(4210)!!, 0.001)
        assertEquals(4.21, batteryVolts(4_210_000)!!, 0.001)
        assertEquals(null, batteryVolts(0))
    }
}
