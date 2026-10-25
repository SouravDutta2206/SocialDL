"""User-Agent runtime probe for the WebView cookie capture flow (§2.6).

Why this exists: a capture/use UA mismatch is a common reason a captured cookie
jar still gets treated as logged-out by yt-dlp. The WebView must present the same
User-Agent yt-dlp itself will present downstream.

One warmup-style function — Kotlin's `core:cookies` fetches it once via
PythonEngine (already warm from warmup()) and caches the result. It introspects
the *installed* yt-dlp so the answer stays correct after a §2.5.2 hot-swap moves
to a different yt-dlp/curl_cffi.
"""
import json


def probe_user_agent() -> str:
    """Returns {"user_agent": ...} as a JSON string.

    - Chrome impersonate UA when curl_cffi-backed impersonation is available in the
      installed yt-dlp (now, or after a future hot-swap).
    - Otherwise yt-dlp's own std_headers["User-Agent"], i.e. exactly what a
      non-impersonating yt-dlp run would send.
    - Never raises: on any internal failure the Kotlin side falls back to its
      hardcoded constant.
    """
    ua = None
    try:
        import yt_dlp
        ua = yt_dlp.utils.std_headers.get("User-Agent")
    except Exception:
        yt_dlp = None

    impersonated = _chrome_impersonate_ua()
    if impersonated:
        ua = impersonated

    return json.dumps({"user_agent": ua or ""})


def _chrome_impersonate_ua():
    """Derives the Chrome version from yt-dlp's available impersonate targets and
    returns a matching Chrome UA, or None when impersonation is unavailable."""
    try:
        from curl_cffi import requests as _curl_requests  # noqa: F401 — availability check
        import yt_dlp
        with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "skip_download": True}) as ydl:
            try:
                targets = ydl.list_impersonate_targets() or []
            except Exception:
                targets = []

            chrome_names = []
            for t in targets:
                name = str(getattr(t, "target", None) or t).lower()
                if name.startswith("chrome"):
                    chrome_names.append(name)
            if not chrome_names:
                return None

            # Targets come back in ascending version order; the last is the newest Chrome.
            best = chrome_names[-1]
            version = "".join(ch for ch in best if ch.isdigit()) or "120"
            return (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/{v}.0.0.0 Safari/537.36".format(v=version)
            )
    except Exception:
        return None
