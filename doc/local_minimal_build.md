# 本地免安装 Android Studio 极简命令行编译指南 (Local Minimal Build)

如果你完全不想在电脑上安装几个 GB 的 Android Studio 界面，但又希望在本地直接调试和构建 APK，只需 **两样绿色便携工具**（解压即用，不写注册表，不用时直接删掉文件夹即可）：

---

## 所需的极简便携包 (共约 300MB)

1. **便携版 JDK 17 (Zip)**：
   - 推荐 Eclipse Temurin OpenJDK 17：[下载链接 (Windows x64 .zip)](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip)
   - 解压到例如：`D:\sdks\jdk-17`

2. **Android 官方命令行工具 (commandlinetools)**：
   - 官方直链：[commandlinetools-win-11076708_latest.zip](https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip)
   - 解压到例如：`D:\sdks\android-sdk\cmdline-tools\latest`

---

## 本地编译步骤（终端 3 行命令）

打开 PowerShell，执行以下命令即可：

```powershell
# 1. 指定便携工具路径
$env:JAVA_HOME = "D:\sdks\jdk-17"
$env:ANDROID_HOME = "D:\sdks\android-sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"

# 2. 首次运行自动接受协议并安装轻量 platform-34
sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"

# 3. 本地编译出 APK
./gradlew assembleDebug
```

编译完成后，你的 APK 直接出现在：
`app\build\outputs\apk\debug\app-debug.apk`

---

## 为什么刚才 GitHub Actions 报错？（已彻底修复）

刚才云端构建失败的原因已查明并彻底修复：
1. **第一次**：缺少 `gradle.properties`（已补齐 `android.useAndroidX=true`）。
2. **第二次**：Compose 1.2 中 `LinearProgressIndicator` 的进度参数应为浮点数 `Float`，之前传入了闭包 `{ progress }` 触发了 Kotlin 类型检查报错。
3. **已修复并推送**：代码现已全部修复并推送到 GitHub 仓库 `jokerclay/snore-app`。
