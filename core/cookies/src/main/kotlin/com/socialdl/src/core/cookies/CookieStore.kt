package com.socialdl.src.core.cookies

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.Platform
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Refresh state of a stored jar: file mtime is the "refreshed at" timestamp. */
    data class JarState(val lastRefreshedAt: Long)

    /** Current jar for [platform], or `null` when none is stored. */
    fun jarState(platform: Platform): JarState?

    /**
     * Persists a complete Netscape-format jar ([NetscapeCookieJarWriter.buildJarContent]
     * output or a validated manual import), encrypting at rest.
     */
    suspend fun save(platform: Platform, jarContent: String)

    /** Deletes the stored jar for [platform] (and the capture browser session). */
    suspend fun clear(platform: Platform)

    /**
     * Manual `cookies.txt` import fallback (§2.6). The file is validated
     * (tab-delimited, correct column count) *before* anything is accepted; a file
     * with a single malformed data line is rejected wholesale.
     *
     * @return `null` when the jar was stored; otherwise the human-readable failure
     *   reason for the caller's message surface.
     */
    suspend fun importNetscapeFile(platform: Platform, uri: Uri): String?

    /**
     * Decrypts the stored jar for [platform] into a throwaway file under `cacheDir`
     * and hands the path to [block]; the temp file is deleted in a `finally` the
     * moment the block returns.
     *
     * When nothing is stored (or the jar cannot be decrypted), [block] receives
     * `null` — missing cookies must never introduce a new failure mode; callers pass
     * the null straight through to the extractor's optional cookiefile.
     */
    suspend fun <T> withCookieFile(platform: Platform, block: suspend (String?) -> T): T
}

/**
 * [CookieStore] implementation: `EncryptedFile` at rest + a throwaway cacheDir
 * decrypt for the extractor's cookiefile hand-off, per §2.6/§2.7.
 */
@Singleton
class CookieStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CookieStore {

    private val tag = "CookieStore"

    /** Serializes save/clear/decrypt so a capture racing a clear can't interleave. */
    private val mutex = Mutex()

    /** Building the MasterKey hits the Keystore; do it once per process. */
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val tempDir: File by lazy {
        File(context.cacheDir, "cookies_bridge").also { dir ->
            dir.mkdirs()
            // Process death mid-extraction skips the finally in withCookieFile,
            // leaving a DECRYPTED jar on disk. Purge any survivors at the start of
            // the next extraction — the decrypted jar must never outlive one call.
            dir.listFiles()?.forEach { stale -> runCatching { stale.delete() } }
        }
    }

    /**
     * YouTube keeps its original filename (`youtube_cookies.txt.enc`) so existing
     * installs remain valid; other platforms use `<platform>_cookies.txt.enc`.
     */
    private fun jarFile(platform: Platform): File {
        val name = when (platform) {
            Platform.YOUTUBE -> "youtube_cookies.txt.enc"
            else -> "${platform.name.lowercase(Locale.ROOT)}_cookies.txt.enc"
        }
        return File(File(context.filesDir, "cookies"), name)
    }

    /**
     * Scratch directory for atomic writes. `EncryptedFile` authenticates the
     * ciphertext with the **file name** as associated data
     * (`EncryptedFile.openFileOutput()` / `openFileInput()` pass `mFile.getName()`),
     * so the staging copy MUST keep the exact basename of the final jar. Writing
     * `<jar>.tmp` and renaming it used to leave every saved jar undecryptable.
     *
     * A sibling directory is used so the rename stays on the same filesystem
     * (atomic on Android's ext4/f2fs).
     */
    private val stagingDir: File by lazy {
        File(File(context.filesDir, "cookies"), ".staging").also { dir ->
            dir.mkdirs()
            // Sweep a half-written file left by a crash in a previous process.
            dir.listFiles()?.forEach { stale -> runCatching { stale.delete() } }
        }
    }

    private fun stagingFileFor(target: File): File =
        File(stagingDir, target.name).also { runCatching { it.delete() } }

