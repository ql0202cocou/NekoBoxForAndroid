package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.requireV2RayTransport
import moe.matsuri.nb4a.utils.Util

fun parseUniversal(link: String): AbstractBean {
    val bean = if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.zlibDecompress(Util.b64Decode(link.substringAfter("?"))))
        }.requireBean()
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.b64Decode(link.substringAfter(":").substringAfter(":")))
        }.requireBean()
    }
    // 通用链接是序列化好的 bean，传输方式与分享链接一样要在导入时拦下未知值。
    // 只有 VMess / VLESS / Trojan 按 type 构建传输层
    if (bean is StandardV2RayBean && (bean is VMessBean || bean is TrojanBean)) {
        bean.type = requireV2RayTransport(bean.type)
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