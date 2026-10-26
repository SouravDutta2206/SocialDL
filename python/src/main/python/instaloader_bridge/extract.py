"""Anonymous Instaloader fallback for Instagram (used when no cookies exist).

gallery-dl requires a signed-in jar for most Instagram posts. Instaloader's
shortcode endpoint still resolves a subset of public posts anonymously, so the
Kotlin extractor routes here only when `CookieStore` has no Instagram jar.

This bridge has no login/session handling by design: failures are typed and
surfaced normally (`auth_wall`, `rate_limited`, `unavailable`, …). The payload
shape is identical to gallery_dl_bridge so the Kotlin mapper is shared.
"""

import re

import instaloader

from common.envelope import (
    AUTH_WALL,
    NETWORK,
    RATE_LIMITED,
    UNAVAILABLE,
    classify,
    fail,
    succeed,
)

_AUTH_MARKERS = (
    "login required",
    "login_required",
    "private profile",
    "privateprofile",
    "requires login",
    "not logged in",
    "checkpoint",
    "401",
    "unauthorized",
    "please wait a few minutes",
)

_SHORTCODE_RE = re.compile(r"/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)")


def extract(url):
    try:
        loader = instaloader.Instaloader(quiet=True)
        shortcode = _shortcode(url)
        if not shortcode:
            return fail(
                UNAVAILABLE,
                "Unsupported Instagram URL — only posts, reels, and IGTV links are supported",
            )

        try:
            post = instaloader.Post.from_shortcode(loader.context, shortcode)
        except instaloader.PrivateProfileNotFollowedException as e:
            return fail(
                AUTH_WALL,
                "This account is private — sign in with Instagram to check access",
                tail=[str(e)],
            )
        except instaloader.LoginRequiredException as e:
            return fail(
                AUTH_WALL,
                "Instagram needs a signed-in session for this content",
                tail=[str(e)],
            )
        except instaloader.ProfileNotExistsException as e:
            return fail(UNAVAILABLE, "This Instagram post or account no longer exists", tail=[str(e)])

        payload = _normalize(post)
        if not payload["items"]:
            return fail(UNAVAILABLE, "This post contains no downloadable media")
        return succeed(payload)

    except Exception as error:
        return _failure_envelope(error)


def _failure_envelope(error):
    """Maps anonymous-fetch failures, keeping raw text in debug_log only."""
    text = str(error).lower()

    if "fetching post metadata failed" in text:
        return fail(
            UNAVAILABLE,
            "This Instagram post is private, deleted, or unavailable",
            tail=[str(error)],
        )

    if "401" in text or "unauthorized" in text or "please wait a few minutes" in text:
        return fail(
            AUTH_WALL,
            "Instagram needs a signed-in session for this content",
            tail=[str(error)],
        )

    if "please wait" in text or "rate limit" in text or "429" in text:
        return fail(
            RATE_LIMITED,
            "Instagram is rate-limiting requests — try again in a few minutes",
            tail=[str(error)],
        )

    kind = classify(error, _AUTH_MARKERS)
    if kind == AUTH_WALL:
        return fail(
            AUTH_WALL,
            "Instagram needs a signed-in session for this content",
            tail=[str(error)],
        )
    if kind == NETWORK:
        return fail(NETWORK, "Couldn't reach Instagram — check your connection", tail=[str(error)])

    return fail(UNAVAILABLE, "Couldn't load this Instagram post", tail=[str(error)])


def _shortcode(url):
    match = _SHORTCODE_RE.search(url or "")
    return match.group(1) if match else None


def _normalize(post):
    items = []

    if post.typename == "GraphSidecar":
        for index, node in enumerate(post.get_sidecar_nodes(), 1):
            if node.is_video:
                items.append(_item(
                    index=index,
                    media_type="video",
                    url=node.video_url,
                    thumbnail_url=node.display_url,
                    duration=getattr(post, "video_duration", None),
                ))
            else:
                items.append(_item(
                    index=index,
                    media_type="image",
                    url=node.display_url,
                    thumbnail_url=node.display_url,
                    duration=None,
                ))
    elif post.typename == "GraphVideo":
        items.append(_item(
            index=1,
            media_type="video",
            url=post.video_url,
            thumbnail_url=post.url,
            duration=getattr(post, "video_duration", None),
        ))
    else:
        items.append(_item(
            index=1,
            media_type="image",
            url=post.url,
            thumbnail_url=post.url,
            duration=None,
        ))

    items = [item for item in items if item["url"]]

    return {
        "shortcode": post.shortcode,
        "caption": post.caption,
        "owner": post.owner_username,
        "items": items,
    }


def _item(index, media_type, url, thumbnail_url, duration):
    return {
        "index": index,
        "media_type": media_type,
        "url": url,
        "thumbnail_url": thumbnail_url,
        "duration": duration,
    }