    /**
     * The jar file's mtime IS the refresh state: `save()` writes it, so no separate
     * stamping is needed. Callers must read this on a background dispatcher.
     */
    override fun jarState(platform: Platform): CookieStore.JarState? {
        val file = jarFile(platform)
        if (!file.exists() || file.length() == 0L) return null
        return CookieStore.JarState(lastRefreshedAt = file.lastModified())
    }

    override suspend fun save(platform: Platform, jarContent: String) {
        if (jarContent.isBlank()) {
            Logger.w(tag, "save(${platform.name}) ignored: jar content is blank")
            return
        }
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // Write the encrypted jar to a staging file with the SAME basename,
                // then rename over the target. A crash mid-write must not destroy
                // the last good jar, and EncryptedFile's file-name AAD must match
                // between write and read.
                val target = jarFile(platform)
                target.parentFile?.mkdirs()
                val temp = stagingFileFor(target)
                try {
                    createEncryptedFile(temp).openFileOutput().use { out ->
                        out.write(jarContent.toByteArray())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!e.isCryptoOrCorruptionFailure()) throw e
                    resetCorruptedKeyset(e)
                    val retryTemp = stagingFileFor(target)
                    createEncryptedFile(retryTemp).openFileOutput().use { out ->
                        out.write(jarContent.toByteArray())
                    }
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        throw IOException("Could not atomically replace ${target.name}")
                    }
                }
                // Older builds staged as `<jar>.tmp`; those files were encrypted
                // under a different AAD name and can never be decrypted. Remove any
                // survivor instead of leaving dead ciphertext on disk.
                runCatching { File(target.parentFile, "${target.name}.tmp").delete() }
            }
            Logger.i(tag, "${platform.name} cookie jar saved (${jarContent.length} chars)")
        }
    }

    /**
     * Deletes only the encrypted jar. [CookieStore] owns persistence; the live WebView
     * session is wiped separately by `CookieCaptureController.clearCaptureSession()`,
     * which Settings' clear action invokes alongside this.
     */
    override suspend fun clear(platform: Platform) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = jarFile(platform)
                if (file.exists()) file.delete()
                // A crash could have left a staging copy behind; it is encrypted
                // under the same key as the real jar, so drop it with the jar.
                runCatching { File(stagingDir, file.name).delete() }
                runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
            }
            Logger.i(tag, "${platform.name} cookie jar cleared")
        }
    }

    override suspend fun importNetscapeFile(platform: Platform, uri: Uri): String? {
        var tooLarge = false
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            tooLarge = true
                            return@use null
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            }.getOrNull()
        }
        if (tooLarge) {
            return "Cookie file is larger than 5 MB — export a normal cookies.txt instead."
        }
        if (text == null) {
            Logger.w(tag, "Cookie import: document unreadable ($uri)")
            return "Import failed: the selected file could not be read."
        }

        val validation = NetscapeCookieJarWriter.validateNetscapeFile(text)
        return when {
            validation.hasInvalidLines ->
                "File contains malformed cookie lines — expected 7 tab-delimited columns per line (Netscape format)."
            validation.hasNoCookies ->
                "No cookie data lines found — is this an exported cookies.txt?"
            else -> {
                // Manual imports must be re-emitted as a complete jar: the required
                // Netscape header is prepended and every validated line gets a
                // canonical trailing newline (yt-dlp/gallery-dl reject a headerless jar).
                save(platform, NetscapeCookieJarWriter.buildJarFromValidatedLines(validation.validLines))
                null
            }
        }
    }

    override suspend fun <T> withCookieFile(
        platform: Platform,
        block: suspend (String?) -> T
    ): T {
        val prefix = platform.name.lowercase(Locale.ROOT)
        var tempFile: File? = null

        // ONE try/finally covers the decryption AND the block invocation: when a
        // CancellationException lands during or right after decryption, the decrypted
        // copy is still deleted before this function returns/throws.
        return try {
            // The state check, throwaway-dir (re)creation, decryption and corrupt-jar
            // cleanup all run under the same lock as save()/clear(), so no TOCTOU race
            // can delete a jar mid-read or interleave with a capture.
            val decryptedPath = mutex.withLock {
                withContext(Dispatchers.IO) {
                    if (jarState(platform) == null) {
                        null
                    } else {
                        // `tempDir`'s `by lazy` mkdirs() only ran once; the OS or user
                        // can clear cacheDir while the app is running, so re-create it
                        // on every decryption. Without this, the plaintext temp file
                        // would fail to be written and cookies would silently vanish.
                        tempDir.mkdirs()
                        val file = File(tempDir, "${prefix}_cookies_${System.nanoTime()}.txt")
                        tempFile = file
                        try {
                            createEncryptedFile(platform).openFileInput().use { input ->
                                file.outputStream().use { input.copyTo(it) }
                            }
                            file.absolutePath
                        } catch (e: CancellationException) {
                            // Cancellation is not corruption: rethrow untouched and
                            // leave the encrypted jar in place.
                            throw e
                        } catch (e: Exception) {
                            Logger.e(
                                tag,
                                "Failed to decrypt ${platform.name} cookie jar; extraction continues without cookies",
                                e
                            )
                            // Only a crypto/corruption failure proves the jar is dead
                            // weight (Keystore key lost on restore/clear-data, or invalid
                            // ciphertext). A generic IOException — cache dir cleared,
                            // ENOSPC, transient read error — must NEVER destroy the last
                            // good jar: the next extraction may succeed.
                            if (e.isCryptoOrCorruptionFailure()) {
                                runCatching { jarFile(platform).delete() }
                                    .onFailure {
                                        Logger.w(tag, "Best-effort corrupt cookie jar delete failed", it)
                                    }
                            }
                            null
                        }
                    }
                }
            }

            if (decryptedPath != null) {
                Logger.d(tag, "Decrypted ${platform.name} cookie jar to throwaway ${tempFile?.name}")
                block(decryptedPath)
            } else {
                block(null)
            }
        } finally {
            // The decrypted copy must not outlive the call that needed it.
            tempFile?.let { file ->
                runCatching { file.delete() }
                    .onFailure { Logger.w(tag, "Best-effort temp cookie delete failed", it) }
            }
        }
    }

    /**
     * True when [this] — or any cause in its chain — is a crypto/format failure,
     * meaning the jar can never be decrypted on this device. Tink's corrupt-keyset
     * exception is shaded, so it is matched on its simple name instead of importing
     * a package path that varies between versions.
     */
    private fun Throwable.isCryptoOrCorruptionFailure(): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = this
        while (current != null && seen.add(current)) {
            if (current is GeneralSecurityException) return true
            if (current::class.java.simpleName.contains("InvalidProtocolBufferException")) return true
            current = current.cause
        }
        return false
    }

    private fun createEncryptedFile(platform: Platform): EncryptedFile =
        createEncryptedFile(jarFile(platform))

    private fun createEncryptedFile(file: File): EncryptedFile {
        file.parentFile?.mkdirs()
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun resetCorruptedKeyset(cause: Throwable) {
        Logger.w(
            tag,
            "Resetting corrupted EncryptedFile keyset; existing cookie jars will be cleared",
            cause
        )
        runCatching { context.deleteSharedPreferences(ENCRYPTED_FILE_PREF_NAME) }
            .onFailure { Logger.w(tag, "Failed to delete corrupted keyset prefs", it) }
        val cookiesDir = File(context.filesDir, "cookies")
        runCatching {
            cookiesDir.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.name.endsWith(".enc")) {
                    entry.delete()
                }
            }
            stagingDir.listFiles()?.forEach { it.delete() }
        }.onFailure { Logger.w(tag, "Failed to sweep undecryptable cookie jars", it) }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        const val ENCRYPTED_FILE_PREF_NAME = "__androidx_security_crypto_encrypted_file_pref__"
    }
}


