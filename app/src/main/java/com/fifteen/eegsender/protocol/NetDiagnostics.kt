package com.fifteen.eegsender.protocol

/**
 * 把 Socket 的底层错误码翻译成「用户能照着做」的中文提示。
 *
 * 真机上 `EPERM (Operation not permitted)` 特别容易被误读成「网络不通」，其实它是
 * **系统在应用层拒绝把这个包发出去**，跟网线、路由器、IP 填得对不对都无关：
 *
 * - Android 16+ 的「局域网访问保护」：应用没有局域网访问权限时，往局域网地址
 *   （10.x / 192.168.x 以及广播地址）发包会被拒绝；
 * - 部分国产 ROM（如 MagicOS / EMUI）的「应用联网权限」「纯净模式」会直接掐掉
 *   侧载应用的网络能力。
 *
 * 两种情况的表现完全一样，所以提示里要把两个方向都指出来。
 */
internal object NetDiagnostics {

    /** 错误文本是否属于「系统拒绝发包」。 */
    fun isPermissionDenied(raw: String?): Boolean {
        if (raw == null) return false
        return raw.contains("EPERM") ||
            raw.contains("EACCES") ||
            raw.contains("not permitted") ||
            raw.contains("Permission denied")
    }

    /**
     * 把原始错误文本变成可读提示。无法识别的错误原样返回，便于继续排查。
     */
    fun explain(raw: String?): String {
        if (raw.isNullOrBlank()) return "没有任何可用的网络出口"

        return when {
            isPermissionDenied(raw) ->
                "系统拒绝本应用向局域网发包（$raw）。这不是网络不通，而是权限问题：" +
                    "① 设置→应用→本应用→权限，允许「联网 / WLAN」；" +
                    "② 关闭系统的「纯净模式」；" +
                    "③ 若系统弹过「访问本地网络设备」的授权框，请选择允许。"

            raw.contains("ENETUNREACH") || raw.contains("Network is unreachable") ->
                "本机没有到该地址的路由（$raw）。请确认手机和接收端连在同一个热点/网段上。"

            raw.contains("EHOSTUNREACH") ->
                "目标地址不可达（$raw）。请核对接收端 IP 是否是当前网段的地址。"

            raw.contains("EADDRINUSE") ->
                "本地端口被占用（$raw）。请稍后重试，或重启本应用。"

            else -> raw
        }
    }
}
