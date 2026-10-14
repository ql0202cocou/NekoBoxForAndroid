package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SilencedLogs
import io.nekohasekai.sagernet.assertNoFailures
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.CORE_AUTO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_MIERU
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.kryo.KryoSamples
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.golden.GoldenAddressCorpus
import io.nekohasekai.sagernet.golden.GoldenBaseline
import io.nekohasekai.sagernet.golden.GoldenJvmScenario
import io.nekohasekai.sagernet.golden.dir
import io.nekohasekai.sagernet.ui.profile.settingActivityOf
import java.io.File
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// QUALITY-004：协议类型 × 转换入口的契约测试。ProtocolInventory.PROTOCOLS 列出全部协议，下面的 EXPECTED 登记表
// 对每个类型写明它在各入口的状态；测试把登记表和生产代码的真实分派逐项对账，并核对登记表覆盖了 ProxyEntity 里的
// 每个 TYPE_*。新增协议时：加 TYPE_* 常量、PROTOCOLS 一行、EXPECTED 一行，漏一处这里就失败；
// 登记表写的是「应该怎样」，实际分派不一致（漏了某个 when 分支）同样失败。
// 范围：ProtocolHandlers.kt 的各分派、editor 入口、通用链接类型表、Kryo 转换器与登记、构建入口（黄金场景）。
// 分享链接的导出 / 解析见 ProtocolLinkTest，bean 的 Kryo 往返见本类的 Kryo 部分（历史字节的兼容由 KryoCompatTest 管）
class ProtocolContractTest {

    companion object {
        @ClassRule
        @JvmField
        val temp = TemporaryFolder()

        private fun nekoNote() = "NekoBean 的字段初始化用 org.json（mockable android.jar 在 JVM 上抛异常），JVM 上构造不出来"
    }

    @get:Rule
    val logs = SilencedLogs()

    /** 分享入口：有专属格式的链接 / 没有专属格式，standardLink 回落到通用链接 / 不分享。 */
    private enum class Share { DEDICATED, UNIVERSAL, NONE }

    /**
     * 一个类型在各入口的预期。
     * - haveLink：ProxyEntity.haveLink()（分组导出、节点分享菜单的总开关）；
     * - share：standardLink 实际给出的东西；
     * - hasStandardLink：hasStandardLink(bean) 的返回（驱动「标准」菜单项）；
     * - singBox：buildSingBoxOutbound 有这种 bean 的分支；
     * - external：externalCore(bean) 有条目；
     * - editor：settingActivityOf 有编辑器；
     * - universalType：TypeMap（通用链接 sn://）里有这个类型。
     */
    private class Expect(
        val haveLink: Boolean,
        val share: Share,
        val hasStandardLink: Boolean,
        val singBox: Boolean,
        val external: Boolean,
        val editor: Boolean = true,
        val universalType: Boolean = true,
        val note: String = "",
    )

    private val EXPECTED: Map<Int, Expect> = mapOf(
        ProxyEntity.TYPE_SOCKS to Expect(true, Share.DEDICATED, true, singBox = true, external = false),
        ProxyEntity.TYPE_HTTP to Expect(true, Share.DEDICATED, true, singBox = true, external = false),
        ProxyEntity.TYPE_SS to Expect(true, Share.DEDICATED, true, singBox = true, external = false),
        ProxyEntity.TYPE_VMESS to Expect(true, Share.DEDICATED, true, singBox = true, external = true),
        ProxyEntity.TYPE_TROJAN to Expect(true, Share.DEDICATED, true, singBox = true, external = true),
        ProxyEntity.TYPE_TROJAN_GO to Expect(true, Share.DEDICATED, true, singBox = false, external = true),
        // Mieru 没有专属链接，standardLink 回落到 sn://，hasStandardLink 却是 true：「标准」菜单项与「SN Link」重复
        ProxyEntity.TYPE_MIERU to Expect(
            true, Share.UNIVERSAL, true, singBox = false, external = true,
            note = "hasStandardLink 为 true 但没有专属链接（已知缺口）",
        ),
        ProxyEntity.TYPE_NAIVE to Expect(true, Share.DEDICATED, true, singBox = false, external = true),
        ProxyEntity.TYPE_HYSTERIA to Expect(true, Share.DEDICATED, true, singBox = true, external = true),
        ProxyEntity.TYPE_SSH to Expect(true, Share.UNIVERSAL, false, singBox = true, external = false),
        ProxyEntity.TYPE_WG to Expect(true, Share.UNIVERSAL, false, singBox = true, external = false),
        ProxyEntity.TYPE_TUIC to Expect(true, Share.DEDICATED, true, singBox = true, external = false),
        ProxyEntity.TYPE_SHADOWTLS to Expect(true, Share.UNIVERSAL, false, singBox = true, external = false),
        ProxyEntity.TYPE_ANYTLS to Expect(true, Share.DEDICATED, true, singBox = true, external = true),
        // 链：不分享（haveLink 为 false）；成员由规划展开，自己没有出站；没有通用链接类型，toUniversalLink 对它无意义
        ProxyEntity.TYPE_CHAIN to Expect(
            false, Share.NONE, true, singBox = false, external = false, universalType = false,
            note = "hasStandardLink 为 true 但 haveLink 为 false，菜单不可达",
        ),
        // Neko：插件承载，没有编辑器、不分享；外核表没有条目（调用方跳过）
        ProxyEntity.TYPE_NEKO to Expect(
            false, Share.NONE, false, singBox = false, external = false, editor = false, note = nekoNote(),
        ),
        ProxyEntity.TYPE_CONFIG to Expect(true, Share.UNIVERSAL, false, singBox = true, external = false),
    )

