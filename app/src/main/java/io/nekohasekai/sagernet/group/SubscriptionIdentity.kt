package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.readableMessage
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 订阅更新「下载什么、怎么解析」的身份：下载开始时记下，写库事务开头与分组当前行比对，
 * 不一致说明用户在下载期间改了订阅（甚至整个换成了另一个订阅），这次结果不再属于这个分组，
 * 整体放弃，不写节点也不写 lastUpdated。
 *
 * 只收会改变下载内容或解析结果的字段：分组类型、订阅类型、链接、自定义 UA（服务端可能按 UA
 * 下发不同内容）、强制解析与去重开关（节点列表是按这两项处理出来的）、节点解析 DNS（只在
 * 强制解析开着时才影响结果）。自动更新开关 / 间隔、仅连接时更新、分组名、排序、前置 / 落地
 * 代理不影响下载内容，改动这些不该让进行中的更新作废。
 */
data class SubscriptionIdentity(
    val groupType: Int,
    val subscriptionType: Int?,
    val link: String?,
    val customUserAgent: String?,
    val forceResolve: Boolean?,
    val deduplication: Boolean?,
    val proxyServerNameserver: String?,
)

fun ProxyGroup.subscriptionIdentity(): SubscriptionIdentity {
    val subscription = subscription
    return SubscriptionIdentity(
        groupType = type,
        subscriptionType = subscription?.type,
        // 编辑器保存时去掉首尾空白：只差空白的链接是同一个订阅，不让进行中的更新因此作废
        link = subscription?.link?.trim(),
        customUserAgent = subscription?.customUserAgent,
        forceResolve = subscription?.forceResolve,
        deduplication = subscription?.deduplication,
        // 强制解析关着时 DNS 设置不参与解析，不计入身份
        proxyServerNameserver = proxyServerNameserver.takeIf { subscription?.forceResolve == true },
    )
}

/** 订阅更新的结果已过期：下载期间订阅身份变了。不是失败，也不是协程取消，由 executeUpdate 单独处理。 */
class SubscriptionSupersededException(groupId: Long) :
    Exception("Subscription of group $groupId changed during the update")

/** [started] 是下载开始时的身份，[current] 是事务里重新读到的分组行（分组被删除的情况由调用方先处理）。 */
fun isSubscriptionSuperseded(started: SubscriptionIdentity, current: ProxyGroup): Boolean =
    current.subscriptionIdentity() != started

/** 手填 / 深链订阅链接的统一规则：只接受 http(s)。scheme 取第一个冒号之前，不区分大小写。 */
fun isHttpSubscriptionLink(link: String): Boolean {
    val scheme = link.substringBefore(':', "").lowercase()
    return scheme == "http" || scheme == "https"
}

/**
 * 编辑器里订阅链接的唯一规则（输入框 LinkOrContentPreference 与保存前的检查共用）：空值放行（可以先建分组再填）；
 * 经「选择文件」得到的 content:// 放行（更新时按这个前缀读本地文件）；其余必须是单行的合法 http(s) 链接。
 * 返回不能保存的原因，null 表示可以保存。深链导入不接受 content://，仍用 [isHttpSubscriptionLink]
 */
fun subscriptionLinkError(link: String): String? {
    if (link.isBlank() || link.startsWith("content://")) return null
    if (link.contains("\n")) return "Unexpected new line"
    return try {
        link.toHttpUrl()
        null
    } catch (e: Exception) {
        e.readableMessage
    }
}

fun isEditableSubscriptionLink(link: String): Boolean = subscriptionLinkError(link) == null
