package dev.standby.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerTest {
    @Test fun landscapeOnlyWhenStandingOnItsSide() {
        assertTrue(isLandscape(8.5f, 0.5f))   // on a stand, tilted back
        assertTrue(isLandscape(-9.6f, 1f))    // other side
        assertFalse(isLandscape(0.2f, 0.1f))  // flat on a pad
        assertFalse(isLandscape(0.5f, 9.7f))  // portrait
        assertFalse(isLandscape(6f, 5f))      // diagonal, in a hand
    }

    @Test fun debounceIgnoresBlips() {
        val d = Debounce(1500)
        assertFalse(d.update(true, 0))
        assertFalse(d.update(true, 1000))
        assertTrue(d.update(true, 1500))
        assertTrue(d.update(false, 1600))   // a bump
        assertTrue(d.update(true, 1700))
        assertTrue(d.update(false, 5000))
        assertFalse(d.update(false, 6500))  // really picked up
        d.reset()
        assertFalse(d.stable)
    }
}
