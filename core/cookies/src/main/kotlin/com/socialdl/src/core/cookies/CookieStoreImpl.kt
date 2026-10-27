package com.socialdl.src.core.cookies

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cookieMetaStore: DataStore<Preferences> by preferencesDataStore(
    name = "socialdl_cookies_meta"
)

/**
 * [CookieStore] implementation: `EncryptedFile` at rest + a throwaway cacheDir
 * decrypt for the extractor's cookiefile hand-off, per §2.6/§2.7.
 */
@Singleton
class CookieStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CookieStore {

    private val tag = "CookieStore"

    private object Keys {
        /**
         * YouTube keeps its original key so upgrading installs retain the Settings
         * status; every other platform uses a namespaced key.
         */
        fun lastRefreshed(platform: Platform) = when (platform) {
            Platform.YOUTUBE -> longPreferencesKey("youtube_cookies_last_refreshed")
            else -> longPreferencesKey("cookies_last_refreshed_${platform.name.lowercase(Locale.ROOT)}")
        }
    }

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

    override fun hasCookies(platform: Platform): Boolean {
        val file = jarFile(platform)
        return file.exists() && file.length() > 0
    }

    override fun lastRefreshedAt(platform: Platform): Flow<Long?> =
        context.cookieMetaStore.data.map { prefs -> prefs[Keys.lastRefreshed(platform)] }

    override suspend fun save(platform: Platform, jarContent: String) {
        if (jarContent.isBlank()) {
            Logger.w(tag, "save(${platform.name}) ignored: jar content is blank")
            return
        }
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // Write the encrypted jar to a sibling temp file, then rename over
                // the target. A crash mid-write must not destroy the last good jar.
                val target = jarFile(platform)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.tmp")
                runCatching { temp.delete() }
                createEncryptedFile(temp).openFileOutput().use { out ->
                    out.write(jarContent.toByteArray())
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        throw IOException("Could not atomically replace ${target.name}")
                    }
                }
            }
            context.cookieMetaStore.edit {
                it[Keys.lastRefreshed(platform)] = System.currentTimeMillis()
            }
            Logger.i(tag, "${platform.name} cookie jar saved (${jarContent.length} chars)")
        }
    }

    override suspend fun clear(platform: Platform) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = jarFile(platform)
                if (file.exists()) file.delete()
            }
            // Clearing only the encrypted jar leaves the live WebView session
            // behind: the next capture would auto-succeed and "sign out" would be
            // a lie. Drop the WebView cookie store too.
            withContext(Dispatchers.Main) {
                runCatching {
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }
                }.onFailure { Logger.w(tag, "Failed to clear WebView cookies", it) }
            }
            context.cookieMetaStore.edit { it.remove(Keys.lastRefreshed(platform)) }
            Logger.i(tag, "${platform.name} cookie jar cleared")
        }
    }

    override suspend fun importNetscapeFile(platform: Platform, uri: Uri): CookieStore.ImportOutcome {
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
            return CookieStore.ImportOutcome.Rejected(
                reason = "Cookie file is larger than 5 MB — export a normal cookies.txt instead."
            )
        }
        if (text == null) {
            Logger.w(tag, "Cookie import: document unreadable ($uri)")
            return CookieStore.ImportOutcome.Unreadable
        }

        val validation = NetscapeCookieJarWriter.validateNetscapeFile(text)
        return when {
            validation.hasInvalidLines -> CookieStore.ImportOutcome.Rejected(
                reason = "File contains malformed cookie lines — expected 7 tab-delimited columns per line (Netscape format).",
                invalidLineNumbers = validation.invalidLineNumbers
            )
            validation.hasNoCookies -> CookieStore.ImportOutcome.Rejected(
                reason = "No cookie data lines found — is this an exported cookies.txt?"
            )
            else -> {
                // Re-join with a canonical trailing newline; every line already validated.
                save(platform, validation.validLines.joinToString("\n"))
                CookieStore.ImportOutcome.Success
            }
        }
    }

    override suspend fun <T> withCookieFile(
        platform: Platform,
        block: suspend (String?) -> T
    ): T {
        if (!hasCookies(platform)) return block(null)

        val prefix = platform.name.lowercase(Locale.ROOT)
        val tempFile = File(tempDir, "${prefix}_cookies_${System.nanoTime()}.txt")

        // Decrypt in its own try/catch: only a decryption failure may degrade to
        // "no cookies". Exceptions from [block] (network, Python, cancellation)
        // must propagate untouched and must never trigger a second invocation.
        val decrypted = try {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    createEncryptedFile(platform).openFileInput().use { input ->
                        tempFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "Failed to decrypt ${platform.name} cookie jar; extraction continues without cookies", e)
            // A jar that cannot be decrypted (corrupt file or restored without the
            // Keystore key) is dead weight: delete it so the UI stops reporting
            // "signed in" for a session that can never be used.
            runCatching { jarFile(platform).delete() }
            false
        }

        return try {
            if (decrypted) {
                Logger.d(tag, "Decrypted ${platform.name} cookie jar to throwaway ${tempFile.name}")
                block(tempFile.absolutePath)
            } else {
                block(null)
            }
        } finally {
            // The decrypted copy must not outlive the call that needed it.
            runCatching { tempFile.delete() }
                .onFailure { Logger.w(tag, "Best-effort temp cookie delete failed", it) }
        }
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

    private companion object {
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
    }
}
