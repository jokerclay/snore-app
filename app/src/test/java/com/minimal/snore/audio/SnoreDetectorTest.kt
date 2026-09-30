package com.minimal.snore.audio

import org.junit.Assert.*
import org.junit.Test

class SnoreDetectorTest {

    private fun createLowFreqFrame(amplitude: Short = 4000, halfPeriod: Int = 40): ShortArray {
        return ShortArray(1600) { i ->
            if ((i / halfPeriod) % 2 == 0) amplitude else (-amplitude).toShort()
        }
    }

    private fun createHighFreqFrame(amplitude: Short = 4000): ShortArray {
        return ShortArray(1600) { i ->
            if (i % 2 == 0) amplitude else (-amplitude).toShort()
        }
    }

    private fun createSilenceFrame(): ShortArray {
        return ShortArray(1600) { 0 }
    }

    @Test
    fun testSilenceHasMinimumDecibels() {
        var lastDb = 0f
        val detector = SnoreDetector(
            calibrationOffset = 0.0f,
            onDecibelUpdate = { lastDb = it },
            onSnoreDetected = { _, _, _ -> }
        )

        detector.processFrame(createSilenceFrame(), 1600)
        assertEquals(20.0f, lastDb, 0.1f)
    }

    @Test
    fun testCalibrationOffsetShiftsDecibels() {
        var uncalibratedDb = 0f
        val uncalibratedDetector = SnoreDetector(
            calibrationOffset = 0.0f,
            onDecibelUpdate = { uncalibratedDb = it },
            onSnoreDetected = { _, _, _ -> }
        )

        var calibratedDb = 0f
        val calibratedDetector = SnoreDetector(
            calibrationOffset = 6.0f,
            onDecibelUpdate = { calibratedDb = it },
            onSnoreDetected = { _, _, _ -> }
        )

        val frame = createLowFreqFrame(amplitude = 2000)
        uncalibratedDetector.processFrame(frame, 1600)
        calibratedDetector.processFrame(frame, 1600)

        assertTrue(calibratedDb > uncalibratedDb)
        assertEquals(6.0f, calibratedDb - uncalibratedDb, 0.1f)
    }

    @Test
    fun testHighFrequencyNoiseRejectedByZcr() {
        var snoreDetected = false
        val detector = SnoreDetector(
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, _ -> snoreDetected = true }
        )

        val highFreqFrame = createHighFreqFrame(amplitude = 5000)
        // Send 15 frames (1.5 seconds) of high frequency sound
        for (i in 0 until 15) {
            detector.processFrame(highFreqFrame, 1600)
        }
        // Send silence to close burst
        for (i in 0 until 5) {
            detector.processFrame(createSilenceFrame(), 1600)
        }

        assertFalse("High frequency sound must be rejected by ZCR filter", snoreDetected)
    }

    @Test
    fun testNormalSnoreDetectedSuccessfully() {
        var detectedCount = 0
        var detectedPeakDb = 0f
        var detectedDuration = 0L
        var isApnea = false

        var fakeTime = 1000L
        val detector = SnoreDetector(
            timeProvider = { fakeTime },
            onDecibelUpdate = {},
            onSnoreDetected = { peak, duration, apnea ->
                detectedCount++
                detectedPeakDb = peak
                detectedDuration = duration
                isApnea = apnea
            }
        )

        val snoreFrame = createLowFreqFrame(amplitude = 4000) // ~78 dB, deep pitch
        val silence = createSilenceFrame()

        // 10 frames of snore (1000ms duration, valid between 600ms and 3500ms)
        for (i in 0 until 10) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }

        // Drop back to silence to complete the burst
        fakeTime += 100L
        detector.processFrame(silence, 1600)

        assertEquals(1, detectedCount)
        assertEquals(1000L, detectedDuration)
        assertTrue(detectedPeakDb > 70f)
        assertFalse(isApnea)
    }

    @Test
    fun testBurstDurationTooShortIgnored() {
        var detectedCount = 0
        var fakeTime = 1000L
        val detector = SnoreDetector(
            timeProvider = { fakeTime },
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, _ -> detectedCount++ }
        )

        val snoreFrame = createLowFreqFrame(amplitude = 4000)
        // 3 frames = 300ms (below MIN_SNORE_DURATION_MS 600ms)
        for (i in 0 until 3) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }
        detector.processFrame(createSilenceFrame(), 1600)

        assertEquals(0, detectedCount)
    }

    @Test
    fun testBurstDurationTooLongIgnored() {
        var detectedCount = 0
        var fakeTime = 1000L
        val detector = SnoreDetector(
            timeProvider = { fakeTime },
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, _ -> detectedCount++ }
        )

        val snoreFrame = createLowFreqFrame(amplitude = 4000)
        // 40 frames = 4000ms (above MAX_SNORE_DURATION_MS 3500ms)
        for (i in 0 until 40) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }
        detector.processFrame(createSilenceFrame(), 1600)

        assertEquals(0, detectedCount)
    }

    @Test
    fun testApneaSuspectFlaggedAfterSilenceGap() {
        val apneaResults = mutableListOf<Boolean>()
        var fakeTime = 10_000L

        val detector = SnoreDetector(
            timeProvider = { fakeTime },
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, isApnea -> apneaResults.add(isApnea) }
        )

        val snoreFrame = createLowFreqFrame(amplitude = 3000) // > 54dB
        val silence = createSilenceFrame()

        // 1st snore: 10 frames (1000ms)
        for (i in 0 until 10) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }
        fakeTime += 100L
        detector.processFrame(silence, 1600)

        assertEquals(1, apneaResults.size)
        assertFalse("First snore should not be apnea suspect", apneaResults[0])

        // Advance simulated time by 25 seconds of silence (within 12s~60s apnea window)
        fakeTime += 25_000L
        detector.processFrame(silence, 1600)

        // 2nd snore: 10 frames (1000ms)
        for (i in 0 until 10) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }
        fakeTime += 100L
        detector.processFrame(silence, 1600)

        assertEquals(2, apneaResults.size)
        assertTrue("Second snore after 25s silence gap should be flagged as apnea suspect", apneaResults[1])
    }

    @Test
    fun testResetClearsApneaGapState() {
        val apneaResults = mutableListOf<Boolean>()
        var fakeTime = 10_000L

        val detector = SnoreDetector(
            timeProvider = { fakeTime },
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, isApnea -> apneaResults.add(isApnea) }
        )

        val snoreFrame = createLowFreqFrame(amplitude = 3000)
        val silence = createSilenceFrame()

        // 1st snore
        for (i in 0 until 10) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }
        fakeTime += 100L
        detector.processFrame(silence, 1600)
        assertEquals(1, apneaResults.size)

        // Simulate interruption (e.g. phone call focus loss -> resume -> reset)
        detector.reset()

        // Advance 25s
        fakeTime += 25_000L
        detector.processFrame(silence, 1600)

        // 2nd snore
        for (i in 0 until 10) {
            fakeTime += 100L
            detector.processFrame(snoreFrame, 1600)
        }
        fakeTime += 100L
        detector.processFrame(silence, 1600)

        assertEquals(2, apneaResults.size)
        assertFalse("Reset must clear lastSnoreEndTime so resuming doesn't false-trigger apnea", apneaResults[1])
    }
}
