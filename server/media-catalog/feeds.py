#!/usr/bin/python3
"""Refresh shared catalog membership using public Nuvio charts and Watchmode US subscriptions."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import closing
from dataclasses import astuple, dataclass
from datetime import datetime
from email.utils import parsedate_to_datetime
import fcntl
import hashlib
from html import unescape
import json
import os
from pathlib import Path
import re
import shlex
import sqlite3
import sys
import time
from urllib.parse import quote, urlencode

import requests


DATA = Path(os.environ.get('MKBTV_STATE_DIR', Path.home() / '.local/share/mkbtv')) / 'catalog'
NUVIO = 'https://catalog.nuvio.tv'
WATCHMODE = 'https://api.watchmode.com/v1'
CHART_SOURCE = 'nuvio-chart'
US_SOURCE = 'watchmode-us-sub'
SAMPLE_SOURCE = 'justwatch-nuvio-us-sample'
SAMPLE_REPOSITORY = 'rdfornasero/justwatch-nuvio'
TOP_250 = 'imdb-top-250'
TOP_SOURCE = 'imdb-chart-via-crazyuploader'
TOP_REPOSITORY = 'crazyuploader/IMDb-Top-50'
DAY = 86400
CHARTS = {
    'genre:soap': [('series', 'popular-series', 'Soap')],
    'genre:kids': [('series', 'popular-series', 'Kids')],
    'genre:war & politics': [('series', 'popular-series', 'War & Politics')],
    'airing-today': [('series', 'airing-today-series', None)],
    'new-and-returning': [('series', 'new-and-returning-series', None)],
    'popular': [('movie', 'popular-movies', None), ('series', 'popular-series', None)],
    'trending': [('movie', 'trending-movies', None), ('series', 'trending-series', None)],
}
PROVIDERS = {
    'disney-plus': {'disney+', 'disney plus'},
    'netflix': {'netflix'},
    'hbo': {'max', 'hbo max'},
    'prime-video': {'amazon prime', 'amazon prime video', 'prime video'},
    'hulu': {'hulu'},
}
WATCH_TYPES = {
    'movie': 'movie', 'tv_movie': 'movie', 'short_film': 'movie', 'tv_special': 'movie',
    'tv_miniseries': 'series', 'tv_series': 'series',
}
IMDB = re.compile(r'tt[0-9]{7,}')
TMDB = re.compile(r'tmdb:(?:(movie|tv|series):)?([1-9][0-9]*)')


class FeedError(Exception):
    pass


def require(condition, code):
    if not condition:
        raise FeedError(code)


def log(key, **fields):
    print(json.dumps({'feed': key, **fields}, separators=(',', ':')), flush=True)


class Client:
    def __init__(self, watch_key=None):
        self.session = requests.Session()
        self.session.headers['User-Agent'] = 'mkbtv-catalog/1'
        self.watch_key = watch_key
        self.last_request = 0

    def close(self):
        self.session.close()

    def get(self, url, params=None):
        # No redirect can forward an API key to another origin.
        headers = {'X-API-Key': self.watch_key} if self.watch_key else {}
        for attempt in range(3):
            interval = 1.6 if self.watch_key else 0.8
            time.sleep(max(0, self.last_request + interval - time.monotonic()))
            self.last_request = time.monotonic()
            try:
                response = self.session.get(url, params=params, headers=headers,
                                            allow_redirects=False, timeout=(8, 30))
            except requests.RequestException:
                if attempt == 2:
                    raise FeedError('network-failure') from None
                time.sleep(2 ** (attempt + 1))
                continue
            with response:
                if response.status_code in (429, 500, 502, 503, 504):
                    delay = response.headers.get('Retry-After', '')
                    if delay.isdecimal():
                        wait = int(delay)
                    elif delay:
                        try:
                            wait = max(0, parsedate_to_datetime(delay).timestamp() - time.time())
                        except (ValueError, TypeError, OverflowError):
                            raise FeedError('invalid-retry-after') from None
                    else:
                        wait = 60 if response.status_code == 429 else 2 ** (attempt + 1)
                    if wait > 120 or attempt == 2:
                        raise FeedError(f'HTTP-{response.status_code}')
                    time.sleep(wait)
                    continue
                require(response.status_code == 200, f'HTTP-{response.status_code}')
                try:
                    return response.json()
                except ValueError:
                    raise FeedError('invalid-json') from None


@dataclass(frozen=True)
class Member:
    type: str
    imdb_id: str | None
    rank: int
    source_id: str
    name: str
    year: int | None
    tmdb_id: int | None
    tmdb_type: str | None


def database(path):
    db = sqlite3.connect(path, timeout=30)
    db.execute('PRAGMA foreign_keys=ON')
    db.executescript("""
        CREATE TABLE IF NOT EXISTS feed (
            key TEXT PRIMARY KEY, snapshot TEXT NOT NULL, updated_at INTEGER NOT NULL, source TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS member (
            key TEXT NOT NULL REFERENCES feed(key) ON DELETE CASCADE,
            type TEXT NOT NULL CHECK (type IN ('movie','series')), imdb_id TEXT, rank INTEGER NOT NULL,
            source_id TEXT NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb_id INTEGER, tmdb_type TEXT,
            PRIMARY KEY (key,type,source_id)
        );
        CREATE INDEX IF NOT EXISTS member_rank ON member(key,type,rank);
        CREATE INDEX IF NOT EXISTS member_imdb ON member(key,type,imdb_id);
        CREATE INDEX IF NOT EXISTS member_tmdb_imdb ON member(type,tmdb_id,imdb_id)
            WHERE tmdb_id IS NOT NULL AND imdb_id IS NOT NULL;
        CREATE TABLE IF NOT EXISTS chart_gap (
            key TEXT NOT NULL REFERENCES feed(key) ON DELETE CASCADE, type TEXT NOT NULL, skip INTEGER NOT NULL,
            PRIMARY KEY (key,type,skip)
        );
    """)
    return db


def publish(db, key, source, members, now, gaps=()):
    require(members, 'empty-catalog')
    rows = [astuple(m) for m in members]
    digest = hashlib.sha256(json.dumps([rows, gaps], separators=(',', ':')).encode()).hexdigest()
    with db:
        db.execute('INSERT INTO feed VALUES (?,?,?,?) ON CONFLICT(key) DO UPDATE SET '
                   'snapshot=excluded.snapshot,updated_at=excluded.updated_at,source=excluded.source',
                   (key, digest, now, source))
        db.execute('DELETE FROM member WHERE key=?', (key,))
        db.executemany('INSERT INTO member VALUES (?,?,?,?,?,?,?,?,?)', ((key, *r) for r in rows))
        db.execute('DELETE FROM chart_gap WHERE key=?', (key,))
        db.executemany('INSERT INTO chart_gap VALUES (?,?,?)', ((key, kind, skip) for kind, skip in gaps))
    log(key, status='published', count=len(members), without_imdb=sum(m.imdb_id is None for m in members),
        interior_short_cache_empty_pages=len(gaps))


def expire(db, now):
    # Remove on the last nightly run before the free license's 30-day retention limit.
    with db:
        db.execute('DELETE FROM feed WHERE source=? AND updated_at<=?', (US_SOURCE, now-29*DAY))
        db.execute('DELETE FROM feed WHERE source=? AND updated_at<=?', (SAMPLE_SOURCE, now-14*DAY))


def chart_member(r, kind, rank):
    require(isinstance(r, dict) and r.get('type') == kind, 'invalid-chart-type')
    source_id, name = r.get('id'), r.get('name')
    require(isinstance(source_id, str) and source_id and isinstance(name, str) and name.strip(),
            'invalid-chart-title')
    imdb_id = source_id if IMDB.fullmatch(source_id) else None
    if not imdb_id and isinstance(r.get('imdb_id'), str) and IMDB.fullmatch(r['imdb_id']):
        imdb_id = r['imdb_id']
    tmdb = TMDB.fullmatch(source_id)
    year = re.match(r'[0-9]{4}', str(r.get('releaseInfo') or ''))
    tmdb_type = None
    if tmdb:
        raw_type = tmdb[1] or kind
        tmdb_type = 'tv' if raw_type in ('series', 'tv') else 'movie'
    return Member(kind, imdb_id, rank, source_id, name.strip(),
                  int(year[0]) if year else None, int(tmdb[2]) if tmdb else None, tmdb_type)


def chart_manifest(get):
    meta = get(f'{NUVIO}/manifest.json')
    require(isinstance(meta, dict) and meta.get('id') == 'com.nuvio.tmdb.catalogs', 'wrong-manifest')
    defaults = {o['key']: o.get('default') for o in meta.get('config', [])}
    require(defaults.get('region') == 'US', 'unexpected-default-region')
    return {(c['type'], c['id']): {e['name']: e for e in c.get('extra', [])} for c in meta['catalogs']}


def chart_entries(get, manifest, kind, catalog_id, genre, pause=time.sleep):
    extras = manifest.get((kind, catalog_id), {})
    require('skip' in extras, 'pagination-unadvertised')
    require(not genre or genre in extras.get('genre', {}).get('options', []), 'genre-unadvertised')

    page_ids_seen = {}
    def fetch(skip):
        params = {'genre': genre} if genre else {}
        if skip:
            params['skip'] = str(skip)
        suffix = '/' + urlencode(params, quote_via=quote) if params else ''
        page = get(f'{NUVIO}/catalog/{kind}/{catalog_id}{suffix}.json')
        require(isinstance(page, dict) and isinstance(page.get('metas'), list), 'invalid-chart-page')
        rows = page['metas']
        require(len(rows) <= 20, 'chart-page-width-changed')
        if genre and rows:
            require(any(genre in (r.get('genres') or []) for r in rows), 'genre-filter-ignored')
        require(rows or page.get('cacheMaxAge') in (21600, 300), 'unknown-empty-page')
        members = [chart_member(r, kind, skip+i+1) for i, r in enumerate(rows)]
        page_ids = tuple(m.source_id for m in members)
        if len(page_ids) >= 5:
            require(page_ids_seen.setdefault(page_ids, skip) == skip, 'repeated-chart-page')
        return members, page.get('cacheMaxAge')

    # The source addresses 20 underlying slots at a time; it can publish empty pages
    # amid populated pages. Its observed outer boundary is two short-cache empty
    # pages starting at 5000. Check the rest even when an interior page is empty.
    per_page, retry = {}, {}
    skip = terminal = 0
    while terminal < 2:
        # TMDB error 22 documents 500 pages maximum. Fail if the protocol changes.
        require(skip <= 500 * 20 + 20, 'chart-terminal-not-found')
        members, age = fetch(skip)
        if not members and age == 300 and skip >= 5000:
            terminal += 1
        else:
            terminal = 0
            if not members and age == 300:
                retry[skip] = time.monotonic() + age
            per_page[skip] = members
        skip += 20

    gaps = []
    for skip, next_attempt in retry.items():
        pause(max(0, next_attempt - time.monotonic()))
        members, age = fetch(skip)
        per_page[skip] = members
        if not members and age == 300:
            gaps.append((kind, skip))

    published, seen = [], set()
    for _, items in sorted(per_page.items()):
        for m in items:
            if m.source_id not in seen:
                published.append(m)
                seen.add(m.source_id)
    require(published, 'empty-chart')
    return published, gaps


def chart_part(manifest, kind, catalog_id, genre):
    with closing(Client()) as client:
        return chart_entries(client.get, manifest, kind, catalog_id, genre)


def due_charts(db, now):
    today = datetime.fromtimestamp(now).date()
    fresh = {key for key, stamp in db.execute('SELECT key,updated_at FROM feed WHERE source=?', (CHART_SOURCE,))
             if stamp <= now and datetime.fromtimestamp(stamp).date() == today}
    return [key for key in CHARTS if key not in fresh]


def charts(db, keys):
    if not keys:
        return True
    with closing(Client()) as client:
        manifest = chart_manifest(client.get)
    failed = False
    # A movie and series chart share a key. Fetch independently, then publish
    # both snapshots within one transaction only after both scans succeeded.
    partial = {key: {} for key in keys}
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {pool.submit(chart_part, manifest, *part): (key, i)
                for key in keys for i, part in enumerate(CHARTS[key])}
        for job in as_completed(jobs):
            key, i = jobs[job]
            try:
                partial[key][i] = job.result()
                if len(partial[key]) == len(CHARTS[key]):
                    parts = [partial[key][n] for n in range(len(CHARTS[key]))]
                    members = [m for rows, gaps in parts for m in rows]
                    gaps = [gap for rows, page_gaps in parts for gap in page_gaps]
                    publish(db, key, CHART_SOURCE, members, int(time.time()), gaps)
            except (FeedError, ValueError, TypeError, KeyError) as err:
                log(key, type=CHARTS[key][i][0], status='failed',
                    reason=str(err) if isinstance(err, FeedError) else 'invalid-response')
                failed = True
    return not failed


def provider_ids(sources, key):
    require(isinstance(sources, list), 'invalid-source-directory')
    ids = set()
    for source in sources:
        if (source.get('name') or '').strip().lower() not in PROVIDERS[key]:
            continue
        if source.get('host_source_id') is not None or source.get('host_source'):
            continue
        # /sources is already filtered by types=sub; the schema makes type optional.
        if source.get('type') not in (None, 'sub') or 'US' not in (source.get('regions') or []):
            continue
        source_id = source.get('id')
        require(type(source_id) is int and source_id > 0, 'invalid-provider-id')
        ids.add(source_id)
    require(ids, 'US-subscription-source-unavailable')
    return ','.join(map(str, sorted(ids)))


def watch_member(row, rank):
    source_id, kind = row.get('id'), WATCH_TYPES.get(row.get('type'))
    require(type(source_id) is int and source_id > 0 and kind, 'invalid-watchmode-id-or-type')
    imdb_id, tmdb_id, tmdb_type = row.get('imdb_id'), row.get('tmdb_id'), row.get('tmdb_type')
    # A provider membership can exist before external identifiers are assigned.
    # An absent/malformed optional cross-reference cannot invalidate the title.
    imdb_id = imdb_id.strip() if isinstance(imdb_id, str) else ''
    imdb_id = imdb_id if IMDB.fullmatch(imdb_id) else None
    if isinstance(tmdb_id, str) and re.fullmatch(r'[0-9]{1,12}', tmdb_id):
        tmdb_id = int(tmdb_id)
    expected_type = 'tv' if kind == 'series' else 'movie'
    if type(tmdb_id) is not int or not 0 < tmdb_id < 10**12 or tmdb_type not in (None, '', expected_type):
        tmdb_id = None
    tmdb_type = expected_type if tmdb_id else None
    name, year = row.get('title'), row.get('year')
    require(isinstance(name, str) and name.strip(), 'missing-title')
    require(year is None or type(year) is int, 'invalid-year')
    return Member(kind, imdb_id, rank, f'watchmode:{source_id}', name.strip(), year or None, tmdb_id, tmdb_type)


def watch_entries(get, ids, remaining):
    require(remaining > 0, 'insufficient-quota')
    members = []
    source_ids = set()
    number = 1
    while True:
        page = get(f'{WATCHMODE}/list-titles/', {'source_ids': ids, 'regions': 'US', 'source_types': 'sub',
                                                 'sort_by': 'title_asc', 'limit': 250, 'page': number})
        require(isinstance(page, dict) and page.get('page') == number and isinstance(page.get('titles'), list),
                'invalid-watchmode-page')
        if number == 1:
            total, pages = page.get('total_results'), page.get('total_pages')
            require(type(total) is int and total > 0, 'empty-catalog')
            require(type(pages) is int and pages == (total+249)//250, 'invalid-watchmode-total')
            require(pages <= remaining, 'insufficient-quota')
        require((page.get('total_results'), page.get('total_pages')) == (total, pages), 'watchmode-total-changed')
        require(len(page['titles']) == min(250, total-len(members)), 'incomplete-watchmode-page')
        for row in page['titles']:
            member = watch_member(row, len(members)+1)
            require(member.source_id not in source_ids, 'repeated-watchmode-title')
            members.append(member)
            source_ids.add(member.source_id)
        if number == pages:
            break
        number += 1
    return members


def provider_keys(db, now):
    existing = dict(db.execute('SELECT key,updated_at FROM feed WHERE source=?', (US_SOURCE,)))
    # Prime is the largest catalog. Alternating it with the four others costs
    # about 2,160 US list pages in a 31-day month at the current catalog sizes.
    # A missed slot is due after two days; providers() checks live quota per feed.
    prime_night = (now // DAY) % 2 == 0
    return [key for key in PROVIDERS if key not in existing or now - existing[key] >= 2 * DAY
            or ((key == 'prime-video') == prime_night and now - existing[key] >= DAY // 2)]


def providers(db, watch_key, optional=False):
    keys = provider_keys(db, int(time.time()))
    if not watch_key:
        log('watchmode', status='unavailable', reason='WATCHMODE_API_KEY-missing', feeds_due=keys)
        return optional
    if not keys:
        return True
    with closing(Client(watch_key)) as client:
        status = client.get(f'{WATCHMODE}/status/')
        require(status['quota'] > status['quotaUsed'], 'insufficient-quota')
        sources = client.get(f'{WATCHMODE}/sources/', {'regions': 'US', 'types': 'sub'})
        failed = False
        for key in keys:
            try:
                status = client.get(f'{WATCHMODE}/status/')
                remaining = status['quota'] - status['quotaUsed']
                members = watch_entries(client.get, provider_ids(sources, key), remaining)
                publish(db, key, US_SOURCE, members, int(time.time()))
            except (FeedError, KeyError, TypeError, ValueError) as err:
                reason = str(err) if isinstance(err, FeedError) else 'invalid-response'
                log(key, status='failed', reason=reason)
                failed = True
                if reason in ('HTTP-401', 'HTTP-403', 'HTTP-429'):
                    break
    return not failed


def github_revision(get, repository, directory):
    latest = get(f'https://api.github.com/repos/{repository}/commits',
                 {'path': directory, 'sha': 'main', 'per_page': 1})
    require(isinstance(latest, list) and len(latest) == 1, 'missing-publication')
    revision = latest[0]['sha']
    require(isinstance(revision, str) and re.fullmatch(r'[a-f0-9]{40}', revision), 'invalid-publication-revision')
    stamp = datetime.fromisoformat(latest[0]['commit']['committer']['date'])
    require(stamp.tzinfo is not None, 'invalid-publication-date')
    return revision, int(stamp.timestamp())


def top_250_members(rows, kind):
    require(isinstance(rows, list) and len(rows) == 250, 'incomplete-top-250')
    members = []
    subtypes = {'TV Mini Series', 'TV Series'} if kind == 'series' else {'Movie'}
    for rank, row in enumerate(rows, 1):
        require(isinstance(row, dict) and type(row.get('Rank')) is int and row['Rank'] == rank,
                'invalid-top-250-rank')
        require(row.get('titleType') in subtypes and row.get('isSeries') is (kind == 'series') and
                row.get('isEpisode') is False,
                'invalid-top-250-type')
        name, link = row.get('name'), row.get('link')
        require(isinstance(name, str) and name.strip() and isinstance(link, str), 'invalid-top-250-title')
        match = re.fullmatch(r'https://www\.imdb\.com/title/(tt[0-9]{7,})/', link)
        require(match, 'invalid-top-250-link')
        imdb_id = match[1]
        members.append(Member(kind, imdb_id, rank, imdb_id, unescape(name).strip(), None, None, None))
    return members


def refresh_top_250(db, get, now):
    revision, published_at = github_revision(get, TOP_REPOSITORY, 'data/top250')
    require(0 <= now - published_at < 7*DAY, 'stale-top-250-publication')
    previous = db.execute('SELECT updated_at FROM feed WHERE key=?', (TOP_250,)).fetchone()
    require(not previous or published_at >= previous[0], 'top-250-publication-regressed')
    base = f'https://raw.githubusercontent.com/{TOP_REPOSITORY}/{revision}/data/top250'
    movies = top_250_members(get(f'{base}/movies.json'), 'movie')
    series = top_250_members(get(f'{base}/shows.json'), 'series')
    members = movies + series
    require(len({m.imdb_id for m in members}) == 500, 'duplicate-top-250-id')
    publish(db, TOP_250, TOP_SOURCE, members, published_at)
    return True


def top_250_job(db):
    with closing(Client()) as client:
        return refresh_top_250(db, client.get, int(time.time()))


def provider_samples(db, get, now):
    stored = {key: (source, stamp) for key, source, stamp in db.execute('SELECT key,source,updated_at FROM feed')}
    # Full US source rows take precedence, including after a failed refresh.
    # Expiry is handled before this job. Activation seeds every sampled service.
    keys = [key for key in PROVIDERS if stored.get(key, (None,))[0] != US_SOURCE]
    if not keys:
        return True
    revision, published_at = github_revision(get, SAMPLE_REPOSITORY, 'site/catalog')
    require(0 <= now - published_at < 14*DAY, 'stale-sample-publication')
    base = f'https://raw.githubusercontent.com/{SAMPLE_REPOSITORY}/{revision}/site/catalog'
    good = True
    for key in keys:
        previous_source, previous_date = stored.get(key, (None, 0))
        if previous_source == SAMPLE_SOURCE and previous_date >= published_at:
            continue
        slug = 'max' if key == 'hbo' else key
        try:
            members = []
            for kind in ('movie', 'series'):
                data = get(f'{base}/{kind}/jw_{slug}_{kind}_popular.json')
                rows = data.get('metas') if isinstance(data, dict) else None
                require(isinstance(rows, list) and 0 < len(rows) <= 250, 'invalid-sample-size')
                part = [chart_member(row, kind, i) for i, row in enumerate(rows, start=1)]
                require(len({row.source_id for row in part}) == len(part), 'duplicate-sample-title')
                members.extend(part)
            publish(db, key, SAMPLE_SOURCE, members, published_at)
        except (FeedError, ValueError, TypeError, KeyError) as err:
            log(key, source=SAMPLE_SOURCE, status='failed',
                reason=str(err) if isinstance(err, FeedError) else 'invalid-sample-response')
            good = False
    return good


def provider_job(db, only):
    expire(db, int(time.time()))
    if only == 'watchmode':
        return providers(db, watchmode_key(DATA/'.env.local'))
    full_ok = True
    if only != 'samples':
        try:
            full_ok = providers(db, watchmode_key(DATA/'.env.local'), optional=True)
        except (FeedError, KeyError, ValueError, TypeError) as err:
            log('watchmode', status='failed', reason=str(err) if isinstance(err, FeedError) else 'invalid-response')
            full_ok = False
    with closing(Client()) as client:
        sample_ok = provider_samples(db, client.get, int(time.time()))
    return full_ok and sample_ok


def watchmode_key(path):
    configured = os.environ.get('WATCHMODE_API_KEY')
    if configured:
        return configured
    try:
        with path.open(encoding='utf-8') as handle:
            for line in handle:
                name, equal, value = line.partition('=')
                if equal and name.strip() == 'WATCHMODE_API_KEY':
                    try:
                        values = shlex.split(value.strip(), comments=True)
                    except ValueError:
                        raise FeedError('invalid-private-env') from None
                    require(len(values) <= 1, 'invalid-private-env')
                    return values[0] if values else None
    except FileNotFoundError:
        pass
    except (OSError, UnicodeError):
        raise FeedError('private-env-unreadable') from None
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', choices=('watchmode', 'samples', 'providers', 'charts', TOP_250, *CHARTS))
    args = parser.parse_args()
    root = DATA
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    good = True
    with closing(database(root/'feeds.sqlite3')) as db:
        jobs = []
        if args.only in (None, TOP_250):
            jobs.append((TOP_250, lambda: top_250_job(db)))
        if args.only in (None, 'watchmode', 'providers', 'samples'):
            jobs.append(('watchmode', lambda: provider_job(db, args.only)))
        if args.only in (None, 'charts', *CHARTS):
            # A retry of the nightly job fills only failed feeds. An explicit
            # chart selection remains available for an on-demand refresh.
            keys = [args.only] if args.only in CHARTS else due_charts(db, int(time.time()))
            jobs.append(('charts', lambda: charts(db, keys)))
        for name, action in jobs:
            with (root/f'.feeds-{name}.lock').open('a') as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    if not action():
                        good = False
                except BlockingIOError:
                    log(name, status='failed', reason='already-running')
                    good = False
                except (FeedError, KeyError, TypeError, ValueError) as err:
                    log(name, status='failed', reason=str(err) if isinstance(err, FeedError) else 'invalid-response')
                    good = False
    return 0 if good else 1


if __name__ == '__main__':
    sys.exit(main())
