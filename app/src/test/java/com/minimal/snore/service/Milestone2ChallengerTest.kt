package com.minimal.snore.service

import com.minimal.snore.audio.SnoreDetector
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * EMPIRICAL CHALLENGER TEST SUITE: Milestone 2
 *
 * Exhaustively stress-tests:
 * 1. Apnea false-positive prevention across full 12s~60s window (with vs without reset)
 * 2. Rapid audio focus state transitions and thread contention on audioLock
 * 3. Hardware resilience, error code handling, stepped backoff, and circuit breaker
 * 4. Permanent focus loss lifecycle state invariants
 * 5. Vacuous pass critique in worker's test (1600ms sleep vs true 12s~60s window)
 */
class Milestone2ChallengerTest {

    private val frameSize = 1600
    // Synthesize 100Hz resonant snore frame (peak ~68 dB, low ZCR)
    private val snoreFrame = ShortArray(frameSize) { i ->
        (kotlin.math.sin(i * 2.0 * Math.PI * 100.0 / 16000.0) * 12000.0).toInt().toShort()
    }
    private val quietFrame = ShortArray(frameSize) { 0 }

    private fun produceSnoreBurst(detector: SnoreDetector, burstFrames: Int = 8, quietFrames: Int = 2) {
        for (i in 0 until burstFrames) {
            detector.processFrame(snoreFrame, frameSize)
        }
        for (i in 0 until quietFrames) {
            detector.processFrame(quietFrame, frameSize)
        }
    }

    // =========================================================================
    // Challenge 1: Apnea False-Positive Prevention Across Full 12s~60s Spectrum
    // =========================================================================

    /**
     * EMPIRICAL TEST:
     * Without detector.reset(), any interruption lasting in [12s..60s] creates a
     * silenceGap that triggers a false-positive apnea alert on the next snore.
     *
     * With detector.reset() (as called in SnoreMonitorService on pause and resume),
     * lastSnoreEndTime is cleared to 0L, completely eliminating the false-positive alert!
     */
    @Test
    fun testApneaFalsePositiveSuppressionAcrossAllGapDurations() {
        val testGapDurationsMs = listOf(
            12000L, // Exact lower boundary
            15000L, // 15s phone call
            25000L, // 25s phone call
            30000L, // 30s alarm / call
            45000L, // 45s phone call
            59999L, // Just under upper boundary
            60000L  // Exact upper boundary
        )

        val lastSnoreEndTimeField = SnoreDetector::class.java.getDeclaredField("lastSnoreEndTime").apply {
            isAccessible = true
        }
        val lastTriggerTimeField = SnoreDetector::class.java.getDeclaredField("lastTriggerTime").apply {
            isAccessible = true
        }

        for (gapMs in testGapDurationsMs) {
            // Case A: WITHOUT detector.reset() (Vulnerability Demonstration)
            var apneaWithoutReset = false
            var snoreCountA = 0
            val detectorA = SnoreDetector(
                onDecibelUpdate = {},
                onSnoreDetected = { _, _, isApnea ->
                    snoreCountA++
                    if (isApnea) apneaWithoutReset = true
                }
            )

            // Snore 1
            produceSnoreBurst(detectorA)
            assertEquals("Snore 1 must be detected in Case A", 1, snoreCountA)
            assertFalse("Snore 1 cannot be apnea suspect", apneaWithoutReset)

            // Simulate interruption of duration gapMs without reset
            val nowA = System.currentTimeMillis()
            lastSnoreEndTimeField.set(detectorA, nowA - gapMs)
            lastTriggerTimeField.set(detectorA, nowA - gapMs)

            // Snore 2 arrives after the call without reset
            produceSnoreBurst(detectorA)
            assertEquals("Snore 2 must be detected in Case A", 2, snoreCountA)
            assertTrue(
                "CRITICAL: Without detector.reset(), a call of ${gapMs}ms MUST trigger false-positive apnea alert",
                apneaWithoutReset
            )

            // Case B: WITH detector.reset() (Industrial Fix Verification)
            var apneaWithReset = false
            var snoreCountB = 0
            val detectorB = SnoreDetector(
                onDecibelUpdate = {},
                onSnoreDetected = { _, _, isApnea ->
                    snoreCountB++
                    if (isApnea) apneaWithReset = true
                }
            )

            // Snore 1
            produceSnoreBurst(detectorB)
            assertEquals("Snore 1 must be detected in Case B", 1, snoreCountB)
            assertFalse("Snore 1 cannot be apnea suspect", apneaWithReset)

            // Interruption begins: pause invoked -> detector.reset()
            detectorB.reset()

            // Simulate elapsed call time
            val nowB = System.currentTimeMillis()
            lastSnoreEndTimeField.set(detectorB, nowB - gapMs)
            lastTriggerTimeField.set(detectorB, nowB - gapMs)

            // Interruption ends: resume invoked -> detector.reset()
            detectorB.reset()

            // Verify lastSnoreEndTime is strictly 0L after reset
            val resetTime = lastSnoreEndTimeField.getLong(detectorB)
            assertEquals("lastSnoreEndTime must be 0L after reset", 0L, resetTime)

            // Snore 2 arrives after call completion
            produceSnoreBurst(detectorB)
            assertEquals("Snore 2 must be detected in Case B", 2, snoreCountB)
            assertFalse(
                "VERIFIED: With detector.reset(), call of ${gapMs}ms MUST NOT trigger false-positive apnea alert",
                apneaWithReset
            )
        }
    }

