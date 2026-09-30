package com.minimal.snore.audio

/**
 * Ring buffer for short samples (16-bit PCM).
 * Keeps the most recent [capacity] samples in memory.
 */
class CircularPcmBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var writePos = 0
    private var isFull = false
    private val lock = Any()

    fun write(data: ShortArray, length: Int) {
        synchronized(lock) {
            for (i in 0 until length) {
                buffer[writePos] = data[i]
                writePos = (writePos + 1) % capacity
                if (writePos == 0) {
                    isFull = true
                }
            }
        }
    }

    /**
     * Extracts the most recent [sampleCount] samples in chronological order.
     */
    fun getRecentSamples(sampleCount: Int): ShortArray {
        synchronized(lock) {
            val count = sampleCount.coerceAtMost(if (isFull) capacity else writePos)
            val result = ShortArray(count)
            var startPos = (writePos - count + capacity) % capacity
            for (i in 0 until count) {
                result[i] = buffer[startPos]
                startPos = (startPos + 1) % capacity
            }
            return result
        }
    }
}
