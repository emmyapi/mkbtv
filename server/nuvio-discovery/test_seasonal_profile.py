from contextlib import nullcontext
from datetime import datetime, timezone
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

import discovery
import seasonal_profile as seasonal


def instant(month, day):
    return datetime(2027, month, day, 12, tzinfo=timezone.utc)


def candidate(uid, score=2, kind='movie', refs=(), release='', via=()):
    return {'meta': {'id': uid, 'type': kind, 'name': uid}, 'score': score,
            'refs': list(refs), 'release': release, 'via': list(via)}


class SeasonalSelectionTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.base = {'profile_id': 1, 'revision': 5, 'rows': [], 'seasonal_history': {}}
        self.save()
        self.config = {'tokens': {'1': 'test', '2': 'other'}}
        self.theme = seasonal.THEMES['christmas-favorites']
        self.original = [r['id'] for r in self.theme['references']]
        self.curated = [candidate(r['id'], 5, r['type']) for r in (*self.theme['references'], *self.theme['reserves'])]
        self.movies = [candidate(f'tt{9000000+i}', 7, refs=[self.original[i%8]]) for i in range(20)]
        self.pool = {'editorial': self.curated, 'dynamic': self.movies, 'complete': True, 'generation': 'one'}
        self.read = self.enter(patch.object(seasonal, 'read_pool', return_value=self.pool))
        self.guard = self.enter(patch.object(seasonal, 'pool_guard', side_effect=lambda *_: nullcontext(True)))
        self.shown = self.enter(patch.object(seasonal, 'record_shown'))

    def enter(self, mock):
        result = mock.start()
        self.addCleanup(mock.stop)
        return result

    def save(self):
        discovery.atomic_json(self.root/'data'/'catalogs.json',
                              {'profiles': {'1': self.base, '2': dict(self.base, profile_id=2)}})

    def view(self, at=None, profile='1', **updates):
        session = {'session': 'visit', 'watched': [], **updates}
        return seasonal.profile_view(self.root, self.config, profile, session, at or instant(12, 24))

    @staticmethod
    def ids(row):
        return [m['id'] for m in row['metas']] if row else []

    def test_membership_and_order_and_watched_seed_still_counts(self):
        _, row, _, version = self.view()
        before = self.ids(row)
        self.assertIn(before[0], [m['meta']['id'] for m in self.movies])
        self.assertEqual(len(before), 16)
        self.assertEqual(set(before) & set(self.original), set(self.original))
        watched = [{'type': 'movie', 'id': self.original[0], 'at': seasonal.millis(instant(12, 23))}]
        _, refill, _, next_version = self.view(seasonalWatched=watched)
        after = self.ids(refill)
        self.assertEqual(len(after),16)
        self.assertGreater(next_version, version)
        self.assertNotIn(self.original[0], after)
        self.assertEqual(after[before.index(self.original[0])], self.curated[8]['meta']['id'])
        self.assertEqual(len(set(after) & set(before)), 15)
        self.assertEqual(self.view(seasonalWatched=watched)[3], next_version)
        self.assertEqual(self.ids(self.view(profile='2')[1]), before)
        self.pool['dynamic'] = [candidate('tt9999999', refs=[self.original[0]]),
                                candidate('tt9999998', refs=['tt12345'])]
        reduced = self.ids(self.view()[1])
        self.assertIn('tt9999999', reduced)
        self.assertNotIn('tt9999998', reduced)

    def test_reference_affinity_survives_an_unavailable_poster_and_respects_exclusions(self):
        self.curated[0]['meta']['moviedb_id'] = '72'
        self.view()
        self.pool['editorial'] = []
        self.pool['dynamic'] = [candidate('tt9888880', score=2.2, refs=[self.original[1]]),
                                candidate('tt9888881', refs=[self.original[0]])]
        event = {'type':'movie','id':'tmdb:72','at':seasonal.millis(instant(12,23))}
        self.assertEqual(self.ids(self.view(session='new',seasonalWatched=[event])[1])[0], 'tt9888881')
        self.config['excluded_seed_ids'] = {'1': ['tmdb:72']}
        self.assertEqual(self.ids(self.view(session='another')[1])[0], 'tt9888880')

    def test_visible_films_are_in_the_watch_sync_scope_even_with_a_large_pool(self):
        self.pool['dynamic'] = [candidate(f'tt{9000000+i}', score=i, refs=[self.original[i % 8]])
                                for i in range(550)]
        _, row, info, _ = self.view()
        self.assertEqual(len(info['seasonalMovieIds']), 512)
        self.assertIn('tt9000549', self.ids(row))
        visible_movies = {meta['id'] for meta in row['metas'] if meta['type'] == 'movie'}
        self.assertTrue(visible_movies <= set(info['seasonalMovieIds']))

    def test_new_release_enters_open_full_shelf(self):
        self.movies[0]['release'] = '2027-12-02'
        old = self.ids(self.view()[1])
        self.movies.append(candidate('tt9900888', 8, refs=[self.original[1]], release='2027-12-24'))
        current = self.ids(self.view()[1])
        self.assertEqual(len(set(old) & set(current)), 15)
        self.assertIn('tt9900888', current)

    def test_partial_empty_exhaustion_and_date_switch(self):
        self.pool['editorial'] = []
        self.pool['dynamic'] = self.movies[:2]
        before = self.ids(self.view()[1])
        self.assertEqual(len(before),2)
        mark = lambda uid: {'type':'movie','id':uid,'at':seasonal.millis(instant(12,23))}
        self.assertEqual(self.ids(self.view(seasonalWatched=[mark(before[0])])[1]), before[1:])
        self.assertIsNone(self.view(seasonalWatched=[mark(before[1])])[1])
        self.pool['dynamic'] = self.movies
        self.assertIsNone(self.view(at=instant(12,26))[1])
        self.assertEqual(self.read.call_args.args[1], 'christmas-favorites')
        _, _, info, _ = self.view(at=instant(12,27))
        self.assertEqual(self.read.call_args.args[1], 'one-last-movie-before-new-year')
        self.assertTrue(info['seasonalOccurrence'])

    def test_one_missing_replacement_then_a_new_release_restores_sixteen(self):
        self.pool['dynamic'] = self.movies[:8]
        before = self.ids(self.view()[1])
        self.assertEqual(len(before), 16)
        watch = {'type': 'movie', 'id': self.movies[0]['meta']['id'], 'at': seasonal.millis(instant(12, 23))}
        reduced = self.ids(self.view(seasonalWatched=[watch])[1])
        self.assertEqual(reduced, [uid for uid in before if uid != watch['id']])
        premiere = candidate('tt9900200', refs=[self.original[0]], release='2027-12-24')
        self.pool['dynamic'].append(premiere)
        after = self.ids(self.view()[1])
        self.assertEqual(len(after), 16)
        self.assertEqual([uid for uid in after if uid != premiere['meta']['id']], reduced)

    def test_previous_market_exhaustion_reopens_without_losing_watches(self):
        self.pool.update(editorial=[], dynamic=self.movies[:1])
        mark = {'type': 'movie', 'id': self.movies[0]['meta']['id'], 'at': seasonal.millis(instant(12, 23))}
        self.assertIsNone(self.view(seasonalWatched=[mark])[1])
        path = self.root / 'seasonal' / 'profiles' / '1.json'
        state = json.loads(path.read_text())
        self.assertTrue(state['closed'])
        del state['release_scope']  # Profile persisted by the Denmark-only build.
        discovery.atomic_json(path, state)
        self.pool['dynamic'] = self.movies[:2]
        self.assertEqual(self.ids(self.view()[1]), [self.movies[1]['meta']['id']])
        self.assertIsNone(self.view(seasonalWatched=[dict(mark, id=self.movies[1]['meta']['id'])])[1])
        self.pool['dynamic'] = self.movies[:3]
        self.assertIsNone(self.view()[1])

    def test_unknown_empty_recovers_and_progress_only_retires_series(self):
        self.pool.update(editorial=[], dynamic=[], complete=False)
        self.assertIsNone(self.view()[1])
        self.pool.update(editorial=self.curated, dynamic=self.movies)
        series = next(i['id'] for i in self.theme['references'] if i['type']=='series')
        _, row, info, _ = self.view(seasonalInProgress=[series])
        self.assertEqual(len(self.ids(row)),16)
        self.assertNotIn(series,self.ids(row))
        self.assertIn(series,info['seasonalSeriesIds'])
        # A second device may not yet have received the first device's Continue Watching update.
        self.assertNotIn(series, self.ids(self.view(seasonalInProgress=[])[1]))

    def test_alias_undo_overrules_export_and_stale_retry(self):
        item = self.movies[0]
        item['meta']['moviedb_id'] = '72'
        self.pool['dynamic'] = [item]
        uid = item['meta']['id']
        at = seasonal.millis(instant(12,22))
        self.base['seasonal_history'] = {'movie/tmdb:72': at}
        self.save()
        self.assertNotIn(uid,self.ids(self.view()[1]))
        event = {'type':'movie','id':uid,'at':at+1}
        self.assertIn(uid,self.ids(self.view(seasonalUnwatched=[event])[1]))
        self.assertIn(uid,self.ids(self.view(seasonalWatched=[dict(event,at=at)])[1]))

    def test_overlap_exhaustion_does_not_fall_through(self):
        self.pool.update(editorial=[],dynamic=[])
        _, row, info, _ = self.view(at=instant(6,4))
        self.assertIsNone(row)
        self.assertEqual(self.read.call_args.args[1], 'made-in-denmark')
        self.assertIn('seasonalValidUntil',info)
        self.read.reset_mock()
        self.view(at=instant(6,6))
        self.read.assert_not_called()
        self.view(at=instant(6,7))
        self.assertEqual(self.read.call_args.args[1], 'pride-on-screen')

    def test_catalog_change_during_last_card_selection_does_not_latch_stale_exhaustion(self):
        self.pool.update(editorial=[], dynamic=[], complete=True)
        def changed(*_):
            if not self.pool['dynamic']:
                self.pool.update(dynamic=self.movies[:1], generation='two')
                return nullcontext(False)
            return nullcontext(True)
        self.guard.side_effect=changed
        self.assertEqual(len(self.ids(self.view()[1])),1)
        self.assertEqual(self.guard.call_count,2)

    def test_movie_never_revived_by_duplicate_alias_or_episode(self):
        row = self.curated[0]['meta']
        row['moviedb_id']='14'
        self.pool['dynamic'] += [candidate('tmdb:14', score=100, refs=self.original),
                                 candidate('tt8409876',score=100,kind='series',refs=self.original)]
        ids = self.ids(self.view()[1])
        self.assertEqual(len(ids),16)
        self.assertNotIn('tmdb:14',ids)
        self.assertNotIn('tt8409876',ids)

    def test_remote_recommendation_outage_preserves_watch_for_seasonal(self):
        doc = {'format': 'nuvio-profile-backup-v2', 'profile_id': 1, 'watched': [], 'progress': [],
               'capture_finished_at': '2027-12-24T12:00:00Z'}
        session = {'session': 'visit', 'watched': [{'type': 'movie', 'id': self.original[0],
                                                    'at': seasonal.millis(instant(12, 23))}]}
        old = dict(self.base, rows=[{'id': 'for-you', 'name': 'For You', 'metas': []}])
        with patch.object(discovery, 'build_profile', side_effect=discovery.DiscoveryError('UPSTREAM_UNAVAILABLE')):
            self.base = discovery.update_profile(self.root, doc, old, session, [])
        self.assertTrue(self.base['recommendations_pending'])
        self.save()
        self.assertNotIn(self.original[0], self.ids(self.view()[1]))

    def test_http_payload_and_revision_agree(self):
        # Exercise the real route, including the winner chosen by its current clock.
        with patch.object(seasonal, 'datetime', wraps=datetime) as clock:
            clock.now.return_value=instant(12,24)
            status, manifest=discovery.response_for(self.root,self.config,'/test/manifest.json')
            self.assertEqual(status,200)
            self.assertEqual(manifest['catalogs'][0], {'id':'seasonal','type':'mixed','name':'Christmas Favorites'})
            _, row=discovery.response_for(self.root,self.config,'/test/catalog/mixed/seasonal.json')
            for field in ('seasonalOccurrence','seasonalValidUntil','configVersion'):
                self.assertEqual(manifest[field],row[field])
            self.assertEqual(len(row['metas']),16)
            self.assertEqual(row['staleError'],0)
            clock.now.return_value=instant(1,8)
            _, gap=discovery.response_for(self.root,self.config,'/test/catalog/mixed/seasonal.json')
            self.assertEqual(gap['metas'],[])
