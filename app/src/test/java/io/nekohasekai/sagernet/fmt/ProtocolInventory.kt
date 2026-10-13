package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HTTP
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_MIERU
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NAIVE
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SHADOWTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SSH
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN_GO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TUIC
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_WG
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.neko.NekoBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import java.lang.reflect.Field
import java.lang.reflect.Modifier

// QUALITY-004 契约测试共用的清单：协议类型 -> bean 类，以及按公开字段做的反射比较与填充。
// 新增协议时：ProxyEntity 加 TYPE_* 常量后，ProtocolContractTest 会因为这里没有对应条目而失败，
// 按提示补 PROTOCOLS 与 ProtocolContractTest 里的登记表

/** 一种协议类型：名字（报错用）、类型常量、bean 类、JVM 上能不能构造 bean（NekoBean 的字段初始化用 org.json）。 */
class Proto(val name: String, val type: Int, val beanClass: Class<out AbstractBean>, val constructible: Boolean = true) {
    override fun toString() = name
}

object ProtocolInventory {

    val PROTOCOLS: List<Proto> = listOf(
        Proto("SOCKS", TYPE_SOCKS, SOCKSBean::class.java),
        Proto("HTTP", TYPE_HTTP, HttpBean::class.java),
        Proto("Shadowsocks", TYPE_SS, ShadowsocksBean::class.java),
        Proto("VMess/VLESS", TYPE_VMESS, VMessBean::class.java),
        Proto("Trojan", TYPE_TROJAN, TrojanBean::class.java),
        Proto("Trojan-Go", TYPE_TROJAN_GO, TrojanGoBean::class.java),
        Proto("Mieru", TYPE_MIERU, MieruBean::class.java),
        Proto("Naive", TYPE_NAIVE, NaiveBean::class.java),
        Proto("Hysteria", TYPE_HYSTERIA, HysteriaBean::class.java),
        Proto("SSH", TYPE_SSH, SSHBean::class.java),
        Proto("WireGuard", TYPE_WG, WireGuardBean::class.java),
        Proto("TUIC", TYPE_TUIC, TuicBean::class.java),
        Proto("ShadowTLS", TYPE_SHADOWTLS, ShadowTLSBean::class.java),
        Proto("AnyTLS", TYPE_ANYTLS, AnyTLSBean::class.java),
        Proto("Chain", TYPE_CHAIN, ChainBean::class.java),
        Proto("Neko", TYPE_NEKO, NekoBean::class.java, constructible = false),
        Proto("Config", TYPE_CONFIG, ConfigBean::class.java),
    )

    /** ProxyEntity 里全部 TYPE_* 常量（反射取，新增常量不用改这里）。 */
    fun declaredTypes(): Map<String, Int> =
        ProxyEntity::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TYPE_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    fun proto(type: Int): Proto = PROTOCOLS.single { it.type == type }

    // ---- 反射字段：持久化的公开实例字段（同 KryoSamples.fieldsOf）

    fun fieldsOf(cls: Class<*>): List<Field> = cls.fields
        .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
        .sortedBy { it.name }

    fun snapshot(bean: AbstractBean): Map<String, Any?> = fieldsOf(bean.javaClass).associate { it.name to it.get(bean) }

    /** 新实例走一遍 initializeDefaultValues 得到的默认字段值（解析器返回前都会调它）。 */
    fun defaults(cls: Class<out AbstractBean>): Map<String, Any?> =
        snapshot(cls.getDeclaredConstructor().newInstance().apply { initializeDefaultValues() })

    /** 逐字段比较，返回不一致之处；ignore 里的字段不比。 */
    fun diff(where: String, expected: AbstractBean, actual: AbstractBean, ignore: Set<String> = emptySet()): List<String> {
        if (expected.javaClass != actual.javaClass) {
            return listOf("$where：期望类型 ${expected.javaClass.simpleName}，实际 ${actual.javaClass.simpleName}")
        }
        val e = snapshot(expected)
        val a = snapshot(actual)
        return e.keys.filter { it !in ignore && e[it] != a[it] }.map { "$where.$it：期望 ${e[it]}，实际 ${a[it]}" }
    }

    // ---- 反射填充：把指定字段（null 表示全部公开字段）填成与默认值不同的值，不管取值是否合法

    fun fillNonDefault(bean: AbstractBean, only: Collection<String>? = null) {
        val defaults = defaults(bean.javaClass)
        for ((index, field) in fieldsOf(bean.javaClass).withIndex()) {
            if (only != null && field.name !in only) continue
            val default = defaults[field.name]
            field.set(
                bean,
                when (field.type) {
                    String::class.java -> "x-${field.name}".takeIf { it != default } ?: "y-${field.name}"
                    Int::class.javaObjectType -> (4200 + index).takeIf { it != default } ?: (5200 + index)
                    Boolean::class.javaObjectType -> !(default as? Boolean ?: false)
                    List::class.java -> listOf(11L, 22L, 33L)
                    else -> error("${bean.javaClass.simpleName}.${field.name}：填充器不认识的字段类型 ${field.type}")
                },
            )
        }
    }
}
