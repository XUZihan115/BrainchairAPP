$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'D:/BrainChair/sdk'
$env:GRADLE_USER_HOME = 'D:/BrainChair/gradle-cache'
$buildRoot = 'D:/BrainChair/build-stage2'
$oraclePython = 'D:/BrainChair/stage2-test-env/Scripts/python.exe'
if (!(Test-Path -LiteralPath $oraclePython)) { throw 'Missing isolated SciPy test environment; see docs/stage2/README.md' }
& $oraclePython (Join-Path $projectRoot 'scripts/stage2_oracle.py') --output (Join-Path $projectRoot 'output/stage2/fixtures')
if ($LASTEXITCODE -ne 0) { throw 'Independent oracle generation failed' }
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& robocopy $projectRoot $buildRoot /E /XD .git .gradle .kotlin .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { throw 'Build-copy synchronization failed' }
New-Item -ItemType Directory -Force -Path "$buildRoot/test-fixtures/stage2" | Out-Null
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'output/stage2/fixtures') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination "$buildRoot/test-fixtures/stage2" }
& 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -p $buildRoot assembleDebug testDebugUnitTest lintDebug --no-daemon --offline --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed' }
$result = Join-Path $projectRoot 'output/stage2'
New-Item -ItemType Directory -Force -Path $result | Out-Null
Copy-Item -LiteralPath "$buildRoot/app/build/outputs/apk/debug/app-debug.apk" -Destination "$result/BrainChair-B-stage2.apk"
New-Item -ItemType Directory -Force -Path "$result/junit" | Out-Null
Get-ChildItem -LiteralPath "$buildRoot/app/build/test-results/testDebugUnitTest" -Filter '*.xml' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path "$result/junit" $_.Name)
}
Copy-Item -LiteralPath "$buildRoot/app/build/reports/lint-results-debug.txt" -Destination "$result/lint.txt"
Write-Host "Stage 2 APK and evidence: $result"
