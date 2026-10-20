package io.nekohasekai.sagernet.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * [MainActivity] 级的页面外状态，旋转时留存，进程重建时经 SavedStateHandle 带回。
 *
 * 目前只有设置一级页的列表滚动状态：从一级页进入二级页面（七个分类页和末尾四个入口）前记下，
 * 返回一级页时由新的设置列表取走恢复，免得每次从顶部滚到底。以前它是 Activity 的字段，二级页上一旋转就丢；
 * 放在这里，「设置 → 分类页 → 旋转 → 返回」一级页仍停在原来的位置。
 */
class MainViewModel(private val handle: SavedStateHandle) : ViewModel() {

    /**
     * 进入二级页面前设置一级列表的滚动状态（LayoutManager 的 SavedState，可序列化）；没有待恢复的状态时为 null。
     * 赋 null 即清除
     */
    var settingsListState: Parcelable?
        get() = handle[KEY_SETTINGS_LIST_STATE]
        set(value) {
            if (value == null) handle.remove<Parcelable>(KEY_SETTINGS_LIST_STATE)
            else handle[KEY_SETTINGS_LIST_STATE] = value
        }

    /** 新的设置列表取走进入二级页面前保存的滚动状态，只取一次 */
    fun takeSettingsListState(): Parcelable? = handle.remove<Parcelable>(KEY_SETTINGS_LIST_STATE)

    private companion object {
        const val KEY_SETTINGS_LIST_STATE = "settingsListState"
    }
}
