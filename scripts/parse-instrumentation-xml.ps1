param([string]$XmlPath)
[xml]$x = Get-Content -LiteralPath $XmlPath -Encoding UTF8
$root = $x.testsuites
if ($null -ne $root) {
  Write-Output ("SUMMARY tests={0} failures={1} errors={2} skipped={3} time={4}s" -f $root.tests, $root.failures, $root.errors, $root.skipped, $root.time)
} else {
  $root = $x.testsuite
  Write-Output ("SUMMARY tests={0} failures={1} errors={2} skipped={3} time={4}s" -f $root.tests, $root.failures, $root.errors, $root.skipped, $root.time)
}
Write-Output "--- skipped ---"
foreach ($ts in $root.testsuite) {
  foreach ($tc in $ts.testcase) {
    if ($null -ne $tc.skipped) { Write-Output ("SKIP {0}#{1}" -f $tc.classname, $tc.name) }
  }
}
Write-Output "--- failures ---"
foreach ($ts in $root.testsuite) {
  foreach ($tc in $ts.testcase) {
    if ($null -ne $tc.failure -or $null -ne $tc.error) {
      Write-Output ("FAIL {0}#{1}" -f $tc.classname, $tc.name)
      $msg = if ($null -ne $tc.failure) { $tc.failure.message } else { $tc.error.message }
      if ($msg) { $t = $msg.ToString(); Write-Output ("   " + $t.Substring(0, [Math]::Min(300, $t.Length))) }
    }
  }
}
