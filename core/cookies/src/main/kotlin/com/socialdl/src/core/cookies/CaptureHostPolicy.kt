package com.socialdl.src.core.cookies

/**
 * Host-matching rules shared by the capture WebView's navigation allowlist and
 * the login-complete check (§2.6).
 *
 * Kept deliberately Android-free (`android.net.Uri` is not available in plain
 * JVM unit tests) so the policy can be exercised without a device.
 */
object CaptureHostPolicy {

    /**
     * Google serves its account pages from country domains (`google.co.in`,
     * `google.com.au`, `google.co.uk`, ...) as well as `google.com`. After a 2FA
     * confirmation Google may land on one of those — refusing them is what used
     * to bounce a completed sign-in out to the external browser, leaving the
     * WebView spinning on the accounts page forever.
     *
     * Matched against the whole host, so the optional `.*\.` accepts any
     * subdomain while the TLD fallback still rejects lookalikes such as
     * `google.evil.com` or `google.co.in.evil.com`.
     */
    val GOOGLE_COUNTRY_DOMAIN =
        Regex("""(^|.*\.)google\.[a-z]{2,3}(\.[a-z]{2})?$""", RegexOption.IGNORE_CASE)

    /**
     * True when [host] is one of [allowedHosts] or a subdomain of one, or matches
     * one of [extraPatterns] (domain families such as Google's ccTLDs).
     */
    fun isAllowedHost(
        host: String?,
        allowedHosts: List<String>,
        extraPatterns: List<Regex> = emptyList(),
    ): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (allowedHosts.any { allowed ->
                val target = normalizeHost(allowed) ?: return@any false
                normalized == target || normalized.endsWith(".$target")
            }
        ) {
            return true
        }
        return extraPatterns.any { pattern -> pattern.matches(normalized) }
    }

    /**
     * True when [host] belongs to the success origin's domain family. `www.` is
     * stripped before comparison so a flow that finishes on `m.youtube.com`,
     * `music.youtube.com` or the bare `youtube.com` still auto-completes.
     */
    fun matchesSuccessHost(host: String?, successOrigin: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        val originHost = normalizeHost(hostOf(successOrigin)) ?: return false
        val family = originHost.removePrefix("www.")
        return normalized == family || normalized.endsWith(".$family")
    }

    /** Lowercases, trims and drops a trailing root dot; null for blank input. */
    fun normalizeHost(host: String?): String? = host
        ?.trim()
        ?.lowercase()
        ?.trimEnd('.')
        ?.takeIf { it.isNotEmpty() }

    /**
     * Extracts the host from an absolute URL (or a bare `host/path` string)
     * without `android.net.Uri`, so it is safe to call from unit tests.
     * Ports, user-info and trailing dots are stripped.
     */
    fun hostOf(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        val afterScheme = value.substringAfter("://", value)
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }
        return normalizeHost(host)?.takeIf { it.isNotBlank() }
    }
}
