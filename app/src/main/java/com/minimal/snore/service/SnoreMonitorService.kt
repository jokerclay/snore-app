package com.minimal.snore.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import com.minimal.snore.R
import com.minimal.snore.SnoreApplication
import com.minimal.snore.audio.CircularPcmBuffer
import com.minimal.snore.audio.SnoreDetector
import com.minimal.snore.audio.WavWriter
import com.minimal.snore.audio.YamnetClassifier
import com.minimal.snore.data.AppSettings
import com.minimal.snore.data.SleepSession
import com.minimal.snore.data.SnoreEvent
import com.minimal.snore.data.SnoreRepository
import com.minimal.snore.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Calendar
import java.util.UUID

class SnoreMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    // Audio recording synchronization and resilience
    private val audioLock = Any()
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var isFocusPaused = false

    // Audio Focus Management
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    internal val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                handleAudioFocusGained()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                handleAudioFocusLost(isTransient = true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                handleAudioFocusLost(isTransient = false)
            }
        }
    }

    private var sessionStartTime = 0L
    private val sessionEvents = mutableListOf<SnoreEvent>()

    // 5 seconds of 16kHz PCM buffer (16000 samples/sec * 5s = 80,000 samples)
    private val circularBuffer = CircularPcmBuffer(80000)
    private lateinit var detector: SnoreDetector
    private lateinit var repository: SnoreRepository
    private var yamnetClassifier: YamnetClassifier? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "SnoreMonitorService"
        const val LOW_STORAGE_THRESHOLD_BYTES = 100L * 1024L * 1024L // 100MB

        const val MAX_CONSECUTIVE_ERRORS = 3
        const val MAX_REINIT_ATTEMPTS = 5

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isFocusPaused = MutableStateFlow(false)
        val isFocusPaused: StateFlow<Boolean> = _isFocusPaused.asStateFlow()

        private val _isLowStorage = MutableStateFlow(false)
        val isLowStorage: StateFlow<Boolean> = _isLowStorage.asStateFlow()

        private val _liveDb = MutableStateFlow(25f)
        val liveDb: StateFlow<Float> = _liveDb.asStateFlow()

        private val _snoreCount = MutableStateFlow(0)
        val snoreCount: StateFlow<Int> = _snoreCount.asStateFlow()

        private val _apneaCount = MutableStateFlow(0)
        val apneaCount: StateFlow<Int> = _apneaCount.asStateFlow()

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
        yamnetClassifier = YamnetClassifier(applicationContext)
        val settings = AppSettings.getInstance(applicationContext)

        detector = SnoreDetector(
            calibrationOffset = settings.micCalibration.offsetDb,
            onDecibelUpdate = { db ->
                _liveDb.value = db
            },
            onSnoreDetected = { peakDb, durationMs, isApneaSuspect ->
                onSnoreFound(peakDb, durationMs, isApneaSuspect)
            }
        )
    }

    fun updateCalibration(offsetDb: Float) {
        if (::detector.isInitialized) {
            detector.calibrationOffset = offsetDb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    fun isLowStorage(): Boolean {
        return try {
            val stat = StatFs(filesDir.absolutePath)
            stat.availableBytes < LOW_STORAGE_THRESHOLD_BYTES
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    internal fun requestAudioFocus(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            audioManager = am

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioFocusRequest = request
            val result = am.requestAudioFocus(request)
            Log.i(TAG, "requestAudioFocus result: $result")

            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    isFocusPaused = false
                    _isFocusPaused.value = false
                    true
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    isFocusPaused = true
                    _isFocusPaused.value = true
                    updateNotification("已暂停 (音频焦点让出)")
                    true
                }
                else -> {
                    Log.w(TAG, "Audio focus request not granted: $result")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus", e)
            false
        }
    }

    internal fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        } finally {
            audioFocusRequest = null
        }
    }

    internal fun handleAudioFocusLost(isTransient: Boolean) {
        Log.i(TAG, "handleAudioFocusLost: isTransient=$isTransient")
        if (!isRecording) return

        if (!isTransient) {
            // Permanent loss -> graceful shutdown
            stopMonitoring()
            return
        }

        // Transient loss (e.g. phone call, alarm, navigation speech)
        if (isFocusPaused) return
        isFocusPaused = true
        _isFocusPaused.value = true
        _liveDb.value = 25f

        synchronized(audioLock) {
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord on transient focus loss", e)
            }
            Unit
        }

        // Prevent false-positive 12s~60s apnea gap detections
        detector.reset()
        updateNotification("已暂停 (音频焦点让出)")
    }

    internal fun handleAudioFocusGained() {
        Log.i(TAG, "handleAudioFocusGained: isRecording=$isRecording, isFocusPaused=$isFocusPaused")
        if (!isRecording || !isFocusPaused) return

        isFocusPaused = false
        _isFocusPaused.value = false

        // Reset detector before resuming so any silence interval is discarded
        detector.reset()

        synchronized(audioLock) {
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                    audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING
                ) {
                    audioRecord?.startRecording()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error restarting AudioRecord on focus gain", e)
            }
            Unit
        }

        updateNotification("正在监测鼾声")
    }

    private fun startMonitoring() {
        if (isRecording) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(com.minimal.snore.receiver.AutoSleepReceiver.PROMPT_NOTIFICATION_ID)

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("正在监测睡眠声音..."))

        _isRunning.value = true
        _isLowStorage.value = false
        _snoreCount.value = 0
        _apneaCount.value = 0
        sessionStartTime = System.currentTimeMillis()
        sessionEvents.clear()
        isRecording = true
        isFocusPaused = false
        _isFocusPaused.value = false
        detector.reset()

        // Request audio focus before starting capture loop
        requestAudioFocus()

        serviceScope.launch(Dispatchers.IO) {
            recordAudioLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        frameSize: Int
    ): Boolean {
        return try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AudioRecord.getMinBufferSize returned error: $minBufferSize")
                return false
            }
            val bufferSize = maxOf(minBufferSize, frameSize * 4)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize (state != STATE_INITIALIZED)")
                record.release()
                return false
            }
            if (!isFocusPaused) {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(TAG, "AudioRecord failed to start recording (recordingState != RECORDSTATE_RECORDING)")
                    record.stop()
                    record.release()
                    return false
                }
            }
            audioRecord = record
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate AudioRecord", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudioLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val frameSize = 1600 // 100ms at 16kHz
        val chunk = ShortArray(frameSize)

        var consecutiveErrors = 0
        var reinitAttempts = 0

        val initialSuccess = synchronized(audioLock) {
            initAudioRecord(sampleRate, channelConfig, audioFormat, frameSize)
        }

        if (!initialSuccess) {
            Log.e(TAG, "Initial AudioRecord init failed, stopping monitoring")
            stopMonitoring()
            return
        }

        try {
            while (isRecording) {
                // 1. Audio Focus Pause Handling
                if (isFocusPaused) {
                    delay(200)
                    continue
                }

                // 2. Safe Audio Read under synchronized lock
                val readSamples = synchronized(audioLock) {
                    val record = audioRecord
                    if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            record.read(chunk, 0, frameSize)
                        } catch (e: Exception) {
                            Log.w(TAG, "Exception during AudioRecord.read()", e)
                            AudioRecord.ERROR
                        }
                    } else {
                        -1
                    }
                }

                // 3. Process frame or handle hardware errors
                if (readSamples > 0) {
                    consecutiveErrors = 0
                    reinitAttempts = 0
                    circularBuffer.write(chunk, readSamples)
                    detector.processFrame(chunk, readSamples)
                } else {
                    consecutiveErrors++
                    Log.w(TAG, "AudioRecord read returned $readSamples (consecutiveErrors=$consecutiveErrors)")

                    // Crucial: Throttle CPU on negative/zero read to eliminate 100% spinning loop
                    delay(100)

                    // Re-initialization sequence when consecutive errors hit threshold
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS && !isFocusPaused && isRecording) {
                        if (reinitAttempts >= MAX_REINIT_ATTEMPTS) {
                            Log.e(TAG, "Max AudioRecord reinit attempts reached ($MAX_REINIT_ATTEMPTS). Stopping recording loop.")
                            _liveDb.value = 25f
                            updateNotification("录音设备异常，睡眠监测已暂停")
                            break
                        }

                        reinitAttempts++
                        val backoffMs = (500L * reinitAttempts).coerceAtMost(3000L)
                        Log.i(TAG, "AudioRecord error threshold reached ($consecutiveErrors). Re-initializing in ${backoffMs}ms (attempt $reinitAttempts/$MAX_REINIT_ATTEMPTS)...")
                        delay(backoffMs)

                        synchronized(audioLock) {
                            cleanupAudio()
                            if (initAudioRecord(sampleRate, channelConfig, audioFormat, frameSize)) {
                                consecutiveErrors = 0
                                detector.reset()
                                Log.i(TAG, "AudioRecord pipeline successfully re-initialized!")
                            } else {
                                Log.w(TAG, "AudioRecord re-initialization failed on attempt $reinitAttempts")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal exception in recordAudioLoop", e)
        } finally {
            cleanupAudio()
        }
    }

    private fun onSnoreFound(peakDb: Float, durationMs: Long, isApneaSuspect: Boolean) {
        val audioSamples = circularBuffer.getRecentSamples(80000)

        serviceScope.launch(Dispatchers.Default) {
            // AI Verification Step:
            // Extract recent 0.975s (15600 samples) and classify with YAMNet
            val aiCheckSamples = circularBuffer.getRecentSamples(YamnetClassifier.SAMPLES_REQUIRED)
            val result = yamnetClassifier?.classify(aiCheckSamples)

            // If classified as airplane, vehicle noise or not a snore, discard it
            if (result != null && (!result.isSnore || result.isAirplaneOrTraffic)) {
                android.util.Log.d("SnoreService", "Filtered out noise: ${result.topCategory} (score: ${result.topScore})")
                return@launch
            }

            // Confirmed human snoring: save audio and record event
            withContext(Dispatchers.IO) {
                val timestamp = System.currentTimeMillis()
                _snoreCount.value += 1
                if (isApneaSuspect) {
                    _apneaCount.value += 1
                }

                val lowStorage = isLowStorage()
                _isLowStorage.value = lowStorage

                var savedAudioPath = ""
                if (lowStorage) {
                    android.util.Log.w("SnoreService", "Low storage (<100MB). Skipping WAV saving.")
                } else {
                    try {
                        val audioDir = File(filesDir, "snore_audio").apply { if (!exists()) mkdirs() }
                        val audioFile = File(audioDir, "snore_${timestamp}.wav")
                        WavWriter.writeWavFile(audioFile, audioSamples, 16000)
                        savedAudioPath = audioFile.absolutePath
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val event = SnoreEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = timestamp,
                    durationMs = durationMs,
                    peakDb = peakDb,
                    audioFilePath = savedAudioPath,
                    isApneaSuspect = isApneaSuspect
                )
                sessionEvents.add(event)
                repository.addEvent(event)

                if (lowStorage) {
                    updateNotification("监测中：已检测到 ${_snoreCount.value} 次打呼噜 [存储空间不足，已暂停录音保存]")
                } else {
                    updateNotification("监测中：已检测到 ${_snoreCount.value} 次打呼噜")
                }
            }
        }
    }

    private fun stopMonitoring() {
        if (!isRecording && !_isRunning.value) return

        val endTime = System.currentTimeMillis()
        val totalMonitoringMs = if (sessionStartTime > 0L) endTime - sessionStartTime else 0L

        // Generate SleepSession summary if monitored for at least 5s or events occurred
        if (totalMonitoringMs > 5000L || sessionEvents.isNotEmpty()) {
            val totalSnoreMs = sessionEvents.sumOf { it.durationMs }
            val maxDb = sessionEvents.maxOfOrNull { it.peakDb } ?: 0f
            val avgDb = if (sessionEvents.isNotEmpty()) sessionEvents.map { it.peakDb }.average().toFloat() else 0f
            val lightCount = sessionEvents.count { it.peakDb < 48f }
            val mediumCount = sessionEvents.count { it.peakDb in 48f..60f }
            val severeCount = sessionEvents.count { it.peakDb > 60f }
            val apneaCount = sessionEvents.count { it.isApneaSuspect }

            val hourly = mutableMapOf<Int, Int>()
            val cal = Calendar.getInstance()
            for (ev in sessionEvents) {
                cal.timeInMillis = ev.timestamp
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                hourly[hour] = (hourly[hour] ?: 0) + 1
            }

            val session = SleepSession(
                id = UUID.randomUUID().toString(),
                startTime = sessionStartTime,
                endTime = endTime,
                totalMonitoringMs = totalMonitoringMs,
                totalSnoreMs = totalSnoreMs,
                snoreCount = sessionEvents.size,
                maxDb = maxDb,
                avgDb = avgDb,
                lightSnoreCount = lightCount,
                mediumSnoreCount = mediumCount,
                severeSnoreCount = severeCount,
                apneaSuspectCount = apneaCount,
                hourlyDistribution = hourly
            )
            repository.saveSession(session)
        }

        isRecording = false
        isFocusPaused = false
        _isFocusPaused.value = false
        _isRunning.value = false

        abandonAudioFocus()
        cleanupAudio()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupAudio() {
        synchronized(audioLock) {
            try {
                audioRecord?.let { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception during cleanupAudio", e)
            } finally {
                audioRecord = null
            }
        }
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
        yamnetClassifier?.close()
        yamnetClassifier = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
