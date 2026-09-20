package io.nekohasekai.sagernet.group

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    // 弹窗的生命周期样板：activity 已死则立刻按 dismissValue 恢复；否则挂
    // observer 后弹窗，按钮点击也会触发 dismiss，正常路径统一走
    // OnDismissListener 摘 observer 并恢复（tryResume 忽略第二次）。activity
    // 销毁时 onDismiss 不回调（窗口直接泄漏），不靠 observer 兜底的话协程会
    // 攥着分组的 updating 锁永远挂住；observer 必须在每条恢复路径上摘掉，
    // 否则每弹一次窗就泄漏一个
    private suspend fun <T> showDialog(
        dismissValue: T,
        configure: MaterialAlertDialogBuilder.(Continuation<T>) -> MaterialAlertDialogBuilder,
    ): T = suspendCancellableCoroutine { c ->
        // CancellableContinuation hides the ktx tryResume extension behind
        // its internal member; view it as a plain Continuation instead.
        @Suppress("UNCHECKED_CAST") val cont = c as Continuation<T>
        runOnMainDispatcher {
            if (context.isFinishing || context.isDestroyed) {
                cont.tryResume(dismissValue)
                return@runOnMainDispatcher
            }
            val observer = object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    owner.lifecycle.removeObserver(this)
                    cont.tryResume(dismissValue)
                }
            }
            context.lifecycle.addObserver(observer)
            MaterialAlertDialogBuilder(context).configure(cont)
                .setOnDismissListener { _ ->
                    context.lifecycle.removeObserver(observer)
                    cont.tryResume(dismissValue)
                }
                .show()
        }
    }

    // A dismissed dialog counts as refusal.
    override suspend fun confirm(message: String): Boolean = showDialog(false) { cont ->
        setTitle(R.string.confirm)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> cont.tryResume(true) }
            .setNegativeButton(R.string.no) { _, _ -> cont.tryResume(false) }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        if (changed == 0 && duplicate.isEmpty()) {
            if (byUser) onMainDispatcher {
                if (!context.isFinishing && !context.isDestroyed) {
                    context.snackbar(
                        context.getString(R.string.group_no_difference, group.displayName())
                    ).show()
                }
            }
            return
        }

        var status = ""
        if (added.isNotEmpty()) {
            status += context.getString(
                R.string.group_added, added.joinToString("\n", postfix = "\n\n")
            )
        }
        if (updated.isNotEmpty()) {
            status += context.getString(R.string.group_changed,
                updated.map { it }.joinToString("\n", postfix = "\n\n") {
                    if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                })
        }
        if (deleted.isNotEmpty()) {
            status += context.getString(
                R.string.group_deleted, deleted.joinToString("\n", postfix = "\n\n")
            )
        }
        if (duplicate.isNotEmpty()) {
            status += context.getString(
                R.string.group_duplicate, duplicate.joinToString("\n", postfix = "\n\n")
            )
        }

        // 用 launch 而不是挂起调用方：snackbar 后的 1 秒延迟和弹窗不该占用
        // 订阅更新的分组锁（executeUpdate 的 finally 要等本回调返回才释放）
        runOnMainDispatcher {
            if (context.isFinishing || context.isDestroyed) return@runOnMainDispatcher
            context.snackbar(
                context.getString(R.string.group_updated, group.name, changed)
            ).show()
            delay(1000L)

            // Showing a dialog on a destroyed activity throws BadTokenException;
            // don't let it bubble up and misreport the successful update as failed.
            if (context.isFinishing || context.isDestroyed) return@runOnMainDispatcher
            runCatching {
                MaterialAlertDialogBuilder(context).setTitle(
                    context.getString(R.string.group_diff, group.displayName())
                ).setMessage(status.trim()).setPositiveButton(android.R.string.ok, null).show()
            }.onFailure { Logs.w(it) }
        }
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            if (!context.isFinishing && !context.isDestroyed) {
                context.snackbar(message).show()
            }
        }
    }

    override suspend fun alert(message: String) = showDialog(Unit) { cont ->
        setTitle(R.string.ooc_warning)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> cont.tryResume(Unit) }
    }

}
