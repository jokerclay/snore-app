package com.minimal.snore.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.minimal.snore.data.SnoreEvent
import com.minimal.snore.data.SnoreRepository
import com.minimal.snore.service.SnoreMonitorService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF151922),
                    primary = Color(0xFF00BFA5),
                    secondary = Color(0xFF38BDF8)
                )
            ) {
                SnoreAppScreen(
                    onPlayAudio = { path -> playAudioClip(path) },
                    onStopAudio = { stopAudioClip() }
                )
            }
        }
    }

    private fun playAudioClip(filePath: String) {
        try {
            stopAudioClip()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopAudioClip()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAudioClip() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        stopAudioClip()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoreAppScreen(
    onPlayAudio: (String) -> Unit,
    onStopAudio: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SnoreRepository.getInstance(context) }
    val events by repository.eventsFlow.collectAsState()

    val isRunning by SnoreMonitorService.isRunning.collectAsState()
    val liveDb by SnoreMonitorService.liveDb.collectAsState()
    val sessionSnoreCount by SnoreMonitorService.snoreCount.collectAsState()

    var isNightOledMode by remember { mutableStateOf(false) }
    var playingFilePath by remember { mutableStateOf<String?>(null) }

    // Permissions launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val recordAudioGranted = perms[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            SnoreMonitorService.start(context)
        } else {
            Toast.makeText(context, "需要麦克风权限以检测打鼾", Toast.LENGTH_LONG).show()
        }
    }

    fun requestStartService() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val hasRecordPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasRecordPerm) {
            SnoreMonitorService.start(context)
        } else {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    // Pure Black AMOLED Mode (Touch anywhere to wake up)
    if (isNightOledMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { isNightOledMode = false }
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "夜间微光防刺眼模式",
                    color = Color.DarkGray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${liveDb.toInt()} dB",
                    color = Color(0xFF225544),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已检测打鼾: $sessionSnoreCount 次",
                    color = Color.DarkGray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "点击屏幕任意位置退出微光模式",
                    color = Color(0xFF333333),
                    fontSize = 12.sp
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌙", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打鼾监测 (Snore App)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    // Battery Optimization Exemption Shortcut
                    IconButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "电池优化设置",
                            tint = Color(0xFFAAAAAA)
                        )
                    }
                    // Clear history button
                    if (events.isNotEmpty()) {
                        IconButton(onClick = { repository.clearAll() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "清空所有记录",
                                tint = Color(0xFFAAAAAA)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0E14),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0B0E14)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Main Monitoring Control Button
            MonitoringButtonSection(
                isRunning = isRunning,
                liveDb = liveDb,
                snoreCount = sessionSnoreCount,
                onToggle = {
                    if (isRunning) {
                        SnoreMonitorService.stop(context)
                    } else {
                        requestStartService()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Decibel & Sensitivity Meter
            LiveMeterCard(liveDb = liveDb, isRunning = isRunning)

            Spacer(modifier = Modifier.height(12.dp))

            // Night AMOLED Mode Switch Button
            if (isRunning) {
                OutlinedButton(
                    onClick = { isNightOledMode = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🌙 开启夜间纯黑防刺眼模式 (AMOLED省电)")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Recorded Events Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "打鼾记录与回听 (${events.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (events.isNotEmpty()) {
                    Text(
                        text = "仅保留鼾声片段",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Events List
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRunning) "监测运行中，正在静默捕捉打鼾声..." else "点击上方大按钮开始夜间监测",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        SnoreEventItem(
                            event = event,
                            isPlaying = playingFilePath == event.audioFilePath,
                            onPlayToggle = {
                                if (playingFilePath == event.audioFilePath) {
                                    onStopAudio()
                                    playingFilePath = null
                                } else {
                                    onPlayAudio(event.audioFilePath)
                                    playingFilePath = event.audioFilePath
                                }
                            },
                            onDelete = {
                                if (playingFilePath == event.audioFilePath) {
                                    onStopAudio()
                                    playingFilePath = null
                                }
                                repository.deleteEvent(event.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonitoringButtonSection(
    isRunning: Boolean,
    liveDb: Float,
    snoreCount: Int,
    onToggle: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRunning) Color(0xFFEF4444) else Color(0xFF00BFA5),
        label = "color"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.15f))
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(105.dp)
                    .clip(CircleShape)
                    .background(buttonColor),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isRunning) "结束睡眠" else "开始入睡",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isRunning) {
            Text(
                text = "已捕捉打鼾: $snoreCount 次",
                color = Color(0xFF34D399),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(
                text = "锁屏前点击开始，请放置在床头附近",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun LiveMeterCard(liveDb: Float, isRunning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "实时环境响度",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isRunning) "${liveDb.toInt()} dB" else "-- dB",
                    color = if (liveDb > 55f) Color(0xFFFBBF24) else Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Visual mini progress bar
            val progress = ((liveDb - 20f) / 70f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = if (isRunning) progress else 0f,
                modifier = Modifier
                    .width(160.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (liveDb > 60f) Color(0xFFEF4444) else Color(0xFF00BFA5),
                trackColor = Color(0xFF222834)
            )
        }
    }
}

@Composable
fun SnoreEventItem(
    event: SnoreEvent,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(event.timestamp) { timeFormat.format(Date(event.timestamp)) }
    val durationSec = String.format(Locale.US, "%.1fs", event.durationMs / 1000f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Play Button
            IconButton(
                onClick = onPlayToggle,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) Color(0xFF00BFA5) else Color(0xFF222834))
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = "试听",
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Middle info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "打鼾时间: $formattedTime",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = "持续: $durationSec",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "峰值: ${event.peakDb.toInt()} dB",
                        fontSize = 12.sp,
                        color = if (event.peakDb > 65f) Color(0xFFF87171) else Color(0xFF34D399)
                    )
                }
            }

            // Right Delete Button
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            }
        } else {
            Toast.makeText(context, "已开启免电池优化，整夜运行更稳定！", Toast.LENGTH_SHORT).show()
        }
    }
}
