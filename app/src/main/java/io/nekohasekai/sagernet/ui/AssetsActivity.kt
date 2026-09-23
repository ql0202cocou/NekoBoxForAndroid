package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Process
import android.text.format.DateFormat
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.padForSystemBars
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    // same file libcore locks around its extraction (assets_lock.go)
    private val assetsLock get() = File(app.filesDir, "assets.lock")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        binding.recyclerView.padForSystemBars()
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = arrayOf("geoip.db", "geosite.db")

    companion object {
        private val CA_EXTENSIONS = listOf(".pem", ".crt", ".cer")
    }

    // both processes read ca.pem in SagerNet.onCreate only
    private fun needRestart() {
        if (isFinishing || isDestroyed) return
        snackbar(R.string.need_restart).setAction(R.string.apply) { triggerFullRestart(this) }.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            // displayName 只留最后一段路径，File(assetsDir, fileName) 逃不出目录
            val fileName = contentResolver.displayName(file)

            val isCertificate = CA_EXTENSIONS.any { fileName.lowercase().endsWith(it) }
            if (fileName.isBlank() || fileName == ".." ||
                !(fileName.endsWith(".db") || isCertificate)
            ) {
                alert(getString(R.string.route_not_asset, fileName)).show()
                return@registerForActivityResult
            }
            val assetsDir = app.assetsDir

            runOnDefaultDispatcher {
                // a custom CA always lands in ca.pem, whatever the picked file was called
                val outFile = File(
                    assetsDir, if (isCertificate) SagerNet.CA_FILE_NAME else fileName
                ).apply {
                    parentFile?.mkdirs()
                }
                // copy aside and rename: a failed copy must not leave a truncated
                // db where the previous, working one was. The pid keeps the name
                // apart from libcore's own extraction temp file in :bg.
                val tmpFile = File(outFile.parentFile, "${outFile.name}.tmp.${Process.myPid()}")
                // appScope: an escaping IOException (unreadable document, revoked
                // permission, full disk) would take the whole app down
                try {
                    if (isCertificate) {
                        // parse, then re-encode as PEM: Go's AppendCertsFromPEM reads PEM
                        // blocks only, while the picker also yields DER (.cer) files
                        val certificates = contentResolver.openInputStream(file)?.use {
                            CertificateFactory.getInstance("X.509").generateCertificates(it)
                        } ?: error("cannot open $fileName")
                        if (certificates.isEmpty()) {
                            error(getString(R.string.route_not_asset, fileName))
                        }
                        tmpFile.writeText(certificates.joinToString("") { it.toPem() })
                    } else {
                        contentResolver.openInputStream(file)?.use(tmpFile.outputStream())
                            ?: error("cannot open $fileName")
                    }
                    // publish under the lock libcore holds while extracting the same file
                    lockFile(assetsLock) {
                        if (!tmpFile.renameTo(outFile)) error("cannot replace " + outFile.name)
                        File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt")
                            .writeText("Custom")
                    }

                    adapter.reloadAssets()
                    if (isCertificate) onMainDispatcher { needRestart() }
                } catch (e: Exception) {
                    Logs.w(e)
                    // tryToShow, not show: this runs on appScope and the
                    // activity may be gone by now (BadTokenException)
                    onMainDispatcher { alert(e.readableMessage).tryToShow() }
                } finally {
                    // no-op after a successful rename; drops a half-copied db otherwise
                    tmpFile.delete()
                }
            }

        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val assetsDir = app.assetsDir
            val files = assetsDir.listFiles()?.filter {
                it.isFile && (it.name.endsWith(".db") || it.name == SagerNet.CA_FILE_NAME) &&
                        it.name !in assetNames
            }

            layout.refreshLayout.post {
                // mutate the list on the main thread: this runs on a
                // background dispatcher while the main thread reads it
                assets.clear()
                assets.add(File(assetsDir, "geoip.db"))
                assets.add(File(assetsDir, "geosite.db"))
                if (files != null) assets.addAll(files)
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val files = actions.map { it.second }
            runOnDefaultDispatcher {
                files.forEach { it.deleteRecursively() }
                // the root store keeps a removed ca.pem until the next process start
                if (files.any { it.name == SagerNet.CA_FILE_NAME }) {
                    onMainDispatcher { needRestart() }
                }
            }
        }

    }

    val updating = AtomicInteger()
    private val updatingAssets = Collections.synchronizedSet(mutableSetOf<String>())

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage).show()
                        "<unknown>"
                    }
                } else {
                    "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

            val isUpdating = file.absolutePath in updatingAssets
            binding.subscriptionUpdateProgress.isInvisible = !isUpdating
            binding.rulesUpdate.isInvisible = file.name !in assetNames || isUpdating
            binding.rulesUpdate.setOnClickListener {
                val targetFile = file
                if (!updatingAssets.add(targetFile.absolutePath)) return@setOnClickListener
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                lifecycleScope.launch(Dispatchers.Default) {
                    runCatching {
                        updateAsset(targetFile, versionFile, localVersion)
                    }.onFailure {
                        onMainDispatcher {
                            if (!isFinishing && !isDestroyed) {
                                alert(it.readableMessage).tryToShow()
                            }
                        }
                    }

                    onMainDispatcher {
                        updatingAssets.remove(targetFile.absolutePath)
                        adapter.reloadAssets()
                        // This holder may now represent another file.
                        if (this@AssetHolder.file == targetFile) {
                            binding.rulesUpdate.isInvisible = targetFile.name !in assetNames
                            binding.subscriptionUpdateProgress.isInvisible = true
                        }
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

        }

    }

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules"
        ),
        RuleAssetsProvider(
            "L11R/antizapret-sing-box-geo"
        ),
    )

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        val fileName = file.name

        // a settings backup from another build can carry an out-of-range index
        val ruleProvider = rulesProviders.getOrNull(DataStore.rulesProvider)
            ?: rulesProviders.first()
        val repo = ruleProvider.repoByFileName[fileName]
            ?: error("No rules repository for $fileName")

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedPort)
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val browserDownloadUrl: String?
            val tagName: String
            try {
                val release = JSONObject(Util.getStringBox(response.contentString))
                tagName = release.optString("tag_name")

                if (tagName == localVersion) {
                    onMainDispatcher {
                        snackbar(R.string.route_asset_no_update).show()
                    }
                    return
                }

                val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
                val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                    ?: error("File $fileName not found in release ${release["url"]}")
                browserDownloadUrl = assetToDownload.getStr("browser_download_url")
            } finally {
                response.close()
            }

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            // download aside and rename: a truncated download must not leave a
            // corrupted db behind (box may mmap it). The release assets are plain
            // .db files, so there is nothing to decompress here. The pid keeps the
            // name apart from libcore's own extraction temp file in :bg.
            val cacheFile = File(file.parentFile, "${file.name}.tmp.${Process.myPid()}")
            cacheFile.parentFile?.mkdirs()

            try {
                response.writeTo(cacheFile.canonicalPath)
                // only the publish runs under the lock: holding it through the download
                // would stall a :bg start waiting to extract the same file
                lockFile(assetsLock) {
                    if (!cacheFile.renameTo(file)) {
                        throw IOException("cannot replace ${file.absolutePath}")
                    }
                    versionFile.writeText(tagName)
                }
            } finally {
                response.close()
                // no-op after a successful rename; drops a truncated download otherwise
                cacheFile.delete()
            }

            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

    private fun Certificate.toPem(): String =
        "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            )
        )
    }
}
