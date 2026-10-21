param([switch]$SkipBaseline)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$out=Join-Path $projectRoot 'output/stage26'
$env:JAVA_HOME='D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:/BrainChair/sdk'
$env:GRADLE_USER_HOME='D:/BrainChair/gradle-cache'
$python='D:/BrainChair/stage2-test-env/Scripts/python.exe'
$gradle='D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $python "$PSScriptRoot/stage25_extract.py" 'app-debug.apk.1' --out "$out/data"
if($LASTEXITCODE -ne 0){throw 'APK extraction failed'}
& $python "$PSScriptRoot/stage2_oracle.py" --output "$out/synthetic"
if($LASTEXITCODE -ne 0){throw 'Synthetic oracle failed'}
function Prepare-Fixtures([string]$target) {
    New-Item -ItemType Directory -Force -Path "$target/test-fixtures/stage2","$target/test-fixtures/stage25","$target/test-fixtures/stage26" | Out-Null
    Copy-Item -Path "$out/synthetic/*" -Destination "$target/test-fixtures/stage2"
    Copy-Item -Path "$out/data/*" -Destination "$target/test-fixtures/stage25"
}
if(!$SkipBaseline) {
    $before='D:/BrainChair/build-stage26-before'
    & git -C $projectRoot archive --format=zip -o "$out/baseline-source.zip" 1fed131
    if($LASTEXITCODE -ne 0){throw 'Baseline export failed'}
    Expand-Archive -LiteralPath "$out/baseline-source.zip" -DestinationPath $before -Force
    foreach($name in @('SafetyRegressionProbeTest.kt','TrialSafetyReplayTest.kt')) {
        Copy-Item -LiteralPath "$projectRoot/app/src/test/java/com/yourteam/brainchair/signal/$name" -Destination "$before/app/src/test/java/com/yourteam/brainchair/signal/$name"
    }
    Prepare-Fixtures $before
    & $gradle -p $before testDebugUnitTest --tests '*SafetyRegressionProbeTest' --tests '*TrialSafetyReplayTest' --no-daemon --offline --console=plain
    if($LASTEXITCODE -ne 0){throw 'Baseline probe failed'}
    New-Item -ItemType Directory -Force -Path "$out/before" | Out-Null
    Copy-Item -Path "$before/test-fixtures/stage26/*.csv" -Destination "$out/before"
}
$after='D:/BrainChair/build-stage26-after'
New-Item -ItemType Directory -Force -Path $after | Out-Null
& robocopy $projectRoot $after /E /XD .git .gradle .kotlin .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if($LASTEXITCODE -ge 8){throw 'Working source copy failed'}
Prepare-Fixtures $after
Set-Content -LiteralPath "$after/test-fixtures/stage26/fixed.flag" -Value 'Stage 2.6'
& $gradle -p $after testDebugUnitTest assembleDebug lintDebug --no-daemon --offline --console=plain
if($LASTEXITCODE -ne 0){throw 'Stage 2.6 build/tests failed'}
New-Item -ItemType Directory -Force -Path "$out/after","$out/junit" | Out-Null
Copy-Item -Path "$after/test-fixtures/stage26/*.csv" -Destination "$out/after"
Copy-Item -Path "$after/app/build/test-results/testDebugUnitTest/*.xml" -Destination "$out/junit"
Copy-Item -LiteralPath "$after/app/build/reports/lint-results-debug.txt" -Destination "$out/lint.txt"
Copy-Item -LiteralPath "$after/app/build/outputs/apk/debug/app-debug.apk" -Destination "$out/BrainChair-B-stage26.apk"
