package io.nekohasekai.sagernet.group

// 深链导入订阅的确认框内容。深链字符串来自外部、解码上限很大，
// 直接渲染会在用户确认前卡死主线程，所以显示前一律截断；截断只影响显示，导入的仍是完整值。
// 这里只放纯逻辑，便于 JVM 单测。

const val IMPORT_PREVIEW_NAME_MAX = 100
const val IMPORT_PREVIEW_LINK_MAX = 500
const val IMPORT_PREVIEW_PRESET_MAX = 200

private const val ELLIPSIS = "…"
private val lineBreaks = Regex("\r\n|[\r\n\u0085\u2028\u2029]")
private val layoutControls = Regex("[\u0000-\u001F\u007F-\u009F\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]")

// 先限制处理长度并保留完整代理对，再把外部文本的换行和方向控制符显示为可见字符。
fun truncateForDisplay(text: String, max: Int): String {
    val bounded = if (text.length <= max) text else {
        val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        text.substring(0, end) + ELLIPSIS
    }
    return bounded.replace(lineBreaks, "⏎").replace(layoutControls, "�")
}

enum class SubscriptionPreset { PROXY_SERVER_NAMESERVER, FORCE_RESOLVE, CUSTOM_USER_AGENT }

// 深链可以预设的、会改变订阅行为的字段：只列出相对默认值有设置的项，
// 值已截断。没有任何预设时返回空列表，确认框与原先一致
fun subscriptionPresets(
    proxyServerNameserver: String?,
    forceResolve: Boolean?,
    customUserAgent: String?,
): List<Pair<SubscriptionPreset, String>> = buildList {
    if (!proxyServerNameserver.isNullOrBlank()) {
        add(SubscriptionPreset.PROXY_SERVER_NAMESERVER to truncateForDisplay(proxyServerNameserver.trim(), IMPORT_PREVIEW_PRESET_MAX))
    }
    if (forceResolve == true) {
        add(SubscriptionPreset.FORCE_RESOLVE to "true")
    }
    if (!customUserAgent.isNullOrBlank()) {
        add(SubscriptionPreset.CUSTOM_USER_AGENT to truncateForDisplay(customUserAgent.trim(), IMPORT_PREVIEW_PRESET_MAX))
    }
}
