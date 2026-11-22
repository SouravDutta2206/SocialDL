package com.socialdl.src.extraction.threads

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.ThumbnailCache
import com.socialdl.src.core.cookies.CookieStore
import com.socialdl.src.core.model.Platform
import com.socialdl.src.extraction.api.ExtractionError
import com.socialdl.src.extraction.api.ExtractionResult
import com.socialdl.src.extraction.api.MediaExtractor
import com.socialdl.src.extraction.api.PlatformCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Threads extractor backed by an off-screen Android [WebView] DOM/Relay-preloader
 * pipeline with encrypted per-run cookie jar replay via [CookieStore].
 */
@Singleton
class ThreadsExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieStore: CookieStore,
    private val thumbnailCache: ThumbnailCache,
) : MediaExtractor {

    private val tag = "ThreadsExtractor"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Serialize WebView runs so two extractions never race each other. */
    private val gate = Mutex()

    override val platform: Platform = Platform.THREADS

    override val capabilities: PlatformCapabilities = PlatformCapabilities(
        supportsCarousels = true,
        supportsLoginGatedContent = true,
        needsWebView = true,
        typicalLatencyMs = 6000L,
        showsFormatPicker = false,
    )

    override suspend fun extract(url: String): ExtractionResult {
        val normalizedUrl = normalizeThreadsUrl(url)
        if (!supports(normalizedUrl)) {
            return ExtractionResult.Error(ExtractionError.UnsupportedUrl(url))
        }

        val hasCookies = cookieStore.jarState(Platform.THREADS) != null
        Logger.d(tag, "Extracting Threads metadata for $normalizedUrl (cookies=$hasCookies)")

        return try {
            // Parse the decrypted jar while its throwaway file is alive; the file
            // is deleted as soon as this block returns, before the WebView starts.
            val cookies = cookieStore.withCookieFile(Platform.THREADS) { jarPath ->
                jarPath?.let { path ->
                    withContext(Dispatchers.IO) {
                        runCatching { parseNetscapeCookies(File(path).readLines()) }
                            .onFailure { Logger.w(tag, "Failed to parse Threads cookie jar", it) }
                            .getOrDefault(emptyList())
                    }
                }.orEmpty()
            }

            withTimeout(EXTRACTION_TIMEOUT_MS) {
                gate.withLock {
                    val rawJson = withContext(Dispatchers.Main) {
                        runHeadlessExtraction(normalizedUrl, cookies)
                    }
                    decodeAndMap(rawJson, normalizedUrl)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(tag, "Threads extraction timed out for $normalizedUrl")
            ExtractionResult.Error(ExtractionError.Timeout())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ThreadsWebViewLoadException) {
            Logger.w(tag, "Threads WebView load failed for $normalizedUrl: ${e.message}")
            ExtractionResult.Error(ExtractionError.NetworkFailure(e.message ?: "Failed to load Threads page", e))
        } catch (e: Exception) {
            Logger.e(tag, "Threads extraction failed for $normalizedUrl", e)
            ExtractionResult.Error(ExtractionError.ParsingFailed("Couldn't process Threads' response", e))
        }
    }

    private suspend fun decodeAndMap(rawJson: String, sourceUrl: String): ExtractionResult {
        Logger.d(tag, "Threads raw extraction JSON: ${rawJson.take(1500)}")
        val dto = try {
            json.decodeFromString<ThreadsExtractionDto>(rawJson)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to deserialize Threads JSON", e)
            return ExtractionResult.Error(ExtractionError.ParsingFailed("Malformed Threads response", e))
        }

        val items = mapThreadsItems(dto, sourceUrl) { thumbnailCache.store(it) }
        return if (items.isEmpty()) {
            if (dto.loginWall) {
                ExtractionResult.Error(
                    ExtractionError.AuthWallDetected(
                        platform = Platform.THREADS,
                        detail = "Sign in to Threads to access this post",
                    )
                )
            } else {
                ExtractionResult.Error(ExtractionError.NoDownloadableMedia())
            }
        } else {
            ExtractionResult.Success(items)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runHeadlessExtraction(
        postUrl: String,
        cookies: List<ParsedNetscapeCookie>,
    ): String {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // Outer try/finally runs synchronously on Dispatchers.Main before
        // runHeadlessExtraction returns (and before `gate` unlocks), so a
        // late posted cleanup can never wipe a subsequent extraction's cookies.
        return try {
            // 1) Replay the decrypted Netscape jar into CookieManager so extraction
            // never depends on a live capture WebView session. Signed out of Threads
            // (no jar): explicitly expire any stale session left by a capture or a
            // previous run so it cannot leak into this anonymous run.
            // setCookie is asynchronous, so await every callback — a page load racing
            // a pending write would run with the old (or no) session.
            setCookiesAsync(
                cm,
                cookies.takeIf { it.isNotEmpty() }
                    ?.map { cookie -> cookie.url to cookie.setCookieHeader }
                    ?: SIGNED_OUT_EXPIRY_COOKIES,
            )
            // 2) Pre-seed consent cookies to avoid cookie banners blocking hydration.
            CONSENT_COOKIES.forEach { cookie ->
                cm.setCookie(THREADS_ORIGIN, "$cookie; Path=/; Secure")
            }

            suspendCancellableCoroutine { continuation ->
                val mainHandler = Handler(Looper.getMainLooper())
                val finished = AtomicBoolean(false)
                val pollingStarted = AtomicBoolean(false)
                val preloaderSeenPolls = AtomicInteger(0)
                var webView: WebView? = null

                fun cleanup() {
                    mainHandler.removeCallbacksAndMessages(null)
                    val wv = webView ?: return
                    webView = null
                    runCatching { wv.removeJavascriptInterface(JS_BRIDGE_NAME) }
                    runCatching { wv.stopLoading() }
                    runCatching { wv.destroy() }
                }

            fun finishWithResult(payload: String) {
                if (!finished.compareAndSet(false, true)) return
                mainHandler.post {
                    cleanup()
                    if (continuation.isActive) continuation.resume(payload)
                }
            }

            fun finishWithError(error: Throwable) {
                if (!finished.compareAndSet(false, true)) return
                mainHandler.post {
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.post { cleanup() }
                }
            }

            try {
                val wv = WebView(context)
                webView = wv

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // Never override userAgentString (preserves Sec-CH-UA client hints).
                }

                cm.setAcceptThirdPartyCookies(wv, true)

                // Chromium requires non-zero dimensions even when detached from a window.
                wv.measure(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                wv.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

                val bridge = ThreadsJsBridge { payload -> finishWithResult(payload) }
                wv.addJavascriptInterface(bridge, JS_BRIDGE_NAME)
                wv.webChromeClient = WebChromeClient()

                fun schedulePoll(view: WebView) {
                    if (finished.get()) return
                    view.evaluateJavascript(HYDRATION_CHECK_JS) { raw ->
                        if (finished.get()) return@evaluateJavascript
                        // evaluateJavascript wraps string results in JSON quotes (e.g. "\"2\"").
                        val state = raw?.trim()?.trim('"')
                        val readyNow = when (state) {
                            "2" -> true // TargetQuery preloader + media signal ready
                            "1" -> preloaderSeenPolls.incrementAndGet() >= TEXT_ONLY_SETTLE_POLLS
                            else -> false
                        }
                        if (readyNow) {
                            view.evaluateJavascript(EXTRACTOR_JS, null)
                        } else {
                            mainHandler.postDelayed({ schedulePoll(view) }, POLL_INTERVAL_MS)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val scheme = request.url?.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") {
                            // Threads mobile web tries to bounce to intent:// or barcelona://
                            // to open the native app; block it so the web post stays hydrated.
                            Logger.d(tag, "Blocked non-HTTP WebView redirect (${scheme ?: "unknown"})")
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (finished.get()) return
                        if (pollingStarted.compareAndSet(false, true)) {
                            schedulePoll(view)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        val scheme = request.url?.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") return
                        if (request.isForMainFrame) {
                            finishWithError(
                                ThreadsWebViewLoadException(
                                    "WebView main-frame error (${error.errorCode}): ${error.description}"
                                )
                            )
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        finishWithError(
                            ThreadsWebViewLoadException("WebView render process crashed")
                        )
                        return true
                    }
                }

                wv.loadUrl(postUrl)
            } catch (e: Exception) {
                finishWithError(e)
            }
        }
        } finally {
            // Expire every replayed cookie from the global CookieManager before
            // returning and before the extraction Mutex releases.
            clearReplayedCookies(cm, cookies)
        }
    }

    /**
     * Writes [entries] to [cm] one at a time, awaiting every asynchronous
     * `setCookie` callback. `loadUrl` must not run while a write is pending, or
     * the page can load with the previous session (or none at all).
     */
    private suspend fun setCookiesAsync(
        cm: CookieManager,
        entries: List<Pair<String, String>>,
    ) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            for ((url, header) in entries) {
                suspendCancellableCoroutine { continuation ->
                    cm.setCookie(url, header) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
    }

    /**
     * Expires every cookie replayed from the decrypted jar in the
     * process-global [CookieManager] (never written back to WebView's on-disk
     * cookie DB: no `flush()`).
     */
    private fun clearReplayedCookies(
        cm: CookieManager,
        cookies: List<ParsedNetscapeCookie>,
    ) {
        cookies.forEach { cookie ->
            runCatching { cm.setCookie(cookie.url, cookie.expiredSetCookieHeader, null) }
        }
    }

    private class ThreadsJsBridge(
        private val onResult: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun onExtracted(json: String) {
            onResult(json)
        }
    }

    private class ThreadsWebViewLoadException(message: String) : Exception(message)

    private companion object {
        const val EXTRACTION_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 500L
        const val TEXT_ONLY_SETTLE_POLLS = 6
        const val VIEWPORT_WIDTH = 1080
        const val VIEWPORT_HEIGHT = 2280
        const val JS_BRIDGE_NAME = "AndroidMedia"
        const val THREADS_ORIGIN = "https://www.threads.com"
        val CONSENT_COOKIES = listOf("ps_n=1", "ps_l=1", "dpr=2")

        /**
         * Signed-out runs must not inherit a session from the capture WebView or
         * a prior extraction, so both Threads cookie scopes are expired before
         * the post page loads.
         */
        val SIGNED_OUT_EXPIRY_COOKIES = listOf(
            "https://threads.com" to "sessionid=; Domain=.threads.com; Path=/; Max-Age=0; Secure",
            "https://threads.com" to "ds_user_id=; Domain=.threads.com; Path=/; Max-Age=0; Secure",
            "https://www.threads.com" to "sessionid=; Path=/; Max-Age=0; Secure",
            "https://www.threads.com" to "ds_user_id=; Path=/; Max-Age=0; Secure",
        )

        /**
         * Returns a plain scalar string:
         * - "2": TargetQueryRelayPreloader is present AND media (image_versions2 or progressive video) is hydrated
         * - "1": TargetQueryRelayPreloader is present (may be a text-only post)
         * - "0": Still hydrating
         */
        const val HYDRATION_CHECK_JS = """
(function () {
  if (/^\/login\b/i.test(location.pathname) || document.querySelector('input[type="password"], input[name="username"]')) {
    return '1';
  }
  var scripts = Array.prototype.slice.call(
    document.querySelectorAll('script[data-sjs], script[type="application/json"]')
  );
  var targetTexts = scripts
    .map(function (s) { return s.textContent || ''; })
    .filter(function (t) { return t.indexOf('TargetQueryRelayPreloader') !== -1; });
  if (targetTexts.length === 0) return '0';
  var joined = targetTexts.join('\n');
  var hasImages = joined.indexOf('image_versions2') !== -1;
  var hasVideo = !!document.querySelector('video[src^="https"][src*="fbcdn"]');
  return (hasImages || hasVideo) ? '2' : '1';
})()
"""

        /**
         * Extracts main-post media only from TargetQueryRelayPreloader and the primary post DOM,
         * ignoring DownwardQuery blobs (replies / quoted posts) and UI assets.
         */
        const val EXTRACTOR_JS = """
(function () {
  var bodySample = ((document.body && document.body.innerText) || '').slice(0, 3000);
  var isLoginWall = /^\/login\b/i.test(location.pathname)
    || !!document.querySelector('input[type="password"], input[name="username"]')
    || /log in to see|this account is private|log in to threads|log in or sign up to view/i.test(bodySample);

  var out = { status: 'ok', kind: 'none', shortcode: '', author: '', caption: '', loginWall: isLoginWall, items: [], diag: '' };

  var scMatch = location.href.match(/post\/([A-Za-z0-9_-]+)/);
  if (scMatch) out.shortcode = scMatch[1];

  var authorMatch = location.href.match(/\/@([\w.\-]+)\/post\//i);
  if (authorMatch) out.author = '@' + authorMatch[1];

  var ogDesc = document.querySelector('meta[property="og:description"]');
  if (ogDesc) {
    var desc = (ogDesc.getAttribute('content') || '').trim();
    if (desc) out.caption = desc;
  }

  var targetScripts = Array.prototype.slice.call(
    document.querySelectorAll('script[data-sjs], script[type="application/json"]')
  )
    .map(function (s) { return s.textContent || ''; })
    .filter(function (t) { return t.indexOf('TargetQueryRelayPreloader') !== -1; });
  var targetTexts = targetScripts.join('\n');

  function b64url(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    try { return decodeURIComponent(escape(window.atob(s))); }
    catch (e) { return null; }
  }

  function mediaIdOf(url) {
    var k = (url.match(/ig_cache_key=([^&]+)/) || [])[1];
    if (!k) return null;
    var dec = b64url(decodeURIComponent(k).split('.')[0]);
    return dec ? dec.replace(/[^0-9]/g, '') : null;
  }

  function cleanImageUrl(raw) {
    if (!raw || typeof raw !== 'string') return null;
    var u = raw.replace(/&amp;/g, '&').trim();
    if (!/^https?:\/\//i.test(u)) return null;
    if (u.indexOf('t39.92108-6') !== -1) return null; // skip ODS share-card image
    if (u.indexOf('t51.82787-19') !== -1) return null; // skip profile avatar
    return u;
  }

  function findAllProgressiveVideos(root) {
    var vids = Array.prototype.slice.call(root.querySelectorAll('video'));
    var outVids = [], seenSrc = {};
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      var src = v.currentSrc || v.src || (v.querySelector('source') ? v.querySelector('source').src : '');
      if (src && /^https?:\/\//i.test(src) && src.indexOf('fbcdn') !== -1 && src.indexOf('rsrc.php') === -1 && !seenSrc[src]) {
        seenSrc[src] = true;
        outVids.push({ el: v, src: src, used: false });
      }
    }
    return outVids;
  }

  function extractDuration(videoEl, videoUrl) {
    if (videoEl && videoEl.duration && isFinite(videoEl.duration) && videoEl.duration > 0) {
      return videoEl.duration;
    }
    var efgMatch = videoUrl.match(/[?&]efg=([^&]+)/);
    if (efgMatch) {
      var efgText = b64url(decodeURIComponent(efgMatch[1]));
      if (efgText) {
        try {
          var efgObj = JSON.parse(efgText);
          if (typeof efgObj.duration_s === 'number' && efgObj.duration_s > 0) {
            return efgObj.duration_s;
          }
        } catch (e) {}
      }
    }
    return null;
  }

  var primaryContainer = document.querySelector('article, [data-pressable-container="true"]') || document;
  var domVids = findAllProgressiveVideos(primaryContainer);
  if (domVids.length === 0) domVids = findAllProgressiveVideos(document);

  function takeNextDomVideo() {
    for (var i = 0; i < domVids.length; i++) {
      if (!domVids[i].used) {
        domVids[i].used = true;
        return domVids[i];
      }
    }
    return null;
  }

  var seen = {}, items = [], carouselFound = false;

  function processMediaNode(node) {
    if (!node || typeof node !== 'object') return false;
    var hasImg = node.image_versions2 && Array.isArray(node.image_versions2.candidates) && node.image_versions2.candidates.length > 0;
    var c0 = hasImg ? node.image_versions2.candidates[0] : null;
    var imgUrl = c0 ? cleanImageUrl(c0.url) : null;

    var v0 = (Array.isArray(node.video_versions) && node.video_versions.length > 0) ? node.video_versions[0] : null;
    var jsonVidUrl = (v0 && typeof v0.url === 'string' && /^https?:\/\//i.test(v0.url)) ? v0.url : null;
    var isVideoNode = !!jsonVidUrl || node.media_type === 2 || node.has_audio === true || !!node.video_dash_manifest;

    if (isVideoNode) {
      var domVid = jsonVidUrl ? null : takeNextDomVideo();
      var vidUrl = jsonVidUrl || (domVid ? domVid.src : null);
      if (vidUrl) {
        var vidKey = 'v:' + (node.pk || node.id || vidUrl.split('?')[0]);
        if (seen[vidKey]) return true;
        seen[vidKey] = true;
        var videoEl = domVid ? domVid.el : null;
        var poster = (videoEl ? cleanImageUrl(videoEl.getAttribute('poster')) : null) || imgUrl;
        var vw = (v0 && typeof v0.width === 'number') ? v0.width : ((videoEl && videoEl.videoWidth > 0) ? videoEl.videoWidth : (c0 && typeof c0.width === 'number' ? c0.width : null));
        var vh = (v0 && typeof v0.height === 'number') ? v0.height : ((videoEl && videoEl.videoHeight > 0) ? videoEl.videoHeight : (c0 && typeof c0.height === 'number' ? c0.height : null));
        items.push({
          kind: 'video',
          pk: node.pk ? String(node.pk) : (node.id ? String(node.id) : null),
          url: vidUrl,
          posterUrl: poster,
          w: vw,
          h: vh,
          duration: extractDuration(videoEl, vidUrl),
          cover: false
        });
        return true;
      }
    }

    if (imgUrl) {
      var id = mediaIdOf(imgUrl) || (node.pk ? String(node.pk) : (node.id ? String(node.id) : imgUrl.split('?')[0]));
      if (!id || seen[id]) return true;
      seen[id] = true;
      items.push({
        kind: 'photo',
        pk: id,
        url: imgUrl,
        w: typeof c0.width === 'number' ? c0.width : null,
        h: typeof c0.height === 'number' ? c0.height : null,
        cover: false
      });
      return true;
    }
    return false;
  }

  function walk(node, depth) {
    if (!node || typeof node !== 'object' || depth > 40) return;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) walk(node[i], depth + 1);
      return;
    }
    // Prefer the explicit carousel_media array when present so mixed photo+video
    // carousels keep their exact order and don't double-count video posters as photos.
    if (Array.isArray(node.carousel_media) && node.carousel_media.length > 0) {
      carouselFound = true;
      for (var ci = 0; ci < node.carousel_media.length; ci++) {
        processMediaNode(node.carousel_media[ci]);
      }
      return;
    }
    if (!carouselFound && (node.image_versions2 || (Array.isArray(node.video_versions) && node.video_versions.length > 0))) {
      if (processMediaNode(node)) return;
    }
    var keys = Object.keys(node);
    for (var k = 0; k < keys.length; k++) {
      walk(node[keys[k]], depth + 1);
    }
  }

  var parsedScripts = 0;
  for (var si = 0; si < targetScripts.length; si++) {
    try {
      walk(JSON.parse(targetScripts[si]), 0);
      parsedScripts++;
    } catch (e) {}
  }

  // Fallback for single-video posts where TargetQuery had nulled video_versions and no media_type hint.
  if (items.length === 0 && domVids.length > 0) {
    var dv = domVids[0];
    var ogImg = document.querySelector('meta[property="og:image"]');
    var poster = cleanImageUrl(dv.el.getAttribute('poster')) || cleanImageUrl(ogImg ? ogImg.getAttribute('content') : null);
    items.push({
      kind: 'video',
      url: dv.src,
      posterUrl: poster,
      w: dv.el.videoWidth > 0 ? dv.el.videoWidth : null,
      h: dv.el.videoHeight > 0 ? dv.el.videoHeight : null,
      duration: extractDuration(dv.el, dv.src),
      cover: true
    });
  } else if (items.length === 1 && items[0].kind === 'photo' && domVids.length > 0 && !carouselFound) {
    // Single video post where TargetQuery only exposed its poster in image_versions2.
    var targetHasVideoHint = /"media_type"\s*:\s*2\b|"video_dash_manifest"\s*:\s*"|"has_audio"\s*:\s*true/.test(targetTexts);
    if (targetHasVideoHint) {
      var dv2 = domVids[0];
      items[0] = {
        kind: 'video',
        pk: items[0].pk,
        url: dv2.src,
        posterUrl: cleanImageUrl(dv2.el.getAttribute('poster')) || items[0].url,
        w: dv2.el.videoWidth > 0 ? dv2.el.videoWidth : items[0].w,
        h: dv2.el.videoHeight > 0 ? dv2.el.videoHeight : items[0].h,
        duration: extractDuration(dv2.el, dv2.src),
        cover: true
      };
    }
  }

  out.diag = 'scripts=' + targetScripts.length + ',parsed=' + parsedScripts
    + ',carousel=' + (carouselFound ? '1' : '0')
    + ',items=' + items.length + ',domVids=' + domVids.length;

  if (items.length === 0) {
    out.status = 'no_media';
    window.AndroidMedia && window.AndroidMedia.onExtracted(JSON.stringify(out));
    return;
  }

  out.kind = items.length > 1 ? 'carousel' : items[0].kind;

  // For photo carousels without explicit carousel_media order, promote og:image cover to index 0.
  if (!carouselFound && items.length > 1) {
    var ogEl = document.querySelector('meta[property="og:image"]');
    if (ogEl) {
      var ogUrl = (ogEl.getAttribute('content') || '').replace(/&amp;/g, '&');
      var ogMatch = (ogUrl.match(/\/([0-9_]+_n)\.(?:jpg|webp|png)/i) || [])[1];
      if (ogMatch) {
        for (var i = 0; i < items.length; i++) {
          var checkUrl = items[i].kind === 'video' ? (items[i].posterUrl || '') : (items[i].url || '');
          var f = (checkUrl.match(/\/([0-9_]+_n)\.(?:jpg|webp|png)/i) || [])[1];
          if (f === ogMatch) {
            items[i].cover = true;
            items.unshift(items.splice(i, 1)[0]);
            break;
          }
        }
      }
    }
  }

  out.items = items;
  window.AndroidMedia && window.AndroidMedia.onExtracted(JSON.stringify(out));
})()
"""
    }
}
