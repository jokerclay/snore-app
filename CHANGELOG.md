# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-10-01

### Added
- **数据原子化持久化 (`AtomicFile`)**：采用 `androidx.core.util.AtomicFile` 重构 `sessions_history.json`、`snore_events.json` 及 `last_session.json` 的读写，支持写入断电崩溃下的 `.bak` 故障自动恢复机制，彻底消除 0 字节文件损坏风险。
- **极低磁盘安全防线 (`StatFs`)**：通过 `android.os.StatFs` 动态监测可用闪存空间；低于 100MB 时自动挂起 5 秒打鼾音频 WAV 文件的写入，同时完整保留分贝监测、打鼾频次计数与整夜睡眠评估指标。
- **系统音频焦点管理 (`OnAudioFocusChangeListener`)**：注册 `AudioManager` 音频焦点监听，来电或系统闹钟等瞬态焦点抢占（`AUDIOFOCUS_LOSS_TRANSIENT`）时优雅暂停录制，焦点恢复（`AUDIOFOCUS_GAIN`）后自动唤醒。
- **呼吸暂停误报重置机制**：在音频焦点恢复及暂停恢复时重置静音计时器，防止通话或外部打断时长被声学状态机误判为 12s~60s 的睡眠呼吸暂停（Apnea）。
- **硬件级录音韧性重试与 CPU 保护**：捕获连续底层 `AudioRecord` HAL 读取异常，采用阶梯退避重试初始化机制（容错上限 3 次，重试最多 5 次），并在异常阶段引入 CPU 限流延时，杜绝死循环高耗电发热。
- **App 内历史睡眠报告日期导航**：在 `SleepReportTab` 中增加日期切换控件（`[◀ 前一天] [最新] [后一天 ▶]`），可无缝回溯查阅往期任一整夜睡眠的严重程度分级、打鼾负担比、疑似窒息次数、响度分布光谱与 24 小时柱状图。
- **麦克风距离与灵敏度校准**：新增“床头近距离 (0dB)”、“床头柜标准 (+3dB)”、“卧室桌远距 (+6dB)”三档距离增益补偿配置，并在设置面板中提供可视化选择，偏置值实时作用于动态底噪追踪与声学判定电平。
- **核心算法自动化单元测试套件**：引入 JUnit 4 纯计算单元测试，覆盖 PCM 环形缓冲区（`CircularPcmBufferTest`）、打鼾与呼吸暂停检测状态机（`SnoreDetectorTest`）、24小时跨午夜睡眠时间窗口（`AppSettingsTest`）及并发持久化压力测试。

### Changed
- `versionCode` 升级至 `2`，`versionName` 升级至 `1.1.0`。
- Release 构建配置集成 Debug 默认签名，生成的 `app-release.apk` 支持免证书配置即刻安装。

---

## [1.0.0] - 2026-09-30

### Added
- 初始 MVP 版本发布。
- 本地纯端侧声学打鼾检测引擎（RMS 分贝计算、ZCR 过零率低频共振滤波）。
- 集成 TensorFlow Lite YAMNet 声学分类模型进行端侧声音二次验证。
- 自动夜间监测时间窗口（默认 22:30 至 07:30）。
- 基于前台服务（`Foreground Service` + `AudioRecord` + `WakeLock`）的长周期后台保活录音架构。
- 触发打鼾时提取环形缓冲并保存 5 秒 PCM WAV 音频片段。
- 基于 Jetpack Compose 构建极简现代 UI（睡眠报告、录音回放、数据备份与夜间时间设置）。
