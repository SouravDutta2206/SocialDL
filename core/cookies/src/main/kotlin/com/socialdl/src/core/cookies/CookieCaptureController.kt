package com.socialdl.src.core.cookies

import android.webkit.CookieManager
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.Platform
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed class CaptureOutcome {
    /** Marker cookies found, jar harvested, written and encrypted. */
    data class Success(val cookieCount: Int) : CaptureOutcome()

    /** Web content reached but jar had none of the session markers (not signed in). */
    data object NotSignedIn : CaptureOutcome()

    /** Harvest or persist failed — surfaced so the UI can show a plain error state. */
    data class Failure(val message: String) : CaptureOutcome()
}

/**
 * Orchestrates one cookie-capture flow generically over [CookieCaptureConfig]
 * (§2.6). The WebView belongs to the Compose host; lifecycle discipline
 * (`stopLoading()` + `destroy()` on every exit path) lives there too. This class
 * owns only the check / harvest / persist decisions.
 */
@Singleton
class CookieCaptureController @Inject constructor(
    private val cookieStore: CookieStore,
    private val userAgentProvider: UserAgentProvider,
) {

    private val tag = "CookieCapture"

    /** Resolves the flow config for [platform], probing the runtime UA for YouTube. */
    suspend fun config(platform: Platform): CookieCaptureConfig = when (platform) {
        Platform.YOUTUBE -> YoutubeCookieCapture.config(userAgent = userAgentProvider.probe())
        Platform.INSTAGRAM -> InstagramCookieCapture.config()
        // Exhaustive: never silently treat Twitter/Threads/UNKNOWN as YouTube and
        // harvest the wrong platform's session.
        Platform.TWITTER, Platform.THREADS, Platform.UNKNOWN ->
            throw IllegalArgumentException("Cookie capture is not supported for ${platform.name}")
    }

    /**
     * Auto-detect check, run from `onPageFinished`: the WebView has settled on
     * [CookieCaptureConfig.successOrigin] (or a sibling host such as
     * `m.youtube.com`) and its jar now carries a marker session cookie (the same
     * SAPISID pair yt-dlp itself keys on).
     */
    fun isLoginComplete(config: CookieCaptureConfig, currentUrl: String?): Boolean {
        if (currentUrl == null || !currentUrl.startsWith("https://", ignoreCase = true)) return false
        if (!CaptureHostPolicy.matchesSuccessHost(
                CaptureHostPolicy.hostOf(currentUrl),
                config.successOrigin
            )
        ) {
            return false
        }
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(config.successOrigin)
        }.getOrNull()
        return hasMarkerCookies(config, cookie)
    }

    /**
     * Manual "I'm signed in" fallback path: skip the success-URL heuristic and
     * harvest from the configured origins, but still require the marker cookies —
     * otherwise the first anonymous `VISITOR_INFO1_LIVE` cookie would be persisted
     * as a "signed-in" jar and Settings would lie.
     */
    suspend fun manualCapture(config: CookieCaptureConfig, currentUrl: String?): CaptureOutcome {
        if (currentUrl.isNullOrBlank()) return CaptureOutcome.NotSignedIn
        return harvestAndStore(config)
    }

    /**
     * Reads `CookieManager.getCookie()` from every configured origin, builds the
     * Netscape jar through [NetscapeCookieJarWriter] and stores it encrypted.
     */
    suspend fun harvestAndStore(config: CookieCaptureConfig): CaptureOutcome {
        return try {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            // WebView commits cookies on its own schedule; flush before reading so
            // the final accounts/youtube redirect's Set-Cookie headers are visible
            // to the app-side CookieManager. Without this a freshly finished login
            // can harvest an empty/stale jar on some WebView builds.
            runCatching { manager.flush() }
                .onFailure { Logger.w(tag, "CookieManager.flush() failed before harvest", it) }

            val harvests = config.harvests.mapNotNull { candidate ->
                val header = manager.getCookie(candidate.url)
                if (header.isNullOrBlank()) null else candidate.copy(header = header)
            }
            if (harvests.isEmpty()) {
                Logger.w(tag, "Harvest found no cookies across configured origins")
                return CaptureOutcome.NotSignedIn
            }

            // Only a marker-bearing jar counts as signed in. This is the same check
            // auto-detect uses, so the manual fallback can no longer persist an
            // anonymous jar that merely happens to contain some cookies.
            val successHeader = harvests.firstOrNull { harvest ->
                CaptureHostPolicy.matchesSuccessHost(
                    CaptureHostPolicy.hostOf(harvest.url),
                    config.successOrigin
                )
            }?.header
            if (!hasMarkerCookies(config, successHeader)) {
                Logger.w(
                    tag,
                    "No ${config.markerCookies.joinToString("/")} marker in " +
                        "${config.successOrigin}; refusing to store a signed-out jar"
                )
                return CaptureOutcome.NotSignedIn
            }

            val jar = NetscapeCookieJarWriter.buildJarContent(harvests)
                ?: return CaptureOutcome.NotSignedIn

            cookieStore.save(config.platform, jar)
            val cookieCount = countLines(jar)
            Logger.i(tag, "Captured ${config.platform.name} cookie jar with $cookieCount cookies")
            CaptureOutcome.Success(cookieCount)
        } catch (e: CancellationException) {
            // Never turn a screen-leave into a "capture failed" toast / save.
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "Cookie harvest failed", e)
            CaptureOutcome.Failure(e.message ?: "Cookie capture failed")
        }
    }

    /** Marker names are matched exactly, so `SAPISID` cannot match `__Secure-3PAPISID`. */
    private fun hasMarkerCookies(config: CookieCaptureConfig, header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        val present = NetscapeCookieJarWriter.parseHeader(header).mapTo(HashSet()) { it.first }
        return config.markerCookies.any { marker -> marker in present }
    }

    private fun countLines(jar: String): Int =
        jar.lines().count { it.isNotBlank() && !it.startsWith("#") }
}
