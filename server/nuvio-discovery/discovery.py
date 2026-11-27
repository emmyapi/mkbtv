#!/usr/bin/env python3
"""Private Nuvio catalog from local watched exports; no account credentials.

Candidates: Nuvio Catalog, TMDB title recommendations and Simkl related titles.
Live profile sessions rotate genre rows based on recent distinct watched titles.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import fcntl
import hashlib
import hmac
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import random
import secrets
import sqlite3
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

from poster_art import PosterArt, TITLE_ID

STATE = Path(os.environ.get('MKBTV_STATE_DIR', Path.home() / '.local/share/mkbtv'))
ROOT = Path(os.environ.get('MKBTV_DISCOVERY_DIR', STATE / 'discovery'))
HISTORY = Path(os.environ.get('MKBTV_HISTORY_DIR', STATE / 'history'))
CATALOG = 'https://catalog.nuvio.tv'
CINEMETA = 'https://v3-cinemeta.strem.io'
SIMKL = 'https://api.simkl.com'
TMDB = 'https://www.themoviedb.org'
TMDB_BASIS = 'TMDB title recommendations'
SIMKL_BASIS = 'Simkl users_recommendations'
ALLOWED = {'catalog.nuvio.tv', 'v3-cinemeta.strem.io', 'cinemeta-live.strem.io', 'cinemeta-catalogs.strem.io', 'api.simkl.com', 'www.themoviedb.org'}
ID = re.compile(r'^(tt[0-9]+|tmdb:[0-9]+)$')
TYPE = {'movie', 'series'}
MAX_BYTES = 8 * 1024 * 1024
MOVIE_GENRES = {'Action', 'Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama',
                'Family', 'Fantasy', 'History', 'Horror', 'Music', 'Mystery', 'Romance',
                'Science Fiction', 'Thriller', 'War', 'Western'}
SERIES_GENRES = {'Action': 'Action & Adventure', 'Adventure': 'Action & Adventure',
                'Science Fiction': 'Sci-Fi & Fantasy', 'Fantasy': 'Sci-Fi & Fantasy',
                'War': 'War & Politics', 'History': 'War & Politics'}
TV_GENRES = {'Action & Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama',
             'Family', 'Kids', 'Mystery', 'News', 'Reality', 'Sci-Fi & Fantasy', 'Soap', 'Talk',
             'War & Politics', 'Western'}

class DiscoveryError(Exception):
    pass


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix='.new-')
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(',', ':'))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def cache_path(root, url):
    return root / 'cache' / (hashlib.sha256(url.encode()).hexdigest() + '.json')


def fetch(root, url, ttl=86400):
    host = urlsplit(url).hostname
    if host not in ALLOWED or urlsplit(url).scheme != 'https':
        raise DiscoveryError('UPSTREAM_NOT_ALLOWED')
    path = cache_path(root, url)
    if path.exists() and time.time() - path.stat().st_mtime < ttl:
        return json.loads(path.read_text())
    negative = path.with_suffix('.failure')
    host_cooldown = path.parent / (host + '.cooldown')
    for marker in (host_cooldown, negative):
        if marker.exists():
            failure = json.loads(marker.read_text())
            if failure['until'] > time.time():
                if path.exists() and failure['code'] != 404:
                    return json.loads(path.read_text())
                raise DiscoveryError('UPSTREAM_HTTP_' + str(failure['code']))
    request = Request(url, headers={'User-Agent': 'mkbtv-discovery/1'})
    try:
        with urlopen(request, timeout=4 if host in ('api.simkl.com', 'www.themoviedb.org') else 20) as response:
            if urlsplit(response.url).hostname not in ALLOWED:
                raise DiscoveryError('UPSTREAM_REDIRECT_REFUSED')
            body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            raise DiscoveryError('UPSTREAM_TOO_LARGE')
        value = parse_tmdb_recommendations(body) if host == 'www.themoviedb.org' else json.loads(body)
    except HTTPError as error:
        cooldown = (error.code == 429 or (host == 'api.simkl.com' and error.code == 412)
                    or (host == 'www.themoviedb.org' and (error.code in (403, 412) or error.code >= 500)))
        if cooldown:
            retry = error.headers.get('Retry-After', '')
            delay = int(retry) if re.fullmatch('[0-9]{1,8}', retry) else (300 if error.code == 429 else 3600)
            atomic_json(host_cooldown, {'code': error.code, 'until': time.time() + max(1, delay)})
        elif error.code == 404:
            atomic_json(negative, {'code': error.code, 'until': time.time() + 86400})
        if path.exists() and (cooldown or error.code >= 500):
            return json.loads(path.read_text())
        raise DiscoveryError('UPSTREAM_HTTP_' + str(error.code)) from None
    except (URLError, TimeoutError, ValueError) as error:
        if host == 'www.themoviedb.org':
            marker = negative if isinstance(error, ValueError) else host_cooldown
            atomic_json(marker, {'code': 503, 'until': time.time() + 300})
        if path.exists():
            return json.loads(path.read_text())
        raise DiscoveryError('UPSTREAM_UNAVAILABLE') from None
    atomic_json(path, value)
    return value


def canonical_genres(meta):
    result = set()
    for value in meta.get('genres', []):
        result.update({'Sci-Fi': ['Science Fiction'], 'Sci-Fi & Fantasy': ['Science Fiction', 'Fantasy'],
                       'Action & Adventure': ['Action', 'Adventure'],
                       'War & Politics': ['War']}.get(value, [value]))
    return result


def identity(meta):
    return (meta.get('type'), meta.get('id'))


def title_keys(meta):
    keys = {identity(meta)}
    if meta.get('moviedb_id'):
        keys.add((meta['type'], 'tmdb:' + str(meta['moviedb_id'])))
    if meta.get('imdb_id'):
        keys.add((meta['type'], meta['imdb_id']))
    return keys


def safe_meta(meta):
    if not isinstance(meta, dict) or meta.get('type') not in TYPE or not ID.fullmatch(str(meta.get('id', ''))):
        return None
    if not isinstance(meta.get('name'), str) or not meta['name'].strip():
        return None
    # Only catalog preview fields. No source account IDs, source URL or watch timestamps.
    keys = ('id', 'type', 'name', 'poster', 'posterShape', 'background', 'logo', 'description',
            'releaseInfo', 'genres', 'imdbRating', 'released', 'runtime')
    preview = {key: meta[key] for key in keys if key in meta}
    imdb = meta.get('imdb_id')
    if isinstance(imdb, str) and re.fullmatch(r'tt[0-9]{1,12}', imdb):
        preview['imdb_id'] = imdb
    number = str(meta.get('moviedb_id', ''))
    if re.fullmatch(r'[1-9][0-9]{0,11}', number):
        preview['moviedb_id'] = number
    return preview


def metadata_url(host, kind, content_id):
    return host + '/meta/' + kind + '/' + quote(content_id) + '.json'


def metadata(root, kind, content_id, *, load=None):
    load = load or fetch
    if kind not in TYPE or not ID.fullmatch(content_id):
        return None
    host = CINEMETA if content_id.startswith('tt') else CATALOG
    try:
        data = load(root, metadata_url(host, kind, content_id), 7 * 86400)
    except DiscoveryError as error:
        if host == CINEMETA and str(error) in ('UPSTREAM_HTTP_404', 'UPSTREAM_HTTP_412'):
            try:
                data = load(root, metadata_url(CATALOG, kind, content_id), 7 * 86400)
            except DiscoveryError as fallback_error:
                if str(fallback_error) == 'UPSTREAM_HTTP_404':
                    return None
                raise
        elif str(error) == 'UPSTREAM_HTTP_404':
            return None
        else:
            raise
    raw = data.get('meta')
    result = safe_meta(raw)
    if not result or result['type'] != kind:
        return None
    if content_id.startswith('tt') and any(key.startswith('tt') and key != content_id
                                           for _, key in title_keys(result)):
        return None
    number = result.get('moviedb_id', '')  # Cinemeta
    if not re.fullmatch(r'[1-9][0-9]{0,11}', number):
        number = ''
        # Nuvio Catalog identifies the original TMDB record with a typed source link.
        for link in raw.get('links', []):
            if isinstance(link, dict) and link.get('category') == 'source':
                match = re.fullmatch(re.escape(TMDB) + r'/(movie|tv)/([1-9][0-9]{0,11})', str(link.get('url')))
                if match:
                    if (match[1] == 'movie') != (kind == 'movie'):
                        return None
                    number = match[2]
                    break
    if content_id.startswith('tmdb:'):
        if number and content_id != 'tmdb:' + number:
            return None
        if result['id'].startswith('tmdb:') and result['id'] != content_id:
            return None
        number = content_id[5:]
    if number:
        result['moviedb_id'] = number
    return result


def watched_unique(document):
    if document.get('format') != 'nuvio-profile-backup-v2':
        raise DiscoveryError('HISTORY_FORMAT_INVALID')
    profile = document.get('profile_id')
    unique = {}
    for row in document.get('watched', []):
        if row.get('profile_id', profile) != profile:
            raise DiscoveryError('HISTORY_PROFILE_MISMATCH')
        kind, content_id = row.get('content_type'), row.get('content_id')
        if kind not in TYPE or not isinstance(content_id, str) or not ID.fullmatch(content_id):
            continue
        date = row.get('watched_at') or row.get('created_at')
        if isinstance(date, str) and not date.isdigit():
            try:
                parsed = datetime.fromisoformat(date)
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                date = int(parsed.timestamp() * 1000)
            except (ValueError, OverflowError):
                continue
        try:
            date = int(date)
        except (TypeError, ValueError):
            continue
        key = (kind, content_id)
        if date > unique.get(key, 0):
            unique[key] = date
    return unique


def genre_preferences(metas):
    counts = Counter()
    for meta in metas:
        if meta:
            # A TV series contributes once, regardless of episode count.
            for genre in canonical_genres(meta):
                counts[genre] += 1
    return counts


def related(root, seed, *, load=None):
    load = load or fetch
    imdb = seed['id'] if seed['id'].startswith('tt') else seed.get('imdb_id', '')
    if not re.fullmatch(r'tt[0-9]{1,12}', imdb):
        return []
    path = 'movies' if seed['type'] == 'movie' else 'tv'
    data = load(root, SIMKL + '/' + path + '/' + imdb + '?extended=full', 7 * 86400)
    if not isinstance(data, dict):
        raise DiscoveryError('SIMKL_RECOMMENDATIONS_INVALID')
    rows = data.get('users_recommendations')
    if rows is None:
        return []
    if not isinstance(rows, list):
        raise DiscoveryError('SIMKL_RECOMMENDATIONS_INVALID')
    result = []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get('ids'), dict):
            continue
        kind = 'movie' if row.get('type') == 'movie' else 'series' if row.get('type') in ('tv', 'show') else None
        item_id = row.get('ids', {}).get('imdb')
        if kind and isinstance(item_id, str) and re.fullmatch(r'tt[0-9]{1,12}', item_id):
            result.append((kind, item_id))
    return list(dict.fromkeys(result))


class RecommendationScroller(HTMLParser):
    """Read typed title links from TMDB's per-title recommendations carousel."""
    def __init__(self):
        super().__init__()
        self.depth = 0
        self.found = False
        self.keys = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'div' and attrs.get('id') == 'recommendation_scroller':
            self.depth = 1
            self.found = True
        elif self.depth:
            if tag == 'div':
                self.depth += 1
            elif tag == 'a':
                match = re.fullmatch(r'/(movie|tv)/([1-9][0-9]{0,11})(?:-[^/?#]+)?/?', attrs.get('href') or '')
                if match:
                    kind = 'movie' if match[1] == 'movie' else 'series'
                    self.keys[kind, 'tmdb:' + match[2]] = None

    def handle_endtag(self, tag):
        if self.depth and tag == 'div':
            self.depth -= 1


