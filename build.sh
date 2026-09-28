#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
mkdir -p .build-output
# One validation boundary. Logs are local; this never publishes or deploys anything.
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  2>&1 | tee .build-output/android-build.log
printf '\n%s\n' 'Gradle tasks completed. Debug APK: app/build/outputs/apk/debug/app-debug.apk'