// ============================ Netscape format ============================


/**
 * Converts Android `CookieManager.getCookie()` raw `; `-joined header strings into
 * the Netscape "HTTP Cookie File" line format yt-dlp's `cookiefile` option expects
 * (§2.6).
 *
 * `CookieManager` only exposes `name=value` per URL — it never surfaces the
 * domain/path/expiry/secure attributes individually — so they are filled with the
 * safe defaults the spec calls for:
 *
 *  - `includeSubdomains` = TRUE
 *  - `path` = `/`
 *  - `secure` = TRUE (YouTube/Google auth cookies are HTTPS-only anyway)
 *  - `expiry` = a far-future timestamp below the signed 32-bit `time_t` limit, so
 *    32-bit Python builds cannot overflow it; a stale-but-optimistic expiry is
 *    harmless because a failed extraction re-surfaces `AuthWallDetected` and
 *    re-triggers capture.
 *
 * Pure Kotlin so it can be unit-tested without an Android runtime.
 */
object NetscapeCookieJarWriter {

    /** Exact header http.cookiejar's MozillaCookieJar looks for when loading. */
    const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"

    /**
     * 2038-01-01 00:00 UTC — far beyond any real session lifetime, but safely
     * below `Int.MAX_VALUE` so a 32-bit `time_t` cannot overflow (4102444800,
     * the old 2100 value, wraps negative there).
     */
    const val FAR_FUTURE_EXPIRY_SECONDS = 2_145_916_800L

