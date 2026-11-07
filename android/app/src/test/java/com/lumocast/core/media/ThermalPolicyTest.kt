package com.lumocast.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun normalTemperatureKeepsFullQuality() {
        val plan = ThermalPolicy.plan(ThermalPolicy.NONE, 60)
        assertEquals(60, plan.fpsCap)
        assertEquals(1.0, plan.bitrateScale, 0.001)
        assertFalse(plan.stop)
        assertNull(plan.note)
    }

    @Test
    fun moderateDropsToThreeQuarterFpsAndBitrate() {
        val plan = ThermalPolicy.plan(ThermalPolicy.MODERATE, 60)
        assertEquals(45, plan.fpsCap)
        assertEquals(0.75, plan.bitrateScale, 0.001)
        assertFalse(plan.stop)
        assertTrue(plan.note!!.contains("偏热"))
    }

    @Test
    fun severeHalvesFpsWithFloorOf15() {
        val plan = ThermalPolicy.plan(ThermalPolicy.SEVERE, 60)
        assertEquals(30, plan.fpsCap)
        assertEquals(0.5, plan.bitrateScale, 0.001)

        val low = ThermalPolicy.plan(ThermalPolicy.SEVERE, 24)
        assertEquals(15, low.fpsCap)
    }

    @Test
    fun criticalRequestsStop() {
        val plan = ThermalPolicy.plan(ThermalPolicy.CRITICAL, 60)
        assertTrue(plan.stop)
        assertTrue(plan.note!!.contains("停止"))
    }

    @Test
    fun emergencyAndShutdownAlsoStop() {
        assertTrue(ThermalPolicy.plan(ThermalPolicy.EMERGENCY, 30).stop)
        assertTrue(ThermalPolicy.plan(ThermalPolicy.SHUTDOWN, 30).stop)
    }
}
