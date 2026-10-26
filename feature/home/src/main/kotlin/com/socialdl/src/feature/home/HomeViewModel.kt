package com.socialdl.src.feature.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.UiMessageBus
import com.socialdl.src.core.download.DownloadManager
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.ExtractorRegistry
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.UrlPlatformResolver
import com.socialdl.src.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val detectedPlatform: Platform? = null,
    val mediaItems: List<MediaItem> = emptyList(),
    /**
     * Set when the last extraction hit `ExtractionError.AuthWallDetected` — drives
     * the "Sign in with <platform>" card CTA (§2.6 recapture flow). Cleared on the
     * next extraction attempt, sign-in completion, or failure of another kind.
     */
    val authWallUrl: String? = null,

    /**
     * False for single-format platforms (Instagram/Twitter/Threads): the card
     * action downloads the best format directly instead of opening the picker.
     * Mirrors the extractor's [com.socialdl.src.extraction.api.PlatformCapabilities].
     */
    val showsFormatPicker: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val extractorRegistry: ExtractorRegistry,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    private val tag = "HomeViewModel"

    // Display copy lives in res/values/strings.xml (R-F.2); the VM resolves it at
    // string-build time. A deeper UiText-based redesign is deferred (see plan R-F.2).
    private fun stringRes(@StringRes resId: Int, vararg args: Any): String =
        appContext.getString(resId, *args)

    /** One-shot user messages (toasts); replaces the old UiState.infoMessage field. */
    private val messageBus = UiMessageBus()
    val messages = messageBus.events

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _resolvingItemId = MutableStateFlow<String?>(null)
    val resolvingItemId: StateFlow<String?> = _resolvingItemId.asStateFlow()

    private var extractionJob: Job? = null

    fun resolveItemFormats(item: MediaItem, onResolved: (MediaItem) -> Unit) {
        if (!item.isPlaceholder) {
            onResolved(item)
            return
        }

        viewModelScope.launch {
            _resolvingItemId.value = item.sourceUrl
            val extractor = extractorRegistry.resolve(item.sourceUrl)
            if (extractor != null) {
                when (val result = extractor.extract(item.sourceUrl)) {
                    is ExtractionResult.Success -> {
                        val resolvedItem = result.items.firstOrNull()?.copy(
                            carouselIndex = item.carouselIndex
                        ) ?: item
                        _uiState.update { state ->
                            val updatedList = state.mediaItems.map {
                                // Match the exact carousel slot; matching only by sourceUrl would collapse
                                // N carousel cards into one and crash LazyColumn (duplicate keys).
                                if (it.sourceUrl == item.sourceUrl && it.carouselIndex == item.carouselIndex) resolvedItem else it
                            }
                            state.copy(mediaItems = updatedList)
                        }
                        _resolvingItemId.value = null
                        onResolved(resolvedItem)
                    }
                    is ExtractionResult.Error -> {
                        _resolvingItemId.value = null
                        messageBus.post(
                            stringRes(R.string.home_error_presets_failed, result.cause.message),
                            isError = true
                        )
                    }
                }
            } else {
                _resolvingItemId.value = null
                onResolved(item)
            }
        }
    }

    suspend fun startDownload(
        mediaItem: MediaItem,
        selectedFormat: MediaFormat,
        container: String? = null,
        customTitle: String? = null,
        customAuthor: String? = null
    ): String {
        return downloadManager.enqueueDownload(
            mediaItem = mediaItem,
            selectedFormat = selectedFormat,
            container = container,
            customTitle = customTitle,
            customAuthor = customAuthor
        )
    }

    suspend fun startBatchDownload(items: List<Pair<MediaItem, MediaFormat>>): List<String> {
        val groupId = java.util.UUID.randomUUID().toString()
        // Enqueue items individually: a mid-loop failure must not silently lose the
        // remaining items after the caller announced selectedPairs.size. Return the
        // ACTUAL enqueued IDs so toasts report a truthful count.
        val enqueued = mutableListOf<String>()
        items.forEach { (item, format) ->
            try {
                enqueued += downloadManager.enqueueDownload(
                    mediaItem = item,
                    selectedFormat = format,
                    container = format.container,
                    customTitle = item.title,
                    customAuthor = item.author,
                    groupId = groupId
                )
            } catch (e: Exception) {
                Logger.e(tag, "Failed to enqueue batch item '${item.title}'", e)
            }
        }
        return enqueued
    }

    /**
     * Single-tap download for platforms whose extractor sets
     * `showsFormatPicker = false` (Instagram/Twitter/Threads). "Best available" is
     * already decided at extraction time — one format per media element — so the UI
     * never renders a chooser for these sources.
     */
    fun startDirectDownload(item: MediaItem) {
        val format = item.videoFormats.firstOrNull()
            ?: item.imageFormats.firstOrNull()
            ?: item.audioFormats.firstOrNull()
        if (format == null) {
            messageBus.post("This post has no downloadable media", isError = true)
            return
        }
        viewModelScope.launch {
            try {
                downloadManager.enqueueDownload(mediaItem = item, selectedFormat = format)
                messageBus.post("Download queued: ${item.title} (${format.label})")
            } catch (e: Exception) {
                Logger.e(tag, "Failed to enqueue direct download for '${item.title}'", e)
                messageBus.post("Couldn't queue download: ${e.message ?: "unknown error"}", isError = true)
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        val platform = if (newQuery.isNotBlank()) {
            UrlPlatformResolver.resolvePlatform(newQuery)
        } else {
            null
        }
        _uiState.update { it.copy(query = newQuery, detectedPlatform = platform) }
    }

    fun submitQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        val platform = UrlPlatformResolver.resolvePlatform(trimmed)
        if (platform != Platform.UNKNOWN) {
            val extractor = extractorRegistry.resolve(trimmed)
            if (extractor != null) {
                Logger.i(tag, "submitQuery: url='$trimmed' platform=$platform routing to ${extractor::class.simpleName}")
                extractMedia(extractor, trimmed)
            } else {
                Logger.w(tag, "submitQuery: url='$trimmed' platform=$platform but no extractor found")
                messageBus.post(stringRes(R.string.home_error_no_extractor, platform), isError = true)
            }
        } else {
            messageBus.post(stringRes(R.string.home_error_unsupported_link), isError = true)
        }
    }

    private fun extractMedia(extractor: MediaExtractor, url: String) {
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, mediaItems = emptyList(), authWallUrl = null) }
            try {
                when (val result = extractor.extract(url)) {
                    is ExtractionResult.Success -> {
                        Logger.i(tag, "Successfully extracted ${result.items.size} items from $url")
                        _uiState.update { current ->
                            current.copy(
                                isLoading = false,
                                query = "", // Clear search bar on successful extraction
                                showsFormatPicker = extractor.capabilities.showsFormatPicker,
                                // Defense-in-depth: guarantee unique (sourceUrl, carouselIndex) pairs so
                                // LazyColumn keys can never collide.
                                mediaItems = result.items.distinctBy { "${it.sourceUrl}_${it.carouselIndex}" }
                            )
                        }
                        messageBus.post(
                            stringRes(
                                R.string.home_info_loaded,
                                result.items.firstOrNull()?.title ?: "media"
                            )
                        )
                    }
                    is ExtractionResult.Error -> {
                        val errMsg = result.cause.message
                        Logger.w(tag, "Extraction failed for $url: $errMsg")
                        // §2.6 recapture flow: only YouTube surfaces the sign-in card.
                        // Instagram auth walls are resolved from Settings → Instagram login;
                        // Home stays a pure search surface (product decision, Oct 2026).
                        val authWallPlatform =
                            (result.cause as? ExtractionError.AuthWallDetected)?.platform
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                mediaItems = emptyList(),
                                showsFormatPicker = true,
                                authWallUrl = if (authWallPlatform != null &&
                                    authWallPlatform != Platform.INSTAGRAM
                                ) url else null
                            )
                        }
                        messageBus.post(errMsg ?: "Extraction failed", isError = true)
                    }
                }
            } catch (e: CancellationException) {
                Logger.d(tag, "Extraction cancelled for $url")
            } catch (e: Exception) {
                Logger.e(tag, "Unexpected error extracting $url", e)
                _uiState.update {
                    it.copy(isLoading = false, mediaItems = emptyList())
                }
                messageBus.post(
                    stringRes(R.string.home_error_extraction, e.message ?: "unknown"),
                    isError = true
                )
            }
        }
    }

    /**
     * Called when the YouTube cookie-capture route returned to Home carrying
     * `COOKIE_CAPTURE_RESULT_KEY` = true: re-run extraction on the URL that
     * originally hit the wall, automatically — no manual round-trip.
     */
    fun onAuthSignInCompleted() {
        val pending = _uiState.value.authWallUrl ?: return
        _uiState.update { it.copy(authWallUrl = null) }
        val extractor = extractorRegistry.resolve(pending)
        if (extractor == null) {
            // Don't silently drop the pending retry — tell the user to re-paste.
            Logger.w(tag, "Sign-in completed but no extractor resolved for $pending")
            messageBus.post("Signed in — paste the link again to download")
            return
        }
        Logger.i(tag, "Sign-in completed; auto-retrying extraction of $pending")
        extractMedia(extractor, pending)
    }
}
