"""Synthetic preservation and validation tests; no account or network access."""
import importlib.util
import json
from datetime import datetime, timedelta
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Client:
    api_url = 'https://backend.example.invalid'

    def __init__(self, fail=False):
        self.logged_out = False
        self.fail = fail
        self.user_id = 'synthetic-owner'

    def login(self):
        pass

    def logout(self):
        self.logged_out = True

    def rpc(self, name, params):
        if name == 'sync_pull_profiles':
            return []
        if self.fail:
            raise m.BackupError('HTTP_503')
        if name == 'sync_pull_library':
            return [{'content_id': 'tt123', 'content_type': 'movie', 'name': 'Synthetic'}]
        if name == 'sync_pull_watched_items':
            return [{'content_id': 'tt123', 'content_type': 'movie', 'watched_at': 1234567890123}]
        if name == 'sync_pull_watch_progress':
            return [{'id': 'row1', 'user_id': self.user_id, 'profile_id': params['p_profile_id'],
                     'content_id': 'tt456', 'content_type': 'movie', 'position': 12001, 'duration': 50000}]
        raise AssertionError(name)

    def progress_count(self, profile):
        return 1


class Tests(unittest.TestCase):
    def profile_path(self, folder):
        generation = json.loads((folder / 'manifest.json').read_text())['generation']
        return folder / 'generations' / generation / 'profile-1.json'

    def test_init_offline_creates_only_requested_empty_profiles_once(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict('os.environ', {}, clear=True):
            folder = Path(directory)
            with patch.object(m, 'Client', side_effect=AssertionError('unexpected account access')):
                m.initialize(folder, [3, 1])
            expected = {'library': 0, 'watched': 0, 'progress': 0}
            self.assertEqual(m.verify(folder)['verified_profiles'],
                             [{'profile_id': p, 'counts': expected} for p in (1, 3)])
            previous = (folder / 'manifest.json').read_bytes()
            with self.assertRaisesRegex(m.BackupError, 'HISTORY_ALREADY_EXISTS'):
                m.initialize(folder, [2])
            self.assertEqual(previous, (folder / 'manifest.json').read_bytes())

    def test_export_uses_operator_backend_and_requires_own_credentials(self):
        with patch.dict('os.environ', {}, clear=True):
            with self.assertRaisesRegex(m.BackupError, 'NUVIO_API_URL_MISSING'):
                m.Client()
            for url in ('http://example.invalid', 'https://name@example.invalid', 'https://example.invalid/path'):
                with patch.dict('os.environ', {'NUVIO_API_URL': url}):
                    with self.assertRaisesRegex(m.BackupError, 'NUVIO_API_URL_INVALID'):
                        m.Client()
            with patch.dict('os.environ', {'NUVIO_API_URL': Client.api_url}):
                with self.assertRaisesRegex(m.BackupError, 'NUVIO_PUBLIC_KEY_MISSING'):
                    m.Client()
                with patch.dict('os.environ', {'NUVIO_PUBLIC_KEY': 'test'}):
                    client = m.Client()
                    self.assertEqual(client.api_url, Client.api_url)
                    with patch.object(client, 'request') as request:
                        with self.assertRaisesRegex(m.BackupError, 'NUVIO_EMAIL_MISSING'):
                            client.login()
                        with patch.dict('os.environ', {'NUVIO_EMAIL': 'test@example.invalid'}):
                            with self.assertRaisesRegex(m.BackupError, 'NUVIO_PASSWORD_MISSING'):
                                client.login()
                        request.assert_not_called()

    def test_export_verify_preserves_timestamp_and_milliseconds(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            client = Client()
            m.export(folder, client)
            self.assertTrue(client.logged_out)
            self.assertTrue(m.verify(folder)['ok'])
            data = json.loads(self.profile_path(folder).read_text())
            self.assertEqual(data['watched'][0]['watched_at'], 1234567890123)
            self.assertEqual(data['progress'][0]['position'], 12001)
            self.assertEqual(self.profile_path(folder).stat().st_mode & 0o777, 0o600)

    def test_failed_capture_preserves_previous_export_and_logs_out(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, Client())
            old = self.profile_path(folder).read_bytes()
            failed = Client(fail=True)
            with self.assertRaises(m.BackupError):
                m.export(folder, failed)
            self.assertTrue(failed.logged_out)
            self.assertEqual(self.profile_path(folder).read_bytes(), old)
            self.assertTrue(m.verify(folder)['ok'])

    def test_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, Client())
            path = self.profile_path(folder)
            data = json.loads(path.read_text())
            data['watched'][0]['watched_at'] += 1
            path.write_text(json.dumps(data))
            with self.assertRaisesRegex(m.BackupError, 'DIGEST'):
                m.verify(folder)

    def test_ambiguous_progress_does_not_publish(self):
        client = Client()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, client)
            previous = (folder / 'manifest.json').read_bytes()
            original = client.rpc
            row = original('sync_pull_watch_progress', {'p_profile_id': 1})[0]
            for expected, batch in [(201, [row] * 200), (0, [row]), (2, [row, row]),
                                    (1, [{**row, 'user_id': 'different'}]),
                                    (1, [{**row, 'profile_id': 2}])]:
                client.progress_count = lambda profile: expected
                client.rpc = lambda name, params: (batch if name == 'sync_pull_watch_progress'
                                                   else original(name, params))
                with self.assertRaisesRegex(m.BackupError, 'PROGRESS_'):
                    m.export(folder, client)
                self.assertEqual(previous, (folder / 'manifest.json').read_bytes())
                self.assertTrue(client.logged_out)

    def test_counted_progress_over_200_and_empty_profile(self):
        client = Client()
        for expected in [229, 0]:
            rows = [{'id': str(i), 'user_id': client.user_id, 'profile_id': 2} for i in range(expected)]
            client.progress_count = lambda profile: expected
            def rpc(name, params):
                self.assertEqual(name, 'sync_pull_watch_progress')
                self.assertEqual(params, {'p_profile_id': 2, 'p_limit': expected + 1})
                return rows
            client.rpc = rpc
            self.assertEqual(m.progress_rows(client, 2), rows)

    def test_range_must_supply_exact_progress_count(self):
        with patch.dict('os.environ', {'NUVIO_API_URL': Client.api_url, 'NUVIO_PUBLIC_KEY': 'test'}):
            client = m.Client()
        for header, expected in [('*/0', 0), ('0-0/229', 229)]:
            with patch.object(client, 'request', return_value=header):
                self.assertEqual(client.progress_count(1), expected)
        for header in ['', '0-0/*', '*/200', '0-0/0']:
            with patch.object(client, 'request', return_value=header):
                with self.assertRaisesRegex(m.BackupError, 'PROGRESS_COUNT_INVALID'):
                    client.progress_count(1)

    def test_repeated_page_and_profile_identity_are_rejected(self):
        client = Client()
        client.rpc = lambda name, params: [{'content_id': 'tt1'}] * m.PAGE_SIZE
        with self.assertRaisesRegex(m.BackupError, 'REPEATED'):
            m.pages(client, 'read', 1, numbered=True)
        for rows in ([{'profile_index': None}], [{'profile_index': True}],
                     [{'profile_index': 1}, {'profile_index': 1}]):
            with self.assertRaises(m.BackupError):
                m.profile_ids(rows)

    def test_nested_auth_payload_and_unsafe_manifest_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, Client())
            path = self.profile_path(folder)
            data = json.loads(path.read_text())
            data['library'][0]['metadata'] = {'access_token': 'synthetic'}
            with self.assertRaisesRegex(m.BackupError, 'UNAPPROVED'):
                m.validate(data)
            manifest_path = folder / 'manifest.json'
            manifest = json.loads(manifest_path.read_text())
            manifest['profiles'][0]['file'] = '../outside.json'
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(m.BackupError, 'PATH'):
                m.verify(folder)

    def test_empty_or_missing_previous_profile_does_not_replace_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, Client())
            previous = (folder / 'manifest.json').read_bytes()
            for missing_profile in (False, True):
                client = Client()
                old = client.rpc
                def rpc(name, params):
                    if name == 'sync_pull_profiles' and missing_profile:
                        return [{'profile_index': 2}]
                    if name == 'sync_pull_watched_items' and not missing_profile:
                        return []
                    return old(name, params)
                client.rpc = rpc
                with self.assertRaises(m.BackupError):
                    m.export(folder, client)
                self.assertEqual((folder / 'manifest.json').read_bytes(), previous)

    def test_failed_generation_write_keeps_previous_manifest_and_files(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, Client())
            previous = (folder / 'manifest.json').read_bytes()
            real = m.atomic_json
            def interrupted(path, data):
                if path.name == 'manifest.json':
                    raise OSError('synthetic disk full')
                return real(path, data)
            with patch.object(m, 'atomic_json', side_effect=interrupted):
                with self.assertRaises(OSError):
                    m.export(folder, Client())
            self.assertEqual((folder / 'manifest.json').read_bytes(), previous)
            self.assertTrue(m.verify(folder)['ok'])

    def test_export_strips_urls_account_ids_and_auth_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            client = Client()
            old = client.rpc
            def rpc(name, params):
                result = old(name, params)
                for row in result:
                    row.update({'addon_base_url': 'https://example.invalid/token/secret',
                                'user_id': client.user_id, 'headers': {'Authorization': 'secret'},
                                'access_token': 'synthetic'})
                return result
            client.rpc = rpc
            m.export(folder, client)
            raw = self.profile_path(folder).read_text()
            self.assertNotIn('secret', raw)
            self.assertNotIn(client.user_id, raw)

    def test_retention_keeps_24_months_and_unknown_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            m.export(folder, Client())
            current = json.loads((folder / 'manifest.json').read_text())
            source = self.profile_path(folder)
            data = json.loads(source.read_text())
            # 36 historical monthly generations, each internally valid.
            for year in (2020, 2021, 2022):
                for month in range(1, 13):
                    gen = folder / 'generations' / f'{year}{month:02d}01T000000.000000Z'
                    gen.mkdir()
                    digest = m.atomic_json(gen / 'profile-1.json', data)
                    manifest = {k: v for k, v in current.items() if k != 'generation'}
                    manifest['profiles'][0]['sha256'] = digest
                    m.atomic_json(gen / 'manifest.json', manifest)
            # Recent daily copies must not displace the older monthly copies.
            today = datetime.strptime(current['generation'], '%Y%m%dT%H%M%S.%fZ')
            recent = []
            for offset in range(1, 46):
                name = (today - timedelta(days=offset)).strftime('%Y%m%dT%H%M%S.%fZ')
                gen = folder / 'generations' / name
                gen.mkdir()
                digest = m.atomic_json(gen / 'profile-1.json', data)
                manifest = {k: v for k, v in current.items() if k != 'generation'}
                manifest['profiles'][0]['sha256'] = digest
                m.atomic_json(gen / 'manifest.json', manifest)
                recent.append(name)
            unknown = folder / 'generations' / 'unknown-recovery'
            unknown.mkdir()
            (unknown / 'keep').write_text('synthetic recovery')
            m.retain_generations(folder)
            complete = [path for path in (folder / 'generations').iterdir()
                        if m.GENERATION.fullmatch(path.name)]
            retained = {path.name for path in complete}
            self.assertEqual(len({name[:6] for name in retained}), 24)
            self.assertTrue(set(recent[:30]).issubset(retained))
            self.assertNotIn('20200101T000000.000000Z', retained)
            self.assertTrue(unknown.exists())
            self.assertTrue(m.verify(folder)['ok'])


if __name__ == '__main__':
    unittest.main()
