package io.nekohasekai.sagernet.widget

import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.CoroutineScope

// Preference 自己没有生命周期。它们都挂在 Activity 承载的偏好页里（编辑器、设置），页面与 Activity 同生共死，
// 所以取 context 链上的 LifecycleOwner（Activity）的作用域：页面销毁时在途的刷新随之取消，不再回调已丢弃的偏好项。
// 取不到（context 不是 Activity 派生）时返回 null，调用方不发起刷新，下次绑定自然重试。
internal fun Preference.pageScopeOrNull(): CoroutineScope? =
    generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycleScope
