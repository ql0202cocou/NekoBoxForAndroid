package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.fmt.CLASH_API_LISTEN
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.padForSystemBars
import moe.matsuri.nb4a.utils.WebViewUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceRegistry

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    override val opensFromSettings = true

    private var mWebView: WebView? = null
    private var webviewContainer: ViewGroup? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // The native container keeps the page clear of the system bars; the WebView itself
        // must then see those insets as zero, or a dashboard using CSS safe-area-inset-*
        // pads a second time (the AppBar already clears the status bar). IME insets still
        // reach the WebView.
        binding.webviewContainer.padForSystemBars(consume = true)
        webviewContainer = binding.webviewContainer

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        setupWebView(binding.webview)
        loadPanel(binding.webview)
    }

    // close 菜单销毁 WebView 后 action_set_url 还能重建，所以初始化统一收在这里
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        mWebView = webView
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptEnabled = true
        // the panel is served over http; nothing here has any business reading local
        // files or another app's content provider
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }

            @Suppress("DEPRECATION") // the only overload the platform calls below API 24
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) =
                leavesPanel(url)

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ) = leavesPanel(request?.url?.toString())
        }
    }

    // close 之后按原布局参数重建一个 WebView
    private fun recreateWebView(): WebView? {
        val container = webviewContainer ?: return null
        val webView = WebView(container.context)
        container.addView(
            webView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setupWebView(webView)
        return webView
    }

    private fun destroyWebView() {
        // 先从父布局摘下再 destroy：销毁仍挂在视图树上的 WebView 可能崩溃
        (mWebView?.parent as? ViewGroup)?.removeView(mWebView)
        mWebView?.destroy()
        mWebView = null
    }

    // Host:port of the page currently loaded. This WebView runs JavaScript right next
    // to the loopback Clash API, so it hosts that one origin and nothing else.
    private var panelOrigin: String? = null

    // 面板因核心没在跑而留空（打开时就没跑，或中途停止）：连上后自动加载。
    // 用户用 close 菜单关掉的不算，mWebView 为 null 时也不自动重建
    private var waitingForCore = false

    private fun loadPanel(webView: WebView) {
        val url = panelUrl()
        panelOrigin = url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" }
        if (panelOrigin == CLASH_API_LISTEN && !clashApiServing()) {
            // 核心没在跑时这个回环端口谁都能占：yacd 把 secret 存在该源的 localStorage，
            // 加载过去就等于让占端口的页面在同源读走它。不加载，并清掉 WebView 存储
            // （deleteAllData 才保证包含 Web Storage；本应用只有面板用 WebView）
            WebStorage.getInstance().deleteAllData()
            webView.loadUrl("about:blank")
            // 已连接而面板仍不可用，只能是 Clash API 没开
            snackbar(
                if (ServiceRegistry.state.connected) R.string.clash_api_disabled else R.string.not_connected
            ).show()
            waitingForCore = true
            return
        }
        waitingForCore = false
        webView.loadUrl(url)
    }

    // 要求 Connected，不能用 started：Connecting 阶段 started 已为 true，核心却还没占住
    // 9090，此时带 secret 加载就可能发给先占了端口的应用
    private fun clashApiServing() = ServiceRegistry.state.connected && DataStore.enableClashAPI

    // 由 MainActivity.changeState 在每次服务状态变化时调用
    fun onCoreStateChanged(state: BaseService.State) {
        if (!state.started) {
            // Stopping 即触发，早于端口释放：开着的 yacd 会继续带着 secret 请求本机 9090，
            // 端口一放出谁占了谁就收到。换成空白页，重新连上后再加载
            if (panelOrigin != CLASH_API_LISTEN) return
            waitingForCore = true
            mWebView?.loadUrl("about:blank")
        } else if (state.connected && waitingForCore && DataStore.enableClashAPI) {
            // Clash API 关着时连上了面板也不可用：保持等待，免得每次重连都弹提示
            loadPanel(mWebView ?: return)
        }
    }

    // true = don't follow it here. A link or redirect off the panel goes to the browser;
    // anything that is not http(s) at all is dropped.
    private fun leavesPanel(url: String?): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return true
        if ("${parsed.host}:${parsed.port}" == panelOrigin) return false
        context?.launchCustomTab(url)
        return true
    }

    // yacd reads the API secret from the query string. sing-box serves the panel at
    // /ui/ and its /ui redirect drops the query, so load the slash form directly.
    // The secret goes to the endpoint this app itself serves and nowhere else: the URL
    // is user-editable, and while the core is down any local app can bind that loopback
    // port — the very population the secret exists to keep out.
    private fun panelUrl(): String {
        val url = DataStore.yacdURL
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.queryParameter("secret") != null) return url
        if ("${parsed.host}:${parsed.port}" != CLASH_API_LISTEN) return url
        if (!clashApiServing()) return url
        return parsed.newBuilder().apply {
            if (parsed.encodedPath == "/ui") encodedPath("/ui/")
            addQueryParameter("secret", DataStore.requireClashApiSecret())
        }.build().toString()
    }

    override fun onDestroyView() {
        destroyWebView()
        webviewContainer = null
        super.onDestroyView()
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.yacdURL = view.text.toString()
                        // close 之后 WebView 已销毁，重建一个再加载
                        loadPanel(mWebView ?: recreateWebView() ?: return@setPositiveButton)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                mWebView?.onPause()
                destroyWebView()
            }
        }
        return true
    }
}
