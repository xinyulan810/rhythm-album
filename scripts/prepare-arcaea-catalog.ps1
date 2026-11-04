param([Parameter(Mandatory=$true)][string]$Revision)
$ErrorActionPreference = 'Stop'
if ($Revision -notmatch '^[0-9a-f]{40}$') { throw 'Use a pinned commit SHA.' }
$root = Split-Path $PSScriptRoot -Parent
$base = "https://raw.githubusercontent.com/DarrenDanielDay/arcaea-toolbelt-data/$Revision"
$data = Invoke-RestMethod "$base/src/data/song-data.json"
$meta = Invoke-RestMethod "$base/src/data/meta.json"
$songs = @($data.songs | ForEach-Object {
    [ordered]@{
        sourceId = $_.id
        title = $_.title_localized.en
        artist = $_.artist
        names = @($_.title_localized.PSObject.Properties.Value | Select-Object -Unique)
        aliases = @($_.alias | Where-Object { $_ } | Select-Object -Unique)
    }
})
if ($songs.Count -lt 300) { throw 'Unexpectedly small source catalogue.' }
$out = Join-Path $root 'android/app/src/main/assets'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$snapshot = [ordered]@{version=1; game='arcaea'; provider='arcaea-toolbelt'; revision=$Revision;
    source="$base/src/data/song-data.json"; sourceVersion=$meta.version; songs=$songs}
$json = $snapshot | ConvertTo-Json -Depth 8 -Compress
[IO.File]::WriteAllText((Join-Path $out 'rhythm-arcaea.json'), $json, (New-Object Text.UTF8Encoding($false)))
Invoke-WebRequest "$base/LICENSE" -OutFile (Join-Path $out 'rhythm-arcaea-LICENSE.txt')
[pscustomobject]@{Songs=$songs.Count;Aliases=($songs | ForEach-Object {$_.aliases.Count} | Measure-Object -Sum).Sum;Bytes=(Get-Item (Join-Path $out 'rhythm-arcaea.json')).Length;SourceVersion=$meta.version;Revision=$Revision}