    // ---- 登记表覆盖全部类型

    @Test
    fun `ProxyEntity 的每个 TYPE 常量都登记在清单与预期表里`() {
        val failures = ArrayList<String>()
        val declared = ProtocolInventory.declaredTypes()
        for ((name, value) in declared) {
            if (ProtocolInventory.PROTOCOLS.none { it.type == value }) failures += "$name($value)：没有登记在 ProtocolInventory.PROTOCOLS"
            if (value !in EXPECTED) failures += "$name($value)：没有登记在 ProtocolContractTest.EXPECTED"
        }
        for (proto in ProtocolInventory.PROTOCOLS) {
            if (proto.type !in declared.values) failures += "${proto.name}：类型 ${proto.type} 不在 ProxyEntity 的 TYPE_* 常量里"
        }
        assertEquals("类型值重复", declared.size, declared.values.toSet().size)
        assertEquals("PROTOCOLS 里有重复的类型", ProtocolInventory.PROTOCOLS.size, ProtocolInventory.PROTOCOLS.map { it.type }.toSet().size)
        assertEquals("PROTOCOLS 里有重复的 bean 类", ProtocolInventory.PROTOCOLS.size, ProtocolInventory.PROTOCOLS.map { it.beanClass }.toSet().size)
        assertNoFailures(failures)
    }

    // ---- 每个类型的 fixture：有专属链接的取链接变体里的 bean，其余手写

    private fun fixtures(proto: Proto): List<AbstractBean> {
        val dedicated = ProtocolLinkCases.SPECS.filter { it.type == proto.type }.flatMap { spec -> spec.cases.map { it.bean() } }
        if (dedicated.isNotEmpty()) return dedicated
        val bean: AbstractBean = when (proto.type) {
            TYPE_MIERU -> MieruBean().apply {
                name = "mieru-node"; serverAddress = "mieru.example.com"; serverPort = 2999
                protocol = "TCP"; username = "user"; password = "pass-word"; mtu = 1400
            }

            ProxyEntity.TYPE_SSH -> SSHBean().apply {
                name = "ssh-node"; serverAddress = "ssh.example.com"; serverPort = 22
                username = "user"; authType = SSHBean.AUTH_TYPE_PASSWORD; password = "pass-word"
            }

            ProxyEntity.TYPE_WG -> WireGuardBean().apply {
                name = "wg-node"; serverAddress = "wg.example.com"; serverPort = 51820
                localAddress = "10.0.0.2/32"; privateKey = "A".repeat(43) + "="; peerPublicKey = "B".repeat(43) + "="
            }

            ProxyEntity.TYPE_SHADOWTLS -> ShadowTLSBean().apply {
                name = "shadowtls-node"; serverAddress = "stls.example.com"; serverPort = 443
                version = 3; password = "pass-word"; security = "tls"; sni = "www.example.org"
            }

            TYPE_CHAIN -> ChainBean().apply { name = "chain-node"; proxies = listOf(1L, 2L) }
            ProxyEntity.TYPE_CONFIG -> ConfigBean().apply { name = "config-node"; type = 1; config = "{\"type\":\"direct\"}" }
            else -> error("${proto.name}：没有 fixture，补在 ProtocolContractTest.fixtures")
        }
        return listOf(bean.apply { initializeDefaultValues() })
    }

