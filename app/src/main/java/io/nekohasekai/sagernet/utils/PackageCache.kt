package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    lateinit var uidMap: Map<Int, Set<String>>
    // 首次加载完成的标志，写在 reload() 的最后一步（volatile 写）：读到 true 的线程
    // 一定能看到前面所有字段的赋值。加载本身由 PackageCache 的监视器串行化
    @Volatile
    private var ready = false
    var registerd = AtomicBoolean(false)

    // called from init (suspend)：确保已加载并注册包变更监听，只注册一次
    fun register() {
        if (registerd.getAndSet(true)) return
        try {
            ensureLoaded()
            app.listenForPackageChanges(false) {
                reload()
                labelMap.clear()
            }
        } catch (e: Throwable) {
            // 失败后允许下一次 register() 重试；awaitLoadSync() 自己也会重试加载
            registerd.set(false)
            Logs.w(e)
        }
    }

    // 未加载时在监视器内加载一次：先拿到锁的线程负责加载，其他线程在锁上等它，
    // 进锁后再次检查 ready，已加载就不重复扫描。加载抛异常时 ready 保持 false，
    // 异常交给调用方，下一次调用会重新尝试
    private fun ensureLoaded() {
        if (ready) return
        synchronized(this) {
            if (!ready) reload()
        }
    }

    @Synchronized // register() and the package-change receiver may race
    @SuppressLint("InlinedApi")
    fun reload() {
        val rawPackageInfo = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        installedPackages = rawPackageInfo.filter {
            when (it.packageName) {
                "android" -> true
                else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
        }.associateBy { it.packageName }

        installedPluginPackages = rawPackageInfo.filter {
            Plugins.isExe(it)
        }.associateBy { it.packageName }

        // 不再单独 getInstalledApplications(GET_META_DATA)：上面的查询同样带
        // GET_META_DATA，applicationInfo 内容一致；MATCH_UNINSTALLED_PACKAGES 多出的
        // 已卸载（保留数据）包没有 FLAG_INSTALLED，筛掉后与原查询基本一致，省一次
        // 全量 IPC。被设备管理设为 hidden 的已安装包理论上仍会多出来（未上机确认）
        val installed = rawPackageInfo.mapNotNull { it.applicationInfo }
            .filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        installedApps = installed.associateBy { it.packageName }
        // swap the whole map like the fields above: packageNameByUid reads
        // this from a gomobile callback thread with no synchronization, so
        // mutating a shared HashMap in place can corrupt a concurrent read.
        // ready 必须是最后一次写入（见 ready 的注释）
        val newUidMap = HashMap<Int, HashSet<String>>()
        for (info in installed) {
            val uid = info.uid
            newUidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
        uidMap = newUidMap
        packageMap = installed.associate { it.packageName to it.uid }
        ready = true
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    // 非阻塞就绪状态：主线程调用方（AppListPreference.getSummary 等）先用它
    // 判断，未就绪时返占位并后台等待，不能在主线程 awaitLoadSync
    val isLoaded
        get() = ready

    // 正常返回时各 map 一定已初始化；加载失败（如读取包列表被拒）抛出原异常，
    // 调用方自行处理，缓存保持未加载，下次调用重试。启动时的 register() 失败过
    // 就在这里补注册，否则本进程再也收不到应用安装 / 卸载的通知
    fun awaitLoadSync() {
        if (!registerd.get()) register()
        ensureLoaded()
    }

    // written from background threads, cleared from the package-change receiver
    private val labelMap = ConcurrentHashMap<String, String>()
    fun loadLabel(packageName: String): String {
        var label = labelMap[packageName]
        if (label != null) return label
        val info = installedApps[packageName] ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

}