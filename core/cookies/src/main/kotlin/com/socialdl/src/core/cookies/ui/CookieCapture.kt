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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.cookies.CaptureHostPolicy
import com.socialdl.src.core.cookies.CaptureOutcome
import com.socialdl.src.core.cookies.CookieCaptureConfig
import com.socialdl.src.core.cookies.CookieCaptureController
import com.socialdl.src.core.cookies.cookieCaptureConfig
import com.socialdl.src.core.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================ ViewModel ============================


/**
 * Drives the full-screen WebView cookie capture (§2.6): resolves the probed-UA
 * config once, auto-detects login completion from `onPageFinished`, and handles
 * the manual "I'm signed in" + Cancel fallbacks.
 */
@HiltViewModel
class CookieCaptureViewModel @Inject constructor(
    private val controller: CookieCaptureController,
) : ViewModel() {

    data class CaptureUiState(
        val config: CookieCaptureConfig? = null,
        /** True from manual/auto harvest start until the outcome lands. */
        val busy: Boolean = false,
        /** One-shot success; the screen acknowledges it, then routes back. */
        val result: CaptureOutcome? = null,
        /**
         * Non-terminal feedback shown above the WebView. Used when the harvest
         * found no signed-in session or failed — the screen stays open so the
         * user can finish the redirect / retry instead of being popped back
         * silently to a "Not signed in" row.
         */
        val message: String? = null,
        /** True when the user hit Cancel. */
        val cancelled: Boolean = false,
        /** Terminal init failure; the screen shows this instead of an endless spinner. */
        val initError: String? = null,
    )

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var lastPageUrl: String? = null

    private var initializedFor: Platform? = null

    /** Resolves the flow config once per platform (the route dictates the platform). */
    fun initialize(platform: Platform) {
        if (initializedFor == platform) return
        initializedFor = platform
        // A platform switch must never show the previous platform's config (or a
        // stale init error) while the new config resolves.
        _uiState.update { it.copy(config = null, initError = null) }
        viewModelScope.launch {
            runCatching { controller.config(platform) }
                .onSuccess { config ->
                    _uiState.update { it.copy(config = config) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            initError = error.message
                                ?: "Cookie sign-in is not available for this platform"
                        )
                    }
                }
        }
    }

    /** Tracks the most recent URL for the manual fallback path. */
    fun onPageFinished(url: String?) {
        lastPageUrl = url
        val config = _uiState.value.config ?: return
        val current = _uiState.value
        if (current.busy || current.result != null || current.cancelled) return

        if (url != null && controller.isLoginComplete(config, url)) {
            harvest()
        }
    }

    fun onManualSignInClicked() {
        if (_uiState.value.busy || _uiState.value.result != null) return
        harvest(manual = true)
    }

    fun onCancelClicked() {
        // Cancel is terminal: once cancelled (or the success result landed) a
        // second tap must not re-trigger the exit side effect.
        _uiState.update {
            if (it.cancelled || it.result != null) it else it.copy(cancelled = true)
        }
    }

    fun acknowledgeResult() {
        _uiState.update { it.copy(result = null) }
    }

    /** Clears the one-shot cancel flag after the screen delivered its exit. */
    fun acknowledgeCancel() {
        _uiState.update { it.copy(cancelled = false) }
    }

    /** Clears the transient message once the user has seen it. */
    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun harvest(manual: Boolean = false) {
        val config = _uiState.value.config ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            val outcome = if (manual) {
                val url = lastPageUrl
                controller.manualCapture(config, url)
            } else {
                controller.harvestAndStore(config)
            }
            when (outcome) {
                is CaptureOutcome.Success ->
                    _uiState.update { it.copy(busy = false, result = outcome) }
                CaptureOutcome.NotSignedIn ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = "Not signed in yet. Finish the sign-in above " +
                                "(including any 2FA redirect back to the site), then tap ✓."
                        )
                    }
                is CaptureOutcome.Failure ->
                    _uiState.update { it.copy(busy = false, message = outcome.message) }
            }
        }
    }
}


