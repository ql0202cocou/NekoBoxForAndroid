@file:SuppressLint("SoonBlockedPrivateApi")

package io.nekohasekai.sagernet.ktx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.NGUtil
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import io.nekohasekai.sagernet.bg.ServiceRegistry

fun String?.blankAsNull(): String? = if (isNullOrBlank()) null else this

val Throwable.readableMessage
    get() = localizedMessage.takeIf { !it.isNullOrBlank() } ?: javaClass.simpleName

fun parsePort(str: String?, default: Int, min: Int = 1025): Int {
    val value = str?.toIntOrNull() ?: default
    return if (value < min || value > 65535) default else value
}

fun broadcastReceiver(callback: (Context, Intent) -> Unit): BroadcastReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = callback(context, intent)
    }

fun Context.listenForPackageChanges(onetime: Boolean = true, callback: () -> Unit) =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            callback()
            if (onetime) context.unregisterReceiver(this)
        }
    }.apply {
        registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
    }

// 未挂进树的 preference 没有 parent，直接视为无事可做
fun Preference.remove() = parent?.removePreference(this) ?: false

/**
 * A slightly more performant variant of parseNumericAddress.
 *
 * Bug in Android 9.0 and lower: https://issuetracker.google.com/issues/123456213
 */

private val parseNumericAddress by lazy {
    InetAddress::class.java.getDeclaredMethod("parseNumericAddress", String::class.java).apply {
        isAccessible = true
    }
}

fun String?.parseNumericAddress(): InetAddress? =
    Os.inet_pton(OsConstants.AF_INET, this) ?: Os.inet_pton(OsConstants.AF_INET6, this)?.let {
        if (Build.VERSION.SDK_INT >= 29) it else parseNumericAddress.invoke(
            null, this
        ) as InetAddress
    }

@JvmOverloads
fun DialogFragment.showAllowingStateLoss(fragmentManager: FragmentManager, tag: String? = null) {
    if (!fragmentManager.isStateSaved) show(fragmentManager, tag)
}

fun String.urlSafe(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

fun String.unUrlSafe(): String {
    return NGUtil.urlDecode(this)
}

fun RecyclerView.scrollTo(index: Int, force: Boolean = false) {
    if (force) post {
        scrollToPosition(index)
    }
    postDelayed({
        try {
            layoutManager?.startSmoothScroll(object : LinearSmoothScroller(context) {
                init {
                    targetPosition = index
                }

                override fun getVerticalSnapPreference(): Int {
                    return SNAP_TO_START
                }
            })
        } catch (ignored: IllegalArgumentException) {
        }
    }, 300L)
}

// 拖拽排序：把 from 处的项挪到 to，途经的项依次顺移一格并接过邻项的 userOrder，
// 被拖的项拿到 to 处原来的 userOrder。itemAt 取某位置的项（取不到的跳过），
// putAt 把项放进新位置；from 处取不到项时什么都不改，返回 false
inline fun <T : Any> moveUserOrder(
    from: Int,
    to: Int,
    userOrder: KMutableProperty1<T, Long>,
    itemAt: (Int) -> T?,
    putAt: (Int, T) -> Unit,
): Boolean {
    val first = itemAt(from) ?: return false
    var previousOrder = userOrder.get(first)
    val (step, range) = if (from < to) Pair(1, from until to) else Pair(
        -1, from downTo to + 1
    )
    for (i in range) {
        val next = itemAt(i + step) ?: continue
        val order = userOrder.get(next)
        userOrder.set(next, previousOrder)
        previousOrder = order
        putAt(i, next)
    }
    userOrder.set(first, previousOrder)
    putAt(to, first)
    return true
}

val app get() = SagerNet.application

val shortAnimTime by lazy {
    app.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
}

fun View.crossFadeFrom(other: View) {
    clearAnimation()
    other.clearAnimation()
    if (isVisible && other.isGone) return
    alpha = 0F
    visibility = View.VISIBLE
    animate().alpha(1F).duration = shortAnimTime
    other.animate().alpha(0F).setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            other.visibility = View.GONE
        }
    }).duration = shortAnimTime
}


fun Fragment.snackbar(textId: Int) = (requireActivity() as MainActivity).snackbar(textId)
fun Fragment.snackbar(text: CharSequence) = (requireActivity() as MainActivity).snackbar(text)

// 复制到剪贴板，返回给 snackbar 用的导出结果提示
@StringRes
fun exportToClipboard(text: String) =
    if (SagerNet.trySetPrimaryClip(text)) R.string.action_export_msg else R.string.action_export_err

