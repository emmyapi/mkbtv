"""Cached worldwide theatrical and home release dates for movie search."""

from collections import deque
from contextlib import closing
from datetime import datetime, timezone
from html.parser import HTMLParser
from http.client import HTTPException
from itertools import islice
import json
import os
from pathlib import Path
import re
import sqlite3
from threading import Condition, Thread
import time

import discovery as d
from seasonal_provider import DAY, ProviderError, PublicProvider, worldwide_release_rows


MOVIE_ID = re.compile(r'tt[0-9]{1,12}|tmdb:[1-9][0-9]{0,11}')
MAX_BATCH = 200
MAX_PENDING = 256
SCHEMA = """
CREATE TABLE IF NOT EXISTS movie_release (
    tmdb_id TEXT PRIMARY KEY, imdb_id TEXT UNIQUE, theatrical_date TEXT, home_date TEXT, age_rating TEXT
);
CREATE INDEX IF NOT EXISTS release_theatrical ON movie_release(theatrical_date, home_date);
CREATE INDEX IF NOT EXISTS release_home ON movie_release(home_date);
CREATE TABLE IF NOT EXISTS movie_release_check (content_id TEXT PRIMARY KEY, next_check INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS movie_release_revision (id INTEGER PRIMARY KEY CHECK(id=1), revision TEXT NOT NULL);
INSERT OR IGNORE INTO movie_release_revision VALUES (1, hex(randomblob(16)));
"""


class CanonicalPage(HTMLParser):
    def __init__(self, body):
        super().__init__()
        self.links = []
        self.feed(body.partition(b'</head>')[0].decode())
        self.close()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'link' and 'canonical' in (attrs.get('rel') or '').split():
            self.links.append(attrs.get('href') or '')


def typed_dates(body, number):
    page = CanonicalPage(body)
    canonical = re.escape(d.TMDB) + '/movie/' + re.escape(number) + r'(?:-[^/?#]+)?/releases/?'
    if len(page.links) != 1 or not re.fullmatch(canonical, page.links[0]):
        raise ProviderError('unverified release page identity')
    rows = list(worldwide_release_rows(body, strict_dates=True))
    if not rows:
        raise ProviderError('no recognized dated release rows')
    theatrical = min((row['date'] for row in rows if row['type'] in ('Theatrical', 'Theatrical (limited)')),
                     default=None)
    home = min((row['date'] for row in rows if row['type'] in ('Digital', 'Physical')), default=None)
    # Prefer the original US theatrical cut when home editions have a different certificate.
    certificates = [row for row in rows if row['country'] == 'US' and row['certification'] and
                    len(row['certification']) <= 16 and row['type'] in ('Theatrical', 'Theatrical (limited)', 'Digital', 'Physical')]
    preferred = min(certificates, key=lambda row: (not row['type'].startswith('Theatrical'), row['date']),
                    default=None)
    return [theatrical, home, preferred['certification'] if preferred else None]


def native_imdb_id(provider, uid):
    """The public catalog can name the same movie in both identifier namespaces."""
    try:
        doc = provider.load(provider.root, d.metadata_url(d.CATALOG, 'movie', uid))
    except (ProviderError, d.DiscoveryError, HTTPException, OSError, ValueError):
        return None
    raw = doc.get('meta') if isinstance(doc, dict) else None
    if not isinstance(raw, dict) or raw.get('type') != 'movie':
        return None
    card = d.safe_meta(raw)
    links = raw.get('links', [])
    if not card or not isinstance(links, list):
        return None
    keys = {key for _, key in d.title_keys(card)}
    for link in links:
        if isinstance(link, dict) and link.get('category') == 'source':
            url = str(link.get('url'))
            source = re.fullmatch(re.escape(d.TMDB) + r'/(movie|tv)/([1-9][0-9]{0,11})/?', url)
            if source:
                if source[1] != 'movie':
                    return None
                keys.add('tmdb:' + source[2])
            imdb_link = re.fullmatch(r'https://www\.imdb\.com/title/(tt[0-9]{1,12})/?', url)
            if imdb_link:
                keys.add(imdb_link[1])
    imdb = {key for key in keys if key.startswith('tt')}
    if len(imdb) == 1 and keys == imdb | {uid}:
        value = imdb.pop()
        return value if MOVIE_ID.fullmatch(value) else None
    return None


