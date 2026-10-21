$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'D:/BrainChair/sdk'
$env:GRADLE_USER_HOME = 'D:/BrainChair/gradle-cache'
$buildRoot = 'D:/BrainChair/build-stage1'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& robocopy $projectRoot $buildRoot /E /XD .git .gradle .kotlin .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { throw 'Build-copy synchronization failed' }
& 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -p $buildRoot assembleDebug testDebugUnitTest lintDebug --no-daemon --offline --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed' }
$result = Join-Path $projectRoot 'output/stage1'
New-Item -ItemType Directory -Force -Path $result | Out-Null
Copy-Item -LiteralPath "$buildRoot/app/build/outputs/apk/debug/app-debug.apk" -Destination "$result/BrainChair-B-stage1.apk"
New-Item -ItemType Directory -Force -Path "$result/junit" | Out-Null
Get-ChildItem -LiteralPath "$buildRoot/app/build/test-results/testDebugUnitTest" -Filter '*.xml' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path "$result/junit" $_.Name)
}
Copy-Item -LiteralPath "$buildRoot/app/build/reports/lint-results-debug.txt" -Destination "$result/lint.txt"
Write-Host "Stage 1 APK and evidence: $result"
