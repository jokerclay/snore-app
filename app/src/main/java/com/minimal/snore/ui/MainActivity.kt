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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.minimal.snore.data.AppSettings
import com.minimal.snore.data.SleepSession
import com.minimal.snore.data.SnoreEvent
import com.minimal.snore.data.SnoreRepository
import com.minimal.snore.receiver.AutoSleepScheduler
import com.minimal.snore.service.SnoreMonitorService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAutoStart(intent)

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

    override fun onResume() {
        super.onResume()
        AutoSleepScheduler.scheduleAlarms(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        checkAutoStart(intent)
    }

    private fun checkAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_AUTO_START", false) == true) {
            val hasRecordPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasRecordPerm) {
                SnoreMonitorService.start(this)
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
    val latestSession by repository.latestSessionFlow.collectAsState()

    val isRunning by SnoreMonitorService.isRunning.collectAsState()
    val liveDb by SnoreMonitorService.liveDb.collectAsState()
    val sessionSnoreCount by SnoreMonitorService.snoreCount.collectAsState()
    val sessionApneaCount by SnoreMonitorService.apneaCount.collectAsState()

    val appSettings = remember { AppSettings.getInstance(context) }
    var autoEnabled by remember { mutableStateOf(appSettings.autoEnabled) }
    var showTimeDialog by remember { mutableStateOf(false) }

    var isNightOledMode by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0: 睡眠报告, 1: 录音回听
    var playingFilePath by remember { mutableStateOf<String?>(null) }

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

    // Pure Black AMOLED Mode
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
                    text = "已捕捉打鼾: $sessionSnoreCount 次",
                    color = Color.DarkGray,
                    fontSize = 14.sp
                )
                if (sessionApneaCount > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "疑似屏气暂停: $sessionApneaCount 次",
                        color = Color(0xFF885522),
                        fontSize = 12.sp
                    )
                }
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
                    IconButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "电池优化设置",
                            tint = Color(0xFFAAAAAA)
                        )
                    }
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // Main Monitoring Control Button
            MonitoringButtonSection(
                isRunning = isRunning,
                liveDb = liveDb,
                snoreCount = sessionSnoreCount,
                apneaCount = sessionApneaCount,
                onToggle = {
                    if (isRunning) {
                        SnoreMonitorService.stop(context)
                    } else {
                        requestStartService()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Live Meter Card
            LiveMeterCard(liveDb = liveDb, isRunning = isRunning)

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-schedule Card (Tap to edit schedule times)
            AutoScheduleCard(
                autoEnabled = autoEnabled,
                bedtime = String.format(Locale.US, "%02d:%02d", appSettings.bedtimeHour, appSettings.bedtimeMinute),
                wakeup = String.format(Locale.US, "%02d:%02d", appSettings.wakeupHour, appSettings.wakeupMinute),
                onToggle = { enabled ->
                    autoEnabled = enabled
                    appSettings.autoEnabled = enabled
                    AutoSleepScheduler.scheduleAlarms(context)
                },
                onEditTimes = { showTimeDialog = true }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (showTimeDialog) {
                TimeSettingDialog(
                    initialBedtimeHour = appSettings.bedtimeHour,
                    initialBedtimeMinute = appSettings.bedtimeMinute,
                    initialWakeupHour = appSettings.wakeupHour,
                    initialWakeupMinute = appSettings.wakeupMinute,
                    onDismiss = { showTimeDialog = false },
                    onConfirm = { bedH, bedM, wakeH, wakeM ->
                        appSettings.bedtimeHour = bedH
                        appSettings.bedtimeMinute = bedM
                        appSettings.wakeupHour = wakeH
                        appSettings.wakeupMinute = wakeM
                        AutoSleepScheduler.scheduleAlarms(context)
                        showTimeDialog = false
                    }
                )
            }

            if (isRunning) {
                OutlinedButton(
                    onClick = { isNightOledMode = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🌙 开启夜间纯黑防刺眼模式 (AMOLED省电)")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Tab Selector: Report vs Audio Clips
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF151922),
                contentColor = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("📊 睡眠报告", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("🎙️ 录音回听 (${events.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tab Content
            if (selectedTab == 0) {
                SleepReportTab(session = latestSession)
            } else {
                AudioClipsTab(
                    events = events,
                    isRunning = isRunning,
                    playingFilePath = playingFilePath,
                    onPlayToggle = { event ->
                        if (playingFilePath == event.audioFilePath) {
                            onStopAudio()
                            playingFilePath = null
                        } else {
                            onPlayAudio(event.audioFilePath)
                            playingFilePath = event.audioFilePath
                        }
                    },
                    onDelete = { event ->
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

@Composable
fun SleepReportTab(session: SleepSession?) {
    if (session == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无昨夜完整睡眠报告", color = Color.Gray, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("点击上方大按钮开始入睡监测，晨起点击结束即可生成", color = Color(0xFF555555), fontSize = 12.sp)
            }
        }
        return
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Severity Grade Banner
        val gradeColor = when {
            session.severityLevel.contains("重度") -> Color(0xFFEF4444)
            session.severityLevel.contains("中度") -> Color(0xFFFBBF24)
            else -> Color(0xFF34D399)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("睡眠呼吸综合评级", color = Color.Gray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = session.severityLevel,
                        color = gradeColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(gradeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "打鼾占比 ${String.format(Locale.US, "%.1f", session.snoreBurdenPercent)}%",
                        color = gradeColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Apnea Alert Card if detected
        if (session.apneaSuspectCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "检测到 ${session.apneaSuspectCount} 次疑似屏气暂停",
                            color = Color(0xFFF87171),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "打鼾之间出现 12~60 秒完全无声屏气并伴随剧烈大喘气",
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // 4 Key Metrics Grid
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricBox(
                title = "监测总时长",
                value = formatDuration(session.totalMonitoringMs),
                modifier = Modifier.weight(1f)
            )
            MetricBox(
                title = "累计打鼾时长",
                value = formatDuration(session.totalSnoreMs),
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricBox(
                title = "打鼾总次数",
                value = "${session.snoreCount} 次",
                modifier = Modifier.weight(1f)
            )
            MetricBox(
                title = "最高峰值分贝",
                value = "${session.maxDb.toInt()} dB",
                modifier = Modifier.weight(1f)
            )
        }

        // Loudness Distribution Spectrum
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("鼾声响度分级分布", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(10.dp))

                // Spectrum 3-color progress bar
                val total = session.snoreCount.coerceAtLeast(1)
                val lightRatio = session.lightSnoreCount.toFloat() / total
                val medRatio = session.mediumSnoreCount.toFloat() / total
                val severeRatio = session.severeSnoreCount.toFloat() / total

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF222834))
                ) {
                    if (lightRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(lightRatio.coerceAtLeast(0.01f))
                                .background(Color(0xFF00BFA5))
                        )
                    }
                    if (medRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(medRatio.coerceAtLeast(0.01f))
                                .background(Color(0xFFFBBF24))
                        )
                    }
                    if (severeRatio > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(severeRatio.coerceAtLeast(0.01f))
                                .background(Color(0xFFEF4444))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LegendItem(color = Color(0xFF00BFA5), label = "轻鼾 (<48dB): ${session.lightSnoreCount}")
                    LegendItem(color = Color(0xFFFBBF24), label = "中鼾 (48~60): ${session.mediumSnoreCount}")
                    LegendItem(color = Color(0xFFEF4444), label = "重鼾 (>60dB): ${session.severeSnoreCount}")
                }
            }
        }

        // Hourly Timeline Distribution Chart
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("整夜时间轴分布 (每小时频次)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                HourlyDistributionChart(hourlyMap = session.hourlyDistribution)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun HourlyDistributionChart(hourlyMap: Map<Int, Int>) {
    val textMeasurer = rememberTextMeasurer()
    val hours = (0..23).filter { hourlyMap.containsKey(it) }.sorted()
    val displayHours = if (hours.isEmpty()) listOf(0, 1, 2, 3, 4, 5, 6, 7) else hours
    val maxCount = displayHours.maxOfOrNull { hourlyMap[it] ?: 0 }?.coerceAtLeast(1) ?: 1

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val width = size.width
        val height = size.height
        val barAreaHeight = height - 24.dp.toPx()
        val barCount = displayHours.size
        val barWidth = (width / barCount) * 0.55f
        val stepX = width / barCount

        for (i in 0 until barCount) {
            val hour = displayHours[i]
            val count = hourlyMap[hour] ?: 0
            val barHeight = (count.toFloat() / maxCount.toFloat()) * (barAreaHeight - 16.dp.toPx())
            val x = i * stepX + (stepX - barWidth) / 2f
            val y = barAreaHeight - barHeight

            // Draw bar
            drawRoundRect(
                color = if (count > 0) Color(0xFF38BDF8) else Color(0xFF222834),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight.coerceAtLeast(4.dp.toPx())),
                cornerRadius = CornerRadius(4.dp.toPx())
            )

            // Draw count number on top of bar if count > 0
            if (count > 0) {
                val countText = "$count"
                val textLayoutResult = textMeasurer.measure(
                    text = countText,
                    style = TextStyle(color = Color(0xFF38BDF8), fontSize = 10.sp)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = countText,
                    topLeft = Offset(
                        x + (barWidth - textLayoutResult.size.width) / 2f,
                        (y - textLayoutResult.size.height - 2.dp.toPx()).coerceAtLeast(0f)
                    ),
                    style = TextStyle(color = Color(0xFF38BDF8), fontSize = 10.sp)
                )
            }

            // Draw hour label at bottom
            val hourLabel = "${hour}h"
            val labelLayoutResult = textMeasurer.measure(
                text = hourLabel,
                style = TextStyle(color = Color.Gray, fontSize = 10.sp)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = hourLabel,
                topLeft = Offset(
                    x + (barWidth - labelLayoutResult.size.width) / 2f,
                    barAreaHeight + 4.dp.toPx()
                ),
                style = TextStyle(color = Color.Gray, fontSize = 10.sp)
            )
        }
    }
}

@Composable
fun MetricBox(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = Color.Gray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = Color.LightGray, fontSize = 10.sp)
    }
}

@Composable
fun AudioClipsTab(
    events: List<SnoreEvent>,
    isRunning: Boolean,
    playingFilePath: String?,
    onPlayToggle: (SnoreEvent) -> Unit,
    onDelete: (SnoreEvent) -> Unit
) {
    if (events.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isRunning) "监测运行中，正在静默捕捉打鼾声..." else "暂无打鼾录音，点击上方大按钮开始夜间监测",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(events, key = { it.id }) { event ->
                SnoreEventItem(
                    event = event,
                    isPlaying = playingFilePath == event.audioFilePath,
                    onPlayToggle = { onPlayToggle(event) },
                    onDelete = { onDelete(event) }
                )
            }
        }
    }
}

@Composable
fun MonitoringButtonSection(
    isRunning: Boolean,
    liveDb: Float,
    snoreCount: Int,
    apneaCount: Int,
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
                .size(130.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.15f))
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(98.dp)
                    .clip(CircleShape)
                    .background(buttonColor),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isRunning) "结束睡眠" else "开始入睡",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "打鼾: $snoreCount 次",
                    color = Color(0xFF34D399),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (apneaCount > 0) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "疑似屏气: $apneaCount 次",
                        color = Color(0xFFFBBF24),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "实时环境响度",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isRunning) "${liveDb.toInt()} dB" else "-- dB",
                    color = if (liveDb > 55f) Color(0xFFFBBF24) else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            val progress = ((liveDb - 20f) / 70f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = if (isRunning) progress else 0f,
                modifier = Modifier
                    .width(160.dp)
                    .height(7.dp)
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
            IconButton(
                onClick = onPlayToggle,
                modifier = Modifier
                    .size(40.dp)
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

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "打鼾: $formattedTime",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    if (event.isApneaSuspect) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "[⚠️屏气伴随]",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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
                        color = if (event.peakDb > 60f) Color(0xFFF87171) else Color(0xFF34D399)
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除",
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
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

@Composable
fun AutoScheduleCard(
    autoEnabled: Boolean,
    bedtime: String,
    wakeup: String,
    onToggle: (Boolean) -> Unit,
    onEditTimes: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEditTimes() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("⚡", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (autoEnabled) "夜间全自动守候 (已开启)" else "夜间全自动守候 (已关闭)",
                        color = if (autoEnabled) Color(0xFF38BDF8) else Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "作息: $bedtime 熄屏自启 ➔ $wakeup 自动结算",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }
            }

            Switch(
                checked = autoEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF00BFA5)
                )
            )
        }
    }
}

