from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import discovery
import for_you


class ForYouJobTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.catalog = self.root / 'data' / 'catalogs.json'
        self.profiles = {str(i): {'profile_id': i, 'rows': [], 'seasonal_history': {f'movie/tt{i}': i},
                                  'seasonal_progress': ['tt88']} for i in (1, 2)}
        discovery.atomic_json(self.catalog, {'profiles': self.profiles})
        discovery.atomic_json(self.root / 'config.json', {'excluded_seed_ids': {'1': ['tt99']}})

    def test_background_cursor_resumes_and_failure_isolated_between_profiles(self):
        before = self.catalog.read_bytes()
        calls = []
        def batch(root, doc, profile, excluded, *, cursor, background):
            pid = doc['profile_id']
            self.assertTrue(background)
            self.assertEqual(doc['watched'], [{'content_id': f'tt{pid}', 'content_type': 'movie', 'watched_at': pid}])
            self.assertEqual(doc['progress'][0]['content_id'], 'tt88')
            self.assertEqual(excluded, ['tt99'] if pid == 1 else [])
            calls.append((pid, cursor.get('history', 0)))
            if pid == 1 and len(calls) == 3:
                raise discovery.DiscoveryError('public source unavailable')
            return {'cursor': {'history': cursor.get('history', 0) + 4}, 'candidates': [], 'matched': 1, 'distinct': 1}
        with patch.object(for_you, 'pool_for', side_effect=batch):
            for_you.prepare(self.root)
            with self.assertLogs('for_you', 'WARNING'):
                self.assertEqual(for_you.prepare(self.root)[0]['status'], 'retry')
            for_you.prepare(self.root)
        self.assertEqual(calls, [(1, 0), (2, 0), (1, 4), (2, 4), (1, 4), (2, 8)])
        self.assertEqual(before, self.catalog.read_bytes())
        (self.root / 'for-you' / 'profiles' / '1.json').write_text('{truncated')
        with patch.object(for_you, 'pool_for', side_effect=batch):
            for_you.prepare(self.root)
        self.assertEqual(calls[-2:], [(1, 0), (2, 12)])

    def test_preview_is_read_only_and_uses_selection_evidence(self):
        cards = [{'id': f'tt{i}', 'type': 'movie', 'name': f'Film {i}'} for i in range(101, 211)]
        first = cards[:60]
        self.profiles['1']['rows'] = [{'id': 'for-you', 'metas': first}]
        discovery.atomic_json(self.catalog, {'profiles': self.profiles})
        reason = {'seed': 'movie/tt1', 'basis': 'tmdb', 'rank': 1}
        pool = {'candidates': [{'meta': card, 'strength': 1.2, 'interests': [], 'sources': [reason]} for card in cards],
                'progress': [], 'aliases': {}, 'watched': {'movie/tt1': 1}, 'matched': 1, 'distinct': 1}
        before = self.catalog.read_bytes()
        with patch.object(for_you, 'pool_for', return_value=pool):
            summary = for_you.preview(self.root, 1, 3)
            detail = for_you.preview(self.root, 1, 1, details=True)
        self.assertEqual(summary['visits'], [{'size': 60, 'arrivals': 30, 'front_arrivals': 10, 'rewatches': 0}] * 3)
        self.assertEqual(detail['visits'][0]['cards'][0]['sources'], [reason])
        self.assertEqual(before, self.catalog.read_bytes())
        self.assertFalse((self.root / 'for-you').exists())
