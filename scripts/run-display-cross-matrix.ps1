<#
.SYNOPSIS
  Runs the display cross matrix (cutout x width dp x font scale x nav mode x theme)
  for the AnAnNotes insets/system-bar instrumented classes on a booted emulator.
  ASCII-only on purpose: PowerShell 5.1 misreads BOM-less UTF-8 scripts.

.DESCRIPTION
  Applies each combination via adb (overlay enable / wm density override /
  font_scale / navigation_mode / ui night), runs the given classes through
  am instrument (no Gradle uninstall), and writes a CSV. Settings are restored
  afterwards. Full crossing example (252 combos - plan several hours):

  .\run-display-cross-matrix.ps1 -Cutouts hole,tall,double,corner,waterfall,pixel_4a,emu01 `
    -WidthDp 320,360,400 -FontScales 1.0,1.5,2.0 -NavModes 0,2 -Themes light,dark

  The 2026-10-04 verification ran 42 targeted combos on API 36; the handover
  guide lists the broader API 29/35 crossing as outstanding.
#>
param(
  [string]$Adb = "F:\AndroidDev\Sdk\platform-tools\adb.exe",
  [string]$ApkDebug = "D:\anan\env-check\app\build\outputs\apk\debug\app-debug.apk",
  [string]$ApkAndroidTest = "D:\anan\env-check\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
  [string[]]$Cutouts = @("hole"),
  [string[]]$WidthDp = @("none"),
  [string[]]$FontScales = @("1.0"),
  [string[]]$NavModes = @("2"),
  [string[]]$Themes = @("light"),
  [string]$Classes = "com.anan.anannotes.ui.SystemWindowInsetsTest,com.anan.anannotes.ui.PageInsetsTest,com.anan.anannotes.ui.SystemBarAppearanceTest,com.anan.anannotes.ui.SecondaryPageLayoutTest",
  [string]$OutCsv = "D:\anan\outputs\display-cross-matrix.csv"
)

$ErrorActionPreference = "Continue"  # adb/device hiccups must never kill a multi-hour run; handle per-combo.
# Accept both array input and comma-joined strings.
$Cutouts = @($Cutouts | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$WidthDp = @($WidthDp | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$FontScales = @($FontScales | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$NavModes = @($NavModes | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$Themes = @($Themes | ForEach-Object { $_ -split ',' } | Where-Object { $_ })

function Invoke-AdbShell([string]$Command) {
  # adb prints progress ("Success" etc.) to stderr; never treat that as failure.
  $o = & $Adb shell $Command 2>$null
  return ($o -join " ")
}

$AllCutoutOverlays = @(
  "com.android.internal.display.cutout.emulation.hole",
  "com.android.internal.display.cutout.emulation.tall",
  "com.android.internal.display.cutout.emulation.double",
  "com.android.internal.display.cutout.emulation.corner",
  "com.android.internal.display.cutout.emulation.waterfall",
  "com.android.internal.display.cutout.emulation.emu01",
  "com.android.internal.emulation.pixel_4a"
)
$CutoutToOverlay = @{
  hole = "com.android.internal.display.cutout.emulation.hole"
  tall = "com.android.internal.display.cutout.emulation.tall"
  double = "com.android.internal.display.cutout.emulation.double"
  corner = "com.android.internal.display.cutout.emulation.corner"
  waterfall = "com.android.internal.display.cutout.emulation.waterfall"
  emu01 = "com.android.internal.display.cutout.emulation.emu01"
  pixel_4a = "com.android.internal.emulation.pixel_4a"
}
# width dp -> density override (1080 px wide panel: density = 1080 / dp * 160)
$WidthDpToDensity = @{ "320" = "540"; "360" = "480"; "400" = "432"; "none" = "" }

$OriginalNav = (Invoke-AdbShell "settings get secure navigation_mode").Trim()
Write-Output "installing APKs..."
& $Adb install -r $ApkDebug 2>$null | Out-Null
& $Adb install -r $ApkAndroidTest 2>$null | Out-Null
if (-not ((Invoke-AdbShell "pm list packages") -match "anannotes\.test")) {
  throw "androidTest APK install failed; aborting matrix"
}

if (-not (Test-Path $OutCsv)) { "timestamp,cutout,width_dp,font_scale,nav_mode,theme,verdict,failures" | Set-Content -Encoding ASCII $OutCsv }
# Rows are appended to the CSV immediately: a crashed emulator or host must not
# discard hours of results (the first full run lost 28 combos this way).
function Append-Row([string]$row) { Add-Content -Encoding ASCII -Path $OutCsv -Value $row }
function Test-DeviceHealthy {
  return ((Invoke-AdbShell "settings get system font_scale") -match "^[0-9.]+$")
}
$comboTotal = $Cutouts.Count * $WidthDp.Count * $FontScales.Count * $NavModes.Count * $Themes.Count
$comboIndex = 0
$failedPattern = "^\d+\) (\w+)\("
try {
  foreach ($cutout in $Cutouts) {
    foreach ($dp in $WidthDp) {
      foreach ($fs in $FontScales) {
        foreach ($nav in $NavModes) {
          foreach ($theme in $Themes) {
            $comboIndex++
            Write-Output "== [$comboIndex/$comboTotal] $cutout/${dp}dp/fs$fs/nav$nav/$theme =="
            $deviceOk = $false
            foreach ($attempt in 1..5) {
              if (Test-DeviceHealthy) { $deviceOk = $true; break }
              Write-Output "   device unhealthy, waiting (attempt $attempt/5)..."
              Start-Sleep -Seconds 20
            }
            if (-not $deviceOk) {
              Write-Output "   -> DEVICE-UNHEALTHY, aborting run (keep CSV rows so far)"
              Append-Row "$(Get-Date -Format s),$cutout,$dp,$fs,$nav,$theme,DEVICE-UNHEALTHY,"""
              break
            }
            foreach ($overlay in $AllCutoutOverlays) {
              [void](Invoke-AdbShell "cmd overlay disable $overlay")
            }
            if ($cutout -ne "none") { [void](Invoke-AdbShell "cmd overlay enable $($CutoutToOverlay[$cutout])") }
            $density = $WidthDpToDensity[$dp]
            if ($density) { [void](Invoke-AdbShell "wm density $density") }
            else { [void](Invoke-AdbShell "wm density reset") }
            [void](Invoke-AdbShell "settings put system font_scale $fs")
            [void](Invoke-AdbShell "settings put secure navigation_mode $nav")
            [void](Invoke-AdbShell "cmd uimode night $(if ($theme -eq 'dark') { 'yes' } else { 'no' })")
            Start-Sleep -Seconds 3

            $log = (& $Adb shell am instrument -w -e class $Classes com.anan.anannotes.test/androidx.test.runner.AndroidJUnitRunner 2>$null) -join "`n"
            $verdict = "ERROR"
            if ($log -match "OK \(") { $verdict = "PASS" }
            elseif ($log -match "Tests run: (\d+),\s*Failures: (\d+)") { $verdict = "FAIL($($Matches[1])/$($Matches[2]))" }
            $failed = ([regex]::Matches($log, $failedPattern) | ForEach-Object { $_.Groups[1].Value }) -join ";"
            Write-Output "   -> $verdict $failed"
            Append-Row "$(Get-Date -Format s),$cutout,$dp,$fs,$nav,$theme,$verdict,""$failed"""
          }
        }
      }
    }
  }
} finally {
  Write-Output "restoring device settings..."
  foreach ($overlay in $AllCutoutOverlays) { [void](Invoke-AdbShell "cmd overlay disable $overlay") }
  [void](Invoke-AdbShell "wm density reset")
  [void](Invoke-AdbShell "settings put system font_scale 1.0")
  [void](Invoke-AdbShell "settings put secure navigation_mode $OriginalNav")
  [void](Invoke-AdbShell "cmd uimode night no")
}
Write-Output "csv (appended incrementally): $OutCsv"
