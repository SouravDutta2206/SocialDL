"""
Shared base contract for Python-side extractors per §2.3.1.
Mirrors Kotlin MediaExtractor one layer down.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum


class MediaType(Enum):
    IMAGE = "image"
    VIDEO = "video"
    GIF = "gif"


@dataclass
class MediaItem:
    """One media element within a post (photo/video/gif)."""
    index: int
    media_type: MediaType
    url: str
    thumbnail_url: str | None = None
    description: str | None = None


@dataclass
class MediaInfo:
    """The whole post/tweet — maps onto Kotlin's MediaItem."""
    platform: str
    shortcode: str
    caption: str | None
    owner: str | None
    items: list[MediaItem] = field(default_factory=list)


class BaseDownloader(ABC):
    @staticmethod
    @abstractmethod
    def can_handle(url: str) -> bool:
        """Returns True if this downloader can handle the given URL."""
        ...

    @abstractmethod
    def fetch(self, url: str) -> MediaInfo:
        """Resolves metadata and stream URLs for the given post URL."""
        ...
