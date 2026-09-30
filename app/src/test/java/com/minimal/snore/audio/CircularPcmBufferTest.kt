package com.minimal.snore.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CircularPcmBufferTest {

    @Test
    fun testEmptyBufferReturnsEmptyArray() {
        val buffer = CircularPcmBuffer(100)
        val samples = buffer.getRecentSamples(10)
        assertEquals(0, samples.size)
    }

    @Test
    fun testWriteLessThanCapacity() {
        val buffer = CircularPcmBuffer(10)
        val data = shortArrayOf(1, 2, 3, 4, 5)
        buffer.write(data, 5)

        val retrievedAll = buffer.getRecentSamples(10)
        assertEquals(5, retrievedAll.size)
        assertArrayEquals(data, retrievedAll)

        val retrievedPartial = buffer.getRecentSamples(3)
        assertEquals(3, retrievedPartial.size)
        assertArrayEquals(shortArrayOf(3, 4, 5), retrievedPartial)
    }

    @Test
    fun testWriteExactCapacity() {
        val buffer = CircularPcmBuffer(5)
        val data = shortArrayOf(10, 20, 30, 40, 50)
        buffer.write(data, 5)

        val retrieved = buffer.getRecentSamples(5)
        assertEquals(5, retrieved.size)
        assertArrayEquals(data, retrieved)
    }

    @Test
    fun testWrapAroundOverwritingOldData() {
        val buffer = CircularPcmBuffer(5)
        // Write 4 items
        buffer.write(shortArrayOf(1, 2, 3, 4), 4)
        // Write 3 more items: 5, 6, 7 (total 7 items into capacity 5, oldest 1, 2 should be overwritten)
        buffer.write(shortArrayOf(5, 6, 7), 3)

        val retrieved = buffer.getRecentSamples(5)
        assertEquals(5, retrieved.size)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), retrieved)

        // Retrieve subset
        val recent2 = buffer.getRecentSamples(2)
        assertArrayEquals(shortArrayOf(6, 7), recent2)
    }

    @Test
    fun testMultipleFullRotations() {
        val buffer = CircularPcmBuffer(4)
        for (i in 1..25) {
            buffer.write(shortArrayOf(i.toShort()), 1)
        }
        // Most recent 4 should be 22, 23, 24, 25
        val retrieved = buffer.getRecentSamples(10)
        assertEquals(4, retrieved.size)
        assertArrayEquals(shortArrayOf(22, 23, 24, 25), retrieved)
    }

    @Test
    fun testConcurrentWriteAndRead() {
        val capacity = 1000
        val buffer = CircularPcmBuffer(capacity)
        val iterations = 5000
        val latch = CountDownLatch(2)
        var readException: Throwable? = null

        thread {
            try {
                val chunk = ShortArray(50) { it.toShort() }
                for (i in 0 until iterations) {
                    buffer.write(chunk, chunk.size)
                }
            } finally {
                latch.countDown()
            }
        }

        thread {
            try {
                for (i in 0 until iterations) {
                    val samples = buffer.getRecentSamples(100)
                    assertTrue(samples.size <= 100)
                }
            } catch (t: Throwable) {
                readException = t
            } finally {
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNull(readException)
    }
}
