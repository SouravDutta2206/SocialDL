package com.socialdl.src.feature.home

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.UiMessageBus
import com.socialdl.src.core.cookies.supportsCookieCapture
import com.socialdl.src.core.download.DownloadManager
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.model.defaultFormat
import com.socialdl.src.core.model.uiKey
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val mediaItems: List<MediaItem> = emptyList(),
    /**
     * Set when the last extraction hit `ExtractionError.AuthWallDetected` on a
     * platform with a capture route — drives the "Sign in with <platform>" card
     * CTA (§2.6 recapture flow). Cleared on the next extraction attempt,
     * sign-in completion, or failure of another kind.
     */
    val authWallUrl: String? = null,

    /**
     * Platform that can resolve [authWallUrl] through its WebView cookie capture
     * route. Null for platforms without a capture flow (e.g. UNKNOWN),
     * which keep the generic error only.
     */
    val authWallPlatform: Platform? = null,

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

    /**
     * In-flight on-demand placeholder resolution. Only one may run at a time: a new
     * tap or search cancels the previous one so a slow response cannot reopen a
     * stale bottom sheet or overwrite the card that is currently resolving.
     */
    private var resolveJob: Job? = null

    fun resolveItemFormats(item: MediaItem, itemKey: String, onResolved: (MediaItem) -> Unit) {
        // Another card (or the same one, double-tapped) supersedes this resolution.
        resolveJob?.cancel()

        if (!item.isPlaceholder) {
            onResolved(item)
            return
        }

        // Publish the spinner before extraction starts so the card reacts immediately.
        _resolvingItemId.value = itemKey
        resolveJob = viewModelScope.launch {
            try {
                val extractor = extractorRegistry.resolve(item.sourceUrl)
                if (extractor == null) {
                    if (!isActive) return@launch
                    onResolved(item)
                    return@launch
                }
                when (val result = extractor.extract(item.sourceUrl)) {
                    is ExtractionResult.Success -> {
                        // Extraction is cancellable and may have been superseded while
                        // suspended; never publish results for a stale request.
                        if (!isActive) return@launch
                        val resolvedItem = result.items.firstOrNull()?.copy(
                            carouselIndex = item.carouselIndex
                        ) ?: item
                        _uiState.update { state ->
                            val updatedList = state.mediaItems.mapIndexed { idx, it ->
                                // Match the exact card key; matching only by sourceUrl would collapse
                                // N carousel cards into one and crash LazyColumn (duplicate keys).
                                if (it.uiKey(fallbackIndex = idx) == itemKey) resolvedItem else it
                            }
                            state.copy(mediaItems = updatedList)
                        }
                        if (!isActive) return@launch
                        onResolved(resolvedItem)
                    }
                    is ExtractionResult.Error -> {
                        if (!isActive) return@launch
                        messageBus.post(
                            stringRes(R.string.home_error_presets_failed, result.cause.message),
                            isError = true
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "Failed to resolve formats for '${item.title}'", e)
                if (isActive) {
                    messageBus.post(
                        stringRes(R.string.home_error_presets_failed, e.message ?: "unknown error"),
                        isError = true
                    )
                }
            } finally {
                // Never leave the card spinner stuck, even if extraction throws.
                // Only clear our own key: a newer resolution may already own the spinner.
                if (_resolvingItemId.value == itemKey) _resolvingItemId.value = null
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
        // Enqueue items individually: a mid-loop failure must not silently lose the
        // remaining items after the caller announced selectedPairs.size. Return the
        // ACTUAL enqueued IDs so toasts report a truthful count.
        val enqueued = mutableListOf<String>()
        items.forEach { (item, format) ->
            try {
                enqueued += downloadManager.enqueueDownload(
                    mediaItem = item,
                    selectedFormat = format,
                    // Playlist placeholders carry a synthetic "MP4" container; passing
                    // it would lock the worker to MP4 before yt-dlp resolves the real
                    // format. Let the worker adopt the JIT-resolved native container.
                    container = format.container.takeUnless { format.isPlaceholder },
                    customTitle = item.title,
                    customAuthor = item.author,
                )
            } catch (e: CancellationException) {
                // The batch scope is going away; stop enqueuing instead of logging
                // each remaining item as a failure.
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "Failed to enqueue batch item '${item.title}'", e)
            }
        }
        return enqueued
    }

    /**
     * ViewModel-scoped enqueue for the format sheet. Running the suspend enqueue here
     * (instead of a composable's coroutine scope) lets the download survive rotation
     * or navigation away from Home, and reports outcomes through [messageBus].
     */
    fun enqueueSingleDownload(
        mediaItem: MediaItem,
        selectedFormat: MediaFormat,
        container: String? = null,
        customTitle: String? = null,
        customAuthor: String? = null,
    ) {
        viewModelScope.launch {
            try {
                startDownload(mediaItem, selectedFormat, container, customTitle, customAuthor)
                messageBus.post(
                    stringRes(
                        R.string.home_download_queued,
                        customTitle ?: mediaItem.title,
                        selectedFormat.label
                    )
                )
            } catch (e: CancellationException) {
                // ViewModel cleared mid-enqueue: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "Failed to enqueue download for '${mediaItem.title}'", e)
                messageBus.post(
                    stringRes(R.string.home_error_queue_failed, e.message ?: "unknown error"),
                    isError = true
                )
            }
        }
    }

    /**
     * ViewModel-scoped batch enqueue. Reports the ACTUAL enqueued count: a mid-loop
     * failure must not overstate what landed in the queue.
     */
    fun enqueueBatchDownload(items: List<Pair<MediaItem, MediaFormat>>) {
        viewModelScope.launch {
            try {
                val taskIds = startBatchDownload(items)
                if (taskIds.isEmpty()) {
                    messageBus.post(stringRes(R.string.home_batch_none_queued), isError = true)
                } else {
                    messageBus.post(
                        if (taskIds.size == 1) stringRes(R.string.home_batch_queued_one)
                        else stringRes(R.string.home_batch_queued_many, taskIds.size)
                    )
                }
            } catch (e: CancellationException) {
                // ViewModel cleared mid-batch: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "Failed to enqueue batch download", e)
                messageBus.post(
                    stringRes(R.string.home_error_batch_queue_failed, e.message ?: "unknown error"),
                    isError = true
                )
            }
        }
    }

    /**
     * Single-tap download for platforms whose extractor sets
     * `showsFormatPicker = false` (Instagram/Twitter/Threads). "Best available" is
     * already decided at extraction time — one format per media element — so the UI
     * never renders a chooser for these sources.
     */
    fun startDirectDownload(item: MediaItem) {
        val format = item.defaultFormat()
        if (format == null) {
            messageBus.post(stringRes(R.string.home_error_no_media), isError = true)
            return
        }
        viewModelScope.launch {
            try {
                downloadManager.enqueueDownload(mediaItem = item, selectedFormat = format)
                messageBus.post(stringRes(R.string.home_download_queued, item.title, format.label))
            } catch (e: CancellationException) {
                // ViewModel cleared mid-enqueue: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "Failed to enqueue direct download for '${item.title}'", e)
                messageBus.post(
                    stringRes(R.string.home_error_queue_failed, e.message ?: "unknown error"),
                    isError = true
                )
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
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
        // A new search invalidates any placeholder resolution from the old result list.
        resolveJob?.cancel()
        _resolvingItemId.value = null
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    mediaItems = emptyList(),
                    authWallUrl = null,
                    authWallPlatform = null
                )
            }
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
                                mediaItems = result.items.distinctBy { it.uiKey() }
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
                        // §2.6 recapture flow: YouTube, Instagram, X and Threads have a
                        // WebView capture route with auto-retry on success.
                        val authWallPlatform =
                            (result.cause as? ExtractionError.AuthWallDetected)
                                ?.platform
                                ?.takeIf { supportsCookieCapture(it) }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                mediaItems = emptyList(),
                                showsFormatPicker = true,
                                authWallUrl = url.takeIf { authWallPlatform != null },
                                authWallPlatform = authWallPlatform
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
     * Called when a cookie-capture route returned to Home carrying
     * `COOKIE_CAPTURE_RESULT_KEY` = true: re-run extraction on the URL that
     * originally hit the wall, automatically — no manual round-trip. Works for
     * both the YouTube and Instagram capture routes because the extractor is
     * resolved from the pending URL.
     */
    fun onAuthSignInCompleted() {
        val pending = _uiState.value.authWallUrl ?: return
        _uiState.update { it.copy(authWallUrl = null, authWallPlatform = null) }
        val extractor = extractorRegistry.resolve(pending)
        if (extractor == null) {
            // Don't silently drop the pending retry — tell the user to re-paste.
            Logger.w(tag, "Sign-in completed but no extractor resolved for $pending")
            messageBus.post(stringRes(R.string.home_signed_in_repaste))
            return
        }
        Logger.i(tag, "Sign-in completed; auto-retrying extraction of $pending")
        extractMedia(extractor, pending)
    }
}
