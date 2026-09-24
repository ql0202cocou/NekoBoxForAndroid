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
import io.nekohasekai.sagernet.widget.padForSystemBars
import moe.matsuri.nb4a.utils.WebViewUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.nekohasekai.sagernet.bg.ServiceRegistry

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

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

    private fun loadPanel(webView: WebView) {
        val url = panelUrl()
        panelOrigin = url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" }
        webView.loadUrl(url)
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
        if (!DataStore.enableClashAPI || !ServiceRegistry.state.started) return url
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
