"""Public movie discovery and worldwide release evidence. This module accepts no profile input."""

from contextlib import closing
import csv
from datetime import date, datetime, timedelta
from html import unescape
from html.parser import HTMLParser
from io import StringIO
import json
import os
from pathlib import Path
import re
import sqlite3
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen

import discovery as d

AWARDS = 'https://raw.githubusercontent.com/DLu/oscar_data/master/oscars.csv'
DAY = 86400
IMDB_TITLES = 'https://v3.sg.media-imdb.com/suggestion/t/'


class ProviderError(Exception):
    """The current supply is unknown. Preserve useful previous results and retry."""


def day_value(value):
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


def release_year(meta):
    match = re.match(r'\d{4}', str(meta.get('releaseInfo', '')))
    return int(match[0]) if match else None


def minutes(value):
    match = re.fullmatch(r'\s*(\d{1,3})\s*(?:min(?:utes)?|m)?\s*', str(value), re.I)
    if match:
        return int(match[1])
    match = re.fullmatch(r'(\d)h\s*(\d{1,2})m', str(value), re.I)
    return int(match[1]) * 60 + int(match[2]) if match else None


def parse_awards(body):
    ceremonies = {}
    for row in csv.DictReader(StringIO(body.decode()), delimiter='\t', quoting=csv.QUOTE_NONE):
        if row.get('CanonicalCategory') == 'BEST PICTURE' and re.fullmatch(r'tt\d+', row.get('FilmId') or ''):
            films = ceremonies.setdefault(row['Ceremony'], {})
            films[row['FilmId']] = row.get('Winner') == 'True'
    if not ceremonies:
        raise ProviderError('invalid Academy results')
    result = {}
    for ceremony, films in ceremonies.items():
        winners = [uid for uid, won in films.items() if won]
        if len(winners) == 1:
            for uid in films:
                if uid != winners[0]:
                    result[uid] = {'ceremony': ceremony, 'winner': winners[0], 'source': AWARDS}
    return result


class MoviePage(HTMLParser):
    """Read typed catalog IDs, pagination and title details from TMDB."""

    def __init__(self, body):
        super().__init__()
        self.ids = {}
        self.keywords = []
        self.current_keyword = False
        self.overview_depth = 0
        self.overview = []
        self.page = None
        self.next_page = None
        self.feed_seen = False
        self.details = False
        self.collection = ''
        self.feed(body.decode())
        self.close()

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        uid = a.get('id', '')
        classes = (a.get('class') or '').split()
        if tag == 'div' and (self.overview_depth or 'overview' in classes):
            self.overview_depth += 1
        if self.overview_depth and tag in ('p', 'br'):
            self.overview.append(' ')
        if tag == 'div' and uid == 'media-list':
            self.feed_seen = True
        if tag == 'div' and re.fullmatch(r'page_\d+', uid or '') and 'page_wrapper' in classes:
            self.page = int(uid[5:])
        if tag == 'section' and 'keywords' in classes:
            self.details = True
        if tag == 'a':
            url = urlsplit(a.get('href') or '')
            match = re.fullmatch(r'/movie/(\d+)(?:-[^/]*)?/?', url.path)
            if match and not url.netloc:
                self.ids['tmdb:' + match[1]] = None
            if re.fullmatch(r'/keyword/\d+-[^/]+/movie', url.path):
                self.current_keyword = True
                self.details = True
            if 'load_more' in classes and a.get('data-next-page', '').isdigit():
                self.next_page = int(a['data-next-page'])

    def handle_endtag(self, tag):
        if self.overview_depth and tag == 'div':
            self.overview_depth -= 1
        if tag == 'a':
            self.current_keyword = False

    def handle_data(self, text):
        if self.overview_depth:
            self.overview.append(text)
        if self.current_keyword and text.strip():
            self.keywords.append(text.strip().casefold())
        # The detail page loads its collection card by the canonical collection ID.
        match = re.search(r"/collection/\{0\}/static_cache/movie_card[^']*'\),\s*'(\d+)'", text)
        if match:
            self.collection = 'tmdb:' + match[1]


