package moe.matsuri.nb4a.plugin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import java.security.MessageDigest

typealias PluginConflictReporter = (pluginId: String, packages: List<String>) -> Unit

object Plugins {
    const val AUTHORITIES_PREFIX_SEKAI_EXE = "io.nekohasekai.sagernet.plugin."
    const val AUTHORITIES_PREFIX_NEKO_EXE = "moe.matsuri.exe."

    const val ACTION_NATIVE_PLUGIN = "io.nekohasekai.sagernet.plugin.ACTION_NATIVE_PLUGIN"

    const val METADATA_KEY_ID = "io.nekohasekai.sagernet.plugin.id"
    const val METADATA_KEY_EXECUTABLE_PATH = "io.nekohasekai.sagernet.plugin.executable_path"

    fun isExe(pkg: PackageInfo): Boolean {
        return pkg.providers?.any {
            val auth = it.authority ?: return@any false
            auth.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)
                    || auth.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)
        } == true
    }

    fun preferExePrefix(): String {
        return AUTHORITIES_PREFIX_NEKO_EXE
    }

    fun displayExeProvider(pkgName: String): String {
        return if (pkgName.startsWith(AUTHORITIES_PREFIX_SEKAI_EXE)) {
            "SagerNet"
        } else if (pkgName.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
            "Matsuri"
        } else {
            "Unknown"
        }
    }

    fun getPlugin(pluginId: String, onConflict: PluginConflictReporter): ProviderInfo? {
        if (pluginId.isBlank()) return null
        getPluginExternal(pluginId, onConflict)?.let { return it }
        // internal so
        return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
    }

    fun getPluginExternal(pluginId: String, onConflict: PluginConflictReporter = { _, _ -> }): ProviderInfo? {
        if (pluginId.isBlank()) return null
        val candidates = getExtPluginOld(pluginId).ifEmpty { getExtPluginNew(pluginId) }
        return selectProvider(pluginId, candidates, { isTrustedPlugin(pluginId, it) }, onConflict)
    }

    internal fun selectProvider(
        pluginId: String,
        candidates: List<ProviderInfo>,
        isTrusted: (ProviderInfo) -> Boolean,
        onConflict: PluginConflictReporter,
    ): ProviderInfo? {
        // 同一组件可同时响应两个旧入口。去重后只保留签名可信的提供方。
        val trusted = candidates.distinctBy { it.packageName to it.name }.filter(isTrusted)
        if (trusted.size <= 1) return trusted.firstOrNull()
        trusted.singleOrNull { it.authority.startsWith(preferExePrefix()) }?.let { return it }
        onConflict(pluginId, trusted.map { it.packageName }.distinct())
        return trusted.first()
    }

    private fun getExtPluginNew(pluginId: String): List<ProviderInfo> {
        PackageCache.awaitLoadSync()
        return PackageCache.installedPluginPackages.values.flatMap { pkg ->
            pkg.providers?.filter { it.loadString(METADATA_KEY_ID) == pluginId } ?: emptyList()
        }
    }

    private fun buildUri(id: String, auth: String) = Uri.Builder()
        .scheme("plugin")
        .authority(auth)
        .path("/$id")
        .build()

    private fun getExtPluginOld(pluginId: String): List<ProviderInfo> {
        val flags = PackageManager.GET_META_DATA or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE
        val list1 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "io.nekohasekai.sagernet")), flags
        )
        val list2 = SagerNet.application.packageManager.queryIntentContentProviders(
            Intent(ACTION_NATIVE_PLUGIN, buildUri(pluginId, "moe.matsuri.lite")), flags
        )
        return (list1 + list2).mapNotNull {
            it.providerInfo
        }.filter { it.exported }
    }

    /**
     * SHA-256 hashes of trusted plugin signing certificates, besides this app's own
     * signature. The official MatsuriDayo/plugins APKs (hysteria, naive, mieru, juicity,
     * tuic, sing-box, xray, brook) are all signed with one certificate, extracted with
     * `apksigner verify --print-certs` from the 2026-08-29 release assets.
     */
    private val TRUSTED_PLUGIN_SIGNATURES = setOf(
        // MatsuriDayo/plugins official signing certificate
        "35762758ce86a6ec297d9ccac689469bc43b9fed8ae1b27f100a86bbac00a055",
    )

    private val trustedSignatures by lazy {
        TRUSTED_PLUGIN_SIGNATURES + packageSignatureSha256s(SagerNet.application.packageName)
    }

    // SagerNet 的 0.10.4 和 0.10.6 官方发行包使用同一证书，只对指定插件和包名核对当前签名。
    internal fun matchesOfficialTrojanGo(pluginId: String, provider: ProviderInfo, currentSigners: () -> Set<String>): Boolean =
        pluginId == "trojan-go-plugin" && provider.packageName == "io.nekohasekai.sagernet.plugin.trojan_go" &&
            provider.loadString(METADATA_KEY_ID) == pluginId &&
            currentSigners() == setOf("32250a4b5f3a6733df57a3b9ec16c38d2c7fc5f2f693a9636f8f7b3be3549641")

    private fun isTrustedPlugin(pluginId: String, provider: ProviderInfo): Boolean {
        val packageName = provider.packageName
        val signatures = packageSignatureSha256s(packageName)
        val trusted = signatures.any { it in trustedSignatures } || matchesOfficialTrojanGo(pluginId, provider) {
            packageSignatureSha256s(packageName, currentOnly = true)
        }
        if (!trusted) {
            Logs.w("Rejecting untrusted plugin package $packageName, signatures: $signatures")
        }
        return trusted
    }

    private fun packageSignatureSha256s(packageName: String, currentOnly: Boolean = false): Set<String> {
        val pm = SagerNet.application.packageManager
        val signatures: Array<Signature>? = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val signingInfo = pm.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo ?: return emptySet()
                if (currentOnly || signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    // oldest first; covers key rotation
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Logs.w("Plugin package $packageName not found", e)
            return emptySet()
        }
        return signatures?.mapTo(HashSet()) { it.sha256() } ?: emptySet()
    }

    private fun Signature.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
