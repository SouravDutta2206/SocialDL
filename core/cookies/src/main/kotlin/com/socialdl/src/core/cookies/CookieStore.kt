package com.socialdl.src.core.cookies

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Encrypted persistence for the captured YouTube cookie jar (§2.6).
 *
 * The jar lives at `context.filesDir/cookies/youtube_cookies.txt.enc`, written
 * through `androidx.security.crypto.EncryptedFile` — never plaintext at rest, and
 * never through `SettingsRepository`/Room (§2.7: every secret in this app is
 * file-shaped, so blobs bypass the key-value settings store entirely).
 */
interface CookieStore {

    /** True when a captured/imported jar is currently stored. Synchronous, disk-cheap. */
    fun hasYoutubeCookies(): Boolean

    /** Epoch millis of the last successful save/import, or null — DataStore-backed, non-secret. */
    val lastRefreshedAt: Flow<Long?>

    /**
     * Persists a complete Netscape-format jar ([NetscapeCookieJarWriter.buildJarContent]
     * output or a validated manual import), encrypting at rest and stamping the
     * refresh timestamp.
     */
    suspend fun save(jarContent: String)

    /** Deletes the stored jar and the last-refreshed stamp. */
    suspend fun clear()

    /**
     * Manual `cookies.txt` import fallback (§2.6). The file is validated
     * (tab-delimited, correct column count) *before* anything is accepted; a file
     * with a single malformed data line is rejected wholesale.
     */
    suspend fun importNetscapeFile(uri: Uri): ImportOutcome

    /**
     * Decrypts the stored jar into a throwaway file under `cacheDir` and hands the
     * path to [block]; the temp file is deleted in a `finally` the moment the block
     * returns, so decrypted cookies never persist on disk longer than one call
     * (§2.6 "Bridging into yt-dlp").
     *
     * When nothing is stored, [block] receives `null` — missing cookies must never
     * introduce a new failure mode; callers pass the null straight through to
     * yt-dlp's optional `cookiefile`.
     */
    suspend fun <T> withYoutubeCookieFile(block: suspend (String?) -> T): T

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
