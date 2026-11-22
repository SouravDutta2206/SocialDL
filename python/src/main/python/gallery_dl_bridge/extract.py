"""Instagram extraction via gallery-dl (replaces the Instaloader bridge).

gallery-dl's Instagram extractor is the maintained path; Instaloader's web
GraphQL endpoints are increasingly 401-blocked. We drive gallery-dl's extractor
API, collect direct CDN URLs plus normalized metadata, and leave byte downloads
to Kotlin.

Cookies are a standard Netscape jar produced by the WebView capture flow (the
same format yt-dlp consumes): ``sessionid`` + ``ds_user_id`` mark a signed-in
Instagram session.
"""

import re

import gallery_dl  # noqa: F401 — import proves Chaquopy packaging at boot
from gallery_dl import exception as gd_exc

from common import gallery_dl_util
from common.envelope import (
    AUTH_WALL,
    NETWORK,
    RATE_LIMITED,
    UNAVAILABLE,
    classify,
    fail,
    succeed,
)

# Only single-post surfaces are extractable. Anything else (profiles, explore,
# hashtags) makes gallery-dl page through an unbounded feed, which is both a
# privacy/bandwidth hazard and a way to pin a dispatcher thread for minutes.
_POST_URL_RE = re.compile(
    r"/(?:p|reel|reels|tv|stories)/[A-Za-z0-9_.\-]+", re.IGNORECASE
)

_UNSUPPORTED_URL = (
    "Unsupported Instagram URL — only posts, reels, stories, and IGTV links are supported"
)

# Keep a small delay between requests: removing pacing entirely (0) makes rapid
# successive carousel/manual fetches much more likely to hit HTTP 429.
_INSTAGRAM_OPTIONS = {
    # Plain CDN URLs instead of ytdl: manifest URLs (Kotlin downloads bytes and
    # muxing is not needed for these already-merged Instagram streams).
    "videos": "merged",
    "audio": False,
    "sleep-request": 1,
}


def extract(url, cookiefile=None, user_agent=None):
    """Returns the flat success payload consumed by InstagramExtractor.

    Payload: ``{"shortcode", "caption", "owner", "items": [
        {"index", "media_type", "url", "thumbnail_url", "duration"}
    ]}``
    """
    # Reject profile/explore/feed URLs before gallery-dl starts paginating them.
    if not _POST_URL_RE.search(url or ""):
        return fail(UNAVAILABLE, _UNSUPPORTED_URL)

    try:
        post, files = gallery_dl_util.extract_messages(
            url,
            cookiefile=cookiefile,
            user_agent=user_agent,
            extractor_section="instagram",
            extractor_options=_INSTAGRAM_OPTIONS,
        )
    except gd_exc.NoExtractorError:
        return fail(UNAVAILABLE, _UNSUPPORTED_URL)
    except Exception as error:
        return _failure_envelope(error, cookiefile)

    if not files:
        return fail(UNAVAILABLE, "This post contains no downloadable media")

    items = []
    for fallback_index, (media_url, meta) in enumerate(files, 1):
        items.append({
            "index": meta.get("num") or fallback_index,
            "media_type": "video" if _is_video(media_url, meta) else "image",
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


_VIDEO_EXTENSIONS = (".mp4", ".m4v", ".mov", ".webm")


def _is_video(media_url, meta):
    """Videos are not guaranteed to expose ``video_url`` in gallery-dl metadata.
    Treat an explicit type, video metadata, or a video URL extension as video."""
    if meta.get("video_url"):
        return True
    if str(meta.get("media_type") or "").lower() == "video":
        return True
    path = str(media_url or "").split("?", 1)[0].lower()
    return path.endswith(_VIDEO_EXTENSIONS)


def _failure_envelope(error, cookiefile):
    """Maps gallery-dl exceptions, keeping raw text in debug_log (never the toast)."""
    text = str(error).lower()

    if isinstance(error, gd_exc.NoExtractorError):
        return fail(UNAVAILABLE, _UNSUPPORTED_URL)

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
