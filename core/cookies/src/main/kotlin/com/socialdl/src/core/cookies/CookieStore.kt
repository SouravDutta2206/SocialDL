package com.socialdl.src.core.cookies

import android.net.Uri
import com.socialdl.src.core.model.Platform
import kotlinx.coroutines.flow.Flow

/**
 * Encrypted persistence for captured cookie jars, one per platform (§2.6).
 *
 * Each jar lives at `context.filesDir/cookies/<platform>_cookies.txt.enc`, written
 * through `androidx.security.crypto.EncryptedFile` — never plaintext at rest, and
 * never through `SettingsRepository`/Room (§2.7: every secret in this app is
 * file-shaped, so blobs bypass the key-value settings store entirely).
 *
 * Per-platform keying is what lets the same WebView capture flow feed both
 * yt-dlp (YouTube) and gallery-dl (Instagram) without sharing jars.
 */
interface CookieStore {

    /** True when a captured/imported jar is currently stored for [platform]. */
    fun hasCookies(platform: Platform): Boolean

    /** Epoch millis of the last successful save/import for [platform]. */
    fun lastRefreshedAt(platform: Platform): Flow<Long?>

    /**
     * Persists a complete Netscape-format jar ([NetscapeCookieJarWriter.buildJarContent]
     * output or a validated manual import), encrypting at rest and stamping the
     * refresh timestamp.
     */
    suspend fun save(platform: Platform, jarContent: String)

    /** Deletes the stored jar and the last-refreshed stamp for [platform]. */
    suspend fun clear(platform: Platform)

    /**
     * Manual `cookies.txt` import fallback (§2.6). The file is validated
     * (tab-delimited, correct column count) *before* anything is accepted; a file
     * with a single malformed data line is rejected wholesale.
     */
    suspend fun importNetscapeFile(platform: Platform, uri: Uri): ImportOutcome

    /**
     * Decrypts the stored jar for [platform] into a throwaway file under `cacheDir`
     * and hands the path to [block]; the temp file is deleted in a `finally` the
     * moment the block returns.
     *
     * When nothing is stored, [block] receives `null` — missing cookies must never
     * introduce a new failure mode; callers pass the null straight through to the
     * extractor's optional cookiefile.
     */
    suspend fun <T> withCookieFile(platform: Platform, block: suspend (String?) -> T): T

    sealed class ImportOutcome {
        /** File validated and stored. */
        data object Success : ImportOutcome()

        /** Content resolved but rejected by validation. */
        data class Rejected(
            val reason: String,
            val invalidLineNumbers: List<Int> = emptyList(),
        ) : ImportOutcome()

        /** The content resolver could not open the picked document at all. */
        data object Unreadable : ImportOutcome()
    }
}
