"""Twitter/X extraction — gallery-dl with a signed-in jar, Syndication CDN without.

Ported from the reference hybrid downloader (``twitter_x.py``) and its syndication
engine (``downloader/twitter.py``), adapted to this app's JSON-envelope contract
(``common/envelope.py``): the bridge only resolves metadata and direct CDN URLs;
byte downloads stay in Kotlin (``core:download``).

Engine routing — the same condition as the reference:

- a Twitter/X Netscape jar exists -> gallery-dl's extractor API (works for
  protected / follow-gated content);
- no jar -> Twitter's public Syndication CDN (anonymous, no credentials);
- any gallery-dl failure -> transparent fallback to syndication, with the
  gallery-dl error kept in ``debug_log`` only.

Payload shape consumed by ``TwitterExtractor``::

    {"shortcode", "caption", "owner", "engine", "items": [
        {"index", "media_type", "url", "thumbnail_url", "duration", "variants": [
            {"url", "res_label", "quality", "width", "height", "bitrate",
             "content_type"}
        ]}
    ]}

``media_type`` is ``image`` | ``video`` | ``animated_gif``; ``variants`` is
non-empty only for video / animated_gif and is sorted highest-quality-first.

Under gallery-dl, videocard ``thumbnail_url`` comes from that entity's poster
preview (emitted right after the media file when ``previews`` is on); the
syndication engine fills it straight from ``media_url_https``. Without either,
the field is ``None`` and no video URL is ever used as a stand-in thumbnail.
"""

import re
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import requests

from common.envelope import (
    AUTH_WALL,
    NETWORK,
    NO_MEDIA,
    RATE_LIMITED,
    UNAVAILABLE,
    classify,
    fail,
    succeed,
)

try:
    import gallery_dl  # noqa: F401  — import proves Chaquopy packaging at boot
    from gallery_dl import exception as gd_exc

    from common import gallery_dl_util

    _GDL_AVAILABLE = True
    _GDL_NO_EXTRACTOR_ERRORS = (gd_exc.NoExtractorError,)
except Exception:
    _GDL_AVAILABLE = False
    _GDL_NO_EXTRACTOR_ERRORS = ()

# ── Constants & patterns (reference twitter_x.py :133–172) ───────────────────

# Matches x.com/USER/status/ID, twitter.com/USER/status/ID, mobile hosts, and the
# i/web variants.
_TWEET_URL_RE = re.compile(
    r"(?:https?://)?(?:www\.|mobile\.|m\.)?(?:x\.com|twitter\.com)/"
    r"(?:i/web/status|i/status|status|[^/]+/status)/(\d+)",
    re.IGNORECASE,
)

# Syndication CDN endpoint and its required static parameters (reference :144–161).
_SYNDICATION_URL = "https://cdn.syndication.twimg.com/tweet-result"

_HEADERS = {
    "user-agent": (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/98.0.4758.80 Safari/537.36"
    ),
}

_STATIC_PARAMS = {
    "token": "4yklmjmshzi",
    "d6kzi5": "3853i5ab8p5f",
    "1tcm8l": "eyeesq8v7juc",
    "717meh": "1j88mm2ybny8",
    "nidos4": "ob44v58pa78",
    "5wka4z": "9f1835chv5q5",
    "zp6hrz": "zngv8x58cdy",
}

# Resolution is parsed from the CDN path, not a width/height field: the
# syndication payload does not reliably include one (reference :452–462).
_VIDEO_PATH_RES = re.compile(r"/vid/(?:avc1/)?(\d+)x(\d+)/")

_VIDEO_EXTENSIONS = ("mp4", "m4v", "mov", "webm", "mkv")

# Twitter/X sign-in failure vocabulary. Auth-wall markers are platform-domain
# knowledge and stay in this bridge (same split as the Instagram bridges). HTTP
# 401/403 are matched through classify(..., auth_statuses=(401, 403)) so digits
# inside tweet ids or CDN URLs are never mistaken for auth failures.
_AUTH_MARKERS = (
    "authentication",
    "authorization",
    "login",
    "log in",
    "sign in",
    "sign-in",
    "cookies",
    "unauthorized",
)


class _Failure(Exception):
    """Carries a typed extraction failure up to ``extract``."""

    def __init__(self, kind, detail, tail=None):
        super().__init__(detail)
        self.kind = kind
        self.detail = detail
        self.tail = [line for line in (tail or []) if line]


