@echo off
setlocal enabledelayedexpansion

title Snore App - Project Cleaner

echo ========================================================
echo        Snore App - Project Workspace Cleaner
echo ========================================================
echo.

:: Configure environment if not already set
if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\JetBrains\JetBrains Rider 2025.3.3\jbr" (
        set "JAVA_HOME=C:\Program Files\JetBrains\JetBrains Rider 2025.3.3\jbr"
    )
)
if "%ANDROID_HOME%"=="" (
    if exist "D:\Programming\android\sdk" (
        set "ANDROID_HOME=D:\Programming\android\sdk"
    )
)

:: 1. Stop Gradle Daemons to release locked jars/files
echo [1/4] Stopping Gradle Daemons...
if exist "gradlew.bat" (
    call gradlew.bat --stop >nul 2>&1
)

:: 2. Run Gradle clean task
echo [2/4] Running Gradle clean...
if exist "gradlew.bat" (
    call gradlew.bat clean >nul 2>&1
)

:: 3. Removing build outputs & intermediate caches...
echo [3/4] Purging build caches and temporary directories...

if exist "app\build" (
    echo   - Deleting app\build ...
    rd /s /q "app\build" >nul 2>&1
)

if exist "build" (
    echo   - Deleting root build\ ...
    rd /s /q "build" >nul 2>&1
)

if exist ".gradle" (
    echo   - Deleting .gradle\ caches ...
    rd /s /q ".gradle" >nul 2>&1
)

if exist ".agents" (
    echo   - Deleting .agents\ teamwork logs ...
    rd /s /q ".agents" >nul 2>&1
)

if exist ".idea" (
    echo   - Deleting .idea\ IDE cache ...
    rd /s /q ".idea" >nul 2>&1
)

if exist ".cxx" (
    echo   - Deleting .cxx\ NDK cache ...
    rd /s /q ".cxx" >nul 2>&1
)

if exist "captures" (
    echo   - Deleting captures\ ...
    rd /s /q "captures" >nul 2>&1
)

:: 4. Clean stray iml, log, tmp, bak files
echo [4/4] Removing stray temporary files (*.iml, *.log, *.tmp, *.bak)...
del /s /q /f *.iml >nul 2>&1
del /s /q /f *.log >nul 2>&1
del /s /q /f *.tmp >nul 2>&1
del /s /q /f *.bak >nul 2>&1

echo.
echo ========================================================
echo [SUCCESS] Project workspace cleaned successfully!
echo All build artifacts, dex caches, temporary files, and
echo teamwork agent logs have been purged.
echo ========================================================
echo.
pause
