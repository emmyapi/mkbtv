import json
import sqlite3
import tempfile
import unittest
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from unittest.mock import patch

import for_you_pool as pool
from for_you_rank import release_age_group, select_lineup


def meta(uid, genres=('Drama',), kind='movie', **values):
    return {'type': kind, 'id': uid, 'name': 'Public ' + uid, 'genres': list(genres), **values}


def history(keys, profile=1):
    return {'format': 'nuvio-profile-backup-v2', 'profile_id': profile,
            'watched': [{'content_type': k, 'content_id': uid, 'watched_at': i,
                         'title': 'private export title'} for i, (k, uid) in enumerate(keys, 1)],
            'progress': []}


class Fixture:
    def __init__(self):
        self.calls = Counter()
        self.seed_requests = set()
        self.card_requests = Counter()
        self.offline = False

    def card(self, kind, uid):
        self.calls['card'] += 1
        self.card_requests[kind, uid] += 1
        if self.offline:
            raise pool.d.DiscoveryError('UPSTREAM_HTTP_429')
        number = int(uid.removeprefix('tt').removeprefix('tmdb:'))
        origins = ['KR'] if number in (1, 2, 3101, 3102) else []
        genres = ('Sci-Fi', 'Drama') if origins else ('Drama',)
        return meta('tt' + str(number), genres, kind, moviedb_id=str(number)), origins

    def related(self, seed, basis):
        self.calls['related'] += 1
        if self.offline:
            raise pool.d.DiscoveryError('UPSTREAM_HTTP_429')
        uid = seed['id']
        number = int(uid.removeprefix('tt').removeprefix('tmdb:'))
        self.seed_requests.add((seed['type'], number, basis))
        prefix = 'tt' if basis == 'simkl' else 'tmdb:'
        return [('movie', prefix + '3000'), ('movie', prefix + str(3100 + number))]

    def catalog(self, kind, genre, country, page, recent=False):
        self.calls['catalog'] += 1
        if self.offline:
            raise pool.d.DiscoveryError('UPSTREAM_HTTP_429')
        group = {(False, 'Drama'): 1, (True, 'Drama'): 2, (False, 'Science Fiction'): 3,
                 (True, 'Science Fiction'): 4}.get((bool(country), genre), 5)
        offset = 10000 + group * 1000 + page * 20
        return [meta('tt' + str(offset + i), (genre or 'Drama',), kind) for i in range(20)]


class PoolTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name) / 'discovery'
        self.provider = Fixture()
        # Every provider interaction in these tests is a fixture or explicit parser sample.
        network = patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('unexpected network'))
        network.start()
        self.addCleanup(network.stop)

    def prepare(self, doc, previous=None, **kwargs):
        before = self.provider.calls.copy()
        result = pool.prepare_pool(self.root, doc, background=True, provider=self.provider,
                                   cursor=(previous or {}).get('cursor'), **kwargs)
        delta = self.provider.calls - before
        self.assertLessEqual(delta['card'], pool.SEEDS_PER_BATCH + pool.CARDS_PER_BATCH)
        self.assertLessEqual(delta['related'], 2 * pool.SEEDS_PER_BATCH)
        self.assertLessEqual(delta['catalog'], pool.PAGES_PER_BATCH)
        return result

    def test_full_history_fractional_votes_support_candidates_and_no_live_fetch(self):
        keys = [('movie', 'tt' + str(i)) for i in range(1, 19)]
        keys += [('series', 'tt19')] * 24 + [('series', 'tmdb:19'), ('movie', 'tmdb:5')]
        doc = history(keys)
        doc['progress'] = [{'content_type': 'series', 'content_id': 'tmdb:19', 'position': 100}]
        result = None
        for _ in range(24):
            result = self.prepare(doc, result, excluded_ids=['tmdb:5'])
        self.assertEqual(result['distinct'], 19)
        self.assertEqual(result['matched'], 18)
        self.assertEqual(result['genre_votes'], {'Drama': 17, 'Science Fiction': 1})
        self.assertAlmostEqual(sum(result['interests'].values()), 1)
        self.assertGreater(result['interests']['South Korea / Science Fiction'], 0)
        self.assertEqual(result['aliases']['series/tmdb:19'], 'series/tt19')
        self.assertEqual(result['watched']['series/tt19'], result['watched']['series/tmdb:19'])
        self.assertEqual(result['progress'], ['series/tmdb:19', 'series/tt19'])
        self.assertTrue({('movie', i, b) for i in range(1, 19) if i != 5 for b in ('simkl', 'tmdb')}
                        <= self.provider.seed_requests)
        self.assertNotIn(('movie', 5, 'simkl'), self.provider.seed_requests)
        candidates = {m['meta']['id']: m for m in result['candidates']}
        shared, niche = candidates['tt3000'], candidates['tt3101']
        self.assertGreaterEqual(len({r['seed'] for r in shared['sources']}), 17)
        self.assertGreater(shared['strength'], niche['strength'])
        self.assertIn('South Korea / Science Fiction', niche['interests'])
        self.assertEqual({r['seed'] for r in niche['sources']}, {'movie/tt1'})
        self.assertNotIn('tt5', candidates)
        self.assertGreater(len(result['candidates']), 180)
        count = self.provider.calls.copy()
        again = pool.prepare_pool(self.root, doc, ['tmdb:5'], provider=self.provider)
        self.assertEqual(again['candidates'], result['candidates'])
        self.assertEqual(count, self.provider.calls)

    def test_provider_cache_reused_and_no_cross_profile_sources(self):
        first = self.prepare(history([('movie', 'tmdb:1')]))
        other = self.prepare(history([('movie', 'tt1')], profile=2))
        self.assertEqual(first['watched'], other['watched'])
        self.assertEqual(len(self.provider.seed_requests), 2)
        different = pool.prepare_pool(self.root, history([('movie', 'tt77')], profile=3),
                                      known_metas=[meta('tt77', ('Horror',))])
        self.assertEqual(different['interests'], {'Horror': 1})
        self.assertEqual([x['meta']['id'] for x in different['candidates']], ['tt77'])
        with sqlite3.connect(self.root / 'for-you/public.sqlite3') as db:
            saved = str(list(db.execute('SELECT basis,lookup,value FROM lookup')))
            self.assertNotIn('private export title', saved)
            self.assertNotIn('watched_at', saved)
            self.assertNotIn('profile_id', saved)

    def test_last_good_after_rate_limit_and_negative_retry(self):
        doc = history([('movie', 'tt1')])
        result = self.prepare(doc)
        with sqlite3.connect(self.root / 'for-you/public.sqlite3') as db:
            db.execute('UPDATE lookup SET due=0')
        self.provider.offline = True
        out = self.prepare(doc)
        self.assertEqual(out['candidates'], result['candidates'])
        checked = self.provider.card_requests.copy()
        count = self.provider.calls.copy()
        self.assertEqual(self.prepare(doc)['candidates'], result['candidates'])
        self.assertEqual(self.provider.calls['related'], count['related'])
        self.assertEqual(self.provider.calls['catalog'], count['catalog'])
        self.assertTrue(all(self.provider.card_requests[k] == n for k, n in checked.items()))
        with patch.object(self.provider, 'card', return_value=(None, [])):
            with sqlite3.connect(self.root / 'for-you/public.sqlite3') as db:
                db.execute("UPDATE lookup SET due=0 WHERE basis='card'")
            last = self.prepare(doc)
            self.assertEqual(last['genre_votes'], result['genre_votes'])
        with patch.object(self.provider, 'card', return_value=(None, [])) as missing:
            self.prepare(history([('movie', 'tt777')]))
            missing.reset_mock()
            self.prepare(history([('movie', 'tt777')]))
            self.assertNotIn(("movie", "tt777"), [attempt.args for attempt in missing.call_args_list])

    def test_malformed_public_lookups_preserve_good_data_and_continue_other_work(self):
        doc = history([('movie', 'tt1'), ('series', 'tt2')])
        first = self.prepare(doc)
        with sqlite3.connect(self.root / 'for-you/public.sqlite3') as db:
            db.execute('UPDATE lookup SET due=0')
        def relation(seed, basis):
            if basis == 'tmdb':
                return {'error': 'unknown schema'}
            return [None, ['bad'], ('movie', 'tt3000'), ('movie', 'tt5555')]
        def chart(kind, genre, country, page, recent=False):
            if kind == 'movie':
                raise TypeError('metas was null')
            return [meta('tt8899', [genre], 'series')]
        def details(kind, uid):
            if uid == 'tt1':
                raise AttributeError('unrecognized metadata object')
            return Fixture().card(kind, uid)
        with patch.object(self.provider, 'related', side_effect=relation), \
             patch.object(self.provider, 'catalog', side_effect=chart), \
             patch.object(self.provider, 'card', side_effect=details):
            now = self.prepare(doc)
        self.assertEqual(first['genre_votes'], now['genre_votes'])
        shared = next(item for item in now['candidates'] if item['meta']['id'] == 'tt3000')
        self.assertEqual({source['basis'] for source in shared['sources']}, {'tmdb', 'simkl'})
        ids = {item['meta']['id'] for item in now['candidates']}
        self.assertTrue({'tt5555', 'tt8899'} <= ids)
        self.assertEqual(pool.countries({'not': 'a country array'}), [])

    def test_undo_suppresses_alias_votes_and_sources_until_newer_watch(self):
        doc = history([('movie', 'tt1'), ('movie', 'tt2')])
        self.prepare(doc)
        mark = {'movie/tmdb:1': 1}
        hidden = pool.prepare_pool(self.root, doc, unwatched=mark)
        self.assertEqual(hidden['distinct'], 1)
        self.assertEqual(hidden['watched'], {'movie/tt2': 2, 'movie/tmdb:2': 2})
        self.assertNotIn('South Korea / Science Fiction', hidden['interests'])
        shared = next(c for c in hidden['candidates'] if c['meta']['id'] == 'tt3000')
        self.assertEqual({r['seed'] for r in shared['sources']}, {'movie/tt2'})
        self.assertEqual(self.prepare(doc, unwatched=mark)['watched'], hidden['watched'])
        doc['watched'].append({'content_type': 'movie', 'content_id': 'tmdb:1', 'watched_at': 3})
        restored = pool.prepare_pool(self.root, doc, unwatched=mark)
        self.assertEqual(restored['watched']['movie/tt1'], 3)
        self.assertIn('South Korea / Science Fiction', restored['interests'])

    def test_foreground_migrates_public_country_and_aliases_with_local_indexes(self):
        directory = self.root.parent / 'catalog'
        directory.mkdir()
        with sqlite3.connect(directory / 'catalog.sqlite3') as db:
            db.execute('CREATE TABLE title(id INTEGER PRIMARY KEY,imdb_id,type,name,year,genres,adult)')
            db.execute("INSERT INTO title VALUES (1,'tt0000001','movie','Film',2000,'Adventure,Drama',0)")
        with sqlite3.connect(directory / 'feeds.sqlite3') as db:
            db.executescript("""
              CREATE TABLE feed(key,source,updated_at);
              CREATE TABLE member(key,tmdb_id,imdb_id,type,tmdb_type);
              INSERT INTO feed VALUES ('chart','nuvio-chart',0);
              INSERT INTO member VALUES ('chart',10,'tt0000001','movie','movie');
              INSERT INTO member VALUES ('chart',10,'tt0000009','series','tv');
            """)
        doc = history([('movie', 'tt0000001'), ('movie', 'tmdb:10'),
                       ('series', 'tt0000002'), ('series', 'tt0000003')])
        for uid in ('tt0000002', 'tt0000003'):
            raw = meta(uid, ['Science Fiction'], 'series', language='English', country='South Korea')
            url = pool.d.metadata_url(pool.d.CINEMETA, 'series', uid)
            pool.d.atomic_json(pool.d.cache_path(self.root, url), {'meta': raw})
        result = pool.prepare_pool(self.root, doc)
        self.assertEqual(result['distinct'], 3)
        self.assertEqual(result['matched'], 3)
        self.assertEqual(result['genre_votes'], {'Drama': .5, 'Adventure': .5, 'Science Fiction': 2})
        self.assertIn('South Korea / Science Fiction', result['interests'])
        self.assertNotIn('English / Science Fiction', result['interests'])
        self.assertFalse((self.root / 'for-you').exists())

    def test_early_or_invalid_cache_and_verified_recent_relations(self):
        document = history([('movie', 'tt1')])
        rows = [{'id': 'because-watched', 'seed': ['movie', 'tt1'], 'basis': pool.d.TMDB_BASIS,
                 'metas': [meta('tt42', ['Horror'])]},
                {'id': 'because-watched-2', 'seed': ['movie', 'tt99'], 'basis': pool.d.SIMKL_BASIS,
                 'metas': [meta('tt100', ['Drama'])]}]
        directory = self.root / 'for-you'
        directory.mkdir(parents=True)
        path = directory / 'public.sqlite3'
        for contents in (b'', b'incomplete copy'):
            path.write_bytes(contents)
            result = pool.prepare_pool(self.root, document, known_metas=[meta('tt1')],
                                       fallback=[meta('tt2')], related_rows=rows)
            items = {m['meta']['id']: m for m in result['candidates']}
            self.assertEqual(set(items), {'tt1', 'tt2', 'tt42'})
            self.assertEqual(items['tt42']['sources'], [{'seed': 'movie/tt1', 'basis': 'tmdb', 'rank': 1}])
            self.assertGreater(items['tt42']['strength'], items['tt2']['strength'])
            self.assertEqual(path.read_bytes(), contents)

    def test_conflicting_native_mapping_does_not_join_imdb_titles(self):
        known = [meta('tt2', ['Drama'], moviedb_id='5'), meta('tt3', ['Horror'], moviedb_id='5')]
        document = history([('movie', 'tt2'), ('movie', 'tt3')])
        result = pool.prepare_pool(self.root, document, known_metas=known)
        self.assertEqual(result['ambiguous_aliases'], ['movie/tmdb:5'])
        self.assertEqual(result['genre_votes'], {'Drama': 1, 'Horror': 1})
        self.assertTrue(all('moviedb_id' not in item['meta'] for item in result['candidates']))

    def test_same_title_keeps_earliest_premiere_through_direct_and_native_alias_merges(self):
        newer = meta('tt3', kind='series', name='Preferred title', poster='preferred-poster', moviedb_id='301',
                     released='2025-02-14', releaseInfo='2025–')
        for uid in ('tt3', 'tmdb:301'):
            for original in ({'released': '2024-11-08', 'releaseInfo': '2024–'},
                             {'releaseInfo': '2019–2024'}):
                with self.subTest(uid=uid, original=original):
                    first = meta(uid, kind='series', name='Earlier source', **original)
                    result = pool.prepare_pool(self.root, history([]), fallback=[first, newer])
                    self.assertEqual(len(result['candidates']), 1)
                    merged = result['candidates'][0]['meta']
                    self.assertEqual(merged['name'], 'Preferred title')
                    self.assertEqual(merged['poster'], 'preferred-poster')
                    self.assertEqual(merged['releaseInfo'], original['releaseInfo'])
                    self.assertEqual(release_age_group(merged, date(2026, 11, 28)), 'older')
                    if 'released' in original:
                        self.assertEqual(merged['released'][:10], original['released'])

    def test_worldwide_and_country_catalog_use_typed_filters_and_links(self):
        provider = pool.Provider(self.root)
        seen = []
        def request(url, parse, ttl, params, **kwargs):
            seen.append((url, params))
            return parse(b'<div id="media-list"><div class="page_wrapper" id="page_2">'
                         b'<a href="/tv/2-show?language=en-US"></a><a href="/movie/3-film"></a>'
                         b'<a href="//unrelated.example/movie/7"></a></div></div>')
        with patch.object(provider.world, 'request', side_effect=request):
            self.assertEqual(provider.catalog('series', 'Science Fiction', 'South Korea', 2), [('series', 'tmdb:2')])
            self.assertEqual(provider.catalog('movie', '', '', 2), [('movie', 'tmdb:3')])
            for kind, ident in (('movie', 'tmdb:3'), ('series', 'tmdb:2')):
                with patch.object(pool, 'datetime', wraps=datetime) as clock:
                    clock.now.return_value = datetime(2024, 2, 29, tzinfo=timezone.utc)
                    self.assertEqual(provider.catalog(kind, 'Science Fiction', '', 2, True),
                                     [(kind, ident, ['2022-02-28', '2024-02-29'])])
        self.assertEqual(seen[0][1]['with_origin_country'], 'KR')
        self.assertEqual(seen[0][1]['with_genres'], 10765)
        self.assertNotIn('region', seen[1][1])
        self.assertIn('primary_release_date.lte', seen[1][1])
        for (url, params), field in zip(seen[2:], ('primary_release_date', 'first_air_date')):
            self.assertTrue(url.endswith('/discover/movie/items' if field.startswith('primary') else '/discover/tv/items'))
            self.assertEqual(params[field + '.gte'], '2022-02-28')
            self.assertEqual(params[field + '.lte'], '2024-02-29')
            self.assertEqual(params['with_genres'], 878 if field.startswith('primary') else 10765)
            self.assertNotIn('region', params)
            self.assertNotIn('with_original_language', params)

    def test_new_worldwide_matches_flow_through_shared_cache_and_published_poster(self):
        today = date(2026, 11, 28)
        at = int(datetime(2026, 11, 28, tzinfo=timezone.utc).timestamp() * 1000)
        window = ['2024-11-28', '2026-11-28']
        doc = history([('movie', 'tt1')])
        arrivals = [401, 402, 403]
        seen = set()
        def catalog(kind, genre, country, page, recent=False):
            if recent:
                seen.add((kind, genre))
                return [(kind, f'tmdb:{n}', window) for n in arrivals]
            return [meta('tt405', kind=kind, releaseInfo='2026', released='2026-12-25')]
        def card(kind, uid):
            n = uid.split(':')[-1].removeprefix('tt')
            if n == '403':
                return meta('tt403', ('Horror',), kind, moviedb_id=n, releaseInfo='2026'), []
            fields = {'releaseInfo': '2026'}
            if n == '402':
                fields['released'] = '2026-12-15'
                fields['first_air_date' if kind == 'series' else 'release_date'] = '2025-06-10'
            return meta('tt' + n, kind=kind, moviedb_id=n, **fields), []
        with patch.object(self.provider, 'catalog', side_effect=catalog), \
             patch.object(self.provider, 'related', return_value=[]), \
             patch.object(self.provider, 'card', side_effect=card):
            result = self.prepare(doc)
            result = self.prepare(doc, result)
            self.assertEqual(seen, {('series', 'Drama'), ('movie', 'Drama')})
            arrivals.append(404)
            with sqlite3.connect(self.root / 'for-you/public.sqlite3') as db:
                db.execute("UPDATE lookup SET due=0 WHERE basis='catalog'")
            result = self.prepare(doc)
        result = pool.prepare_pool(self.root, doc)
        raw = {(c['meta']['type'], c['meta']['id']): c for c in result['candidates']}
        for kind in ('movie', 'series'):
            item = raw[kind, 'tt401']
            self.assertEqual(item['meta']['mkbtvPremiereWindow'], window)
            self.assertEqual(item['sources'][0]['basis'], 'Recent worldwide Drama ' + kind)
            self.assertEqual(raw[kind, 'tt402']['meta']['released'], '2025-06-10T00:00:00.000Z')
            self.assertNotIn((kind, 'tt403'), raw)
        cards, _ = select_lineup(result, [], {}, now_ms=at)
        self.assertIn('tt404', {card['id'] for card in cards})
        self.assertNotIn('tt405', {card['id'] for card in cards})
        self.assertTrue(all(release_age_group(card, today) == 'recent' for card in cards))
        for card in cards:
            saved = pool.d.safe_meta(json.loads(json.dumps(card)))
            self.assertEqual(release_age_group(saved, date(2028, 12, 1)), 'older')
        self.assertEqual(pool.d.safe_meta(meta('tt7', release_date='2026-11-01', released='1987-05-01'))['released'],
                         '1987-05-01T00:00:00.000Z')
        for extra in ({'year': 2027}, {'first_air_date': '2027'}, {'released': '2027'}):
            self.assertEqual(release_age_group(pool.d.safe_meta(meta('tt8', kind='series', **extra)), today), 'future')


if __name__ == '__main__':
    unittest.main()