    private val constructible get() = ProtocolInventory.PROTOCOLS.filter { it.constructible }

    // ---- ProtocolHandlers 里按类型 / 按 bean 类的分派

    @Test
    fun `putBean、beanForType、putByteArray 对每个类型都有分支且互相一致`() {
        val failures = ArrayList<String>()
        for (proto in constructible) {
            for (bean in fixtures(proto)) {
                val where = "${proto.name}/${bean.javaClass.simpleName}"
                val entity = ProxyEntity().putBean(bean)
                if (entity.type != proto.type) failures += "$where：putBean 把类型设成 ${entity.type}，期望 ${proto.type}"
                if (entity.beanForType() !== bean) failures += "$where：putBean 之后 beanForType 取不回同一个 bean"
                // 序列化字节 -> putByteArray（分享链接、备份记录的严格路径）
                val loaded = ProxyEntity(type = proto.type).apply { putByteArray(KryoConverters.serialize(bean)) }
                val loadedBean = loaded.beanForType()
                if (loadedBean == null) failures += "$where：putByteArray 没有填 bean 字段"
                else failures += ProtocolInventory.diff("$where（putByteArray）", bean, loadedBean)
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `Neko 的 putByteArray 与 displayType 分支存在`() {
        // NekoBean 在 JVM 上构造不出来（org.json）：putByteArray 的分支一进去就在 NekoBean() 上失败；
        // 没有这条分支时它会静默什么都不做，所以「抛异常」本身就是分支存在的证据
        val failed = runCatching { ProxyEntity(type = TYPE_NEKO).putByteArray(byteArrayOf(0)) }.isFailure
        assertTrue("TYPE_NEKO 在 putByteArray 里没有分支", failed)
        assertEquals("Neko", ProxyEntity(type = TYPE_NEKO).displayType())
    }

    @Test
    fun `displayType 对每个类型都不落到未定义类型`() {
        val failures = ArrayList<String>()
        for (proto in ProtocolInventory.PROTOCOLS) {
            val entity = ProxyEntity(type = proto.type)
            if (proto.constructible) entity.putBean(fixtures(proto).first())
            // 链的显示名读 string 资源（JVM 上没有 app），读不到抛异常也说明走进了分支
            val text = runCatching { entity.displayType() }.getOrNull()
            if (text != null && text.startsWith("Undefined type")) failures += "${proto.name}：displayType 没有分支（$text）"
        }
        assertNoFailures(failures)
    }

    @Test
    fun `haveLink、hasStandardLink 与预期表一致`() {
        val failures = ArrayList<String>()
        for (proto in ProtocolInventory.PROTOCOLS) {
            val expect = EXPECTED.getValue(proto.type)
            val have = ProxyEntity(type = proto.type).haveLink()
            if (have != expect.haveLink) failures += "${proto.name}：haveLink 是 $have，预期 ${expect.haveLink}"
            if (proto.constructible) {
                for (bean in fixtures(proto)) {
                    val standard = hasStandardLink(bean)
                    if (standard != expect.hasStandardLink) {
                        failures += "${proto.name}/${bean.javaClass.simpleName}：hasStandardLink 是 $standard，预期 ${expect.hasStandardLink}"
                    }
                }
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `hasStandardLink 为真的类型要么有专属链接，要么写明是已知缺口`() {
        val dedicated = ProtocolLinkCases.DEDICATED_TYPES
        val failures = ArrayList<String>()
        for (proto in constructible) {
            val expect = EXPECTED.getValue(proto.type)
            val isDedicated = proto.type in dedicated
            if ((expect.share == Share.DEDICATED) != isDedicated) {
                failures += "${proto.name}：预期表写 ${expect.share}，ProtocolLinkCases 里${if (isDedicated) "有" else "没有"}它的专属链接规格"
            }
            // hasStandardLink 为真却没有专属链接：必须在预期表里带 note 说明（Mieru、Chain），修好后要把 note 一并删掉
            val gap = expect.hasStandardLink && !isDedicated
            if (gap && expect.note.isEmpty()) failures += "${proto.name}：hasStandardLink 为真但没有专属链接，预期表没有说明"
            if (!gap && expect.note.isNotEmpty() && proto.constructible && proto.type != TYPE_NEKO) {
                failures += "${proto.name}：预期表的说明已经过期：${expect.note}"
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `settingActivityOf 对有编辑器的类型给出编辑器，其余抛异常`() {
        val failures = ArrayList<String>()
        for (proto in ProtocolInventory.PROTOCOLS) {
            val expect = EXPECTED.getValue(proto.type)
            val result = runCatching { settingActivityOf(proto.type) }
            if (expect.editor && result.isFailure) failures += "${proto.name}：settingActivityOf 没有分支（${result.exceptionOrNull()}）"
            if (!expect.editor && result.isSuccess) failures += "${proto.name}：预期没有编辑器，却有 ${result.getOrNull()}"
        }
        assertNoFailures(failures)
    }

    @Test
    fun `通用链接的类型表 TypeMap 与预期表一致且互相可逆`() {
        val failures = ArrayList<String>()
        for (proto in ProtocolInventory.PROTOCOLS) {
            val expect = EXPECTED.getValue(proto.type)
            val name = TypeMap.reversed[proto.type]
            if (expect.universalType && name == null) failures += "${proto.name}：TypeMap 里没有这个类型"
            if (!expect.universalType && name != null) failures += "${proto.name}：预期不在 TypeMap 里，却叫 $name"
            if (name != null && TypeMap[name] != proto.type) failures += "${proto.name}：TypeMap[$name] 取回 ${TypeMap[name]}"
        }
        assertEquals("TypeMap 里有不在 PROTOCOLS 的类型", TypeMap.reversed.keys - ProtocolInventory.PROTOCOLS.map { it.type }.toSet(), emptySet<Int>())
        assertNoFailures(failures)
    }

    @Test
    fun `通用链接信封里的 bean 字节对每个能构造的类型往返不变`() {
        // sn://<类型名>?<base64(zlib(Kryo 字节))>：base64 与 zlib 的信封在 JVM 上跑不了（android.util.Base64），
        // 这里验证信封里装的东西：类型名 -> 类型 -> universalBean(类型, Kryo 字节) 还原出同一个 bean
        val failures = ArrayList<String>()
        for (proto in constructible) {
            val name = TypeMap.reversed[proto.type] ?: continue
            for (bean in fixtures(proto)) {
                val back = runCatching { universalBean(TypeMap.getValue(name), KryoConverters.serialize(bean)) { false } }
                    .getOrElse { failures += "${proto.name}：universalBean 抛出 $it"; continue }
                failures += ProtocolInventory.diff("${proto.name}/${bean.javaClass.simpleName}（通用链接）", bean, back)
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `构建入口：每个类型要么有 sing-box 出站分支要么有外核条目，与预期表一致`() {
        val failures = ArrayList<String>()
        for (proto in constructible) {
            val expect = EXPECTED.getValue(proto.type)
            for (bean in fixtures(proto)) {
                val where = "${proto.name}/${bean.javaClass.simpleName}"
                // buildSingBoxOutbound：没有分支时抛 "can't reach"；fixture 不满足别的校验抛的其它异常也说明走进了分支
                val singBox = runCatching { buildSingBoxOutbound(bean, false) }.exceptionOrNull()
                    .let { it !is IllegalStateException || it.message != "can't reach" }
                if (singBox != expect.singBox) failures += "$where：sing-box 出站分支是 $singBox，预期 ${expect.singBox}"
                val external = externalCore(bean) != null
                if (external != expect.external) failures += "$where：外核条目是 $external，预期 ${expect.external}"
            }
            // Chain 由规划展开成成员，Neko 由插件承载：这两种没有出站，也没有外核条目，其余必须至少有一种
            if (proto.type != TYPE_CHAIN && proto.type != TYPE_NEKO && !expect.singBox && !expect.external) {
                failures += "${proto.name}：既没有 sing-box 出站也没有外核条目，构建不出来"
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `选核判定说要走外核的节点都有外核条目`() {
        val failures = ArrayList<String>()
        for (proto in constructible) {
            for (bean in fixtures(proto)) {
                for (global in listOf(false, true)) {
                    val decision = runCatching { decideCore(proto.type, CORE_AUTO, bean, global) }.getOrElse {
                        failures += "${proto.name}/${bean.javaClass.simpleName}：decideCore 抛出 $it"; continue
                    }
                    val needs = ProxyEntity().putBean(bean).needExternal(decision)
                    if (needs && externalCore(bean) == null) failures += "${proto.name}/${bean.javaClass.simpleName}：needExternal 为真但 externalCore 没有条目"
                }
            }
        }
        assertNoFailures(failures)
    }

    @Test
    fun `singMux 只有 VMess 与 Trojan 返回选项`() {
        // 开着 mux 的 bean：Standard 系（HTTP / ShadowTLS 同类）里只有 VMess、Trojan 走 sing-box multiplex
        val failures = ArrayList<String>()
        for (proto in constructible) {
            val bean = fixtures(proto).first()
            if (bean is io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean) bean.enableMux = true
            val mux = runCatching { ProxyEntity().putBean(bean).singMux() }.getOrNull()
            val expect = proto.type == ProxyEntity.TYPE_VMESS || proto.type == ProxyEntity.TYPE_TROJAN
            if ((mux != null) != expect) failures += "${proto.name}：singMux 是 $mux，预期${if (expect) "有" else "没有"}选项"
        }
        assertNoFailures(failures)
    }

    // ---- Kryo

    @Test
    fun `每个 bean 类都有 Kryo 转换器与兼容性登记`() {
        val converters = KryoConverters::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.returnType }
            .toSet()
        val registered = KryoSamples.registry.getAsJsonObject("classes").keySet()
        val skipped = KryoSamples.registry.getAsJsonObject("skippedClasses").keySet()
        val failures = ArrayList<String>()
        for (proto in ProtocolInventory.PROTOCOLS) {
            val cls = proto.beanClass
            if (cls !in converters) failures += "${proto.name}：KryoConverters 没有返回 ${cls.simpleName} 的转换器（Room 列读不出来）"
            if (cls.name !in registered && cls.name !in skipped) failures += "${proto.name}：${cls.name} 没有登记在 kryo/registry.json"
        }
        assertNoFailures(failures)
    }

    // Kryo 布局里有些字段只在判别字段取特定值时才写（传输方式决定 host / path，security 决定 TLS 字段，
    // SSH 的 authType 决定密码还是私钥）。每个类用几种「形态」把判别字段设成会写出这些字段的值，
    // 一个字段只要在任一形态下往返不丢就算持久化了
    private val V2RAY_SHAPES = listOf(
        mapOf("type" to "ws", "security" to "tls"),
        mapOf("type" to "http", "security" to "none"),
        mapOf("type" to "grpc", "security" to "none"),
    )
    private val KRYO_SHAPES: Map<Class<out AbstractBean>, List<Map<String, Any?>>> = mapOf(
        io.nekohasekai.sagernet.fmt.http.HttpBean::class.java to V2RAY_SHAPES,
        io.nekohasekai.sagernet.fmt.v2ray.VMessBean::class.java to V2RAY_SHAPES,
        io.nekohasekai.sagernet.fmt.trojan.TrojanBean::class.java to V2RAY_SHAPES,
        ShadowTLSBean::class.java to V2RAY_SHAPES,
        io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean::class.java to listOf(mapOf("type" to "ws")),
        SSHBean::class.java to listOf(
            mapOf("authType" to SSHBean.AUTH_TYPE_PASSWORD),
            mapOf("authType" to SSHBean.AUTH_TYPE_PRIVATE_KEY),
        ),
    )

    // 有意不持久化的字段 -> 原因
    private val KRYO_NOT_PERSISTED: Map<Class<out AbstractBean>, Map<String, String>> = mapOf(
        ChainBean::class.java to mapOf(
            "serverAddress" to "链没有服务器：ChainBean.serialize 只写成员 id 列表，不写 AbstractBean 的地址与端口",
            "serverPort" to "同上",
        ),
    )

    @Test
    fun `字段填满的 bean 经 Kryo 往返后每个字段都至少在一种形态下不丢`() {
        // 把每个公开字段填成与默认值不同的值：新增字段没写进 serialize / deserialize 时，往返后这个字段变回默认值。
        // KryoCompatTest 用历史字节验证「读得出旧数据」，这里验证「当前写出的字节不丢字段」
        val failures = ArrayList<String>()
        for (proto in constructible) {
            val cls = proto.beanClass
            val persisted = HashSet<String>()
            for (shape in KRYO_SHAPES[cls] ?: listOf(emptyMap())) {
                val bean = cls.getDeclaredConstructor().newInstance()
                ProtocolInventory.fillNonDefault(bean)
                for ((name, value) in shape) cls.getField(name).set(bean, value)
                val where = "${proto.name}$shape"
                // bean 本身的往返（Room 列）
                val copy = cls.getDeclaredConstructor().newInstance()
                KryoConverters.deserialize(copy, KryoConverters.serialize(bean))
                // 节点实体的往返（备份、Parcel 走 ProxyEntity 的序列化，里面再嵌套 bean 字节）
                val entity = ProxyEntity(id = 7, groupId = 3, userOrder = 5).putBean(bean)
                val back = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity))
                if (back.type != proto.type) failures += "$where（实体往返）：类型 ${back.type}，期望 ${proto.type}"
                val want = ProtocolInventory.snapshot(bean)
                val viaBean = ProtocolInventory.snapshot(copy)
                val viaEntity = ProtocolInventory.snapshot(back.requireBean())
                for ((name, value) in want) {
                    // 实体往返比 bean 往返多一层，两条路径对同一个字段的结论必须一致
                    if ((viaBean[name] == value) != (viaEntity[name] == value)) {
                        failures += "$where.$name：bean 往返与实体往返的结论不一致"
                    }
                    if (viaBean[name] == value) persisted += name
                }
            }
            val exempt = KRYO_NOT_PERSISTED[cls].orEmpty()
            for (field in ProtocolInventory.fieldsOf(cls)) {
                val kept = field.name in persisted
                if (!kept && field.name !in exempt) failures += "${proto.name}.${field.name}：任何形态下 Kryo 往返都丢了这个字段，serialize / deserialize 漏写？"
                if (kept && field.name in exempt) failures += "${proto.name}.${field.name}：写在 KRYO_NOT_PERSISTED 里，但其实会持久化，该从清单删掉"
            }
        }
        assertNoFailures(failures)
    }

    // ---- 构建入口：黄金场景

    private val baseline by lazy { GoldenBaseline.load() }

    @Test
    fun `每个能构造的类型都有一个主节点是它、三种模式都构建成功的黄金场景`() {
        // 黄金场景（app/src/test/resources/golden/）的输入是模拟器上的真实节点字节，GoldenJvmBuildTest 逐字节比对产物。
        // 这里用它们回答「这个类型能经纯函数 buildConfig 构建」：每个类型至少有一个主节点属于它、基线里 run / test /
        // export 三种模式 status 都是 ok 的场景，并在 JVM 上重新构建一遍确认 status 仍是 ok
        val corpus = GoldenAddressCorpus.of(baseline)
        val failures = ArrayList<String>()
        val byType = HashMap<Int, MutableList<String>>()
        for (id in baseline.scenarioIds) {
            val input = baseline.input(id)
            val type = input.dataSource().profile(input.mainProfileId)!!.type
            val allOk = ConfigBuildMode.entries.all { mode ->
                val result = File(input.dir, "${mode.dir}/result.json")
                result.isFile && JsonParser.parseString(result.readText()).asJsonObject["status"].asString == "ok"
            }
            if (allOk) byType.getOrPut(type) { ArrayList() } += id
        }
        for (proto in ProtocolInventory.PROTOCOLS) {
            val ids = byType[proto.type].orEmpty()
            if (proto.type == TYPE_NEKO) {
                // 没有黄金场景（NekoBean 在 JVM 上构造不出来）；Neko 的构建不经 buildConfig 的出站分派（外核表没有它的条目）
                continue
            }
            if (ids.isEmpty()) {
                failures += "${proto.name}：黄金场景里没有主节点是它、三种模式都成功的场景，补一个场景（模拟器重新采集）"
                continue
            }
            val id = ids.first()
            val scenario = GoldenJvmScenario(baseline.input(id), corpus, temp.newFolder())
            for (mode in ConfigBuildMode.entries) {
                val output = scenario.run(mode)
                val status = output.result["status"]
                if (status != "ok") failures += "${proto.name}：场景 $id 在 JVM 上 ${mode.dir} 模式构建结果 $status"
                if (mode == ConfigBuildMode.RUN && output.files["sing-box.json"].isNullOrBlank()) {
                    failures += "${proto.name}：场景 $id 没有产出 sing-box 配置"
                }
            }
        }
        assertNoFailures(failures)
    }
}
