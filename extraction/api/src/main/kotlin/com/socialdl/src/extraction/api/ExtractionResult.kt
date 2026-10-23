package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.MediaItem

/**
 * Result returned by every [MediaExtractor].
 */
sealed interface ExtractionResult {
    data class Success(val items: List<MediaItem>) : ExtractionResult
    data class Error(val cause: ExtractionError) : ExtractionResult
}
