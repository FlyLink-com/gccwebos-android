[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$null = New-Item -ItemType Directory -Force -Path '.build-output'
# Native stderr can contain Gradle status messages. Preserve the process exit code.
$ErrorActionPreference = 'Continue'
& .\gradlew.bat --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug 2>&1 |
    Tee-Object -FilePath '.build-output/android-build.log'
$Code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($Code -ne 0) { throw "Android build stopped (exit $Code). See .build-output/android-build.log." }
$Apk = 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $Apk)) { throw 'Gradle returned success but no Debug APK was found.' }
Get-FileHash $Apk -Algorithm SHA256
Write-Host "Debug APK: $Apk"
