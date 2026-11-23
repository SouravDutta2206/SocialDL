"""Shared JSON envelope contract for every Python bridge (§2.6 / Phase 5).

One dialect, two shapes:

- success: the payload itself IS the envelope (no wrapper) — Kotlin decodes the
  bridge-specific DTO directly.
- failure: ``{"error": kind, "detail": <str>, "debug_log": <tail>?, **extra}``.

Extraction error kinds shared with Kotlin: ``auth_wall`` | ``rate_limited`` |
``network`` | ``unavailable`` | ``no_media`` | ``exception``. Login-only kinds
(``two_factor``, ``bad_credentials``, ``checkpoint``) are returned by the login
entry points and consumed by the login UI, never by the extraction error mapper.

Marker tables:
- transport markers (429 / DNS / timeouts) are platform-neutral and live here;
- auth-wall markers are platform-domain knowledge and stay inside each bridge.

HTTP status codes are matched through [has_http_status] instead of bare
substrings: an error message can legitimately contain the digits ``429``,
``401`` or ``403`` inside a tweet/video id or a signed CDN URL, and a bare
``"429" in text`` check classified those failures as rate limits.
"""

import json
import re

AUTH_WALL = "auth_wall"
RATE_LIMITED = "rate_limited"
NETWORK = "network"
UNAVAILABLE = "unavailable"
NO_MEDIA = "no_media"
EXCEPTION = "exception"

# Ordered: rate-first, then network. Both are transport-level, so shared.
# Bare digits are deliberately absent; [has_http_status] recognizes real HTTP
# status contexts without matching numeric ids or query parameters.
_RATE_MARKERS = (
    "http error 429",
    "too many requests",
)

_NETWORK_MARKERS = (
    "getaddrinfo",
    "connection refused",
    "connection reset",
    "connection aborted",
    "connection error",
    "timed out",
    "unable to download webpage",
    "network",
)

# Permanent/unavailable responses must win over the transport marker above:
# yt-dlp wraps a deleted video as "Unable to download webpage: HTTP Error 404".
_UNAVAILABLE_MARKERS = (
    "http error 404",
    "http error 410",
    "video unavailable",
    "private video",
    "has been removed",
    "does not exist",
    "not available",
)

# Diagnostics only: how many collected log lines ride along on an error envelope.
TAIL_LINES = 40


def has_http_status(message, *codes):
    """True when [message] names an HTTP status from [codes] as a real status.

    Matches only anchored status contexts ("HTTP Error 429", "status code: 403",
    "401 Unauthorized", "429 Too Many Requests", ...). Bare digits inside ids,
    filenames, or signed URLs (``abc429``, ``142900401403999``) never match.
    """
    text = str(message or "").lower()
    wanted = [str(code) for code in codes if str(code).isdigit()]
    if not wanted:
        return False
    alternatives = "|".join(wanted)
    status_context = re.compile(
        r"\b(?:http(?:[\s/][0-9.]+)?(?:\s+error)?|status(?:\s+code)?|error|code)"
        r"\s*[:=]?\s*(?:%s)\b" % alternatives
    )
    phrase_context = re.compile(
        r"(?<!\d)(?:%s)\s+"
        r"(?:too\s+many\s+requests|unauthorized|forbidden|client\s+error|rate\s+limit)"
        % alternatives
    )
    return bool(status_context.search(text) or phrase_context.search(text))


def succeed(payload: dict) -> str:
    """Flat success dialect: the payload is the envelope. No wrapper."""
    return json.dumps(payload)


def fail(kind: str, detail, tail=None, extra: dict | None = None) -> str:
    """Builds the flat error envelope every Kotlin extractor decodes."""
    envelope = {"error": kind, "detail": str(detail)}
    if tail:
        envelope["debug_log"] = "\n".join(list(tail)[-TAIL_LINES:])
    if extra:
        envelope.update(extra)
    return json.dumps(envelope)


def classify(message, auth_markers=(), auth_statuses=()) -> str:
    """Ordered kind resolution: auth → rate → unavailable → network.

    ``auth_markers`` is supplied by the bridge because auth-wall vocabulary is
    platform-specific (yt-dlp messages vs. gallery-dl exception text).
    ``auth_statuses`` lets a bridge match HTTP auth codes (401/403) safely via
    [has_http_status] instead of a bare substring. Permanent HTTP 404/410
    responses are checked before transport markers so a deleted or private
    video is not reported as a connection problem.
    """
    text = str(message).lower()
    if has_http_status(text, *auth_statuses):
        return AUTH_WALL
    if any(marker in text for marker in auth_markers):
        return AUTH_WALL
    if any(marker in text for marker in _RATE_MARKERS) or has_http_status(text, 429):
        return RATE_LIMITED
    if any(marker in text for marker in _UNAVAILABLE_MARKERS):
        return UNAVAILABLE
    if any(marker in text for marker in _NETWORK_MARKERS):
        return NETWORK
    return UNAVAILABLE
