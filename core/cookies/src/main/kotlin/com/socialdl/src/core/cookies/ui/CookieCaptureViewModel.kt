package com.socialdl.src.core.cookies.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.cookies.CaptureOutcome
import com.socialdl.src.core.cookies.CookieCaptureConfig
import com.socialdl.src.core.cookies.CookieCaptureController
import com.socialdl.src.core.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the full-screen WebView cookie capture (§2.6): resolves the probed-UA
 * config once, auto-detects login completion from `onPageFinished`, and handles
 * the manual "I'm signed in" + Cancel fallbacks.
 */
@HiltViewModel
class CookieCaptureViewModel @Inject constructor(
    private val controller: CookieCaptureController
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
    )

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var lastPageUrl: String? = null

    private var initializedFor: Platform? = null

    /** Resolves the flow config once per platform (the route dictates the platform). */
    fun initialize(platform: Platform) {
        if (initializedFor == platform) return
        initializedFor = platform
        viewModelScope.launch {
            runCatching { controller.config(platform) }
                .onSuccess { config ->
                    _uiState.update { it.copy(config = config) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            message = error.message
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
        _uiState.update { it.copy(cancelled = true) }
    }

    fun acknowledgeResult() {
        _uiState.update { it.copy(result = null) }
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