def parse_tmdb_recommendations(body):
    parser = RecommendationScroller()
    parser.feed(body.decode('utf-8'))
    parser.close()
    if not parser.found:
        raise ValueError('TMDB_RECOMMENDATION_SCROLLER_MISSING')
    return list(parser.keys)


def tmdb_related(root, seed, *, load=None):
    load = load or fetch
    number = str(seed.get('moviedb_id', ''))
    if seed['id'].startswith('tmdb:'):
        number = seed['id'][5:]
    if not re.fullmatch(r'[1-9][0-9]{0,11}', number):
        return []
    section = 'movie' if seed['type'] == 'movie' else 'tv'
    url = f'{TMDB}/{section}/{number}/remote/recommendations?version=1&translate=false'
    return [tuple(key) for key in load(root, url, 7 * 86400)][:20]


def interleave(movies, series, limit=60):
    result = []
    for index in range(max(len(movies), len(series))):
        for group in (movies, series):
            if index < len(group):
                result.append(group[index])
                if len(result) >= limit:
                    return result
    return result


def rank_candidates(candidates, watched, preferences, related_weights):
    unique = {identity(m): m for m in candidates if m and title_keys(m).isdisjoint(watched)}
    maximum = max(preferences.values(), default=1)
    def score(meta):
        genres = canonical_genres(meta)
        affinity = sum(preferences[g] / maximum for g in genres) / max(1, len(genres))
        return related_weights.get(identity(meta), 0) + 0.3 * affinity
    ordered = sorted(unique.values(), key=lambda meta: (-score(meta), meta['id']))
    return interleave([m for m in ordered if m['type'] == 'movie'],
                      [m for m in ordered if m['type'] == 'series'])


