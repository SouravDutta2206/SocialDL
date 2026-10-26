import json
import os
import shutil
import subprocess
import yt_dlp


class _DebugLogCollector:
    """
    Collects yt-dlp's own progress/warning/error messages so a failed extraction's
    error envelope can carry the client-skip diagnostics (e.g. 'web_creator requires
    a PO token', 'requiring account age-verification') instead of just the final
    playability reason. Always attached; only ever emitted on error envelopes.
    """

    def __init__(self):
        self.lines = []

    def debug(self, msg):
        self.lines.append(str(msg))

    def info(self, msg):
        self.lines.append(str(msg))

    def warning(self, msg):
        self.lines.append("[warn] " + str(msg))

    def error(self, msg):
        self.lines.append("[error] " + str(msg))

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

def _resolve_node_runtime(node_dir, native_bin_dir):
    """Finds the bundled Node binary for yt-dlp's js_runtimes option, or None.

    Preference order: NativeRuntime's bin/node symlink (already exec-proven by the
    boot smoke test) → nativeLibraryDir/libnode.so → `node` on PATH (desktop/dev).

    Both directories are passed in by the Kotlin side ([NativeRuntime.Paths.binDir]
    and nativeLibraryDir) — the path contract has ONE owner, so Python no longer
    re-derives filesDir by walking parent directories.
    """
    if native_bin_dir:
        candidate = os.path.join(native_bin_dir, "node")
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    if node_dir:
        candidate = os.path.join(node_dir, "libnode.so")
        if os.path.isfile(candidate):
            return candidate
    return shutil.which("node")


def _ensure_native_env(ld_library_path):
    """Spawns of node/ffmpeg must see the support libraries extracted from
    libnode.zip.so (e.g. libz.so.1).

    Kotlin's [NativeRuntime.Paths.ldLibraryPath] is the single source of truth —
    it already contains usr/lib + nativeLibraryDir + /system/lib64. Previously
    Python re-derived the same value with subtly different merge semantics.
    """
    if not ld_library_path:
        return
    existing = os.environ.get("LD_LIBRARY_PATH")
    os.environ["LD_LIBRARY_PATH"] = ld_library_path + (os.pathsep + existing if existing else "")
    os.environ["OPENSSL_CONF"] = "/dev/null"


def _is_playlist_url(url: str) -> bool:
    return "list=" in url or "/playlist" in url


# Log lines (from yt-dlp via the logger collector) that mean the JS runtime chain
# is degraded: challenge solving cannot run, so GVS formats get dropped — often
# silently, because jsless clients still serve most public videos.
_JS_DEGRADATION_MARKERS = (
    "challenge solving failed",
    "No supported JavaScript runtime could be found",
    "Ignoring unsupported JavaScript runtime",
    "Remote component challenge solver script",
)

_JS_RUNTIME_PROBE_CACHE = {}


def _probe_js_runtime(node_dir, native_bin_dir):
    """Resolves + exec-verifies the bundled node runtime. Memoized per resolved
    directory pair (a §2.5.2 hot-swap changes the path, which re-probes).

    Returns a dict: runtime (name or None), path, version, exec_ok, min_supported
    (yt-dlp's own minimum for node), ejs_package (solver distribution present).
    """
    cache_key = (node_dir or "") + "|" + (native_bin_dir or "")
    cached = _JS_RUNTIME_PROBE_CACHE.get(cache_key)
    if cached is not None:
        return cached

    try:
        from yt_dlp.utils._jsruntime import NodeJsRuntime
        min_supported = ".".join(map(str, NodeJsRuntime.MIN_SUPPORTED_VERSION))
    except Exception:
        min_supported = None

    diag = {
        "runtime": None,
        "path": None,
        "version": None,
        "exec_ok": None,
        "min_supported": min_supported,
        "ejs_package": False,
    }
    try:
        import yt_dlp_ejs  # noqa: F401 — solver script distribution probe
        diag["ejs_package"] = True
    except Exception:
        pass

    node_bin = _resolve_node_runtime(node_dir, native_bin_dir)
    if node_bin:
        diag["runtime"] = "node"
        diag["path"] = node_bin
        try:
            proc = subprocess.run(
                [node_bin, "--version"], capture_output=True, text=True, timeout=10)
            version = (proc.stdout or "").strip()
            diag["exec_ok"] = proc.returncode == 0 and version.startswith("v")
            diag["version"] = version or None
        except Exception:
            diag["exec_ok"] = False

    _JS_RUNTIME_PROBE_CACHE[cache_key] = diag
    return diag