def discover_page(body, page):
    html = MoviePage(body)
    if not html.feed_seen or html.page != page or (
        html.next_page is not None and (html.next_page != page + 1 or not html.ids)
    ):
        raise ProviderError('invalid movie discovery page')
    return {'ids': list(html.ids), 'next': html.next_page}


def worldwide_release_rows(body, *, strict_dates=False):
    text = body.decode()
    tables = re.findall(r'<table\b[^>]*class="card releases"[^>]*>(.*?)</table>', text, re.S | re.I)
    if not tables:
        raise ProviderError('unrecognized release table')
    for table in tables:
        country = re.search(r'<h2\b[^>]*\bid="([A-Z]{2})"', table)
        for row in re.findall(r'<tr\b[^>]*>(.*?)</tr>', table, re.S | re.I):
            cells = [unescape(re.sub(r'<[^>]+>', '', value)).strip() for value in
                     re.findall(r'<td\b[^>]*>(.*?)</td>', row, re.S | re.I)]
            if len(cells) < 3 or cells[2] not in ('Premiere', 'Theatrical (limited)', 'Theatrical',
                                                 'Digital', 'Physical', 'TV'):
                continue
            try:
                released = datetime.strptime(cells[0], '%m/%d/%Y').date().isoformat()
            except ValueError:
                if strict_dates and cells[0]:
                    raise ProviderError('unrecognized release date') from None
                continue
            yield {'date': released, 'type': cells[2], 'country': country[1] if country else '',
                   'certification': cells[1]}


def first_worldwide_release(body):
    first = min(worldwide_release_rows(body), key=lambda row: row['date'], default=None)
    return {key: first[key] for key in ('date', 'type', 'country')} if first else None


def detail_tags(body):
    page = MoviePage(body)
    if not page.details and b'facts left_column' not in body and b'collection_waypoint' not in body and b'ott_offers_window' not in body:
        raise ProviderError('unrecognized title detail page')
    duration = re.search(rb'<span[^>]+class="runtime"[^>]*>(.*?)</span>', body, re.S)
    runtime = minutes(duration[1].decode().strip().replace(' ', '')) if duration else None
    return {'keywords': page.keywords, 'franchise': page.collection, 'edition_minutes': runtime,
            'overview': ' '.join(''.join(page.overview).split())}


