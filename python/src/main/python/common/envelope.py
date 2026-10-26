"""Shared JSON envelope contract for every Python bridge (§2.6 / Phase 5).

One dialect, two shapes:

- success: the payload itself IS the envelope (no wrapper) — Kotlin decodes the
  bridge-specific DTO directly.
- failure: ``{"error": kind, "detail": <str>, "debug_log": <tail>?, **extra}``.

Extraction error kinds shared with Kotlin: ``auth_wall`` | ``rate_limited`` |
``network`` | ``unavailable`` | ``exception``. Login-only kinds (``two_factor``,
``bad_credentials``, ``checkpoint``) are returned by the login entry points and
consumed by the login UI, never by the extraction error mapper.

Marker tables:
- transport markers (429 / DNS / timeouts) are platform-neutral and live here;
- auth-wall markers are platform-domain knowledge and stay inside each bridge.
"""

import json

AUTH_WALL = "auth_wall"
RATE_LIMITED = "rate_limited"
NETWORK = "network"
UNAVAILABLE = "unavailable"
EXCEPTION = "exception"

# Ordered: rate-first, then network. Both are transport-level, so shared.
_RATE_MARKERS = (
    "http error 429",
    "too many requests",
    "429",
)

_NETWORK_MARKERS = (
    "getaddrinfo",
    "connection",
    "timed out",
    "unable to download webpage",
    "network",
)

# Diagnostics only: how many collected log lines ride along on an error envelope.
TAIL_LINES = 40


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


def classify(message, auth_markers=()) -> str:
    """Ordered kind resolution: auth → rate → network → unavailable.

    ``auth_markers`` is supplied by the bridge because auth-wall vocabulary is
    platform-specific (yt-dlp messages vs. gallery-dl exception text).
    """
    text = str(message).lower()
    if any(marker in text for marker in auth_markers):
        return AUTH_WALL
    if any(marker in text for marker in _RATE_MARKERS):
        return RATE_LIMITED
    if any(marker in text for marker in _NETWORK_MARKERS):
        return NETWORK
    return UNAVAILABLE
