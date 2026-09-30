package com.minimal.snore.data

import android.content.Context
import android.content.ContextWrapper
import androidx.core.util.AtomicFile
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class AtomicPersistenceRecoveryTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var repository: SnoreRepository

    @Before
    fun setUp() {
        tempDir = File("build/tmp/atomicRecoveryTest_${System.currentTimeMillis()}").apply {
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
     * EMPIRICAL TEST: Interrupted write simulation with corrupted base file.
     * When a process is killed or power fails mid-write:
     * - base file has corrupted / partial / 0-byte content
     * - .bak file contains the last known good snapshot
     *
     * Verifies that repository automatically rolls back to the .bak snapshot
     * without throwing exceptions or losing the historical data.
     */
    @Test
    fun testRecoveryFromCorruptedBaseFileWithValidBak() {
        // Step 1: Save valid event
        val validEvent = SnoreEvent(
            id = "valid_event_1",
            timestamp = 1000L,
            durationMs = 2000L,
            peakDb = 55.0f,
            audioFilePath = "some/path.wav",
            isApneaSuspect = false
        )
        repository.addEvent(validEvent)
        assertEquals(1, repository.eventsFlow.value.size)

        val recordsFile = File(tempDir, "snore_events.json")
        val backupFile = File(tempDir, "snore_events.json.bak")
        assertTrue("Base file must exist", recordsFile.exists())

        // Step 2: Simulate power kill mid-transaction
        // Copy valid base file to .bak, and corrupt the base file with partial / 0-byte data
        recordsFile.copyTo(backupFile, overwrite = true)
        recordsFile.writeText("{\"corrupted_json_incomplete: [")

        // Step 3: Instantiate a new repository (app relaunch simulation)
        val newRepo = SnoreRepository(context)

        // Verifies that AtomicFile.openRead() recovered from .bak
        val recoveredEvents = newRepo.eventsFlow.value
        assertEquals("Repository must safely recover from .bak file", 1, recoveredEvents.size)
        assertEquals("valid_event_1", recoveredEvents[0].id)
        assertEquals(55.0f, recoveredEvents[0].peakDb)

        // Verify base file was restored to valid content
        assertTrue("Base file must be restored", recordsFile.exists())
        val restoredJson = JSONArray(recordsFile.readText())
        assertEquals(1, restoredJson.length())
    }

    /**
     * EMPIRICAL TEST: Power-cut immediately after startWrite renamed base file to .bak.
     * In this scenario:
     * - base file does not exist at all
     * - .bak file contains the complete valid data
     */
    @Test
    fun testRecoveryWhenBaseFileIsMissingAndOnlyBakExists() {
        val validEvent = SnoreEvent(
            id = "event_before_power_loss",
            timestamp = 2000L,
            durationMs = 3000L,
            peakDb = 60.0f,
            audioFilePath = "",
            isApneaSuspect = true
        )
        repository.addEvent(validEvent)

        val recordsFile = File(tempDir, "snore_events.json")
        val backupFile = File(tempDir, "snore_events.json.bak")

        // Simulate base file renamed to .bak and power cut before new file is created
        recordsFile.renameTo(backupFile)
        assertFalse("Base file does not exist", recordsFile.exists())
        assertTrue("Backup file exists", backupFile.exists())

        // App relaunch
        val newRepo = SnoreRepository(context)
        val recovered = newRepo.eventsFlow.value
        assertEquals("Must restore from .bak when base file is missing", 1, recovered.size)
        assertEquals("event_before_power_loss", recovered[0].id)
        assertTrue(recordsFile.exists())
    }

    /**
     * EMPIRICAL TEST: Simulated disk failure / exception during write.
     * Verifies that when write fails, failWrite restores the intact base file
     * and leaves no dangling partial file.
     */
    @Test
    fun testWriteAtomicFailureRollback() {
        val testFile = File(tempDir, "test_atomic_rollback.json")
        testFile.writeText("{\"status\": \"original_intact\"}")

        val atomicFile = AtomicFile(testFile)
        val fos = atomicFile.startWrite()
        fos.write("{\"status\": \"partial_uncommitted\"".toByteArray(Charsets.UTF_8))

        // Simulate write failure: failWrite is called
        atomicFile.failWrite(fos)

        // Verifies the file rolled back to the original intact content
        assertEquals("{\"status\": \"original_intact\"}", testFile.readText())
        val bakFile = File(tempDir, "test_atomic_rollback.json.bak")
        assertFalse("Backup file should be restored back to base file", bakFile.exists())
    }

    /**
     * EMPIRICAL TEST: clearAll cleanly deletes base file and backup file.
     */
    @Test
    fun testClearAllDeletesSessionFileAndBackup() {
        val session = SleepSession(
            id = "sess_1",
            startTime = 1000L,
            endTime = 2000L,
            totalMonitoringMs = 1000L,
            totalSnoreMs = 500L,
            snoreCount = 2,
            maxDb = 60f,
            avgDb = 45f,
            lightSnoreCount = 1,
            mediumSnoreCount = 1,
            severeSnoreCount = 0,
            apneaSuspectCount = 0,
            hourlyDistribution = emptyMap()
        )
        repository.saveSession(session)

        val sessionFile = File(tempDir, "last_session.json")
        val sessionBak = File(tempDir, "last_session.json.bak")
        assertTrue(sessionFile.exists())

        // Create a simulated leftover .bak file
        sessionFile.copyTo(sessionBak, overwrite = true)
        assertTrue(sessionBak.exists())

        repository.clearAll()

        assertFalse("last_session.json must be deleted", sessionFile.exists())
        assertFalse("last_session.json.bak must be deleted", sessionBak.exists())
        assertEquals(0, repository.sessionsHistoryFlow.value.size)
        assertNull(repository.latestSessionFlow.value)
    }
}
