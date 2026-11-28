"""Build private For You evidence from whole-title history and shared public lookups."""
from collections import Counter, defaultdict
from contextlib import closing
from datetime import datetime, timezone
from functools import lru_cache
import json
import os
from pathlib import Path
import re
import sqlite3
import time

import discovery as d
from for_you_rank import interest_shares
from seasonal_provider import DAY, MoviePage, ProviderError, PublicProvider

SEEDS_PER_BATCH = 4
CARDS_PER_BATCH = 12
PAGES_PER_BATCH = 2
PAGES = 4
# Public parsers may encounter a JSON detail object, list or nested field with
# an unexpected shape. Handle these errors only around the individual lookup.
LOOKUP_ERRORS = (d.DiscoveryError, ProviderError, TimeoutError, TypeError, ValueError,
                 AttributeError, KeyError, IndexError)
GENRE_IDS = {'Action': 28, 'Adventure': 12, 'Animation': 16, 'Comedy': 35, 'Crime': 80,
             'Documentary': 99, 'Drama': 18, 'Family': 10751, 'Fantasy': 14, 'History': 36,
             'Horror': 27, 'Music': 10402, 'Mystery': 9648, 'Romance': 10749, 'Science Fiction': 878,
             'Thriller': 53, 'War': 10752, 'Western': 37, 'Action & Adventure': 10759,
             'Sci-Fi & Fantasy': 10765, 'War & Politics': 10768, 'Kids': 10762, 'News': 10763,
             'Reality': 10764, 'Soap': 10766, 'Talk': 10767}
ID = re.compile(r'(movie|series)/(tt[0-9]{1,12}|tmdb:[1-9][0-9]{0,11})\Z')


def key(kind, uid):
    value = f'{kind}/{uid}'
    return value if ID.fullmatch(value) else ''


def cycle(items, cursor, name, limit):
    if not items:
        return []
    offset = cursor.get(name, 0) % len(items)
    size = min(limit, len(items))
    cursor[name] = (offset + size) % len(items)
    return [items[(offset + i) % len(items)] for i in range(size)]


@lru_cache(maxsize=1)
def country_codes():
    # The Cinemeta country field uses both display names and abbreviations.
    names = {'US': 'United States', 'GB': 'United Kingdom', 'KR': 'South Korea',
             'KP': 'North Korea', 'DK': 'Denmark', 'SE': 'Sweden', 'NO': 'Norway',
             'FI': 'Finland', 'DE': 'Germany', 'FR': 'France', 'JP': 'Japan', 'CN': 'China',
             'IN': 'India', 'CA': 'Canada', 'ES': 'Spain', 'AU': 'Australia', 'IT': 'Italy',
             'BR': 'Brazil', 'MX': 'Mexico', 'HK': 'Hong Kong', 'TW': 'Taiwan', 'IR': 'Iran'}
    aliases = {'usa': 'US', 'u.s.a.': 'US', 'uk': 'GB', 'republic of korea': 'KR'}
    try:
        records = json.loads(Path('/usr/share/iso-codes/json/iso_3166-1.json').read_text())['3166-1']
    except (OSError, ValueError, KeyError):
        records = []
    for record in records:
        code = record['alpha_2']
        names.setdefault(code, record.get('common_name', record['name']))
        for field in ('name', 'official_name', 'common_name', 'alpha_3'):
            if record.get(field):
                aliases[record[field].casefold()] = code
    for code, name in names.items():
        aliases[code.lower()] = aliases[name.casefold()] = code
    return aliases, names


def countries(values):
    if not isinstance(values, (list, tuple, set)):
        return []
    aliases, names = country_codes()
    clean = set()
    for value in values:
        if isinstance(value, str) and 0 < len(value.strip()) <= 70:
            code = aliases.get(value.strip().casefold())
            if code:
                clean.add(names[code])
    return sorted(clean)