@Composable
fun TimeSettingDialog(
    initialBedtimeHour: Int,
    initialBedtimeMinute: Int,
    initialWakeupHour: Int,
    initialWakeupMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (bedHour: Int, bedMin: Int, wakeHour: Int, wakeMin: Int) -> Unit
) {
    var bedHour by remember { mutableStateOf(initialBedtimeHour) }
    var bedMin by remember { mutableStateOf(initialBedtimeMinute) }
    var wakeHour by remember { mutableStateOf(initialWakeupHour) }
    var wakeMin by remember { mutableStateOf(initialWakeupMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置夜间自动守候作息", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "在此时间区间内，只要手机处于锁屏状态或插上充电线，App 将在后台静默自动开始打鼾监测；到起床时间自动结算报告。",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("入睡时间点:", fontSize = 14.sp, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { bedHour = (bedHour - 1 + 24) % 24 }) {
                            Text("◀", color = Color.LightGray, fontSize = 16.sp)
                        }
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", bedHour, bedMin),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        IconButton(onClick = { bedHour = (bedHour + 1) % 24 }) {
                            Text("▶", color = Color.LightGray, fontSize = 16.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("晨起时间点:", fontSize = 14.sp, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { wakeHour = (wakeHour - 1 + 24) % 24 }) {
                            Text("◀", color = Color.LightGray, fontSize = 16.sp)
                        }
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", wakeHour, wakeMin),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399)
                        )
                        IconButton(onClick = { wakeHour = (wakeHour + 1) % 24 }) {
                            Text("▶", color = Color.LightGray, fontSize = 16.sp)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2A303C))

                Text("系统防拦截必调权限 (点击直接跳转):", fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ctx = LocalContext.current
                    OutlinedButton(
                        onClick = { openAutoStartSettings(ctx) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("🚀 自启动管理", fontSize = 11.sp, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { openAlarmSettings(ctx) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("⏰ 闹钟与提醒", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(bedHour, bedMin, wakeHour, wakeMin)
            }) {
                Text("保存设置", color = Color(0xFF00BFA5), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1C222D)
    )
}

fun openAutoStartSettings(context: Context) {
    val intents = listOf(
        Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
        Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (ignored: Exception) {}
    }
    // Fallback to app details
    try {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else {
        Toast.makeText(context, "当前系统版本无需单独授权闹钟权限", Toast.LENGTH_SHORT).show()
    }
}
