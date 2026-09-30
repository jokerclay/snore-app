package com.minimal.snore.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight, zero-external-dependency Acoustic Snore Detector.
 * Runs on 100ms frames (1600 samples at 16kHz).
 */
class SnoreDetector(
    private val onDecibelUpdate: (Float) -> Unit,
    private val onSnoreDetected: (peakDb: Float, durationMs: Long, isApneaSuspect: Boolean) -> Unit
) {
    // Dynamic noise floor tracking (starts around 32dB for a quiet bedroom)
    private var noiseFloor = 32.0f
    
    // Sensitivity threshold above ambient noise floor (dB)
    var triggerThresholdOffset = 12.0f

    // Burst tracking state
    private var inBurst = false
    private var burstStartFrameTime = 0L
    private var burstFrameCount = 0
    private var burstPeakDb = 0f
    private var lastTriggerTime = 0L
    private var lastSnoreEndTime = 0L

    companion object {
        private const val FRAME_DURATION_MS = 100L
        private const val MIN_SNORE_DURATION_MS = 600L  // Minimum 0.6 seconds
        private const val MAX_SNORE_DURATION_MS = 3500L // Maximum 3.5 seconds
        private const val MIN_COOLDOWN_MS = 1500L       // Cooldown between detections
    }

    /**
     * Process a 100ms audio chunk (1600 samples of 16-bit PCM).
     */
    fun processFrame(samples: ShortArray, length: Int) {
        if (length == 0) return

        // 1. Calculate RMS and Decibels
        var sumSquares = 0.0
        var zeroCrossings = 0
        var prevSign = samples[0] >= 0

        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sumSquares += s * s

            val currentSign = samples[i] >= 0
            if (currentSign != prevSign) {
                zeroCrossings++
                prevSign = currentSign
            }
        }

        val rms = sqrt(sumSquares / length)
        // Approximate SPL decibels (calibrated roughly for Android internal mic)
        val rawDb = if (rms > 1.0) 20.0 * log10(rms) else 0.0
        val currentDb = min(95.0f, max(20.0f, (rawDb * 1.08f).toFloat()))

        onDecibelUpdate(currentDb)

        // 2. Zero-crossing rate (ZCR)
        val zcr = zeroCrossings.toFloat() / length

        // 3. Dynamic ambient noise floor update
        // Only adapt when the sound is quiet (not an event)
        if (currentDb < noiseFloor + 5.0f) {
            noiseFloor = noiseFloor * 0.96f + currentDb * 0.04f
        }

        val currentTime = System.currentTimeMillis()
        val triggerLevel = max(42.0f, noiseFloor + triggerThresholdOffset)

        // Snore characteristics:
        // - Loudness significantly higher than background room noise
        // - Low ZCR (deep resonance, typical ZCR < 0.22, whereas hissing/friction > 0.35)
        val isAcousticCandidate = currentDb >= triggerLevel && zcr < 0.22f

        if (isAcousticCandidate) {
            if (!inBurst) {
                inBurst = true
                burstStartFrameTime = currentTime
                burstFrameCount = 1
                burstPeakDb = currentDb
            } else {
                burstFrameCount++
                if (currentDb > burstPeakDb) {
                    burstPeakDb = currentDb
                }
            }
        } else {
            if (inBurst) {
                // Sound has dropped back down. Check if the completed burst matches snore inhalation duration
                val burstDuration = burstFrameCount * FRAME_DURATION_MS
                val timeSinceLastTrigger = currentTime - lastTriggerTime

                if (burstDuration in MIN_SNORE_DURATION_MS..MAX_SNORE_DURATION_MS &&
                    timeSinceLastTrigger > MIN_COOLDOWN_MS
                ) {
                    lastTriggerTime = currentTime
                    // If there was a 12s~60s gap of silence after previous snore and this sound is loud, flag suspected apnea
                    val silenceGap = if (lastSnoreEndTime > 0L) currentTime - lastSnoreEndTime else 0L
                    val isApneaSuspect = (silenceGap in 12000L..60000L) && burstPeakDb > 54.0f
                    lastSnoreEndTime = currentTime

                    onSnoreDetected(burstPeakDb, burstDuration, isApneaSuspect)
                }

                inBurst = false
                burstFrameCount = 0
                burstPeakDb = 0f
            }
        }
    }

    fun reset() {
        inBurst = false
        burstFrameCount = 0
        burstPeakDb = 0f
        noiseFloor = 32.0f
        lastSnoreEndTime = 0L
    }
}
