param([Parameter(ValueFromRemainingArguments = $true)][string[]]$FlutterArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:PUB_CACHE = Join-Path $projectRoot '.tool-cache/pub'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tool-cache/gradle'
$env:TEMP = (New-Item -ItemType Directory -Force (Join-Path $projectRoot '.tool-cache/tmp')).FullName
$env:TMP = $env:TEMP
$env:APPDATA = (New-Item -ItemType Directory -Force (Join-Path $projectRoot '.tool-cache/appdata')).FullName
$env:LOCALAPPDATA = (New-Item -ItemType Directory -Force (Join-Path $projectRoot '.tool-cache/localappdata')).FullName
$env:ANDROID_USER_HOME = (New-Item -ItemType Directory -Force (Join-Path $projectRoot '.tool-cache/android-user')).FullName
$env:CI = 'true'
$env:FLUTTER_SUPPRESS_ANALYTICS = 'true'
# Follow an existing simple Windows proxy for these build child processes only.
# Do not change system settings, disable TLS checks, or embed credentials.
$internet = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
if ($internet.ProxyEnable -eq 1 -and $internet.ProxyServer -match '^([^:;=]+):(\d+)$') {
    $buildProxyHost = $Matches[1]
    $buildProxyPort = $Matches[2]
    if (-not $env:HTTPS_PROXY) { $env:HTTPS_PROXY = "http://${buildProxyHost}:${buildProxyPort}" }
    if (-not $env:HTTP_PROXY) { $env:HTTP_PROXY = $env:HTTPS_PROXY }
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Dhttp.proxyHost=$buildProxyHost -Dhttp.proxyPort=$buildProxyPort -Dhttps.proxyHost=$buildProxyHost -Dhttps.proxyPort=$buildProxyPort"
}
$gitCommand = Get-Command git -ErrorAction SilentlyContinue
if ($gitCommand) {
    $gitRoot = Split-Path (Split-Path $gitCommand.Source -Parent) -Parent
    $perlBin = Join-Path $gitRoot 'usr/bin'
    if (Test-Path (Join-Path $perlBin 'perl.exe')) { $env:PATH = "$perlBin;$env:PATH" }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $projectRoot '.tool-cache/android-sdk' }
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot '.flutter/bin/flutter.bat') @FlutterArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
