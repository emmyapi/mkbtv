"""Bounded public catalog preparation, independent of Home requests and watch history."""
from datetime import datetime, timezone
import fcntl
import json
import logging
import time

from discovery import atomic_json
from seasonal_calendar import current_occurrence, next_calendar_boundary, upcoming_occurrences
from seasonal_catalog import refresh_theme


def calendar_config(root):
    path = root / 'config.json'
    return json.loads(path.read_text()).get('seasonal_calendar') if path.exists() else None


def preview(root, now):
    config = calendar_config(root)
    occ = current_occurrence(now, config)
    return {'theme': occ['theme'] if occ else None, 'occurrence': occ['id'] if occ else None,
            'nextBoundary': (occ['next_boundary'] if occ else next_calendar_boundary(now, config)).isoformat(),
            'upcoming': [{'theme': o['theme'], 'start': o['start'].isoformat(), 'end': o['end'].isoformat()}
                         for o in upcoming_occurrences(now, days=45, calendar_config=config)]}


def prepare(root, all_themes=False):
    directory = root / 'seasonal'
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (directory / 'preparation.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {'busy': True}
        now = datetime.now(timezone.utc)
        schedule = preview(root, now)
        active = schedule['theme']
        themes = list(dict.fromkeys(([active] if active else []) + [o['theme'] for o in schedule['upcoming']]))
        path = directory / 'work.json'
        work = json.loads(path.read_text()) if path.exists() else {}
        due = [slug for slug in themes if work.get(slug, {}).get('due', 0) <= now.timestamp()]
        # Give the current theme its first batch immediately, then share work fairly with future themes.
        due.sort(key=lambda slug: (slug != active or slug in work, work.get(slug, {}).get('last', 0)))
        result = {}
        for slug in (due if all_themes else due[:1]):
            status = refresh_theme(root, slug, now)
            work[slug] = {'last': time.time(), 'due': status['next_check']}
            atomic_json(path, work)
            result[slug] = status
        return result


def worker(root, stop):
    while not stop.is_set():
        try:
            prepare(root)
        except Exception as error:
            # Keep the existing discovery service available during a provider or catalog failure.
            logging.getLogger(__name__).warning('Seasonal import deferred (%s)', type(error).__name__)
        stop.wait(30)
