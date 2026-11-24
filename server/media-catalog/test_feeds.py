from datetime import datetime
import re
import sqlite3
import unittest
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch
from urllib.parse import unquote

import feeds


def watch_row(i, kind='movie'):
    return {'id': i, 'type': kind, 'title': f'Sample {i}', 'year': 1995,
            'imdb_id': None, 'tmdb_id': None, 'tmdb_type': None}


def nuvio_row(ident, kind='series'):
    return {'id': ident, 'type': kind, 'name': 'A title', 'releaseInfo': '1993–2000', 'genres': ['Soap']}


class WatchmodeTests(unittest.TestCase):
    def test_direct_cli_private_env_and_override(self):
        with TemporaryDirectory() as temp, patch.dict('os.environ', {}, clear=True):
            path = Path(temp) / 'values'
            self.assertIsNone(feeds.watchmode_key(path))
            value = 'unit-test-placeholder'
            path.write_text("OTHER=unused\nWATCHMODE_API_KEY='" + value + "' # local\n")
            self.assertEqual(feeds.watchmode_key(path), value)
            with patch.dict('os.environ', {'WATCHMODE_API_KEY': 'systemd-value'}):
                self.assertEqual(feeds.watchmode_key(path), 'systemd-value')
            path.write_text("WATCHMODE_API_KEY='unterminated")
            with self.assertRaisesRegex(feeds.FeedError, 'invalid-private-env'):
                feeds.watchmode_key(path)

    def test_all_pages_types_and_fallbacks(self):
        types = tuple(feeds.WATCH_TYPES)
        pages = []
        for i in range(1, 253):
            r = watch_row(i, types[i % len(types)])
            if i == 2:
                r.update(imdb_id='tt1234567', tmdb_id=123, tmdb_type='movie')
            pages.append(r)

        def request(url, params):
            self.assertEqual(url, 'https://api.watchmode.com/v1/list-titles/')
            self.assertEqual({'regions': 'US', 'source_types': 'sub', 'source_ids': '26', 'sort_by': 'title_asc',
                              'limit': 250, 'page': params['page']}, params)
            n = params['page']
            return {'page': n, 'total_results': 252, 'total_pages': 2, 'titles': pages[(n-1)*250:n*250]}

        entries = feeds.watch_entries(request, '26', 2)
        self.assertEqual(len(entries), 252)
        self.assertEqual(set(x.type for x in entries), {'movie', 'series'})
        self.assertEqual((entries[0].name, entries[0].source_id, entries[0].imdb_id, entries[0].year),
                         ('Sample 1', 'watchmode:1', None, 1995))
        self.assertEqual((entries[1].imdb_id, entries[1].tmdb_id), ('tt1234567', 123))

    def test_empty_partial_duplicate_or_budget_never_publishes(self):
        cases = [(0, 0, [], 1, 'empty-catalog'), (2, 1, [watch_row(1)], 1, 'incomplete-watchmode-page'),
                 (2, 1, [watch_row(1)]*2, 1, 'repeated-watchmode-title'),
                 (251, 2, [], 1, 'insufficient-quota')]
        for total, pages, titles, budget, reason in cases:
            with self.subTest(reason=reason):
                get = Mock(return_value={'page': 1, 'total_results': total, 'total_pages': pages, 'titles': titles})
                with self.assertRaisesRegex(feeds.FeedError, reason):
                    feeds.watch_entries(get, '203', budget)
                self.assertEqual(get.call_count, 1)

    def test_partial_external_ids_preserve_watchmode_membership(self):
        for missing in ('', 'N/A', '0', 0, -1):
            with self.subTest(missing=missing):
                row=dict(watch_row(8),imdb_id=missing,tmdb_id=missing,tmdb_type='')
                member=feeds.watch_member(row,1)
                self.assertEqual('watchmode:8',member.source_id)
                self.assertIsNone(member.imdb_id)
                self.assertIsNone(member.tmdb_id)
        row=dict(watch_row(9),type='movie',tmdb_id='124',tmdb_type='movie')
        self.assertEqual(124,feeds.watch_member(row,1).tmdb_id)
        self.assertIsNone(feeds.watch_member(dict(row,tmdb_type='tv'),1).tmdb_id)

    def test_source_matches_direct_us_subscription_only(self):
        direct = {'name': 'Amazon Prime', 'id': 26, 'regions': ['US'], 'type': 'sub', 'host_source_id': None}
        other = [dict(direct, id=1, regions=['NL']), dict(direct, id=2, type='purchase'),
                 dict(direct, id=3, host_source_id=24), dict(direct, id=4, name='Starz via Prime')]
        self.assertEqual(feeds.provider_ids([*other, direct], 'prime-video'), '26')
        with self.assertRaisesRegex(feeds.FeedError, 'source-unavailable'):
            feeds.provider_ids(other, 'prime-video')