// ============================ Screen ============================


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
    var exitDelivered by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.result, uiState.cancelled) {
        when {
            !exitDelivered && uiState.cancelled -> {
                exitDelivered = true
                viewModel.acknowledgeCancel()
                onResult(false)
            }
            !exitDelivered && uiState.result is CaptureOutcome.Success -> {
                exitDelivered = true
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
            when {
                config != null -> key(config.platform, config.loginUrl) {
                    CaptureWebView(config = config, onSettled = viewModel::onPageFinished)
                }
                uiState.initError != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = uiState.initError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    TextButton(onClick = viewModel::onCancelClicked) {
                        Text("Close", color = Color(0xFFEF4444))
                    }
                }
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            if (uiState.busy) {
                // Opaque to input: taps/drags must never reach the WebView that is
                // still laid out underneath this scrim while a harvest is running.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
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
    // Popups opened by window.open() are real WebViews; every one must be
    // destroyed on its close/transfer path and when this screen is disposed.
    val popups = remember { mutableSetOf<WebView>() }

    // Deterministic teardown on BOTH the composable-leaves-composition path and
    // the ambient cancellation path (§2.6: destroy() on every exit path).
    // Destroying while still attached to the view hierarchy leaks the renderer
    // process, so safeDestroy() detaches first.
    DisposableEffect(Unit) {
        onDispose {
            popups.toList().forEach { it.safeDestroy() }
            popups.clear()
            webViewRef?.safeDestroy()
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
                    // The UA is deliberately never overridden (§2.6). Overriding makes
                    // WebView suppress its User-Agent client hints, and "browser-like UA
                    // with no Sec-CH-UA" is a fingerprint platforms risk-control (X
                    // refused logins until the override was removed). Logged for
                    // diagnostics — it is what identified the problem.
                    Logger.i(
                        TAG,
                        "Capture UA (${config.platform.name}): ${settings.userAgentString}"
                    )
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
                            return openPopupInPlace(view, config, resultMsg, popups)
                        }
                    }
                    settings.setSupportMultipleWindows(true)
                    loadUrl(config.loginUrl)
                }.also { webViewRef = it }
            },
            onRelease = { view -> view.safeDestroy() }
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
    popups: MutableSet<WebView>,
): Boolean {
    val popup = WebView(opener.context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // NEVER copy opener.settings.userAgentString into the popup: an explicit
        // UA makes Chromium suppress Sec-CH-UA client hints, which Google's SSO
        // risk-control rejects. The untouched WebView UA is what the opener uses.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                popups.remove(window)
                window.safeDestroy()
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Let http(s) commits reach onPageStarted, where the allowlist is
                // checked before the URL is handed to the visible WebView.
                if (request.url.scheme?.lowercase() !in HTTP_SCHEMES) {
                    view.post {
                        popups.remove(view)
                        view.safeDestroy()
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    popups.remove(view)
                    view.post { view.safeDestroy() }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url.isNullOrBlank() || url == "about:blank" || view in destroyingWebViews) return
                val scheme = url.substringBefore(':').lowercase()
                val allowed = scheme in HTTP_SCHEMES &&
                    CaptureHostPolicy.isAllowedHost(
                        CaptureHostPolicy.hostOf(url),
                        config.allowedHosts,
                        config.allowedHostPatterns
                    )
                if (!allowed) {
                    Logger.w(TAG, "Discarded popup navigation: $url")
                    popups.remove(view)
                    view.post { view.safeDestroy() }
                    return
                }
                view.stopLoading()
                opener.post {
                    if (opener !in destroyingWebViews) {
                        runCatching { opener.loadUrl(url) }
                            .onFailure { Logger.w(TAG, "Popup transfer failed", it) }
                    }
                    popups.remove(view)
                    view.safeDestroy()
                }
            }
        }
    }
    val transport = resultMsg.obj as? WebView.WebViewTransport
    if (transport == null) {
        popup.safeDestroy()
        return false
    }
    popups += popup
    transport.webView = popup
    resultMsg.sendToTarget()
    return true
}

private val HTTP_SCHEMES = setOf("http", "https")

/**
 * WebViews still being torn down. `destroy()` is not idempotent, and the
 * dispose/release callbacks that fire on one screen exit can both target the
 * same view.
 */
private val destroyingWebViews = java.util.Collections.synchronizedSet(
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())
)

/**
 * Destroys a WebView safely: stop loading, detach it from whatever parent still
 * holds it, then destroy it. Calling `destroy()` while attached leaks the
 * renderer; repeated calls are ignored.
 */
private fun WebView.safeDestroy() {
    if (!destroyingWebViews.add(this)) return
    runCatching { stopLoading() }
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { destroy() }
}


// ============================ Navigation ============================

/**
 * One capture route for every platform (§2.6): the platform travels as a path
 * argument, so adding a capturable platform needs no new route, navigation helper or
 * graph registration — only a `CookieCaptureConfig`.
 */
const val COOKIE_CAPTURE_ROUTE = "cookie_capture_route/{platform}"

private const val ARG_PLATFORM = "platform"

/**
 * SavedStateHandle key the capture screen uses to hand `success: Boolean` back to
 * the destination it was launched from (§2.6 recapture flow; §2.7 settings screen).
 */
const val COOKIE_CAPTURE_RESULT_KEY = "cookie_capture_result"

fun NavController.navigateToCookieCapture(
    platform: Platform,
    navOptions: NavOptions? = null,
) {
    navigate("cookie_capture_route/${platform.name}", navOptions)
}

/**
 * Registers the full-screen capture route. On completing/cancelling, the result is
 * written to the previous back stack entry's SavedStateHandle and the capture screen
 * pops itself off.
 */
fun NavGraphBuilder.cookieCaptureScreen(navController: NavController) {
    composable(
        route = COOKIE_CAPTURE_ROUTE,
        arguments = listOf(navArgument(ARG_PLATFORM) { type = NavType.StringType })
    ) { entry ->
        val platform = runCatching {
            Platform.valueOf(entry.arguments?.getString(ARG_PLATFORM).orEmpty())
        }.getOrDefault(Platform.UNKNOWN)
        CookieCaptureScreen(
            platform = platform,
            onResult = { success ->
                // A stale/re-delivered callback from an old capture entry must not
                // pop the destination that happens to be current now.
                if (navController.currentBackStackEntry?.id == entry.id) {
                    publishResult(navController, success)
                }
            }
        )
    }
}

private fun publishResult(navController: NavController, success: Boolean) {
    navController.previousBackStackEntry
        ?.savedStateHandle
        ?.set(COOKIE_CAPTURE_RESULT_KEY, success)
    navController.popBackStack()
}
