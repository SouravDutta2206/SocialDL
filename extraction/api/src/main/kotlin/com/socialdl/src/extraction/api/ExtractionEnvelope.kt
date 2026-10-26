package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one wire dialect every Python bridge speaks (§2.6 / Phase 5):
 *
 * - success → the payload itself is the envelope (no wrapper);
 * - failure → `{"error": kind, "detail": ..., "debug_log": ...}`.
 *
 * This decoder separates the two shapes so each extractor's success DTO stays
 * success-only — unlike `YtDlpNormalizedResponse`, which historically doubled as
 * both and therefore could not be reused by a second bridge.
 */
sealed interface ExtractionEnvelope {
    data class Success(val payload: JsonElement) : ExtractionEnvelope

    data class Failure(
        val kind: String?,
        val detail: String?,
        val debugLog: String?,
    ) : ExtractionEnvelope
}

private val envelopeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Decodes a raw bridge response into [ExtractionEnvelope]. Never throws: a
 * malformed payload becomes a [ExtractionEnvelope.Failure] with a null kind,
 * which [mapErrorKind] renders as a generic content error.
 */
fun decodeEnvelope(raw: String): ExtractionEnvelope {
    val obj = runCatching { envelopeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return ExtractionEnvelope.Failure(null, "Malformed bridge response", null)

    val error = obj["error"]
        ?: return ExtractionEnvelope.Success(obj)

    return ExtractionEnvelope.Failure(
        kind = runCatching { error.jsonPrimitive.content }.getOrNull(),
        detail = obj["detail"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
        debugLog = obj["debug_log"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
    )
}

/**
 * Single kind → typed-error mapping shared by every extractor, so error UX stays
 * consistent as platforms are added (Phase 12).
 */
fun mapErrorKind(kind: String?, detail: String?, platform: Platform): ExtractionError = when (kind) {
    "auth_wall" -> ExtractionError.AuthWallDetected(platform, detail ?: "Sign-in required")
    "rate_limited" -> ExtractionError.RateLimited()
    "network" -> ExtractionError.NetworkFailure(
        message = detail ?: "Network failure during extraction"
    )
    else -> ExtractionError.ContentUnavailable(detail ?: "Content unavailable")
}