def fetch_dates(provider, uid):
    imdb = None
    if uid.startswith('tmdb:'):
        number = uid[5:]
        imdb = native_imdb_id(provider, uid)
    else:
        meta = provider.card('movie', uid)
        # A typed title card must independently identify this IMDb movie and its TMDB movie.
        if not meta or meta.get('type') != 'movie':
            raise ProviderError('unverified movie identity')
        identities = d.title_keys(meta)
        imdb_ids = {value for _, value in identities if value.startswith('tt')}
        native_ids = {value[5:] for _, value in identities if value.startswith('tmdb:')}
        if imdb_ids != {uid} or len(native_ids) != 1:
            raise ProviderError('unverified movie identity')
        imdb, number = uid, native_ids.pop()
        if not re.fullmatch('[1-9][0-9]{0,11}', number):
            raise ProviderError('invalid TMDB movie identity')
    url = f'{d.TMDB}/movie/{number}/releases?language=en-US'
    theatrical, home, age_rating = provider.request(url, lambda body: typed_dates(body, number), DAY // 4,
                                        cache_version='typed-worldwide-v2')
    return number, imdb, theatrical, home, age_rating


def release_card(theatrical, home, today, age_rating=None):
    result = {}
    if age_rating:
        result['ageRating'] = age_rating
    if theatrical:
        result['cinemaDate'] = theatrical
    if home:
        result['homeDate'] = home
    if home and home <= today:
        result['status'] = 'home'
    elif theatrical and theatrical <= today:
        result['status'] = 'cinema'
    elif theatrical or home:
        result['status'] = 'upcoming'
    else:
        result['status'] = 'unknown'
    return result


