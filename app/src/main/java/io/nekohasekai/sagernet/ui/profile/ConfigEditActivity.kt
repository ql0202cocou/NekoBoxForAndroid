package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.editorkit.utils.EditorTheme
import com.blacksquircle.ui.language.json.JsonLanguage
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.appbar.AppBarLayout
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
import io.nekohasekai.sagernet.widget.liftOnScrollOf
import io.nekohasekai.sagernet.widget.padForSystemBars
import io.nekohasekai.sagernet.widget.safeDrawingTypes
import org.json.JSONObject
import io.nekohasekai.sagernet.database.EditorCache

class ConfigEditActivity : ThemedActivity() {

    // 载入时的文本；未保存与否只看当前文本是否与它相同。
    // 存储内容在本页存活期间不变，所以重建时重新载入即可得到同一份，不必进 savedInstanceState
    private var loadedText = ""
    // 每次按键都会读：先比长度，相同再逐字比，不整篇复制成 String
    private val dirty: Boolean
        get() = binding.editor.text.let { it.length != loadedText.length || !it.contentEquals(loadedText) }
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

    // 第一次用到在 onCreate 里（super 之后）
    val binding by lazy { LayoutEditConfigBinding.inflate(layoutInflater) }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        if (savedInstanceState != null) {
            parentDirty = savedInstanceState.getBoolean("parentDirty")
        } else {
            parentDirty = EditorCache.dirty
        }

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
            // 配色随亮暗切换：库默认配色在亮色底上的符号（{ } : ,）几乎看不见。
            // 底色与当前行高亮改用页面的颜色角色，避免与页面 surface 色差
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            val base = if (night) EditorTheme.DARCULA else EditorTheme.INTELLIJ_LIGHT
            colorScheme = base.copy(
                backgroundColor = getColorAttr(com.google.android.material.R.attr.colorSurface),
                selectedLineColor = getColorAttr(com.google.android.material.R.attr.colorSurfaceContainer),
            )
            setHorizontallyScrolling(true)
            if (useConfigStore) {
                setTextContent(DataStore.configurationStore.getString(key) ?: "")
            } else {
                setTextContent(EditorCache.profileCacheStore.getString(key) ?: "")
            }
            loadedText = text.toString()
            // 系统在重建时恢复文本也会触发监听，所以每次都按「与载入文本是否相同」重算，
            // 改回原样即视为未修改
            addTextChangedListener {
                EditorCache.dirty = dirty || parentDirty
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

        val extendedKeyboard = binding.extendedKeyboard
        extendedKeyboard.setKeyListener { char ->
            try {
                binding.editor.insert(char)
            } catch (e: Exception) {
                Logs.e(e)
            }
        }
        extendedKeyboard.setHasFixedSize(true)
        // 键位 40dp 宽：窄屏上放不下时靠末尾的键滚动，所以最常用的引号排在最前、下划线最后
        extendedKeyboard.submitList("\"{},:_".map { it.toString() })

        ViewCompat.setOnApplyWindowInsetsListener(binding.keyboardContainer) { v, windowInsets ->
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
        // 顶栏随编辑器滚动抬升，取代原来顶栏下的固定分隔线。
        // 编辑器的 onScrollChanged 重写链都调了 super，View 级的滚动监听会被触发
        findViewById<AppBarLayout>(R.id.appbar).liftOnScrollOf(binding.editor)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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
