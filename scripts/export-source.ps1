$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $workspace
try {
  & node scripts/privacy-check.cjs
  if ($LASTEXITCODE -ne 0) { throw 'Privacy check failed; export cancelled.' }
  $files = (& node scripts/public-files.cjs) | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or -not $files) { throw 'Source manifest failed.' }
  $manifest = Get-Content -LiteralPath package.json -Raw | ConvertFrom-Json
  $destination = Join-Path $workspace "release/Aurora-Music-Next-$($manifest.version)-Source.zip"
  New-Item -ItemType Directory -Force -Path (Join-Path $workspace 'release') | Out-Null
  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $stream = [IO.File]::Open($destination, [IO.FileMode]::Create)
  $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
  try {
    foreach ($relative in $files) {
      $source = [IO.Path]::GetFullPath((Join-Path $workspace $relative))
      if (-not $source.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source outside workspace.' }
      [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $source, $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
  } finally { $archive.Dispose(); $stream.Dispose() }
  $hasher = [Security.Cryptography.SHA256]::Create()
  $inputStream = [IO.File]::OpenRead($destination)
  try { $hash = [BitConverter]::ToString($hasher.ComputeHash($inputStream)).Replace('-', '') }
  finally { $inputStream.Dispose(); $hasher.Dispose() }
  Write-Host "Source archive: $destination"
  Write-Host "SHA256: $hash"
} finally { Pop-Location }
