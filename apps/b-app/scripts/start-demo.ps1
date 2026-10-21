$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME = 'D:/BrainChair/sdk'
$env:ANDROID_AVD_HOME = 'D:/BrainChair/android-user/avd'
$adb = 'D:/BrainChair/sdk/platform-tools/adb.exe'
$apk = Join-Path $projectRoot 'output/BrainChair.apk'
if (!(Test-Path -LiteralPath $apk)) { throw 'Build first: scripts/build.ps1' }
$devices = & $adb devices
if (!($devices -match 'emulator-5554\s+device')) {
    Start-Process -FilePath 'D:/BrainChair/sdk/emulator/emulator.exe' -ArgumentList @('-avd', 'BrainChair_API35', '-port', '5554', '-no-snapshot', '-gpu', 'swiftshader', '-memory', '2048')
}
$ready = $false
for ($i = 0; $i -lt 120; $i++) {
    $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null
    if ($boot -match '1') { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (!$ready) { throw 'Emulator did not boot within 4 minutes.' }
& $adb -s emulator-5554 install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $adb -s emulator-5554 shell am start -n com.yourteam.brainchair/.MainActivity
