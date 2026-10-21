$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'D:/BrainChair/sdk'
$env:GRADLE_USER_HOME = 'D:/BrainChair/gradle-cache'
$buildRoot = 'D:/BrainChair/project'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
# Windows Android/JVM tooling can fail on non-ASCII paths. Keep a disposable build copy.
& robocopy $projectRoot $buildRoot /E /XD .git .gradle .idea build tmp output /XF '*.log' /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { throw 'Could not synchronize build copy.' }
& 'D:/BrainChair/tools/gradle-8.11.1/bin/gradle.bat' -p $buildRoot assembleDebug testDebugUnitTest lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed. See output above.' }
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'output') | Out-Null
Copy-Item -LiteralPath (Join-Path $buildRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $projectRoot 'output/BrainChair.apk')
Write-Host 'APK: output/BrainChair.apk'
