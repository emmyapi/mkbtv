"""Resolve a public IMDb title's primary image when a visible card needs it."""

from collections import OrderedDict
import json
import re
from threading import Lock
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

TITLE_ID = re.compile(r"tt[0-9]{7,12}")
ENDPOINT = "https://v3.sg.media-imdb.com/suggestion/x/"
MAX_REPLY = 128 * 1024


def primary_image(document, title_id):
    rows = document["d"] if "d" in document else []
    if not isinstance(rows, list):
        raise ValueError("Invalid IMDb reply")
    for row in rows:
        if row.get("id") != title_id:
            continue
        image = row.get("i") or {}
        url = image.get("imageUrl", "")
        if not isinstance(url, str) or any(ch.isspace() for ch in url):
            return None
        parts = urlsplit(url)
        if (
            parts.scheme != "https"
            or parts.netloc != "m.media-amazon.com"
            or parts.query
            or parts.fragment
            or not re.fullmatch(r"/images/[A-Za-z0-9/@%+_.=-]+\.(?:jpg|jpeg|png|webp)", parts.path)
        ):
            return None
        # Use the closest useful size for a TV card. Keep small originals at their native size.
        if url.endswith("._V1_.jpg") and isinstance(image.get("width"), int) and image["width"] > 600:
            return url.removesuffix("._V1_.jpg") + "._V1_QL85_UX600_.jpg"
        return url
    return None


def lookup(title_id):
    request = Request(ENDPOINT + title_id + ".json", headers={"User-Agent": "mkbtv/1.0"})
    with urlopen(request, timeout=5) as response:
        if urlsplit(response.url).hostname != "v3.sg.media-imdb.com":
            raise ValueError("Unexpected artwork source")
        body = response.read(MAX_REPLY + 1)
    if len(body) > MAX_REPLY:
        raise ValueError("Oversize artwork response")
    return primary_image(json.loads(body), title_id)


class PosterArt:
    def __init__(self, fetch=lookup):
        self.fetch = fetch
        self.cache = OrderedDict()
        self.guard = Lock()
        self.stripes = [Lock() for _ in range(6)]
        self.cooldown = 0

    def resolve(self, title_id):
        if not TITLE_ID.fullmatch(title_id):
            return 404, None
        # The same card in different profiles shares a lookup. Only URLs and misses use memory.
        slot = self.stripes[int(title_id[2:]) % len(self.stripes)]
        if not slot.acquire(timeout=8):
            return 503, None
        try:
            now = time.monotonic()
            with self.guard:
                known = self.cache.get(title_id)
                if known and known[0] > now:
                    self.cache.move_to_end(title_id)
                    return known[1:]
                if self.cooldown > now:
                    return 503, None
            try:
                url = self.fetch(title_id)
                status, ttl = (302, 7 * 86400) if url else (404, 86400)
            except HTTPError as exc:
                url = None
                status, ttl = (404, 86400) if exc.code == 404 else (503, 20)
                if exc.code == 429:
                    retry = exc.headers.get("Retry-After", "")
                    with self.guard:
                        self.cooldown = now + (int(retry) if re.fullmatch(r"[0-9]{1,8}", retry) else 60)
                exc.close()
            except (OSError, URLError, ValueError, AttributeError, TypeError):
                status, url, ttl = 503, None, 20
            with self.guard:
                self.cache[title_id] = (time.monotonic() + ttl, status, url)
                self.cache.move_to_end(title_id)
                if len(self.cache) > 4096:
                    self.cache.popitem(last=False)
            return status, url
        finally:
            slot.release()
