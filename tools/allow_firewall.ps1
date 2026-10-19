# 放行 EEG Sender 的 UDP 入站端口：9000（数据流）、9001（自动发现）
#
# 用途：手机当热点、电脑当接收端时，Windows 通常把「手机热点」这条连接
#       归类为「公用网络」，默认丢弃所有入站 UDP。不建这条规则，抓包器
#       会一个包都收不到（发现广播也收不到）。
#
# 用法（需要管理员权限）：
#     Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','D:\EEGSender\tools\allow_firewall.ps1'
#
# 本脚本可重复执行：规则已存在时只会检查/启用，不会重复创建。

$ErrorActionPreference = 'Stop'

foreach ($port in 9000, 9001) {
    $name = "EEG Sender UDP $port"
    $rule = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue

    if ($rule) {
        if ($rule.Enabled -ne 'True') {
            Enable-NetFirewallRule -DisplayName $name
            Write-Host "[FIX ] rule existed but was disabled, enabled it : $name"
        } else {
            Write-Host "[SKIP] rule already present and enabled          : $name"
        }
        continue
    }

    New-NetFirewallRule -DisplayName $name `
        -Direction Inbound `
        -Protocol UDP `
        -LocalPort $port `
        -Action Allow `
        -Profile Any `
        -Description 'EEG Sender: phone -> PC UDP stream (9000) / auto-discovery (9001)' | Out-Null
    Write-Host "[OK  ] created inbound allow rule for UDP $port"
}

Write-Host ''
Write-Host 'Current EEG rules:'
Get-NetFirewallRule -DisplayName 'EEG Sender UDP *' |
    ForEach-Object { "  {0}  Enabled={1}  Action={2}" -f $_.DisplayName, $_.Enabled, $_.Action }
