import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
from contextlib import redirect_stdout
from io import BytesIO, StringIO
from http.client import HTTPConnection
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('discovery', Path(__file__).with_name('discovery.py'))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)


def meta(i, genre='Drama', kind='movie'):
    return {'id': 'tt' + str(i), 'type': kind, 'name': 'Synthetic ' + str(i), 'genres': [genre]}


def title_relation(root, seed):
    return [(seed['type'], 'tt9' + seed['id'][2:])]


def document(profile, kind='movie', repeated=False):
    rows = [{'content_id': 'tt1', 'content_type': kind, 'watched_at': '2026-01-01'}]
    if repeated:
        rows *= 100
    return {'format': 'nuvio-profile-backup-v2', 'profile_id': profile, 'watched': rows,
            'capture_finished_at': '2026-11-20T15:00:00+00:00', 'library': [], 'progress': []}


class DiscoverWatchedIdentityTests(unittest.TestCase):
    def setUp(self):
        import discover_query
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.data_patch = patch.object(discover_query, 'DATA', self.root)
        self.data_patch.start()
        self.addCleanup(self.data_patch.stop)
        with sqlite3.connect(self.root / 'catalog.sqlite3') as db:
            db.executescript("""
              CREATE TABLE meta(key,value);
              INSERT INTO meta VALUES('snapshot','v1');
              CREATE TABLE title(id INTEGER PRIMARY KEY,imdb_id,type,subtype,name,original,year,genres,rating,votes,adult);
              INSERT INTO title VALUES(1,'tt0000001','movie','movie','First',NULL,2010,NULL,7,20,0),
                                      (2,'tt0000002','movie','movie','Second',NULL,2011,NULL,8,10,0);
            """)
        with sqlite3.connect(self.root / 'feeds.sqlite3') as db:
            db.executescript("""
              CREATE TABLE feed(key TEXT PRIMARY KEY,snapshot,updated_at,source);
              CREATE TABLE member(key,type,tmdb_id,tmdb_type,imdb_id,rank,source_id,name,year);
              CREATE TABLE chart_gap(key,type,skip);
              INSERT INTO feed VALUES ('chart','v1',0,'nuvio-chart'),('trending','v1',0,'nuvio-chart');
            """)
            db.execute("INSERT INTO feed VALUES('hulu','v1',?,'watchmode-us-sub')", (int(time.time()),))

    def query(self, *uids, catalog='all'):
        return d.discover_response(self.root, 1, dict(watch='watched', watched_movies=list(uids), catalog=catalog))

    def test_prefer_consistent_typed_local_crosswalk_and_reject_conflicts(self):
        with sqlite3.connect(self.root / 'feeds.sqlite3') as db:
            db.executescript("""
              INSERT INTO member(key,type,tmdb_id,tmdb_type,imdb_id) VALUES
                ('chart','movie',70,'movie','tt0000001'), ('hulu','movie',70,'movie','tt0000001'),
                ('chart','series',70,'tv','tt0000002'), ('chart','movie',71,'movie','tt0000002');
            """)
        with patch.object(d, 'fetch', side_effect=AssertionError('unexpected fetch')):
            status, body = self.query('tmdb:70', 'tmdb:71')
            self.assertEqual((status, body['total']), (200, 2))
            with sqlite3.connect(self.root / 'feeds.sqlite3') as db:
                db.execute("UPDATE member SET imdb_id='tt0000002' WHERE key='hulu'")
            self.assertEqual(self.query('tmdb:70'), (503, {'error': 'watch_unavailable'}))

    def test_cached_imdb_and_confirmed_native_only_with_no_outbound_requests(self):
        native = {'type': 'movie', 'id': 'tmdb:70', 'name': 'First'}
        url = 'https://catalog.nuvio.tv/meta/movie/tmdb%3A70.json'
        cache = self.root / 'cache'
        cache.mkdir()
        path = cache / (hashlib.sha256(url.encode()).hexdigest() + '.json')
        with patch.object(d, 'fetch', side_effect=AssertionError('unexpected fetch')), \
             patch.object(d, 'urlopen', side_effect=AssertionError('unexpected network access')), \
             patch.object(d, 'atomic_json', side_effect=AssertionError('unexpected cache write')):
            self.assertEqual(self.query('tmdb:70'), (503, {'error': 'watch_unavailable'}))
            self.assertEqual(list(cache.iterdir()), [])
            path.write_text(json.dumps({'meta': dict(native, imdb_id='tt0000001')}))
            status, body = self.query('tmdb:70')
            self.assertEqual((status, [item['id'] for item in body['metas']]), (200, ['tt0000001']))
            path.write_text(json.dumps({'meta': native}))
            self.assertEqual(self.query('tmdb:70'), (503, {'error': 'watch_unavailable'}))
            path.unlink()
            with sqlite3.connect(self.root / 'feeds.sqlite3') as db:
                db.execute("INSERT INTO member VALUES('hulu','movie',70,'movie',NULL,1,'watchmode:99','First',2010)")
            status, body = self.query('tmdb:70', catalog='hulu')
            self.assertEqual((status, [item['id'] for item in body['metas']]), (200, ['tmdb:70']))
            self.assertEqual(list(cache.iterdir()), [])
            path.write_text(json.dumps({'meta': native}))
            status, body = self.query('tmdb:70')
            self.assertEqual((status, [item['id'] for item in body['metas']]), (200, ['tmdb:70']))
            for bad in [dict(native, type='series'), dict(native, id='tmdb:71', imdb_id='tt0000001'),
                        dict(native, id='tt0000002', imdb_id='tt0000001'), dict(native, imdb_id='bad')]:
                with self.subTest(bad=bad):
                    path.write_text(json.dumps({'meta': bad}))
                    self.assertEqual(self.query('tmdb:70'), (503, {'error': 'watch_unavailable'}))