class Top250Tests(unittest.TestCase):
    now = int(datetime.fromisoformat('2026-11-24T12:00:00+00:00').timestamp())
    revision = 'b' * 40

    def setUp(self):
        self.publication = [{'sha': self.revision, 'commit': {'committer': {'date': '2026-11-24T09:15:41Z'}}}]
        self.files = {}
        for kind, start in [('movies', 1000000), ('shows', 2000000)]:
            series = kind == 'shows'
            self.files[kind] = [
                {'Rank': rank, 'name': f'Story &amp; title {rank}', 'isSeries': series, 'isEpisode': False,
                 'titleType': 'TV Series' if series else 'Movie',
                 'link': f'https://www.imdb.com/title/tt{start+rank}/'} for rank in range(1, 251)]
        self.db = feeds.database(':memory:')
        self.addCleanup(self.db.close)

    def get(self, url, params=None):
        if params:
            self.assertEqual(params, {'path':'data/top250','sha':'main','per_page':1})
            self.assertEqual(url, f'https://api.github.com/repos/{feeds.TOP_REPOSITORY}/commits')
            return self.publication
        self.assertIn('/' + self.revision + '/data/top250/', url)
        return self.files[url.rsplit('/', 1)[-1].removesuffix('.json')]

    def snapshot(self):
        return self.db.execute('SELECT snapshot,updated_at FROM feed WHERE key=?', (feeds.TOP_250,)).fetchone()

    def test_pinned_distinct_rankings_have_stable_generation(self):
        with patch.object(feeds, 'log'):
            self.assertTrue(feeds.refresh_top_250(self.db, self.get, self.now))
            first = self.snapshot()
            self.assertTrue(feeds.refresh_top_250(self.db, self.get, self.now+3600))
            self.assertEqual(first, self.snapshot())
            self.assertEqual(self.db.execute('SELECT type,count(*),min(rank),max(rank) FROM member GROUP BY type').fetchall(),
                             [('movie',250,1,250), ('series',250,1,250)])
            self.assertEqual(self.db.execute('SELECT name FROM member WHERE rank=1 LIMIT 1').fetchone()[0], 'Story & title 1')

    def test_invalid_series_cannot_replace_either_chart(self):
        with patch.object(feeds, 'log'):
            feeds.refresh_top_250(self.db, self.get, self.now)
            previous = self.snapshot()
            self.files['movies'][0]['name'] = 'New movie name from partially loaded pair'
            self.files['shows'].pop()
            with self.assertRaisesRegex(feeds.FeedError, 'incomplete-top-250'):
                feeds.refresh_top_250(self.db, self.get, self.now)
            self.assertEqual(self.snapshot(), previous)
            self.assertEqual(self.db.execute("SELECT name FROM member WHERE type='movie' AND rank=1").fetchone()[0],
                             'Story & title 1')

    def test_rejects_invalid_links_types_duplicates_rank_and_old_publication(self):
        first = self.files['shows'][0].copy()
        cases = [({'Rank':2}, 'invalid-top-250-rank'), ({'isEpisode':True}, 'invalid-top-250-type'),
                 ({'titleType':'Video Game'}, 'invalid-top-250-type'), ({'link':'https://example.org/'}, 'invalid-top-250-link'),
                 ({'link':self.files['movies'][0]['link']}, 'duplicate-top-250-id')]
        for bad, why in cases:
            with self.subTest(bad=bad):
                self.files['shows'][0] = {**first, **bad}
                with self.assertRaisesRegex(feeds.FeedError, why):
                    feeds.refresh_top_250(self.db, self.get, self.now)
                self.assertIsNone(self.snapshot())
        with self.assertRaisesRegex(feeds.FeedError, 'stale-top-250-publication'):
            feeds.refresh_top_250(self.db, self.get, self.now+8*feeds.DAY)


