package com.minimal.snore.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DataExportManager {

    /**
     * Exports all historical sleep sessions, snore events, and audio recordings
     * into a single comprehensive ZIP file, then opens the Android share sheet.
     */
    fun exportAllData(
        context: Context,
        sessions: List<SleepSession>,
        events: List<SnoreEvent>
    ): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val readableFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeStampStr = dateFormat.format(Date())

        val exportDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val zipFile = File(exportDir, "SnoreApp_Backup_$timeStampStr.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1. Export sleep_sessions.csv
            val sessionsCsv = buildString {
                append("Session ID,Date,Start Time,End Time,Total Monitoring (Hours),Snore Duration (Mins),Snore Burden (%),Snore Count,Peak dB,Avg dB,Light Snores (<48dB),Medium Snores (48-60dB),Severe Snores (>60dB),Apnea Count,Severity Grade\n")
                sessions.forEach { s ->
                    val dateStr = dateOnlyFormat.format(Date(s.startTime))
                    val startStr = readableFormat.format(Date(s.startTime))
                    val endStr = readableFormat.format(Date(s.endTime))
                    val totalHours = String.format(Locale.US, "%.2f", s.totalMonitoringMs / 3600000.0)
                    val snoreMins = String.format(Locale.US, "%.1f", s.totalSnoreMs / 60000.0)
                    val burdenStr = String.format(Locale.US, "%.1f", s.snoreBurdenPercent)
                    append("${s.id},$dateStr,$startStr,$endStr,$totalHours,$snoreMins,$burdenStr,${s.snoreCount},${s.maxDb.toInt()},${s.avgDb.toInt()},${s.lightSnoreCount},${s.mediumSnoreCount},${s.severeSnoreCount},${s.apneaSuspectCount},\"${s.severityLevel}\"\n")
                }
            }
            addZipEntry(zos, "sleep_sessions.csv", sessionsCsv.toByteArray())

            // 2. Export sleep_sessions.json (Full JSON with 24-hour distribution)
            val sessionsJsonArray = JSONArray()
            sessions.forEach { sessionsJsonArray.put(it.toJson()) }
            addZipEntry(zos, "sleep_sessions.json", sessionsJsonArray.toString(2).toByteArray())

            // 3. Export snore_events.csv
            val eventsCsv = buildString {
                append("Event ID,Date Time,Timestamp,Peak dB,Duration (ms),Suspected Apnea,Audio File\n")
                events.forEach { e ->
                    val timeStr = readableFormat.format(Date(e.timestamp))
                    val audioName = File(e.audioFilePath).name
                    append("${e.id},$timeStr,${e.timestamp},${e.peakDb.toInt()},${e.durationMs},${e.isApneaSuspect},$audioName\n")
                }
            }
            addZipEntry(zos, "snore_events.csv", eventsCsv.toByteArray())

            // 4. Export README.txt (Data dictionary and documentation)
            val readme = """
                ============================================================
                Snore App (打鼾监测) - 个人睡眠健康数据导出包
                导出时间: ${readableFormat.format(Date())}
                累计记录天数: ${sessions.size} 天
                累计捕获鼾声: ${events.size} 次
                ============================================================

                [文件说明]:
                1. sleep_sessions.csv:
                   整夜睡眠汇总报表。每行代表一整晚的监测，包含入睡时间、醒来时间、
                   监测总时长、打鼾总时长、打鼾负担占比(%)、最高响度、平均响度、
                   轻/中/重度鼾声分布与屏气疑似次数。适合直接在 Excel、Pandas 中导入。

                2. sleep_sessions.json:
                   结构化原始数据，包含每小时打鼾频次直方图分布 (hourlyDistribution)，
                   适合二次数据分析、可视化看板或自建健康系统。

                3. snore_events.csv:
                   每一声打鼾的详细流水账。记录精确时间戳、峰值分贝、持续时长以及
                   对应的音频切片文件名。

                4. audio/ 目录:
                   所有捕捉到的 5 秒标准 WAV 原始音频切片 (16kHz 16-bit 单声道 PCM)。
                   可在任何播放器、Audacity 或 Python (librosa/scipy) 中直接播放分析。

                [评级参考标准]:
                - 轻度鼾声: < 48 dB
                - 中度鼾声: 48 ~ 60 dB
                - 重度鼾声: > 60 dB
                - 疑似屏气 (Apnea): 打鼾之间出现 12~60 秒的完全静音空档，并随后伴随剧烈喘息大鼾声。
                ============================================================
            """.trimIndent()
            addZipEntry(zos, "README.txt", readme.toByteArray())

            // 5. Add all WAV audio clips inside audio/
            val audioDir = File(context.filesDir, "snore_audio")
            if (audioDir.exists() && audioDir.isDirectory) {
                val wavFiles = audioDir.listFiles { _, name -> name.endsWith(".wav") } ?: emptyArray()
                for (wavFile in wavFiles) {
                    addFileToZip(zos, "audio/${wavFile.name}", wavFile)
                }
            }
        }

        return zipFile
    }

    fun shareZipFile(context: Context, zipFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Snore App 打鼾监测全量数据备份")
            putExtra(Intent.EXTRA_TEXT, "这是从手机导出的 Snore App 全量睡眠打鼾历史数据与音频备份。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "导出数据包到电脑或应用")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun addZipEntry(zos: ZipOutputStream, entryName: String, data: ByteArray) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun addFileToZip(zos: ZipOutputStream, entryName: String, file: File) {
        if (!file.exists()) return
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            fis.copyTo(zos)
        }
        zos.closeEntry()
    }
}
