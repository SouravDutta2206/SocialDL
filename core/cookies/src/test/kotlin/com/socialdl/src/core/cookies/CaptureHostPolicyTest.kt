package com.socialdl.src.core.cookies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHostPolicyTest {

    private val youtubeHosts = listOf("youtube.com", "youtu.be", "google.com", "gstatic.com")
    private val patterns = listOf(CaptureHostPolicy.GOOGLE_COUNTRY_DOMAIN)

    private fun allowed(host: String?): Boolean =
        CaptureHostPolicy.isAllowedHost(host, youtubeHosts, patterns)

    // ---- allowed hosts ----

    @Test
    fun allowsExactHostAndSubdomains() {
        assertTrue(allowed("www.youtube.com"))
        assertTrue(allowed("m.youtube.com"))
        assertTrue(allowed("youtube.com"))
        assertTrue(allowed("accounts.youtube.com"))
        assertTrue(allowed("accounts.google.com"))
    }

    @Test
    fun allowsGoogleCountryDomains() {
        // The post-2FA redirect can land on a ccTLD account domain.
        assertTrue(allowed("accounts.google.co.in"))
        assertTrue(allowed("myaccount.google.co.in"))
        assertTrue(allowed("google.co.in"))
        assertTrue(allowed("www.google.co.uk"))
        assertTrue(allowed("accounts.google.com.au"))
        assertTrue(allowed("www.google.com.br"))
    }

    @Test
    fun rejectsLookalikeAndUnrelatedHosts() {
        assertFalse(allowed("evil.com"))
        assertFalse(allowed("notyoutube.com"))
        assertFalse(allowed("youtube.com.evil.com"))
        assertFalse(allowed("google.co.in.evil.com"))
        assertFalse(allowed("google.evil.com"))
        assertFalse(allowed("youtube.co"))
        assertFalse(allowed(""))
        assertFalse(allowed(null))
    }

    @Test
    fun toleratesCaseAndTrailingRootDot() {
        assertTrue(allowed("WWW.YouTube.COM."))
        assertEquals("www.youtube.com", CaptureHostPolicy.normalizeHost(" WWW.YouTube.COM. "))
    }

    // ---- URL host extraction ----

    @Test
    fun hostOf_stripsSchemePortUserInfoAndPath() {
        assertEquals("www.youtube.com", CaptureHostPolicy.hostOf("https://www.youtube.com/"))
        assertEquals("accounts.google.com", CaptureHostPolicy.hostOf("https://accounts.google.com:443/v3/signin?hl=en"))
        assertEquals("youtube.com", CaptureHostPolicy.hostOf("youtube.com/feed/subscriptions"))
        assertEquals("youtube.com", CaptureHostPolicy.hostOf("https://user:pass@youtube.com/watch?v=1"))
        assertNull(CaptureHostPolicy.hostOf(null))
        assertNull(CaptureHostPolicy.hostOf("   "))
    }

    // ---- success-origin matching ----

    @Test
    fun successHost_acceptsYoutubeFamilyOnly() {
        val origin = "https://www.youtube.com"
        assertTrue(CaptureHostPolicy.matchesSuccessHost("www.youtube.com", origin))
        assertTrue(CaptureHostPolicy.matchesSuccessHost("youtube.com", origin))
        assertTrue(CaptureHostPolicy.matchesSuccessHost("m.youtube.com", origin))
        assertTrue(CaptureHostPolicy.matchesSuccessHost("music.youtube.com", origin))

        assertFalse(CaptureHostPolicy.matchesSuccessHost("accounts.google.com", origin))
        assertFalse(CaptureHostPolicy.matchesSuccessHost("www.google.com", origin))
        assertFalse(CaptureHostPolicy.matchesSuccessHost("youtube.com.evil.com", origin))
        assertFalse(CaptureHostPolicy.matchesSuccessHost("notyoutube.com", origin))
        assertFalse(CaptureHostPolicy.matchesSuccessHost(null, origin))
    }

    @Test
    fun googleCountryPattern_matchesOnlyGoogleTlds() {
        val pattern = CaptureHostPolicy.GOOGLE_COUNTRY_DOMAIN
        assertTrue(pattern.matches("google.co.in"))
        assertTrue(pattern.matches("accounts.google.com"))
        assertTrue(pattern.matches("www.google.co.uk"))
        assertTrue(pattern.matches("google.com.au"))
        assertFalse(pattern.matches("google.evil.com"))
        assertFalse(pattern.matches("notgoogle.com"))
        assertFalse(pattern.matches("google.co.in.evil.com"))
    }

    // ---- platform configs ----

    @Test
    fun youtubeConfig_coversCctldRedirectsAndGoogleAssets() {
        val config = YoutubeCookieCapture.config(userAgent = null)
        listOf(
            "www.youtube.com",
            "m.youtube.com",
            "accounts.google.com",
            "accounts.google.co.in",
            "www.google.co.uk",
            "www.gstatic.com",
            "www.recaptcha.net",
        ).forEach { host ->
            assertTrue(
                "expected $host to be allowed",
                CaptureHostPolicy.isAllowedHost(host, config.allowedHosts, config.allowedHostPatterns)
            )
        }
    }

    @Test
    fun instagramConfig_coversLoginOauthAndMetaHosts() {
        val config = InstagramCookieCapture.config()
        listOf(
            "www.instagram.com",
            "m.instagram.com",
            "i.instagram.com",
            "www.facebook.com",
            "m.facebook.com",
            "www.meta.com",
            "static.cdninstagram.com",
            "static.xx.fbcdn.net",
            "connect.facebook.net",
        ).forEach { host ->
            assertTrue(
                "expected $host to be allowed",
                CaptureHostPolicy.isAllowedHost(host, config.allowedHosts, config.allowedHostPatterns)
            )
        }
        assertFalse(
            CaptureHostPolicy.isAllowedHost(
                "instagram.com.evil.com",
                config.allowedHosts,
                config.allowedHostPatterns
            )
        )
    }
}
