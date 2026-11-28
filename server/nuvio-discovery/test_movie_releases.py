from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import threading
import time
import unittest
from unittest.mock import Mock, patch

from movie_releases import MovieReleases, ProviderError, fetch_dates, typed_dates


def page(number='12', entries=()):
    body = f'<head><link rel="canonical" href="https://www.themoviedb.org/movie/{number}-name/releases"></head>'
    for country, medium, day, *rating in entries:
        body += (f'<table class="card releases"><h2 id="{country}"></h2>'
                 f'<tr><td>{day}</td><td>{rating[0] if rating else ""}</td><td>{medium}</td><td></td></tr></table>')
    return body.encode()


def utc(day):
    return datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp()


class OfficialDates(unittest.TestCase):
    def test_worldwide_minimum_per_type_and_exact_title(self):
        entries = [('US', 'Premiere', '01/01/2025'), ('GB', 'TV', '02/01/2025'),
                   ('FI', 'Digital', '12/30/2026'), ('FR', 'Theatrical', '11/04/2026'),
                   ('DK', 'Physical', '12/20/2026'), ('DE', 'Theatrical (limited)', '11/01/2026')]
        self.assertEqual(['2026-11-01', '2026-12-20', None], typed_dates(page(entries=entries), '12'))
        self.assertEqual([None, None, None], typed_dates(page(entries=entries[:2]), '12'))
        with self.assertRaises(ProviderError):
            typed_dates(page('123', entries), '12')
        with self.assertRaises(ProviderError):
            typed_dates(page('12'), '12')
        with self.assertRaises(ProviderError):
            typed_dates(page(entries=[*entries, ('GB', 'Digital', 'broken')]), '12')

    def test_original_us_theatrical_certificate_uses_the_same_page_as_worldwide_dates(self):
        entries = [('DK', 'Theatrical', '06/01/2024', '15'), ('US', 'Digital', '08/29/2024', 'R'),
                   ('US', 'Theatrical', '06/14/2024', 'PG-13')]
        self.assertEqual(['2024-06-01', '2024-08-29', 'PG-13'], typed_dates(page(entries=entries), '12'))
        self.assertEqual(['2024-06-01', None, None], typed_dates(page(entries=entries[:1]), '12'))

    def test_exact_imdb_crosswalk_and_native_ids(self):
        provider = Mock()
        provider.request.return_value = ['2026-01-02', None, None]
        card = {'type': 'movie', 'id': 'tt1', 'moviedb_id': '12'}
        provider.card.return_value = card
        self.assertEqual(('12', 'tt1', '2026-01-02', None, None), fetch_dates(provider, 'tt1'))
        self.assertIn('/movie/12/releases?', provider.request.call_args.args[0])
        for invalid in (None, dict(card, type='series'), dict(card, id='tt2'),
                        dict(card, id='tmdb:13', imdb_id='tt1'), dict(card, moviedb_id='01')):
            provider.card.return_value = invalid
            with self.assertRaises(ProviderError):
                fetch_dates(provider, 'tt1')
        provider.card.reset_mock()
        self.assertEqual(('12', None, '2026-01-02', None, None), fetch_dates(provider, 'tmdb:12'))
        provider.card.assert_not_called()

    def test_native_first_links_only_an_explicit_matching_tmdb_movie(self):
        provider = Mock()
        provider.request.return_value = [None, '2000-01-01', None]
        card = {'id': 'tt1', 'name': 'Movie', 'type': 'movie'}
        links = [{'category': 'source', 'url': 'https://www.themoviedb.org/movie/12'},
                 {'category': 'source', 'url': 'https://www.imdb.com/title/tt1/'}]
        for meta, expected in [(dict(card, links=links), 'tt1'), (card, None),
                               (dict(card, links=links, moviedb_id='13'), None),
                               (dict(card, links=links, imdb_id='tt2'), None),
                               (dict(card, links=links, type='series'), None)]:
            provider.load.return_value = {'meta': meta}
            self.assertEqual(('12', expected, None, '2000-01-01', None), fetch_dates(provider, 'tmdb:12'))
        provider.load.side_effect = ProviderError('offline')
        self.assertEqual(('12', None, None, '2000-01-01', None), fetch_dates(provider, 'tmdb:12'))


