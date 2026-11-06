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
    /**
     * Domain-family matchers for hosts that cannot be enumerated as suffixes,
     * e.g. Google's country domains (`google.co.in`, `google.com.au`).
     */
    val allowedHostPatterns: List<Regex> = emptyList(),
    /** Toolbar copy shown above the WebView. */
    val title: String,
    val subtitle: String,
    /**
     * When set, the WebView presents exactly this UA instead of the cleaned device
     * UA. Instagram sessions are validated against the gallery-dl desktop UA, so
     * capture must use the same string.
     */
    val userAgentOverride: String? = null,

    /**
     * Leave the WebView's own User-Agent untouched (no `wv` stripping).
     *
     * Why this matters: Android WebView only emits User-Agent *client hints*
     * (`Sec-CH-UA` request headers, `navigator.userAgentData`) when the app sends
     * the default UA string. Any override — including our cleaned device UA —
     * suppresses them, producing a fingerprint no real Chrome ever shows (a
     * Chrome-like UA with zero client hints). Platforms that risk-control logins
     * fingerprint exactly that mismatch.
     *
     * Since Chromium's 2024 UA reduction, the default WebView UA is already
     * Chrome-shaped (`Android 10; K`, `Chrome/X.0.0.0`) and differs only by the
     * `wv` token and `Version/4.0` prefix, so keeping it costs little and buys
     * self-consistent fingerprint data.
     */
    val presentDefaultUserAgent: Boolean = false,
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
            // YouTube properties the sign-in flow can bounce through.
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "ytimg.com",
            "ggpht.com",
            "googlevideo.com",
            // Google sign-in + its static/asset and account hosts.
            "google.com",
            "gstatic.com",
            "googleusercontent.com",
            "googleapis.com",
            "gvt1.com",
            "gvt2.com",
            "recaptcha.net",
        ),
        allowedHostPatterns = listOf(CaptureHostPolicy.GOOGLE_COUNTRY_DOMAIN),
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
        allowedHosts = listOf(
            "instagram.com",
            // Facebook OAuth ("Log in with Facebook") + Accounts Center.
            "facebook.com",
            // Meta's account/OAuth hops that can be reached from the login flow.
            "meta.com",
            // Static/CDN/JS hosts used by the login page. Only main-frame loads
            // open externally now, so these are mostly future-proofing.
            "cdninstagram.com",
            "fbcdn.net",
            "facebook.net",
        ),
        title = "Sign in with Instagram",
        subtitle = "Log in normally (2FA included). SocialDL never sees your password; only cookies are stored encrypted.",
        userAgentOverride = USER_AGENT,
    )
}

/**
 * Twitter/X capture configuration (Phase 6).
 *
 * X issues `auth_token` only to signed-in sessions; `ct0` is its CSRF token and
 * is served to signed-out visitors too. [CookieCaptureConfig.markerCookies] is
 * matched ANY-of, so pairing them would accept an anonymous jar — the marker is
 * `auth_token` alone.
 *
 * No User-Agent override (confirmed on-device, Nov 2026): X risk-controls its web
 * login by browser fingerprint, and the configurations that report a *fabricated*
 * UA are refused with "We've temporarily limited your login" while Chrome on the
 * same device logs in fine. Android WebView only emits User-Agent client hints
 * (`Sec-CH-UA`, `navigator.userAgentData`) when the app does not override the UA,
 * so any override produces "Chrome-like UA, zero client hints" — a combination no
 * real browser produces. Leaving [CookieCaptureConfig.userAgentOverride] null and
 * [CookieCaptureConfig.presentDefaultUserAgent] true keeps the engine's own
 * (already Chrome-shaped) UA and its client hints consistent, which X accepts.
 * YouTube strips `wv`/`Version/4.0` because Google only reads the UA string;
 * Instagram pins a desktop UA because its session is UA-bound. X needs neither.
 *
 * X sessions are not UA-bound, so the extraction bridge does not pin a UA either.
 */
object TwitterCookieCapture {

    const val LOGIN_URL = "https://x.com/i/flow/login"
    const val SUCCESS_ORIGIN = "https://x.com"

    /** `auth_token` is the only signed-in-only cookie; see the class note. */
    val MARKER_COOKIES = listOf("auth_token")

    val HARVESTS = listOf(
        NetscapeCookieJarWriter.Harvest(
            url = "https://x.com",
            cookieDomain = ".x.com"
        ),
        // Sessions created before the twitter.com -> x.com rename may still live
        // under the legacy domain; an empty harvest is a cheap no-op.
        NetscapeCookieJarWriter.Harvest(
            url = "https://twitter.com",
            cookieDomain = ".twitter.com"
        ),
    )

    fun config(): CookieCaptureConfig = CookieCaptureConfig(
        platform = Platform.TWITTER,
        loginUrl = LOGIN_URL,
        successOrigin = SUCCESS_ORIGIN,
        markerCookies = MARKER_COOKIES,
        harvests = HARVESTS,
        userAgent = null,
        allowedHosts = listOf(
            "x.com",
            "twitter.com",
            // Login-page static assets + embedded link hops.
            "twimg.com",
            "t.co",
            // SSO hops flow through the embedded WebView: Apple's redirect is
            // WebView-friendly; Google follows the YouTube recipe (wv-stripped
            // UA + allowlisted account hosts). "Continue with Google" is also a
            // reported workaround for X's temporary login limits.
            "apple.com",
            "icloud.com",
            "google.com",
            "accounts.google.com",
            "gstatic.com",
            "googleapis.com",
        ),
        title = "Sign in with X",
        subtitle = "Log in normally (2FA included). SocialDL never sees your password; only cookies are stored encrypted.",
        // Keep WebView's own (already Chrome-shaped, reduced) UA so client hints stay
        // populated and self-consistent. Overriding it — even to look more like
        // Chrome — silently drops Sec-CH-UA/navigator.userAgentData, which is the
        // anomaly X's login risk control reacts to.
        presentDefaultUserAgent = true,
    )
}