def catalog(root, kind, catalog_id, genre=None, *, skip=0):
    suffix = '/genre=' + quote(genre) if genre else ''
    if skip:
        suffix += ('&' if suffix else '/') + 'skip=' + str(skip)
    data = fetch(root, CATALOG + '/catalog/' + kind + '/' + catalog_id + suffix + '.json', 6 * 3600)
    values = [safe_meta(row) for row in data.get('metas', [])]
    return [row for row in values if row]


def trending_genre_pool(root, genres, watched, first_page):
    """Split the shared Trending charts by the genres on their actual posters."""
    if not genres:
        return {}
    pool = {genre: [] for genre in genres}
    seen = set(watched)

    def add_page(items):
        for item in items:
            keys = title_keys(item)
            if not keys.isdisjoint(seen):
                continue
            seen.update(keys)
            for genre in canonical_genres(item).intersection(pool):
                if len(pool[genre]) < 40:
                    pool[genre].append(item)

    add_page(first_page)
    # Trending has no genre parameter. Share each 20-slot page across every
    # genre and profile. A short or empty page can precede populated pages.
    for skip in range(20, 201, 20):
        if all(len(items) >= 40 for items in pool.values()):
            break
        try:
            movies = catalog(root, 'movie', 'trending-movies', skip=skip)
            series = catalog(root, 'series', 'trending-series', skip=skip)
        except DiscoveryError:
            break  # Keep ranked posters already received if the provider goes offline.
        add_page(interleave(movies, series))
    return {genre: items for genre, items in pool.items() if items}


def preferred_genres(metas, limit, supported=MOVIE_GENRES):
    preferences = genre_preferences(metas)
    order = {}
    for meta in metas:
        for genre in sorted(canonical_genres(meta)):
            if genre in supported:
                order.setdefault(genre, len(order))
    return sorted(order, key=lambda g: (-preferences[g], order[g]))[:limit]


def choose_genre_rows(profile, previous, rotation):
    pool = profile['genre_pool']
    count = min(3, len(pool))
    old = [row['name'] for row in previous.get('rows', []) if row['id'].startswith('genre-')]
    selected = random.SystemRandom().sample(list(pool), count)
    if set(selected) == set(old) and len(pool) > count:
        selected[-1] = secrets.choice([name for name in pool if name not in selected])
    elif selected == old and count > 1:
        selected = selected[1:] + selected[:1]
    set_genre_rows(profile, selected, rotation)


