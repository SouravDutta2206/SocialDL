package com.socialdl.src.core.cookies

/**
 * Parameters for one WebView cookie-capture flow (§2.6 scope note): the controller
 * is generic over these values, not hardcoded to YouTube internals — wiring up a
 * second WebView-login-backed platform later is a config addition, not a rewrite.
 */
data class CookieCaptureConfig(
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
    /** UA the WebView must present — probed from the installed yt-dlp (§2.6). */
    val userAgent: String?,
)

/** YouTube's capture configuration — the only concrete config shipped in v1. */
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
        loginUrl = LOGIN_URL,
        successOrigin = SUCCESS_ORIGIN,
        markerCookies = MARKER_COOKIES,
        harvests = HARVESTS,
        userAgent = userAgent,
    )
}