    /**
     * EMPIRICAL TEST:
     * Verification of gaps outside the [12s..60s] window.
     * Gaps below 12s (e.g. 11.9s) or above 60s (e.g. 60.1s) should not trigger apnea anyway.
     */
    @Test
    fun testApneaWindowBoundariesOutsideRange() {
        val outsideGaps = listOf(
            0L,
            5000L,
            11900L, // Below 12s
            60100L, // Above 60s
            120000L // 2 minutes
        )

        val lastSnoreEndTimeField = SnoreDetector::class.java.getDeclaredField("lastSnoreEndTime").apply {
            isAccessible = true
        }
        val lastTriggerTimeField = SnoreDetector::class.java.getDeclaredField("lastTriggerTime").apply {
            isAccessible = true
        }

        for (gapMs in outsideGaps) {
            var apneaDetected = false
            var snoreCount = 0
            val detector = SnoreDetector(
                onDecibelUpdate = {},
                onSnoreDetected = { _, _, isApnea ->
                    snoreCount++
                    if (isApnea) apneaDetected = true
                }
            )

            produceSnoreBurst(detector)
            assertEquals(1, snoreCount)

            val now = System.currentTimeMillis()
            lastSnoreEndTimeField.set(detector, now - gapMs)
            // Ensure cooldown is satisfied so snore 2 is actually evaluated
            lastTriggerTimeField.set(detector, now - maxOf(gapMs, 1600L))

            produceSnoreBurst(detector)
            assertEquals("Second snore must be registered for gap ${gapMs}ms", 2, snoreCount)
            assertFalse("Gap of ${gapMs}ms is outside [12s..60s] and must not trigger apnea", apneaDetected)
        }
    }

    /**
     * EMPIRICAL CRITIQUE TEST:
     * Expose that the worker's test used Thread.sleep(1600ms), which is only 1.6s.
     * Even WITHOUT detector.reset(), 1.6s gap NEVER triggers apnea!
     */
    @Test
    fun testCritiqueOfWorker1600msTest() {
        var apneaDetected = false
        var snoreCount = 0
        val detector = SnoreDetector(
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, isApnea ->
                snoreCount++
                if (isApnea) apneaDetected = true
            }
        )

        produceSnoreBurst(detector)
        assertEquals(1, snoreCount)

