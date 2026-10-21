$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$out=Join-Path $projectRoot 'output/stage3'
$buildRoot='D:/BrainChair/build-stage3'
$env:JAVA_HOME='D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:/BrainChair/sdk'
$env:GRADLE_USER_HOME='D:/BrainChair/gradle-cache'
New-Item -ItemType Directory -Force -Path $buildRoot,"$out/jvm","$out/junit" | Out-Null
& robocopy $projectRoot $buildRoot /E /XD .git .gradle .kotlin .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if($LASTEXITCODE -ge 8){throw 'Build copy failed'}
New-Item -ItemType Directory -Force -Path "$buildRoot/test-fixtures/stage2" | Out-Null
& 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -p $buildRoot testDebugUnitTest --tests '*Stage3ProtocolTest' --tests '*Stage3ControlTest' --tests '*Stage3ReplayTest' --tests '*Stage3IntegrationTest' assembleDebug lintDebug "-Pstage3Evidence=$out/jvm" "-Pstage3Data=$projectRoot/output/stage25/data" "-Pstage3Expected=$projectRoot/docs/cca-window-comparison-windows.csv" --no-daemon --offline --console=plain
$buildExit=$LASTEXITCODE
if(Test-Path "$buildRoot/app/build/test-results/testDebugUnitTest"){
    Copy-Item -Path "$buildRoot/app/build/test-results/testDebugUnitTest/*.xml" -Destination "$out/junit"
}
if($buildExit -ne 0){throw 'Stage 3 build/checks failed'}
Copy-Item -LiteralPath "$buildRoot/app/build/reports/lint-results-debug.txt" -Destination "$out/lint.txt"
Copy-Item -LiteralPath "$buildRoot/app/build/outputs/apk/debug/app-debug.apk" -Destination "$out/BrainChair-B-stage3.apk"
Get-FileHash -LiteralPath "$out/BrainChair-B-stage3.apk" -Algorithm SHA256
