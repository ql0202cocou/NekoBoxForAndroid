package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutNetworkBinding
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.widget.hostMainScrollable

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 顶部栏的抬升由工具页的控制器按当前页判断，这里只让本页的滚动视图参与
        hostMainScrollable(view, lift = (parentFragment as? ToolsFragment)?.appBarLift)

        val binding = LayoutNetworkBinding.bind(view)
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }
    }

}