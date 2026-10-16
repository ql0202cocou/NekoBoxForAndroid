package io.nekohasekai.sagernet.ui

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView

/**
 * 配置页工具栏的搜索接线：搜索框的监听、失焦收起、返回键先收起搜索框，以及按关键字过滤各分组页
 * （从 ConfigurationFragment 拆出，逻辑不变）。
 * 由 ConfigurationFragment 的字段持有，只存 fragment 引用，生命周期与原先这些代码写在 fragment 里一致；
 * 返回键回调仍挂在 fragment 的 viewLifecycleOwner 上
 */
class ConfigurationSearch(private val fragment: ConfigurationFragment) : SearchView.OnQueryTextListener {

    override fun onQueryTextChange(query: String): Boolean {
        if (query.isEmpty()) {
            // 清空作用到每个分组页：滑到别的页时搜索框失焦收起才发出这次清空，
            // selectedGroup 此时已是新页，只清当前页的话原页留着搜索词，之后每次
            // 整组重读都按它过滤
            for (page in fragment.childFragmentManager.fragments) {
                (page as? ProfileListFragment)?.adapter?.filter("")
            }
        } else {
            fragment.getCurrentGroupFragment()?.adapter?.filter(query)
        }
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    /** 给工具栏上的搜索框（若有）接上监听；onViewCreated 调用 */
    fun setup(searchView: SearchView?) {
        if (searchView != null) {
            searchView.setOnQueryTextListener(this)
            searchView.maxWidth = Int.MAX_VALUE

            searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    cancelSearch(searchView)
                }
            }

            // 搜索框展开时返回键先收起它，而不是直接退出；收起后回调停用，
            // 返回交还给 MainActivity 的页面级处理
            val closeSearchOnBack = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = cancelSearch(searchView)
            }
            fragment.requireActivity().onBackPressedDispatcher
                .addCallback(fragment.viewLifecycleOwner, closeSearchOnBack)
            searchView.setOnSearchClickListener { closeSearchOnBack.isEnabled = true }
            searchView.setOnCloseListener {
                closeSearchOnBack.isEnabled = false
                false
            }
            searchViewBackCallback = closeSearchOnBack
        }
    }

    private var searchViewBackCallback: OnBackPressedCallback? = null

    private fun cancelSearch(searchView: SearchView) {
        searchView.onActionViewCollapsed()
        searchView.clearFocus()
        searchViewBackCallback?.isEnabled = false
    }
}
