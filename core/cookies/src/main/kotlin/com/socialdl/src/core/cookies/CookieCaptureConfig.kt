package com.socialdl.src.core.cookies

import com.socialdl.src.core.model.Platform

/**
 * Parameters for one WebView cookie-capture flow (§2.6 scope note): the controller
 * and screen are generic over these values — wiring up a second WebView-login-backed
 * platform is one entry in [CAPTURE_CONFIGS], not a rewrite.
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
)

/**
 * The capture configuration table — one entry per WebView-login-backed platform.
 *
 * Shared capture rules that every entry relies on:
 * - the UA is never overridden (Chromium suppresses its client hints then; platforms
 *   risk-control logins on "browser UA with no Sec-CH-UA" — X refused logins with any
 *   override and accepted the untouched UA);
 * - marker cookies are matched ANY-of, so a marker list must only contain
 *   signed-in-only cookies (`ct0` for X is served logged-out too, so it is NOT a marker);
 * - capture (the WebView) and extraction (the bridge) may share the same UA only via
 *   `WebSettings.getDefaultUserAgent` — never via a hardcoded constant.
 */
private val CAPTURE_CONFIGS = listOf(
    CookieCaptureConfig(
        platform = Platform.YOUTUBE,
        // Plain YouTube home — NOT accounts.google.com/ServiceLogin..., which Google
        // aggressively rejects in embedded WebViews ("This browser or app may not be
        // secure"). The user taps the profile icon and signs in exactly like they
        // would in a normal browser.
        loginUrl = "https://www.youtube.com/",
        successOrigin = "https://www.youtube.com",
        // The pair yt-dlp itself looks for to decide a jar is a logged-in session.
        markerCookies = listOf("SAPISID", "__Secure-3PAPISID"),
        harvests = listOf(
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
        ),
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
    ),
    CookieCaptureConfig(
        platform = Platform.INSTAGRAM,
        loginUrl = "https://www.instagram.com/accounts/login/",
        successOrigin = "https://www.instagram.com",
        // The pair gallery-dl's Instagram extractor keys on (cookies_names).
        markerCookies = listOf("sessionid", "ds_user_id"),
        harvests = listOf(
            NetscapeCookieJarWriter.Harvest(
                url = "https://www.instagram.com",
                cookieDomain = ".instagram.com"
            ),
        ),
        allowedHosts = listOf(
            "instagram.com",
            // Facebook OAuth ("Log in with Facebook") + Accounts Center.
            "facebook.com",
            // Meta's account/OAuth hops that can be reached from the login flow.
            "meta.com",
            // Static/CDN/JS hosts used by the login page.
            "cdninstagram.com",
            "fbcdn.net",
            "facebook.net",
        ),
        title = "Sign in with Instagram",
        subtitle = "Log in normally (2FA included). SocialDL never sees your password; only cookies are stored encrypted.",
    ),
    CookieCaptureConfig(
        platform = Platform.TWITTER,
        loginUrl = "https://x.com/i/flow/login",
        successOrigin = "https://x.com",
        // `auth_token` is the only signed-in-only cookie: `ct0` is Twitter's CSRF
        // token and is served to signed-out visitors too, so ANY-of matching forbids
        // it in this list.
        markerCookies = listOf("auth_token"),
        harvests = listOf(
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
        ),
        allowedHosts = listOf(
            "x.com",
            "twitter.com",
            // Login-page static assets + embedded link hops.
            "twimg.com",
            "t.co",
            // SSO hops flow through the embedded WebView: Apple's redirect is
            // WebView-friendly; Google's account hosts are allowlisted too.
            // "Continue with Google" is also a reported workaround for X's
            // temporary login limits.
            "apple.com",
            "icloud.com",
            "google.com",
            "accounts.google.com",
            "gstatic.com",
            "googleapis.com",
        ),
        title = "Sign in with X",
        subtitle = "Log in normally (2FA included). SocialDL never sees your password; only cookies are stored encrypted.",
    ),
).associateBy { it.platform }

/**
 * Resolves the capture flow for [platform]. Fail-closed: platforms without a
 * capture route (Threads/UNKNOWN) must never harvest a session.
 */
fun cookieCaptureConfig(platform: Platform): CookieCaptureConfig =
    CAPTURE_CONFIGS[platform]
        ?: throw IllegalArgumentException("Cookie capture is not supported for ${platform.name}")

/**
 * Host-matching rules shared by the capture WebView's navigation allowlist and
 * the login-complete check (§2.6).
 *
 * Kept deliberately Android-free (`android.net.Uri` is not available in plain
 * JVM unit tests) so the policy can be exercised without a device.
 */
object CaptureHostPolicy {

    /**
     * Google serves its account pages from country domains (`google.co.in`,
     * `google.com.au`, `google.co.uk`, ...) as well as `google.com`. After a 2FA
     * confirmation Google may land on one of those — refusing them is what used
     * to bounce a completed sign-in out to the external browser, leaving the
     * WebView spinning on the accounts page forever.
     *
     * Matched against the whole host, so the optional `.*\.` accepts any
     * subdomain while the TLD fallback still rejects lookalikes such as
     * `google.evil.com` or `google.co.in.evil.com`.
     */
    val GOOGLE_COUNTRY_DOMAIN =
        Regex("""(^|.*\.)google\.[a-z]{2,3}(\.[a-z]{2})?$""", RegexOption.IGNORE_CASE)

    /**
     * True when [host] is one of [allowedHosts] or a subdomain of one, or matches
     * one of [extraPatterns] (domain families such as Google's ccTLDs).
     */
    fun isAllowedHost(
        host: String?,
        allowedHosts: List<String>,
        extraPatterns: List<Regex> = emptyList(),
    ): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (allowedHosts.any { allowed ->
                val target = normalizeHost(allowed) ?: return@any false
                normalized == target || normalized.endsWith(".$target")
            }
        ) {
            return true
        }
        return extraPatterns.any { pattern -> pattern.matches(normalized) }
    }

    /**
     * True when [host] belongs to the success origin's domain family. `www.` is
     * stripped before comparison so a flow that finishes on `m.youtube.com`,
     * `music.youtube.com` or the bare `youtube.com` still auto-completes.
     */
    fun matchesSuccessHost(host: String?, successOrigin: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        val originHost = normalizeHost(hostOf(successOrigin)) ?: return false
        val family = originHost.removePrefix("www.")
        return normalized == family || normalized.endsWith(".$family")
    }

    /** Lowercases, trims and drops a trailing root dot; null for blank input. */
    fun normalizeHost(host: String?): String? = host
        ?.trim()
        ?.lowercase()
        ?.trimEnd('.')
        ?.takeIf { it.isNotEmpty() }

    /**
     * Extracts the host from an absolute URL (or a bare `host/path` string)
     * without `android.net.Uri`, so it is safe to call from unit tests.
     * Ports, user-info and trailing dots are stripped.
     */
    fun hostOf(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        val afterScheme = value.substringAfter("://", value)
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
        return normalizeHost(host)?.takeIf { it.isNotBlank() }
    }
}
