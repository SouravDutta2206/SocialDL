package com.socialdl.src.extraction.api

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry holding all registered [MediaExtractor] implementations.
 * Injected with a Hilt multibinding set of extractors.
 */
@Singleton
class ExtractorRegistry @Inject constructor(
    private val extractors: Set<@JvmSuppressWildcards MediaExtractor>
) {
    /**
     * Resolves the first [MediaExtractor] supporting the given URL.
     * Returns null if no extractor supports it.
     */
    fun resolve(url: String): MediaExtractor? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return extractors.firstOrNull { it.supports(trimmed) }
    }

    /**
     * Returns all currently registered extractors.
     */
    fun allExtractors(): List<MediaExtractor> = extractors.toList()
}
