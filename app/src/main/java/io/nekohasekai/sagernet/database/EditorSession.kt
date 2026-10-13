package io.nekohasekai.sagernet.database

import android.os.SystemClock
import io.nekohasekai.sagernet.Key

// Ownership of the shared in-memory profileCacheStore: at most one top-level
// editor (profile / route / group) uses it at a time, child editors
// (ConfigEditActivity, AppListActivity, ProfileSelectActivity) share their
// parent's keys by design and never claim a session. The token turns a silent
// cache takeover into an explicit failure instead of a save into the wrong
// entity.

// Bundle key under which each editor persists its token.
const val STATE_EDITOR_SESSION = "editorSessionState"

enum class EditorSessionState { OWNER, CACHE_LOST, TAKEN_OVER }

// First creation: drop whatever the previous editor left, then claim the
// cache. Must run before the editor writes any of its own keys (editingId
// included).
fun claimEditorSession(): Long {
    EditorCache.profileCacheStore.reset()
    val token = SystemClock.elapsedRealtimeNanos()
    EditorCache.profileCacheStore.putLong(Key.EDITOR_SESSION, token)
    return token
}

// Restore: compare the token kept in the editor's instance state against the
// cache. CACHE_LOST means the process died and the in-memory cache is empty
// (the caller re-initializes as before); TAKEN_OVER means another top-level
// editor claimed the cache meanwhile.
fun checkEditorSession(savedToken: Long): EditorSessionState {
    val cached = EditorCache.profileCacheStore.getLong(Key.EDITOR_SESSION)
    return when {
        cached == null -> EditorSessionState.CACHE_LOST
        cached == savedToken -> EditorSessionState.OWNER
        else -> EditorSessionState.TAKEN_OVER
    }
}

// Re-claim with the same token after a process-death re-init rewrote the
// cache, so later recreations still match.
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
