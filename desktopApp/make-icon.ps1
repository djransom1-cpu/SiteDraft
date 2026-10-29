# Builds the desktop app icon from the Android launcher art.
# The foreground JPG carries a fake transparency checkerboard; this keys out those light grey
# pixels, puts the pencil-and-square glyph on a white rounded tile (as on the tablet), and writes
# icon.png (1024) plus a multi-size icon.ico for the Windows installer and title bar.
# Run from the repo root with Windows PowerShell 5.1: powershell -ExecutionPolicy Bypass -File desktopApp\make-icon.ps1
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "app\src\main\res\drawable\ic_launcher_foreground_final.jpg"
$outPng = Join-Path $PSScriptRoot "icon.png"
$outIco = Join-Path $PSScriptRoot "icon.ico"
$resPng = Join-Path $PSScriptRoot "src\main\resources\icon.png"

$jpg = [System.Drawing.Bitmap]::FromFile($src)
$w = $jpg.Width; $h = $jpg.Height
$glyph = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
$srcData = $jpg.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$dstData = $glyph.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$n = $srcData.Stride * $h
$bytes = New-Object byte[] $n
[System.Runtime.InteropServices.Marshal]::Copy($srcData.Scan0, $bytes, 0, $n)
for ($i = 0; $i -lt $n; $i += 4) {
    $b = $bytes[$i]; $g = $bytes[$i + 1]; $r = $bytes[$i + 2]
    $max = [Math]::Max($r, [Math]::Max($g, $b)); $min = [Math]::Min($r, [Math]::Min($g, $b))
    # Checkerboard = light and nearly grey. The glyph is saturated orange/blue or dark grey.
    if ($max -gt 175 -and ($max - $min) -lt 28) { $bytes[$i + 3] = 0 } else { $bytes[$i + 3] = 255 }
}
[System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $dstData.Scan0, $n)
$jpg.UnlockBits($srcData); $glyph.UnlockBits($dstData); $jpg.Dispose()

function Tile([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $gfx.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gfx.Clear([System.Drawing.Color]::Transparent)
    # White rounded tile with a thin blueprint-blue edge.
    $r = [int]($size * 0.22)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($size - $d - 1, 0, $d, $d, 270, 90)
    $path.AddArc($size - $d - 1, $size - $d - 1, $d, $d, 0, 90)
    $path.AddArc(0, $size - $d - 1, $d, $d, 90, 90)
    $path.CloseFigure()
    $gfx.FillPath([System.Drawing.Brushes]::White, $path)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 0, 51, 102)), ([Math]::Max(1, $size / 64))
    $gfx.DrawPath($pen, $path)
    # Glyph fills 84% of the tile.
    $inset = [int]($size * 0.08)
    $gfx.DrawImage($glyph, $inset, $inset, $size - 2 * $inset, $size - 2 * $inset)
    $gfx.Dispose()
    return $bmp
}

$big = Tile 1024
$big.Save($outPng, [System.Drawing.Imaging.ImageFormat]::Png)
New-Item -ItemType Directory -Force (Split-Path -Parent $resPng) | Out-Null
$big.Save($resPng, [System.Drawing.Imaging.ImageFormat]::Png)
$big.Dispose()

# ICO with PNG-compressed entries (Windows Vista and later read these).
$sizes = @(256, 128, 64, 48, 32, 16)
$blobs = @()
foreach ($s in $sizes) {
    $t = Tile $s
    $ms = New-Object System.IO.MemoryStream
    $t.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $blobs += ,($ms.ToArray())
    $t.Dispose(); $ms.Dispose()
}
$fs = [System.IO.File]::Create($outIco)
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($k = 0; $k -lt $sizes.Count; $k++) {
    $s = $sizes[$k]
    $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32)
    $bw.Write([uint32]$blobs[$k].Length); $bw.Write([uint32]$offset)
    $offset += $blobs[$k].Length
}
foreach ($blob in $blobs) { $bw.Write($blob) }
$bw.Close(); $fs.Close()
$glyph.Dispose()
Write-Output "Wrote $outPng, $resPng and $outIco"