class CatalogPage(MoviePage):
    """Use TMDB's typed links and pagination markers for worldwide movie and TV pages."""
    def __init__(self, body, kind):
        self.pairs = {}
        self.section = 'movie' if kind == 'movie' else 'tv'
        self.page_depth = 0
        super().__init__(body)

    def handle_starttag(self, tag, attrs):
        super().handle_starttag(tag, attrs)
        values = dict(attrs)
        if tag == 'div' and (self.page_depth or
                             re.fullmatch(r'page_\d+', values.get('id') or '')
                             and 'page_wrapper' in (values.get('class') or '').split()):
            self.page_depth += 1
        if tag == 'a' and self.page_depth:
            match = re.fullmatch(r'/' + self.section + r'/([1-9][0-9]{0,11})(?:-[^/?#]*)?/?',
                                 values.get('href') or '')
            if match:
                self.pairs['tmdb:' + match[1]] = None

    def handle_endtag(self, tag):
        super().handle_endtag(tag)
        if tag == 'div' and self.page_depth:
            self.page_depth -= 1


class Provider:
    """No account or profile attributes cross this boundary."""
    def __init__(self, root, load=None):
        self.root = root
        self.load = load or d.fetch
        self.world = PublicProvider(root)

    def card(self, kind, uid):
        origins = []
        def load(root, url, ttl):
            data = self.load(root, url, ttl)
            if not isinstance(data, dict) or not isinstance(data.get('meta', {}), dict):
                raise ProviderError('invalid public metadata')
            raw = data.get('meta') or {}
            # metadata() checks the requested identity before we use these facts.
            production = raw.get('production_countries') or []
            if isinstance(production, list):
                origins.extend(row.get('iso_3166_1') or row.get('name') for row in production if isinstance(row, dict))
            if url.startswith(d.CINEMETA + '/') and raw.get('type') == kind and raw.get('id') == uid:
                value = raw.get('country')
                if isinstance(value, str):
                    origins.extend(value.split(','))
            return data
        meta = d.metadata(self.root, kind, uid, load=load)
        if meta and not origins and uid.startswith('tmdb:'):
            imdb = meta.get('imdb_id') or meta['id']
            if imdb.startswith('tt'):
                # A TMDB request may yield an IMDb preview with no country information.
                try:
                    item = self.load(self.root, d.metadata_url(d.CINEMETA, kind, imdb), 7 * DAY)
                    raw = item.get('meta') if isinstance(item, dict) else None
                except (d.DiscoveryError, ProviderError):
                    raw = None
                if (isinstance(raw, dict) and raw.get('id') == imdb and raw.get('type') == kind
                        and isinstance(raw.get('country'), str)):
                    origins.extend(raw['country'].split(','))
        return meta, countries(origins) if meta else []

    def related(self, seed, basis):
        method = d.tmdb_related if basis == 'tmdb' else d.related
        return method(self.root, seed)

    def catalog(self, kind, genre, country, page):
        tv_genre = d.SERIES_GENRES.get(genre, genre) if kind == 'series' else genre
        if genre and not country:
            chart = 'popular-movies' if kind == 'movie' else 'popular-series'
            return d.catalog(self.root, kind, chart, tv_genre, skip=(page - 1) * 20)
        # Worldwide catalog and verified country/genre interests. No release region or language filter.
        section = 'movie' if kind == 'movie' else 'tv'
        date_field = 'primary_release_date' if kind == 'movie' else 'first_air_date'
        today = datetime.now(timezone.utc).date().isoformat()
        params = {'page': page, 'sort_by': date_field + '.desc', 'include_adult': 'false',
                  date_field + '.lte': today, 'vote_count.gte': '10'}
        if country:
            code = country_codes()[0].get(country.casefold())
            if not code or tv_genre not in GENRE_IDS:
                return []
            params.update(with_origin_country=code, with_genres=GENRE_IDS[tv_genre])
        def parse(body):
            html = CatalogPage(body, kind)
            if not html.feed_seen or html.page != page:
                raise ProviderError('invalid worldwide discovery page')
            return list(html.pairs)
        ids = self.world.request(f'{d.TMDB}/discover/{section}/items', parse, DAY, params)
        return [(kind, uid) for uid in ids]


