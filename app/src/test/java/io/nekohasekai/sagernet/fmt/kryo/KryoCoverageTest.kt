package io.nekohasekai.sagernet.fmt.kryo

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

// 覆盖性：registry.json 登记每一层的当前版本号与有样本的历史版本号。有人升了某层的版本号
// 却没先导出旧版本样本、没登记，这里失败并指出是哪一层、哪个版本
class KryoCoverageTest {

    private val registry = KryoSamples.registry
    private val layers = registry.getAsJsonObject("layers")
    private val classes = registry.getAsJsonObject("classes")
    private val skipped = registry.getAsJsonObject("skippedClasses")

    private fun check(failures: List<String>) {
        assertTrue("${failures.size} 处问题：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // 每个登记的类在当前实现下写出的各层版本号（ProxyGroup 两种格式都算）
    private fun currentWritten(): Map<String, Map<String, Int>> {
        val out = LinkedHashMap<String, Map<String, Int>>()
        for (name in classes.keySet()) {
            when (name) {
                ProxyEntity::class.java.name -> out[name] = KryoSamples.versionsOf(
                    ProxyEntity(type = ProxyEntity.TYPE_SOCKS, socksBean = SOCKSBean().applyDefaultValues())
                )

                ProxyGroup::class.java.name -> {
                    fun group() = ProxyGroup(type = GroupType.SUBSCRIPTION, subscription = SubscriptionBean().applyDefaultValues())
                    out["$name（存储格式）"] = KryoSamples.versionsOf(group())
                    out["$name（分享格式）"] = KryoSamples.versionsOf(group().apply { export = true })
                }

                else -> out[name] = KryoSamples.versionsOf(
                    (Class.forName(name).getDeclaredConstructor().newInstance() as Serializable).applyDefaultValues()
                )
            }
        }
        return out
    }

    @Test
    fun `各层当前写出的版本号与登记一致`() {
        val failures = ArrayList<String>()
        for ((cls, versions) in currentWritten()) {
            for ((layer, version) in versions) {
                val entry = layers.getAsJsonObject(layer)
                if (entry == null) {
                    failures += "$cls 写出了未登记的层 $layer（版本 $version）：在 kryo/registry.json 登记并补样本"
                    continue
                }
                val registered = entry["current"].asInt
                if (version != registered) {
                    failures += "$layer：当前实现写出版本 $version，registry.json 登记的当前版本是 $registered。" +
                        "升版本号前要先用升级前的实现导出 v$registered 样本入库（见 kryo/README.md「升版本号时怎么补样本」），" +
                        "再把登记改为 $version 并补当前版本样本"
                }
            }
        }
        check(failures)
    }

    @Test
    fun `每个版本都有样本或登记了没有样本的原因`() {
        val failures = ArrayList<String>()
        val seen = HashMap<String, MutableSet<Int>>()
        val currentSamples = HashMap<String, MutableSet<Int>>()
        for (sample in KryoSamples.samples) {
            for ((layer, version) in sample.versions) {
                seen.getOrPut(layer) { HashSet() } += version
                if (sample.current) currentSamples.getOrPut(layer) { HashSet() } += version
                if (layers.getAsJsonObject(layer) == null) failures += "${sample.id}：层 $layer 没有登记"
            }
        }
        for (layer in layers.keySet()) {
            val entry = layers.getAsJsonObject(layer)
            val current = entry["current"].asInt
            val sampled = entry.getAsJsonArray("sampled").map { it.asInt }.toSet()
            val unsampled = entry.getAsJsonObject("unsampled").keySet().map { it.toInt() }.toSet()
            val have = seen[layer].orEmpty()
            for (v in sampled - have) failures += "$layer v$v：登记为有样本，但 samples/ 里没有这个版本的样本"
            for (v in have - sampled) failures += "$layer v$v：samples/ 里有样本，但 registry.json 的 sampled 没有登记"
            for (v in 0..current) {
                if (v !in sampled && v !in unsampled) {
                    failures += "$layer v$v：既没有样本，也没有登记原因（unsampled）"
                }
            }
            for (v in sampled + unsampled) {
                if (v > current) failures += "$layer v$v：大于登记的当前版本 $current"
            }
            for (v in sampled intersect unsampled) failures += "$layer v$v：同时登记为有样本和没有样本"
            if (current !in currentSamples[layer].orEmpty()) {
                failures += "$layer：没有当前版本 v$current 的样本（current = true）"
            }
        }
        check(failures)
    }

    @Test
    fun `每个用这套格式存储的类都已登记`() {
        // Room 的列转换器覆盖每一个存进数据库的 bean；再加上本身写进备份的两个实体
        val stored = KryoConverters::class.java.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && Serializable::class.java.isAssignableFrom(it.returnType) }
            .filter { !Modifier.isAbstract(it.returnType.modifiers) } // 泛型的 deserialize 擦除后返回 Serializable
            .map { it.returnType.name }
            .toSet() + ProxyEntity::class.java.name + ProxyGroup::class.java.name
        val failures = ArrayList<String>()
        for (name in stored) {
            if (!classes.has(name) && !skipped.has(name)) failures += "$name 没有在 kryo/registry.json 登记"
        }
        for (name in classes.keySet()) {
            val beanLayers = KryoSamples.layersOf(name)
            if (AbstractBean::class.java.isAssignableFrom(Class.forName(name)) && beanLayers.last() != "AbstractBean") {
                failures += "$name 的层列表应以 AbstractBean 结尾"
            }
            if (KryoSamples.samples.none { it.className == name && it.current }) failures += "$name 没有当前版本的样本"
            if (KryoSamples.samples.none { it.className == name && !it.current }) failures += "$name 没有旧版本实现写出的样本"
        }
        check(failures)
    }
}
