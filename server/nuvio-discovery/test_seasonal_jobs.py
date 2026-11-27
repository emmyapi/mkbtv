from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from discovery import atomic_json
import seasonal_jobs as jobs


class SeasonalPreparationTests(unittest.TestCase):
    def test_changed_rule_refreshes_active_theme_before_saved_deadline(self):
        active, upcoming = 'dinner-is-served', 'christmas-favorites'
        schedule = {'theme': active, 'upcoming': [{'theme': upcoming}]}
        future = time.time() + 86400
        with tempfile.TemporaryDirectory() as temp, patch.object(jobs, 'preview', return_value=schedule), \
                patch.object(jobs, 'refresh_theme', return_value={'next_check': future}) as refresh:
            root = Path(temp)
            atomic_json(root / 'seasonal' / 'work.json', {
                active: {'last': 10, 'due': future},
                upcoming: {'last': 1, 'due': future, 'revision': 'old'}
            })
            self.assertEqual(list(jobs.prepare(root)), [active])
            self.assertEqual(list(jobs.prepare(root)), [upcoming])
            self.assertEqual(jobs.prepare(root), {})
            self.assertEqual(refresh.call_count, 2)
