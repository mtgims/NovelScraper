"""NovelScraper core: framework-free scraping logic.

The future API/job layer is expected to import :func:`scrape_book`, the
:class:`ScraperConfig`/:class:`SiteProfile` config types, and the data models.
"""

from .config import ScraperConfig
from .errors import (
    ContentNotFoundError,
    FetchError,
    ProfileError,
    RetryableFetchError,
    ScraperError,
)
from .models import Book, Chapter, ScrapeResult, VolumeResult
from .orchestrator import scrape_book
from .site_profile import (
    SiteProfile,
    UnsupportedSourceError,
    load_profiles,
    resolve_book_url,
)

__all__ = [
    "ScraperConfig",
    "SiteProfile",
    "load_profiles",
    "resolve_book_url",
    "UnsupportedSourceError",
    "scrape_book",
    "Book",
    "Chapter",
    "ScrapeResult",
    "VolumeResult",
    "ScraperError",
    "FetchError",
    "RetryableFetchError",
    "ContentNotFoundError",
    "ProfileError",
]
