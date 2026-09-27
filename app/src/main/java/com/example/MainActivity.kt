package com.example

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.DistractionFreeEmptyState
import com.example.ui.DistractionFreeFloatingCloseButton
import com.example.ui.DistractionFreeTopBar
import com.example.ui.FullscreenCustomViewOverlay
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ProcessedUrl
import com.example.util.UrlPreprocessor
import com.example.webview.DistractionFreeWebChromeClient
import com.example.webview.DistractionFreeWebViewClient
import com.example.webview.configureWebSettings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Holds shared URL from intent if launched via ACTION_SEND or ACTION_VIEW
    private var pendingIntentUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract shared URL on cold start
        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                DistractionFreeViewerApp(
                    incomingUrl = pendingIntentUrl.value,
                    onUrlConsumed = { pendingIntentUrl.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Extract shared URL if app was already running
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                pendingIntentUrl.value = sharedText
            }
        } else if (Intent.ACTION_VIEW == action) {
            val dataString = intent.dataString
            if (!dataString.isNullOrBlank()) {
                pendingIntentUrl.value = dataString
            }
        }
    }
}

@Composable
fun DistractionFreeViewerApp(
    incomingUrl: String?,
    onUrlConsumed: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State for URL entry and loading
    var urlInput by remember { mutableStateOf("") }
    var currentProcessedUrl by remember { mutableStateOf<ProcessedUrl?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf<String?>(null) }

    // Full-screen video state for WebChromeClient
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // Hold reference to WebView
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Function to load a URL into the viewer
    val loadUrl: (String) -> Unit = { rawUrl ->
        val processed = UrlPreprocessor.process(rawUrl)
        if (processed != null) {
            currentProcessedUrl = processed
            urlInput = processed.originalExtractedUrl
            webViewInstance?.let { webView ->
                (webView.webViewClient as? DistractionFreeWebViewClient)?.updateAllowedHost(
                    host = processed.allowedHost,
                    path = ""
                )
                webView.loadUrl(processed.targetUrl)
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Invalid or unsupported URL. Please enter a valid web link.")
            }
        }
    }

    // Function to clear and reset the viewer
    val clearAndReset: () -> Unit = {
        // Hide fullscreen if visible
        if (fullscreenCustomView != null) {
            customViewCallback?.onCustomViewHidden()
            fullscreenCustomView = null
            customViewCallback = null
        }
        currentProcessedUrl = null
        urlInput = ""
        pageTitle = null
        isLoading = false
        loadingProgress = 0
        webViewInstance?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
        }
    }

    // Handle incoming URL from share sheet or open link
    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            loadUrl(incomingUrl)
            onUrlConsumed()
        }
    }

    // Back handler: handle fullscreen first, then webview backstack, then normal back
    BackHandler(enabled = fullscreenCustomView != null || (webViewInstance?.canGoBack() == true)) {
        if (fullscreenCustomView != null) {
            customViewCallback?.onCustomViewHidden()
            fullscreenCustomView = null
            customViewCallback = null
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (fullscreenCustomView == null) {
                DistractionFreeTopBar(
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    onOpenUrl = {
                        if (urlInput.isNotBlank()) {
                            loadUrl(urlInput)
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Please enter a URL or paste a link.")
                            }
                        }
                    },
                    onPasteFromClipboard = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clipboard != null && clipboard.hasPrimaryClip()) {
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val pasted = clip.getItemAt(0).coerceToText(context).toString()
                                if (pasted.isNotBlank()) {
                                    urlInput = pasted
                                    loadUrl(pasted)
                                    Toast.makeText(context, "Link pasted & opened", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Clipboard is empty.")
                            }
                        }
                    },
                    onClearInput = { urlInput = "" },
                    isLoading = isLoading,
                    progress = loadingProgress,
                    platformBadge = currentProcessedUrl?.platformLabel,
                    isPageLoaded = currentProcessedUrl != null,
                    onReload = { webViewInstance?.reload() },
                    modifier = Modifier.statusBarsPadding()
                )
            }
        },
        floatingActionButton = {
            if (fullscreenCustomView == null && currentProcessedUrl != null) {
                DistractionFreeFloatingCloseButton(
                    onCloseAndClear = clearAndReset,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Viewport hosting WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        configureWebSettings(this)

                        webViewClient = DistractionFreeWebViewClient(
                            allowedHost = currentProcessedUrl?.allowedHost.orEmpty(),
                            onNavigationBlocked = { blockedUrl, reason ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(reason)
                                }
                            },
                            onPageLoading = { loading ->
                                isLoading = loading
                            },
                            onPageTitleReceived = { title ->
                                pageTitle = title
                            },
                            onErrorReceived = { err ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Error loading: $err")
                                }
                            }
                        )

                        webChromeClient = DistractionFreeWebChromeClient(
                            onShowCustomViewCallback = { view, callback ->
                                fullscreenCustomView = view
                                customViewCallback = callback
                            },
                            onHideCustomViewCallback = {
                                fullscreenCustomView = null
                                customViewCallback = null
                            },
                            onProgressChangedCallback = { progress ->
                                loadingProgress = progress
                            }
                        )

                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // If no page is loaded, show the friendly Empty/Welcome State on top
            AnimatedVisibility(
                visible = currentProcessedUrl == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                DistractionFreeEmptyState(
                    onSampleSelected = { sampleUrl ->
                        urlInput = sampleUrl
                        loadUrl(sampleUrl)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Fullscreen custom view overlay (e.g. HTML5 video fullscreen)
            fullscreenCustomView?.let { customView ->
                FullscreenCustomViewOverlay(
                    customView = customView,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Clean up WebView when exiting
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
        }
    }
}