def set_genre_rows(profile, genres, rotation=None):
    rows = [r for r in profile['rows'] if not r['id'].startswith('genre-')]
    for i, genre in enumerate(genres, 1):
        ranked = profile['genre_pool'][genre]
        cards = list(ranked)
        if rotation is not None:
            # Keep chart leaders near the front while varying the visible posters
            # across visits. Retrying a session reproduces the same shelf.
            rng = random.Random(f'{rotation}/{genre}')
            for start in range(0, len(cards), 6):
                group = cards[start:start + 6]
                rng.shuffle(group)
                cards[start:start + 6] = group
        rows.append({'id': f'genre-{i}', 'name': genre, 'metas': cards, 'basis': 'nuvio_trending'})
    profile['rows'] = rows


def build_profile(root, document, excluded_ids=(), previous_rows=()):
    unique = watched_unique(document)
    keys = sorted(unique, key=lambda key: (unique[key], key), reverse=True)
    eligible_keys = [key for key in keys if key[1] not in excluded_ids]
    recent_metas, distinct = [], set()
    start = 0
    with ThreadPoolExecutor(max_workers=3) as pool:
        while len(recent_metas) < 15 and start < len(eligible_keys):
            batch = eligible_keys[start:start + 15 - len(recent_metas)]
            start += len(batch)
            for key, result in zip(batch, pool.map(lambda key: metadata(root, *key), batch)):
                aliases = ({key} | title_keys(result)) if result else {key}
                if aliases.isdisjoint(distinct) and all(alias[1] not in excluded_ids for alias in aliases):
                    recent_metas.append(result)
                    distinct.update(aliases)
    available = [m for m in recent_metas if m]
    if recent_metas and not available:
        raise DiscoveryError('HISTORY_METADATA_INCOMPLETE')
    seed_metas = [m for m in recent_metas[:3] if m]
    preferences = genre_preferences(seed_metas)
    taste_genres = preferred_genres(seed_metas, 3) or ['Drama', 'Comedy', 'Action']
    genre_pool = preferred_genres(available, 6, MOVIE_GENRES | TV_GENRES)
    watched = set(unique)
    for meta in available:
        watched.update(title_keys(meta))
    because_seeds = available[:3]
    relations = {}
    unavailable = set()
    for seed in because_seeds:
        for basis, source in ((TMDB_BASIS, tmdb_related), (SIMKL_BASIS, related)):
            group = identity(seed), basis
            try:
                relations[group] = source(root, seed)
            except DiscoveryError as error:
                relations[group] = []
                if str(error) != 'UPSTREAM_HTTP_404':
                    unavailable.add(group)
    candidate_keys = list(dict.fromkeys(key for group in relations.values() for key in group if key not in watched))
    def related_meta(key):
        try:
            return metadata(root, *key)
        except DiscoveryError as error:
            return error
    with ThreadPoolExecutor(max_workers=3) as pool:
        # Nuvio can return an IMDb card for a TMDB request. Retain the requested key.
        by_id = dict(zip(candidate_keys, pool.map(related_meta, candidate_keys)))
    weights = Counter()
    taste_candidates = []
    for seed in seed_metas:
        for index, key in enumerate(relations[identity(seed), SIMKL_BASIS]):
            card = by_id.get(key)
            if isinstance(card, dict):
                weights[identity(card)] += 1 / (index + 1)
                taste_candidates.append(card)
    trending = interleave(catalog(root, 'movie', 'trending-movies'), catalog(root, 'series', 'trending-series'))
    rows = [{'id': 'for-you', 'name': 'For You', 'metas': []},
            {'id': 'popular-now', 'name': 'Popular Now', 'metas': trending}]
    # Keep the For You candidate pool independent of the random genre shelves.
    def genre_titles(genre):
        movies = catalog(root, 'movie', 'popular-movies', genre) if genre in MOVIE_GENRES else []
        tv_genre = SERIES_GENRES.get(genre, genre)
        series = catalog(root, 'series', 'popular-series', tv_genre) if tv_genre in TV_GENRES else []
        return [m for m in interleave(movies, series) if identity(m) not in watched]
    for genre in taste_genres:
        taste_candidates.extend(genre_titles(genre))
    rows[0]['metas'] = rank_candidates(taste_candidates, watched, preferences, weights)
    # Only a previously verified recommendation for this exact seed/source can survive an outage.
    old = {(tuple(row['seed']), row['basis']): row['metas'] for row in previous_rows if 'seed' in row}
    for seed in because_seeds:
        for basis in (TMDB_BASIS, SIMKL_BASIS):
            group = identity(seed), basis
            previous = old.get(group, [])
            prior_by_key = {alias: card for card in previous for alias in title_keys(card)}
            source_keys = relations[group] if group not in unavailable else [identity(card) for card in previous]
            seen = set(watched)
            recommended = []
            for key in source_keys:
                card = prior_by_key.get(key) if group in unavailable else by_id.get(key)
                if isinstance(card, DiscoveryError):
                    card = prior_by_key.get(key)
                if card and key not in seen and title_keys(card).isdisjoint(seen):
                    recommended.append(card)
                    seen.update(title_keys(card))
                    seen.add(key)
            if recommended:
                index = len(rows) - 1
                rows.append({'id': 'because-watched' + (f'-{index}' if index > 1 else ''),
                             'name': 'Because You Watched ' + seed['name'], 'metas': recommended,
                             'seed': list(identity(seed)), 'basis': basis})
                break
    if not trending or not rows[0]['metas']:
        raise DiscoveryError('GENERATED_CATALOG_EMPTY')
    pool = trending_genre_pool(root, genre_pool, watched, trending)
    result = {'profile_id': document['profile_id'], 'updated_at': datetime.now(timezone.utc).isoformat(),
              'source_capture': document['capture_finished_at'], 'rows': rows, 'genre_pool': pool,
              'summary': {'watched_distinct': len(keys), 'matched': len(available), 'seed_count': len(seed_metas),
                          'related_seed_count': len(rows) - 2, 'genre_count': min(3, len(pool)),
                          'genre_pool_count': len(pool), 'row_count': len(rows) + min(3, len(pool)),
                          'because_watched_source': next((r['basis'] for r in rows if 'basis' in r), None)}}
    set_genre_rows(result, list(pool)[:3])
    return result


