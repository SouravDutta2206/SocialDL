import json
import os
import yt_dlp

# Substrings of yt-dlp's DownloadError message that specifically mean
# "this needs a logged-in session", not just "something went wrong" —
# matched here so a structured envelope crosses the Chaquopy boundary
# instead of a raw traceback. See §2.6 & §4.1.
_AUTH_WALL_MARKERS = (
    "sign in to confirm your age",
    "members-only content",
    "sign in to confirm you're not a bot",
    "login required",
    "confirm your age",
    "the page needs to be reloaded",
)

# Substrings of yt-dlp's DownloadError message that mean the platform is
# throttling us (HTTP 429). UI can show a "wait and retry" state.
_RATE_LIMIT_MARKERS = (
    "http error 429",
    "too many requests",
)

# Substrings of yt-dlp's DownloadError message that mean a transport-level
# network failure (DNS, connection refused/reset, timeouts). UI can offer a retry.
_NETWORK_MARKERS = (
    "getaddrinfo",
    "connection",
    "timed out",
    "unable to download webpage",
    "network",
)

def warmup():
    """
    Pre-warms yt-dlp and the YouTube extractor into memory.
    Called during app startup on Dispatchers.IO to eliminate first-search latency.
    """
    try:
        ydl = yt_dlp.YoutubeDL({"quiet": True, "skip_download": True, "no_warnings": True})
        ydl.get_info_extractor("Youtube")
    except Exception:
        pass

def extract(url: str, cookiefile: str | None = None, node_dir: str | None = None) -> str:
    """
    Extracts metadata and direct stream URLs for YouTube videos/playlists via yt-dlp.
    Metadata only (skip_download=True) - byte downloads and muxing happen in Kotlin/FFmpeg.
    """
    # node_dir is the directory containing whichever Node binary NodeBinaryProvider resolved.
    # Prepended to PATH so yt-dlp-ejs and yt-dlp's JS engine find it automatically.
    if node_dir:
        os.environ["PATH"] = node_dir + os.pathsep + os.environ.get("PATH", "")

    # Enable playlist extraction when 'list=' is present or URL is a playlist endpoint
    is_playlist_url = "list=" in url or "/playlist" in url

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,       # metadata only — never triggers a download
        "noplaylist": not is_playlist_url,  # allow playlist extraction when list is present
        "extract_flat": "in_playlist" if is_playlist_url else False,  # fast flat metadata for playlists, deep for single videos
        "cookiefile": cookiefile,    # decrypted temp path if available
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if not info:
                return json.dumps({"error": "no_info", "detail": "No metadata returned by yt-dlp"})
            return json.dumps(_normalize(info))

    except yt_dlp.utils.DownloadError as e:
        message = str(e).lower()
        if any(marker in message for marker in _AUTH_WALL_MARKERS):
            return json.dumps({"error": "auth_wall", "detail": str(e)})
        if any(marker in message for marker in _RATE_LIMIT_MARKERS):
            return json.dumps({"error": "rate_limited", "detail": str(e)})
        if any(marker in message for marker in _NETWORK_MARKERS):
            return json.dumps({"error": "network", "detail": str(e)})
        return json.dumps({"error": "unavailable", "detail": str(e)})
    except Exception as e:
        return json.dumps({"error": "exception", "detail": str(e)})

def _normalize_single_entry(info: dict) -> dict:
    thumbnails = sorted(
        info.get("thumbnails", []) or [],
        key=lambda t: (t.get("height") or 0),
        reverse=True,
    )
    best_thumb = thumbnails[0]["url"] if thumbnails else info.get("thumbnail")

    formats = []
    for f in info.get("formats", []) or []:
        # Drop non-media entries (storyboards, mhtml image tracks, etc.)
        if f.get("vcodec") == "none" and f.get("acodec") == "none":
            continue

        # Hard filter: DRM-protected formats are not downloadable
        if f.get("has_drm"):
            continue

        # Hard filter: only plain resumable HTTP(S) formats are supported.
        # Exclude streaming manifest protocols (DASH/HLS fragment segments)
        if f.get("protocol") not in ("https", "http"):
            continue

        formats.append({
            "format_id": str(f.get("format_id") or ""),
            "ext": f.get("ext"),
            "vcodec": f.get("vcodec"),
            "acodec": f.get("acodec"),
            "width": f.get("width"),
            "height": f.get("height"),
            "fps": f.get("fps"),
            "abr": f.get("abr"),               # audio bitrate, kbps
            "asr": f.get("asr"),               # audio sample rate, Hz
            "audio_channels": f.get("audio_channels"),
            "tbr": f.get("tbr"),                # total bitrate, kbps — size-estimate fallback
            "dynamic_range": f.get("dynamic_range"),  # "SDR" / "HDR10" / etc.
            "filesize": f.get("filesize"),
            "filesize_approx": f.get("filesize_approx"),
            "url": f.get("url"),               # direct googlevideo.com CDN URL
            "downloader_options": f.get("downloader_options"),  # e.g. http_chunk_size
        })

    vid_id = info.get("id")
    # Clean URL: strip anything starting with &list or ?list to ensure clean standalone URL
    if vid_id:
        clean_url = f"https://www.youtube.com/watch?v={vid_id}"
    else:
        raw_url = info.get("webpage_url") or info.get("url") or ""
        clean_url = raw_url.split("&list=")[0].split("?list=")[0]

    return {
        "id": vid_id,
        "webpage_url": clean_url,
        "title": info.get("title") or "YouTube Video",
        "uploader": info.get("uploader") or info.get("channel") or "Unknown Channel",
        "duration": info.get("duration") or 0,        # seconds, int
        "thumbnail_url": best_thumb,
        "formats": formats,
    }

def _normalize(info: dict) -> dict:
    if info.get("_type") == "playlist" or "entries" in info:
        entries = info.get("entries") or []
        normalized_entries = []
        for entry in entries:
            if not entry:
                continue
            normalized_entries.append(_normalize_single_entry(entry))

        return {
            "is_playlist": True,
            "title": info.get("title") or "YouTube Playlist",
            "uploader": info.get("uploader") or info.get("channel") or "Unknown Channel",
            "entries": normalized_entries,
        }

    return _normalize_single_entry(info)
