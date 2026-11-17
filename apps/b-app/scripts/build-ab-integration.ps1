$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$out=Join-Path $projectRoot 'output/ab-integration'
$buildRoot='D:/BrainChair/build-ab-integration'
$env:JAVA_HOME='D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:/BrainChair/sdk'
$env:GRADLE_USER_HOME='D:/BrainChair/gradle-cache'
& 'D:/BrainChair/stage2-test-env/Scripts/python.exe' "$PSScriptRoot/stage2_oracle.py" --output "$out/fixtures"
if($LASTEXITCODE -ne 0){throw 'Synthetic regression fixture failed'}
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& robocopy $projectRoot $buildRoot /E /XD .git .gradle .kotlin .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if($LASTEXITCODE -ge 8){throw 'Build copy failed'}
New-Item -ItemType Directory -Force -Path "$buildRoot/test-fixtures/stage2" | Out-Null
Copy-Item -Path "$out/fixtures/*" -Destination "$buildRoot/test-fixtures/stage2"
& 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -p $buildRoot testDebugUnitTest --tests '*SafetyTest' --tests '*ReceiverTest' --tests '*SignalTest' --tests '*RecognitionControlTest' --tests '*AbIntegrationTest' assembleDebug lintDebug --no-daemon --offline --console=plain
if($LASTEXITCODE -ne 0){throw 'Integration build/checks failed'}
New-Item -ItemType Directory -Force -Path "$out/junit" | Out-Null
Copy-Item -Path "$buildRoot/app/build/test-results/testDebugUnitTest/*.xml" -Destination "$out/junit"
Copy-Item -LiteralPath "$buildRoot/app/build/reports/lint-results-debug.txt" -Destination "$out/lint.txt"
Copy-Item -LiteralPath "$buildRoot/app/build/outputs/apk/debug/app-debug.apk" -Destination "$out/BrainChair-B-ab-check.apk"
