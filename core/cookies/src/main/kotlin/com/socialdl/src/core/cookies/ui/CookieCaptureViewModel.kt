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
        /** One-shot terminal state; the screen acknowledges it, then routes back. */
        val result: CaptureOutcome? = null,
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
            _uiState.update { it.copy(config = controller.config(platform)) }
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

    private fun harvest(manual: Boolean = false) {
        val config = _uiState.value.config ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val outcome = if (manual) {
                val url = lastPageUrl
                controller.manualCapture(config, url)
            } else {
                controller.harvestAndStore(config)
            }
            _uiState.update { it.copy(busy = false, result = outcome) }
        }
    }
}
