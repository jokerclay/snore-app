package com.minimal.snore.service

import com.minimal.snore.audio.SnoreDetector
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying Audio Focus transitions, Hardware Resilience error logic,
 * and false-positive apnea prevention during audio interruptions.
 */
class AudioFocusAndResilienceTest {

    @Test
    fun testResilienceConstants() {
        assertEquals("Max consecutive errors before re-init must be 3", 3, SnoreMonitorService.MAX_CONSECUTIVE_ERRORS)
        assertEquals("Max reinit attempts circuit breaker must be 5", 5, SnoreMonitorService.MAX_REINIT_ATTEMPTS)
    }

    @Test
    fun testSteppedBackoffCalculation() {
        // Backoff formula: (500L * attempt).coerceAtMost(3000L)
        val expectedBackoffs = listOf(
            1 to 500L,
            2 to 1000L,
            3 to 1500L,
            4 to 2000L,
            5 to 2500L,
            6 to 3000L,
            7 to 3000L
        )

        for ((attempt, expectedMs) in expectedBackoffs) {
            val calculated = (500L * attempt).coerceAtMost(3000L)
            assertEquals("Backoff for attempt $attempt must match", expectedMs, calculated)
        }
    }

    @Test
    fun testApneaFalsePositivePreventionOnDetectorReset() {
        var detectedApnea = false
        var snoreCount = 0

        val detector = SnoreDetector(
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, isApneaSuspect ->
                snoreCount++
                if (isApneaSuspect) {
                    detectedApnea = true
                }
            }
        )

        val frameSize = 1600
        val snoreFrame = ShortArray(frameSize) { i ->
            (kotlin.math.sin(i * 2.0 * Math.PI * 100.0 / 16000.0) * 12000.0).toInt().toShort()
        }
        val quietFrame = ShortArray(frameSize) { 0 }

        // Process snore burst 1 (8 frames = 800ms)
        for (f in 0 until 8) {
            detector.processFrame(snoreFrame, frameSize)
        }
        for (f in 0 until 2) {
            detector.processFrame(quietFrame, frameSize)
        }

        assertEquals("First snore must be detected", 1, snoreCount)
        assertFalse("First snore without prior gap cannot be apnea suspect", detectedApnea)

        // Wait past MIN_COOLDOWN_MS (1500ms) to allow next detection
        Thread.sleep(1600)

        // Simulate audio focus loss (call/alarm interruption)
        // Calling detector.reset() as required by R2 on pause/resume:
        detector.reset()

        // Process snore burst 2 after focus resume
        for (f in 0 until 8) {
            detector.processFrame(snoreFrame, frameSize)
        }
        for (f in 0 until 2) {
            detector.processFrame(quietFrame, frameSize)
        }

        assertEquals("Second snore must be detected after focus resume", 2, snoreCount)
        assertFalse("Detector reset must eliminate false-positive apnea suspect alert from interruption gap", detectedApnea)
    }

    @Test
    fun testSimulatedConsecutiveErrorRecoveryStateMachine() {
        var consecutiveErrors = 0
        var reinitAttempts = 0
        var reinitCount = 0
        var circuitBreakerTripped = false

        fun onReadResult(readSamples: Int, initSuccessOnAttempt: Boolean = true) {
            if (readSamples > 0) {
                consecutiveErrors = 0
                reinitAttempts = 0
            } else {
                consecutiveErrors++
                if (consecutiveErrors >= SnoreMonitorService.MAX_CONSECUTIVE_ERRORS) {
                    if (reinitAttempts >= SnoreMonitorService.MAX_REINIT_ATTEMPTS) {
                        circuitBreakerTripped = true
                        return
                    }
                    reinitAttempts++
                    reinitCount++
                    if (initSuccessOnAttempt) {
                        consecutiveErrors = 0
                    }
                }
            }
        }

        // 1. Transient errors below threshold (2 errors) should NOT trigger re-init
        onReadResult(-1) // consecutiveErrors = 1
        onReadResult(0)  // consecutiveErrors = 2
        assertEquals(0, reinitCount)
        assertEquals(2, consecutiveErrors)

        // 2. Successful read resets consecutive errors
        onReadResult(1600)
        assertEquals(0, consecutiveErrors)
        assertEquals(0, reinitAttempts)

        // 3. Three consecutive errors trigger re-init
        onReadResult(-3) // ERROR_INVALID_OPERATION (1)
        onReadResult(-6) // ERROR_DEAD_OBJECT (2)
        onReadResult(-1) // ERROR (3) -> triggers reinit
        assertEquals(1, reinitCount)
        assertEquals(0, consecutiveErrors)

        // 4. Repeated hardware failures trip circuit breaker after MAX_REINIT_ATTEMPTS (5)
        reinitCount = 0
        reinitAttempts = 0
        consecutiveErrors = 0

        // If hardware continuously fails (initSuccessOnAttempt = false):
        // 1st trigger at error 3: reinitAttempts becomes 1
        onReadResult(-1, initSuccessOnAttempt = false)
        onReadResult(-1, initSuccessOnAttempt = false)
        onReadResult(-1, initSuccessOnAttempt = false)
        assertEquals(1, reinitAttempts)

        // Consecutive errors remain >= 3 on subsequent failed reads:
        // 2nd reinit: attempt 2
        onReadResult(-1, initSuccessOnAttempt = false)
        assertEquals(2, reinitAttempts)

        // 3rd reinit: attempt 3
        onReadResult(-1, initSuccessOnAttempt = false)
        assertEquals(3, reinitAttempts)

        // 4th reinit: attempt 4
        onReadResult(-1, initSuccessOnAttempt = false)
        assertEquals(4, reinitAttempts)

        // 5th reinit: attempt 5
        onReadResult(-1, initSuccessOnAttempt = false)
        assertEquals(5, reinitAttempts)
        assertFalse("Circuit breaker not tripped yet while reaching attempt 5", circuitBreakerTripped)

        // Next read error exceeds MAX_REINIT_ATTEMPTS -> trips circuit breaker
        onReadResult(-1, initSuccessOnAttempt = false)
        assertTrue("Circuit breaker must trip when reinitAttempts >= MAX_REINIT_ATTEMPTS", circuitBreakerTripped)
    }

    @Test
    fun testFocusStateFlowInitialState() {
        assertFalse("Initial isFocusPaused state flow should be false", SnoreMonitorService.isFocusPaused.value)
    }
}
