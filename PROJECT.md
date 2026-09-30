# Project: Snore App Improvements (R1 - R5)

## Architecture
- **Language / Runtime**: Kotlin 1.9, Android API 26-34, Jetpack Compose, Coroutines/Flow, JUnit 4.
- **Persistence Layer**: `SnoreRepository` manages `sessions_history.json`, `snore_events.json`, `last_session.json` using `androidx.core.util.AtomicFile` for zero-risk atomic file writes with `.bak` recovery.
- **Audio Service Layer**: `SnoreMonitorService` runs background audio capture (`AudioRecord`), handles system audio focus via `AudioManager.OnAudioFocusChangeListener`, guards against disk exhaustion (<100MB StatFs pause), and recovers from HAL/AudioRecord hardware read errors.
- **Acoustic Analysis Layer**: `SnoreDetector` tracks RMS dB, adaptive noise floor, snore burst detection, and 12s~60s apnea gap state machine with distance calibration gain offset. `CircularPcmBuffer` manages ring buffer audio caching.
- **UI / Settings Layer**: `MainActivity` hosts Compose UI, `SleepReportTab` with historical session date navigation (`[◀ 前一天] 2026-xx-xx [后一天 ▶]`), and `TimeSettingDialog` with bedtime, wake-up, and microphone calibration settings.
- **Test Suite Layer**: JUnit tests in `app/src/test/java/com/minimal/snore/` covering `CircularPcmBufferTest`, `SnoreDetectorTest`, and `AppSettingsTest`.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | R1.1 Atomic JSON Persistence | Use `AtomicFile` with `.bak` recovery for `sessions_history.json`, `snore_events.json`, and `last_session.json` | M1 | ORIGINAL_REQUEST §R1 |
| 2 | R1.2 Low-Storage Guard | Check `StatFs` (<100MB); gracefully pause WAV saving while preserving sleep statistics & decibels | M1 | ORIGINAL_REQUEST §R1 |
| 3 | R2.1 Audio Focus Management | Register `OnAudioFocusChangeListener`; pause on transient loss; auto-resume on `AUDIOFOCUS_GAIN`; reset detector | M2 | ORIGINAL_REQUEST §R2 |
| 4 | R2.2 Hardware Error Recovery | Throttle CPU on read errors (`delay(100)`), count consecutive errors (threshold=3), and safely re-init AudioRecord | M2 | ORIGINAL_REQUEST §R2 |
| 5 | R4.1 Calibration Storage | Model `MicCalibration` (Bedside +0dB, Nightstand +3dB, Far +6dB) in `AppSettings` with SharedPreferences | M3 | ORIGINAL_REQUEST §R4 |
| 6 | R4.2 Calibration UI Selector | Add microphone calibration setting controls in `TimeSettingDialog` | M3 | ORIGINAL_REQUEST §R4 |
| 7 | R4.3 Calibration Math Application | Apply calibration offset to live decibels, noise floor, trigger level, and apnea threshold in `SnoreDetector` | M3 | ORIGINAL_REQUEST §R4 |
| 8 | R3.1 Historical Date Navigation | Add `[◀ 前一天] 2026-xx-xx [后一天 ▶]` navigation controls to `SleepReportTab` | M4 | ORIGINAL_REQUEST §R3 |
| 9 | R3.2 Dynamic Historical Rendering | Dynamically render selected session severity grade, snore burden %, apnea count, spectrum, and 24-hr histogram | M4 | ORIGINAL_REQUEST §R3 |
| 10 | R5.1 Test Suite Infrastructure | Add `testImplementation("junit:junit:4.13.2")` in `app/build.gradle.kts` without heavy dependencies | M5 | ORIGINAL_REQUEST §R5 |
| 11 | R5.2 CircularPcmBufferTest | JUnit test verifying ring buffer write, wrap-around, and recent sample retrieval | M5 | ORIGINAL_REQUEST §R5 |
| 12 | R5.3 SnoreDetectorTest | JUnit test verifying RMS dB, adaptive noise floor tracking, and 12s~60s apnea state machine | M5 | ORIGINAL_REQUEST §R5 |
| 13 | R5.4 AppSettingsTest | JUnit test verifying midnight-crossing night window logic across all 24 hours | M5 | ORIGINAL_REQUEST §R5 |
| 14 | R5.5 Build & Verification | Run `./gradlew test` (clean pass) and `.\build.bat` (app-debug.apk in <5s, 0 warnings/errors) | M5 | ORIGINAL_REQUEST §Acceptance |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Atomic Persistence & Low-Storage Guard | `SnoreRepository.kt`, `SnoreMonitorService.kt` | none | DONE |
| M2 | Audio Focus & Hardware Resilience | `SnoreMonitorService.kt` | M1 | DONE |
| M3 | Microphone Distance & Sensitivity Calibration | `AppSettings.kt`, `TimeSettingDialog` in `MainActivity.kt`, `SnoreDetector.kt` | M2 | DONE |
| M4 | In-App Historical Session Navigation | `SleepReportTab` & state in `MainActivity.kt` | M3 | DONE |
| M5 | Core Algorithm Unit Test Suite & Acceptance | `app/build.gradle.kts`, `app/src/test/java/com/minimal/snore/`, `./gradlew test`, `.\build.bat` | M1, M2, M3, M4 | DONE |

