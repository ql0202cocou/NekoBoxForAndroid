package io.nekohasekai.sagernet.widget

import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

// Preference 自己没有生命周期。它们都挂在 Activity 承载的偏好页里（编辑器、设置），页面与 Activity 同生共死，
// 所以取 context 链上的 LifecycleOwner（Activity）的作用域：页面销毁时在途的刷新随之取消，不再回调已丢弃的偏好项。
// 取不到（context 不是 Activity 派生）时返回 null，调用方不发起刷新，下次绑定自然重试。
// 摘要会在 RecyclerView 绑定期间读取。保留生命周期的 Job，但改用排队的 Main：即便后台在 withContext
// 挂起前就返回，launch 也不会在 getSummary 的调用栈里 notifyChanged，避免布局期间重入刷新。
internal fun Preference.pageScopeOrNull(): CoroutineScope? =
    generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycleScope?.plus(Dispatchers.Main)
