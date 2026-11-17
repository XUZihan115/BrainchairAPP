param([string]$Serial='', [string]$Out='',[string]$Adb='adb')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$package='com.yourteam.brainchair.receiver'
if(-not $Serial){
    $devices=@((& $Adb devices) | Select-Object -Skip 1 | Where-Object {$_ -match '^\S+\s+device\s*$'} | ForEach-Object {($_ -split '\s+')[0]})
    if($devices.Count -ne 1){throw 'Connect one authorized device, or choose it with -Serial; no device was modified'}
    $Serial=$devices[0]
}
if(-not $Out){$Out=Join-Path $projectRoot ('output/control-fix/device-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
if(Test-Path -LiteralPath $Out){throw 'Choose a new output directory to preserve earlier logs'}
New-Item -ItemType Directory -Path $Out | Out-Null
$collected=@();$unavailable=@()
foreach($name in @('stage2-status.json','control-diagnostics.jsonl','control-diagnostics.previous.jsonl','stage2-trace.csv')){
    # exec-out does not reliably propagate the remote command's exit code on this adb version.
    # shell uses the exit-status protocol; never save a cat error as if it were a log file.
    & $Adb -s $Serial shell run-as $package test -f "files/$name" *> "$Out/$name.stderr.txt"
    if($LASTEXITCODE -ne 0){$unavailable+=$name;continue}
    $value=& $Adb -s $Serial exec-out run-as $package cat "files/$name" 2> "$Out/$name.stderr.txt"
    if($LASTEXITCODE -eq 0 -and (($value | Select-Object -First 1) -notmatch '^(cat:|run-as:)')){$value | Set-Content -LiteralPath "$Out/$name" -Encoding utf8;$collected+=$name}
    else {$unavailable+=$name}
}
$appPid=(& $Adb -s $Serial shell pidof -s $package | Out-String).Trim()
if($appPid -match '^\d+$'){
    & $Adb -s $Serial logcat -d "--pid=$appPid" -t 600 | Set-Content -LiteralPath "$Out/app-logcat.txt" -Encoding utf8
}
& $Adb -s $Serial shell dumpsys meminfo $package | Set-Content -LiteralPath "$Out/meminfo.txt" -Encoding utf8
@{collectedAt=(Get-Date -Format o);serial=$Serial;files=$collected;unavailable=$unavailable;
    validation='Log collection only; does not assert successful A/B/Unity integration'} | ConvertTo-Json | Set-Content -LiteralPath "$Out/collection.json" -Encoding utf8
Write-Output "Logs collected at $Out"
if($unavailable.Count){Write-Output "Unavailable (service not started, diagnostics disabled, rotation absent, or non-debuggable APK): $($unavailable -join ', ')"}
