package io.nekohasekai.sagernet.golden.measure

import android.app.ActivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.stateOrStopped
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ExternalRunPlan
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean.FLOW_VISION
import io.nekohasekai.sagernet.golden.collect.CollectRefusedException
import io.nekohasekai.sagernet.golden.collect.GoldenCollector
import io.nekohasekai.sagernet.golden.collect.ScenarioBuilder
import io.nekohasekai.sagernet.golden.collect.anytls
import io.nekohasekai.sagernet.golden.collect.reality
import io.nekohasekai.sagernet.golden.collect.shadowsocks
import io.nekohasekai.sagernet.golden.collect.tls
import io.nekohasekai.sagernet.golden.collect.vless
import io.nekohasekai.sagernet.golden.collect.vmess
import io.nekohasekai.sagernet.golden.collect.ws
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// K0 实测入口（plan.md K0「实测」）：只编进 debug 包，由 buildScript/golden/measure.sh 经
// GoldenCollectProvider 的 measure* 方法分步触发：
//   measurePrepare  防误伤检查 → 记下原有设置 → 写入选择器分组夹具与实测用设置
//   measureStart    走应用正常的启动路径（VpnService.prepare + SagerNet.startService）发出启动，立即返回
//   measureStatus   服务当前状态，以及发出启动以来收到的状态变化（相对启动请求的毫秒数）
//   measureFinish   停止服务（广播 CLOSE，与通知栏按钮同一路径）→ 清掉夹具 → 恢复原有设置
// 只测量，不改变服务、外核进程的启动与守护逻辑。进程数、内存、系统日志由主机脚本在设备外采集
object GoldenMeasure {

    const val METHOD_PREPARE = "measurePrepare"
    const val METHOD_START = "measureStart"
    const val METHOD_STATUS = "measureStatus"
    const val METHOD_FINISH = "measureFinish"
    val METHODS = setOf(METHOD_PREPARE, METHOD_START, METHOD_STATUS, METHOD_FINISH)

    const val FORMAT_VERSION = 1

    // 夹具写进数据库前建、清掉后删，内容是实测前的设置表（格式同采集入口的标记文件）。
    // 中途被打断时，下次据它认出库里的行是实测入口留下的，并按它恢复设置
    const val OWNED_MARKER = "golden-measure.owned"

    private const val GROUP_ID = 1L
    private const val MAX_NODES = 600
    private const val SERVICE_CONNECT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_STOP_TIMEOUT_MS = 30_000L

    // 实测用的 DNS：与采集夹具相同的文档用途地址，连不上，不向真实服务器发查询
    private const val MEASURE_REMOTE_DNS = "https://dns.example.net/dns-query"
    private const val MEASURE_DIRECT_DNS = "https://192.0.2.53/dns-query"

    private val ownedMarker get() = File(app.filesDir, OWNED_MARKER)
    private val collectMarker get() = File(app.filesDir, GoldenCollector.OWNED_MARKER)

    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    // 以下状态跨 call 保留在主进程里：start 绑定的连接、发出启动的时刻、收到的状态变化
    @Volatile
    private var connection: SagerConnection? = null

    @Volatile
    private var requestedAtElapsed = -1L

    @Volatile
    private var requestedAtWall = -1L

    private val transitions = CopyOnWriteArrayList<Map<String, Any?>>()

    fun call(method: String, extras: Bundle): Map<String, Any?> = when (method) {
        METHOD_PREPARE -> prepare(extras)
        METHOD_START -> start()
        METHOD_STATUS -> status()
        METHOD_FINISH -> finish(extras)
        else -> throw CollectRefusedException("unknown method $method")
    }

    // 结果整体转成 JSON 再 Base64：content call 打印 Bundle 时不转义，JSON 里的逗号与括号会让
    // 主机侧无法切分
    fun encode(result: Map<String, Any?>): String =
        Base64.getEncoder().encodeToString(gson.toJson(result).toByteArray())

    // ---- prepare ----