class ReleaseCache(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache = MovieReleases(self.root, catalog_dir=self.root)
        self.addCleanup(self.cache.stop)

    def test_migration_keeps_existing_dates_and_schedules_certification_refresh(self):
        with TemporaryDirectory() as other:
            root = Path(other)
            with closing(sqlite3.connect(root / 'movie-releases.sqlite3')) as db:
                db.executescript("""
                    CREATE TABLE movie_release(tmdb_id TEXT PRIMARY KEY,imdb_id TEXT UNIQUE,theatrical_date TEXT,home_date TEXT);
                    CREATE TABLE movie_release_check(content_id TEXT PRIMARY KEY,next_check INTEGER NOT NULL);
                    INSERT INTO movie_release VALUES ('12','tt1','2020-01-01',NULL);
                    INSERT INTO movie_release_check VALUES ('tt1',9999999999);
                """)
            migrated = MovieReleases(root)
            self.assertEqual('cinema', migrated.lookup(['tt1'], enqueue=False)['releases']['tt1']['status'])
            with patch('movie_releases.fetch_dates', return_value=('12', 'tt1', '2020-01-01', None, 'PG')):
                migrated._refresh('tt1')
            self.assertEqual('PG', migrated.lookup(['tt1'])['releases']['tt1']['ageRating'])

    def test_future_changes_on_the_day_across_restart_without_refresh(self):
        facts = ('12', 'tt1', '2026-11-20', '2026-12-03', 'PG-13')
        with patch('movie_releases.fetch_dates', return_value=facts) as fetch:
            with patch('movie_releases.time.time', return_value=utc('2026-11-01')):
                self.cache._refresh('tt1')
                self.cache._refresh('tmdb:12')
                self.assertEqual(1, fetch.call_count)
            cache = MovieReleases(self.root)
            for day, status in [('2026-11-19', 'upcoming'), ('2026-11-20', 'cinema'), ('2026-12-03', 'home')]:
                with patch('movie_releases.time.time', return_value=utc(day)):
                    result = cache.lookup(['tt1', 'tmdb:12', 'tt2'])
                    item = {'cinemaDate': facts[2], 'homeDate': facts[3], 'ageRating': 'PG-13', 'status': status}
                    self.assertEqual({'releases': {'tt1': item, 'tmdb:12': item, 'tt2': {'status': 'unknown'}},
                                      'pending': False}, result)

    def test_failure_retains_evidence_and_successful_correction_replaces_it(self):
        facts = ('12', 'tt1', '2020-01-01', None, None)
        with patch('movie_releases.fetch_dates', side_effect=[facts, ProviderError('down'),
                                                             facts, ('12', 'tt1', None, '2019-11-01', None)]) as fetch:
            for day, expected in [('2026-11-01', 'cinema'), ('2026-11-02', 'cinema'),
                                  ('2026-11-03', 'cinema'), ('2026-11-04', 'home')]:
                with patch('movie_releases.time.time', return_value=utc(day)):
                    self.cache._refresh('tt1')
                    self.cache._refresh('tt1')
                    self.assertEqual(expected, self.cache.lookup(['tt1'])['releases']['tt1']['status'])
            self.assertEqual(4, fetch.call_count)

    def test_native_record_can_gain_an_alias_without_transferring_conflicting_facts(self):
        with patch('movie_releases.fetch_dates', side_effect=[
                ('12', None, None, '2000-01-01', None), ('12', 'tt1', None, '2000-01-01', None),
                ('12', 'tt2', '2020-11-01', None, None)]):
            for uid in ('tmdb:12', 'tt1', 'tt2'):
                self.cache._refresh(uid)
        result = self.cache.lookup(['tt1', 'tmdb:12', 'tt2'])
        self.assertEqual('home', result['releases']['tt1']['status'])
        self.assertEqual(result['releases']['tt1'], result['releases']['tmdb:12'])
        self.assertEqual({'status': 'unknown'}, result['releases']['tt2'])

    def test_cache_only_read_does_not_enqueue_watch_filtered_ids(self):
        self.cache.start()
        with patch('movie_releases.fetch_dates') as fetch:
            result = self.cache.lookup(['tt1234'], enqueue=False)
            self.assertEqual({'releases': {'tt1234': {'status': 'unknown'}}, 'pending': False}, result)
            self.assertFalse(self.cache.pending)
            fetch.assert_not_called()

    def test_background_request_returns_immediately_coalesces_and_bounds_work(self):
        entered, allow = threading.Event(), threading.Event()
        self.addCleanup(allow.set)

        def delayed(_provider, uid):
            entered.set()
            if not allow.wait(timeout=2):
                raise RuntimeError('lookup should return before the provider')
            return uid[2:], uid, None, '2000-01-01', None

        with patch('movie_releases.fetch_dates', side_effect=delayed) as fetch, patch('movie_releases.MAX_PENDING', 2):
            self.cache.start()
            self.assertTrue(self.cache.lookup(['tt1', 'tt1', 'tt2', 'tt3'])['pending'])
            self.assertTrue(entered.wait(timeout=1))
            self.assertTrue(self.cache.lookup(['tt3'])['pending'])
            for _ in range(3):
                cached = self.cache.lookup(['tt1', 'tt2', 'tt3'])
                self.assertEqual({'status': 'unknown'}, cached['releases']['tt1'])
                self.assertTrue(cached['pending'])
            self.assertEqual({'tt1', 'tt2'}, self.cache.pending)
            allow.set()
            deadline = time.monotonic() + 2
            while self.cache.lookup(['tt1', 'tt2'])['pending'] and time.monotonic() < deadline:
                time.sleep(0.005)
            self.assertEqual('home', self.cache.lookup(['tt2'])['releases']['tt2']['status'])
            self.cache.stop()
            self.assertEqual(2, fetch.call_count)

    def test_prewarms_only_public_movie_chart_ids_and_catalog_leaders(self):
        with closing(sqlite3.connect(self.root / 'feeds.sqlite3')) as db:
            db.executescript("""
                CREATE TABLE member(key,type,tmdb_type,imdb_id,tmdb_id,source_id,rank);
                INSERT INTO member VALUES ('popular','movie','movie',NULL,123,'x',1),
                  ('popular','series','tv','tt11',11,'tt11',1), ('trending','movie',NULL,'tt10',NULL,'tt10',1);
            """)
        with closing(sqlite3.connect(self.root / 'catalog.sqlite3')) as db:
            db.executescript("""
                CREATE TABLE title(id,type,adult,imdb_id,votes);
                INSERT INTO title VALUES (4,'movie',0,'tt4',500),(3,'movie',1,'tt3',600);
            """)
        self.assertEqual(['tt10', 'tmdb:123', 'tt4'], list(self.cache._seeds()))


if __name__ == '__main__':
    unittest.main()
