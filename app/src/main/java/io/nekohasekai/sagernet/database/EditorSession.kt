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
