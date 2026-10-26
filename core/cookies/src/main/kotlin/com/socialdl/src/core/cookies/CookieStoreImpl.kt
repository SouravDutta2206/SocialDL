package com.socialdl.src.core.cookies

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
                createEncryptedFile(platform).openFileOutput().use { out ->
                    out.write(jarContent.toByteArray())
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
            context.cookieMetaStore.edit { it.remove(Keys.lastRefreshed(platform)) }
            Logger.i(tag, "${platform.name} cookie jar cleared")
        }
    }

    override suspend fun importNetscapeFile(platform: Platform, uri: Uri): CookieStore.ImportOutcome {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }.getOrNull()
        } ?: run {
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
        try {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    createEncryptedFile(platform).openFileInput().use { input ->
                        tempFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            Logger.d(tag, "Decrypted ${platform.name} cookie jar to throwaway ${tempFile.name}")
            return block(tempFile.absolutePath)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to decrypt ${platform.name} cookie jar; extraction continues without cookies", e)
            return block(null)
        } finally {
            // The decrypted copy must not outlive the call that needed it.
            runCatching { tempFile.delete() }
                .onFailure { Logger.w(tag, "Best-effort temp cookie delete failed", it) }
        }
    }

    private fun createEncryptedFile(platform: Platform): EncryptedFile {
        val file = jarFile(platform)
        file.parentFile?.mkdirs()
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }
}
