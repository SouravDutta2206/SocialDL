package com.socialdl.src.extraction.api

import com.socialdl.src.core.model.Platform
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionEnvelopeTest {

    @Test
    fun `success payload is the envelope with no wrapper`() {
        val envelope = decodeEnvelope("""{"shortcode":"abc","items":[{"url":"https://cdn/x.jpg"}]}""")
        assertTrue(envelope is ExtractionEnvelope.Success)
        val obj = (envelope as ExtractionEnvelope.Success).payload.jsonObject
        assertEquals("abc", obj["shortcode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `failure envelope carries kind detail and debug log`() {
        val envelope = decodeEnvelope(
            """{"error":"rate_limited","detail":"429","debug_log":"line1\nline2"}"""
        ) as ExtractionEnvelope.Failure
        assertEquals("rate_limited", envelope.kind)
        assertEquals("429", envelope.detail)
        assertEquals("line1\nline2", envelope.debugLog)
    }

    @Test
    fun `malformed payload becomes a null-kind failure`() {
        val envelope = decodeEnvelope("not json") as ExtractionEnvelope.Failure
        assertEquals(null, envelope.kind)
    }

    @Test
    fun `error kinds map to typed errors`() {
        val platform = Platform.INSTAGRAM
        assertTrue(mapErrorKind("auth_wall", "login", platform) is ExtractionError.AuthWallDetected)
        assertTrue(mapErrorKind("rate_limited", null, platform) is ExtractionError.RateLimited)
        assertTrue(mapErrorKind("network", "dns", platform) is ExtractionError.NetworkFailure)
        assertTrue(mapErrorKind("unavailable", "gone", platform) is ExtractionError.ContentUnavailable)
        assertTrue(mapErrorKind(null, null, platform) is ExtractionError.ContentUnavailable)
    }

    @Test
    fun `auth wall carries the platform for recapture routing`() {
        val error = mapErrorKind("auth_wall", "login", Platform.INSTAGRAM)
            as ExtractionError.AuthWallDetected
        assertEquals(Platform.INSTAGRAM, error.platform)
    }
}