class PublicProvider:
    def __init__(self, root):
        self.root = root
        self.cache = root / 'seasonal'
        self.last_request = 0.0

    def request(self, url, parse, ttl, payload=None, *, cache_version=None):
        host = urlsplit(url).hostname
        if host not in d.ALLOWED and url != AWARDS and not url.startswith(IMDB_TITLES):
            raise ProviderError('invalid provider')
        encoded = urlencode(payload).encode() if payload else None
        key = url + ('?' + encoded.decode() if encoded else '')
        if cache_version:
            key += '#' + cache_version
        path = d.cache_path(self.cache, key)
        now = time.time()
        if path.exists() and now - path.stat().st_mtime < ttl:
            return json.loads(path.read_text())
        for marker in (path.parent / (host + '.cooldown'), path.with_suffix('.failure')):
            if marker.exists():
                value = json.loads(marker.read_text())
                if value['until'] > now:
                    raise d.DiscoveryError('UPSTREAM_HTTP_' + str(value['code']))
        time.sleep(max(0, self.last_request + 0.12 - time.monotonic()))
        self.last_request = time.monotonic()
        req = Request(url, data=encoded, headers={'User-Agent': 'mkbtv-discovery/1'})
        try:
            with urlopen(req, timeout=6) as response:
                if urlsplit(response.url).hostname != host and urlsplit(response.url).hostname not in d.ALLOWED:
                    raise ProviderError('provider redirect')
                body = response.read(d.MAX_BYTES + 1)
            if len(body) > d.MAX_BYTES:
                raise ProviderError('oversize response')
            value = parse(body)
        except HTTPError as exc:
            if exc.code == 404:
                d.atomic_json(path.with_suffix('.failure'), {'code': 404, 'until': now + min(ttl, 21600)})
            elif exc.code in (403, 412, 429) or exc.code >= 500:
                retry = exc.headers.get('Retry-After', '')
                delay = min(86400, max(60, int(retry))) if retry.isdigit() else 300
                d.atomic_json(path.parent / (host + '.cooldown'), {'code': exc.code, 'until': now + delay})
            raise d.DiscoveryError('UPSTREAM_HTTP_' + str(exc.code)) from None
        except (URLError, TimeoutError, ValueError) as exc:
            raise ProviderError('unavailable provider') from exc
        d.atomic_json(path, value)
        return value

    def load(self, root, url, ttl=DAY):
        parser = d.parse_tmdb_recommendations if url.startswith(d.TMDB + '/') else json.loads
        return self.request(url, parser, min(ttl, DAY))

    def card(self, kind, uid):
        return d.metadata(self.root, kind, uid, load=self.load)

    def related(self, seed, source):
        method = d.tmdb_related if source == 'tmdb' else d.related
        try:
            pairs = method(self.root, seed, load=self.load)
        except d.DiscoveryError as error:
            if str(error) == 'UPSTREAM_HTTP_404':
                return []
            raise
        return [uid for kind, uid in pairs if kind == 'movie']

    def search(self, phrase):
        url = d.CATALOG + '/catalog/movie/popular-movies/search=' + quote(phrase, safe='') + '.json'
        rows = self.request(url, json.loads, DAY // 6).get('metas')
        if not isinstance(rows, list):
            raise ProviderError('invalid title search')
        return [m['id'] for m in rows if d.safe_meta(m) and m['type'] == 'movie']

    def keyword(self, phrase):
        url = d.TMDB + '/search/keyword?' + urlencode({'query': phrase, 'language': 'en-US'})

        def parse(body):
            html = body.decode()
            if 'search_results' not in html:
                raise ProviderError('invalid keyword search')
            links = re.findall(r'<a\b[^>]*href="/keyword/(\d+)-[^"]+"[^>]*>([^<]+)</a>', html)
            return next((uid for uid, name in links if unescape(name).strip().casefold() == phrase), '')

        return self.request(url, parse, 7 * DAY)

    def first_keyword(self, phrases):
        return next((uid for phrase in phrases if (uid := self.keyword(phrase))), '')

    def keyword_movies(self, theme, seed, page):
        params = {'language': 'en-US', 'include_adult': 'false', 'page': page, 'sort_by': 'popularity.desc'}
        if seed is None:
            keyword = self.first_keyword(theme['signals'])
        else:
            # Combine an original's narrative premise with the independently
            # annotated theme. The broader keyword lookup is a separate source.
            keyword = self.first_keyword(t for t in seed['traits'] if t not in theme['signals'])
            constrained = {'made-in-denmark': ('with_origin_country', 'DK'),
                           'under-90-minutes': ('with_runtime.lte', 89), 'the-extra-hour': ('with_runtime.gte', 150)}
            if theme['slug'] in constrained:
                key, value = constrained[theme['slug']]
                params[key] = value
            elif keyword:
                signal = self.first_keyword(theme['signals'])
                keyword = signal + ',' + keyword if signal and signal != keyword else ''
        if not keyword:
            return {'ids': [], 'next': None}
        params['with_keywords'] = keyword
        return self.request(d.TMDB + '/discover/movie/items', lambda b: discover_page(b, page), DAY, params)

    def releases(self, start, end, page, *, theme=None, upcoming=False):
        params = {'language': 'en-US', 'include_adult': 'false', 'page': page,
                  'primary_release_date.gte': start, 'primary_release_date.lte': end,
                  'sort_by': 'popularity.desc'}
        if theme:
            keyword = self.first_keyword(theme['signals'])
            if not keyword:
                return {'ids': [], 'next': None}
            order = 'primary_release_date.asc' if upcoming else 'primary_release_date.desc'
            params.update(with_keywords=keyword, sort_by=order)
        return self.request(d.TMDB + '/discover/movie/items', lambda b: discover_page(b, page), DAY, params)

    def awards(self):
        return self.request(AWARDS, parse_awards, DAY)

    def details(self, meta):
        uid = meta.get('imdb_id') or meta['id']
        kind = meta['type']
        facts = {'keywords': [], 'franchise': '', 'countries': [], 'texts': {}}
        catalog_id = meta.get('moviedb_id')
        if meta.get('description'):
            facts['texts']['catalog synopsis'] = meta['description']
        if uid.startswith('tt'):
            # Credits and country come from the original structured record. A second
            # copy of the same synopsis will not create a second relationship.
            url = d.metadata_url(d.CINEMETA, kind, uid)
            try:
                raw = self.load(self.root, url).get('meta') or {}
            except d.DiscoveryError as exc:
                if str(exc) not in ('UPSTREAM_HTTP_404', 'UPSTREAM_HTTP_412'):
                    raise
                raw = {}
            if raw.get('id') == uid and raw.get('type') == kind:
                facts['countries'] = [v.strip() for v in str(raw.get('country', '')).split(',')]
                if raw.get('description'):
                    facts['texts']['Cinemeta synopsis'] = raw['description']
        if catalog_id and kind == 'movie':
            url = d.TMDB + '/movie/' + catalog_id + '?language=en-US'
            tags = self.request(url, detail_tags, DAY, cache_version='overview-v1')
            facts.update({key: tags[key] for key in ('keywords', 'franchise', 'edition_minutes')})
            if tags['overview']:
                facts['texts']['TMDB overview'] = tags['overview']
        return facts

    def release_evidence(self, meta, today):
        original = day_value(meta.get('released'))
        year = release_year(meta)
        earliest = {'date': str(original), 'source': 'Catalog release date'} if original else None
        prior_year = bool(year and year < today.year)
        # An established title needs no extra upstream call. A recent, upcoming
        # or undated title may already have premiered elsewhere before its card date.
        cutoff = today - timedelta(days=90)
        needs_dates = original >= cutoff if original else not year or year >= cutoff.year
        number = meta.get('moviedb_id')
        if needs_dates and number and meta['type'] == 'movie':
            url = f'{d.TMDB}/movie/{number}/releases?language=en-US'
            try:
                first = self.request(url, first_worldwide_release, DAY // 4, cache_version='worldwide-first-v1')
            except (d.DiscoveryError, ProviderError) as exc:
                if str(exc) != 'UPSTREAM_HTTP_404':
                    if original and original <= today:
                        # The card proves release. Retry the international date
                        # comparison next pass before granting a freshness boost.
                        return {'date': '', 'catalog_date': str(original), 'source': 'Catalog release date'}
                    if not (prior_year and not original):
                        raise
                first = None
            if first and (not earliest or first['date'] <= earliest['date']):
                earliest = dict(first, source=url)
        if earliest:
            released = day_value(earliest['date'])
            if released > today:
                return None
            if not year or released.year <= year:
                return earliest
            # A later regional release or reissue cannot make an older title fresh.
        if prior_year:
            return {'date': '', 'year': year, 'source': 'Catalog release year'}
        return None

    def subtype(self, uid):
        path = Path(os.environ.get('MKBTV_CATALOG_DIR', self.root.parent / 'catalog')) / 'catalog.sqlite3'
        if not uid.startswith('tt'):
            return ''
        if path.is_file():
            with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as db:
                row = db.execute('SELECT subtype,adult FROM title WHERE id=?', (int(uid[2:]),)).fetchone()
            if row:
                return 'adult' if row[1] else row[0]
        # The public exact-title suggestion includes qid (movie, tvMovie, tvEpisode,
        # short, etc). Ignore promoted and fuzzy results in the same response.
        try:
            data = self.request(IMDB_TITLES + uid + '.json', json.loads, 7 * DAY)
        except d.DiscoveryError as exc:
            if str(exc) != 'UPSTREAM_HTTP_404':
                raise
            return ''
        return next((item.get('qid', '') for item in data.get('d', []) if item.get('id') == uid), '')
