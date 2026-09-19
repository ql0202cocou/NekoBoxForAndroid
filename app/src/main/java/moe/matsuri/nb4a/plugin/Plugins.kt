package moe.matsuri.nb4a.plugin

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.widget.Toast
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import java.security.MessageDigest

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

    fun isUsingMatsuriExe(pluginId: String): Boolean {
        getPlugin(pluginId)?.apply {
            if (authority.startsWith(AUTHORITIES_PREFIX_NEKO_EXE)) {
                return true
            }
        }
        return false;
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

    fun getPlugin(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null
        getPluginExternal(pluginId)?.let { return it }
        // internal so
        return ProviderInfo().apply { authority = AUTHORITIES_PREFIX_NEKO_EXE }
    }

    fun getPluginExternal(pluginId: String): ProviderInfo? {
        if (pluginId.isBlank()) return null

        // try queryIntentContentProviders
        var providers = getExtPluginOld(pluginId)

        // try PackageCache
        if (providers.isEmpty()) providers = getExtPluginNew(pluginId)

        // not found
        if (providers.isEmpty()) return null

        // Never execute binaries from untrusted packages (see isTrustedPlugin).
        providers = providers.filter { isTrustedPlugin(it.packageName) }
        if (providers.isEmpty()) return null

        if (providers.size > 1) {
            val prefer = providers.filter {
                it.authority.startsWith(preferExePrefix())
            }
            if (prefer.size == 1) providers = prefer
        }

        if (providers.size > 1) {
            val message =
                "Conflicting plugins found from: ${providers.joinToString { it.packageName }}"
            // may run on a Looper-less :bg thread; a Toast must be posted from the main thread
            runOnMainDispatcher {
                Toast.makeText(SagerNet.application, message, Toast.LENGTH_LONG).show()
            }
        }

        return providers[0]
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

    fun isTrustedPlugin(packageName: String): Boolean {
        val signatures = packageSignatureSha256s(packageName)
        val trusted = signatures.any { it in trustedSignatures }
        if (!trusted) {
            Logs.w("Rejecting untrusted plugin package $packageName, signatures: $signatures")
        }
        return trusted
    }

    private fun packageSignatureSha256s(packageName: String): Set<String> {
        val pm = SagerNet.application.packageManager
        val signatures: Array<Signature>? = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val signingInfo = pm.getPackageInfo(
                    packageName, PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo ?: return emptySet()
                if (signingInfo.hasMultipleSigners()) {
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
