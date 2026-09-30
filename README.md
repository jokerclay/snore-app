# 极简打鼾监测 App (Snore App)

一个专为个人打造的极简、轻量、离线、零广告睡眠打鼾监测 Android 应用。

- **0 本地开发环境负担**：无需安装庞大的 Android Studio 或 Android SDK，通过 GitHub Actions 免费在云端 2 分钟自动编译出 APK。
- **0 存储焦虑**：整夜录音不产生大文件。采用内存 5 秒环形缓冲区，**仅在确认打鼾时切片保存 5 秒音频**，一整夜音频占用通常 < 2MB。
- **整夜稳定防杀**：基于原生 `ForegroundService` + `PartialWakeLock`，锁屏整夜不掉线。
- **极简两屏交互**：
  1. **入睡监控屏**：大按钮一键启停、实时分贝表、一键夜间微光防刺眼模式（AMOLED 省电）。
  2. **晨起报告与回听**：打鼾次数统计、时间轴列表、一键试听打鼾录音切片。

---

## 🚀 3 步获取 APK (无需安装任何 SDK)

### 第一步：在 GitHub 上新建一个仓库
访问 [GitHub](https://github.com/new) 新建一个空仓库，比如命名为 `snore-app`（公开或私有均可）。

### 第二步：推送当前项目代码
在当前目录终端执行：
```bash
git add .
git commit -m "feat: initial minimal snore app"
git branch -M main
git remote add origin https://github.com/<你的用户名>/snore-app.git
git push -u origin main
```

### 第三步：下载编译好的 APK
1. 进入 GitHub 仓库页面，点击上方的 **Actions** 标签页。
2. 你会看到名为 `Build Android APK` 的工作流正在自动运行（通常耗时 2~3 分钟）。
3. 运行完成后，点击该次构建，在页面底部的 **Artifacts** 区域即可直接下载 **`SnoreApp-debug.apk`**。
4. 发送到手机上直接安装运行！

---

## 🛠️ 项目技术架构

```
snore-app/
├── .github/workflows/build-apk.yml  # GitHub Actions 自动化云编译配置
├── app/
│   ├── src/main/java/com/minimal/snore/
│   │   ├── SnoreApplication.kt      # 通知渠道初始化
│   │   ├── audio/
│   │   │   ├── CircularPcmBuffer.kt # 内存 5 秒环形音频缓冲（零磁盘开销）
│   │   │   ├── SnoreDetector.kt     # 声学自适应底噪与打鼾特征检测器
│   │   │   └── WavWriter.kt         # 极轻量 WAV 格式导出器
│   │   ├── data/
│   │   │   ├── SnoreEvent.kt        # 打鼾事件模型
│   │   │   └── SnoreRepository.kt   # 本地轻量数据存储（免复杂数据库）
│   │   ├── service/
│   │   │   └── SnoreMonitorService.kt # 前台保活与音频采集引擎
│   │   └── ui/
│   │       └── MainActivity.kt      # Jetpack Compose 极简交互界面
│   └── build.gradle.kts
└── doc/                             # 架构与思考文档
```