# ── Entry point ─────────────────────────────────────────────────────────────


def extract(url, cookiefile=None):
    """Returns the flat success payload or a typed failure envelope.

    ``cookiefile`` is the decrypted throwaway Netscape jar path handed in by
    ``CookieStore.withCookieFile(Platform.TWITTER)``, or ``None`` when no jar is
    stored. No user-agent is pinned: X sessions are not UA-bound.
    """
    try:
        normalized = _normalize_url(url or "")
        tweet_id = _extract_tweet_id(normalized)
    except ValueError:
        return fail(
            UNAVAILABLE,
            "Unsupported Twitter/X URL — paste a link to a post "
            "(https://x.com/USER/status/1234567890)",
        )
    except Exception as error:
        return _failure_envelope(error, cookiefile)

    # Auto engine routing: cookies -> gallery-dl; any gallery-dl problem falls
    # back to the anonymous syndication engine (reference :369–401, :850–858).
    gdl_error = None
    gdl_auth_failure = False
    if cookiefile:
        try:
            payload = _via_gallery_dl(normalized, cookiefile)
            payload["engine"] = "gallery-dl"
            return succeed(payload)
        except _Failure as failure:
            # A text-only tweet is a definitive answer: the syndication engine
            # would only repeat the same request (and waste a second fetch) to
            # discover the same absence of media.
            if failure.kind == NO_MEDIA:
                return fail(failure.kind, failure.detail, tail=failure.tail)
            gdl_error = str(failure)
            gdl_auth_failure = (
                classify(gdl_error, _AUTH_MARKERS, auth_statuses=(401, 403))
                == AUTH_WALL
            )
        except Exception as error:  # noqa: BLE001 — deliberate fallback boundary
            gdl_error = str(error)
            gdl_auth_failure = (
                classify(gdl_error, _AUTH_MARKERS, auth_statuses=(401, 403))
                == AUTH_WALL
            )

    try:
        payload = _via_syndication(normalized, tweet_id, gdl_error)
        payload["engine"] = "syndication"
        return succeed(payload)
    except _Failure as failure:
        # An invalid/expired jar is the one case where the anonymous retry's
        # "unavailable" must not hide the actionable cause: ask for re-sign-in.
        if gdl_auth_failure and failure.kind != RATE_LIMITED:
            return fail(
                AUTH_WALL,
                "The saved X cookies are no longer valid — sign in with X again",
                tail=[gdl_error] + failure.tail,
            )
        return fail(failure.kind, failure.detail, tail=failure.tail)
    except Exception as error:
        return _failure_envelope(error, cookiefile)


# ── URL handling (reference :341–365) ───────────────────────────────────────


def _normalize_url(url):
    """Normalize a tweet URL: resolve t.co, then twitter.com/mobile -> x.com.

    Short links are detected from the parsed hostname, never from a ``t.co/``
    substring inside a path/query. The redirect request is streamed and closed
    so no response body is ever buffered just to read the final URL.
    """
    candidate = (url or "").strip()
    if not candidate:
        raise ValueError("Empty URL")
    if "://" not in candidate:
        candidate = "https://" + candidate

    parsed = urlsplit(candidate)
    if (parsed.scheme or "").lower() in ("http", "https") and (
        parsed.hostname or ""
    ).lower() in ("t.co", "www.t.co"):
        try:
            with requests.get(
                candidate,
                headers=_HEADERS,
                allow_redirects=True,
                timeout=10,
                stream=True,
            ) as response:
                candidate = response.url
        except requests.RequestException:
            pass

    parsed = urlsplit(candidate)
    scheme = (parsed.scheme or "https").lower()
    raw_host = (parsed.hostname or "").lower()
    canonical_host = raw_host
    for prefix in ("www.", "mobile.", "m."):
        if canonical_host.startswith(prefix):
            canonical_host = canonical_host[len(prefix):]
            break
    # Only strip X/Twitter host prefixes; any other host is left untouched.
    host = canonical_host if canonical_host in ("twitter.com", "x.com") else raw_host
    if host == "twitter.com":
        host = "x.com"

    netloc = host
    if parsed.port is not None:
        netloc = "%s:%d" % (host, parsed.port)
    return urlunsplit((scheme, netloc, parsed.path, parsed.query, parsed.fragment))


def _extract_tweet_id(url):
    match = _TWEET_URL_RE.search(url)
    if not match:
        raise ValueError("Could not extract tweet ID from URL: %s" % url)
    return match.group(1)


