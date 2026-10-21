param([string]$Apk = 'app-debug.apk.1')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$buildRoot='D:/BrainChair/build-stage25'
$result=Join-Path $projectRoot 'output/stage25'
$env:JAVA_HOME='D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:/BrainChair/sdk'
$env:GRADLE_USER_HOME='D:/BrainChair/gradle-cache'
$oraclePython='D:/BrainChair/stage2-test-env/Scripts/python.exe'
& $oraclePython "$PSScriptRoot/stage25_extract.py" $Apk --out "$result/data"
if($LASTEXITCODE -ne 0){throw 'APK validation failed'}
& $oraclePython "$PSScriptRoot/stage2_oracle.py" --output "$result/synthetic"
if($LASTEXITCODE -ne 0){throw 'Synthetic fixture generation failed'}
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& robocopy $projectRoot $buildRoot /E /XD .git .gradle .kotlin .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if($LASTEXITCODE -ge 8){throw 'Build copy failed'}
New-Item -ItemType Directory -Force -Path "$buildRoot/test-fixtures/stage2","$buildRoot/test-fixtures/stage25" | Out-Null
Copy-Item -Path "$result/synthetic/*" -Destination "$buildRoot/test-fixtures/stage2"
Copy-Item -Path "$result/data/*" -Destination "$buildRoot/test-fixtures/stage25"
& 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -p $buildRoot testDebugUnitTest assembleDebug lintDebug --no-daemon --offline --console=plain
if($LASTEXITCODE -ne 0){throw 'Tests or build failed'}
New-Item -ItemType Directory -Force -Path "$result/junit" | Out-Null
Copy-Item -Path "$buildRoot/test-fixtures/stage25/*.csv","$buildRoot/test-fixtures/stage25/selected.json","$buildRoot/test-fixtures/stage25/checks.tsv" -Destination $result
Copy-Item -Path "$buildRoot/app/build/test-results/testDebugUnitTest/*.xml" -Destination "$result/junit"
Copy-Item -LiteralPath "$buildRoot/app/build/reports/lint-results-debug.txt" -Destination "$result/lint.txt"
Copy-Item -LiteralPath "$buildRoot/app/build/outputs/apk/debug/app-debug.apk" -Destination "$result/BrainChair-B-stage25-validation.apk"
