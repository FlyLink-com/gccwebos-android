[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
$Jar = Join-Path $Root 'gradle/wrapper/gradle-wrapper.jar'
$Expected = '55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c'
$Uri = 'https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar'
if (Test-Path -LiteralPath $Jar) {
    if ((Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
        throw 'Wrapper checksum mismatch. Refusing to execute or overwrite.'
    }
    exit 0
}
$null = New-Item -ItemType Directory -Force -Path (Split-Path $Jar -Parent)
$Temp = "$Jar.$([Guid]::NewGuid().ToString('N')).tmp"
try {
    Write-Host 'Fetching the checksum-pinned official Gradle 9.4.1 wrapper JAR...'
    Invoke-WebRequest -Uri $Uri -OutFile $Temp -UseBasicParsing -TimeoutSec 90
    if ((Get-FileHash -LiteralPath $Temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
        throw 'Downloaded wrapper checksum mismatch. Nothing will be executed.'
    }
    Move-Item -LiteralPath $Temp -Destination $Jar
    Write-Host 'Official wrapper JAR SHA-256 verified.'
} finally {
    if (Test-Path -LiteralPath $Temp) { Remove-Item -LiteralPath $Temp -Force }
}
