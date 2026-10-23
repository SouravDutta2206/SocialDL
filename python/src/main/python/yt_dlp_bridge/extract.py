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

def extract(url: str, cookiefile: str | None = None, node_dir: str | None = None) -> str:
    """
    Extracts metadata and direct stream URLs for YouTube videos via yt-dlp.
    Metadata only (skip_download=True) - byte downloads and muxing happen in Kotlin/FFmpeg.
    """
    # node_dir is the directory containing whichever Node binary NodeBinaryProvider resolved.
    # Prepended to PATH so yt-dlp-ejs and yt-dlp's JS engine find it automatically.
    if node_dir:
        os.environ["PATH"] = node_dir + os.pathsep + os.environ.get("PATH", "")

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,       # metadata only — never triggers a download
        "noplaylist": True,          # single video by default
        "extract_flat": False,       # fully resolve formats, not a flat playlist stub
        "cookiefile": cookiefile,    # decrypted temp path if available
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as e:
        message = str(e).lower()
        if any(marker in message for marker in _AUTH_WALL_MARKERS):
            return json.dumps({"error": "auth_wall", "detail": str(e)})
        return json.dumps({"error": "unavailable", "detail": str(e)})
    except Exception as e:
        return json.dumps({"error": "exception", "detail": str(e)})

    if not info:
        return json.dumps({"error": "no_info", "detail": "No metadata returned by yt-dlp"})

    return json.dumps(_normalize(info))

def _normalize(info: dict) -> dict:
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

    return {
        "title": info.get("title") or "YouTube Video",
        "uploader": info.get("uploader") or info.get("channel") or "Unknown Channel",
        "duration": info.get("duration") or 0,        # seconds, int
        "thumbnail_url": best_thumb,
        "formats": formats,
    }
