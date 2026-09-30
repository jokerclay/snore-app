package com.minimal.snore.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.minimal.snore.R
import com.minimal.snore.SnoreApplication
import com.minimal.snore.audio.CircularPcmBuffer
import com.minimal.snore.audio.SnoreDetector
import com.minimal.snore.audio.WavWriter
import com.minimal.snore.data.SnoreEvent
import com.minimal.snore.data.SnoreRepository
import com.minimal.snore.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class SnoreMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    // 5 seconds of 16kHz PCM buffer (16000 samples/sec * 5s = 80,000 samples)
    private val circularBuffer = CircularPcmBuffer(80000)
    private lateinit var detector: SnoreDetector
    private lateinit var repository: SnoreRepository

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _liveDb = MutableStateFlow(25f)
        val liveDb: StateFlow<Float> = _liveDb.asStateFlow()

        private val _snoreCount = MutableStateFlow(0)
        val snoreCount: StateFlow<Int> = _snoreCount.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SnoreMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SnoreMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = SnoreRepository.getInstance(applicationContext)

        detector = SnoreDetector(
            onDecibelUpdate = { db ->
                _liveDb.value = db
            },
            onSnoreDetected = { peakDb, durationMs ->
                onSnoreFound(peakDb, durationMs)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isRecording) return

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("正在监测睡眠声音..."))

        _isRunning.value = true
        _snoreCount.value = 0
        isRecording = true
        detector.reset()

        serviceScope.launch(Dispatchers.IO) {
            recordAudioLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordAudioLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val frameSize = 1600 // 100ms at 16kHz
        val bufferSize = maxOf(minBufferSize, frameSize * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                stopMonitoring()
                return
            }

            audioRecord?.startRecording()
            val chunk = ShortArray(frameSize)

            while (isRecording && isActive) {
                val readSamples = audioRecord?.read(chunk, 0, frameSize) ?: -1
                if (readSamples > 0) {
                    circularBuffer.write(chunk, readSamples)
                    detector.processFrame(chunk, readSamples)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cleanupAudio()
        }
    }

    private fun onSnoreFound(peakDb: Float, durationMs: Long) {
        val timestamp = System.currentTimeMillis()
        _snoreCount.value += 1

        // Capture last 5 seconds (80,000 samples)
        val audioSamples = circularBuffer.getRecentSamples(80000)
        val audioDir = File(filesDir, "snore_audio").apply { if (!exists()) mkdirs() }
        val audioFile = File(audioDir, "snore_${timestamp}.wav")

        serviceScope.launch(Dispatchers.IO) {
            WavWriter.writeWavFile(audioFile, audioSamples, 16000)
            val event = SnoreEvent(
                id = UUID.randomUUID().toString(),
                timestamp = timestamp,
                durationMs = durationMs,
                peakDb = peakDb,
                audioFilePath = audioFile.absolutePath
            )
            repository.addEvent(event)
            updateNotification("监测中：已检测到 ${_snoreCount.value} 次打呼噜")
        }
    }

    private fun stopMonitoring() {
        isRecording = false
        _isRunning.value = false
        cleanupAudio()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupAudio() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnoreApp:WakeLockTag").apply {
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SnoreMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, SnoreApplication.CHANNEL_ID)
            .setContentTitle("睡眠打鼾监测中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束监测", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopMonitoring()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
