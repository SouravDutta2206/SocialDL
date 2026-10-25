package com.socialdl.src.core.cookies.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.socialdl.src.core.cookies.CaptureOutcome
import com.socialdl.src.core.cookies.CookieCaptureConfig

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
    onResult: (success: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CookieCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Deliver the one-shot result exactly once, on either terminal path.
    // acknowledgeResult() nulls the outcome, so this LaunchedEffect cannot re-fire.
    LaunchedEffect(uiState.result, uiState.cancelled) {
        when {
            uiState.cancelled -> onResult(false)
            uiState.result is CaptureOutcome.Success -> {
                viewModel.acknowledgeResult()
                onResult(true)
            }
            uiState.result is CaptureOutcome.Failure ||
                uiState.result is CaptureOutcome.NotSignedIn -> {
                viewModel.acknowledgeResult()
                onResult(false)
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
                    text = "Sign in with YouTube",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "Sign in normal YouTube: tap the profile icon and log in. SocialDL never sees your password.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9AA0A6)
                )
            }
            IconButton(
                onClick = viewModel::onManualSignInClicked,
                enabled = !uiState.busy
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "I'm signed in",
                    tint = Color(0xFFEF4444)
                )
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
                settings.userAgentString = if (cleanDeviceUa.isNotBlank()) {
                    cleanDeviceUa
                } else {
                    config.userAgent ?: defaultUa
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = false

                    override fun onPageFinished(view: WebView, url: String?) {
                        onSettled(url)
                    }
                }
                loadUrl(config.loginUrl)
            }.also { webViewRef = it }
        }
    )
}