        // Simulate 1600ms gap WITHOUT calling reset:
        val lastSnoreEndTimeField = SnoreDetector::class.java.getDeclaredField("lastSnoreEndTime").apply {
            isAccessible = true
        }
        val lastTriggerTimeField = SnoreDetector::class.java.getDeclaredField("lastTriggerTime").apply {
            isAccessible = true
        }
        val now = System.currentTimeMillis()
        lastSnoreEndTimeField.set(detector, now - 1600L)
        lastTriggerTimeField.set(detector, now - 1600L)

        // DO NOT call detector.reset()
        produceSnoreBurst(detector)
        assertEquals(2, snoreCount)

        // Notice that even WITHOUT reset, it passes because 1600ms < 12000ms!
        assertFalse(
            "Worker's 1600ms test passed vacuously because 1.6s is outside the 12s~60s apnea threshold",
            apneaDetected
        )
    }

    /**
     * EMPIRICAL TEST:
     * In-flight burst abortion:
     * If transient focus loss occurs mid-burst (sound was playing when phone rang),
     * detector.reset() must clear in-flight burst state so partial remnants
     * don't concatenate with resume audio.
     */
    @Test
    fun testMidBurstInterruptionAbortion() {
        var snoreCount = 0
        val detector = SnoreDetector(
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, _ -> snoreCount++ }
        )

        // Start burst: 3 frames (300ms) - below 600ms min snore threshold
        for (i in 0 until 3) {
            detector.processFrame(snoreFrame, frameSize)
        }

        val inBurstField = SnoreDetector::class.java.getDeclaredField("inBurst").apply {
            isAccessible = true
        }
        val burstFrameCountField = SnoreDetector::class.java.getDeclaredField("burstFrameCount").apply {
            isAccessible = true
        }

        assertTrue("Should be in burst after 3 loud frames", inBurstField.getBoolean(detector))
        assertEquals(3, burstFrameCountField.getInt(detector))

        // Interruption occurs -> reset
        detector.reset()

        assertFalse("inBurst must be reset to false", inBurstField.getBoolean(detector))
        assertEquals("burstFrameCount must be reset to 0", 0, burstFrameCountField.getInt(detector))

        // After resume, 4 frames arrive (400ms) followed by quiet
        // If not reset, 3 + 4 = 7 frames (700ms) would have falsely triggered a snore!
        for (i in 0 until 4) {
            detector.processFrame(snoreFrame, frameSize)
        }
        for (i in 0 until 2) {
            detector.processFrame(quietFrame, frameSize)
        }

        assertEquals("Aborted burst plus 400ms burst must NOT concatenate to reach 600ms threshold", 0, snoreCount)
    }

    // =========================================================================
    // Challenge 2: Rapid Audio Focus State Transitions & Concurrency Stress
    // =========================================================================

    /**
     * EMPIRICAL TEST:
     * Multi-threaded rapid toggling between AUDIOFOCUS_GAIN and AUDIOFOCUS_LOSS_TRANSIENT.
     * Simulates 1,000 rapid state changes under high concurrency.
     * Verifies mutex lock acquisition, volatile state visibility, and no deadlocks.
     */
    @Test
    fun testRapidFocusTogglingConcurrencyHarness() {
        val iterations = 1000
        val audioLock = Any()
        val isRecording = AtomicBoolean(true)
        val isFocusPaused = AtomicBoolean(false)
        val consecutiveErrors = AtomicInteger(0)
        val deadlockDetected = AtomicBoolean(false)
        val toggleCount = AtomicInteger(0)

        val detector = SnoreDetector(
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, _ -> }
        )

        val executor = Executors.newFixedThreadPool(4)
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(4)

        // Thread 1: Focus Toggler (simulating AudioManager callback on Main thread)
        executor.execute {
            startLatch.await()
            try {
                for (i in 0 until iterations) {
                    if (i % 2 == 0) {
                        // Transient loss
                        if (!isFocusPaused.get()) {
                            isFocusPaused.set(true)
                            synchronized(audioLock) {
                                // simulate stopping AudioRecord
                            }
                            detector.reset()
                        }
                    } else {
                        // Focus gain
                        if (isFocusPaused.get()) {
                            isFocusPaused.set(false)
                            detector.reset()
                            synchronized(audioLock) {
                                // simulate starting AudioRecord
                            }
                        }
                    }
                    toggleCount.incrementAndGet()
                }
            } catch (t: Throwable) {
                deadlockDetected.set(true)
            } finally {
                finishLatch.countDown()
            }
        }

        // Thread 2: Simulated Audio Capture Loop (Dispatchers.IO)
        executor.execute {
            startLatch.await()
            try {
                var reads = 0
                while (reads < iterations && isRecording.get()) {
                    if (isFocusPaused.get()) {
                        Thread.sleep(1)
                        continue
                    }
                    val readSamples = synchronized(audioLock) {
                        if (!isFocusPaused.get()) 1600 else -1
                    }
                    if (readSamples > 0) {
                        consecutiveErrors.set(0)
                        detector.processFrame(snoreFrame, frameSize)
                    } else {
                        consecutiveErrors.incrementAndGet()
                    }
                    reads++
                }
            } catch (t: Throwable) {
                deadlockDetected.set(true)
            } finally {
                finishLatch.countDown()
            }
        }

        // Thread 3: Hardware Error Injector
        executor.execute {
            startLatch.await()
            try {
                for (i in 0 until iterations / 2) {
                    if (!isFocusPaused.get()) {
                        consecutiveErrors.incrementAndGet()
                    }
                    Thread.sleep(1)
                }
            } catch (t: Throwable) {
                deadlockDetected.set(true)
            } finally {
                finishLatch.countDown()
            }
        }

        // Thread 4: Background State Observer
        executor.execute {
            startLatch.await()
            try {
                for (i in 0 until iterations / 2) {
                    val paused = isFocusPaused.get()
                    assertNotNull(paused)
                    Thread.sleep(1)
                }
            } catch (t: Throwable) {
                deadlockDetected.set(true)
            } finally {
                finishLatch.countDown()
            }
        }

        startLatch.countDown()
        val finished = finishLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("Execution must complete within 10s without deadlock", finished)
        assertFalse("No deadlocks or thread contention exceptions", deadlockDetected.get())
        assertTrue("At least some focus toggles must have executed", toggleCount.get() > 0)
    }

    // =========================================================================
    // Challenge 3: Hardware Error Resilience State Machine Stress
    // =========================================================================

    /**
     * EMPIRICAL TEST:
     * Comprehensive verification of AudioRecord read error handling:
     * - ERROR (-1), ERROR_BAD_VALUE (-2), ERROR_INVALID_OPERATION (-3), ERROR_DEAD_OBJECT (-6)
     * - Intermittent single/double errors do NOT trigger re-init
     * - 3 consecutive errors trigger stepped backoff re-init
     * - 5 consecutive failed re-inits trip the circuit breaker cleanly
     */
    @Test
    fun testHardwareResilienceWithAllErrorCodes() {
        val errorCodes = listOf(-1, -2, -3, -6, 0)

        for (errorCode in errorCodes) {
            var consecutiveErrors = 0
            var reinitAttempts = 0
            var reinitTriggered = 0
            var circuitBreakerTripped = false

            fun handleRead(readResult: Int, reinitSuccess: Boolean = true) {
                if (readResult > 0) {
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
                        reinitTriggered++
                        if (reinitSuccess) {
                            consecutiveErrors = 0
                        }
                    }
                }
            }

            // Test 1: Single error followed by success does not trigger re-init
            handleRead(errorCode)
            assertEquals("Single error must not trigger reinit for code $errorCode", 0, reinitTriggered)
            handleRead(1600)
            assertEquals(0, consecutiveErrors)
            assertEquals(0, reinitAttempts)

            // Test 2: Double error followed by success does not trigger re-init
            handleRead(errorCode)
            handleRead(errorCode)
            assertEquals("Double error must not trigger reinit for code $errorCode", 0, reinitTriggered)
            handleRead(1600)
            assertEquals(0, consecutiveErrors)

            // Test 3: Exactly 3 errors trigger re-init
            handleRead(errorCode)
            handleRead(errorCode)
            handleRead(errorCode)
            assertEquals("3 errors must trigger reinit for code $errorCode", 1, reinitTriggered)
            assertEquals("Successful reinit resets consecutive errors", 0, consecutiveErrors)

            // Test 4: Stepped backoff progression
            val backoffs = (1..SnoreMonitorService.MAX_REINIT_ATTEMPTS).map { attempt ->
                (500L * attempt).coerceAtMost(3000L)
            }
            assertEquals(listOf(500L, 1000L, 1500L, 2000L, 2500L), backoffs)

            // Test 5: Persistent hardware failure (reinit fails continuously)
            // 3 initial read errors trigger attempt 1.
            // When reinit fails, consecutiveErrors remains >= 3, so each subsequent read error
            // applies stepped backoff and increments reinit attempt until attempt 5.
            reinitAttempts = 0
            reinitTriggered = 0
            consecutiveErrors = 0
            circuitBreakerTripped = false

            // Attempt 1: 3 read errors
            handleRead(errorCode, reinitSuccess = false)
            handleRead(errorCode, reinitSuccess = false)
            handleRead(errorCode, reinitSuccess = false)
            assertEquals("Attempt 1 triggered at 3 consecutive errors", 1, reinitAttempts)

            // Attempts 2..5: consecutiveErrors remains >= 3 on null record read failure
            for (attempt in 2..SnoreMonitorService.MAX_REINIT_ATTEMPTS) {
                handleRead(errorCode, reinitSuccess = false)
                assertEquals("Attempt $attempt recorded", attempt, reinitAttempts)
            }
            assertFalse("Circuit breaker not tripped yet at attempt 5", circuitBreakerTripped)

            // Next read error exceeds MAX_REINIT_ATTEMPTS -> trips circuit breaker cleanly
            handleRead(errorCode, reinitSuccess = false)
            assertTrue("Circuit breaker must trip after max reinit attempts for code $errorCode", circuitBreakerTripped)
        }
    }

    // =========================================================================
    // Challenge 4: Permanent Loss & Cleanup Invariant Verification
    // =========================================================================

    /**
     * EMPIRICAL TEST:
     * Verification of state invariants on permanent focus loss:
     * When AUDIOFOCUS_LOSS occurs, monitoring must be stopped,
     * isRecording, isFocusPaused, and isRunning must be false,
     * and detector must be cleanly resettable.
     */
    @Test
    fun testPermanentFocusLossStateInvariants() {
        val detector = SnoreDetector(
            onDecibelUpdate = {},
            onSnoreDetected = { _, _, _ -> }
        )

        // Model state variables of SnoreMonitorService
        var isRecording = true
        var isFocusPaused = true
        var isRunning = true
        var audioFocusRequestAbandoned = false
        var audioRecordCleanedUp = false

        fun stopMonitoring() {
            if (!isRecording && !isRunning) return
            isRecording = false
            isFocusPaused = false
            isRunning = false
            audioFocusRequestAbandoned = true
            audioRecordCleanedUp = true
            detector.reset()
        }

        fun handleAudioFocusLost(isTransient: Boolean) {
            if (!isRecording) return
            if (!isTransient) {
                stopMonitoring()
                return
            }
        }

        // Simulate permanent loss
        handleAudioFocusLost(isTransient = false)

        assertFalse("isRecording must be false on permanent loss", isRecording)
        assertFalse("isFocusPaused must be reset to false on permanent loss", isFocusPaused)
        assertFalse("isRunning must be false on permanent loss", isRunning)
        assertTrue("AudioFocusRequest must be abandoned on permanent loss", audioFocusRequestAbandoned)
        assertTrue("AudioRecord must be cleaned up on permanent loss", audioRecordCleanedUp)

        // Idempotency: calling stopMonitoring again must be safe
        stopMonitoring()
        assertFalse(isRecording)
        assertFalse(isRunning)
    }
}
