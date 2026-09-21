param(
  [string]$OutputPath = (Join-Path $PSScriptRoot '..\resources\icon.png')
)

Add-Type -AssemblyName System.Drawing

function New-RoundedPath([System.Drawing.RectangleF]$Rect, [float]$Radius) {
  $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
  $diameter = $Radius * 2
  $arc = [System.Drawing.RectangleF]::new($Rect.X, $Rect.Y, $diameter, $diameter)
  $path.AddArc($arc, 180, 90)
  $arc.X = $Rect.Right - $diameter
  $path.AddArc($arc, 270, 90)
  $arc.Y = $Rect.Bottom - $diameter
  $path.AddArc($arc, 0, 90)
  $arc.X = $Rect.X
  $path.AddArc($arc, 90, 90)
  $path.CloseFigure()
  return $path
}

$bitmap = [System.Drawing.Bitmap]::new(1024, 1024, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::Transparent)

$tileRect = [System.Drawing.RectangleF]::new(64, 64, 896, 896)
$tilePath = New-RoundedPath $tileRect 224
$gradient = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
  $tileRect,
  [System.Drawing.Color]::FromArgb(255, 255, 99, 123),
  [System.Drawing.Color]::FromArgb(255, 170, 18, 55),
  45
)
$graphics.FillPath($gradient, $tilePath)

$highlight = [System.Drawing.Drawing2D.GraphicsPath]::new()
$highlight.AddEllipse([System.Drawing.RectangleF]::new(-100, -180, 850, 720))
$highlightBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(23, 255, 255, 255))
$graphics.SetClip($tilePath)
$graphics.FillPath($highlightBrush, $highlight)
$graphics.ResetClip()

$white = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
foreach ($bar in @(
  [System.Drawing.RectangleF]::new(300, 468, 72, 226),
  [System.Drawing.RectangleF]::new(476, 354, 72, 340),
  [System.Drawing.RectangleF]::new(652, 414, 72, 280)
)) {
  $barPath = New-RoundedPath $bar 36
  $graphics.FillPath($white, $barPath)
  $barPath.Dispose()
}

$wave = [System.Drawing.Drawing2D.GraphicsPath]::new()
$wave.AddBezier(250, 446, 354, 314, 471, 292, 573, 366)
$wave.AddBezier(573, 366, 655, 426, 709, 431, 777, 354)
$wavePen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 46)
$wavePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$wavePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$wavePen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
$graphics.DrawPath($wavePen, $wave)

$output = [System.IO.Path]::GetFullPath($OutputPath)
$bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)

$wavePen.Dispose()
$wave.Dispose()
$white.Dispose()
$highlightBrush.Dispose()
$highlight.Dispose()
$gradient.Dispose()
$tilePath.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

Write-Output $output
