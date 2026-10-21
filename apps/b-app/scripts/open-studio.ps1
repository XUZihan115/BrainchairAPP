$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'D:/BrainChair/tools/jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'D:/BrainChair/sdk'
$env:ANDROID_AVD_HOME = 'D:/BrainChair/android-user/avd'
$env:GRADLE_USER_HOME = 'D:/BrainChair/gradle-cache'
Start-Process -FilePath 'D:/BrainChair/tools/android-studio/bin/studio64.exe' -ArgumentList ('"' + $projectRoot + '"')
