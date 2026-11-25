package com.socialdl.src.extraction.api

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
        /**
         * The raw failure object, kept so platform-specific diagnostics that ride
         * on the envelope (e.g. yt-dlp's `js_diagnostics`) can still be read.
         * Null when the response was not even a JSON object.
         */
        val raw: JsonObject? = null,
    ) : ExtractionEnvelope
}

/**
 * Shared lenient JSON for every bridge payload: unknown keys are ignored so a
 * bridge-side addition is never a breaking change, and lenient/coercing parsing
 * tolerates the messy values Python frequently emits.
 */
val ExtractionJson = Json {
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
    val obj = runCatching { ExtractionJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return ExtractionEnvelope.Failure(null, "Malformed bridge response", null)

    // `JsonNull.content` is the literal string "null", which used to turn an
    // explicit `"error": null` into a bogus Failure("null"); an explicit null is
    // simply an absent key here.
    val error = obj["error"]?.takeUnless { it is JsonNull }
        ?: return ExtractionEnvelope.Success(obj)

    return ExtractionEnvelope.Failure(
        kind = runCatching { error.jsonPrimitive.contentOrNull }.getOrNull(),
        detail = obj["detail"]?.takeUnless { it is JsonNull }
            ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
        debugLog = obj["debug_log"]?.takeUnless { it is JsonNull }
            ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
        raw = obj,
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
    "no_media" -> ExtractionError.NoDownloadableMedia(
        detail ?: "This post contains no downloadable media."
    )
    else -> ExtractionError.ContentUnavailable(detail ?: "Content unavailable")
}

/**
 * Shared fetch → decode → map → typed-error runner for Python-bridge extractors.
 *
 * Each extractor only supplies how to fetch the raw JSON and how to map a
 * success payload; timeout, cancellation propagation, bridge-exception mapping,
 * failure-envelope logging, and error typing live here so the platforms cannot
 * drift apart.
 *
 * @param tag Logger tag for this extractor.
 * @param platform platform used for typed-error routing and log copy.
 * @param url original (user-facing) URL, used in log lines only.
 * @param bridgeErrorMessage user-facing message when the bridge itself throws.
 * @param isBridgeException true for exceptions that mean "Python failed", mapped
 *   to a generic parse failure instead of leaking a traceback into the UI.
 * @param onFailureEnvelope extra diagnostics hook (e.g. yt-dlp JS diagnostics).
 * @param fetchRawJson performs the bridge call (cookie hand-off included).
 * @param mapSuccess maps a decoded success payload to media items.
 */
suspend fun runEnvelopeExtraction(
    tag: String,
    platform: Platform,
    url: String,
    bridgeErrorMessage: String,
    isBridgeException: (Throwable) -> Boolean = { false },
    onFailureEnvelope: (ExtractionEnvelope.Failure) -> Unit = {},
    fetchRawJson: suspend () -> String,
    mapSuccess: suspend (JsonElement) -> ExtractionResult,
): ExtractionResult {
    return try {
        val rawJson = fetchRawJson()
        when (val envelope = decodeEnvelope(rawJson)) {
            is ExtractionEnvelope.Failure -> {
                onFailureEnvelope(envelope)
                Logger.w(
                    tag,
                    "${platform.displayName} error envelope: ${envelope.kind} | ${envelope.detail}"
                )
                envelope.debugLog?.takeIf { it.isNotBlank() }?.let {
                    Logger.w(tag, "${platform.displayName} bridge log:\n$it")
                }
                val error = if (envelope.raw == null) {
                    ExtractionError.ParsingFailed(envelope.detail ?: bridgeErrorMessage)
                } else {
                    mapErrorKind(envelope.kind, envelope.detail, platform)
                }
                ExtractionResult.Error(error)
            }

            is ExtractionEnvelope.Success -> mapSuccess(envelope.payload)
        }
    } catch (e: TimeoutCancellationException) {
        Logger.e(tag, "Extraction timed out for $url", e)
        ExtractionResult.Error(ExtractionError.Timeout())
    } catch (e: CancellationException) {
        // User cancel or structured cancellation — never convert to a user-facing
        // failure; let the coroutine unwind.
        throw e
    } catch (e: Exception) {
        Logger.e(tag, "Extraction failed for $url", e)
        if (isBridgeException(e)) {
            ExtractionResult.Error(ExtractionError.ParsingFailed(bridgeErrorMessage))
        } else {
            ExtractionResult.Error(
                ExtractionError.ParsingFailed(e.message ?: "Unknown extraction error")
            )
        }
    }
}