    private fun prepare(extras: Bundle): Map<String, Any?> {
        fun count(key: String, default: Int) = extras.getInt(key, default).also {
            if (it < 0) throw CollectRefusedException("negative $key")
        }
        val xray = count("xray", 38)
        val mihomo = count("mihomo", 2)
        val singbox = count("singbox", 2)
        val total = xray + mihomo + singbox
        if (total == 0) throw CollectRefusedException("the selector group needs at least one member")
        if (total > MAX_NODES) throw CollectRefusedException("at most $MAX_NODES members")
        val mode = extras.getString("mode") ?: Key.MODE_VPN
        if (mode != Key.MODE_VPN && mode != Key.MODE_PROXY) throw CollectRefusedException("unknown mode $mode")
        val logLevel = count("logLevel", 1)
        val allowWipe = extras.getBoolean("allowWipe", false)

        if (collectMarker.exists()) throw CollectRefusedException(
            "an interrupted golden collection left ${collectMarker.name}; run ./run golden collect once to restore first"
        )
        val running = runningServices()
        if (running.isNotEmpty()) throw CollectRefusedException(
            "the proxy service is running (${running.joinToString()}); stop it first"
        )
        checkOwnership(allowWipe)

        val kvDao = PublicDatabase.kvPairDao
        val savedSettings = ownedMarker.takeIf { it.isFile }
            ?.let { runCatching { decodeSettings(it.readText()) }.getOrNull() }
            ?: kvDao.all()
        ownedMarker.writeText(encodeSettings(savedSettings))

        val fixture = fixture(xray, mihomo, singbox)
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.proxyDao.reset()
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(fixture.groups)
            SagerDatabase.proxyDao.insert(fixture.profiles)
        }
        // 设置：整表清空（其余键取代码里的默认值），只写实测需要的键与进程启动时会写回的键
        kvDao.reset()
        kvDao.insert(savedSettings.filter { it.key in GoldenCollector.PRESERVED_SETTINGS })
        DataStore.serviceMode = mode
        DataStore.logLevel = logLevel
        DataStore.remoteDns = MEASURE_REMOTE_DNS
        DataStore.directDns = MEASURE_DIRECT_DNS
        DataStore.selectedGroup = GROUP_ID
        DataStore.selectedProxy = fixture.mainProfileId

        // 用生产代码构建一次选中节点的配置，核对夹具确实按预期分到各核心：
        // 外核数量与参数不符时实测没有意义，直接失败
        PackageCache.awaitLoadSync()
        val selected = SagerDatabase.proxyDao.getById(fixture.mainProfileId)
            ?: error("selected profile ${fixture.mainProfileId} is not in the table")
        val plan = ExternalRunPlan.from(buildConfig(selected))
        val external = linkedMapOf<String, Int>()
        for (hop in plan.hops) external[hop.pluginId] = (external[hop.pluginId] ?: 0) + 1
        val expected = linkedMapOf("xray-plugin" to xray, "mihomo-plugin" to mihomo).filterValues { it > 0 }
        if (external != expected) throw CollectRefusedException(
            "the fixture maps to external cores $external, expected $expected"
        )
        // 按运行计划应起的外核进程数（每组一个）
        val processes = linkedMapOf<String, Int>()
        for (group in plan.groups) processes[group.pluginId] = (processes[group.pluginId] ?: 0) + 1

