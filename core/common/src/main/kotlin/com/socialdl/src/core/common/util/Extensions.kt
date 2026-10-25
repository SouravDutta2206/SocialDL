package com.socialdl.src.core.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.Locale

/**
 * Formats duration in seconds to "M:SS" or "H:MM:SS" display string.
 */
fun Int?.toTimeString(): String {
    if (this == null || this <= 0) return "0:00"
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Formats byte count to human-readable size string.
 * e.g. 1024 -> "1.0 KB", 1048576 -> "1.0 MB"
 */
fun Long?.toHumanReadableSize(): String {
    if (this == null || this <= 0) return "~ unknown"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
}

/**
 * Validates whether a string looks like a URL (has a recognized scheme or is host-like).
 */
fun String.isLikelyUrl(): Boolean {
    val trimmed = this.trim()
    if (trimmed.isBlank()) return false
    // Has explicit scheme
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true
    // Looks like a domain (contains a dot, no spaces)
    return !trimmed.contains(' ') && trimmed.contains('.')
}

/**
 * Ensures a URL string has an https:// scheme prefix.
 */
fun String.ensureHttps(): String {
    val trimmed = this.trim()
    return when {
        trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "https://")
        else -> "https://$trimmed"
    }
}

/**
 * Safely reads the primary clipboard text.
 *
 * Unlike raw `primaryClip.getItemAt(0).text` access, this never crashes on
 * description-only clips (whose items have no text), never NPEs when no clipboard
 * service or clip exists, and falls back to coercing non-text items (URIs, intents).
 *
 * @return the clipboard text, or null when unavailable/blank.
 */
fun readClipboardText(context: Context): String? {
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        for (index in 0 until clip.itemCount) {
            val item: ClipData.Item = clip.getItemAt(index) ?: continue
            val text = item.coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) return text
        }
        null
    } catch (_: Exception) {
        null
    }
}
