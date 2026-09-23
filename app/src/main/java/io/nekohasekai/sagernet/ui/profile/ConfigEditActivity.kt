package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.language.json.JsonLanguage
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutEditConfigBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.safeDrawingTypes
import moe.matsuri.nb4a.ui.ExtendedKeyboard
import org.json.JSONObject
import io.nekohasekai.sagernet.database.EditorCache

class ConfigEditActivity : ThemedActivity() {

    var dirty = false
    var key = Key.SERVER_CONFIG
    var useConfigStore = false

    // 进入时父编辑器的脏标记；未保存退出时恢复，避免父编辑器误报未保存
    var parentDirty = false
    var saved = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as ConfigEditActivity).saveAndExit()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    lateinit var binding: LayoutEditConfigBinding

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        // The framework restores the editor text on recreation; restore the
        // dirty flag with it, or the back guard would be bypassed after a
        // rotation.
        if (savedInstanceState != null) {
            dirty = savedInstanceState.getBoolean("dirty")
            parentDirty = savedInstanceState.getBoolean("parentDirty")
        } else {
            parentDirty = EditorCache.dirty
        }

        binding = LayoutEditConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.config_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        guardUnsavedChanges({ dirty }) { UnsavedChangesDialogFragment().apply { key() } }

        binding.editor.apply {
            language = JsonLanguage()
            setHorizontallyScrolling(true)
            if (useConfigStore) {
                setTextContent(DataStore.configurationStore.getString(key) ?: "")
            } else {
                setTextContent(EditorCache.profileCacheStore.getString(key) ?: "")
            }
            addTextChangedListener {
                if (!dirty) {
                    dirty = true
                    EditorCache.dirty = true
                }
            }
        }

        binding.actionTab.setOnClickListener {
            try {
                binding.editor.insert(binding.editor.tab())
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
        binding.actionUndo.setOnClickListener {
            try {
                // 空栈时 UndoStack.pop() 直接越界（库未判空），属常规情况，先判 canUndo
                if (binding.editor.canUndo()) binding.editor.undo()
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
        binding.actionRedo.setOnClickListener {
            try {
                // 同上，先判 canRedo
                if (binding.editor.canRedo()) binding.editor.redo()
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
        binding.actionFormat.setOnClickListener {
            formatText()?.let {
                binding.editor.setTextContent(it)
            }
        }

        val extendedKeyboard = findViewById<ExtendedKeyboard>(R.id.extended_keyboard)
        extendedKeyboard.setKeyListener { char ->
            try {
                binding.editor.insert(char)
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
        extendedKeyboard.setHasFixedSize(true)
        extendedKeyboard.submitList("{},:_\"".map { it.toString() })
        extendedKeyboard.setBackgroundColor(getColorAttr(R.attr.primaryOrTextPrimary))

        val keyboardContainer = findViewById<LinearLayout>(R.id.keyboard_container)
        ViewCompat.setOnApplyWindowInsetsListener(keyboardContainer) { v, windowInsets ->
            // getInsets(ime()) is already zero while the keyboard is hidden.
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(safeDrawingTypes)
            v.updatePadding(
                left = systemBarInsets.left,
                right = systemBarInsets.right,
                bottom = systemBarInsets.bottom,
            )
            v.updateLayoutParams<MarginLayoutParams> {
                // The navigation-bar part is padding; only the remaining IME height
                // belongs in the margin so the extended keyboard is not lifted twice.
                bottomMargin = maxOf(0, imeInsets.bottom - systemBarInsets.bottom)
            }

            windowInsets
        }

        binding.editorContainer.padForSystemBars(bottom = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("dirty", dirty)
        outState.putBoolean("parentDirty", parentDirty)
    }

    override fun onDestroy() {
        // 放弃编辑（未保存退出）时把父编辑器的脏标记恢复为进入前的值
        if (isFinishing && !saved) EditorCache.dirty = parentDirty
        super.onDestroy()
    }

    fun formatText(): String? {
        try {
            val txt = binding.editor.text.toString()
            if (txt.isBlank()) {
                return ""
            }
            return JSONObject(txt).toStringPretty()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
                .setMessage(e.readableMessage).show()
            return null
        }
    }

    fun saveAndExit() {
        formatText()?.let {
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, it)
            } else {
                EditorCache.profileCacheStore.putString(key, it)
            }
            saved = true
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                saveAndExit()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