# ── gallery-dl engine (metadata-only; mirror of gallery_dl_bridge/extract.py) ──


def _via_gallery_dl(url, cookiefile):
    if not _GDL_AVAILABLE:
        raise RuntimeError("gallery-dl is not installed")

    # Pin the media selection explicitly (gallery-dl 1.32.19 defaults are the
    # same, but pinning keeps a future upgrade from silently changing payloads):
    # video files on, AND poster previews on. Without ``previews`` gallery-dl
    # emits no poster at all for video/gif entities (their per-file metadata
    # never carries ``media_url_https``), which used to leave every videocard
    # without a thumbnail. The app still downloads media-only: ``_via_gallery_dl``
    # pairs each preview with the preceding video card instead of emitting it.
    tweet, files = gallery_dl_util.extract_messages(
        url,
        cookiefile=cookiefile,
        extractor_section="twitter",
        extractor_options={"videos": True, "previews": True},
    )

    if not files:
        raise _Failure(NO_MEDIA, "This tweet contains no downloadable media")

    owner = "unknown"
    author = tweet.get("author") or {}
    if isinstance(author, dict):
        owner = author.get("name") or author.get("nick") or "unknown"
    caption = tweet.get("content") or None
    shortcode = str(tweet.get("tweet_id") or "")

    items = []
    for media_url, meta in files:
        if not media_url:
            continue
        media_type = _gdl_media_type(meta, media_url)

        # gallery-dl (with previews on) emits each video/gif entity as the
        # media file followed by its poster preview (``type == "preview"``).
        # Pair the poster with the most recent video/gif card still missing
        # one; a preview is never its own downloadable card. The break keeps
        # an unpaired preview from leaking a poster across entity groups.
        if meta.get("type") == "preview":
            for existing in reversed(items):
                if existing["media_type"] not in ("video", "animated_gif"):
                    break
                if existing["thumbnail_url"] is None:
                    existing["thumbnail_url"] = media_url
                    break
            continue

        item = {
            "index": len(items) + 1,
            "media_type": media_type,
            "url": media_url,
            "thumbnail_url": media_url if media_type == "image" else None,
            "duration": _duration_seconds(meta.get("duration")),
            "variants": [],
        }
        if media_type != "image":
            width = _int_or_zero(meta.get("width"))
            height = _int_or_zero(meta.get("height"))
            bitrate = _int_or_zero(meta.get("bitrate"))
            item["variants"] = [
                _variant(media_url, width, height, bitrate)
            ]
        items.append(item)

    if not items:
        raise _Failure(NO_MEDIA, "This tweet contains no downloadable media")

    return {
        "shortcode": shortcode,
        "caption": caption,
        "owner": owner,
        "items": items,
    }


def _gdl_media_type(meta, media_url):
    kind = str(meta.get("type") or "").lower()
    if kind in ("animated_gif", "gif"):
        return "animated_gif"
    if kind == "video":
        return "video"
    extension = str(meta.get("extension") or "").lower()
    if not extension:
        extension = (
            str(media_url or "").split("?", 1)[0].rsplit(".", 1)[-1].lower()
        )
    if extension in _VIDEO_EXTENSIONS:
        return "video"
    if extension == "gif":
        return "animated_gif"
    return "image"


# ── Syndication engine (port of reference :405–693) ─────────────────────────