class Cache:
    """Only the background worker writes. No HTTP requests run inside write transactions."""
    def __init__(self, root, write):
        path = root / 'for-you' / 'public.sqlite3'
        self.read_only = not write
        if write:
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            self.db = sqlite3.connect(path, timeout=4)
            self.db.execute('PRAGMA journal_mode=WAL')
            self.db.execute('CREATE TABLE IF NOT EXISTS lookup (basis TEXT, lookup TEXT, value TEXT, '
                            'due INTEGER, PRIMARY KEY(basis,lookup)) WITHOUT ROWID')
        else:
            self.db = None
            if path.is_file():
                try:
                    self.db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=0.25)
                except sqlite3.Error:
                    pass

    def close(self):
        if self.db is not None:
            self.db.close()

    def read(self, basis, keys):
        if not keys or self.db is None:
            return {}
        try:
            rows = self.db.execute('SELECT lookup,value FROM lookup WHERE basis=? '
                                   'AND lookup IN (SELECT value FROM json_each(?)) AND value IS NOT NULL',
                                   (basis, json.dumps(list(keys))))
            return {k: json.loads(value) for k, value in rows}
        except (sqlite3.Error, ValueError):
            if not self.read_only:
                raise
            # A serving request can race the initial CREATE or a catalog rebuild.
            self.close()
            self.db = None
            return {}

    def due(self, basis, identifiers):
        rows = self.db.execute('SELECT lookup FROM lookup WHERE basis=? '
                               'AND lookup IN (SELECT value FROM json_each(?)) AND due>?',
                               (basis, json.dumps(list(identifiers)), time.time()))
        return set(identifiers).difference(row[0] for row in rows)

    def refresh(self, basis, lookup, retrieve, ttl=7 * DAY):
        now = int(time.time())
        row = self.db.execute('SELECT due,value FROM lookup WHERE basis=? AND lookup=?', (basis, lookup)).fetchone()
        if row and row[0] > now:
            return
        try:
            value = retrieve()
            # Empty provider lists and unresolvable records are genuine negative lookups.
            due = now + (ttl if value else DAY)
        except LOOKUP_ERRORS as error:
            due = now + (DAY if str(error) == 'UPSTREAM_HTTP_404' else 900)
            value = json.loads(row[1]) if row and row[1] else None
        identifiers = {lookup}
        if basis == 'card' and row and row[1]:
            # A missing/unrecognized response is inconclusive. Keep the last
            # verified title, including verified country facts during a partial outage.
            old_card, old_origins = json.loads(row[1])
            if not value:
                value = [old_card, old_origins]
            elif not value[1]:
                value[1] = old_origins
        if basis == 'card' and value:
            identifiers.update(key(kind, uid) for kind, uid in d.title_keys(value[0]))
        payload = json.dumps(value) if value else None
        with self.db:
            if basis == 'card' and value:
                native = next((s for s in identifiers if '/tmdb:' in s), None)
                imdb = next((s for s in identifiers if '/tt' in s), None)
                if native and imdb:
                    self.db.execute("INSERT INTO lookup VALUES ('alias',?,?,0) ON CONFLICT(basis,lookup) "
                                    "DO UPDATE SET value=CASE WHEN value=excluded.value THEN value ELSE 'null' END",
                                    (native, json.dumps(imdb)))
            self.db.executemany('INSERT OR REPLACE INTO lookup VALUES (?,?,?,?)',
                                ((basis, uid, payload, due) for uid in sorted(identifiers) if uid))


class Titles:
    """Resolve only typed identifier evidence. Keep public previews separate from private source evidence."""
    def __init__(self):
        self.links = {}
        self.cards = {}
        self.countries = defaultdict(set)

    def resolve(self, ident):
        return self.links.get(ident, ident) or ident

    def link(self, native, imdb):
        # Conflicting providers cannot join two distinct IMDb works.
        self.links[native] = imdb if self.links.get(native, imdb) == imdb else None

    def add(self, meta, origins=()):
        card = d.safe_meta(meta)
        if not card:
            return
        kind = card['type']
        ident = key(kind, card['id'])
        if not ident:
            return
        imdb = card['id'] if card['id'].startswith('tt') else card.get('imdb_id')
        if imdb and card.get('imdb_id', imdb) != imdb:
            return
        native = card['id'] if card['id'].startswith('tmdb:') else 'tmdb:' + card.get('moviedb_id', '')
        if imdb and key(kind, native):
            self.link(key(kind, native), key(kind, imdb))
        canonical = key(kind, imdb) if imdb else ident
        genres = card.pop('genres', None)
        if isinstance(genres, list):
            valid = [g for g in genres if isinstance(g, str) and g]
            if valid:
                card['genres'] = valid
        if canonical != ident:
            card.update(id=imdb, moviedb_id=native[5:])
        old = self.cards.get(canonical, {})
        self.cards[canonical] = {**old, **card}
        self.countries[canonical].update(countries(origins))

    def card(self, ident):
        canonical = self.resolve(ident)
        return self.cards.get(canonical) or self.cards.get(ident)

    def collapse(self):
        conflicts = {native for native, imdb in self.links.items() if not imdb}
        if conflicts:
            for card in self.cards.values():
                if key(card['type'], 'tmdb:' + card.get('moviedb_id', '')) in conflicts:
                    card.pop('moviedb_id')
            for native in conflicts:
                self.cards.pop(native, None)
        for native, imdb in self.links.items():
            if not imdb:
                continue
            if native in self.cards:
                card = dict(self.cards.pop(native), id=imdb.split('/')[1], imdb_id=imdb.split('/')[1])
                self.cards[imdb] = {**card, **self.cards.get(imdb, {})}
                self.countries[imdb].update(self.countries.pop(native, ()))
            if imdb in self.cards:
                self.cards[imdb].setdefault('moviedb_id', native.split(':')[1])


