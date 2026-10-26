"""Instagram extraction via gallery-dl (replaces the Instaloader bridge).

gallery-dl's Instagram extractor is the maintained path; Instaloader's web
GraphQL endpoints are increasingly 401-blocked. We drive gallery-dl's extractor
API, collect direct CDN URLs plus normalized metadata, and leave byte downloads
to Kotlin.

Cookies are a standard Netscape jar produced by the WebView capture flow (the
same format yt-dlp consumes): ``sessionid`` + ``ds_user_id`` mark a signed-in
Instagram session.
"""

import gallery_dl  # noqa: F401 — import proves Chaquopy packaging at boot
from gallery_dl import config, exception as gd_exc
from gallery_dl.extractor import find
from gallery_dl.extractor.message import Message

from common.envelope import (
    AUTH_WALL,
    NETWORK,
    RATE_LIMITED,
    UNAVAILABLE,
    classify,
    fail,
    succeed,
)


def extract(url, cookiefile=None, user_agent=None):
    """Returns the flat success payload consumed by InstagramExtractor.

    Payload: ``{"shortcode", "caption", "owner", "items": [
        {"index", "media_type", "url", "thumbnail_url", "duration"}
    ]}``
    """
    _configure(cookiefile, user_agent)

    try:
        extractor = find(url)
    except gd_exc.NoExtractorError:
        return fail(
            UNAVAILABLE,
            "Unsupported Instagram URL — only posts, reels, and IGTV links are supported",
        )

    try:
        post = {}
        files = []
        for message in extractor:
            kind = message[0]
            if kind == Message.Directory:
                post.update(message[2] or {})
            elif kind == Message.Url:
                files.append((message[1], message[2] or {}))
    except Exception as error:
        return _failure_envelope(error, cookiefile)

    if not files:
        return fail(UNAVAILABLE, "This post contains no downloadable media")

    items = []
    for fallback_index, (media_url, meta) in enumerate(files, 1):
        items.append({
            "index": meta.get("num") or fallback_index,
            "media_type": "video" if meta.get("video_url") else "image",
            "url": media_url,
            "thumbnail_url": meta.get("display_url"),
            "duration": meta.get("duration"),
        })

    return succeed({
        "shortcode": post.get("post_shortcode") or files[0][1].get("post_shortcode"),
        "caption": post.get("description"),
        "owner": post.get("username"),
        "items": items,
    })


def _configure(cookiefile, user_agent):
    # Deterministic behavior: ignore gallery-dl's user config files.
    config.clear()
    if cookiefile:
        config.setg("cookies", cookiefile)
    if user_agent:
        config.setg("user-agent", user_agent)

    # Plain CDN URLs instead of ytdl: manifest URLs (Kotlin downloads bytes and
    # muxing is not needed for these already-merged Instagram streams).
    config.set(("extractor", "instagram"), "videos", "merged")
    config.set(("extractor", "instagram"), "audio", False)
    # gallery-dl's Instagram extractor paces requests 6-12s by default; one post
    # per app call is a single API request, so that pacing only adds latency.
    config.set(("extractor", "instagram"), "sleep-request", 0)


def _failure_envelope(error, cookiefile):
    """Maps gallery-dl exceptions, keeping raw text in debug_log (never the toast)."""
    text = str(error).lower()

    if isinstance(error, gd_exc.NoExtractorError):
        return fail(UNAVAILABLE, "Unsupported Instagram URL")

    if isinstance(error, (gd_exc.AuthenticationError, gd_exc.AuthorizationError)):
        return fail(
            AUTH_WALL,
            "Instagram needs a signed-in session for this content",
            tail=[str(error)],
        )

    if isinstance(error, gd_exc.NotFoundError):
        return fail(
            UNAVAILABLE,
            "This Instagram post is private, deleted, or unavailable",
            tail=[str(error)],
        )

    if isinstance(error, gd_exc.HttpError):
        code = getattr(error, "status", None)
        if code == 429:
            return fail(
                RATE_LIMITED,
                "Instagram is rate-limiting requests — try again in a few minutes",
                tail=[str(error)],
            )
        if code in (401, 403):
            return fail(
                AUTH_WALL,
                "Instagram needs a signed-in session for this content",
                tail=[str(error)],
            )

    if "redirect to login" in text or "login page" in text or "challenge page" in text:
        detail = (
            "The saved Instagram cookies are no longer valid — capture them again"
            if cookiefile
            else "Instagram needs a signed-in session for this content"
        )
        return fail(AUTH_WALL, detail, tail=[str(error)])

    if "please wait" in text or "rate limit" in text or "429" in text:
        return fail(
            RATE_LIMITED,
            "Instagram is rate-limiting requests — try again in a few minutes",
            tail=[str(error)],
        )

    if "fetching post metadata failed" in text or "not found" in text:
        return fail(
            UNAVAILABLE,
            "This Instagram post is private, deleted, or unavailable",
            tail=[str(error)],
        )

    kind = classify(error)
    if kind == NETWORK:
        return fail(NETWORK, "Couldn't reach Instagram — check your connection", tail=[str(error)])

    return fail(UNAVAILABLE, "Couldn't load this Instagram post", tail=[str(error)])
