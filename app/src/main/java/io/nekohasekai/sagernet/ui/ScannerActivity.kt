package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.BaseCameraScan
import com.king.camera.scan.CameraScan
import com.king.camera.scan.util.LogUtils
import com.king.camera.scan.util.PermissionUtils
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutScannerBinding
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import io.nekohasekai.sagernet.database.GroupManager


class ScannerActivity : ThemedActivity(),
    CameraScan.OnScanResultCallback<Result> {

    lateinit var binding: LayoutScannerBinding
    lateinit var cameraScan: CameraScan<Result>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        // 二维码库
        initCameraScan()
        startCamera()
        binding.ivFlashlight.setOnClickListener { toggleTorchState() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.scanner_menu, menu)
        return true
    }

    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (!importStarted.compareAndSet(false, true)) return@registerForActivityResult
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                importScans(uris.map { uri ->
                    {
                        val bitmap = decodeQrBitmap(uri)
                        try {
                            CodeUtils.parseCodeResult(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                })
            }
            completeImport(outcome)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_import_file) {
            startFilesForResult(importCodeFile, "image/*")
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private val importStarted = AtomicBoolean(false)

    private data class ImportOutcome(
        var imported: Int = 0,
        val subscriptions: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
    )

    /**
     * 接收扫码结果回调
     * @param result 扫码结果
     */
    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        if (!importStarted.compareAndSet(false, true)) return
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                importScans(listOf { result.result })
            }
            completeImport(outcome)
        }
    }

    private suspend fun importScans(scans: List<() -> Result?>): ImportOutcome {
        val outcome = ImportOutcome()
        for (scan in scans) {
            try {
                outcome.imported += importScan(scan())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionFoundException) {
                outcome.subscriptions.add(e.link)
            } catch (e: Exception) {
                Logs.w(e)
                outcome.errors.add(e.readableMessage)
            }
        }
        return outcome
    }

    private suspend fun importScan(result: Result?): Int {
        val text = result?.text ?: error("QR code not found")
        val results = RawUpdater.parseRaw(text)
            ?.takeIf { it.isNotEmpty() }
            ?: error(app.getString(R.string.action_import_err))
        val currentGroupId = GroupManager.selectedGroupForImport()
        if (DataStore.selectedGroup != currentGroupId) {
            DataStore.selectedGroup = currentGroupId
        }
        for (profile in results) {
            ProfileManager.createProfile(currentGroupId, profile)
        }
        return results.size
    }

    private fun completeImport(outcome: ImportOutcome) {
        if (outcome.imported > 0) {
            val message = buildString {
                append(getString(R.string.action_import_msg))
                append("\n")
                append(outcome.imported)
                append(" profile(s)")
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
        if (outcome.errors.isNotEmpty()) {
            val message = buildString {
                append(getString(R.string.action_import_err))
                append("\n")
                append(outcome.errors.distinct().take(3).joinToString("\n"))
                if (outcome.errors.size > 3) append("\n…")
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
        for (link in outcome.subscriptions.distinct()) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = link.toUri()
            })
        }
        finish()
    }

    private fun decodeQrBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) {
                    decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val largest = max(width, height)
                if (largest > MAX_QR_BITMAP_DIMENSION) {
                    val scale = MAX_QR_BITMAP_DIMENSION.toFloat() / largest
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = contentResolver.openInputStream(uri) ?: error("Cannot open image")
            boundsStream.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_QR_BITMAP_DIMENSION) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("Cannot decode image")
        }
    }

    /**
     * 初始化CameraScan
     */
    fun initCameraScan() {
        cameraScan = BaseCameraScan(this, binding.previewView)
        cameraScan.setAnalyzer(QRCodeAnalyzer())
        cameraScan.setOnScanResultCallback(this)
    }

    /**
     * 启动相机预览
     */
    fun startCamera() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            LogUtils.d("checkPermissionResult != PERMISSION_GRANTED")
            PermissionUtils.requestPermission(
                this, Manifest.permission.CAMERA, CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * 释放相机
     */
    private fun releaseCamera() {
        cameraScan.release()
    }

    /**
     * 切换闪光灯状态（开启/关闭）
     */
    protected fun toggleTorchState() {
        val isTorch = cameraScan.isTorchEnabled
        cameraScan.enableTorch(!isTorch)
        binding.ivFlashlight.isSelected = !isTorch
    }

    val CAMERA_PERMISSION_REQUEST_CODE = 0X86

    companion object {
        private const val MAX_QR_BITMAP_DIMENSION = 2048
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    /**
     * 请求Camera权限回调结果
     * @param permissions
     * @param grantResults
     */
    fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA, permissions, grantResults
            )
        ) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        releaseCamera()
        super.onDestroy()
    }
}
