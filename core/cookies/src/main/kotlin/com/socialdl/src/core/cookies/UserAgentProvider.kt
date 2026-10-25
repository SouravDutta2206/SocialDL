package com.socialdl.src.core.cookies

import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.python.PythonEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the User-Agent the cookie-capture WebView must present (§2.6) by running
 * `common.ua_probe.probe_user_agent` through [PythonEngine] (already warm from the
 * boot-time yt_dlp warmup). Probed once per process and cached; a failure falls
 * back to a hardcoded Chrome- desktop-like constant rather than breaking capture.
 */
@Singleton
class UserAgentProvider @Inject constructor(
    private val pythonEngine: PythonEngine
) {

    private val tag = "UserAgentProvider"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    suspend fun probe(): String {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: run {
                val ua = runCatching {
                    pythonEngine.run(module = "common.ua_probe", function = "probe_user_agent") { raw ->
                        json.parseToJsonElement(raw)
                            .jsonObject["user_agent"]
                            ?.jsonPrimitive?.content
                            .orEmpty()
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: FALLBACK_UA
                Logger.i(tag, "UA probe resolved: ${if (ua == FALLBACK_UA) "fallback" else ua}")
                cached = ua
                ua
            }
        }
    }

    /** Used only when the Python-side probe itself fails (engine cold / hot-swap window). */
    companion object {
        const val FALLBACK_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
