package com.socialdl.src.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.python.PythonEngine
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.ExtractorRegistry
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.UrlPlatformResolver
import com.socialdl.src.core.download.DownloadManager
import com.socialdl.src.core.model.MediaFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val infoMessage: String? = null,
    val detectedPlatform: Platform? = null,
    val mediaItems: List<MediaItem> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val extractorRegistry: ExtractorRegistry,
    private val pythonEngine: PythonEngine,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    private val tag = "HomeViewModel"

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var extractionJob: Job? = null

    fun startDownload(
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

    fun startBatchDownload(items: List<Pair<MediaItem, MediaFormat>>): List<String> {
        val groupId = java.util.UUID.randomUUID().toString()
        return items.map { (item, format) ->
            downloadManager.enqueueDownload(
                mediaItem = item,
                selectedFormat = format,
                container = format.container,
                customTitle = item.title,
                customAuthor = item.author,
                groupId = groupId
            )
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
                _uiState.update { it.copy(infoMessage = "No extractor registered for $platform") }
            }
        } else {
            _uiState.update {
                it.copy(infoMessage = "Unsupported link. Please enter a valid YouTube, Instagram, Twitter, or Threads URL.")
            }
        }
    }

    private fun extractMedia(extractor: MediaExtractor, url: String) {
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, mediaItems = emptyList()) }
            try {
                when (val result = extractor.extract(url)) {
                    is ExtractionResult.Success -> {
                        Logger.i(tag, "Successfully extracted ${result.items.size} items from $url")
                        _uiState.update { current ->
                            current.copy(
                                isLoading = false,
                                query = "", // Clear search bar on successful extraction
                                mediaItems = result.items,
                                infoMessage = "Loaded: ${result.items.firstOrNull()?.title ?: "media"}"
                            )
                        }
                    }
                    is ExtractionResult.Error -> {
                        val errMsg = result.cause.message
                        Logger.w(tag, "Extraction failed for $url: $errMsg")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                mediaItems = emptyList(),
                                infoMessage = errMsg
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                Logger.d(tag, "Extraction cancelled for $url")
            } catch (e: Exception) {
                Logger.e(tag, "Unexpected error extracting $url", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        mediaItems = emptyList(),
                        infoMessage = "Extraction error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Executes Python smoke test end-to-end per Phase 2 exit criteria.
     */
    fun runPythonPing(msg: String = "ping") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = pythonEngine.run(
                    module = "common.ping",
                    function = "ping",
                    msg
                ) { json -> json }

                Logger.d(tag, "Python ping succeeded: $result")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        infoMessage = "Python Engine OK: $result"
                    )
                }
            } catch (e: Exception) {
                Logger.e(tag, "Python ping failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        infoMessage = "Python Error: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }
}
