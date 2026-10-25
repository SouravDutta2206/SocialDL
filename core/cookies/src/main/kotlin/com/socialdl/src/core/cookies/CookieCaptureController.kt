package com.socialdl.src.core.cookies

import android.webkit.CookieManager
import com.socialdl.src.core.common.util.Logger
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

    /** Resolves the default flow config, probing the runtime UA once. */
    suspend fun defaultConfig(): CookieCaptureConfig =
        YoutubeCookieCapture.config(userAgent = userAgentProvider.probe())

    /**
     * Auto-detect check, run from `onPageFinished`: the WebView has settled on
     * [CookieCaptureConfig.successOrigin] and its jar now carries a marker session
     * cookie (the same SAPISID pair yt-dlp itself keys on).
     */
    fun isLoginComplete(config: CookieCaptureConfig, currentUrl: String?): Boolean {
        if (currentUrl == null || !currentUrl.startsWith(config.successOrigin)) return false
        val cookie = CookieManager.getInstance().getCookie(config.successOrigin) ?: return false
        return config.markerCookies.any { cookie.contains("$it=") }
    }

    /**
     * Manual "I'm signed in" fallback path: skip the marker heuristic on the
     * *current* URL and just try harvesting from the configured origins.
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
            val harvests = config.harvests.mapNotNull { candidate ->
                val header = manager.getCookie(candidate.url)
                if (header.isNullOrBlank()) null else candidate.copy(header = header)
            }
            if (harvests.isEmpty()) {
                Logger.w(tag, "Harvest found no cookies across configured origins")
                return CaptureOutcome.NotSignedIn
            }

            val jar = NetscapeCookieJarWriter.buildJarContent(harvests)
                ?: return CaptureOutcome.NotSignedIn

            cookieStore.save(jar)
            val cookieCount = countLines(jar)
            Logger.i(tag, "Captured YouTube cookie jar with $cookieCount cookies")
            CaptureOutcome.Success(cookieCount)
        } catch (e: Exception) {
            Logger.e(tag, "Cookie harvest failed", e)
            CaptureOutcome.Failure(e.message ?: "Cookie capture failed")
        }
    }

    private fun countLines(jar: String): Int =
        jar.lines().count { it.isNotBlank() && !it.startsWith("#") }
}
