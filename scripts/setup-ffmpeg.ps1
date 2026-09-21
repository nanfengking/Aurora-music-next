$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$binary = Join-Path $workspace 'resources/ffmpeg/ffmpeg.exe'
if (Test-Path -LiteralPath $binary) { Write-Host 'FFmpeg already exists; no files changed.'; exit 0 }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('aurora-ffmpeg-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
  $zip = Join-Path $temporary 'ffmpeg.zip'
  Invoke-WebRequest -UseBasicParsing -Uri 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip' -OutFile $zip
  $hasher = [Security.Cryptography.SHA256]::Create()
  $inputStream = [IO.File]::OpenRead($zip)
  try { $hash = [BitConverter]::ToString($hasher.ComputeHash($inputStream)).Replace('-', '') }
  finally { $inputStream.Dispose(); $hasher.Dispose() }
  if ($hash -ne '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba') { throw 'FFmpeg checksum mismatch; nothing installed.' }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [IO.Compression.ZipFile]::ExtractToDirectory($zip, (Join-Path $temporary 'extracted'))
  $found = @(Get-ChildItem -LiteralPath (Join-Path $temporary 'extracted') -Filter ffmpeg.exe -File -Recurse)
  if ($found.Count -ne 1) { throw 'Unexpected FFmpeg archive layout.' }
  Copy-Item -LiteralPath $found[0].FullName -Destination $binary
  Write-Host 'Verified FFmpeg installed for local builds. Read resources/ffmpeg/SOURCE.md before distributing binaries.'
} finally {
  $resolved = [IO.Path]::GetFullPath($temporary)
  $allowed = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\aurora-ffmpeg-'
  if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside the temporary FFmpeg directory.' }
  Remove-Item -LiteralPath $resolved -Force -Recurse
}
