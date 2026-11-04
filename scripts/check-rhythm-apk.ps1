param(
    [string]$ApkPath,
    [string]$BaselinePath
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $ApkPath) {
    $ApkPath = Join-Path $projectRoot 'build/app/outputs/flutter-apk/app-libre-release.apk'
}
$apk = Get-Item -LiteralPath $ApkPath
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
try {
    $entries = @($archive.Entries | ForEach-Object {
        [pscustomobject]@{
            Path = $_.FullName
            Bytes = $_.CompressedLength
            UncompressedBytes = $_.Length
        }
    })
    $native = @($entries | Where-Object { $_.Path -like 'lib/*' })
    $foreign = @($native | Where-Object { $_.Path -notlike 'lib/arm64-v8a/*' })
    if ($foreign.Count) {
        throw "Unexpected non-ARM64 libraries: $($foreign.Path -join ', ')"
    }
    foreach ($name in @('libflutter.so', 'libapp.so', 'libmlkit_google_ocr_pipeline.so', 'librhythm_codec.so')) {
        if ($native.Path -notcontains "lib/arm64-v8a/$name") {
            throw "Missing required ARM64 library: $name"
        }
    }
    $removed = @($entries | Where-Object { $_.Path -match '(?i)c2pa|libmpv\.so|mediakit|media_kit' })
    if ($removed.Count) {
        throw "Removed dependency still packaged: $($removed.Path -join ', ')"
    }
    foreach ($script in @('Hani', 'Jpan', 'Latn')) {
        if (-not ($entries.Path -match "/${script}_ctc/optical/lstm_model\.fb$")) {
            throw "Missing bundled OCR model: $script"
        }
    }
    $groups = @($entries | Group-Object {
        if ($_.Path -like 'lib/*') { 'Native libraries' }
        elseif ($_.Path -like 'assets/mlkit-*') { 'OCR model assets' }
        elseif ($_.Path -like 'assets/flutter_assets/*') { 'Flutter assets' }
        elseif ($_.Path -match '^classes\d*\.dex$') { 'DEX code' }
        else { 'Other entries' }
    } | ForEach-Object {
        [pscustomobject]@{
            Group = $_.Name
            Bytes = [long](($_.Group | Measure-Object Bytes -Sum).Sum)
            Count = $_.Count
        }
    } | Sort-Object Bytes -Descending)
    $baselineBytes = $null
    $savedBytes = $null
    $savedPercent = $null
    if ($BaselinePath) {
        $baselineBytes = (Get-Item -LiteralPath $BaselinePath).Length
        $savedBytes = $baselineBytes - $apk.Length
        $savedPercent = [math]::Round(100 * $savedBytes / $baselineBytes, 2)
    }
    [pscustomobject]@{
        Apk = $apk.FullName
        SHA256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
        Bytes = $apk.Length
        MiB = [math]::Round($apk.Length / 1MB, 3)
        BaselineBytes = $baselineBytes
        SavedBytes = $savedBytes
        SavedPercent = $savedPercent
        ContainerOverheadBytes = $apk.Length - [long](($entries | Measure-Object Bytes -Sum).Sum)
        Groups = $groups
        NativeLibraries = @($native | Sort-Object Bytes -Descending)
        Checks = 'ARM64 only; Flutter/OCR/codec present; three OCR models present; C2PA/MPV absent'
    } | ConvertTo-Json -Depth 6
} finally {
    $archive.Dispose()
}
