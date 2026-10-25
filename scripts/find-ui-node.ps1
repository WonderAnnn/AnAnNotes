param([string]$XmlPath, [string]$Labels = 'Download,USE THIS FOLDER,Show roots,SAVE HERE,downloads,Downloads')
[xml]$x = Get-Content -LiteralPath $XmlPath -Encoding UTF8
$wanted = $Labels.Split(',')
foreach ($n in $x.SelectNodes('//*')) {
  $t = $n.GetAttribute('text')
  $d = $n.GetAttribute('content-desc')
  if (($wanted -contains $t) -or ($wanted -contains $d)) {
    Write-Output ("{0} | desc={1} | clickable={2} | bounds={3}" -f $t, $d, $n.GetAttribute('clickable'), $n.GetAttribute('bounds'))
  }
}
