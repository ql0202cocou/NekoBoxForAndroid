package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.beanForType

/**
 * 节点配置的快照，用来判断一次写入有没有改变「连得上与否」相关的配置：type、core 与 bean 的内容。
 *
 * bean 按 [AbstractBean.equals] 比：同一个类、Kryo 序列化字节相同，节点名不算（改名不影响可用性），
 * customOutboundJson / customConfigJson 算。纯函数，不碰数据库与 Android。
 *
 * 用法：写入前取快照，写入后（或要写入的新值）调用 [differsFrom]；配置变了，之前的测速结果（status / ping / error）
 * 就不再对应现在的配置，调用方在同一事务里把它们清零（见 [ProfileManager.updateProfile]、订阅更新）。
 */
class ProxyConfigSnapshot private constructor(
    private val type: Int,
    private val core: Int,
    private val bean: AbstractBean?,
) {

    constructor(entity: ProxyEntity) : this(
        entity.type,
        entity.core,
        // 类型未定义或 bean 为空的坏行当作没有 bean：与另一边都没有时算没变
        runCatching { entity.beanForType() }.getOrNull(),
    )

    /** [other] 的配置与这份快照不同。 */
    fun differsFrom(other: ProxyEntity): Boolean {
        if (type != other.type || core != other.core) return true
        val otherBean = runCatching { other.beanForType() }.getOrNull()
        return bean != otherBean
    }
}

/**
 * 写配置的唯一入口（编辑器保存、订阅更新）：按列写回 [entity] 的配置（[ProxyEditableFields]），配置相对写入前的
 * [before] 变了就在同一事务里把测速状态（status / ping / error）清零。必须在调用方已开的事务里调用。
 * 返回写到的行数：0 表示这一行已不在库里，什么都没写。
 */
fun ProxyEntity.Dao.updateConfigResettingStatus(before: ProxyConfigSnapshot, entity: ProxyEntity): Int {
    val written = updateEditableFields(ProxyEditableFields(entity))
    if (written > 0 && before.differsFrom(entity)) updateStatus(entity.id, 0, 0, null)
    return written
}

/**
 * 测速结果落库：[result] 是测速开始时读的那一行，测试只填了 status / ping / error，配置还是被测时的。
 * 测试期间库里这一行可能已经变了：编辑器保存或订阅更新改了配置（并已清零状态），恢复备份后同一 id 还可能换成别的节点。
 * 所以重读这一行，配置（[ProxyConfigSnapshot] 的口径）与被测时相同才写，否则旧配置的「可用 xx ms」会盖到新配置上。
 * 必须在调用方已开的事务里调用。返回是否写入：false 表示这一行已不在库里或配置已变，什么都没写。
 */
fun ProxyEntity.Dao.updateTestResult(result: ProxyEntity): Boolean {
    val current = getById(result.id) ?: return false
    if (ProxyConfigSnapshot(result).differsFrom(current)) return false
    updateStatus(result.id, result.status, result.ping, result.error)
    return true
}
