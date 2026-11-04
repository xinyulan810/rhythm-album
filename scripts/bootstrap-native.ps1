$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$vendorPath = Join-Path $projectRoot 'experiments/vendor/aom'
$revision = '9354161f7c9937a40a3815c84f545009ede57fe1'
if (-not (Test-Path $vendorPath)) {
    New-Item -ItemType Directory -Force (Split-Path $vendorPath -Parent) | Out-Null
    & git clone https://aomedia.googlesource.com/aom $vendorPath
    if ($LASTEXITCODE -ne 0) { throw 'libaom clone failed' }
    & git -C $vendorPath checkout --detach $revision
    if ($LASTEXITCODE -ne 0) { throw 'libaom checkout failed' }
}
$actual = & git -C $vendorPath rev-parse HEAD
if ($actual -ne $revision) { throw "Unexpected libaom revision: $actual. Existing files left untouched." }
Write-Output "libaom ready at $revision; retain LICENSE and PATENTS."
$jpegPath = Join-Path $projectRoot 'experiments/vendor/libjpeg-turbo'
$jpegRevision = '4e151a4ad91001b3aa8c2ece2205c15f487ce320'
if (-not (Test-Path -LiteralPath $jpegPath)) {
    & git clone --depth 1 --branch 3.1.2 https://github.com/libjpeg-turbo/libjpeg-turbo.git $jpegPath
    if ($LASTEXITCODE -ne 0) { throw 'libjpeg-turbo clone failed' }
}
$jpegActual = & git -C $jpegPath rev-parse HEAD
if ($jpegActual -ne $jpegRevision) { throw "Unexpected libjpeg-turbo revision: $jpegActual" }
Write-Output "libjpeg-turbo ready at $jpegRevision; retain LICENSE.md."
$davPath = Join-Path $projectRoot 'experiments/vendor/dav1d'
$davRevision = '42b2b24fb8819f1ed3643aa9cf2a62f03868e3aa'
if (-not (Test-Path -LiteralPath $davPath)) {
    & git clone --depth 1 --branch 1.5.1 https://code.videolan.org/videolan/dav1d.git $davPath
    if ($LASTEXITCODE -ne 0) { throw 'dav1d clone failed' }
}
$davActual = & git -C $davPath rev-parse HEAD
if ($davActual -ne $davRevision) { throw "Unexpected dav1d revision: $davActual" }
& python -m pip install --disable-pip-version-check --target (Join-Path $projectRoot '.tool-cache/python-tools') meson==1.7.2
if ($LASTEXITCODE -ne 0) { throw 'Meson setup failed' }
Write-Output "dav1d ready at $davRevision; retain COPYING."