def local_titles(root, titles, ids):
    """Bulk indexed lookup. An unavailable optional index leaves the public-provider route usable."""
    directory = Path(os.environ.get('MKBTV_CATALOG_DIR', root.parent / 'catalog'))
    native = {k for k in ids if '/tmdb:' in k}
    feeds = directory / 'feeds.sqlite3'
    if native and feeds.is_file():
        try:
            with closing(sqlite3.connect(feeds.resolve().as_uri() + '?mode=ro', uri=True)) as db:
                for kind in ('movie', 'series'):
                    numbers = [int(k.split(':')[1]) for k in native if k.startswith(kind + '/')]
                    rows = db.execute("""
                      SELECT m.tmdb_id,min(m.imdb_id),max(m.imdb_id) FROM member m JOIN feed f ON m.key=f.key
                      WHERE m.type=? AND m.tmdb_id IN (SELECT value FROM json_each(?)) AND m.imdb_id IS NOT NULL
                      AND (m.tmdb_type IS NULL OR m.tmdb_type=?)
                      AND (f.source<>'watchmode-us-sub' OR f.updated_at>?)
                      AND (f.source<>'justwatch-nuvio-us-sample' OR f.updated_at>?) GROUP BY m.tmdb_id
                    """, (kind, json.dumps(numbers), 'tv' if kind == 'series' else kind,
                          time.time() - 30 * DAY, time.time() - 14 * DAY))
                    for number, first, last in rows:
                        native_key = key(kind, f'tmdb:{number}')
                        if first != last:
                            titles.links[native_key] = None
                        elif key(kind, first) and first.startswith('tt'):
                            titles.link(native_key, key(kind, first))
        except sqlite3.Error:
            pass
    index = directory / 'catalog.sqlite3'
    missing = {titles.resolve(k) for k in ids if not titles.card(k) or not titles.card(k).get('genres')}
    wanted = {k for k in missing if '/tt' in k}
    if wanted and index.is_file():
        try:
            with closing(sqlite3.connect(index.resolve().as_uri() + '?mode=ro', uri=True)) as db:
                numbers = {int(k.split('/tt')[1]) for k in wanted}
                rows = db.execute('SELECT imdb_id,type,name,year,genres FROM title WHERE adult=0 '
                                  'AND id IN (SELECT value FROM json_each(?))', (json.dumps(sorted(numbers)),))
                for uid, kind, name, year, genres in rows:
                    if key(kind, uid) in wanted:
                        meta = {'id': uid, 'type': kind, 'name': name, 'genres': genres.split(',') if genres else []}
                        if year:
                            meta['releaseInfo'] = str(year)
                        titles.add(meta)
        except sqlite3.Error:
            pass
    titles.collapse()


def card_record(provider, ident):
    meta, origins = provider.card(*ident.split('/'))
    card = d.safe_meta(meta)
    if card and ident in {key(k, uid) for k, uid in d.title_keys(card)}:
        return [card, countries(origins)]
    return None