fun ThemedActivity.startFilesForResult(
    launcher: ActivityResultLauncher<String>, input: String
) {
    try {
        return launcher.launch(input)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    snackbar(getString(R.string.file_manager_missing)).show()
}

fun Fragment.startFilesForResult(
    launcher: ActivityResultLauncher<String>, input: String
) {
    try {
        return launcher.launch(input)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    (requireActivity() as ThemedActivity).snackbar(getString(R.string.file_manager_missing)).show()
}

// Write an export to the document the user picked. Blank content means the state it
// was built from died with the process: refuse instead of truncating the picked file
// to 0 bytes and reporting success.
suspend fun Fragment.writeToDocument(uri: Uri, content: String) {
    // Callers dispatch this on appScope and the fragment can be detached by
    // now. The SAF grant belongs to the package, so write through the app
    // context regardless, and only report if there is still a UI to report to.
    val message = if (content.isBlank()) {
        app.getString(R.string.action_export_err)
    } else try {
        val stream = app.contentResolver.openOutputStream(uri)
            ?: throw FileNotFoundException(uri.toString())
        stream.use { it.bufferedWriter().use { writer -> writer.write(content) } }
        app.getString(R.string.action_export_msg)
    } catch (e: Exception) {
        Logs.w(e)
        e.readableMessage
    }
    onMainDispatcher { if (isAdded) snackbar(message).show() }
}

// 选取文档的显示名。GetContent("*/*") 允许任意文档提供方，坏的提供方可能返回
// 空游标、缺 DISPLAY_NAME 列或查询直接抛错，这时退回 Uri 最后一段路径；
// DISPLAY_NAME 也可能带路径分隔符，只保留最后一个 '/' 之后的部分。
// Uri 连路径段都没有时兜底为空串
fun ContentResolver.displayName(uri: Uri): String {
    val name = try {
        query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            } else null
        }
    } catch (e: Exception) {
        Logs.w(e)
        null
    }
    return (name?.takeIf { it.isNotBlank() } ?: uri.pathSegments.lastOrNull()
        ?.substringAfterLast('/')
        ?.substringAfter(':')
        .orEmpty())
        .substringAfterLast('/')
}

// 经 FileProvider（.cache）把文件交给系统分享面板。provider 只暴露
// cacheDir/share/（cache_paths.xml），不在其中的文件先挪进去；分享负载即用
// 即弃，挪完顺手清掉上一次分享的残留（备份 JSON 含全部节点凭证）
// FileProvider 只暴露这个目录（见 cache_paths.xml）
val Context.shareDir get() = File(cacheDir, "share")

fun Context.shareFile(file: File, mimeType: String) {
    val shareDir = shareDir.apply { mkdirs() }
    val shared = if (file.parentFile == shareDir) {
        file
    } else {
        val target = File(shareDir, file.name)
        when {
            file.renameTo(target) -> target
            runCatching { file.copyTo(target, overwrite = true); file.delete() }.isSuccess -> target
            else -> {
                Logs.w("shareFile: cannot move ${file.name} into share dir")
                Toast.makeText(this, R.string.action_export_err, Toast.LENGTH_SHORT).show()
                return
            }
        }
    }
    shareDir.listFiles()?.forEach { if (it != shared) it.delete() }
    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mimeType)
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(
                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                        this, BuildConfig.APPLICATION_ID + ".cache", shared
                    )
                ), getString(androidx.appcompat.R.string.abc_shareactionprovider_share_with)
        )
    )
}

fun Fragment.needReload() {
    if (ServiceRegistry.state.started) {
        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
            SagerNet.reloadService()
        }.show()
    }
}

fun Fragment.needRestart() {
    snackbar(R.string.need_restart).setAction(R.string.apply) {
        triggerFullRestart(requireContext())
    }.show()
}

fun triggerFullRestart(ctx: Context) {
    runOnDefaultDispatcher {
        SagerNet.stopService()
        delay(500)
        SagerConnection.restartingApp = true
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_RESTART_BG)
        connection.connect(ctx, RestartCallback {
            ProcessPhoenix.triggerRebirth(ctx, Intent(ctx, MainActivity::class.java))
        })
    }
}

private class RestartCallback(val callback: () -> Unit) : SagerConnection.Callback {
    override fun stateChanged(
        state: BaseService.State,
        profileName: String?,
        msg: String?
    ) {
    }

    override fun onServiceConnected(service: ISagerNetService) {
        callback()
    }
}

fun Context.getColour(@ColorRes colorRes: Int): Int {
    return ContextCompat.getColor(this, colorRes)
}

fun Context.getColorAttr(@AttrRes resId: Int): Int {
    return ContextCompat.getColor(this, TypedValue().also {
        theme.resolveAttribute(resId, it, true)
    }.resourceId)
}

const val isOss = BuildConfig.FLAVOR == "oss"
const val isPreview = BuildConfig.FLAVOR == "preview"

fun <T> Continuation<T>.tryResume(value: T) {
    try {
        resumeWith(Result.success(value))
    } catch (ignored: IllegalStateException) {
    }
}

fun <T> Continuation<T>.tryResumeWithException(exception: Throwable) {
    try {
        resumeWith(Result.failure(exception))
    } catch (ignored: IllegalStateException) {
    }
}

operator fun AtomicInteger.getValue(thisRef: Any?, property: KProperty<*>): Int = get()
operator fun AtomicInteger.setValue(thisRef: Any?, property: KProperty<*>, value: Int) = set(value)