def live_history(document, previous, session):
    # Keep unsynced completions until export, or undo from a subsequent snapshot in the same session.
    exported = watched_unique(document)
    live = {(r['type'], r['id']): r['at'] for r in previous.get('live_watched', [])}
    last_snapshot = previous.get('client_snapshot', [])
    snapshot = session['watched'] if session else last_snapshot
    current = {}
    for row in snapshot:
        key = (row['type'], row['id'])
        current[key] = max(current.get(key, 0), row['at'])
    if session and session['session'] == previous.get('session'):
        cutoff = min(current.values()) if len(snapshot) >= 100 else 0
        for row in last_snapshot:
            key = (row['type'], row['id'])
            if row['at'] != live.get(key):
                continue
            if key in current or row['at'] > cutoff:
                live.pop(key)
    if session:
        for key, at in current.items():
            live[key] = max(live.get(key, 0), at)
    additions = [{'type': k, 'id': i, 'at': at} for (k, i), at in sorted(live.items()) if at > exported.get((k, i), 0)]
    document['watched'] = document['watched'] + [
        {'content_type': r['type'], 'content_id': r['id'], 'watched_at': r['at']} for r in additions]
    return additions, snapshot


def update_profile(root, document, previous, session, excluded):
    exported_count = len(document['watched'])
    live, snapshot = live_history(document, previous, session)
    watches = watched_unique(document)
    signature = hashlib.sha256(json.dumps([sorted(watches.items()), sorted(excluded)]).encode()).hexdigest()
    history_changed = signature != previous.get('history_signature')
    seasonal_history = {kind + '/' + uid: at for (kind, uid), at in watches.items()}
    seasonal_progress = sorted({entry['content_id'] for entry in document.get('progress', [])
                                if entry.get('content_type') in ('tv', 'series')
                                and isinstance(entry.get('position'), (int, float)) and entry['position'] > 0})
    session_id = session['session'] if session else previous.get('session')
    new_session = session_id != previous.get('session')
    if session and not history_changed and not previous.get('recommendations_pending') and previous.get('genre_source') == 'nuvio_trending':
        if (not new_session and snapshot == previous.get('client_snapshot') and live == previous.get('live_watched')
                and seasonal_history == previous.get('seasonal_history')
                and seasonal_progress == previous.get('seasonal_progress')):
            return previous
        data = dict(previous)
    else:
        try:
            data = build_profile(root, document, excluded, previous.get('rows', []))
        except DiscoveryError:
            if not session or not previous.get('rows'):
                raise
            # The seasonal pool is already local. A failed remote recommendation lookup must
            # not discard a valid watch or keep its old seasonal poster on Home.
            return dict(previous, live_watched=live, client_snapshot=snapshot, session=session_id,
                        seasonal_history=seasonal_history, seasonal_progress=seasonal_progress,
                        recommendations_pending=True)
    previous_genres = [r['name'] for r in previous.get('rows', []) if r['id'].startswith('genre-')]
    pool = data['genre_pool']
    rotation = f'{session_id or ""}/{signature}'
    if new_session or history_changed or len(previous_genres) != min(3, len(pool)) or not set(previous_genres) <= pool.keys():
        choose_genre_rows(data, previous, rotation)
    else:
        set_genre_rows(data, previous_genres, rotation)
    data.pop('recommendations_pending', None)
    changed = data['rows'] != previous.get('rows') or history_changed
    data.update(live_watched=live, client_snapshot=snapshot, history_signature=signature, session=session_id,
                genre_source='nuvio_trending', seasonal_history=seasonal_history, seasonal_progress=seasonal_progress,
                exported_count=exported_count, source_capture=document['capture_finished_at'],
                revision=previous.get('revision', 0) + int(changed))
    return data


