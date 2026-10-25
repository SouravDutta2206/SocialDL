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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cookieMetaStore: DataStore<Preferences> by preferencesDataStore(
    name = "socialdl_cookies_meta"
)

/**
 * [CookieStore] implementation: `EncryptedFile` at rest + a throwaway cacheDir
 * decrypt for yt-dlp's cookiefile hand-off, per §2.6/§2.7.
 */
@Singleton
class CookieStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CookieStore {

    private val tag = "CookieStore"

    private object Keys {
        val LAST_REFRESHED = longPreferencesKey("youtube_cookies_last_refreshed")
    }

    /** Serializes save/clear/decrypt so a capture racing a clear can't interleave. */
    private val mutex = Mutex()

    private val jarFile: File by lazy { File(File(context.filesDir, "cookies"), "youtube_cookies.txt.enc") }
    private val tempDir: File by lazy { File(context.cacheDir, "cookies_bridge").also { it.mkdirs() } }

    override fun hasYoutubeCookies(): Boolean {
        return jarFile.exists() && jarFile.length() > 0
    }

    override val lastRefreshedAt: Flow<Long?> = context.cookieMetaStore.data.map { prefs ->
        prefs[Keys.LAST_REFRESHED]
    }

    override suspend fun save(jarContent: String) {
        if (jarContent.isBlank()) {
            Logger.w(tag, "save() ignored: jar content is blank")
            return
        }
        mutex.withLock {
            withContext(Dispatchers.IO) {
                createEncryptedFile().openFileOutput().use { out ->
                    out.write(jarContent.toByteArray())
                }
            }
            context.cookieMetaStore.edit { it[Keys.LAST_REFRESHED] = System.currentTimeMillis() }
            Logger.i(tag, "YouTube cookie jar saved (${jarContent.length} chars)")
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (jarFile.exists()) jarFile.delete()
            }
            context.cookieMetaStore.edit { it.remove(Keys.LAST_REFRESHED) }
            Logger.i(tag, "YouTube cookie jar cleared")
        }
    }

    override suspend fun importNetscapeFile(uri: Uri): CookieStore.ImportOutcome {
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
                save(validation.validLines.joinToString("\n"))
                CookieStore.ImportOutcome.Success
            }
        }
    }

    override suspend fun <T> withYoutubeCookieFile(block: suspend (String?) -> T): T {
        if (!hasYoutubeCookies()) return block(null)

        val tempFile = File(tempDir, "yt_cookies_${System.nanoTime()}.txt")
        try {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    createEncryptedFile().openFileInput().use { input ->
                        tempFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            Logger.d(tag, "Decrypted cookie jar to throwaway ${tempFile.name}")
            return block(tempFile.absolutePath)
        } catch (e: Exception) {
            Logger.e(tag, "Failed to decrypt cookie jar; extraction continues without cookies", e)
            return block(null)
        } finally {
            // The decrypted copy must not outlive the call that needed it.
            runCatching { tempFile.delete() }
                .onFailure { Logger.w(tag, "Best-effort temp cookie delete failed", it) }
        }
    }

    private fun createEncryptedFile(): EncryptedFile {
        jarFile.parentFile?.mkdirs()
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            jarFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }
}
