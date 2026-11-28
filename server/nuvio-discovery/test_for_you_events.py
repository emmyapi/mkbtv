"""Exercise For You publication through the same session, export and catalog paths as the TV."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout
from datetime import datetime, timezone
import hashlib
from http.client import HTTPConnection
from io import StringIO
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import discovery
import for_you_pool


def card(kind, uid):
    return {'type': kind, 'id': uid, 'name': f'Title {uid}', 'genres': ['Drama']}


def prepared_pool(root, document, excluded_ids=(), **kwargs):
    # The event tests have a stable reserve, regardless of upstream availability or pagination.
    watches = discovery.watched_unique(document)
    return {
        'candidates': [dict(meta=card('series' if i % 5 == 0 else 'movie', f'tt{i}'), strength=1., interests=['Drama'],
                            sources=[{'seed': 'movie/tt1', 'basis': 'tmdb', 'rank': 1}])
                       for i in range(1001, 1161)],
        'interests': {'Drama': 1.}, 'watched': {f'{k}/{uid}': at for (k, uid), at in watches.items()},
        'aliases': {'series/tmdb:855': 'series/tt55'},
        'progress': [f"series/{entry['content_id']}" for entry in document.get('progress', []) if entry['position'] > 0],
        'cursor': {}, 'matched': len(watches), 'distinct': len(watches),
    }


def exported_watch(kind, uid, at, episode=None):
    row = {'content_type': kind, 'content_id': uid, 'watched_at': at}
    if episode is not None:
        row.update(season=1, episode=episode)
    return row


class ForYouEventTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name) / 'discovery'
        self.history = Path(temp.name) / 'history'
        self.now = int(time.time() * 1000)
        self.generation = 0
        self.exports = {p: [exported_watch('movie', f'tt{p}', self.now - 86_400_000)] for p in (1, 2)}
        self.progress = {1: [], 2: []}
        self.write_export()
        self.real_pool = for_you_pool.prepare_pool
        self.pool_builder = self.enterContext(patch.object(for_you_pool, 'prepare_pool', side_effect=prepared_pool))
        self.enterContext(patch.dict(os.environ, {'MKBTV_CATALOG_DIR': str(Path(temp.name) / 'catalog')}))
        self.enterContext(patch.object(discovery, 'urlopen', side_effect=AssertionError('outbound request')))
        self.enterContext(patch.object(discovery, 'metadata', side_effect=lambda _, kind, uid: card(kind, uid)))
        self.enterContext(patch.object(discovery, 'tmdb_related', side_effect=lambda _, seed:
                                     [(seed['type'], 'tt9' + seed['id'][2:])]))
        self.enterContext(patch.object(discovery, 'related', return_value=[]))
        def catalog(_, kind, source, genre=None, *, skip=0):
            start = 1001 if kind == 'movie' else 2001
            return [card(kind, f'tt{i}') for i in range(start, start + 40)]
        self.enterContext(patch.object(discovery, 'catalog', side_effect=catalog))
        self.nightly()
        self.tokens = json.loads((self.root / 'config.json').read_text())['tokens']
        self.start_server()
        self.addCleanup(self.stop_server)

    def write_export(self):
        self.generation += 1
        capture = datetime.fromtimestamp((self.now + self.generation) / 1000, timezone.utc)
        generation = capture.strftime('%Y%m%dT%H%M%S.%fZ')
        records = []
        for profile, watched in self.exports.items():
            path = self.history / 'generations' / generation / f'profile-{profile}.json'
            discovery.atomic_json(path, {'format': 'nuvio-profile-backup-v2', 'profile_id': profile,
                                         'watched': watched, 'progress': self.progress[profile], 'library': [],
                                         'capture_finished_at': capture.isoformat()})
            records.append({'profile_id': profile, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                            'counts': {'watched': len(watched), 'progress': len(self.progress[profile]), 'library': 0}})
        discovery.atomic_json(self.history / 'manifest.json', {'format': 'nuvio-backup-manifest-v1',
                                                              'generation': generation, 'profiles': records})

    def nightly(self):
        with redirect_stdout(StringIO()):
            discovery.refresh(self.root, self.history)

    def start_server(self):
        self.server = discovery.ThreadingHTTPServer(('127.0.0.1', 0), discovery.handler_for(self.root, self.history))
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
        self.thread.start()

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)

    def request(self, path, payload=None, profile=1):
        conn = HTTPConnection(*self.server.server_address, timeout=5)
        try:
            method = 'GET' if payload is None else 'POST'
            conn.request(method, f'/{self.tokens[str(profile)]}/{path}',
                         body=None if payload is None else json.dumps(payload),
                         headers={'Content-Type': 'application/json'})
            response = conn.getresponse()
            value = json.loads(response.read())
            self.assertEqual(response.status, 200, value)
            return value
        finally:
            conn.close()

    def post(self, visit, watches=(), profile=1):
        # Android sends just the newest completion per whole title, regardless of episode count.
        return self.request('session.json', {'session': visit, 'watched': list(watches)}, profile)

    def row(self, row_id='for-you', profile=1):
        return [m['id'] for m in self.request(f'catalog/mixed/{row_id}.json', profile=profile)['metas']]

    def first_poster(self, kind):
        return next(m['id'] for m in self.request('catalog/mixed/for-you.json')['metas'] if m['type'] == kind)

    def assert_full_rotation(self, previous, current):
        self.assertEqual(len(previous), 60)
        self.assertEqual(len(set(current)), 60)
        self.assertEqual(len(current), 60)
        self.assertTrue(set(current[:10]).isdisjoint(previous))
        self.assertEqual(len(set(current).difference(previous)), 30)

    def test_same_night_reopen_detail_retry_profile_and_service_restart(self):
        self.post('family-room', profile=2)
        other_profile = self.row(profile=2)
        first = self.post('first-visit')
        initial = self.row()
        popular = self.row('popular-now')
        because = self.row('because-watched')
        self.assertEqual(self.post('first-visit')['configVersion'], first['configVersion'])
        self.assertEqual(self.row(), initial)  # Detail, Home, repeated manifest and catalog requests.
        reopened = self.post('reopened-immediately')
        self.assertGreater(reopened['configVersion'], first['configVersion'])
        rotated = self.row()
        self.assert_full_rotation(initial, rotated)
        self.assertEqual(self.row('popular-now'), popular)
        self.assertEqual(self.row('because-watched'), because)
        self.assertEqual(self.row(profile=2), other_profile)

        self.stop_server()
        self.start_server()
        self.nightly()
        version = self.request('manifest.json')['configVersion']
        delayed = self.post('first-visit')  # A delayed request from the earlier visit.
        self.assertEqual(delayed['configVersion'], version)
        self.assertEqual(self.post('reopened-immediately')['configVersion'], version)
        self.assertEqual(self.row(), rotated)
        self.assertEqual(self.row(profile=2), other_profile)

    def test_two_completed_episodes_and_duplicate_delivery_from_two_devices(self):
        self.post('one')
        self.post('two')
        before = self.row()
        episode_one = {'type': 'series', 'id': 'tt55', 'at': self.now - 2000}
        episode_two = dict(episode_one, at=self.now - 1000)

        self.post('one', [episode_one])
        after_one = self.row()
        self.assert_full_rotation(before, after_one)
        self.post('two', [episode_one])
        self.assertEqual(self.row(), after_one)
        self.assertEqual(self.row('because-watched'), ['tt955'])
        self.post('two', [dict(episode_one, id='tmdb:855')])
        self.assertEqual(self.row(), after_one)

        # Concurrent retries share both the published profile and its event history on disk.
        barrier = threading.Barrier(2, timeout=5)
        def send(visit):
            barrier.wait()
            return self.post(visit, [episode_two])
        with ThreadPoolExecutor(max_workers=2) as clients:
            list(clients.map(send, ('one', 'two')))
        after_two = self.row()
        self.assert_full_rotation(after_one, after_two)
        self.post('one', [episode_one])  # Delayed older device snapshot.
        self.post('two', [episode_two])
        self.assertEqual(self.row(), after_two)
        path = self.root / 'data' / 'catalogs.json'
        exposed = len(json.loads(path.read_text())['profiles']['1']['for_you']['exposure']['events'])
        self.post('new-visit', [dict(episode_two, at=self.now)])  # Offline completion arrives on reopen.
        self.assert_full_rotation(after_two, self.row())
        events = json.loads(path.read_text())['profiles']['1']['for_you']['exposure']['events']
        self.assertEqual(len(events), exposed + 1)

    def test_delayed_same_visit_snapshot_preserves_later_episode_until_unmarked(self):
        first = {'type': 'series', 'id': 'tt55', 'at': self.now - 3000}
        second = dict(first, at=self.now - 1000)
        self.post('tv', [first])
        latest = self.post('tv', [second])
        before = self.row()
        stale = self.post('tv', [first])
        self.assertEqual(stale['configVersion'], latest['configVersion'])
        self.assertEqual(self.row(), before)
        path = self.root / 'data' / 'catalogs.json'
        profile = json.loads(path.read_text())['profiles']['1']
        self.assertEqual(profile['seasonal_history']['series/tt55'], second['at'])
        self.post('tv', [])
        profile = json.loads(path.read_text())['profiles']['1']
        self.assertNotIn('series/tt55', profile['seasonal_history'])

    def test_nightly_export_catches_up_offline_watches_and_undo(self):
        self.post('tv')
        first = self.row()
        completed = {'type': 'movie', 'id': self.first_poster('movie'), 'at': self.now - 3000}
        self.post('tv', [completed])
        after_watch = self.row()
        self.assert_full_rotation(first, after_watch)
        self.assertNotIn(completed['id'], after_watch)

        self.exports[1].append(exported_watch('movie', completed['id'], completed['at']))
        self.write_export()
        self.nightly()  # Account sync acknowledged the already-consumed completion.
        self.post('tv', [completed])
        self.assertEqual(self.row(), after_watch)

        self.exports[1].extend([exported_watch('series', 'tt55', self.now - 2000, 1),
                                exported_watch('series', 'tt55', self.now - 1000, 2)])
        self.write_export()
        self.nightly()
        self.assertEqual(self.row(), after_watch)
        self.post('tv', [completed])  # The active TV still has its older local snapshot.
        after_export = self.row()
        self.assert_full_rotation(after_watch, after_export)
        self.post('tv', [dict(type='series', id='tt55', at=self.now - 1000), completed])
        self.assertEqual(self.row(), after_export)

        self.exports[1] = self.exports[1][:-2]  # An export can retract a watched mark.
        self.write_export()
        self.nightly()
        self.post('tv', [completed])
        self.assertEqual(self.row(), after_export)

    def test_partial_episode_progress_refills_one_card_without_rotating(self):
        self.post('tv')
        before = self.row()
        active = self.first_poster('series')
        self.progress[1] = [{'content_type': 'series', 'content_id': active, 'season': 1, 'episode': 1,
                             'position': 120_000, 'duration': 1_500_000, 'last_watched': self.now}]
        self.write_export()
        self.nightly()
        self.post('tv')
        after = self.row()
        self.assertNotIn(active, after)
        self.assertEqual(len(after), 60)
        self.assertEqual([uid for uid in after if uid in before], [uid for uid in before if uid != active])

    def test_explicit_unwatch_of_exported_movie_survives_retry_and_newer_rewatch(self):
        self.pool_builder.side_effect = self.real_pool
        watched = {'type': 'movie', 'id': 'tt5003', 'at': self.now - 3000}
        undo = dict(watched, at=self.now - 2000)
        self.exports[3] = [exported_watch('movie', 'tt88', self.now - 86_400_000),
                           exported_watch('movie', watched['id'], watched['at'])]
        self.progress[3] = []
        self.write_export()
        def catalog(_, kind, source, genre=None, *, skip=0):
            return [card(kind, f'tt{i}') for i in range(5001, 5006)] if kind == 'movie' else []
        self.enterContext(patch.object(discovery, 'catalog', side_effect=catalog))
        self.enterContext(patch.object(discovery, 'tmdb_related', return_value=[]))
        self.nightly()
        self.tokens = json.loads((self.root / 'config.json').read_text())['tokens']
        self.post('tv', [watched], profile=3)
        before = self.row(profile=3)
        self.assertEqual(len(before), 4)
        self.assertNotIn(watched['id'], before)

        def exposure_count():
            published = json.loads((self.root / 'data' / 'catalogs.json').read_text())
            return len(published['profiles']['3']['for_you']['exposure']['events'])
        exposed = exposure_count()
        payload = {'session': 'tv', 'watched': [], 'seasonalUnwatched': [undo]}
        self.request('session.json', payload, profile=3)
        undone = self.row(profile=3)
        self.assertEqual(len(undone), 5)
        self.assertEqual([uid for uid in undone if uid != watched['id']], before)
        self.assertEqual(exposure_count(), exposed)

        self.stop_server()
        self.start_server()
        self.nightly()
        self.request('session.json', payload, profile=3)
        self.post('tv', [watched], profile=3)  # A stale positive cannot reverse the explicit undo.
        self.assertEqual(self.row(profile=3), undone)
        self.assertEqual(exposure_count(), exposed)
        self.post('tv', [dict(watched, at=self.now - 1000)], profile=3)
        self.assertNotIn(watched['id'], self.row(profile=3))
        self.assertEqual(exposure_count(), exposed + 1)

    def test_existing_catalog_without_for_you_state_upgrades_on_visit(self):
        path = self.root / 'data' / 'catalogs.json'
        published = json.loads(path.read_text())
        # Old catalogs only carried the current rows, last UUID and merged history.
        old_fields = ('profile_id', 'rows', 'genre_pool', 'genre_source', 'revision', 'summary',
                      'client_snapshot', 'live_watched', 'history_signature', 'session',
                      'seasonal_history', 'seasonal_progress', 'exported_count', 'source_capture')
        published['profiles']['1'] = {k: v for k, v in published['profiles']['1'].items() if k in old_fields}
        published['profiles']['1']['rows'][0]['metas'] = [card('movie', 'tt999')]
        discovery.atomic_json(path, published)
        self.assertEqual(self.row(), ['tt999'])
        self.post('upgrade')
        migrated = self.row()
        self.assertEqual(len(set(migrated)), 60)
        self.post('upgrade')
        self.assertEqual(self.row(), migrated)
        self.assertEqual(self.row('because-watched'), ['tt91'])

    def test_completed_watch_uses_the_prepared_pool_during_related_service_outage(self):
        self.post('tv')
        before = self.row()
        completed = {'type': 'movie', 'id': self.first_poster('movie'), 'at': self.now - 1000}
        with patch.object(discovery, 'build_profile', side_effect=discovery.DiscoveryError('UPSTREAM_UNAVAILABLE')):
            pending = self.post('tv', [completed])
            self.assertIn('householdRetryAfterMs', pending)
            offline = self.row()
            self.assert_full_rotation(before, offline)
            self.assertNotIn(completed['id'], offline)
        self.post('tv', [completed])
        self.assertEqual(self.row(), offline)
        self.assertEqual(self.row('because-watched'), ['tt9' + completed['id'][2:]])


if __name__ == '__main__':
    unittest.main()
