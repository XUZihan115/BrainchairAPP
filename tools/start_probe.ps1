# Start the PC-side stand-in receiver (packet capture + protocol validator).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File D:\EEGSender\tools\start_probe.ps1
#   powershell -ExecutionPolicy Bypass -File D:\EEGSender\tools\start_probe.ps1 -Seconds 600
#   powershell -ExecutionPolicy Bypass -File D:\EEGSender\tools\start_probe.ps1 -NoCca
#
# Notes:
#   * Runs python with -u (unbuffered). Without it, stdout is block-buffered and
#     everything not yet flushed is LOST when the process is killed - which looks
#     exactly like "not a single packet arrived".
#   * PYTHONIOENCODING is forced to utf-8; otherwise Python on Windows may emit
#     cp936 and mangle the log.
#   * Firewall: inbound UDP 9000 + 9001 must be allowed. Run
#     tools\allow_firewall.ps1 once (as administrator).

param(
    [int]$Port = 9000,
    [int]$DiscoveryPort = 9001,
    [int]$Seconds = 300,
    [string]$Name = "PC-Receiver",
    [string]$Save = "",
    [switch]$NoCca,
    [switch]$NoDiscovery
)

$ErrorActionPreference = "Stop"
$env:PYTHONIOENCODING = "utf-8"

$probe = Join-Path $PSScriptRoot "udp_probe.py"
if (-not (Test-Path -LiteralPath $probe)) {
    Write-Host "[FAIL] probe script not found: $probe"
    exit 1
}

$probeArgs = @("-3", "-u", $probe)
$probeArgs += @("--port", $Port)
$probeArgs += @("--name", $Name)
$probeArgs += @("--seconds", $Seconds)
if ($NoDiscovery) {
    $probeArgs += "--no-discovery"
} else {
    $probeArgs += @("--discovery-port", $DiscoveryPort)
}
if ($NoCca) { $probeArgs += "--no-cca" }
if ($Save -ne "") { $probeArgs += @("--save", $Save) }

$discLabel = if ($NoDiscovery) { "<off>" } else { [string]$DiscoveryPort }

Write-Host "=== Local IPv4 addresses ==="
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -ne '127.0.0.1' } |
    ForEach-Object { Write-Host ("  {0,-16} {1}" -f $_.IPAddress, $_.InterfaceAlias) }

Write-Host ""
Write-Host ("=== Starting: py " + ($probeArgs -join " ") + " ===")
Write-Host "Listening for data on UDP $Port, discovery on UDP $discLabel"
Write-Host "Stop with Ctrl+C."
Write-Host ""

& py @probeArgs
exit $LASTEXITCODE
