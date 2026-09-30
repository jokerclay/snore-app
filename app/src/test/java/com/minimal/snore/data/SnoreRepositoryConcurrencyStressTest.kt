package com.minimal.snore.data

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SnoreRepositoryConcurrencyStressTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var repository: SnoreRepository

    @Before
    fun setUp() {
        tempDir = File("build/tmp/repoConcurrencyTest_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): Context = this
        }
        repository = SnoreRepository(context)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * EMPIRICAL TEST: Concurrency safety under high contention.
     * 20 concurrent threads simultaneously performing:
     * - addEvent (total 200 events)
     * - saveSession (total 40 sessions)
     * - loadSessionsHistory (via reflection)
     * - updateStorageUsage
     *
     * Verifies:
     * 1. No deadlocks or race condition exceptions.
     * 2. Final event count in memory and in the JSON file equals exactly 200 (no lost updates).
     * 3. Valid JSON syntax in both recordsFile and sessionsHistoryFile.
     * 4. Session history ordering (descending by startTime) is strictly preserved.
     */
    @Test
    fun testHighContentionConcurrentAccess() {
        val threadCount = 20
        val eventsPerThread = 10
        val sessionsPerThread = 2
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)

        val unhandledExceptions = AtomicInteger(0)
        val loadSessionsHistoryMethod = SnoreRepository::class.java.getDeclaredMethod("loadSessionsHistory").apply {
            isAccessible = true
        }

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    startLatch.await()

                    // Concurrently add events
                    for (e in 0 until eventsPerThread) {
                        val event = SnoreEvent(
                            id = "event_t${threadId}_e$e",
                            timestamp = 1000000L + (threadId * 100) + e,
                            durationMs = 2500L,
                            peakDb = 55.0f,
                            audioFilePath = "",
                            isApneaSuspect = (e % 2 == 0)
                        )
                        repository.addEvent(event)

                        // Trigger concurrent reads & storage calculations
                        if (e % 3 == 0) {
                            loadSessionsHistoryMethod.invoke(repository)
                            repository.updateStorageUsage()
                            val currentEvents = repository.eventsFlow.value
                            assertTrue("Flow must not be null", currentEvents != null)
                        }
                    }

                    // Concurrently save sessions
                    for (s in 0 until sessionsPerThread) {
                        val session = SleepSession(
                            id = "session_t${threadId}_s$s",
                            startTime = 2000000L + (threadId * 10) + s,
                            endTime = 2000000L + (threadId * 10) + s + 3600000L,
                            totalMonitoringMs = 3600000L,
                            totalSnoreMs = 50000L,
                            snoreCount = 10,
                            maxDb = 65f,
                            avgDb = 48f,
                            lightSnoreCount = 5,
                            mediumSnoreCount = 3,
                            severeSnoreCount = 2,
                            apneaSuspectCount = 1,
                            hourlyDistribution = mapOf(1 to 5, 2 to 5)
                        )
                        repository.saveSession(session)
                    }

                } catch (ex: Throwable) {
                    ex.printStackTrace()
                    unhandledExceptions.incrementAndGet()
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        // Fire all threads simultaneously
        startLatch.countDown()
        val finishedInTime = finishLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("Execution should finish within 30 seconds without deadlock", finishedInTime)
        assertEquals("There should be zero unhandled exceptions under concurrency", 0, unhandledExceptions.get())

        // Verify Event Count
        val totalExpectedEvents = threadCount * eventsPerThread
        val finalEvents = repository.eventsFlow.value
        assertEquals("All $totalExpectedEvents concurrent events must be preserved in memory", totalExpectedEvents, finalEvents.size)

        // Verify JSON File Integrity
        val recordsFile = File(tempDir, "snore_events.json")
        assertTrue("snore_events.json must exist", recordsFile.exists())
        val recordsContent = recordsFile.readText()
        val jsonArray = JSONArray(recordsContent)
        assertEquals("snore_events.json must contain exactly $totalExpectedEvents items", totalExpectedEvents, jsonArray.length())

        // Verify all event IDs are unique and present
        val idSet = finalEvents.map { it.id }.toSet()
        assertEquals("All event IDs must be distinct (no clobbered writes)", totalExpectedEvents, idSet.size)

        // Verify Sessions History Integrity
        val totalExpectedSessions = threadCount * sessionsPerThread
        val finalSessions = repository.sessionsHistoryFlow.value
        assertEquals("All $totalExpectedSessions sessions must be recorded", totalExpectedSessions, finalSessions.size)

        val historyFile = File(tempDir, "sessions_history.json")
        assertTrue("sessions_history.json must exist", historyFile.exists())
        val historyArray = JSONArray(historyFile.readText())
        assertEquals("sessions_history.json must contain $totalExpectedSessions sessions", totalExpectedSessions, historyArray.length())

        // Verify Sessions are all present and valid
        val sessionIds = finalSessions.map { it.id }.toSet()
        assertEquals("All session IDs must be preserved", totalExpectedSessions, sessionIds.size)

        // After reload, verify loadSessionsHistory sorts by startTime DESC
        loadSessionsHistoryMethod.invoke(repository)
        val reloadedSessions = repository.sessionsHistoryFlow.value
        assertEquals("Reloaded sessions count must match", totalExpectedSessions, reloadedSessions.size)
        for (i in 0 until reloadedSessions.size - 1) {
            assertTrue(
                "Reloaded sessions must be sorted in descending order by startTime: ${reloadedSessions[i].startTime} >= ${reloadedSessions[i + 1].startTime}",
                reloadedSessions[i].startTime >= reloadedSessions[i + 1].startTime
            )
        }
    }


    /**
     * EMPIRICAL TEST: Simultaneous reads and writes while clearing.
     */
    @Test
    fun testConcurrentClearAndAdd() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val finishLatch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until 20) {
                        if (i == 10 && t == 0) {
                            repository.clearAll()
                        } else {
                            repository.addEvent(
                                SnoreEvent(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis(),
                                    durationMs = 1000L,
                                    peakDb = 50f,
                                    audioFilePath = "",
                                    isApneaSuspect = false
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        val completed = finishLatch.await(15, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Concurrent operations should complete without deadlock", completed)
        assertEquals("No errors during concurrent clear and add", 0, errors.get())
    }
}
