#!/usr/bin/env bash
# Snore App - Project Workspace Cleaner

set -e

echo "========================================================"
echo "       Snore App - Project Workspace Cleaner"
echo "========================================================"
echo ""

# 1. Stop gradle daemon
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    echo "[1/3] Stopping Gradle Daemons & running clean..."
    ./gradlew --stop 2>/dev/null || true
    ./gradlew clean 2>/dev/null || true
fi

# 2. Clean build directories & caches
echo "[2/3] Purging build directories and caches..."
rm -rf app/build build .gradle .agents .idea .cxx captures

# 3. Clean stray temp files
echo "[3/3] Removing stray temporary files..."
find . -type f \( -name "*.iml" -o -name "*.log" -o -name "*.tmp" -o -name "*.bak" \) -delete 2>/dev/null || true

echo ""
echo "========================================================"
echo "[SUCCESS] Project workspace cleaned successfully!"
echo "========================================================"
