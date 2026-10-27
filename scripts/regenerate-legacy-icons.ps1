# Regenerates the legacy launcher bitmaps (ic_launcher / ic_launcher_round)
# from the same geometry as drawable/ic_launcher_foreground.xml: a tilted pen
# writing on a smaller gray document, scaled 1.2x around (54,54) on the 108dp
# canvas, rendered to a rounded-square and a circle with transparency outside.
# ASCII only: PowerShell 5.1 misreads BOM-less UTF-8 scripts.
param(
  [string]$ResDir = "D:\anan\env-check\app\src\main\res"
)

Add-Type -AssemblyName System.Drawing

function New-RoundedRectPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = 2 * $r
  $p.AddArc($x, $y, $d, $d, 180, 90)
  $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
  $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
  $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
  $p.CloseFigure()
  return $p
}

function New-WorldMatrix([float]$s) {
  # canvas scale, then the 1.2x group scale around (54,54)
  $m = New-Object System.Drawing.Drawing2D.Matrix
  $m.Scale($s, $s)
  $m.Translate(54, 54)
  $m.Scale(1.2, 1.2)
  $m.Translate(-54, -54)
  return $m
}

function New-PenRotation {
  $m = New-Object System.Drawing.Drawing2D.Matrix
  $m.RotateAt(45, (New-Object System.Drawing.PointF(57, 61.5)))
  return $m
}