class Tests(unittest.TestCase):
    def test_episodes_count_once_and_reject_other_profile(self):
        self.assertEqual(len(d.watched_unique(document(1, 'series', True))), 1)
        value = document(1)
        value['watched'][0]['profile_id'] = 2
        with self.assertRaisesRegex(d.DiscoveryError, 'PROFILE_MISMATCH'):
            d.watched_unique(value)

    def test_watched_exclusion_and_both_media_types(self):
        candidates = [meta(1), meta(2), meta(3, kind='series'), {**meta(4), 'id': 'tmdb:14', 'imdb_id': 'tt1'}]
        ranked = d.rank_candidates(candidates, {('movie', 'tt1')}, {'Drama': 10}, {})
        self.assertEqual({d.identity(m) for m in ranked}, {('movie', 'tt2'), ('series', 'tt3')})

    def test_genres_and_rank_change_with_history(self):
        a = d.genre_preferences([meta(1, 'Drama')])
        b = d.genre_preferences([meta(1, 'Horror')])
        options = [meta(2, 'Drama'), meta(3, 'Horror')]
        self.assertEqual(d.rank_candidates(options, {}, a, {})[0]['id'], 'tt2')
        self.assertEqual(d.rank_candidates(options, {}, b, {})[0]['id'], 'tt3')
        self.assertEqual(d.genre_preferences([meta(1, 'Action & Adventure')])['Adventure'], 1)

    def test_build_uses_simkl_order_and_shared_trending(self):
        def metadata(root, kind, item):
            return meta(int(item[2:]), 'Adventure', kind)
        def catalog(root, kind, catalog_id, genre=None, *, skip=0):
            return [meta(i, genre or 'Adventure', kind) for i in range(10, 25)]
        with patch.object(d, 'metadata', metadata), patch.object(d, 'catalog', catalog), \
             patch.object(d, 'related', return_value=[('movie', 'tt' + str(i)) for i in range(1, 8)]):
            result = d.build_profile(Path('.'), document(1))
        rows = {r['id']: r for r in result['rows']}
        self.assertEqual([r['name'] for r in result['rows']],
                         ['For You', 'Popular Now', 'Because You Watched Synthetic 1', 'Adventure'])
        self.assertEqual(d.manifest(result)['name'], 'Personalized Recommendations')
        self.assertEqual([m['id'] for m in rows['because-watched']['metas']], ['tt' + str(i) for i in range(2, 8)])
        self.assertTrue(all(m['id'] != 'tt1' for m in rows['for-you']['metas']))
        self.assertEqual(rows['popular-now']['metas'][0]['id'], 'tt10')
        self.assertEqual(result['summary']['because_watched_source'], 'Simkl users_recommendations')

    def test_latest_three_distinct_eligible_titles_across_media(self):
        value = document(1)
        value['watched'] = [
            {'content_id': 'tt' + str(i), 'content_type': kind, 'watched_at': date}
            for i, kind, date in [(1, 'movie', '2026-01-01'), (2, 'movie', '2026-01-02'),
                                  (3, 'series', '2026-01-03'), (4, 'series', '2026-01-04'),
                                  (4, 'series', '2026-01-05'), (5, 'movie', '2026-01-06')]]
        def catalog(root, kind, catalog_id, genre=None, *, skip=0):
            return [meta(i, genre or 'Drama', kind) for i in range(1, 12)]
        with patch.object(d, 'metadata', side_effect=lambda root, kind, item: meta(int(item[2:]), kind=kind)), \
             patch.object(d, 'tmdb_related', side_effect=title_relation), \
             patch.object(d, 'catalog', catalog), patch.object(d, 'related', return_value=[]) as related:
            result = d.build_profile(Path('.'), value, ['tt5'])
        self.assertEqual([call.args[1]['id'] for call in related.call_args_list], ['tt4', 'tt3', 'tt2'])
        self.assertEqual(result['summary']['watched_distinct'], 5)
        self.assertEqual(result['rows'][2]['name'], 'Because You Watched Synthetic 4')
        self.assertEqual([[d.identity(m) for m in row['metas']] for row in result['rows'][2:5]],
                         [[('series', 'tt94')], [('series', 'tt93')], [('movie', 'tt92')]])
        self.assertTrue(all(row['basis'] == d.TMDB_BASIS for row in result['rows'][2:5]))
        self.assertNotIn(('movie', 'tt1'), [d.identity(m) for m in result['rows'][0]['metas']])

    def test_profile_token_isolation_and_no_history_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            config = {'tokens': {'1': 'test-profile-one', '2': 'test-profile-two'}}
            d.atomic_json(root / 'data' / 'catalogs.json', {'profiles': {str(profile):
                {'profile_id': profile, 'rows': [{'id': 'for-you', 'name': 'For You', 'metas': [meta(profile)]}]} for profile in (1, 2)}})
            status, value = d.response_for(root, config, '/test-profile-one/catalog/mixed/for-you.json')
            self.assertEqual((status, value['metas'][0]['id']), (200, 'tt1'))
            status, value = d.response_for(root, config, '/test-profile-two/catalog/mixed/for-you.json')
            self.assertEqual((status, value['metas'][0]['id']), (200, 'tt2'))
            for path in ('/wrong/manifest.json', '/test-profile-one/profile-2.json', '/test-profile-one/../../config.json'):
                self.assertEqual(d.response_for(root, config, path)[0], 404)
            self.assertEqual(d.response_for(root, config, '/test-profile-one/manifest.json')[1]['resources'], ['catalog'])

    def test_recent_genres_and_independent_for_you(self):
        names = ['Drama', 'Comedy', 'Action', 'Mystery', 'Horror', 'Family', 'Fantasy']
        value = document(1)
        # Most recent 15 are 20..6. Older titles would otherwise make Western the top genre.
        value['watched'] = [{'content_type': 'movie', 'content_id': 'tt' + str(i),
                             'watched_at': i} for i in range(1, 21)]
        def fake_meta(root, kind, content_id):
            num = int(content_id[2:])
            return meta(num, names[(20 - num) % len(names)] if num >= 6 else 'Western', kind)
        def genre_catalog(root, kind, catalog_id, genre=None, *, skip=0):
            genre = 'Action' if genre == 'Action & Adventure' else genre
            if catalog_id.startswith('trending'):
                return [meta(200 + i, g, kind) for i, g in enumerate(names)]
            index = names.index(genre) if genre else 0
            return [meta(100 + index, genre or 'Drama', kind)]
        with patch.object(d, 'metadata', side_effect=fake_meta) as lookup, \
             patch.object(d, 'tmdb_related', side_effect=title_relation), \
             patch.object(d, 'catalog', side_effect=genre_catalog), patch.object(d, 'related', return_value=[]):
            result = d.build_profile(Path('.'), value)
        self.assertEqual(len(lookup.call_args_list), 15 + 3)
        self.assertEqual(list(result['genre_pool']), names[:6])
        rows = result['rows']
        self.assertEqual([r['name'] for r in rows[2:5]], ['Because You Watched Synthetic ' + str(i) for i in (20, 19, 18)])
        self.assertEqual({x['id'] for x in rows[0]['metas']}, {'tt100', 'tt101', 'tt102'})
        self.assertEqual(len(rows), 8)
        self.assertEqual(len({r['id'] for r in rows}), 8)

    def test_trending_shelves_share_pages_and_skip_watched_aliases(self):
        alias = {**meta(1), 'id': 'tmdb:12', 'imdb_id': 'tt1'}
        native_alias = d.safe_meta({**meta(2), 'moviedb_id': 13})
        def page(root, kind, chart, *, skip):
            self.assertIn(chart, ('trending-movies', 'trending-series'))
            if skip == 40 and kind == 'series':
                return [meta(8, 'Sci-Fi & Fantasy', kind)]
            return []
        with patch.object(d, 'catalog', side_effect=page) as lookup:
            pool = d.trending_genre_pool(Path('.'), ['Drama', 'Fantasy'], {('movie', 'tt1'), ('movie', 'tmdb:13')},
                                        [alias, native_alias, meta(3), meta(3)])
        self.assertEqual([m['id'] for m in pool['Drama']], ['tt3'])
        self.assertEqual([m['id'] for m in pool['Fantasy']], ['tt8'])
        self.assertEqual(len(lookup.call_args_list), len({(c.args[1], c.kwargs['skip']) for c in lookup.call_args_list}))

    def test_session_order_rotates_within_chart_positions(self):
        ranked = [meta(i) for i in range(30)]
        profile = {'genre_pool': {'Drama': ranked}, 'rows': []}
        d.set_genre_rows(profile, ['Drama'], 'visit1')
        previous = profile['rows'][0]['metas']
        d.set_genre_rows(profile, ['Drama'], 'visit1')
        self.assertEqual(previous, profile['rows'][0]['metas'])
        d.set_genre_rows(profile, ['Drama'], 'visit2')
        current = profile['rows'][0]['metas']
        self.assertNotEqual(previous, current)
        self.assertEqual({m['id'] for m in current[:6]}, {m['id'] for m in ranked[:6]})
        self.assertEqual(ranked[0]['id'], 'tt0')

    def test_empty_history_and_duplicate_aliases_in_rolling_window(self):
        value = document(1)
        value['watched'] = []
        def genre_catalog(root, kind, catalog_id, genre=None, *, skip=0):
            if catalog_id == 'trending-series':
                return [meta(180, 'Kids', kind)]
            self.assertFalse(kind == 'movie' and genre == 'Kids')
            return [meta(150, genre or 'Drama', kind)]
        def seed_meta(root, kind, content_id):
            if content_id == 'tmdb:20':
                content_id = 'tt15'
            return meta(int(content_id[2:]), 'Kids' if content_id == 'tt1' else 'Drama', kind)
        with patch.object(d, 'catalog', side_effect=genre_catalog), patch.object(d, 'related', return_value=[]), \
             patch.object(d, 'tmdb_related', side_effect=title_relation), \
             patch.object(d, 'metadata', side_effect=seed_meta) as lookup:
            empty = d.build_profile(Path('.'), value)
            self.assertEqual([r['id'] for r in empty['rows']], ['for-you', 'popular-now'])
            value['watched'] = [{'content_type': 'series', 'content_id': 'tt' + str(i), 'watched_at': i}
                                for i in range(1, 16)]
            value['watched'].append({'content_type': 'series', 'content_id': 'tmdb:20', 'watched_at': 16})
            parsed = d.build_profile(Path('.'), value)
            self.assertEqual(lookup.call_count, 16 + 3)
            self.assertEqual(list(parsed['genre_pool']), ['Drama', 'Kids'])
            self.assertEqual([r['name'] for r in parsed['rows'][2:5]],
                             [f'Because You Watched Synthetic {n}' for n in (15, 14, 13)])

    def test_upstream_outage_keeps_only_verified_rows_for_the_same_seed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with patch.object(d, 'urlopen', side_effect=d.HTTPError('', 429, '', {'Retry-After': '120'}, None)) as urlopen:
                for title in ('tt1', 'tt2'):
                    with self.assertRaisesRegex(d.DiscoveryError, '429'):
                        d.fetch(root, d.SIMKL + '/movies/' + title)
                self.assertEqual(urlopen.call_count, 1)
            with patch.object(d, 'metadata', return_value=meta(1)), \
                 patch.object(d, 'catalog', side_effect=lambda _, k, c, genre=None, skip=0: [meta(99, genre or 'Drama', k)]):
                previous = [
                    {'seed': ['movie', 'tt1'], 'basis': d.TMDB_BASIS, 'metas': [meta(1), meta(12)]},
                    {'seed': ['series', 'tt1'], 'basis': d.TMDB_BASIS, 'metas': [meta(77)]},
                    {'basis': 'genre_similarity', 'metas': [meta(33)]},
                ]
                for error in ('UPSTREAM_HTTP_404', 'UPSTREAM_HTTP_429', 'UPSTREAM_UNAVAILABLE'):
                    with patch.object(d, 'related', side_effect=d.DiscoveryError(error)), \
                         patch.object(d, 'tmdb_related', side_effect=d.DiscoveryError(error)):
                        result = d.build_profile(root, document(1))
                        self.assertFalse(any(r['id'].startswith('because') for r in result['rows']))
                        self.assertTrue(result['rows'][0]['metas'])
                        recovered = d.build_profile(root, document(1), previous_rows=previous)
                        rows = [r for r in recovered['rows'] if r['id'].startswith('because')]
                        expected = [] if error.endswith('404') else [[meta(12)]]
                        self.assertEqual([r['metas'] for r in rows], expected)

    def test_tmdb_scroller_order_and_stale_cache_on_markup_change_and_rate_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            url = d.TMDB + '/movie/603/remote/recommendations?version=1&translate=false'
            def response(html):
                page = BytesIO(html.encode())
                page.url = url
                return page
            html = """<a href="/movie/99-outside"></a><div id="recommendation_scroller">
            <div><a href="/movie/2-second"></a><a href="/movie/2-second"></a></div>
            <a href="/tv/3-third"></a><a href="//elsewhere.invalid/movie/1"></a></div>
            <div id="mlt-results"><a href="/movie/4-genre"></a></div>"""
            expected = [('movie', 'tmdb:2'), ('series', 'tmdb:3')]
            seed = {**meta(1), 'moviedb_id': '603'}
            with patch.object(d, 'urlopen', return_value=response(html)) as open_:
                self.assertEqual(d.tmdb_related(root, seed), expected)
                self.assertEqual(d.tmdb_related(root, seed), expected)
                self.assertEqual(open_.call_count, 1)
                self.assertEqual(open_.call_args.args[0].full_url, url)
            with patch.object(d, 'urlopen', return_value=response('<a href="/tv/3-third"></a>')) as open_:
                self.assertEqual([tuple(k) for k in d.fetch(root, url, 0)], expected)
                self.assertEqual([tuple(k) for k in d.fetch(root, url, 0)], expected)
                self.assertEqual(open_.call_count, 1)
            with patch.object(d, 'urlopen', side_effect=d.HTTPError('', 429, '', {'Retry-After': '120'}, None)) as open_:
                for number in (3, 4):
                    with self.assertRaisesRegex(d.DiscoveryError, '429'):
                        d.tmdb_related(root, {**meta(9, kind='series'), 'moviedb_id': number})
                self.assertEqual(open_.call_count, 1)

    def test_native_candidate_maps_to_playable_id_and_watched_aliases_are_removed(self):
        def lookup(root, kind, key):
            number = int(key.removeprefix('tmdb:').removeprefix('tt'))
            return {**meta(number if key.startswith('tt') else number - 100, kind=kind),
                    'moviedb_id': number if key.startswith('tmdb:') else 100 + number}
        with patch.object(d, 'metadata', side_effect=lookup), \
             patch.object(d, 'related', return_value=[('movie', 'tt77')]), \
             patch.object(d, 'tmdb_related', return_value=[('movie', 'tmdb:101'), ('movie', 'tmdb:104'), ('movie', 'tmdb:102')]), \
             patch.object(d, 'catalog', side_effect=lambda _, k, c, genre=None, skip=0: [meta(99, genre or 'Drama', k)]):
            result = d.build_profile(Path('.'), document(1))
            self.assertEqual([m['id'] for m in result['rows'][2]['metas']], ['tt4', 'tt2'])
            self.assertEqual({m['id'] for m in result['rows'][0]['metas']}, {'tt77', 'tt99'})

    def test_optional_simkl_rejects_malformed_responses(self):
        with patch.object(d, 'fetch', return_value={'users_recommendations': {'error': 'unavailable'}}):
            with self.assertRaises(d.DiscoveryError):
                d.related(Path('.'), meta(1))
        mixed = [None, {'ids': 1}, {'type': 'tv', 'ids': {'imdb': 'tt2'}}]
        with patch.object(d, 'fetch', return_value={'users_recommendations': mixed}):
            self.assertEqual(d.related(Path('.'), meta(1)), [('series', 'tt2')])

    def test_typed_tmdb_mapping_from_cinemeta_and_nuvio(self):
        card = {**meta(1, kind='series'), 'links': [{'category': 'source', 'url': d.TMDB + '/tv/15'}]}
        with patch.object(d, 'fetch', return_value={'meta': card}):
            self.assertEqual(d.metadata(Path('.'), 'series', 'tmdb:15')['moviedb_id'], '15')
            self.assertEqual(d.metadata(Path('.'), 'series', 'tt1')['moviedb_id'], '15')
            self.assertIsNone(d.metadata(Path('.'), 'series', 'tmdb:16'))
            self.assertIsNone(d.metadata(Path('.'), 'series', 'tt2'))
            self.assertIsNone(d.metadata(Path('.'), 'movie', 'tmdb:15'))
        with patch.object(d, 'fetch', return_value={'meta': {**meta(1), 'moviedb_id': 60}}):
            self.assertEqual(d.metadata(Path('.'), 'movie', 'tt1')['moviedb_id'], '60')

    def test_unwatch_preserves_export_and_truncated_snapshot_boundary(self):
        def snapshot(ids):
            return [{'type': 'movie', 'id': f'tt{i}', 'at': i} for i in ids]
        def old(rows):
            return {'live_watched': rows, 'session': 'a', 'client_snapshot': rows}
        base = snapshot([1, 20, 88])
        session = {'session': 'a', 'watched': snapshot([20])}
        doc = document(1)
        live, _ = d.live_history(doc, old(base), session)
        self.assertEqual(live, snapshot([20]))
        self.assertIn(('movie', 'tt1'), d.watched_unique(doc))
        replacement = snapshot(range(21, 121))
        truncated, _ = d.live_history(document(1), old(base), {'session': 'a', 'watched': replacement})
        self.assertIn(snapshot([20])[0], truncated)
        fresh, _ = d.live_history(document(1), old(base), {'session': 'b', 'watched': []})
        self.assertEqual(fresh, snapshot([20, 88]))

    def test_live_handler_rebuild_rotation_isolation_and_daily_reconciliation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, history = Path(tmp) / 'runtime', Path(tmp) / 'history'
            generation = '20261120T150000.000000Z'
            records = []
            genres = ['Drama', 'Comedy', 'Action', 'Horror', 'Family', 'Mystery']
            for profile in (1, 2):
                doc = document(profile)
                doc['watched'] = [{'content_type': 'movie', 'content_id': 'tt' + str(i), 'watched_at': i}
                                  for i in range(1, 16)]
                path = history / 'generations' / generation / f'profile-{profile}.json'
                d.atomic_json(path, doc)
                records.append({'profile_id': profile, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                'counts': {'watched': 15, 'library': 0, 'progress': 0}})
            d.atomic_json(history / 'manifest.json', {'format': 'nuvio-backup-manifest-v1',
                                                     'generation': generation, 'profiles': records})
            def genre_rows(root, kind, catalog_id, genre=None, *, skip=0):
                genre = 'Action' if genre == 'Action & Adventure' else genre
                if catalog_id.startswith('trending'):
                    return [meta(300 + i, g, kind) for i, g in enumerate(genres)]
                return [meta(200 + (genres.index(genre) if genre else 0), genre or 'Drama', kind)]
            with patch.object(d, 'metadata', side_effect=lambda _, k, i: meta(int(i[2:]), genres[int(i[2:]) % 6], k)), \
                 patch.object(d, 'tmdb_related', side_effect=title_relation), \
                 patch.object(d, 'catalog', side_effect=genre_rows), patch.object(d, 'related', return_value=[]), \
                 redirect_stdout(StringIO()):
                d.refresh(root, history)
                tokens = json.loads((root / 'config.json').read_text())['tokens']
                server = d.ThreadingHTTPServer(('127.0.0.1', 0), d.handler_for(root, history))
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                def request(method, token, resource, data=None):
                    conn = HTTPConnection(*server.server_address, timeout=3)
                    try:
                        conn.request(method, f'/{token}/{resource}', body=json.dumps(data) if data is not None else None,
                                     headers={'Content-Type': 'application/json'})
                        response = conn.getresponse()
                        self.assertIn('no-store', response.getheader('Cache-Control'))
                        return response.status, json.loads(response.read())
                    finally:
                        conn.close()
                try:
                    token = tokens['1']
                    profile2 = request('GET', tokens['2'], 'manifest.json')
                    post = lambda payload: request('POST', token, 'session.json', payload)
                    first = post({'session': 's1', 'watched': []})
                    self.assertEqual(first[0], 200)
                    genres_from = lambda m: {r['name'] for r in m[1]['catalogs'] if r['id'].startswith('genre-')}
                    same = post({'session': 's1', 'watched': []})
                    self.assertEqual(first, same)
                    reopened = post({'session': 's2', 'watched': []})
                    self.assertNotEqual(genres_from(first), genres_from(reopened))
                    event = {'session': 's2', 'watched': [{'type': 'movie', 'id': 'tt88', 'at': 100}]}
                    watched = post(event)
                    self.assertEqual(watched[1]['catalogs'][2]['name'], 'Because You Watched Synthetic 88')
                    self.assertNotEqual(genres_from(reopened), genres_from(watched))
                    self.assertEqual(watched, post(event))
                    self.assertEqual(profile2, request('GET', tokens['2'], 'manifest.json'))
                    d.refresh(root, history)  # timer sees only the old export
                    self.assertEqual(watched, request('GET', token, 'manifest.json'))
                    self.assertEqual(request('GET', token, 'catalog/mixed/because-watched-3.json')[0], 200)
                    undone = post({'session': 's2', 'watched': []})
                    self.assertGreater(undone[1]['configVersion'], watched[1]['configVersion'])
                    self.assertEqual(undone[1]['catalogs'][2]['name'], 'Because You Watched Synthetic 15')
                    d.refresh(root, history)
                    self.assertEqual(undone, request('GET', token, 'manifest.json'))
                    self.assertEqual(profile2, request('GET', tokens['2'], 'manifest.json'))
                    self.assertEqual(post({'session': 's2', 'watched': [{'id': 'oops'}]})[0], 400)
                    self.assertEqual(request('POST', 'invalid', 'session.json', event)[0], 404)
                finally:
                    server.shutdown()
                    server.server_close()
                    thread.join()

    def test_failed_refresh_keeps_previous_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, history = Path(tmp) / 'runtime', Path(tmp) / 'history'
            generation = '20261120T150000.000000Z'
            body = document(1)
            export = history / 'generations' / generation / 'profile-1.json'
            d.atomic_json(export, body)
            d.atomic_json(history / 'manifest.json', {'format': 'nuvio-backup-manifest-v1', 'generation': generation, 'profiles': [
                {'profile_id': 1, 'sha256': hashlib.sha256(export.read_bytes()).hexdigest(), 'counts': {'watched': 1, 'library': 0, 'progress': 0}}]})
            old = {'profiles': {'1': {'profile_id': 1, 'summary': {'watched_distinct': 1}, 'rows': [{'id': 'old', 'metas': [meta(99)]}]}}}
            d.atomic_json(root / 'data' / 'catalogs.json', old)
            with patch.object(d, 'build_profile', side_effect=d.DiscoveryError('UPSTREAM_HTTP_429')):
                with self.assertRaisesRegex(d.DiscoveryError, '429'):
                    d.refresh(root, history)
            self.assertEqual(json.loads((root / 'data' / 'catalogs.json').read_text()), old)
            export.write_text('{}')
            with self.assertRaises(d.DiscoveryError):
                d.refresh(root, history)
            self.assertEqual(json.loads((root / 'data' / 'catalogs.json').read_text()), old)


if __name__ == '__main__':
    unittest.main()
