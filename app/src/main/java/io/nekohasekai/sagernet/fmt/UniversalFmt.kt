package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.requireV2RayTransport
import moe.matsuri.nb4a.utils.Util

// globalAllowInsecure：全局「允许不安全」，只在链接是 K1 之前生成的 StandardV2Ray 系节点时取值（见 universalBean）
fun parseUniversal(link: String, globalAllowInsecure: () -> Boolean): AbstractBean {
    return if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        universalBean(
            TypeMap[type] ?: error("Type $type not found"),
            Util.zlibDecompress(Util.b64Decode(link.substringAfter("?"))),
            globalAllowInsecure,
        )
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        universalBean(
            TypeMap[type] ?: error("Type $type not found"),
            Util.b64Decode(link.substringAfter(":").substringAfter(":")),
            globalAllowInsecure,
        )
    }
}

// 通用链接解码后的 bean 字节 → bean。纯函数部分（链接的 Base64 / zlib 解码在 parseUniversal 里）
internal fun universalBean(type: Int, bytes: ByteArray, globalAllowInsecure: () -> Boolean): AbstractBean {
    val bean = ProxyEntity(type = type).apply { putByteArray(bytes) }.requireBean()
    // 通用链接是序列化好的 bean，传输方式与分享链接一样要在导入时拦下未知值。
    // 只有 VMess / VLESS / Trojan 按 type 构建传输层
    if (bean is StandardV2RayBean && (bean is VMessBean || bean is TrojanBean)) {
        bean.type = requireV2RayTransport(bean.type)
    }
    // K1 之前生成的链接（StandardV2Ray v7 之前的字节）：按 K1 的选核标注一次（fmt/LegacyProfileUpgrade.kt）。
    // 链接里没有 core 列，导入后的节点是自动选核，按自动标注；全局「允许不安全」取导入时本机的值
    if (bean is StandardV2RayBean && bean.legacyUnlabeled) {
        upgradeLegacyProfile(type, CORE_AUTO, bean, globalAllowInsecure())
        bean.legacyUnlabeled = false
    }
    return bean
}

fun AbstractBean.toUniversalLink(): String {
    var link = "sn://"
    link += TypeMap.reversed[ProxyEntity().putBean(this).type]
    link += "?"
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    return link
}


fun ProxyGroup.toUniversalLink(): String {
    var link = "sn://subscription?"
    // 持锁覆盖整个 翻转-序列化-复位 过程，与 KryoConverters.serialize 内部的
    // synchronized(bean) 互斥；try/finally 保证中途异常时标志复位
    synchronized(this) {
        export = true
        try {
            link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
        } finally {
            export = false
        }
    }
    return link
}