def _via_syndication(url, tweet_id, gdl_error=None):
    fallback_tail = [gdl_error] if gdl_error else []

    params = {"id": tweet_id, "lang": "en"}
    params.update(_STATIC_PARAMS)

    try:
        response = requests.get(
            _SYNDICATION_URL, headers=_HEADERS, params=params, timeout=15
        )
    except requests.RequestException as error:
        raise _Failure(
            NETWORK,
            "Couldn't reach X — check your connection",
            fallback_tail + [str(error)],
        )

    if response.status_code == 404:
        raise _Failure(
            UNAVAILABLE, "This tweet is deleted, private, or unavailable",
            fallback_tail,
        )
    if response.status_code == 429:
        raise _Failure(
            RATE_LIMITED,
            "X is rate-limiting requests — try again in a few minutes",
            fallback_tail,
        )
    if response.status_code != 200:
        kind = NETWORK if response.status_code >= 500 else UNAVAILABLE
        raise _Failure(
            kind,
            "X returned HTTP %s" % response.status_code,
            fallback_tail,
        )

    # Removed/restricted tweets can arrive as HTTP 200 with a tombstone body —
    # a status-code check alone is insufficient (reference :667–668).
    if '"__typename":"TweetTombstone"' in response.text:
        raise _Failure(
            UNAVAILABLE, "This tweet is deleted, private, or unavailable",
            fallback_tail,
        )

    try:
        data = response.json()
    except ValueError:
        raise _Failure(
            UNAVAILABLE, "X returned an unreadable response", fallback_tail
        )

    if not isinstance(data, dict):
        raise _Failure(UNAVAILABLE, "X returned an unexpected response", fallback_tail)

    owner = ((data.get("user") or {}).get("screen_name")) or "unknown"
    caption = _clean_tweet_text(
        data.get("text") or "",
        data.get("entities") or {},
        data.get("parent"),
    )

    media_details = data.get("mediaDetails")
    if isinstance(media_details, list) and media_details:
        items = _parse_media_details(media_details)
    else:
        items = _parse_fallback_media(data)

    if not items:
        raise _Failure(
            NO_MEDIA,
            "This tweet contains no downloadable media",
            fallback_tail,
        )

    return {
        "shortcode": tweet_id,
        "caption": caption or None,
        "owner": owner,
        "items": items,
    }


def _clean_tweet_text(text, entities, parent=None):
    """Expand shortened URLs, drop reply mentions and media t.co links."""
    parent = parent or {}

    for url_data in entities.get("urls") or []:
        expanded = url_data.get("expanded_url") or ""
        short = url_data.get("url") or ""
        if short and expanded:
            text = text.replace(short, expanded)

    reply_screen_name = parent.get("in_reply_to_screen_name")
    if reply_screen_name:
        text = text.replace("@%s" % reply_screen_name, "")

    parent_user = parent.get("user") or {}
    parent_screen_name = parent_user.get("screen_name")
    if parent_screen_name:
        text = text.replace("@%s" % parent_screen_name, "")

    for media in entities.get("media") or []:
        media_url = media.get("url") or ""
        if media_url:
            text = text.replace(media_url, "")

    return text.strip()


def _parse_media_details(media_details):
    """Parse the ``mediaDetails`` array preserving element order."""
    items = []
    for media in media_details:
        if not isinstance(media, dict):
            continue
        media_type = media.get("type")
        poster = _poster_url(media)

        if media_type == "photo":
            raw_url = media.get("media_url_https") or media.get("url") or ""
            if not raw_url:
                continue
            items.append(
                _item(len(items) + 1, "image", _large_image(raw_url), None, [])
            )
            continue

        variants = _extract_video_variants(
            (media.get("video_info") or {}).get("variants")
            or media.get("variants")
            or []
        )
        if not variants:
            continue

        kind = "animated_gif" if media_type == "animated_gif" else "video"
        items.append(
            _item(len(items) + 1, kind, variants[0]["url"], poster, variants)
        )

    return items


def _parse_fallback_media(data):
    """Fallback parser when ``mediaDetails`` is absent (reference :587–646)."""
    items = []

    for photo in data.get("photos") or []:
        raw_url = (photo or {}).get("url") or ""
        if not raw_url:
            continue
        items.append(_item(len(items) + 1, "image", _large_image(raw_url), None, []))

    video = data.get("video") or {}
    if isinstance(video, dict) and video:
        poster = _poster_url(video)
        variants = _extract_video_variants(video.get("variants") or [])
        content_type = video.get("contentType") or ""

        if content_type == "gif":
            if variants:
                items.append(
                    _item(
                        len(items) + 1,
                        "animated_gif",
                        variants[0]["url"],
                        poster,
                        variants,
                    )
                )
            else:
                src = ""
                for variant in video.get("variants") or []:
                    if not isinstance(variant, dict):
                        continue
                    candidate = variant.get("src") or variant.get("url") or ""
                    variant_ct = variant.get("content_type") or variant.get("type") or ""
                    if candidate and not _is_hls_variant(variant_ct, candidate):
                        src = candidate
                        break
                if src:
                    items.append(
                        _item(
                            len(items) + 1,
                            "animated_gif",
                            src,
                            poster,
                            [_variant(src)],
                        )
                    )
        elif variants:
            items.append(
                _item(len(items) + 1, "video", variants[0]["url"], poster, variants)
            )

    return items