class ProviderSampleTests(unittest.TestCase):
    now = int(datetime.fromisoformat('2026-11-24T09:00:00+00:00').timestamp())
    revision = 'a' * 40
    def response(self, url, params=None):
        if params:
            self.assertEqual(params, {'path':'site/catalog','sha':'main','per_page':1})
            return [{'sha': self.revision, 'commit': {'committer': {'date': '2026-11-24T06:00:00Z'}}}]
        self.assertIn('/' + self.revision + '/site/catalog/', url)
        if '/movie/' in url:
            return {'metas':[nuvio_row('tt1234567', 'movie')]}
        return {'metas':[nuvio_row('tmdb:42', 'series')]}

    def test_sample_precedence_and_publication(self):
        with closing(feeds.database(':memory:')) as db, patch.object(feeds, 'log'):
            feeds.publish(db, 'netflix', feeds.US_SOURCE, [feeds.watch_member(watch_row(1), 1)], self.now)
            source = Mock(side_effect=self.response)
            self.assertTrue(feeds.provider_samples(db, source, self.now))
            self.assertEqual(source.call_count, 9)
            self.assertEqual(db.execute('SELECT source FROM feed WHERE key=?', ('netflix',)).fetchone()[0], feeds.US_SOURCE)
            self.assertEqual(db.execute('SELECT count(*) FROM feed WHERE source=?', (feeds.SAMPLE_SOURCE,)).fetchone()[0], 4)
            self.assertEqual(len(feeds.provider_keys(db, self.now)), 4)
            self.assertTrue(feeds.provider_samples(db, source, self.now))
            self.assertEqual(source.call_count, 10)  # Unchanged publication needs only one request.
            feeds.expire(db, self.now + 15*feeds.DAY)
            self.assertEqual(db.execute('SELECT key FROM feed').fetchall(), [('netflix',)])

    def test_invalid_second_half_preserves_previous_feed(self):
        def inconsistent(url, params=None):
            if '/series/' in url:
                return {'metas': []}
            return self.response(url, params)
        with closing(feeds.database(':memory:')) as db, patch.object(feeds, 'log'):
            m = feeds.chart_member(nuvio_row('tt1234567', 'series'), 'series', 1)
            feeds.publish(db, 'hulu', feeds.SAMPLE_SOURCE, [m], self.now-2*feeds.DAY)
            before = db.execute('SELECT * FROM feed').fetchall()
            self.assertFalse(feeds.provider_samples(db, inconsistent, self.now))
            self.assertEqual(db.execute('SELECT * FROM feed').fetchall(), before)


class ChartTests(unittest.TestCase):
    manifest = {('series', 'popular-series'): {'skip': {}, 'genre': {'options': ['Soap']}}}

    def get_chart(self, get):
        return feeds.chart_entries(get, self.manifest, 'series', 'popular-series', 'Soap', pause=lambda _: None)

    def test_sparse_pages_past_100_and_source_boundary(self):
        slots = []
        def request(url):
            self.assertIn('genre=Soap', unquote(url))
            m = re.search(r'skip=(\d+)', url)
            skip = int(m[1]) if m else 0
            slots.append(skip)
            rows = {0: [nuvio_row('tt1234567')], 200: [nuvio_row('tt1234567'), nuvio_row('tmdb:42')],
                    4980: [nuvio_row('other:2')]}.get(skip, [])
            return {'cacheMaxAge': 300 if skip >= 5000 else 21600, 'metas': rows}

        members, gaps = self.get_chart(request)
        self.assertEqual(gaps, [])
        self.assertEqual(slots, list(range(0, 5040, 20)))
        self.assertEqual([(m.source_id, m.rank, m.year) for m in members],
                         [('tt1234567', 1, 1993), ('tmdb:42', 202, 1993), ('other:2', 4981, 1993)])
        self.assertEqual((members[1].tmdb_id, members[1].tmdb_type), (42, 'tv'))
        self.assertIsNone(members[2].imdb_id)

    def test_short_cache_empty_interior_pages_are_revalidated_and_recorded(self):
        visits = {}
        def request(url):
            m = re.search(r'skip=(\d+)', url)
            skip = int(m[1]) if m else 0
            visits[skip] = 1 + visits.get(skip, 0)
            if skip == 0 or (skip == 20 and visits[skip] == 2):
                return {'cacheMaxAge': 21600, 'metas': [nuvio_row(f'native:{skip}')]}
            return {'cacheMaxAge': 300 if skip in (20, 40) or skip >= 5000 else 21600, 'metas': []}

        members, gaps = self.get_chart(request)
        self.assertEqual([m.rank for m in members], [1, 21])
        self.assertEqual(gaps, [('series', 40)])
        self.assertEqual([visits[20], visits[40]], [2, 2])

    def test_protocol_errors_not_mistaken_for_empty(self):
        cases = [({'cacheMaxAge': 300, 'metas': []}, 'empty-chart'),
                 ({'metas': []}, 'unknown-empty-page'), ({'error': 'bad'}, 'invalid-chart-page'),
                 ({'metas': [dict(nuvio_row('tmdb:1'), genres=['Drama'])]}, 'genre-filter-ignored'),
                 ({'metas': [nuvio_row(f'tmdb:{i}') for i in range(1, 6)]}, 'repeated-chart-page')]
        for response, reason in cases:
            with self.subTest(reason=reason), self.assertRaisesRegex(feeds.FeedError, reason):
                self.get_chart(Mock(return_value=response))

    def test_region_change_is_a_failure(self):
        for region in ['NL', None]:
            with self.assertRaisesRegex(feeds.FeedError, 'default-region'):
                feeds.chart_manifest(lambda url: {'id': 'com.nuvio.tmdb.catalogs',
                                                    'config': [{'key': 'region', 'default': region}]})


