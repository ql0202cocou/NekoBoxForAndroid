package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.parseNumericAddress
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.SingBoxOptionsUtil
import moe.matsuri.nb4a.plugin.Plugins
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Callable

// 配置构建外壳的 Android 一侧：从 DataStore、Room、PackageCache 采集 ConfigInput，并提供每次构建新建的平台适配
// 对象。纯构建（ConfigBuild）只经 ConfigInput 取这些，不直接碰这里的任何数据源

/**
 * 按 buildConfig(proxy, …) 的模式采集一次构建的输入，先后次序是契约的一部分：
 * 1. 设置，含 Clash API secret 的生成与写回：它持 RestoreJournal 的跨进程文件锁，必须在 Room 事务之外；
 * 2. 在一个 Room 读取事务里采引用闭包（[ConfigSnapshot.collect]）。库不是 WAL 模式，这个事务独占数据库，
 *    事务里只做 DAO 查询与字节化；
 * 3. 事务结束后，启用的规则带应用时才等 PackageCache（首次可能阻塞），解析这些包名的 UID；
 * 4. 新建本次构建的平台适配对象，插件状态由构建按需查询。
 * 四类输入在不同时刻取得，不是跨数据源的原子快照，与以前构建中逐项读取的语义相同。
 * proxy 是调用方的对象：只取它的记录（不回读数据库），之后不再碰它。
 */
internal fun captureConfigInput(proxy: ProxyEntity, mode: ConfigBuildMode): ConfigInput {
    val settings = ConfigSettings.fromDataStore(mode)
    val main = ProfileRecord.of(proxy)
    val snapshot = SagerDatabase.instance.runInTransaction(Callable {
        ConfigSnapshot.collect(RoomConfigDataSource, main, mode)
    })
    val packages = packagesToResolve(mode, snapshot)
    val packageUids = if (packages.isEmpty()) emptyMap() else {
        PackageCache.awaitLoadSync()
        packages.associateWith { PackageCache[it] }
    }
    return ConfigInput(mode, main, settings, snapshot, packageUids, AndroidConfigPlatform())
}

/**
 * 构建读到的设置，取 DataStore 当前值（含默认值）。Clash API secret 只在运行模式且开了 Clash API 时取，
 * 为空时生成并写回，条件同构建里用它的地方（导出与测速的配置不带 secret）。
 */
fun ConfigSettings.Companion.fromDataStore(mode: ConfigBuildMode): ConfigSettings {
    val enableClashAPI = DataStore.enableClashAPI
    return ConfigSettings(
        serviceMode = DataStore.serviceMode,
        allowAccess = DataStore.allowAccess,
        bypassLanInCore = DataStore.bypassLanInCore,
        remoteDns = DataStore.remoteDns,
        directDns = DataStore.directDns,
        enableDnsRouting = DataStore.enableDnsRouting,
        enableFakeDns = DataStore.enableFakeDns,
        trafficSniffing = DataStore.trafficSniffing,
        resolveDestination = DataStore.resolveDestination,
        ipv6Mode = DataStore.ipv6Mode,
        logLevel = DataStore.logLevel,
        mixedPort = DataStore.mixedPort,
        mtu = DataStore.mtu,
        tunImplementation = DataStore.tunImplementation,
        globalCustomConfig = DataStore.globalCustomConfig,
        enableClashAPI = enableClashAPI,
        clashApiSecret = if (needsClashApiSecret(mode, enableClashAPI)) DataStore.requireClashApiSecret() else null,
        globalAllowInsecure = DataStore.globalAllowInsecure,
        domainStrategyRemote = SingBoxOptionsUtil.domainStrategy("dns-remote"),
        domainStrategyDirect = SingBoxOptionsUtil.domainStrategy("dns-direct"),
        domainStrategyServer = SingBoxOptionsUtil.domainStrategy("server"),
    )
}

// 构建要读的 DAO 查询，只在 captureConfigInput 的读取事务里用；每次查询 Room 都给新对象
private object RoomConfigDataSource : ConfigDataSource {
    override fun group(id: Long): ProxyGroup? = SagerDatabase.groupDao.getById(id)
    override fun profile(id: Long): ProxyEntity? = SagerDatabase.proxyDao.getById(id)
    override fun profiles(ids: List<Long>): List<ProxyEntity> = SagerDatabase.proxyDao.getEntities(ids)
    override fun profilesByGroup(groupId: Long): List<ProxyEntity> = SagerDatabase.proxyDao.getByGroup(groupId)
    override fun enabledRules(): List<RuleEntity> = SagerDatabase.rulesDao.enabledRules()
}

// Android 上的平台能力，每次构建新建一个。插件查询每次都是一轮 IPC；ConfigBuild 按插件分别记住两种查询的结果，
// 同一插件的同一种查询在一次构建里只做一次
private class AndroidConfigPlatform : ConfigPlatform {

    override fun newPort(): Int = mkPort()

    override fun parseNumericAddress(text: String): InetAddress? = text.parseNumericAddress()

    override fun createTempFile(prefix: String, ext: String): File =
        File.createTempFile(prefix + "_", ".$ext", SagerNet.application.cacheDir)

    override fun pluginExternalAuthority(pluginId: String): String? = Plugins.getPluginExternal(pluginId)?.authority

    // 缺插件换成普通异常：预检据此跳过成员，消息里写明哪个插件没装
    override fun pluginError(pluginId: String): Exception? = try {
        PluginManager.init(pluginId)
        null
    } catch (_: PluginManager.PluginNotFoundException) {
        IllegalStateException("plugin $pluginId is not installed")
    } catch (e: Exception) {
        e
    }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) Logs.w(message) else Logs.w(message, error)
    }
}
