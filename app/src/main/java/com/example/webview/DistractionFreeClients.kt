package com.example.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Standard Modern Mobile User-Agent string to ensure responsive mobile web layouts.
 */
const val STANDARD_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

/**
 * Helper to configure WebSettings with requested options.
 */
@SuppressLint("SetJavaScriptEnabled")
fun configureWebSettings(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        mediaPlaybackRequiresUserGesture = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        userAgentString = STANDARD_MOBILE_USER_AGENT
        cacheMode = WebSettings.LOAD_DEFAULT
        allowFileAccess = false
        allowContentAccess = false
    }
}

/**
 * Custom WebViewClient that enforces domain isolation and CSS distraction filtering.
 *
 * In shouldOverrideUrlLoading, navigation away from the original domain
 * (or toward discovery feeds/homepages) is blocked.
 * In onPageFinished, CSS injection is triggered to strip feed components.
 */
class DistractionFreeWebViewClient(
    private var allowedHost: String = "",
    private var initialPath: String = "",
    private val onNavigationBlocked: (url: String, reason: String) -> Unit,
    private val onPageLoading: (isLoading: Boolean) -> Unit,
    private val onPageTitleReceived: (title: String?) -> Unit,
    private val onErrorReceived: (description: String) -> Unit
) : WebViewClient() {

    fun updateAllowedHost(host: String, path: String = "") {
        this.allowedHost = normalizeHost(host)
        this.initialPath = path
    }

    private fun normalizeHost(host: String): String {
        return host.lowercase().removePrefix("www.")
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val newHost = normalizeHost(uri.host.orEmpty())
        val newPath = uri.path.orEmpty().lowercase()

        // If no allowed host set yet (e.g. blank page), allow
        if (allowedHost.isEmpty() || allowedHost == "about:blank") {
            return false
        }

        // Allow legitimate media & CDN asset sub-domains for YouTube embeds
        val isAllowedMediaDomain = allowedHost.contains("youtube") && (
                newHost.contains("youtube") ||
                newHost.contains("googlevideo.com") ||
                newHost.contains("gstatic.com") ||
                newHost.contains("ytimg.com") ||
                newHost.contains("google.com")
        )

        // Check if user is navigating off-domain
        val isSameHost = newHost == allowedHost ||
                newHost.endsWith(".$allowedHost") ||
                allowedHost.endsWith(".$newHost") ||
                isAllowedMediaDomain

        if (!isSameHost) {
            onNavigationBlocked(
                uri.toString(),
                "External link blocked: navigation outside of '$allowedHost' is restricted."
            )
            return true // Block navigation
        }

        // Check for common feed / explore / recommendation trap paths
        val blockedPathKeywords = listOf(
            "/explore", "/home", "/feed", "/trending", "/foryou",
            "/discover", "/recommended", "/shorts", "/reels"
        )

        // Block if the user attempts to click home/explore when viewing a specific post
        val isTrappedPath = blockedPathKeywords.any { keyword ->
            newPath == keyword || newPath.startsWith("$keyword/")
        }

        if (isTrappedPath && initialPath.isNotEmpty() && newPath != initialPath) {
            onNavigationBlocked(
                uri.toString(),
                "Feed navigation blocked: staying on your isolated post."
            )
            return true // Block navigation to feeds
        }

        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageLoading(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageLoading(false)

        view?.title?.let { onPageTitleReceived(it) }

        // Inject distraction-free CSS
        val currentUri = try { Uri.parse(url.orEmpty()) } catch (_: Exception) { null }
        val host = currentUri?.host ?: allowedHost
        val injectionScript = DistractionFreeCss.buildInjectionScript(host)
        view?.evaluateJavascript(injectionScript, null)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        // Only report main-frame errors to avoid noisy ad/tracker block errors
        if (request?.isForMainFrame == true) {
            onPageLoading(false)
            val desc = error?.description?.toString() ?: "Error loading page"
            onErrorReceived(desc)
        }
    }
}

/**
 * Custom WebChromeClient that supports HTML5 full-screen video playback and progress tracking.
 */
class DistractionFreeWebChromeClient(
    private val onShowCustomViewCallback: (view: View, callback: CustomViewCallback) -> Unit,
    private val onHideCustomViewCallback: () -> Unit,
    private val onProgressChangedCallback: (progress: Int) -> Unit
) : WebChromeClient() {

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view != null && callback != null) {
            onShowCustomViewCallback(view, callback)
        }
    }

    override fun onHideCustomView() {
        onHideCustomViewCallback()
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedCallback(newProgress)
    }
}