def load_cards(cache, titles, ids, records=None):
    for card, origins in (cache.read('card', ids) if records is None else records).values():
        titles.add(card, origins)
    native = set(titles.links) | {ident for ident in ids if '/tmdb:' in ident}
    for ident, imdb in cache.read('alias', native).items():
        titles.link(ident, imdb)
    titles.collapse()


def warm_from_discovery(root, titles, ids):
    # At migration or on a live completion the recent-title builder can already
    # have written a verified public response. Never refresh it here.
    def cached(root, url, ttl):
        try:
            with d.cache_path(root, url).open('rb') as stream:
                data = stream.read(d.MAX_BYTES + 1)
            if len(data) <= d.MAX_BYTES:
                entry = json.loads(data)
                if isinstance(entry, dict) and isinstance(entry.get('meta'), dict):
                    return entry
        except (OSError, ValueError, RecursionError):
            pass
        raise d.DiscoveryError('UPSTREAM_HTTP_404')
    if (root / 'cache').is_dir():
        provider = Provider(root, load=cached)
        for ident in ids:
            try:
                record = card_record(provider, ident)
            except LOOKUP_ERRORS:
                continue
            if record:
                titles.add(*record)
        titles.collapse()


def profile_evidence(titles, watches, excluded_ids, unwatched):
    watched = Counter()
    for ident, at in watches.items():
        uid = titles.resolve(ident)
        watched[uid] = max(watched[uid], at)
    for ident, at in unwatched.items():
        uid = titles.resolve(ident)
        if uid in watched and at >= watched[uid]:
            del watched[uid]
    omitted = {titles.resolve(key(kind, uid)) for uid in excluded_ids for kind in d.TYPE}
    seeds = sorted(set(watched) - omitted)
    genres_by_seed = {s: d.canonical_genres(titles.card(s)) for s in seeds if titles.card(s)}
    occurrences = Counter((genre, country) for s, genres in genres_by_seed.items()
                          for genre in genres for country in titles.countries[s])
    combos = {pair for pair, count in occurrences.items() if count >= 2}
    votes, interests = Counter(), Counter()
    for seed, genres in genres_by_seed.items():
        votes.update({genre: 1 / len(genres) for genre in genres})
        tags = genres | {country + ' / ' + genre for genre in genres for country in titles.countries[seed]
                         if (genre, country) in combos}
        interests.update(interest_shares(tags))
    total = sum(interests.values())
    return watched, seeds, omitted, votes, combos, {g: v / total for g, v in sorted(interests.items())}


def catalog_specs(votes, combos):
    subjects = [(genre, '') for genre in sorted(votes, key=lambda g: (-votes[g], g))] or [
        ('Drama', ''), ('Comedy', ''), ('Action', '')]
    subjects = sorted(combos) + subjects + [('', '')]
    return [(kind, genre, country, page) for page in range(1, PAGES + 1)
            for genre, country in subjects for kind in ('movie', 'series')
            if not genre or (genre in d.MOVIE_GENRES if kind == 'movie'
                             else d.SERIES_GENRES.get(genre, genre) in d.TV_GENRES)]


def related_keys(seed, card):
    kind, uid = seed.split('/')
    imdb = uid if uid.startswith('tt') else (card or {}).get('imdb_id')
    native = uid if uid.startswith('tmdb:') else 'tmdb:' + (card or {}).get('moviedb_id', '')
    return {basis: value for basis, ident in [('simkl', imdb), ('tmdb', native)] if (value := key(kind, ident))}


def page_entries(items):
    if not isinstance(items, (list, tuple)):
        raise ProviderError('invalid public catalog list')
    result = []
    for item in items[:60]:
        if isinstance(item, dict):
            card = d.safe_meta(item)
            if card and key(card['type'], card['id']):
                result.append(card)
        elif isinstance(item, (tuple, list)) and len(item) == 2 and key(*item):
            result.append(key(*item))
    if items and not result:
        raise ProviderError('unrecognized public catalog entries')
    return result


def related_entries(items):
    if not isinstance(items, (list, tuple)):
        raise ProviderError('invalid public related list')
    result = [key(*row) for row in items[:30] if isinstance(row, (tuple, list)) and len(row) == 2 and key(*row)]
    if items and not result:
        raise ProviderError('unrecognized public related entries')
    return list(dict.fromkeys(result))