        return linkedMapOf(
            "formatVersion" to FORMAT_VERSION,
            "groupId" to GROUP_ID,
            "mainProfileId" to fixture.mainProfileId,
            "members" to linkedMapOf("xray" to xray, "mihomo" to mihomo, "singbox" to singbox),
            "externalIndex" to external,
            "externalProcesses" to processes,
            "serviceMode" to mode,
            "logLevel" to logLevel,
            "savedSettings" to savedSettings.size,
        )
    }

    // 选择器分组：先 Xray（VLESS + REALITY），再 mihomo（AnyTLS），最后 sing-box 内核节点
    // （Shadowsocks 与 VMess + WS + TLS 交替）。选中第一个成员。地址按顺序取 192.0.2.0/24、
    // 198.51.100.0/24、203.0.113.0/24 的主机地址：IP 字面量，不触发域名解析，也连不上
    private fun fixture(xray: Int, mihomo: Int, singbox: Int) = ScenarioBuilder("measure", "K0 实测").apply {
        group(GROUP_ID, name = "measure-selector", selector = true)
        var next = 0L
        fun add(bean: AbstractBean) {
            next++
            node(next, bean, group = GROUP_ID)
        }
        for (i in 1..xray) {
            add(vless("measure-xray-$i", server = address(next.toInt()), port = 443, flow = FLOW_VISION)
                .reality(utls = "chrome"))
        }
        for (i in 1..mihomo) {
            add(anytls("measure-anytls-$i", server = address(next.toInt()), port = 8443, sni = "anytls.example.com"))
        }
        for (i in 1..singbox) {
            val server = address(next.toInt())
            add(
                if (i % 2 == 1) shadowsocks("measure-ss-$i", server = server, port = 8388)
                else vmess("measure-vmess-$i", server = server, port = 443).ws().tls(sni = "cdn.example.org")
            )
        }
        main = 1L
    }.build()

    private fun address(index: Int): String {
        val prefixes = listOf("192.0.2", "198.51.100", "203.0.113")
        return "${prefixes[index / 254]}.${index % 254 + 1}"
    }

    // 防误伤：同采集入口。库里有不是实测入口建的节点 / 分组 / 规则时拒绝，除非带 allowWipe
    private fun checkOwnership(allowWipe: Boolean) {
        val groups = SagerDatabase.groupDao.allGroups()
        val profiles = SagerDatabase.proxyDao.getAll()
        val rules = SagerDatabase.rulesDao.allRules()
        if (groups.isEmpty() && profiles.isEmpty() && rules.isEmpty()) return
        val ours = ownedMarker.exists() &&
            groups.all { it.id == GROUP_ID } &&
            profiles.all { it.groupId == GROUP_ID && it.id in 1..MAX_NODES } &&
            rules.isEmpty()
        if (ours) return
        if (allowWipe) {
            Logs.w("golden measure: wiping ${groups.size} groups, ${profiles.size} profiles, ${rules.size} rules")
            return
        }
        throw CollectRefusedException(
            "the database holds ${groups.size} groups, ${profiles.size} profiles and ${rules.size} rules " +
                "that the measurement did not create; measuring replaces all of them. " +
                "Re-run with --allow-wipe if this device holds nothing worth keeping"
        )
    }

    // 本应用已启动的服务（VpnService / ProxyService）。getRunningServices 对调用方自己的服务仍然有效
    @Suppress("DEPRECATION")
    private fun runningServices(): List<String> {
        val am = app.getSystemService<ActivityManager>() ?: return emptyList()
        return am.getRunningServices(Int.MAX_VALUE)
            .filter { it.uid == Process.myUid() && it.started }
            .map { it.service.className.substringAfterLast('.') }
            .filter { it == "VpnService" || it == "ProxyService" }
    }

    // ---- start ----

    private fun start(): Map<String, Any?> {
        if (!ownedMarker.exists()) throw CollectRefusedException("nothing prepared: call measurePrepare first")
        val service = bind()
        val before = service.stateOrStopped
        if (before != BaseService.State.Stopped) throw CollectRefusedException(
            "the service is $before; it must be stopped before a measurement"
        )
        val mode = DataStore.serviceMode
        // 与界面的启动按钮同一路径（VpnRequestActivity.StartService）：VPN 模式先确认已授权
        if (mode == Key.MODE_VPN && VpnService.prepare(app) != null) throw CollectRefusedException(
            "VPN is not authorized for ${app.packageName}; grant it with " +
                "adb shell appops set ${app.packageName} ACTIVATE_VPN allow"
        )
        transitions.clear()
        requestedAtWall = System.currentTimeMillis()
        requestedAtElapsed = SystemClock.elapsedRealtime()
        // startService() 吞掉系统拒绝启动前台服务时的 IllegalStateException，返回 null
        SagerNet.startService()
            ?: throw CollectRefusedException("startForegroundService was refused (see adb logcat)")
        return linkedMapOf(
            "requestedAtElapsedMs" to requestedAtElapsed,
            "requestedAtWallMs" to requestedAtWall,
            "serviceMode" to mode,
            "service" to SagerConnection.serviceClass.name,
            "stateBefore" to before.name,
            "processes" to appProcesses(),
        )
    }

    // 绑定服务并等到连上；同一个连接一直用到 finish。状态变化由回调记下（回调在主线程上分发）
    private fun bind(): ISagerNetService {
        connection?.service?.let { return it }
        connection?.let { runCatching { it.disconnect(app) } }
        val latch = CountDownLatch(1)
        val conn = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
        val callback = object : SagerConnection.Callback {
            override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                record(state.name, msg)
            }

            override fun onServiceConnected(service: ISagerNetService) {
                latch.countDown()
            }

            override fun onServiceDisconnected() {
                record("ServiceDisconnected", null)
            }
        }
        connection = conn
        if (!conn.connect(app, callback)) {
            connection = null
            throw CollectRefusedException("bindService was refused")
        }
        if (!latch.await(SERVICE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw CollectRefusedException("the service did not bind within ${SERVICE_CONNECT_TIMEOUT_MS}ms")
        }
        return conn.service ?: throw CollectRefusedException("the service disconnected right after binding")
    }

    private fun record(state: String, msg: String?) {
        val now = SystemClock.elapsedRealtime()
        transitions += linkedMapOf(
            "state" to state,
            "tMs" to if (requestedAtElapsed >= 0) now - requestedAtElapsed else null,
            "elapsedMs" to now,
            "msg" to msg,
        )
    }

    // ---- status ----

    private fun status(): Map<String, Any?> {
        val service = connection?.service
        val state = service?.let {
            runCatching { BaseService.State.fromOrdinal(it.state).name }.getOrElse { "BinderDead" }
        } ?: "Unbound"
        val now = SystemClock.elapsedRealtime()
        return linkedMapOf(
            "state" to state,
            "nowElapsedMs" to now,
            "tMs" to if (requestedAtElapsed >= 0) now - requestedAtElapsed else null,
            "requestedAtElapsedMs" to requestedAtElapsed.takeIf { it >= 0 },
            "requestedAtWallMs" to requestedAtWall.takeIf { it >= 0 },
            "transitions" to transitions.toList(),
            "processes" to appProcesses(),
        )
    }

    // 本应用的 app 进程（主进程与 :bg）；外核子进程不是 app 进程，由主机脚本用 ps 取
    private fun appProcesses(): Map<String, Int> {
        val am = app.getSystemService<ActivityManager>() ?: return emptyMap()
        return am.runningAppProcesses.orEmpty().associate { it.processName to it.pid }
    }

    // ---- finish ----

    private fun finish(extras: Bundle): Map<String, Any?> {
        val stopTimeout = extras.getLong("stopTimeoutMs", DEFAULT_STOP_TIMEOUT_MS)
        val result = linkedMapOf<String, Any?>()
        // 1. 停服务：与通知栏的停止按钮同一路径（Action.CLOSE 广播）
        val service = bind()
        val before = service.stateOrStopped
        result["stateBefore"] = before.name
        if (before != BaseService.State.Stopped) {
            val stopAt = SystemClock.elapsedRealtime()
            SagerNet.stopService()
            var state = before
            while (SystemClock.elapsedRealtime() - stopAt < stopTimeout) {
                state = connection?.service?.stateOrStopped ?: BaseService.State.Stopped
                if (state == BaseService.State.Stopped) break
                Thread.sleep(50)
            }
            result["stopMs"] = SystemClock.elapsedRealtime() - stopAt
            result["stateAfter"] = state.name
            if (state != BaseService.State.Stopped) {
                // 没停下来就不清夹具：服务还引用着这些节点。主机脚本会强行停止应用后再调一次
                result["stopped"] = false
                return result
            }
        } else {
            result["stopMs"] = null
            result["stateAfter"] = before.name
        }
        result["stopped"] = true
        result["transitions"] = transitions.toList()
        connection?.let { runCatching { it.disconnect(app) } }
        connection = null
        requestedAtElapsed = -1L
        requestedAtWall = -1L

        // 2. 清夹具、恢复设置
        if (ownedMarker.isFile) {
            val saved = decodeSettings(ownedMarker.readText())
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.proxyDao.reset()
                SagerDatabase.groupDao.reset()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(saved)
            ownedMarker.delete()
            result["restoredSettings"] = saved.size
        } else {
            result["restoredSettings"] = null
        }
        result["tablesEmpty"] = SagerDatabase.groupDao.allGroups().isEmpty() &&
            SagerDatabase.proxyDao.getAll().isEmpty() && SagerDatabase.rulesDao.allRules().isEmpty()
        return result
    }

    // 标记文件的格式与采集入口相同：[{key, valueType, value(Base64)}]
    private fun encodeSettings(list: List<KeyValuePair>): String = gson.toJson(list.map {
        linkedMapOf("key" to it.key, "valueType" to it.valueType, "value" to Base64.getEncoder().encodeToString(it.value))
    })

    private fun decodeSettings(text: String): List<KeyValuePair> = JsonParser.parseString(text).asJsonArray.map {
        val row = it.asJsonObject
        KeyValuePair().apply {
            key = row["key"].asString
            valueType = row["valueType"].asInt
            value = Base64.getDecoder().decode(row["value"].asString)
        }
    }
}
