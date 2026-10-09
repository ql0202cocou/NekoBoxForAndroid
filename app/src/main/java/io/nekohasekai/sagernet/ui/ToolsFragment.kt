package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    override val opensFromSettings = true

    private var binding: LayoutToolsBinding? = null

    /**
     * 顶部栏随当前页的滚动位置抬升：主界面 ConfigurationFragment.updateAppBarLift 的工具页版本。
     * 两个页面（NetworkFragment / BackupFragment）是 FragmentStateAdapter 建的，
     * 默认 tag 为 "f" + position，按当前页位置取出它的根视图判断能否向上滚
     */
    fun updateAppBarLift() {
        val binding = binding ?: return
        val page = childFragmentManager.findFragmentByTag("f${binding.toolsPager.currentItem}")
        binding.appbar.setLifted(page?.view?.canScrollVertically(-1) == true)
    }

    private val appBarLiftCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = updateAppBarLift()
    }

    override fun onDestroyView() {
        binding?.toolsPager?.unregisterOnPageChangeCallback(appBarLiftCallback)
        binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())

        val binding = LayoutToolsBinding.bind(view)
        // 横屏下的侧边导航栏 / 刘海由 InsetAppBarLayout 处理，各页自己处理自己的内边距
        this.binding = binding
        binding.toolsPager.adapter = ToolsAdapter(tools)
        binding.toolsPager.unregisterOnPageChangeCallback(appBarLiftCallback)
        binding.toolsPager.registerOnPageChangeCallback(appBarLiftCallback)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}