def refresh(root, history, requested_profile=None, session=None):
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (root / '.refresh.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        config_path = root / 'config.json'
        config = json.loads(config_path.read_text()) if config_path.exists() else {
            'listen': '127.0.0.1', 'port': 8387, 'tokens': {}}
        manifest_data = json.loads((history / 'manifest.json').read_text())
        if manifest_data.get('format') != 'nuvio-backup-manifest-v1':
            raise DiscoveryError('HISTORY_MANIFEST_INVALID')
        generation = manifest_data.get('generation', '')
        if not isinstance(generation, str) or not re.fullmatch(r'[0-9]{8}T[0-9]{6}\.[0-9]{6}Z', generation):
            raise DiscoveryError('HISTORY_GENERATION_INVALID')
        directory = history / 'generations' / generation
        records = manifest_data.get('profiles', [])
        profiles = [row.get('profile_id') for row in records]
        if not profiles or any(type(p) is not int or p not in range(1, 7) for p in profiles) or len(set(profiles)) != len(profiles):
            raise DiscoveryError('HISTORY_PROFILES_INVALID')
        state_path = root / 'data' / 'catalogs.json'
        previous = json.loads(state_path.read_text()).get('profiles', {}) if state_path.exists() else {}
        built = dict(previous) if requested_profile is not None else {}
        summaries = []
        if requested_profile is not None and requested_profile not in profiles:
            raise DiscoveryError('HISTORY_PROFILE_UNAVAILABLE')
        for record in records:
            profile = record['profile_id']
            if requested_profile is not None and profile != requested_profile:
                continue
            path = directory / ('profile-' + str(profile) + '.json')
            body = path.read_bytes()
            if hashlib.sha256(body).hexdigest() != record.get('sha256'):
                raise DiscoveryError('HISTORY_CHECKSUM_MISMATCH')
            document = json.loads(body)
            if document.get('profile_id') != profile or document.get('format') != 'nuvio-profile-backup-v2':
                raise DiscoveryError('HISTORY_PROFILE_INVALID')
            for field in ('library', 'watched', 'progress'):
                rows = document.get(field)
                if not isinstance(rows, list) or len(rows) != record.get('counts', {}).get(field):
                    raise DiscoveryError('HISTORY_COUNTS_INVALID')
                if not all(isinstance(row, dict) and isinstance(row.get('content_id'), str) and
                           row['content_id'] and isinstance(row.get('content_type'), str) and row['content_type'] for row in rows):
                    raise DiscoveryError('HISTORY_ROWS_INVALID')
            old = previous.get(str(profile), {})
            if not document['watched'] and old.get('exported_count', old.get('summary', {}).get('watched_distinct', 0)):
                raise DiscoveryError('HISTORY_UNEXPECTEDLY_EMPTY')
            try:
                if datetime.fromisoformat(document['capture_finished_at']).tzinfo is None:
                    raise ValueError()
            except (ValueError, TypeError, KeyError):
                raise DiscoveryError('HISTORY_TIME_INVALID') from None
            data = update_profile(root, document, old, session, config.get('excluded_seed_ids', {}).get(str(profile), []))
            if data is old:
                return manifest(data)
            built[str(profile)] = data
            config['tokens'].setdefault(str(profile), secrets.token_urlsafe(32))
            summaries.append({'profile': profile, **data['summary']})
        # Prepare every profile before one atomic publication. Failed fetches leave all prior rows.
        with (root / '.publication.lock').open('a') as publication:
            fcntl.flock(publication, fcntl.LOCK_EX)
            atomic_json(state_path, {'format': 'nuvio-discovery-v1', 'profiles': built})
        if requested_profile is not None:
            return manifest(built[str(requested_profile)])
        config['tokens'] = {p: config['tokens'][p] for p in built}
        atomic_json(config_path, config)
        print(json.dumps({'refreshed': summaries}))


def manifest(data):
    return {'id': 'dk.mkb.nuvio.discovery.p' + str(data['profile_id']), 'version': '1.1.0', 'configVersion': data.get('revision', 0),
            'name': 'Personalized Recommendations',
            'description': 'Recommendations for the titles and genres you watch. Related titles from TMDB and Simkl. Catalogs from Nuvio/TMDB and metadata from Cinemeta.',
            'resources': ['catalog'], 'types': ['movie', 'series', 'mixed'],
            'catalogs': [{'id': row['id'], 'type': 'mixed', 'name': row['name']} for row in data['rows']],
            'behaviorHints': {'adult': False, 'p2p': False, 'configurable': False}}


def response_for(root, config, path, session=None):
    if path == '/health':
        return 200, {'status': 'ok'}
    parts = urlsplit(path).path.strip('/').split('/')
    profile = token_profile(config, parts[0])
    if profile is None:
        return 404, {'error': 'not_found'}
    if parts[1:] in (['manifest.json'], ['catalog', 'mixed', 'seasonal.json']):
        from seasonal_profile import profile_view
        base, seasonal, info, version = profile_view(root, config, profile, session)
        if base is None:
            return 404, {'error': 'not_found'}
        if parts[1] == 'manifest.json':
            result = manifest(base)
            if base.get('recommendations_pending'):
                result['householdRetryAfterMs'] = 60000
            if seasonal:
                result['catalogs'].insert(0, {'id': 'seasonal', 'type': 'mixed', 'name': seasonal['name']})
            return 200, dict(result, **info, configVersion=version)
        return 200, {'metas': seasonal['metas'] if seasonal else [], 'configVersion': version,
                     'seasonalOccurrence': info.get('seasonalOccurrence'),
                     'seasonalValidUntil': info['seasonalValidUntil'], 'cacheMaxAge': 0,
                     'staleRevalidate': 0, 'staleError': 0}
    data = json.loads((root / 'data' / 'catalogs.json').read_text()).get('profiles', {}).get(profile)
    if data is None:
        return 404, {'error': 'not_found'}
    if len(parts) == 4 and parts[1:3] == ['catalog', 'mixed'] and parts[3].endswith('.json'):
        row = next((row for row in data['rows'] if row['id'] == parts[3][:-5]), None)
        if row:
            return 200, {'metas': row['metas'], 'cacheMaxAge': 0, 'staleRevalidate': 0, 'staleError': 86400}
    return 404, {'error': 'not_found'}


def token_profile(config, candidate):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', candidate):
        return None
    return next((p for p, token in config['tokens'].items() if hmac.compare_digest(candidate, token)), None)


def valid_session(value):
    if not isinstance(value, dict) or not isinstance(value.get('session'), str) or not re.fullmatch(
            r'[A-Za-z0-9_-]{1,128}', value['session']):
        return False
    rows = value.get('watched')
    now = int(time.time() * 1000)
    def events_ok(items, limit):
        return isinstance(items, list) and len(items) <= limit and all(
            isinstance(row, dict) and isinstance(row.get('type'), str) and row['type'] in TYPE and
            isinstance(row.get('id'), str) and len(row['id']) <= 20 and ID.fullmatch(row['id']) and
            type(row.get('at')) is int and 0 < row['at'] <= now + 86400_000 for row in items)
    progress = value.get('seasonalInProgress', [])
    return (events_ok(rows, 100) and events_ok(value.get('seasonalWatched', []), 512)
            and events_ok(value.get('seasonalUnwatched', []), 512)
            and isinstance(progress, list) and len(progress) <= 8 and all(
                isinstance(uid, str) and len(uid) <= 20 and ID.fullmatch(uid) for uid in progress))


def discover_response(root, profile, request):
    from discover_query import DATA, QueryError, query_page, uri
    from contextlib import closing
    # A watch filter may read existing public ID mappings, but must never
    # trigger a fetch or cache write based on a profile's history.
    crosswalk = None
    native_members = set()
    catalog_key = request.get('catalog', 'all') if isinstance(request, dict) else 'all'

    def canonical(kind, ident):
        nonlocal crosswalk
        if crosswalk is None:
            # query_page has validated these lists before requesting an identity.
            # Read all relevant public mappings once; private IDs stay in memory.
            wanted = {int(uid[5:]) for key in ('watched_movies', 'watched_series')
                      for uid in request.get(key, []) if re.fullmatch(r'tmdb:[0-9]{1,12}', uid)}
            crosswalk = {}
            try:
                with closing(sqlite3.connect(uri(DATA/'feeds.sqlite3'), uri=True)) as db:
                    db.execute('PRAGMA temp_store=MEMORY')
                    rows = db.execute("""
                        SELECT m.type,m.tmdb_id,min(nullif(m.imdb_id,'')),max(nullif(m.imdb_id,'')),
                               max(CASE WHEN ?='all' OR m.key=? THEN 1 ELSE 0 END)
                        FROM member m JOIN feed f ON f.key=m.key
                        WHERE m.tmdb_id IN (SELECT value FROM json_each(?))
                          AND (m.tmdb_type IS NULL OR m.tmdb_type=CASE m.type WHEN 'series' THEN 'tv' ELSE 'movie' END)
                          AND (f.source<>'watchmode-us-sub' OR f.updated_at>?)
                          AND (f.source<>'justwatch-nuvio-us-sample' OR f.updated_at>?)
                        GROUP BY m.type,m.tmdb_id
                    """, (catalog_key, catalog_key, json.dumps(sorted(wanted)), time.time()-30*86400, time.time()-14*86400))
                    for typ, native, first, last, in_catalog in rows:
                        if first is not None:
                            valid = first == last and re.fullmatch(r'tt[0-9]{1,12}', first)
                            crosswalk[(typ, native)] = first if valid else None
                        elif in_catalog:
                            native_members.add((typ, native))
            except sqlite3.Error:
                crosswalk.clear()
                native_members.clear()
        key = (kind, int(ident[5:]))
        if key in crosswalk:
            return crosswalk[key]
        native = ident if key in native_members else None
        try:
            path = cache_path(root, metadata_url(CATALOG, kind, ident))
            with path.open('rb') as stream:
                body = stream.read(MAX_BYTES + 1)
            entry = json.loads(body) if len(body) <= MAX_BYTES else None
        except FileNotFoundError:
            return native
        except (OSError, ValueError, RecursionError):
            return None
        meta = entry.get('meta') if isinstance(entry, dict) else None
        if not isinstance(meta, dict) or meta.get('type') != kind:
            return None
        result, explicit = meta.get('id'), meta.get('imdb_id')
        if explicit is not None and not (isinstance(explicit, str) and re.fullmatch(r'tt[0-9]{1,12}', explicit)):
            return None
        if result == ident:
            return explicit or native
        if isinstance(result, str) and re.fullmatch(r'tt[0-9]{1,12}', result) and explicit in (None, result):
            return result
        return None
    try:
        return 200, query_page(request, profile=profile, directory=DATA, resolve=canonical)
    except QueryError as error:
        return error.status, {'error': error.code}


def handler_for(root, history, public_origin=None):
    art = PosterArt()

    class Handler(BaseHTTPRequestHandler):
        def poster(self):
            match = re.fullmatch(r'/poster/(tt[0-9]{7,12})\.jpg', self.path)
            status, url = art.resolve(match[1]) if match else (404, None)
            self.send_response(status)
            if url:
                self.send_header('Location', url)
            self.send_header('Cache-Control', {302: 'public, max-age=86400', 404: 'public, max-age=3600'}.get(status, 'no-store'))
            self.send_header('Connection', 'close')
            self.send_header('Content-Length', '0')
            self.end_headers()

        def do_HEAD(self):
            if self.path.startswith('/poster/'):
                return self.poster()
            self.send_error(404)

        def do_GET(self):
            if self.path.startswith('/poster/'):
                return self.poster()
            try:
                current = json.loads((root / 'config.json').read_text())
                status, value = response_for(root, current, self.path)
            except (DiscoveryError, OSError, sqlite3.Error, ValueError, KeyError):
                status, value = 503, {'error': 'catalog_unavailable'}
            self.reply(status, value)

        def do_POST(self):
            parts = self.path.strip('/').split('/')
            try:
                current = json.loads((root / 'config.json').read_text())
                profile = token_profile(current, parts[0])
            except (OSError, ValueError, KeyError):
                return self.reply(503, {'error': 'catalog_unavailable'})
            if len(parts) != 2 or parts[1] not in ('discover.json','session.json') or profile is None:
                return self.reply(404, {'error': 'not_found'})
            discover = parts[1] == 'discover.json'
            invalid = {'error': 'invalid_query' if discover else 'invalid_session'}
            try:
                size = int(self.headers.get('Content-Length', 0))
                if not 0 < size <= (4 * 1024**2 if discover else 128 * 1024) or self.headers.get_content_type() != 'application/json':
                    return self.reply(400, invalid)
                session = json.loads(self.rfile.read(size))
            except (ValueError, UnicodeError, RecursionError):
                return self.reply(400, invalid)
            if discover:
                status, body = discover_response(root, int(profile), session)
                # Artwork URLs use the operator's origin and contain no profile token.
                host, port = self.server.server_address[:2]
                base = (public_origin or f'http://{host}:{port}') + '/poster/'
                for meta in body.get('metas', []):
                    imdb = meta.get('imdb_id', '')
                    if not meta.get('poster') and TITLE_ID.fullmatch(imdb):
                        meta['poster'] = base + imdb + '.jpg'
                return self.reply(status, body)
            if not valid_session(session):
                return self.reply(400, invalid)
            try:
                refresh(root, history, int(profile), session)
                status, updated = response_for(root, current, '/' + parts[0] + '/manifest.json', session)
            except (OSError, DiscoveryError, sqlite3.Error, ValueError, KeyError):
                return self.reply(503, {'error': 'catalog_unavailable'})
            self.reply(status, updated)

        def reply(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            # The HTTP/1.0 handler closes after sending the body. OkHttp must
            # discard this socket when the next filter request arrives.
            self.send_header('Connection', 'close')
            self.send_header('Cache-Control', 'private, no-store')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass
        def log_message(self, *args):
            pass  # URLs contain profile bearer tokens. Never log them.
    return Handler


def serve(root, history=HISTORY):
    config = json.loads((root / 'config.json').read_text())
    origin = config.get('public_origin', '').rstrip('/')
    if origin:
        parts = urlsplit(origin)
        if (parts.scheme not in ('http', 'https') or not parts.hostname or parts.username is not None
                or parts.password is not None or parts.path or parts.query or parts.fragment):
            raise DiscoveryError('PUBLIC_ORIGIN_INVALID')
    elif config['listen'] in ('', '0.0.0.0'):
        raise DiscoveryError('PUBLIC_ORIGIN_REQUIRED_FOR_WILDCARD')
    server = ThreadingHTTPServer((config['listen'], config['port']), handler_for(root, history, origin))
    from seasonal_jobs import worker
    stop = threading.Event()
    background = threading.Thread(target=worker, args=(root, stop), name='seasonal-catalog', daemon=True)
    background.start()
    try:
        server.serve_forever(poll_interval=1)
    finally:
        stop.set()
        server.server_close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['refresh', 'serve', 'refresh-seasonal', 'seasonal-preview'])
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--history', type=Path, default=HISTORY)
    parser.add_argument('--at', help='Aware ISO-8601 instant for seasonal-preview')
    parser.add_argument('--theme', help='Include public candidate evidence for this slug in seasonal-preview')
    args = parser.parse_args()
    try:
        if args.action == 'refresh':
            refresh(args.root, args.history)
        elif args.action == 'serve':
            serve(args.root, args.history)
        elif args.action == 'refresh-seasonal':
            from seasonal_jobs import prepare
            print(json.dumps(prepare(args.root, all_themes=True)))
        else:
            from seasonal_jobs import preview
            instant = datetime.fromisoformat(args.at) if args.at else datetime.now(timezone.utc)
            if instant.tzinfo is None:
                parser.error('--at requires a UTC offset')
            output = preview(args.root, instant)
            if args.theme:
                from seasonal_calendar import THEMES
                from seasonal_catalog import read_pool
                if args.theme not in THEMES:
                    parser.error('unknown seasonal theme')
                pool = read_pool(args.root, args.theme, instant)
                output['pool'] = {'theme': args.theme, 'generation': pool['generation'], 'complete': pool['complete'],
                                  'editorial': [item['meta']['id'] for item in pool['editorial']],
                                  'dynamic': [{'id': item['meta']['id'], 'refs': item['refs'], 'reason': item['reason']}
                                              for item in pool['dynamic']]}
            print(json.dumps(output))
    except (DiscoveryError, BlockingIOError) as error:
        print(json.dumps({'error': str(error) if isinstance(error, DiscoveryError) else 'REFRESH_ALREADY_RUNNING'}))
        raise SystemExit(1)

if __name__ == '__main__':
    main()
