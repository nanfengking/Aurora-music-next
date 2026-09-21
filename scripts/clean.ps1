$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targets = @(
  (Join-Path $workspace 'out'),
  (Join-Path $workspace 'release'),
  (Join-Path $workspace 'tsconfig.web.tsbuildinfo')
)

foreach ($target in $targets) {
  if (-not (Test-Path -LiteralPath $target)) { continue }
  $resolved = (Resolve-Path -LiteralPath $target).Path
  if (-not $resolved.StartsWith("$workspace\")) {
    throw "Refusing to clean target outside workspace: $resolved"
  }
  Remove-Item -LiteralPath $resolved -Recurse -Force
}
