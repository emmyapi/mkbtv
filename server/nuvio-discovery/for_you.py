"""Publish a profile's For You lineup on visits and completed watches."""
from datetime import datetime, timezone
import fcntl
import json
import logging
import time

import discovery as d
import for_you_pool
from for_you_rank import release_age_group, select_lineup


LINEUP_VERSION = 2  # Whole-history ranking with a soft release-age balance.


def cards_for(profile):
    return next((row.get('metas', []) for row in profile.get('rows', []) if row['id'] == 'for-you'), [])


def reserve(profile):
    # Harvest published public cards once. The old starter pool is still useful on a cold cache.
    cards = {}
    groups = [row.get('metas', []) for row in profile.get('rows', [])]
    groups.extend(profile.get('genre_pool', {}).values())
    groups.append(profile.get('for_you_starters', []))
    for group in groups:
        for meta in group:
            cards[d.identity(meta)] = meta
    return list(cards.values())


def history_document(profile):
    watched = []
    for key, stamp in profile.get('seasonal_history', {}).items():
        kind, uid = key.split('/', 1)
        watched.append({'content_type': kind, 'content_id': uid, 'watched_at': stamp})
    progress = [{'content_type': 'series', 'content_id': uid, 'position': 1}
                for uid in profile.get('seasonal_progress', [])]
    return {'format': 'nuvio-profile-backup-v2', 'profile_id': profile['profile_id'],
            'watched': watched, 'progress': progress}


def pool_for(root, document, profile, excluded, previous_cards=(), **kwargs):
    return for_you_pool.prepare_pool(root, document, excluded, known_metas=profile.get('recent_metas', []),
                                     fallback=[*previous_cards, *reserve(profile)], unwatched=profile.get('for_you_unwatched', {}),
                                     related_rows=[r for r in profile.get('rows', []) if r.get('seed')], **kwargs)


def canonical_stamps(stamps, aliases):
    canonical = {}
    for key, at in stamps.items():
        key = aliases.get(key, key)
        canonical[key] = max(canonical.get(key, 0), at)
    return canonical


def publish(root, document, previous, profile, session, excluded, signature, progress):
    saved = previous.get('for_you', {})
    old_cards = cards_for(previous)
    migration = saved.get('version') != LINEUP_VERSION
    visits = saved.get('visits', [previous['session']] if previous.get('session') else [])
    visit = session['session'] if session else None
    new_visit = bool(visit and visit not in visits)
    changed = (signature != previous.get('history_signature') or progress != previous.get('seasonal_progress')
               or profile.get('for_you_unwatched', {}) != previous.get('for_you_unwatched', {}))
    state = saved
    coverage = previous.get('summary', {}).get('for_you', {})
    if not migration and not new_visit and not changed and not (visit and saved.get('pending')):
        cards = old_cards
    else:
        pool = pool_for(root, document, profile, excluded, old_cards)
        coverage = {'distinct': pool['distinct'], 'matched': pool['matched'], 'candidates': len(pool['candidates'])}
        aliases = pool['aliases']
        observed = canonical_stamps(saved.get('observed', previous.get('seasonal_history', {})), aliases)
        current = canonical_stamps(pool['watched'], aliases)
        pending = any(at > observed.get(key, 0) for key, at in current.items())
        rotate = bool(visit and (new_visit or pending))
        # Apply the new policy once to a published shelf, including on scheduled refresh.
        # An upgrade without a new viewing event preserves the profile's exposure ledger.
        old_exposure = saved.get('exposure', {})
        cards, exposure = select_lineup(pool, old_cards, old_exposure, now_ms=int(time.time() * 1000),
                                        rotate=rotate or (migration and bool(old_cards)))
        if not rotate:
            exposure = old_exposure
        if visit or not saved:
            # Undo removes the vote from the pool. A late replay of its timestamp is not a new watch.
            for key, at in current.items():
                observed[key] = max(observed.get(key, 0), at)
            pending = False
        state = {'version': LINEUP_VERSION, 'exposure': exposure, 'observed': observed, 'pending': pending,
                 'visits': (visits + [visit])[-256:] if new_visit else visits}
    profile['for_you'] = state
    profile['summary'] = dict(profile.get('summary', {}), for_you=coverage)
    profile['rows'] = [dict(row, metas=cards) if row['id'] == 'for-you' else row for row in profile['rows']]


