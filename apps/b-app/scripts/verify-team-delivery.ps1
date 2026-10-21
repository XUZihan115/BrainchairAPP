param(
    [string]$GradleCommand = '',
    [switch]$Offline
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$out=Join-Path $projectRoot 'output/team-delivery'
New-Item -ItemType Directory -Force -Path $out | Out-Null
if([string]::IsNullOrWhiteSpace($GradleCommand)){
    $GradleCommand=Join-Path $projectRoot $(if($env:OS -eq 'Windows_NT'){'gradlew.bat'}else{'gradlew'})
}
$started=[DateTimeOffset]::UtcNow.ToString('o')
# Directory migration checks only. No EEG data, old experiments, emulator or fixed UDP ports.
$buildArgs=@('-p',$projectRoot,'assembleDebug','testDebugUnitTest',
    '--tests','*Stage3ProtocolTest.commandGoldenFramesMatchUnityDocument',
    '--tests','*Stage3ProtocolTest.statusExampleChecksumIsCorrectedByFormulaNotCopied',
    '--tests','*Stage3ProtocolTest.signedEndianUnsignedUptimeAndBinaryCrLfAreParsed',
    '--tests','*Stage3ProtocolTest.malformedStatusAndConfigurationAreRejected',
    '--no-daemon','--console=plain')
if($Offline){$buildArgs+='--offline'}
& $GradleCommand @buildArgs
if($LASTEXITCODE -ne 0){throw 'B independent build / directory migration checks failed'}
$apk=Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
$deliveredApk=Join-Path $out 'BrainChair-B-stage3-team-debug.apk'
Copy-Item -LiteralPath $apk -Destination $deliveredApk
$xml=Join-Path $projectRoot 'app/build/test-results/testDebugUnitTest/TEST-com.yourteam.brainchair.control.Stage3ProtocolTest.xml'
[xml]$tests=Get-Content -LiteralPath $xml -Raw
if($tests.testsuite.tests -ne '4' -or $tests.testsuite.failures -ne '0' -or $tests.testsuite.errors -ne '0'){
    throw 'Expected four passing data-free migration tests'
}
Copy-Item -LiteralPath $xml -Destination (Join-Path $out 'migration-tests.xml')
$result=[ordered]@{
    sourceCommit='6064d5fbf818c948ae2f7926095fba77275b9a9a'
    startedUtc=$started
    completedUtc=[DateTimeOffset]::UtcNow.ToString('o')
    gradleCommand=$GradleCommand
    build='assembleDebug succeeded in independent B project'
    migrationTests=4
    failures=0
    eegExperimentsRerun=$false
    apk=$deliveredApk
    apkBytes=(Get-Item -LiteralPath $deliveredApk).Length
    apkSha256=(Get-FileHash -LiteralPath $deliveredApk -Algorithm SHA256).Hash.ToLowerInvariant()
}
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $out 'validation.json') -Encoding UTF8
Write-Output "B build verified; local APK: $deliveredApk"
