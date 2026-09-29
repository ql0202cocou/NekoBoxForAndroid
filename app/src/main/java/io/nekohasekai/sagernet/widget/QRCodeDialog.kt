package io.nekohasekai.sagernet.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

class QRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_URL = "io.nekohasekai.sagernet.QRCodeDialog.KEY_URL"
        private const val KEY_NAME = "io.nekohasekai.sagernet.QRCodeDialog.KEY_NAME"
        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    constructor(url: String, displayName: String) : this() {
        arguments = Bundle().apply {
            putString(KEY_URL, url)
            putString(KEY_NAME, displayName)
        }
    }

    /**
     * Based on:
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
     * https://android.googlesource.com/platform/
    packages/apps/Settings/+/8a9ccfd/src/com/android/settings/wifi/dpp/WifiDppQrCodeGeneratorFragment.java#153
     */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // arguments 只在恢复状态损坏时缺失；没 URL 画不出码，直接关掉
        val url = arguments?.getString(KEY_URL)
        if (url == null) {
            Logs.w("QRCodeDialog: no url in arguments")
            dismiss()
            return null
        }
        val displayName = arguments?.getString(KEY_NAME).orEmpty()

        return try {
            // 取屏幕尺寸
            var pixelMin = 0

            try {
                val displayMetrics: DisplayMetrics = requireContext().resources.displayMetrics
                val height: Int = displayMetrics.heightPixels
                val width: Int = displayMetrics.widthPixels
                pixelMin = if (height > width) width else height
                pixelMin = (pixelMin * 0.8).roundToInt()
            } catch (e: Exception) {
            }

            val size = if (pixelMin > 0) pixelMin else resources.getDimensionPixelSize(R.dimen.qrcode_size)

            val hints = mutableMapOf<EncodeHintType, Any>()
            if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
            // 宽高传 0 得到每个模块 1 像素（含留白）的最小矩阵，放大交给 ImageView
            val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints)
            LinearLayout(context).apply {
                // 布局
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                // 二维码图片
                addView(ImageView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(size, size)
                    // 按屏幕尺寸编码要在主线程上填几十万像素；这里只填模块数的平方，
                    // 由 ImageView 缩放到 size，关掉滤波让模块边缘保持锐利
                    val dim = qrBits.width
                    val pixels = IntArray(dim * dim) { i ->
                        if (qrBits.get(i % dim, i / dim)) Color.BLACK else Color.WHITE
                    }
                    val bitmap = Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.RGB_565)
                    setImageDrawable(bitmap.toDrawable(resources).apply { isFilterBitmap = false })
                })

                // 名称文字
                addView(TextView(context).apply {
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = displayName
                })
            }
        } catch (e: WriterException) {
            Logs.w(e)
            (activity as? MainActivity)?.snackbar(e.readableMessage)?.show()
            dismiss()
            null
        }
    }
}
