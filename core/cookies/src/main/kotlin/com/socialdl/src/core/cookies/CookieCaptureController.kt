package com.socialdl.src.core.cookies

import android.webkit.CookieManager
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
) {

    private val tag = "CookieCapture"

    /** Resolves the flow config for [platform]. Pure table lookup; no suspension. */
    fun config(platform: Platform): CookieCaptureConfig =
        cookieCaptureConfig(platform)

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
        val manager = CookieManager.getInstance()
        val successCookie = runCatching {
            manager.getCookie(config.successOrigin)
        }.getOrNull()
        if (hasMarkerCookies(config, successCookie)) return true
        return config.harvests.any { harvest ->
            harvest.url != config.successOrigin &&
                hasMarkerCookies(config, runCatching { manager.getCookie(harvest.url) }.getOrNull())
        }
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

            // Only a marker-bearing jar counts as signed in. Check all configured
            // harvests so platforms whose auth cookies live on a sibling origin
            // (e.g. Threads -> .instagram.com) still validate properly.
            if (harvests.none { harvest -> hasMarkerCookies(config, harvest.header) }) {
                Logger.w(
                    tag,
                    "No ${config.markerCookies.joinToString("/")} marker across " +
                        "${config.harvests.joinToString { it.url }}; refusing to store a signed-out jar"
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

    /**
     * Deletes the capture WebView's own cookie store, not just our encrypted jar.
     *
     * Without this, "Clear cookies" only removed the stored jar while x.com / Google /
     * Instagram cookies kept living inside the capture browser. The next sign-in then
     * started from a half-signed-in session the app reported as signed out — confusing
     * for the user and, on platforms that risk-control logins (X), actively harmful:
     * X answered a fresh login with "you are not allowed to login at this time" while
     * presenting the exact User-Agent it had accepted an hour earlier.
     *
     * `CookieManager.removeAllCookies` is the only cross-platform-scoped removal WebView
     * exposes, so this signs the capture browser out of every platform. Stored jars are
     * untouched, so extraction keeps working; only the browser session is reset.
     */
    suspend fun clearCaptureSession() {
        withContext(Dispatchers.Main.immediate) {
            try {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                suspendCancellableCoroutine { continuation ->
                    manager.removeAllCookies { continuation.resume(Unit) }
                }
                manager.flush()
                Logger.i(tag, "Cleared the capture WebView cookie store")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag, "Could not clear the capture WebView cookie store", e)
            }
        }
    }

    private fun countLines(jar: String): Int =
        jar.lines().count { it.isNotBlank() && !it.startsWith("#") }
}
