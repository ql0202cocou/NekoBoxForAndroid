package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.LayoutRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.EditorSessionState
import io.nekohasekai.sagernet.database.STATE_EDITOR_SESSION
import io.nekohasekai.sagernet.database.checkEditorSession
import io.nekohasekai.sagernet.database.claimEditorSession
import io.nekohasekai.sagernet.database.renewEditorSession

/**
 * Base of the top-level editors (profile / route / group), which own the shared
 * in-memory profileCacheStore one at a time; see EditorSession.kt. The claim runs
 * before `super.onCreate()` — a restored preference fragment reads the cache from
 * there — while the give-up has to run after it, so the two halves stay separate
 * calls instead of one wrapper.
 */
abstract class EditorActivity : ThemedActivity {
    constructor() : super()
    constructor(@LayoutRes contentLayoutId: Int) : super(contentLayoutId)

    // Token proving this Activity still owns the cache; persisted so a restore
    // can detect another editor taking it over.
    private var editorSession = 0L

    /** False once another editor claimed the cache; saving would hit the wrong entity. */
    protected var ownsEditorSession = true
        private set

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_EDITOR_SESSION, editorSession)
    }

    /**
     * A first creation resets the cache and claims it, a restore verifies the claim.
     * Call before `super.onCreate()`, and before writing any of the editor's own keys.
     */
    protected fun beginEditorSession(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            editorSession = claimEditorSession()
            return
        }
        editorSession = savedInstanceState.getLong(STATE_EDITOR_SESSION, 0L)
        ownsEditorSession = checkEditorSession(editorSession) != EditorSessionState.TAKEN_OVER
    }

    /**
     * Call right after `super.onCreate()` and return when true: the cache is gone and
     * the user has been told why.
     */
    protected fun finishIfEditorSessionLost(): Boolean {
        if (ownsEditorSession) return false
        Toast.makeText(this, R.string.editor_session_lost, Toast.LENGTH_LONG).show()
        finish()
        return true
    }

    /** Re-claim after a process-death re-init rewrote the cache. */
    protected fun renewEditorSessionToken() = renewEditorSession(editorSession)
}