function Draw-Artwork([System.Drawing.Graphics]$g) {
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.Transform = New-WorldMatrix ($g.VisibleClipBounds.Width / 108.0)
  $rot = New-PenRotation

  $bg = [System.Drawing.Color]::FromArgb(0xF1, 0xF2, 0xF0)
  $docFill = [System.Drawing.Color]::FromArgb(0xC9, 0xCF, 0xD2)
  $docStroke = [System.Drawing.Color]::FromArgb(0x56, 0x5B, 0x5E)
  $fold = [System.Drawing.Color]::FromArgb(0x89, 0x97, 0x9F)
  $textLine = [System.Drawing.Color]::FromArgb(0x7A, 0x84, 0x89)
  $ink = [System.Drawing.Color]::FromArgb(0x45, 0x4F, 0x54)
  $penDark = [System.Drawing.Color]::FromArgb(0x31, 0x3A, 0x3F)
  $penBand = [System.Drawing.Color]::FromArgb(0x9A, 0xA6, 0xAC)

  # Document with a dog-ear at the top-right corner.
  $doc = New-Object System.Drawing.Drawing2D.GraphicsPath
  $doc.AddLine(40, 28, 67, 28)
  $doc.AddLine(67, 28, 75, 36)
  $doc.AddLine(75, 36, 75, 73)
  $doc.AddArc(69, 73, 6, 6, 0, 90)
  $doc.AddLine(69, 79, 40, 79)
  $doc.AddArc(34, 73, 6, 6, 90, 90)
  $doc.AddLine(34, 73, 34, 34)
  $doc.AddArc(34, 28, 6, 6, 180, 90)
  $doc.CloseFigure()
  $g.FillPath((New-Object System.Drawing.SolidBrush($docFill)), $doc)
  $docPen = New-Object System.Drawing.Pen($docStroke, 3.0)
  $docPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
  $g.DrawPath($docPen, $doc)

  # Dog-ear fold hint.
  $foldPath = New-Object System.Drawing.Drawing2D.GraphicsPath
  $foldPath.AddLine(67, 29, 67, 36)
  $foldPath.AddLine(67, 36, 74, 36)
  $foldPen = New-Object System.Drawing.Pen($fold, 2.6)
  $foldPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
  $foldPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
  $foldPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
  $g.DrawPath($foldPen, $foldPath)

  # Two text lines.
  $lines = New-Object System.Drawing.Drawing2D.GraphicsPath
  $lines.AddLine(39.5, 43, 64, 43)
  $lines.AddLine(39.5, 51, 57, 51)
  $linePen = New-Object System.Drawing.Pen($textLine, 2.8)
  $linePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
  $linePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
  $g.DrawPath($linePen, $lines)

  # Ink stroke written by the pen.
  $squiggle = New-Object System.Drawing.Drawing2D.GraphicsPath
  $squiggle.AddBeziers(
    [System.Drawing.PointF[]]@(
      (New-Object System.Drawing.PointF(39.5, 68.5)),
      (New-Object System.Drawing.PointF(42.5, 64.5)),
      (New-Object System.Drawing.PointF(45.5, 61.8)),
      (New-Object System.Drawing.PointF(49, 63.8)),
      (New-Object System.Drawing.PointF(52.5, 65.8)),
      (New-Object System.Drawing.PointF(54.5, 67.8)),
      (New-Object System.Drawing.PointF(56.8, 64.0))
    ))
  $inkPen = New-Object System.Drawing.Pen($ink, 3.8)
  $inkPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
  $inkPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
  $g.DrawPath($inkPen, $squiggle)

  # Pen, rotated 45 degrees around its nib tip at (57, 61.5).
  $nib = New-Object System.Drawing.Drawing2D.GraphicsPath
  $nib.AddPolygon([System.Drawing.PointF[]]@(
    (New-Object System.Drawing.PointF(57, 61.5)),
    (New-Object System.Drawing.PointF(54.4, 54.6)),
    (New-Object System.Drawing.PointF(59.6, 54.6))))
  $nib.Transform($rot)
  $g.FillPath((New-Object System.Drawing.SolidBrush($penDark)), $nib)

  $hole = New-Object System.Drawing.Drawing2D.GraphicsPath
  $hole.AddEllipse(57 - 1.05, 56.6 - 1.05, 2.1, 2.1)
  $hole.Transform($rot)
  $g.FillPath((New-Object System.Drawing.SolidBrush($docFill)), $hole)

  $slit = New-Object System.Drawing.Drawing2D.GraphicsPath
  $slit.AddLine(57, 61.5, 57, 57.9)
  $slit.Transform($rot)
  $slitPen = New-Object System.Drawing.Pen($penBand, 0.7)
  $slitPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
  $slitPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
  $g.DrawPath($slitPen, $slit)

  $section = New-RoundedRectPath 53.9 49 6.2 5.6 1.6
  $section.Transform($rot)
  $g.FillPath((New-Object System.Drawing.SolidBrush($penDark)), $section)

  $band = New-Object System.Drawing.Drawing2D.GraphicsPath
  $band.AddRectangle((New-Object System.Drawing.RectangleF(53.6, 47.2, 6.8, 1.8)))
  $band.Transform($rot)
  $g.FillPath((New-Object System.Drawing.SolidBrush($penBand)), $band)

  $body = New-Object System.Drawing.Drawing2D.GraphicsPath
  $body.AddLine(53.6, 47.2, 53.6, 35.5)
  $body.AddBezier(53.6, 35.5, 53.6, 33.533, 55.133, 32.5, 57, 32.5)
  $body.AddBezier(57, 32.5, 58.867, 32.5, 60.4, 33.533, 60.4, 35.5)
  $body.AddLine(60.4, 35.5, 60.4, 47.2)
  $body.CloseFigure()
  $body.Transform($rot)
  $g.FillPath((New-Object System.Drawing.SolidBrush($ink)), $body)
}

function New-Icon([int]$size, [string]$outPath, [bool]$round) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half

  if ($round) {
    $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
    $clip.AddEllipse(0, 0, $size, $size)
    $g.SetClip($clip)
  } else {
    $clip = New-RoundedRectPath 0 0 $size $size ($size * 0.18)
    $g.SetClip($clip)
  }
  $g.Clear([System.Drawing.Color]::FromArgb(0xF1, 0xF2, 0xF0))

  Draw-Artwork $g

  $g.Dispose()
  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Output "wrote $outPath"
}

$densities = @{
  "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192
}
foreach ($density in $densities.Keys) {
  $dir = Join-Path $ResDir "mipmap-$density"
  $size = $densities[$density]
  New-Icon $size (Join-Path $dir "ic_launcher.png") $false
  New-Icon $size (Join-Path $dir "ic_launcher_round.png") $true
  Remove-Item (Join-Path $dir "ic_launcher.webp") -ErrorAction SilentlyContinue
  Remove-Item (Join-Path $dir "ic_launcher_round.webp") -ErrorAction SilentlyContinue
}
Write-Output "legacy webp files removed (replaced by png)"
