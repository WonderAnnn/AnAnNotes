param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$manifestPath = Join-Path $ProjectRoot 'app\build\intermediates\packaged_manifests\debug\processDebugManifestForPackage\AndroidManifest.xml'
$stringsPath = Join-Path $ProjectRoot 'app\src\main\res\values\strings.xml'
$apkPath = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$expectedApplicationId = 'com.anan.anannotes'
$expectedMinSdk = '29'
$expectedAppName = -join [char[]](0x5B89, 0x5B89, 0x7B80, 0x8BB0)
$androidNamespace = 'http://schemas.android.com/apk/res/android'

foreach ($path in @($manifestPath, $stringsPath, $apkPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required build artifact is missing: $path"
    }
}

$manifest = [xml](Get-Content -LiteralPath $manifestPath -Encoding UTF8 -Raw)
$applicationId = $manifest.manifest.GetAttribute('package')
$minSdkNode = $manifest.SelectSingleNode('/manifest/uses-sdk')
$minSdk = if ($null -ne $minSdkNode) { $minSdkNode.GetAttribute('minSdkVersion', $androidNamespace) } else { '' }
$namespaceManager = New-Object System.Xml.XmlNamespaceManager($manifest.NameTable)
$namespaceManager.AddNamespace('android', $androidNamespace)
$internetPermission = $manifest.SelectSingleNode("/manifest/uses-permission[@android:name='android.permission.INTERNET']", $namespaceManager)
$appNameDocument = [xml](Get-Content -LiteralPath $stringsPath -Encoding UTF8 -Raw)
$appNameNode = $appNameDocument.SelectSingleNode("/resources/string[@name='app_name']")
$appName = if ($null -ne $appNameNode) { $appNameNode.InnerText } else { '' }

$checks = [ordered]@{
    'Application ID' = ($applicationId -eq $expectedApplicationId)
    'Minimum SDK' = ($minSdk -eq $expectedMinSdk)
    'No INTERNET permission' = ($null -eq $internetPermission)
    'Application name' = ($appName -eq $expectedAppName)
}

$failed = @()
foreach ($check in $checks.GetEnumerator()) {
    $status = if ($check.Value) { 'PASS' } else { 'FAIL' }
    Write-Output ("{0}: {1}" -f $check.Key, $status)
    if (-not $check.Value) {
        $failed += $check.Key
    }
}

if ($failed.Count -gt 0) {
    throw ("Build contract failed: {0}" -f ($failed -join ', '))
}

Write-Output ("Verified APK: {0}" -f $apkPath)