def _extract_video_variants(variants):
    """Filter HLS, dedupe, label, and sort mp4 variants best-first.

    The app's download path is a direct HTTP GET, so ``application/x-mpegURL``
    (HLS) variants are unusable and dropped. Sorting descending by pixel count
    then bitrate is what makes ``videoFormats.first()`` the best quality the UI
    downloads (spec Phase 6; the UI has no format picker for Twitter).
    """
    parsed = []
    seen_urls = set()

    for variant in variants or []:
        if not isinstance(variant, dict):
            continue
        content_type = variant.get("content_type") or variant.get("type") or ""
        variant_url = variant.get("url") or variant.get("src") or ""
        if _is_hls_variant(content_type, variant_url):
            continue

        if not variant_url or variant_url in seen_urls:
            continue
        seen_urls.add(variant_url)

        bitrate = _int_or_zero(variant.get("bitrate"))
        parsed.append(_variant(variant_url, bitrate=bitrate))

    parsed.sort(key=lambda entry: (entry["width"] * entry["height"], entry["bitrate"]), reverse=True)
    return parsed


def _is_hls_variant(content_type, url):
    """True for HLS playlists, whose manifests the byte downloader cannot fetch.

    Content-type comparison is case-insensitive, and the URL check ignores any
    query string, so ``.../master.M3U8?token=...`` is still recognized.
    """
    if "mpegurl" in str(content_type or "").lower():
        return True
    return str(url or "").split("?", 1)[0].lower().endswith(".m3u8")


def _variant(url, width=0, height=0, bitrate=0):
    match = _VIDEO_PATH_RES.search(url)
    if match:
        width = int(match.group(1))
        height = int(match.group(2))
        quality = "%dx%d" % (width, height)
        res_label = "%dp" % min(width, height)
    else:
        width, height = int(width or 0), int(height or 0)
        quality = "%dkbps" % (bitrate // 1000) if bitrate else "mp4"
        res_label = ("%dp" % min(width, height)) if width and height else quality
    return {
        "url": url,
        "res_label": res_label,
        "quality": quality,
        "width": width,
        "height": height,
        "bitrate": int(bitrate or 0),
        "content_type": "video/mp4",
    }


def _item(index, media_type, url, thumbnail_url, variants):
    return {
        "index": index,
        "media_type": media_type,
        "url": url,
        "thumbnail_url": thumbnail_url,
        "duration": None,
        "variants": variants,
    }


def _poster_url(media):
    poster = media.get("media_url_https") or media.get("poster") or ""
    return _large_image(poster) if poster else None


def _large_image(url):
    # Full-quality request: Twitter's CDN URLs carry ``?format=jpg&name=small``,
    # so upgrade an existing ``name`` parameter in place instead of appending a
    # second one. Parse the query so ``format``, every other parameter, and the
    # fragment survive; never double-append a query.
    if not url:
        return url
    parsed = urlsplit(url)
    params = [
        (key, value)
        for key, value in parse_qsl(parsed.query, keep_blank_values=True)
        if key.lower() != "name"
    ]
    params.append(("name", "orig"))
    return urlunsplit(
        (parsed.scheme, parsed.netloc, parsed.path, urlencode(params), parsed.fragment)
    )


def _duration_seconds(value):
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    return seconds if seconds > 0 else None


def _int_or_zero(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


# ── Unexpected-failure mapping ──────────────────────────────────────────────


def _failure_envelope(error, cookiefile):
    """Maps unexpected exceptions, keeping raw text in debug_log only."""
    if isinstance(error, _GDL_NO_EXTRACTOR_ERRORS):
        return fail(
            UNAVAILABLE,
            "Unsupported Twitter/X URL — paste a link to a post "
            "(https://x.com/USER/status/1234567890)",
            tail=[str(error)],
        )

    text = str(error)
    kind = classify(text, _AUTH_MARKERS, auth_statuses=(401, 403))

    if kind == AUTH_WALL:
        detail = (
            "The saved X cookies are no longer valid — sign in with X again"
            if cookiefile
            else "X needs a signed-in session for this content"
        )
        return fail(AUTH_WALL, detail, tail=[text])
    if kind == RATE_LIMITED:
        return fail(
            RATE_LIMITED, "X is rate-limiting requests — try again in a few minutes", tail=[text]
        )
    if kind == NETWORK:
        return fail(
            NETWORK, "Couldn't reach X — check your connection", tail=[text]
        )
    return fail(UNAVAILABLE, "Couldn't load this X post", tail=[text])
