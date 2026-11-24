#!/usr/bin/env python3
"""Read-only Nuvio history export. Private payloads never go to stdout."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from uuid import UUID

HISTORY = Path(os.environ.get('MKBTV_STATE_DIR', Path.home() / '.local/share/mkbtv')) / 'history'
PAGE_SIZE = 500
FORMAT = 'nuvio-profile-backup-v2'
FIELDS = {
    'library': {'content_id', 'content_type', 'name', 'genres', 'added_at', 'release_info'},
    'watched': {'content_id', 'content_type', 'title', 'season', 'episode', 'watched_at'},
    'progress': {'content_id', 'content_type', 'season', 'episode', 'position', 'duration',
                 'last_watched', 'video_id'},
}
GENERATION = re.compile(r'^\d{8}T\d{6}\.\d{6}Z$')


class BackupError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise BackupError('HTTP_REDIRECT_REFUSED')


def now():
    return datetime.now(timezone.utc).isoformat()


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        raise BackupError('UNSAFE_DIRECTORY')
    path.chmod(0o700)


def required_env(name):
    value = os.environ.get(name)
    if not value:
        raise BackupError(name + '_MISSING')
    return value


class Client:
    def __init__(self):
        self.api_url = required_env('NUVIO_API_URL').rstrip('/')
        origin = urlsplit(self.api_url)
        if (origin.scheme != 'https' or not origin.hostname or origin.username is not None
                or origin.password is not None or origin.path or origin.query or origin.fragment):
            raise BackupError('NUVIO_API_URL_INVALID')
        self.public_key = required_env('NUVIO_PUBLIC_KEY')
        self.token = None
        self.user_id = None
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, payload=None, *, count_only=False):
        headers = {'apikey': self.public_key, 'Content-Type': 'application/json',
                   'User-Agent': 'mkbtv-backup/1'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if count_only:
            headers['Prefer'] = 'count=exact'
        body = None if count_only else json.dumps(payload or {}).encode()
        request = urllib.request.Request(self.api_url + path, data=body, headers=headers,
                                         method='HEAD' if count_only else 'POST')
        try:
            with self.opener.open(request, timeout=30) as response:
                content_range = response.headers.get('Content-Range', '')
                raw = response.read(16 * 1024 * 1024 + 1)
            if len(raw) > 16 * 1024 * 1024:
                raise BackupError('RESPONSE_TOO_LARGE')
            data = json.loads(raw) if raw else None
            return content_range if count_only else data
        except urllib.error.HTTPError as error:
            raise BackupError('HTTP_' + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError):
            raise BackupError('NETWORK_FAILURE') from None
        except (ValueError, UnicodeError):
            raise BackupError('INVALID_JSON') from None

    def login(self):
        data = self.request('/auth/v1/token?grant_type=password',
                            {'email': required_env('NUVIO_EMAIL'), 'password': required_env('NUVIO_PASSWORD')})
        if not isinstance(data, dict) or not isinstance(data.get('access_token'), str):
            raise BackupError('AUTH_RESPONSE_INVALID')
        self.token = data['access_token']
        try:
            self.user_id = str(UUID(data['user']['id']))
        except (KeyError, TypeError, ValueError, AttributeError):
            raise BackupError('AUTH_USER_INVALID') from None

    def logout(self):
        if self.token:
            try:
                self.request('/auth/v1/logout?scope=local')
            finally:
                self.token = None
                self.user_id = None

    def progress_count(self, profile):
        path = (f'/rest/v1/watch_progress?select=id&user_id=eq.{self.user_id}'
                f'&profile_id=eq.{profile}&limit=1')
        content_range = self.request(path, count_only=True)
        if content_range == '*/0':
            return 0
        match = re.fullmatch(r'0-0/([1-9]\d*)', content_range)
        if not match:
            raise BackupError('PROGRESS_COUNT_INVALID')
        return int(match.group(1))

    def rpc(self, name, params):
        data = self.request('/rest/v1/rpc/' + name, params)
        if not isinstance(data, list) or not all(isinstance(row, dict) for row in data):
            raise BackupError('RPC_SCHEMA_INVALID')
        return data


def profile_ids(rows):
    if not rows:
        return [1]  # Nuvio's implicit primary profile.
    ids = [row.get('profile_index') for row in rows]
    if any(type(value) is not int or value < 1 or value > 6 for value in ids):
        raise BackupError('PROFILE_SCHEMA_INVALID')
    if len(set(ids)) != len(ids):
        raise BackupError('DUPLICATE_PROFILE')
    return sorted(ids)


def pages(client, name, profile, *, numbered):
    result, seen = [], set()
    for index in range(200):
        params = {'p_profile_id': profile}
        params.update({'p_page': index + 1, 'p_page_size': PAGE_SIZE} if numbered else
                      {'p_offset': index * PAGE_SIZE, 'p_limit': PAGE_SIZE})
        batch = client.rpc(name, params)
        if batch:
            digest = hashlib.sha256(json.dumps(batch, sort_keys=True).encode()).hexdigest()
            if digest in seen:
                raise BackupError('PAGINATION_REPEATED')
            seen.add(digest)
        result.extend(batch)
        if len(batch) < PAGE_SIZE:
            return result
    raise BackupError('PAGINATION_LIMIT')


def progress_rows(client, profile):
    expected = client.progress_count(profile)
    batch = client.rpc('sync_pull_watch_progress', {'p_profile_id': profile, 'p_limit': expected + 1})
    if len(batch) != expected:
        raise BackupError('PROGRESS_COUNT_MISMATCH')
    seen = set()
    for row in batch:
        row_id = row.get('id')
        if not isinstance(row_id, str) or not row_id or row_id in seen:
            raise BackupError('PROGRESS_ID_INVALID_OR_REPEATED')
        if row.get('user_id') != client.user_id or row.get('profile_id') != profile:
            raise BackupError('PROGRESS_OWNER_OR_PROFILE_MISMATCH')
        seen.add(row_id)
    return batch


def validate(document):
    if document.get('format') != FORMAT or type(document.get('profile_id')) is not int:
        raise BackupError('BACKUP_SCHEMA_INVALID')
    if document['profile_id'] not in range(1, 7):
        raise BackupError('BACKUP_PROFILE_INVALID')
    for field in ('capture_started_at', 'capture_finished_at'):
        try:
            if datetime.fromisoformat(document[field]).tzinfo is None:
                raise ValueError()
        except (KeyError, TypeError, ValueError):
            raise BackupError('BACKUP_TIME_INVALID') from None
    for field in ('library', 'watched', 'progress'):
        rows = document.get(field)
        if not isinstance(rows, list) or not all(isinstance(row, dict) and
                isinstance(row.get('content_id'), str) and row['content_id'] for row in rows):
            raise BackupError('BACKUP_ROWS_INVALID')
        for row in rows:
            if set(row) - FIELDS[field]:
                raise BackupError('UNAPPROVED_FIELD')
            if row.get('content_type') not in {'movie', 'series', 'anime'}:
                raise BackupError('CONTENT_TYPE_INVALID')
            for key, value in row.items():
                if key == 'genres':
                    if value is not None and (not isinstance(value, list) or
                            not all(isinstance(genre, str) for genre in value)):
                        raise BackupError('GENRES_INVALID')
                elif value is not None and type(value) not in (str, int, float):
                    raise BackupError('FIELD_TYPE_INVALID')
            for key in ('content_id', 'video_id'):
                value = row.get(key)
                if value is not None and (not isinstance(value, str) or
                        not re.fullmatch(r'[A-Za-z0-9_:.-]+', value)):
                    raise BackupError('CONTENT_ID_INVALID')
    # Refuse accidentally included authentication payloads, recursively.
    def check(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key.lower() in {'password', 'access_token', 'refresh_token', 'api_key', 'authorization'}:
                    raise BackupError('CREDENTIAL_FIELD_REFUSED')
                check(item)
        elif isinstance(value, list):
            for item in value:
                check(item)
    check(document)
    return {name: len(document[name]) for name in ('library', 'watched', 'progress')}


def capture(client, profile):
    document = {'format': FORMAT, 'source': client.api_url, 'profile_id': profile,
                'capture_started_at': now(),
                'timestamp_note': 'Source timestamps preserved; some historical dates may be inferred. '
                                  'Latest watched state cannot reconstruct every previous play.',
                'consistency': 'Sequential read-only API calls; not a server-side transaction.'}
    document['library'] = pages(client, 'sync_pull_library', profile, numbered=False)
    document['watched'] = pages(client, 'sync_pull_watched_items', profile, numbered=True)
    document['progress'] = progress_rows(client, profile)
    # Library responses also contain account identifiers and addon URLs, which can
    # contain credentials. None of those belong in a portable viewing backup.
    for field, allowed in FIELDS.items():
        document[field] = [{key: value for key, value in row.items() if key in allowed}
                           for row in document[field]]
    document['capture_finished_at'] = now()
    validate(document)
    return document


def atomic_json(path, document):
    data = (json.dumps(document, ensure_ascii=False, indent=2) + '\n').encode()
    fd, name = tempfile.mkstemp(prefix='.pending-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(name).unlink(missing_ok=True)
    return hashlib.sha256(data).hexdigest()


def preserve_previous(output, documents):
    path = output / 'manifest.json'
    if not path.exists():
        return
    previous = json.loads(path.read_text())
    current = {document['profile_id']: validate(document) for document in documents}
    for entry in previous['profiles']:
        if entry['profile_id'] not in current:
            raise BackupError('PREVIOUS_PROFILE_MISSING')
        for category in ('library', 'watched'):
            if entry['counts'][category] and not current[entry['profile_id']][category]:
                raise BackupError('PREVIOUS_NONEMPTY_DATA_NOW_EMPTY')


def retain_generations(output):
    """Keep latest per day for 31 days and per month for 24 months."""
    generations = output / 'generations'
    current = json.loads((output / 'manifest.json').read_text())['generation']
    valid = []
    for path in generations.iterdir():
        if path.is_symlink() or not GENERATION.fullmatch(path.name) or not path.is_dir():
            continue
        try:
            verify(path)
            manifest = json.loads((path / 'manifest.json').read_text())
            names = {'manifest.json', *(entry['file'] for entry in manifest['profiles'])}
            if {child.name for child in path.iterdir()} != names or any(
                    child.is_symlink() or not child.is_file() for child in path.iterdir()):
                continue
        except (OSError, ValueError, KeyError, BackupError):
            continue  # Unknown/incomplete generations are preserved for inspection.
        valid.append(path)
    daily, monthly, keep = set(), set(), {current}
    for path in sorted(valid, key=lambda item: item.name, reverse=True):
        day, month = path.name[:8], path.name[:6]
        if day not in daily and len(daily) < 31:
            daily.add(day)
            keep.add(path.name)
        if month not in monthly and len(monthly) < 24:
            monthly.add(month)
            keep.add(path.name)
    for path in valid:
        if path.name not in keep:
            for child in path.iterdir():
                child.unlink()
            path.rmdir()


@contextmanager
def locked_output(output):
    private_directory(output)
    lock = os.open(output / '.export.lock', os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(lock, 'w') as locked:
        try:
            fcntl.flock(locked, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise BackupError('EXPORT_ALREADY_RUNNING') from None
        yield


def publish(output, documents):
    # Nothing is replaced until every profile was fetched and validated.
    preserve_previous(output, documents)
    private_directory(output / 'generations')
    generation = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    destination = output / 'generations' / generation
    destination.mkdir(mode=0o700)
    entries = []
    for document in documents:
        filename = 'profile-' + str(document['profile_id']) + '.json'
        digest = atomic_json(destination / filename, document)
        entries.append({'profile_id': document['profile_id'], 'file': filename,
                        'sha256': digest, 'counts': validate(document)})
    manifest = {'format': 'nuvio-backup-manifest-v1', 'completed_at': now(), 'profiles': entries}
    atomic_json(destination / 'manifest.json', manifest)
    # The only public commit point: a crash during generation writes leaves the
    # previous manifest and its immutable files intact.
    atomic_json(output / 'manifest.json', {**manifest, 'generation': generation})
    retain_generations(output)
    return {'ok': True, 'profiles': entries}


def export(output, client=None):
    client = client or Client()
    with locked_output(output):
        try:
            client.login()
            profiles = profile_ids(client.rpc('sync_pull_profiles', {}))
            documents = [capture(client, profile) for profile in profiles]
        finally:
            client.logout()  # Only this exporter session; never global logout.
        return publish(output, documents)


def initialize(output, profiles):
    """Seed a local-only installation with an empty history for each selected TV profile."""
    ids = profile_ids([{'profile_index': p} for p in profiles])
    with locked_output(output):
        if (output / 'manifest.json').exists():
            raise BackupError('HISTORY_ALREADY_EXISTS')
        stamp = now()
        return publish(output, [
            {'format': FORMAT, 'source': 'local', 'profile_id': p, 'capture_started_at': stamp,
             'capture_finished_at': stamp, 'library': [], 'watched': [], 'progress': []}
            for p in ids
        ])


def verify(directory, max_age_hours=None):
    manifest = json.loads((directory / 'manifest.json').read_text())
    if manifest.get('format') != 'nuvio-backup-manifest-v1' or not manifest.get('profiles'):
        raise BackupError('MANIFEST_INVALID')
    if max_age_hours is not None:
        finished = datetime.fromisoformat(manifest['completed_at'])
        age = (datetime.now(timezone.utc) - finished).total_seconds()
        if age > max_age_hours * 3600 or age < -600:
            raise BackupError('BACKUP_STALE_OR_FUTURE')
    if 'generation' in manifest:
        generation = manifest['generation']
        if not isinstance(generation, str) or not GENERATION.fullmatch(generation):
            raise BackupError('GENERATION_PATH_INVALID')
        directory = directory / 'generations' / generation
    checked = []
    for entry in manifest['profiles']:
        if type(entry.get('profile_id')) is not int or entry['profile_id'] not in range(1, 7):
            raise BackupError('MANIFEST_PROFILE_INVALID')
        expected = 'profile-' + str(entry['profile_id']) + '.json'
        if entry['file'] != expected:
            raise BackupError('MANIFEST_PATH_INVALID')
        raw = (directory / expected).read_bytes()
        document = json.loads(raw)
        counts = validate(document)
        if (hashlib.sha256(raw).hexdigest() != entry['sha256'] or
                document['profile_id'] != entry['profile_id'] or counts != entry['counts']):
            raise BackupError('BACKUP_DIGEST_MISMATCH')
        checked.append({'profile_id': entry['profile_id'], 'counts': counts})
    return {'ok': True, 'verified_profiles': checked}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'export', 'verify'])
    parser.add_argument('--directory', type=Path, default=HISTORY)
    parser.add_argument('--max-age-hours', type=float)
    parser.add_argument('--profiles', type=int, choices=range(1, 7), nargs='+',
                        help='TV profile numbers for init (default: 1)')
    args = parser.parse_args()
    if args.profiles and args.action != 'init':
        parser.error('--profiles is only valid with init')
    os.umask(0o077)
    try:
        if args.action == 'init':
            result = initialize(args.directory, args.profiles or [1])
        elif args.action == 'export':
            result = export(args.directory)
        else:
            result = verify(args.directory, args.max_age_hours)
        print(json.dumps(result))
        return 0
    except BackupError as error:
        print(json.dumps({'ok': False, 'error': str(error)}))
    except Exception:
        # Do not leak private HTTP responses, environment values or local payloads.
        print(json.dumps({'ok': False, 'error': 'LOCAL_OR_SCHEMA_FAILURE'}))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
