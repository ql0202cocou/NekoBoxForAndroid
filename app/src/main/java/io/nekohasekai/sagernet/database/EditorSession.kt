package io.nekohasekai.sagernet.database

import android.os.SystemClock
import io.nekohasekai.sagernet.Key

// 共享的内存缓存 profileCacheStore 的归属：同一时刻最多只有一个顶层编辑器
// （配置 / 路由 / 分组）在用它；子编辑器（ConfigEditActivity、AppListActivity、
// ProfileSelectActivity）按设计共用父编辑器的键，从不认领编辑器会话。
// 令牌（token）把「缓存被悄悄接管」变成明确的失败，
// 而不是存进错误的实体。

// 每个编辑器把自己的令牌存进 Bundle 时用的键。
const val STATE_EDITOR_SESSION = "editorSessionState"

enum class EditorSessionState { OWNER, CACHE_LOST, TAKEN_OVER }

// 首次创建：丢掉上一个编辑器留下的所有内容，再认领缓存。
// 必须在编辑器写入自己的任何键（包括 editingId）之前运行。
fun claimEditorSession(): Long {
    EditorCache.profileCacheStore.reset()
    val token = SystemClock.elapsedRealtimeNanos()
    EditorCache.profileCacheStore.putLong(Key.EDITOR_SESSION, token)
    return token
}

// 恢复：把编辑器实例状态里保存的令牌和缓存里的比对。
// CACHE_LOST 表示进程死过、内存缓存是空的（调用方照旧重新初始化）；
// TAKEN_OVER 表示这期间别的顶层编辑器认领了缓存。
fun checkEditorSession(savedToken: Long): EditorSessionState {
    val cached = EditorCache.profileCacheStore.getLong(Key.EDITOR_SESSION)
    return when {
        cached == null -> EditorSessionState.CACHE_LOST
        cached == savedToken -> EditorSessionState.OWNER
        else -> EditorSessionState.TAKEN_OVER
    }
}

// 进程死亡后的重新初始化重写了缓存，这里用同一个令牌重新认领，
// 这样之后再次重建时仍能对得上。
fun renewEditorSession(savedToken: Long) {
    EditorCache.profileCacheStore.putLong(Key.EDITOR_SESSION, savedToken)
}

// 迟到的初始化 / 选择器回调往缓存里写：只要缓存没被别的编辑器接管就放行。
// CACHE_LOST 也放行——进程死亡后缓存是空的，正是由这些写入（和随后的
// renewEditorSession）重建出来的。
fun EditorSessionState.permitsCacheWrite() = this != EditorSessionState.TAKEN_OVER

// 保存要求缓存里就是本编辑器的内容：缓存丢了（还没重建）或被接管都不能存，
// 否则会把别人的 / 空的内容写进本编辑器正在编辑的实体。
fun EditorSessionState.permitsSave() = this == EditorSessionState.OWNER