    /**
     * One harvest: the raw [header] `CookieManager.getCookie(url)` returned for [url],
     * labeled with the Netscape [cookieDomain] this origin should be written under.
     * [header] is blank until the controller stamps in its harvest result.
     */
    data class Harvest(
        val url: String,
        val cookieDomain: String,
        val header: String = "",
    )

    /**
     * Builds the complete multi-line `cookies.txt` content, or `null` when no valid
     * cookie survives parsing (callers must treat null as "nothing captured").
     *
     * Duplicates within one domain are deduped on `(domain, name, value)` — first
     * harvest wins. The same name/value under two different domains is kept
     * because the two cookies are scoped independently (Google sets `SID` for
     * both `.google.com` and `.accounts.google.com`).
     */
    fun buildJarContent(harvests: List<Harvest>): String? {
        val lines = buildLines(harvests)
        if (lines.isEmpty()) return null
        return buildString {
            append(NETSCAPE_HEADER).append('\n')
            append("# Generated by SocialDL core:cookies — do not edit.\n")
            for (line in lines) {
                append(line).append('\n')
            }
        }
    }

    /**
     * Re-emits manually validated data lines as a complete jar. The Netscape header
     * is mandatory (yt-dlp/gallery-dl refuse a jar that does not start with it) and
     * every line ends with a canonical trailing newline.
     */
    fun buildJarFromValidatedLines(validLines: List<String>): String = buildString {
        append(NETSCAPE_HEADER).append('\n')
        for (line in validLines) {
            append(line).append('\n')
        }
    }

    /** Netscape data lines (no header/comments), deduped. Empty list when nothing valid. */
    fun buildLines(harvests: List<Harvest>): List<String> {
        val seen = HashSet<Triple<String, String, String>>() // (domain, name, value)
        val out = ArrayList<String>()
        for (harvest in harvests) {
            for (pair in parseHeader(harvest.header)) {
                if (!seen.add(Triple(harvest.cookieDomain, pair.first, pair.second))) continue
                out.add(
                    listOf(
                        harvest.cookieDomain,
                        "TRUE",                              // includeSubdomains
                        "/",
                        "TRUE",                              // secure default (HTTPS-only)
                        FAR_FUTURE_EXPIRY_SECONDS.toString(),
                        pair.first,
                        pair.second,
                    ).joinToString("\t")
                )
            }
        }
        return out
    }