def _js_runtime_degraded(diag, debug_logger):
    """True when the runtime chain can't run the challenge solver: no runtime at
    all, a runtime that fails exec/versions, or degradation warnings in yt-dlp's
    own log lines."""
    warnings = [
        line for line in debug_logger.lines
        if any(marker in line for marker in _JS_DEGRADATION_MARKERS)
    ]
    degraded = (
        diag["runtime"] is None
        or diag["exec_ok"] is False
        or diag.get("ejs_package") is not True
        or bool(warnings)
    )
    return degraded, warnings


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

def extract(url: str, cookiefile: str | None = None, node_dir: str | None = None,
            native_bin_dir: str | None = None, native_ld_library_path: str | None = None) -> str:
    """
    Extracts metadata and direct stream URLs for YouTube videos/playlists via yt-dlp.
    Metadata only (skip_download=True) - byte downloads and muxing happen in Kotlin/FFmpeg.

    `native_bin_dir` / `native_ld_library_path` come from the Kotlin side's
    [NativeRuntime.Paths] so the native runtime contract has a single owner.
    """
    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,       # metadata only — never triggers a download
        "noplaylist": not _is_playlist_url(url),  # allow playlist extraction when list is present
        "extract_flat": "in_playlist" if _is_playlist_url(url) else False,  # fast flat metadata for playlists, deep for single videos
        "cookiefile": cookiefile,    # decrypted temp path if available
    }

    node_bin = _resolve_node_runtime(node_dir, native_bin_dir)
    if node_bin:
        # yt-dlp 2026.10+ enables ONLY deno by default (utils/_jsruntime.py); node
        # must be registered explicitly via the `js_runtimes` option, else the
        # n/sig challenges fail and every GVS format is dropped. The path accepts
        # a direct file path, so Android's nativeLibraryDir/libnode.so works as-is.
        ydl_opts["js_runtimes"] = {"node": {"path": node_bin}}
        _ensure_native_env(native_ld_library_path)

    js_diag = _probe_js_runtime(node_dir, native_bin_dir)
    debug_logger = _DebugLogCollector()
    ydl_opts["logger"] = debug_logger

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if not info:
                return json.dumps({"error": "no_info", "detail": "No metadata returned by yt-dlp"})
            return json.dumps(_maybe_attach_js_diagnostics(_normalize(info), js_diag, debug_logger))

    except yt_dlp.utils.DownloadError as e:
        message = str(e).lower()
        if any(marker in message for marker in _AUTH_WALL_MARKERS):
            return json.dumps({"error": "auth_wall", "detail": str(e), "debug_log": _tail(debug_logger),
                               **_js_diagnostics(js_diag, debug_logger)})
        if any(marker in message for marker in _RATE_LIMIT_MARKERS):
            return json.dumps({"error": "rate_limited", "detail": str(e), "debug_log": _tail(debug_logger),
                               **_js_diagnostics(js_diag, debug_logger)})
        if any(marker in message for marker in _NETWORK_MARKERS):
            return json.dumps({"error": "network", "detail": str(e), "debug_log": _tail(debug_logger),
                               **_js_diagnostics(js_diag, debug_logger)})
        return json.dumps({"error": "unavailable", "detail": str(e), "debug_log": _tail(debug_logger),
                           **_js_diagnostics(js_diag, debug_logger)})
    except Exception as e:
        return json.dumps({"error": "exception", "detail": str(e), "debug_log": _tail(debug_logger),
                           **_js_diagnostics(js_diag, debug_logger)})


def _js_diagnostics(js_diag, debug_logger):
    """{js_diagnostics: {...}} payload only when the runtime chain is degraded;
    an empty dict (no-op spread) on healthy runs."""
    degraded, warnings = _js_runtime_degraded(js_diag, debug_logger)
    if not degraded:
        return {}
    return {"js_diagnostics": {**js_diag, "warnings": warnings[-10:]}}


def _maybe_attach_js_diagnostics(payload, js_diag, debug_logger):
    degraded, warnings = _js_runtime_degraded(js_diag, debug_logger)
    if degraded:
        payload["js_diagnostics"] = {**js_diag, "warnings": warnings[-10:]}
    return payload


def _tail(logger: _DebugLogCollector, n: int = 40) -> str:
    """Last n collected yt-dlp messages, newline-joined; diagnostics only."""
    return "\n".join(logger.lines[-n:])

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
