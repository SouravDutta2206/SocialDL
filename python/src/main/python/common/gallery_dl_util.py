"""Serialized gallery-dl extractor access shared by the Instagram/Twitter bridges.

gallery-dl keeps its configuration in a single process-global ``config`` object
(and ``config.clear()`` resets every value). Both bridges run on Chaquopy's
shared Python interpreter, so two concurrent extractions — an Instagram post and
a Twitter post, for example — could previously interleave their
``clear``/``set`` calls and run one extractor with the other platform's cookie
jar or user-agent. The module-level lock below makes the whole
configure-and-iterate sequence atomic, so no extraction can ever observe another
one's credentials.

The bridges hand in only the knobs they own; this helper owns the shared
transport configuration (timeout/retries) and the lock discipline.
"""

import threading

import gallery_dl  # noqa: F401 — import proves Chaquopy packaging at boot
from gallery_dl import config, exception as gd_exc
from gallery_dl.extractor import find
from gallery_dl.extractor.message import Message

# Process-global: gallery-dl's config is process-global, so the lock must be too.
_LOCK = threading.Lock()


def extract_messages(
    url,
    cookiefile=None,
    user_agent=None,
    extractor_section=None,
    extractor_options=None,
    timeout=20,
    retries=2,
):
    """Runs one gallery-dl extractor under the shared config lock.

    Returns ``(post_metadata, [(media_url, file_metadata), ...])`` where
    ``post_metadata`` is the merged ``Message.Directory`` metadata (gallery-dl
    emits it before the files) and every entry in the list is a
    ``Message.Url`` pair.

    ``extractor_section``/``extractor_options`` are the per-site settings the
    calling bridge needs (e.g. Instagram ``videos=merged``, Twitter
    ``previews=True``); they are applied under ``("extractor", section)``.
    ``timeout``/``retries`` bound each HTTP request inside gallery-dl so one
    stalled request can't pin the dispatcher thread indefinitely.

    Raises ``gallery_dl.exception.NoExtractorError`` when no extractor matches
    the URL, leaving mapping to the caller's failure envelope.
    """
    with _LOCK:
        # Deterministic behavior: ignore gallery-dl's user config files.
        config.clear()
        if cookiefile:
            config.set(("extractor",), "cookies", cookiefile)
        if user_agent:
            config.set(("extractor",), "user-agent", user_agent)
        config.set(("extractor",), "timeout", timeout)
        config.set(("extractor",), "retries", retries)

        if extractor_section and extractor_options:
            for key, value in extractor_options.items():
                config.set(("extractor", extractor_section), key, value)

        extractor = find(url)
        if extractor is None:
            raise gd_exc.NoExtractorError(url)

        post = {}
        files = []
        for message in extractor:
            kind = message[0]
            if kind == Message.Directory:
                post.update(message[2] or {})
            elif kind == Message.Url:
                files.append((message[1], message[2] or {}))
        return post, files
