@echo off
set "JAVA_HOME=C:\Program Files\JetBrains\JetBrains Rider 2025.3.3\jbr"
set "ANDROID_HOME=D:\Programming\android\sdk"

echo Starting build...
call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo [SUCCESS] APK compiled successfully!
    echo Location: app\build\outputs\apk\debug\app-debug.apk
    echo ========================================================
) else (
    echo.
    echo [FAILED] Build failed.
)
pause