class MovieReleases:
    def __init__(self, root, *, catalog_dir=None):
        root = Path(root)
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = root / 'movie-releases.sqlite3'
        self.catalog_dir = Path(catalog_dir or os.environ.get('MKBTV_CATALOG_DIR', root.parent / 'catalog'))
        self.provider = PublicProvider(root)
        self.condition = Condition()
        self.queue = deque()
        self.pending = set()
        self.running = False
        self.thread = None
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript(SCHEMA)
            with db:
                db.execute('BEGIN IMMEDIATE')
                if 'age_rating' not in {row[1] for row in db.execute('PRAGMA table_info(movie_release)')}:
                    db.execute('ALTER TABLE movie_release ADD COLUMN age_rating TEXT')
                    db.execute('UPDATE movie_release_check SET next_check=0')

    def start(self):
        with self.condition:
            if self.thread and self.thread.is_alive():
                return
            self.running = True
            self.thread = Thread(target=self._worker, name='movie-releases', daemon=True)
            self.thread.start()

    def stop(self):
        with self.condition:
            self.running = False
            self.pending.difference_update(self.queue)
            self.queue.clear()
            self.condition.notify_all()
            thread = self.thread
        if thread:
            thread.join(timeout=2)

    def lookup(self, ids, *, enqueue=True):
        wanted = list(dict.fromkeys(uid for uid in islice(ids, MAX_BATCH)
                                    if isinstance(uid, str) and MOVIE_ID.fullmatch(uid)))
        now = time.time()
        today = datetime.fromtimestamp(now, timezone.utc).date().isoformat()
        with closing(sqlite3.connect(self.path, timeout=0.15)) as db:
            rows = db.execute("""
                SELECT w.value, r.theatrical_date, r.home_date, r.age_rating, c.next_check
                FROM json_each(?) w
                LEFT JOIN movie_release r ON r.imdb_id=w.value OR
                    (substr(w.value,1,5)='tmdb:' AND r.tmdb_id=substr(w.value,6))
                LEFT JOIN movie_release_check c ON c.content_id=w.value
            """, (json.dumps(wanted),)).fetchall()
        releases = {}
        due = []
        for uid, theatrical, home, age_rating, next_check in rows:
            releases[uid] = release_card(theatrical, home, today, age_rating)
            if not next_check or next_check <= now:
                due.append(uid)
        if not enqueue:
            return {'releases': releases, 'pending': False}
        with self.condition:
            if self.running:
                for uid in due:
                    if uid not in self.pending and len(self.pending) < MAX_PENDING:
                        self.pending.add(uid)
                        self.queue.append(uid)
                if due:
                    self.condition.notify()
            # A poll can admit more visible titles after a full queue drains.
            pending = self.running and bool(due)
        return {'releases': releases, 'pending': pending}

    def _refresh(self, uid):
        now = int(time.time())
        with closing(sqlite3.connect(self.path)) as db:
            check = db.execute('SELECT next_check FROM movie_release_check WHERE content_id=?', (uid,)).fetchone()
            if check and check[0] > now:
                return
            try:
                number, imdb, theatrical, home, age_rating = fetch_dates(self.provider, uid)
                with db:
                    db.execute('BEGIN IMMEDIATE')
                    # Dates are replaceable on verified corrections. A conflicting alias
                    # must never transfer another movie's facts to this IMDb identity.
                    old = db.execute('SELECT imdb_id,theatrical_date,home_date,age_rating FROM movie_release WHERE tmdb_id=?',
                                     (number,)).fetchone()
                    if imdb and old and old[0] and old[0] != imdb:
                        raise ProviderError('conflicting IMDb identity')
                    imdb = imdb or (old[0] if old else None)
                    if old != (imdb, theatrical, home, age_rating):
                        db.execute("""INSERT INTO movie_release(tmdb_id,imdb_id,theatrical_date,home_date,age_rating)
                                      VALUES (?,?,?,?,?) ON CONFLICT(tmdb_id) DO UPDATE SET
                                      imdb_id=excluded.imdb_id, theatrical_date=excluded.theatrical_date,
                                      home_date=excluded.home_date, age_rating=excluded.age_rating""",
                                   (number, imdb, theatrical, home, age_rating))
                        db.execute('UPDATE movie_release_revision SET revision=hex(randomblob(16)) WHERE id=1')
                    today = datetime.fromtimestamp(now, timezone.utc).date().isoformat()
                    ttl = 30 * DAY if home and home <= today else DAY // 4
                    aliases = ['tmdb:' + number] + ([imdb] if imdb else [])
                    db.executemany('INSERT OR REPLACE INTO movie_release_check VALUES (?,?)',
                                   [(key, now + ttl) for key in aliases])
            except (OSError, HTTPException, ValueError, TypeError, KeyError, AttributeError,
                    RecursionError, d.DiscoveryError, ProviderError, sqlite3.IntegrityError):
                # A provider outage, parser change, or ambiguous identity cannot erase positive evidence.
                with db:
                    db.execute('INSERT OR REPLACE INTO movie_release_check VALUES (?,?)', (uid, now + 900))

    def _seeds(self):
        """Warm a few public chart leaders even before the first filtered search."""
        seeds = []
        path = self.catalog_dir / 'feeds.sqlite3'
        if path.is_file():
            with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=1)) as db:
                for key in ('trending', 'popular'):
                    rows = db.execute("""
                        SELECT coalesce(imdb_id, CASE WHEN tmdb_type='movie' THEN 'tmdb:'||tmdb_id END, source_id)
                        FROM member WHERE key=? AND type='movie' ORDER BY rank LIMIT 40
                    """, (key,))
                    seeds.extend(uid for uid, in rows if isinstance(uid, str) and MOVIE_ID.fullmatch(uid))
        path = self.catalog_dir / 'catalog.sqlite3'
        if path.is_file():
            with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=1)) as db:
                rows = db.execute("""SELECT imdb_id FROM title WHERE type='movie' AND adult=0
                                     ORDER BY votes DESC,id LIMIT 20""")
                seeds.extend(uid for uid, in rows if isinstance(uid, str) and MOVIE_ID.fullmatch(uid))
        return deque(dict.fromkeys(seeds))

    def _worker(self):
        seeds = deque()
        next_seed = 0
        while True:
            with self.condition:
                if not self.running:
                    return
                if self.queue:
                    uid = self.queue.popleft()
                elif seeds:
                    uid = seeds.popleft()
                    self.pending.add(uid)
                elif time.monotonic() < next_seed:
                    self.condition.wait(timeout=max(0, next_seed - time.monotonic()))
                    continue
                else:
                    uid = None
            try:
                if uid is None:
                    next_seed = time.monotonic() + 3600
                    seeds = self._seeds()
                else:
                    self._refresh(uid)
            except (sqlite3.Error, OSError):
                # A replaced catalog, missing feed or temporarily unavailable cache is retried.
                pass
            finally:
                if uid is not None:
                    with self.condition:
                        self.pending.discard(uid)
