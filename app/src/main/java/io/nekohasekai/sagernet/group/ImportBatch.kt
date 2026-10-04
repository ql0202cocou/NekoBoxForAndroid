package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.v2ray.UnsupportedTransportException

// 一次导入里逐段解析多份输入（如 ZIP 包的各个条目）时的汇总：
// 某段因传输方式不支持被拒只记下原因，不拖垮其余条目；
// 全部处理完一个节点都没有时，才把第一条记下的原因报给用户，
// 而不是笼统的「没找到节点」
class ImportBatch<T> {

    private val items = ArrayList<T>()
    private var unsupportedTransport: UnsupportedTransportException? = null

    // 一段的解析结果；null 表示这段没认出任何节点
    fun add(parsed: List<T>?) {
        if (parsed != null) items.addAll(parsed)
    }

    // 一段因传输方式不支持被拒
    fun reject(e: UnsupportedTransportException) {
        if (unsupportedTransport == null) unsupportedTransport = e
    }

    // 有节点时照常返回（被拒的段落忽略）；一个都没有时抛出记下的原因，
    // 也没有记下原因就返回空列表，由调用方报「没找到节点」
    fun result(): List<T> {
        if (items.isEmpty()) unsupportedTransport?.let { throw it }
        return items
    }
}
