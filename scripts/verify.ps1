param(
  [switch]$SkipBuild,
  [switch]$RequireInstaller
)

$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

$required = @(
  'electron/main.ts',
  'electron/preload.ts',
  'src/main/database.ts',
  'src/main/ipc.ts',
  'src/main/protocol.ts',
  'src/main/webdav.ts',
  'src/services/audioEngine.ts',
  'src/stores/playerStore.ts',
  'LICENSE'
  'PRIVACY.md'
  'resources/ffmpeg/ffmpeg.exe'
  'resources/ffmpeg/LICENSE'
)

foreach ($relativePath in $required) {
  $path = Join-Path $workspace $relativePath
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Required project file is missing: $relativePath"
  }
}

$sourceJavaScript = Get-ChildItem -LiteralPath (Join-Path $workspace 'src') -Recurse -File |
  Where-Object { $_.Extension -in @('.js', '.jsx') }
if ($sourceJavaScript) {
  throw "Generated or duplicate JavaScript was found under src/: $($sourceJavaScript.FullName -join ', ')"
}

if (-not $SkipBuild) {
  Push-Location $workspace
  try {
    & npm.cmd run test:recommendations
    if ($LASTEXITCODE -ne 0) { throw "Recommendation regression tests failed" }
    & npm.cmd run test:i18n
    if ($LASTEXITCODE -ne 0) { throw "Translation regression tests failed" }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed with exit code $LASTEXITCODE" }
  } finally {
    Pop-Location
  }
}

$buildOutputs = @(
  'out/main/index.js',
  'out/preload/index.js',
  'out/renderer/index.html'
)
foreach ($relativePath in $buildOutputs) {
  $path = Join-Path $workspace $relativePath
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Expected build output is missing: $relativePath"
  }
}

if ($RequireInstaller) {
  $manifest = Get-Content -LiteralPath (Join-Path $workspace 'package.json') -Raw | ConvertFrom-Json
  $applicationExe = Join-Path $workspace "release/win-unpacked/$($manifest.build.productName).exe"
  if (-not (Test-Path -LiteralPath $applicationExe -PathType Leaf)) {
    throw 'Unpacked application executable is missing. Verify the local packaging output before distribution.'
  }
  $applicationMetadata = (Get-Item -LiteralPath $applicationExe).VersionInfo
  $versionCore = ($manifest.version -split '[-+]')[0]
  if ($applicationMetadata.ProductName -ne $manifest.build.productName -or
      $applicationMetadata.ProductVersion -notmatch ('^' + [regex]::Escape($versionCore) + '([.+-]|$)')) {
    throw 'Application executable metadata does not match this product/version; do not distribute an unedited Electron binary.'
  }
  $installer = Join-Path $workspace "release/Aurora-Music-Next-$($manifest.version)-Setup.exe"
  if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw 'Windows installer is missing. Run npm run pack:win first.'
  }
  if ((Get-Item -LiteralPath $installer).Length -lt 50MB) {
    throw 'Windows installer is unexpectedly small.'
  }
  $latestPath = Join-Path $workspace 'release/latest.yml'
  if (-not (Test-Path -LiteralPath $latestPath -PathType Leaf)) {
    throw 'Update manifest latest.yml is missing.'
  }
  $latest = Get-Content -LiteralPath $latestPath -Raw
  if ($latest -notmatch [regex]::Escape("version: $($manifest.version)")) {
    throw 'Update manifest version does not match package.json.'
  }
  if ($latest -notmatch [regex]::Escape("url: $(Split-Path $installer -Leaf)")) {
    throw 'Update manifest does not reference the current installer.'
  }
  $sha = [Security.Cryptography.SHA512]::Create()
  $inputStream = [IO.File]::OpenRead($installer)
  try { $installerHash = [Convert]::ToBase64String($sha.ComputeHash($inputStream)) }
  finally { $inputStream.Dispose(); $sha.Dispose() }
  if ($latest -notmatch [regex]::Escape("sha512: $installerHash")) {
    throw 'Update manifest checksum does not match the installer.'
  }
}

Write-Host 'Aurora verification passed.' -ForegroundColor Green
