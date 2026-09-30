package com.minimal.snore.data

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {

    @Test
    fun testNightWindowCrossMidnight() {
        val bedHour = 22
        val bedMin = 30
        val wakeHour = 7
        val wakeMin = 30

        // Before bedtime
        assertFalse(AppSettings.isTimeInNightWindow(22, 29, bedHour, bedMin, wakeHour, wakeMin))
        assertFalse(AppSettings.isTimeInNightWindow(15, 0, bedHour, bedMin, wakeHour, wakeMin))

        // At bedtime
        assertTrue(AppSettings.isTimeInNightWindow(22, 30, bedHour, bedMin, wakeHour, wakeMin))

        // Late evening
        assertTrue(AppSettings.isTimeInNightWindow(23, 59, bedHour, bedMin, wakeHour, wakeMin))

        // Midnight & early morning
        assertTrue(AppSettings.isTimeInNightWindow(0, 0, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(3, 45, bedHour, bedMin, wakeHour, wakeMin))

        // At wake time
        assertTrue(AppSettings.isTimeInNightWindow(7, 30, bedHour, bedMin, wakeHour, wakeMin))

        // After wake time
        assertFalse(AppSettings.isTimeInNightWindow(7, 31, bedHour, bedMin, wakeHour, wakeMin))
        assertFalse(AppSettings.isTimeInNightWindow(8, 0, bedHour, bedMin, wakeHour, wakeMin))
        assertFalse(AppSettings.isTimeInNightWindow(12, 0, bedHour, bedMin, wakeHour, wakeMin))
    }

    @Test
    fun testNightWindowSameDay() {
        val bedHour = 1
        val bedMin = 0
        val wakeHour = 8
        val wakeMin = 0

        assertFalse(AppSettings.isTimeInNightWindow(0, 59, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(1, 0, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(4, 30, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(8, 0, bedHour, bedMin, wakeHour, wakeMin))
        assertFalse(AppSettings.isTimeInNightWindow(8, 1, bedHour, bedMin, wakeHour, wakeMin))
        assertFalse(AppSettings.isTimeInNightWindow(23, 0, bedHour, bedMin, wakeHour, wakeMin))
    }

    @Test
    fun testDaytimeNapWindow() {
        val bedHour = 13
        val bedMin = 0
        val wakeHour = 15
        val wakeMin = 30

        assertFalse(AppSettings.isTimeInNightWindow(12, 59, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(13, 0, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(14, 15, bedHour, bedMin, wakeHour, wakeMin))
        assertTrue(AppSettings.isTimeInNightWindow(15, 30, bedHour, bedMin, wakeHour, wakeMin))
        assertFalse(AppSettings.isTimeInNightWindow(15, 31, bedHour, bedMin, wakeHour, wakeMin))
    }

    @Test
    fun testMicCalibrationOffsets() {
        assertEquals(0.0f, AppSettings.MicCalibration.BEDSIDE.offsetDb, 0.01f)
        assertEquals(3.0f, AppSettings.MicCalibration.NIGHTSTAND.offsetDb, 0.01f)
        assertEquals(6.0f, AppSettings.MicCalibration.FAR.offsetDb, 0.01f)
    }
}