def state_files(root):
    profiles = json.loads((root / 'data' / 'catalogs.json').read_text())['profiles']
    exclusions = json.loads((root / 'config.json').read_text()).get('excluded_seed_ids', {})
    return profiles, exclusions


def read_cursor(path):
    try:
        values = json.loads(path.read_text())
    except (FileNotFoundError, ValueError):
        return {}
    if not isinstance(values, dict):
        return {}
    return {k: v for k, v in values.items() if type(v) is int and v >= 0}


def prepare(root):
    """Advance each profile's independent cursor without locking Home during remote calls."""
    folder = root / 'for-you'
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (folder / '.prepare.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        profiles, exclusions = state_files(root)
        summary = []
        for pid, profile in profiles.items():
            path = folder / 'profiles' / (str(int(pid)) + '.json')
            try:
                pool = pool_for(root, history_document(profile), profile, exclusions.get(pid, []),
                                background=True, cursor=read_cursor(path))
                d.atomic_json(path, pool['cursor'])
            except Exception as error:
                # A bad external record or a private cursor affects only this profile's next batch.
                logging.getLogger(__name__).warning('For You profile batch deferred (%s)', type(error).__name__)
                summary.append({'profile': int(pid), 'status': 'retry'})
                continue
            summary.append({'profile': int(pid), 'matched': pool['matched'], 'distinct': pool['distinct'],
                            'candidates': len(pool['candidates'])})
        return summary


def worker(root, stop):
    while not stop.is_set():
        try:
            prepare(root)
        except (BlockingIOError, FileNotFoundError):
            pass
        except Exception as error:
            # Public feeds can fail independently. Keep the worker alive and tokens out of logs.
            logging.getLogger(__name__).warning('For You preparation deferred (%s)', type(error).__name__)
        stop.wait(60)


def preview(root, pid, visits, details=False):
    profiles, exclusions = state_files(root)
    if str(pid) not in profiles:
        raise d.DiscoveryError('HISTORY_PROFILE_UNAVAILABLE')
    profile = profiles[str(pid)]
    pool = pool_for(root, history_document(profile), profile, exclusions.get(str(pid), []))
    aliases = pool['aliases']
    def key(meta):
        ident = meta['type'] + '/' + meta['id']
        return aliases.get(ident, ident)
    candidates = {key(c['meta']): c for c in pool['candidates']}
    cards = cards_for(profile)
    exposure = profile.get('for_you', {}).get('exposure', {})
    rows = []
    now = int(time.time() * 1000)
    today = datetime.fromtimestamp(now / 1000, timezone.utc).date()
    for _ in range(visits):
        old = {key(c) for c in cards}
        cards, exposure = select_lineup(pool, cards, exposure, now_ms=now)
        lineup = [key(card) for card in cards]
        ages = [release_age_group(card, today) for card in cards]
        row = {'recent': ages.count('recent'), 'front_recent': ages[:10].count('recent'),
               'unknown_release': ages.count('unknown'), 'size': len(lineup), 'arrivals': len(set(lineup) - old),
               'front_arrivals': len(set(lineup[:10]) - old),
               'rewatches': sum(k in pool['watched'] for k in lineup)}
        if details:
            row['cards'] = [{'id': k, 'name': candidates[k]['meta']['name'],
                             'age_group': age, 'interests': candidates[k]['interests'], 'sources': candidates[k]['sources']}
                            for k, age in zip(lineup, ages)]
        rows.append(row)
    return {'matched': pool['matched'], 'distinct': pool['distinct'], 'candidates': len(candidates), 'visits': rows}
