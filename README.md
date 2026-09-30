# 极简打鼾监测 App (Snore App)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Release](https://img.shields.io/github/v/release/jokerclay/snore-app)](https://github.com/jokerclay/snore-app/releases/tag/v1.1.0)

一个专为个人打造的**极简、轻量、100% 离线、零广告**的睡眠打鼾与呼吸健康监测 Android 原生应用。

- 🔒 **100% 离线与隐私安全**：零网络权限（无 `INTERNET`），音频与健康数据永不出手机，无任何第三方分析或广告 SDK。
- ⚡ **极简低功耗设计**：整夜（8 小时）熄屏监测耗电仅 **5% ~ 8%**，不插电也能安心使用。
- 🧠 **YAMNet 离线 AI 降噪**：内置 4.1MB 本地神经网络，自动剔除飞机轰鸣、车流与人声，只保留真实鼾声。
- 🌙 **全自动作息守护**：支持设定入睡与起床时间，夜间作息区间内尽全力自动拉起，早晨自动收尾生成报告。
- 📊 **丰富健康看板**：睡眠呼吸综合评级、打鼾负担占比、疑似屏气暂停（Apnea）筛查、三色响度光谱与 24 小时频次直方图。
- 💾 **数据自主沉淀与导出**：从安装日起历史记录永久保存，机身占用空间实时可视化，支持一键打包导出标准 CSV/JSON/WAV ZIP 压缩包传至电脑。

---

## 📱 界面预览 (Screenshots)

<p align="center">
  <img src="doc/images/screenshot_main_monitoring.png" width="300" alt="主监测与打鼾回听界面" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="doc/images/screenshot_dim_mode.png" width="300" alt="夜间纯黑微光防刺眼模式" />
</p>
<p align="center">
  <sub><b>图 1：主监测界面与 5 秒打鼾切片回听</b> &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; <b>图 2：夜间微光防刺眼模式（AMOLED 纯黑省电）</b></sub>
</p>

---

## 📚 官方文档库 (Documentation)

项目包含全套标准化工程文档，位于 `doc/` 目录下：

* 📋 [**需求规格说明书 (Requirements Specification)**](doc/REQUIREMENTS_SPEC.md)：系统功能需求（FR-01 ~ FR-09）、非功能需求（性能/内存/功耗指标）及异常边界。
* 🛠️ [**技术架构与开发维护手册 (Technical Manual)**](doc/TECH_MANUAL.md)：系统总体架构图、音频采集流水线、动态巡检保活、持久化与数据导出实现、轻量本地构建指令。
* 📖 [**用户使用手册 (User Manual)**](doc/USER_MANUAL.md)：权限配置引导、两种使用模式（自动/手动）、报告指标解读、录音管理、全量导出与无损瘦身指南。
* 🗄️ [**历史讨论与早期设计归档 (Archived Docs)**](doc/archv/)：早期技术方案选型、设计草稿与验证指南归档。

---

## 🚀 极速构建与安装 (Build & Install)

### 方案 A：本地免安装秒级编译 (推荐)
已预置便携构建脚本，在项目根目录下双击或终端运行：
```bat
.\build.bat
```
3~4 秒内即可在 `app\build\outputs\apk\debug\app-debug.apk` 输出最新 APK。

### 方案 B：GitHub Actions 云端自动构建
向 `main` 分支提交代码后，GitHub Actions 会自动触发云编译，在仓库的 **Actions** 页面即可下载最新的 `SnoreApp-debug.apk`。

---

## 🛠️ 项目目录结构

```text
snore-app/
├── .github/workflows/build-apk.yml  # GitHub Actions 自动化编译工作流
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── yamnet.tflite        # Google YAMNet 521类离线声音分类模型 (4.1MB)
│   │   ├── java/com/minimal/snore/
│   │   │   ├── SnoreApplication.kt  # 应用入口与通知渠道定义
│   │   │   ├── audio/
│   │   │   │   ├── CircularPcmBuffer.kt # 内存 5 秒环形音频缓冲（零磁盘开销）
│   │   │   │   ├── SnoreDetector.kt     # RMS分贝/动态底噪/过零率/屏气状态机
│   │   │   │   ├── WavWriter.kt         # 标准 16kHz WAV 导出器
│   │   │   │   └── YamnetClassifier.kt  # TFLite 本地推理引擎
│   │   │   ├── data/
│   │   │   │   ├── AppSettings.kt       # 作息时间与自启偏好设置
│   │   │   │   ├── DataExportManager.kt # 一键全量 ZIP 打包与 FileProvider 导出
│   │   │   │   ├── SleepSession.kt      # 睡眠报告数据模型与 JSON 序列化
│   │   │   │   ├── SnoreEvent.kt        # 打鼾事件模型
│   │   │   │   └── SnoreRepository.kt   # 历史多日归档与机身存储实时计算
│   │   │   ├── receiver/
│   │   │   │   ├── AutoSleepReceiver.kt # 充电、作息闹钟与系统唤醒广播处理
│   │   │   │   └── AutoSleepScheduler.kt# 精确闹钟排程与夜间巡检循环
│   │   │   ├── service/
│   │   │   │   └── SnoreMonitorService.kt# 前台麦克风服务与保活唤醒锁
│   │   │   └── ui/
│   │   │       └── MainActivity.kt      # Jetpack Compose 响应式交互界面
│   │   └── res/xml/file_paths.xml   # Android FileProvider 安全文件导出配置
├── doc/                             # 规范文档库
│   ├── REQUIREMENTS_SPEC.md         # 需求规格说明书
│   ├── TECH_MANUAL.md               # 技术架构与开发维护手册
│   ├── USER_MANUAL.md               # 用户使用手册
│   └── archv/                       # 历史设计文档与早期草稿归档
└── build.bat                        # 本地免安装 4 秒极速编译脚本
```

---

## 📄 开源许可证 (License)

本项目遵循 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源许可证。

这意味着你可以自由学习、使用、修改和分发本项目代码，但任何对本项目的二次分发或衍生版本**均必须同样以 GPL-3.0 协议免费开源全部源代码**，杜绝闭源商业垄断。

