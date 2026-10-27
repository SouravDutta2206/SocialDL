package com.socialdl.src.core.cookies

import com.socialdl.src.core.model.Platform

/**
 * Parameters for one WebView cookie-capture flow (§2.6 scope note): the controller
 * and screen are generic over these values — wiring up a second WebView-login-backed
 * platform is a config addition, not a rewrite.
 */
data class CookieCaptureConfig(
    /** Platform whose encrypted jar receives the harvested cookies. */
    val platform: Platform,
    /** Full login page the WebView loads. */
    val loginUrl: String,
    /** Origin the flow auto-completes on once [markerCookies] are present there. */
    val successOrigin: String,
    /** Session cookies whose presence in [successOrigin]'s jar means "logged in". */
    val markerCookies: List<String>,
    /**
     * Origins to harvest `CookieManager.getCookie()` from after login, each labeled
     * with the Netscape domain its cookies are written under.
     */
    val harvests: List<NetscapeCookieJarWriter.Harvest>,
    /** UA the WebView must present when it should match the downstream client. */
    val userAgent: String?,
    /**
     * Navigation allowlist for the capture WebView. Anything outside these hosts
     * (and their subdomains) is opened externally instead of inside the
     * unaddressable full-screen WebView.
     */
    val allowedHosts: List<String>,
    /** Toolbar copy shown above the WebView. */
    val title: String,
    val subtitle: String,
    /**
     * When set, the WebView presents exactly this UA instead of the cleaned device
     * UA. Instagram sessions are validated against the gallery-dl desktop UA, so
     * capture must use the same string.
     */
    val userAgentOverride: String? = null,
)

/** YouTube's capture configuration. */
object YoutubeCookieCapture {

    /**
     * Plain YouTube home — NOT `accounts.google.com/ServiceLogin...`, which Google
     * aggressively rejects in embedded WebViews ("This browser or app may not be
     * secure"). The user taps the profile icon and signs in exactly like they would
     * in a normal browser; the flow lands on the accounts pages mid-navigation, which
     * the wv-stripped UA (see CookieCaptureScreen) keeps unblocked.
     */
    const val LOGIN_URL = "https://www.youtube.com/"
    const val SUCCESS_ORIGIN = "https://www.youtube.com"

    /** The pair yt-dlp itself looks for to decide a jar is a logged-in session. */
    val MARKER_COOKIES = listOf("SAPISID", "__Secure-3PAPISID")

    val HARVESTS = listOf(
        NetscapeCookieJarWriter.Harvest(
            url = "https://www.youtube.com",
            cookieDomain = ".youtube.com"
        ),
        NetscapeCookieJarWriter.Harvest(
            url = "https://accounts.google.com",
            cookieDomain = ".accounts.google.com"
        ),
        NetscapeCookieJarWriter.Harvest(
            url = "https://www.google.com",
            cookieDomain = ".google.com"
        ),
    )

    /** Builds the default YouTube config, stamped with the [userAgent] probe result. */
    fun config(userAgent: String?): CookieCaptureConfig = CookieCaptureConfig(
        platform = Platform.YOUTUBE,
        loginUrl = LOGIN_URL,
        successOrigin = SUCCESS_ORIGIN,
        markerCookies = MARKER_COOKIES,
        harvests = HARVESTS,
        userAgent = userAgent,
        allowedHosts = listOf(
            "youtube.com",
            "google.com",
            "accounts.google.com",
            "myaccount.google.com",
        ),
        title = "Sign in with YouTube",
        subtitle = "Sign in normally: tap the profile icon and log in. SocialDL never sees your password.",
    )
}

/**
 * Instagram's capture configuration (Phase 5).
 *
 * Mirrors the Playwright reference that preceded the Android build: desktop Chrome
 * UA, instagram.com login page, and `sessionid` + `ds_user_id` as the signed-in
 * markers. gallery-dl consumes the resulting Netscape jar verbatim.
 */
object InstagramCookieCapture {

    const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
    const val SUCCESS_ORIGIN = "https://www.instagram.com"

    /** The pair gallery-dl's Instagram extractor keys on (cookies_names). */
    val MARKER_COOKIES = listOf("sessionid", "ds_user_id")

    val HARVESTS = listOf(
        NetscapeCookieJarWriter.Harvest(
            url = "https://www.instagram.com",
            cookieDomain = ".instagram.com"
        ),
    )

    /**
     * Desktop Chrome UA used by both the capture WebView and gallery-dl's requests
     * (passed to the bridge as `user_agent`). Instagram binds a session to the UA
     * that created it; using one string end-to-end avoids capture/use mismatches.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    fun config(): CookieCaptureConfig = CookieCaptureConfig(
        platform = Platform.INSTAGRAM,
        loginUrl = LOGIN_URL,
        successOrigin = SUCCESS_ORIGIN,
        markerCookies = MARKER_COOKIES,
        harvests = HARVESTS,
        userAgent = USER_AGENT,
        allowedHosts = listOf("instagram.com", "facebook.com"),
        title = "Sign in with Instagram",
        subtitle = "Log in normally (2FA included). SocialDL never sees your password; only cookies are stored encrypted.",
        userAgentOverride = USER_AGENT,
    )
}