def nominations(cache, titles, seeds, specs):
    hits = defaultdict(list)
    for basis in ('tmdb', 'simkl'):
        source_keys = {k: seed for seed in seeds if (k := related_keys(seed, titles.card(seed)).get(basis))}
        for lookup, items in cache.read(basis, source_keys).items():
            for rank, ident in enumerate(items, 1):
                hits[ident].append({'seed': source_keys[lookup], 'basis': basis, 'rank': rank})
    slots = {json.dumps(spec): spec for spec in specs}
    for slot, items in cache.read('catalog', slots).items():
        kind, genre, country, page = slots[slot]
        basis = ' '.join(filter(None, ['Worldwide' if country or not genre else 'Popular', country, genre, kind]))
        for rank, item in enumerate(items, (page - 1) * 20 + 1):
            if isinstance(item, dict):
                titles.add(item)
                ident = key(item['type'], item['id'])
            else:
                ident = item
            hits[ident].append({'seed': None, 'basis': basis, 'rank': rank})
    return hits


def enrich_candidates(cache, provider, hits, cursor):
    # Reserve work for both verified relationships and catalog country evidence.
    # Completed and negatively cached lookups never consume the batch allowance.
    due = cache.due('card', hits)
    direct = sorted((k for k in due if any(r['seed'] for r in hits[k])),
                    key=lambda k: (min(r['rank'] for r in hits[k] if r['seed']), k))
    charts = sorted(due.difference(direct), key=lambda k: (min(r['rank'] for r in hits[k]), k))
    direct_count = min(len(direct), max(CARDS_PER_BATCH * 2 // 3, CARDS_PER_BATCH - len(charts)))
    batch = cycle(direct, cursor, 'related_cards', direct_count)
    batch += cycle(charts, cursor, 'catalog_cards', CARDS_PER_BATCH - len(batch))
    for ident in batch:
        cache.refresh('card', ident, lambda: card_record(provider, ident))


def add_recent_relations(hits, titles, seeds, related_rows):
    eligible = set(seeds)
    sources = {d.SIMKL_BASIS: 'simkl', d.TMDB_BASIS: 'tmdb'}
    for row in related_rows:
        seed = row.get('seed')
        basis = sources.get(row.get('basis'))
        if not basis or not isinstance(seed, (list, tuple)) or len(seed) != 2:
            continue
        seed = titles.resolve(key(*seed))
        if seed not in eligible or not row.get('id', '').startswith('because-watched'):
            continue
        for rank, meta in enumerate(row.get('metas', []), 1):
            card = d.safe_meta(meta)
            if card and (ident := key(card['type'], card['id'])):
                titles.add(card)
                hits[ident].append({'seed': seed, 'basis': basis, 'rank': rank})


def assemble(titles, watches, excluded_ids, hits, fallback, progress, cursor, unwatched):
    watched, seeds, omitted, votes, combos, interests = profile_evidence(titles, watches, excluded_ids, unwatched)
    all_hits = defaultdict(dict)
    for ident, reasons in hits.items():
        for reason in reasons:
            seed = titles.resolve(reason['seed']) if reason['seed'] else None
            if seed is None or (seed in watched and seed not in omitted and seed != titles.resolve(ident)):
                evidence = all_hits[titles.resolve(ident)]
                source = (seed, reason['basis'])
                if source not in evidence or reason['rank'] < evidence[source]['rank']:
                    evidence[source] = dict(reason, seed=seed)
    reserve = {titles.resolve(key(m['type'], m['id'])) for m in fallback}
    candidates = []
    max_votes = max(votes.values(), default=1)
    for ident in sorted((all_hits.keys() | reserve | set(seeds)) - omitted):
        card = titles.card(ident)
        if not card:
            continue
        genres = d.canonical_genres(card).intersection(votes)
        signals = sorted(genres)
        signals += sorted(c + ' / ' + g for g in genres for c in titles.countries[ident] if (g, c) in combos)
        reasons = list(all_hits.get(ident, {}).values())
        direct = {r['seed'] for r in reasons if r['seed'] and r['seed'] != ident}
        if not (signals or direct or ident in watched or not interests):
            continue
        strength = 0.05
        if genres:
            strength = 0.4 + 0.4 * max(votes[g] for g in genres) / max_votes
            if len(signals) > len(genres):
                strength += 0.55
        if direct:
            rank = min(r['rank'] for r in reasons if r['seed'] in direct)
            strength += 0.9 + 0.7 / rank + 0.45 * min(4, len(direct) - 1)
        if not reasons:
            basis = 'Watched title' if ident in watched else 'Catalog genre match' if genres else 'Catalog fallback'
            reasons = [{'seed': ident if ident in watched else None, 'basis': basis, 'rank': 1}]
        candidates.append({'meta': card, 'interests': signals, 'strength': strength, 'sources': reasons})
    aliases = {**{k: k for k in (set(titles.cards) | set(watches) | set(progress))},
               **{k: v for k, v in titles.links.items() if v}}
    active = {titles.resolve(k) for k in progress}
    return {'candidates': candidates, 'interests': interests, 'genre_votes': dict(votes),
            'watched': {k: watched[v] for k, v in aliases.items() if v in watched} | dict(watched),
            'progress': sorted(active | {k for k, v in aliases.items() if v in active}),
            'aliases': aliases, 'ambiguous_aliases': sorted(k for k, v in titles.links.items() if not v),
            'cursor': cursor, 'distinct': len(watched),
            'matched': sum(bool(titles.card(s) and d.canonical_genres(titles.card(s))) for s in seeds)}


def prepare_pool(root, document, excluded_ids=(), *, cursor=None, background=False, known_metas=(),
                 fallback=(), provider=None, related_rows=(), unwatched=None):
    """The caller verifies and merges its export and owns the private, restartable cursor."""
    watches = {key(kind, uid): at for (kind, uid), at in d.watched_unique(document).items() if key(kind, uid)}
    progress = {key('series', row['content_id']) for row in document.get('progress', [])
                if row.get('content_type') in ('series', 'tv') and key('series', row.get('content_id'))
                and isinstance(row.get('position'), (int, float)) and row['position'] > 0}
    unwatched = {k: at for k, at in (unwatched or {}).items()
                 if ID.fullmatch(k) and k.startswith('movie/') and type(at) is int and at > 0}
    cursor = dict(cursor or {})
    titles = Titles()
    fallback = [card for item in fallback if (card := d.safe_meta(item))]
    for meta in [*fallback, *known_metas]:
        titles.add(meta)
    with closing(Cache(root, background)) as cache:
        ids = sorted(set(watches) | set(unwatched) | progress | set(titles.cards))
        cached = cache.read('card', ids)
        events = {**watches, **unwatched}
        missing = sorted((ident for ident in events if ident not in cached), key=events.get, reverse=True)
        warm_from_discovery(root, titles, missing[:12])
        load_cards(cache, titles, ids, cached)
        local_titles(root, titles, ids)
        _, seeds, _, votes, combos, _ = profile_evidence(titles, watches, excluded_ids, unwatched)
        if background:
            pending_aliases = {k for k in unwatched if not titles.card(k)}
            batch = cycle(sorted(set(seeds) | pending_aliases), cursor, 'history', SEEDS_PER_BATCH)
            provider = provider or Provider(root)
            for seed in batch:
                cache.refresh('card', seed, lambda: card_record(provider, seed))
            load_cards(cache, titles, batch)
            _, seeds, omitted, votes, combos, _ = profile_evidence(titles, watches, excluded_ids, unwatched)
            for seed in dict.fromkeys(titles.resolve(s) for s in batch):
                if seed not in seeds:
                    continue
                kind, uid = seed.split('/')
                card = titles.card(seed) or {'type': kind, 'id': uid}
                for basis, lookup in related_keys(seed, card).items():
                    cache.refresh(basis, lookup, lambda: related_entries(provider.related(card, basis)))
        specs = catalog_specs(votes, combos)
        if background:
            for spec in cycle(specs, cursor, 'page', PAGES_PER_BATCH):
                cache.refresh('catalog', json.dumps(spec), lambda: page_entries(provider.catalog(*spec)), ttl=DAY)
        hits = nominations(cache, titles, seeds, specs)
        add_recent_relations(hits, titles, seeds, related_rows)
        load_cards(cache, titles, hits)
        if background:
            enrich_candidates(cache, provider, hits, cursor)
            load_cards(cache, titles, hits)
        local_titles(root, titles, set(hits))
        return assemble(titles, watches, excluded_ids, hits, fallback, progress, cursor, unwatched)