    /**
     * Splits one `; `-joined header into `(name, value)` pairs.
     *
     * Cookie values are kept byte-for-byte — they are base64-ish opaque tokens, and
     * UrlEncoding them would corrupt SAPISID/SID. A segment is skipped entirely when
     * it is blank, has no `=`, or contains raw tab/CR/LF (which would corrupt the
     * line-oriented Netscape file).
     */
    fun parseHeader(header: String): List<Pair<String, String>> {
        if (header.isBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (segment in header.split("; ")) {
            // A trailing ";" before the end of a header (or stray double separators)
            // leaves a dangling ';' on the last token — cookie values cannot contain
            // ';', so it is safe to trim it off.
            val trimmed = segment.trim().trimEnd(';')
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue // no separator, or empty name
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1)
            if (name.isEmpty()) continue
            // A legal cookie name never contains ; whitespace, or raw line-breaks —
            // any of those inside name/value would corrupt the line-oriented file.
            if (name.indexOfAny(FORBIDDEN_CHARS) >= 0) continue
            if (value.indexOfAny(FORBIDDEN_CHARS) >= 0) continue
            if (name.contains(';') || name.contains(' ')) continue
            out.add(name to value)
        }
        return out
    }

    /** Result of validating a user-provided Netscape `cookies.txt` (manual import). */
    data class NetscapeValidation(
        /** Data lines that survived validation, ready to persist verbatim. */
        val validLines: List<String>,
        /** 1-based line numbers of data lines that failed validation. */
        val invalidLineNumbers: List<Int>,
    ) {
        val hasInvalidLines: Boolean get() = invalidLineNumbers.isNotEmpty()
        val hasNoCookies: Boolean get() = validLines.isEmpty()
    }

    /**
     * Validates a user-supplied Netscape cookie file before import (§2.6: correct
     * column count, tab-delimited).
     *
     * Blank lines and `#` comments are ignored — except `#HttpOnly_`-prefixed data
     * lines (the curl convention for the httponly flag), which are accepted and
     * re-emitted verbatim. A data line must be exactly 7 tab-delimited columns with
     * a dot-prefixed domain, a non-empty path/name, and a parseable expiry (`0` =
     * session cookie, accepted).
     */
    fun validateNetscapeFile(content: String): NetscapeValidation {
        val valid = ArrayList<String>()
        val invalid = ArrayList<Int>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            // trimStart() only: a trailing `\t` is a legal empty 7th column (cookie
            // value), and a blanket trim() would collapse the row to 6 columns.
            val line = rawLine.trimEnd('\r', '\n').trimStart()
            if (line.isEmpty()) return@forEachIndexed

            // curl's httponly flag lives in a "#HttpOnly_" prefix on real data lines —
            // everything else starting with "#" is a comment.
            val dataLine = if (line.startsWith("#HttpOnly_")) {
                line.removePrefix("#HttpOnly_")
            } else if (line.startsWith("#")) {
                return@forEachIndexed
            } else {
                line
            }

            val columns = dataLine.split("\t")
            // Domain may be dot-prefixed (domain cookie) or a bare host
            // (host-only cookie) — the Netscape format legalizes both, and
            // browser exports commonly contain the latter.
            val isOk = columns.size == 7 &&
                columns[0].isNotBlank() &&          // domain
                columns[2].isNotBlank() &&          // path
                columns[4].toLongOrNull() != null &&// expiry (0 = session cookie)
                columns[5].isNotBlank()             // name
            if (isOk) valid.add(line) else invalid.add(index + 1)
        }
        return NetscapeValidation(validLines = valid, invalidLineNumbers = invalid)
    }

    private val FORBIDDEN_CHARS = charArrayOf('\t', '\n', '\r')
}


// ============================ DI ============================

@Module
@InstallIn(SingletonComponent::class)
abstract class CookieModule {

    @Binds
    @Singleton
    abstract fun bindCookieStore(
        impl: CookieStoreImpl
    ): CookieStore
}