## Interface Contracts

### M1: Persistence & Storage Contracts
- `SnoreRepository`:
  - `private fun readJsonFile(file: File): String?` uses `AtomicFile(file).openRead()` with `.bak` fallback.
  - `private fun writeJsonFile(file: File, content: String)` uses `AtomicFile(file).startWrite()`, writes UTF-8 bytes, and calls `finishWrite()`.
- `SnoreMonitorService`:
  - `isLowStorage(): Boolean`: Evaluates `StatFs(filesDir.absolutePath).availableBytes < 100L * 1024L * 1024L`.
  - When low storage is active: skips `WavWriter.writeWavFile()`, records `audioFilePath = ""`, logs warning, updates foreground notification, and still calls `sessionEvents.add(event)` and `repository.addEvent(event)`.

### M2: Audio Focus & Resilience Contracts
- `SnoreMonitorService`:
  - `AudioManager.OnAudioFocusChangeListener` handles:
    - `AUDIOFOCUS_LOSS_TRANSIENT`, `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`: pause recording, stop `AudioRecord`, `isFocusPaused = true`, `detector.reset()`, notification "已暂停 (音频焦点让出)".
    - `AUDIOFOCUS_GAIN`: clear `isFocusPaused`, restart `AudioRecord`, `detector.reset()`, notification "正在监测鼾声".
  - Audio Read Loop:
    - If `readSamples <= 0`: call `delay(100)`, increment `consecutiveErrors`.
    - If `consecutiveErrors >= 3`: trigger `reinitAudioRecord()` with stepped backoff up to 5 attempts.

### M3: Calibration Contracts
- `AppSettings`:
  - `enum class MicCalibration(val label: String, val offsetDb: Float)` with `BEDSIDE("床头 (0dB)", 0.0f)`, `NIGHTSTAND("床头柜 (+3dB)", 3.0f)`, `FAR("远距离 (+6dB)", 6.0f)`.
  - Property `micCalibration: MicCalibration` persisted in SharedPreferences `"mic_calibration_name"`.
- `SnoreDetector`:
  - Constructor or setter accepts `calibrationOffset: Float`.
  - `currentDb = min(95.0f, max(20.0f, (rawDb * 1.08f).toFloat() + calibrationOffset))`
  - Dynamic thresholds and apnea checks use calibrated dB.

### M4: Historical Session Navigation Contracts
- `SleepReportTab`:
  - Signature: `@Composable fun SleepReportTab(sessions: List<SleepSession>, initialIndex: Int = 0)`
  - State: `var selectedIndex by remember(sessions) { mutableIntStateOf(0) }`
  - Navigation controls: `[◀ 前一天]` (increments index if < sessions.lastIndex), `[后一天 ▶]` (decrements index if > 0). Displays `sessions[selectedIndex].startTime` formatted date.
  - Renders all components from `sessions[selectedIndex]`.

### M5: Unit Test Contracts
- Dependencies: `testImplementation("junit:junit:4.13.2")` in `app/build.gradle.kts`.
- Test classes:
  - `com.minimal.snore.CircularPcmBufferTest`: tests write, wrap-around, `getRecentSamples`.
  - `com.minimal.snore.SnoreDetectorTest`: tests RMS dB, noise floor, 12s~60s apnea state machine via timestamp injection.
  - `com.minimal.snore.AppSettingsTest`: tests `isTimeInNightWindow` for all 24 hours.

## Code Layout
- `app/src/main/java/com/minimal/snore/data/SnoreRepository.kt`: JSON persistence & storage
- `app/src/main/java/com/minimal/snore/data/AppSettings.kt`: Preferences & calibration model
- `app/src/main/java/com/minimal/snore/service/SnoreMonitorService.kt`: Service, AudioRecord loop, focus listener, StatFs
- `app/src/main/java/com/minimal/snore/audio/SnoreDetector.kt`: Acoustic detection, calibration offset, apnea state machine
- `app/src/main/java/com/minimal/snore/audio/CircularPcmBuffer.kt`: PCM ring buffer
- `app/src/main/java/com/minimal/snore/ui/MainActivity.kt`: Compose UI, SleepReportTab, TimeSettingDialog
- `app/src/test/java/com/minimal/snore/CircularPcmBufferTest.kt`: Buffer unit tests
- `app/src/test/java/com/minimal/snore/SnoreDetectorTest.kt`: Detector unit tests
- `app/src/test/java/com/minimal/snore/AppSettingsTest.kt`: Night window unit tests
- `app/build.gradle.kts`: Gradle build and test dependencies
- `build.bat`: Windows compilation script
