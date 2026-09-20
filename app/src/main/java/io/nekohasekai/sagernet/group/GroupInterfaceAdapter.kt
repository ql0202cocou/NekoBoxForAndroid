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

    override suspend fun confirm(message: String): Boolean {
        return suspendCancellableCoroutine { c ->
            // CancellableContinuation hides the ktx tryResume extension behind
            // its internal member; view it as a plain Continuation instead.
            val cont = c as Continuation<Boolean>
            runOnMainDispatcher {
                if (context.isFinishing || context.isDestroyed) {
                    cont.tryResume(false)
                    return@runOnMainDispatcher
                }
                // observer 必须在每条恢复路径上摘掉，否则每弹一次窗就泄漏一个。
                // 按钮点击也会触发 dismiss，正常路径统一走 OnDismissListener；
                // activity 销毁时 onDismiss 不会回调，由 onDestroy 自注销兜底
                val observer = object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        owner.lifecycle.removeObserver(this)
                        cont.tryResume(false)
                    }
                }
                context.lifecycle.addObserver(observer)
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(R.string.yes) { _, _ -> cont.tryResume(true) }
                    .setNegativeButton(R.string.no) { _, _ -> cont.tryResume(false) }
                    // Buttons dismiss the dialog too; tryResume ignores the second call.
                    // A dismissed dialog counts as refusal.
                    .setOnDismissListener { _ ->
                        context.lifecycle.removeObserver(observer)
                        cont.tryResume(false)
                    }
                    .show()
                // onDismiss is NOT fired when the activity is destroyed (the dialog
                // just leaks its window), so resume on ON_DESTROY as well, or the
                // coroutine would hang forever with the group's updating lock held.
            }
        }
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

        onMainDispatcher {
            if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
            context.snackbar(
                context.getString(R.string.group_updated, group.name, changed)
            ).show()
            delay(1000L)

            // Showing a dialog on a destroyed activity throws BadTokenException;
            // don't let it bubble up and misreport the successful update as failed.
            if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
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

    override suspend fun alert(message: String) {
        return suspendCancellableCoroutine { c ->
            val cont = c as Continuation<Unit>
            runOnMainDispatcher {
                if (context.isFinishing || context.isDestroyed) {
                    cont.tryResume(Unit)
                    return@runOnMainDispatcher
                }
                // 同 confirm()：正常路径由 dismiss 摘掉 observer，onDestroy 自注销兜底
                val observer = object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        owner.lifecycle.removeObserver(this)
                        cont.tryResume(Unit)
                    }
                }
                context.lifecycle.addObserver(observer)
                MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> cont.tryResume(Unit) }
                    .setOnDismissListener { _ ->
                        context.lifecycle.removeObserver(observer)
                        cont.tryResume(Unit)
                    }
                    .show()
            }
        }
    }

}
