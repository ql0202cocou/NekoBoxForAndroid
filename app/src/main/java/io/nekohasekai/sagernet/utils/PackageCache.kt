package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    lateinit var uidMap: Map<Int, Set<String>>
    // A one-shot latch, not a lock: awaitLoadSync() only waits on it, and
    // complete() is idempotent, so a failed attempt followed by a retry is safe.
    private val loaded = CompletableDeferred<Unit>()
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        try {
            reload()
            app.listenForPackageChanges(false) {
                reload()
                labelMap.clear()
            }
        } catch (e: Throwable) {
            // allow a later awaitLoadSync() to retry instead of leaving
            // registerd=true with the maps uninitialized
            registerd.set(false)
            Logs.w(e)
        } finally {
            // awaitLoadSync() blocks on this latch (including the gomobile callback
            // path), so release it even when reload() throws — a stuck latch hangs
            // the VPN mid-start with no error.
            loaded.complete(Unit)
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
        // 已卸载（保留数据）包没有 FLAG_INSTALLED，筛掉后与原查询等价，省一次全量 IPC
        val installed = rawPackageInfo.mapNotNull { it.applicationInfo }
            .filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
        installedApps = installed.associateBy { it.packageName }
        // swap the whole map like the fields above: packageNameByUid reads
        // this from a gomobile callback thread with no synchronization, so
        // mutating a shared HashMap in place can corrupt a concurrent read.
        // uidMap is assigned before packageMap: awaitLoadSync early-returns on
        // ::packageMap.isInitialized, so packageMap must be the last write.
        val newUidMap = HashMap<Int, HashSet<String>>()
        for (info in installed) {
            val uid = info.uid
            newUidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
        uidMap = newUidMap
        packageMap = installed.associate { it.packageName to it.uid }
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized && ::uidMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking { loaded.await() }
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