class StorageTests(unittest.TestCase):
    def test_atomic_replacement_retention_and_expiry(self):
        with TemporaryDirectory() as temp:
            path = Path(temp) / 'catalog.db'
            with closing(feeds.database(path)) as writer, closing(sqlite3.connect(path.as_uri()+'?mode=ro', uri=True)) as reader:
                now = 100 * feeds.DAY
                m = feeds.watch_member(watch_row(1), 1)
                feeds.publish(writer, 'netflix', feeds.US_SOURCE, [m], now)
                feeds.publish(writer, 'popular', feeds.CHART_SOURCE, [m], now)
                def info():
                    return reader.execute("SELECT feed.snapshot,updated_at,source_id FROM member JOIN feed USING(key) "
                                          "WHERE key='netflix'").fetchall()
                old = info()
                with self.assertRaises(sqlite3.IntegrityError):
                    feeds.publish(writer, 'netflix', feeds.US_SOURCE, [m,m], now+1)
                self.assertEqual(info(), old)
                feeds.expire(writer, now + 28*feeds.DAY)
                self.assertEqual(info(), old)
                feeds.expire(writer, now + 30*feeds.DAY)
                self.assertEqual(info(), [])
                self.assertEqual(reader.execute('SELECT key FROM member').fetchall(), [('popular',)])
                with self.assertRaises(sqlite3.OperationalError):
                    reader.execute('DELETE FROM member')

    def test_optional_key_and_native_lookup_index(self):
        with closing(feeds.database(':memory:')) as db, patch.object(feeds, 'log'):
            self.assertFalse(feeds.providers(db, None))
            self.assertTrue(feeds.providers(db, None, optional=True))
            plan = db.execute("EXPLAIN QUERY PLAN SELECT imdb_id FROM member WHERE type='series' "
                              "AND tmdb_id=42 AND imdb_id IS NOT NULL").fetchall()
            self.assertIn('member_tmdb_imdb', str(plan))
            member = feeds.chart_member(nuvio_row('tmdb:12'), 'series', 1)
            feeds.publish(db, 'popular', feeds.CHART_SOURCE, [member], 1, [('series', 20)])
            self.assertEqual(db.execute('SELECT key,type,skip FROM chart_gap').fetchall(), [('popular','series',20)])
            feeds.publish(db, 'popular', feeds.CHART_SOURCE, [member], 2)
            self.assertEqual(db.execute('SELECT key FROM chart_gap').fetchall(), [])

    def test_retry_skips_todays_completed_charts(self):
        with closing(feeds.database(':memory:')) as db:
            first = int(datetime(2020, 1, 15, 23, 30).timestamp())
            db.execute('INSERT INTO feed VALUES (?, ?, ?, ?)', ('popular', 'x', first, feeds.CHART_SOURCE))
            self.assertNotIn('popular', feeds.due_charts(db, first + 600))
            self.assertIn('trending', feeds.due_charts(db, first + 600))
            self.assertIn('popular', feeds.due_charts(db, first + 3600))

    def test_rotation_seeds_and_catches_up(self):
        with closing(feeds.database(':memory:')) as db:
            now = 100*feeds.DAY
            self.assertEqual(feeds.provider_keys(db, now), list(feeds.PROVIDERS))
            with db:
                db.executemany('INSERT INTO feed VALUES (?, ?, ?, ?)',
                               [(k, 'x', now, feeds.US_SOURCE) for k in feeds.PROVIDERS])
            self.assertEqual(feeds.provider_keys(db, now+150), [])
            self.assertEqual(set(feeds.provider_keys(db, now+feeds.DAY)), set(feeds.PROVIDERS)-{'prime-video'})
            self.assertEqual(set(feeds.provider_keys(db, now+2*feeds.DAY)), set(feeds.PROVIDERS))
            self.assertEqual(feeds.provider_keys(db, now+6*feeds.DAY), list(feeds.PROVIDERS))


if __name__ == '__main__':
    unittest.main()
