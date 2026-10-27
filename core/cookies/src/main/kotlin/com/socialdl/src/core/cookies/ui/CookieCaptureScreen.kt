package com.socialdl.src.core.cookies.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.cookies.CaptureHostPolicy
import com.socialdl.src.core.cookies.CaptureOutcome
import com.socialdl.src.core.cookies.CookieCaptureConfig
import com.socialdl.src.core.model.Platform

private const val TAG = "CookieCapture"

/**
 * Full-screen interactive WebView host for cookie capture (§2.6) — a real, visible
 * browser surface on plain youtube.com, where the user signs in exactly like they
 * would in a browser (2FA/passkeys/captchas handled by the trusted origin; no
 * injected JS, no DOM scraping).
 *
 * Lifecycle discipline: `stopLoading()` + `destroy()` on every exit path.
 */
@Composable
fun CookieCaptureScreen(
    platform: Platform,
    onResult: (success: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CookieCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(platform) {
        viewModel.initialize(platform)
    }

    // Deliver the one-shot result exactly once. Failure/NotSignedIn are shown
    // inline instead of being treated as terminal, so the user can finish the
    // redirect (or retry) without losing the WebView session.
    LaunchedEffect(uiState.result, uiState.cancelled) {
        when {
            uiState.cancelled -> onResult(false)
            uiState.result is CaptureOutcome.Success -> {
                viewModel.acknowledgeResult()
                onResult(true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Toolbar: Cancel left, "I'm signed in" fallback right (§2.6).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111214))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::onCancelClicked, enabled = !uiState.busy) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel cookie sign-in",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiState.config?.title ?: "Sign in",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = uiState.config?.subtitle
                        ?: "SocialDL never sees your password; only cookies are stored encrypted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9AA0A6)
                )
            }
            IconButton(
                onClick = viewModel::onManualSignInClicked,
                enabled = !uiState.busy && uiState.config != null
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "I'm signed in",
                    tint = Color(0xFFEF4444)
                )
            }
        }

        uiState.message?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B1B1B))
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB4AB)
                )
                TextButton(onClick = viewModel::dismissMessage) {
                    Text("Dismiss", color = Color(0xFFFFB4AB))
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val config = uiState.config
            if (config != null) {
                CaptureWebView(config = config, onSettled = viewModel::onPageFinished)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (uiState.busy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptureWebView(
    config: CookieCaptureConfig,
    onSettled: (url: String?) -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Deterministic teardown on BOTH the composable-leaves-composition path and
    // the ambient cancellation path (§2.6: destroy() on every exit path).
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google blocks embedded WebViews ("This browser or app may not be
                    // secure") by fingerprinting the UA: the "; wv)" marker and the
                    // legacy "Version/4.0 " prefix give the WebView away. Strip both so
                    // the WebView is indistinguishable from the device's own Chrome —
                    // this keeps accounts.google.com reachable mid sign-in flow (§2.6).
                    val defaultUa = settings.userAgentString
                    val cleanDeviceUa = defaultUa
                        .replace("Version/4.0 ", "")
                        .replace("; wv)", ")")
                    val captureUa = config.userAgentOverride
                        ?: cleanDeviceUa.ifBlank { config.userAgent ?: defaultUa }
                    settings.userAgentString = captureUa
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            return handleNavigation(view, request, config)
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            onSettled(url)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                loading = false
                                Logger.w(
                                    TAG,
                                    "Main-frame load failed (${error.errorCode} " +
                                        "${error.description}) at ${request.url}"
                                )
                            }
                        }
                    }
                    // Google's sign-in occasionally opens a popup (account chooser /
                    // re-auth step). Without multi-window support the popup never
                    // loads and the opener page spins forever, so route the popup's
                    // first real URL into this visible WebView instead.
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message
                        ): Boolean {
                            return openPopupInPlace(view, config, resultMsg)
                        }
                    }
                    settings.setSupportMultipleWindows(true)
                    loadUrl(config.loginUrl)
                }.also { webViewRef = it }
            }
        )
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
    }
}

/**
 * Applies the navigation allowlist to a main-frame load.
 *
 * Only main-frame navigations to a non-allowlisted host are handed to an external
 * browser. Subframe navigations (ads, reCAPTCHA variants, Google's static asset
 * frames) are blocked in place — previously they too launched the external
 * browser, which backgrounded the app and left the sign-in page waiting forever.
 */
private fun handleNavigation(
    view: WebView,
    request: WebResourceRequest,
    config: CookieCaptureConfig,
): Boolean {
    val url = request.url
    if (url.scheme?.lowercase() !in HTTP_SCHEMES) {
        Logger.w(TAG, "Blocked non-web navigation (${url.scheme})")
        return true
    }
    if (CaptureHostPolicy.isAllowedHost(
            url.host,
            config.allowedHosts,
            config.allowedHostPatterns
        )
    ) {
        return false
    }
    if (!request.isForMainFrame) {
        Logger.d(TAG, "Blocked disallowed subframe host ${url.host}")
        return true
    }
    Logger.w(TAG, "Opening non-allowlisted host externally: ${url.host}")
    runCatching {
        view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
    }.onFailure {
        Logger.w(TAG, "Could not open ${url.host} externally", it)
    }
    return true
}

/**
 * Intercepts a `window.open()` popup and loads its first real navigation in the
 * otherwise visible WebView. Cookies live in the process-wide CookieManager, so
 * the session continues seamlessly in the visible view.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun openPopupInPlace(
    opener: WebView,
    config: CookieCaptureConfig,
    resultMsg: Message,
): Boolean {
    val popup = WebView(opener.context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = opener.settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Let http(s) commits reach onPageStarted, where the allowlist is
                // checked before the URL is handed to the visible WebView.
                if (request.url.scheme?.lowercase() !in HTTP_SCHEMES) {
                    view.post {
                        runCatching {
                            view.stopLoading()
                            view.destroy()
                        }
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url.isNullOrBlank() || url == "about:blank") return
                val scheme = url.substringBefore(':').lowercase()
                val allowed = scheme in HTTP_SCHEMES &&
                    CaptureHostPolicy.isAllowedHost(
                        CaptureHostPolicy.hostOf(url),
                        config.allowedHosts,
                        config.allowedHostPatterns
                    )
                if (!allowed) {
                    Logger.w(TAG, "Discarded popup navigation: $url")
                    view.post {
                        runCatching {
                            view.stopLoading()
                            view.destroy()
                        }
                    }
                    return
                }
                view.stopLoading()
                opener.post {
                    runCatching { opener.loadUrl(url) }
                        .onFailure { Logger.w(TAG, "Popup transfer failed", it) }
                    runCatching { view.destroy() }
                }
            }
        }
    }
    val transport = resultMsg.obj as? WebView.WebViewTransport
    if (transport == null) {
        runCatching { popup.destroy() }
        return false
    }
    transport.webView = popup
    resultMsg.sendToTarget()
    return true
}

private val HTTP_SCHEMES = setOf("http", "https")
