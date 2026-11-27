"""Private per-profile selection over the shared seasonal movie catalog."""
from collections import Counter
from contextlib import nullcontext
from copy import deepcopy
from datetime import date, datetime, timezone
import fcntl
import json

from discovery import atomic_json, canonical_genres, title_keys
from seasonal_calendar import COPENHAGEN, THEMES, current_occurrence, next_calendar_boundary
from seasonal_catalog import pool_guard, read_pool, record_shown


def aliases(meta):
    return {identifier for _, identifier in title_keys(meta)}


def millis(instant):
    return int(instant.timestamp() * 1000)


def watched_state(base, state, session):
    # Timestamp explicit corrections so stale export/sync retries cannot undo them.
    marks = state.setdefault('marks', {})
    for field, value in (('seasonalWatched', True), ('seasonalUnwatched', False)):
        for event in session.get(field, []):
            key = event['type'] + '/' + event['id']
            old = marks.get(key)
            if old is None or event['at'] > old[0] or (event['at'] == old[0] and not value):
                marks[key] = [event['at'], value]
    known = {key: (at, True) for key, at in base.get('seasonal_history', {}).items()}
    for event in base.get('live_watched', []):
        key = event['type'] + '/' + event['id']
        if event['at'] > known.get(key, (0, False))[0]:
            known[key] = (event['at'], True)
    for key, mark in marks.items():
        if mark[0] >= known.get(key, (0, False))[0]:
            known[key] = mark
    return known


def watch_time(kind, ids, known):
    at, watched = max((known.get(kind + '/' + uid, (0, False)) for uid in ids),
                      key=lambda event: (event[0], not event[1]))
    return at if watched else 0


def recent_release(item, today):
    try:
        age = (today - date.fromisoformat(item.get('release', ''))).days
    except (TypeError, ValueError):
        return False
    return 0 <= age <= 90


def choose(occ, theme, pool, known, progress, excluded, old_ids, today, discoveries, taste, reference_aliases,
           previous_ids):
    previous_position = {uid: i for i, uid in enumerate(previous_ids)}
    watched_refs = {ref['id'] for ref in theme['references']
                    if set(reference_aliases.get(ref['id'], [ref['id']])).isdisjoint(excluded) and
                    watch_time(ref['type'], reference_aliases.get(ref['id'], [ref['id']]), known)}
    editorial = []
    occupied = set(excluded)
    for item in pool['editorial']:
        meta = item['meta']
        ids = aliases(meta)
        available = ids.isdisjoint(occupied)
        occupied.update(ids)
        at = watch_time(meta['type'], ids, known)
        if available and at < millis(occ['start']) and (meta['type'] == 'movie' or ids.isdisjoint(progress)):
            editorial.append((item, at))
    # Pool order is the editor's opening eight, then the two handpicked reserves.
    editorial.sort(key=lambda pair: pair[1] > 0)
    editorial_standby = editorial[8:9]
    editorial = editorial[:8]
    refs = {r['id'] for r in theme['references']}
    occupied.update(refs)
    occupied.update(r['id'] for r in theme['reserves'])
    watched_relatives = {ident for ident, ids in discoveries.items()
                         if set(ids).isdisjoint(excluded) and watch_time('movie', ids, known)}
    def affinity(meta):
        genres = canonical_genres(meta)
        return 0.4 * len(genres & taste) / max(1, len(genres))

    dynamic = []
    for item in pool['dynamic']:
        meta = item['meta']
        ids = aliases(meta)
        links = set(item.get('refs', ())).intersection(refs)
        if meta['type'] != 'movie' or not links or not ids.isdisjoint(occupied) or watch_time('movie', ids, known):
            continue
        occupied.update(ids)
        score = (float(item['score']) + affinity(meta) + 0.5 * min(2, len(links & watched_refs)) +
                 0.75 * min(2, len(set(item.get('via', ())) & watched_relatives)))
        dynamic.append((item, score))
    dynamic.sort(key=lambda pair: (-pair[1], previous_position.get(pair[0]['meta']['id'], 16), pair[0]['meta']['id']))
    franchises = Counter(i['franchise'] for i, _ in editorial if i.get('franchise'))
    represented = set()
    chosen = []
    old = set(old_ids)
    while len(chosen) < 8:
        allowed = [p for p in dynamic if not p[0].get('franchise') or occ['theme'] == 'may-the-fourth'
                   or franchises[p[0]['franchise']] < 2]
        if not allowed:
            break
        recent = [p for p in allowed if recent_release(p[0], today)] if not chosen else []
        if recent:
            # The premiere place may change mid-window even when the other seven are still eligible.
            item, score = max(recent, key=lambda p: (p[1], p[0]['release'], p[0]['meta']['id'] in old))
        else:
            item, score = max(allowed, key=lambda p: (p[0]['meta']['id'] in old,
                              len(represented) < 3 and bool(set(p[0]['refs']) - represented), p[1]))
        chosen.append((item, score))
        represented.update(item['refs'])
        franchises[item.get('franchise')] += 1
        dynamic.remove((item, score))
    lineup = []
    for item, at in editorial:
        meta = dict(item['meta'])
        if at:
            meta.update(seasonalRewatch=True, seasonalRewatchAt=at)
        lineup.append((meta, float(item['score']) + affinity(meta)))
    for item, score in chosen:
        lineup.append((item['meta'], score + 0.5 * recent_release(item, today)))
    ranked = [m for m, _ in sorted(lineup, key=lambda p: (-p[1], previous_position.get(p[0]['id'], 16), p[0]['id']))]
    by_id = {m['id']: m for m in ranked}
    additions = iter(m for m in ranked if m['id'] not in old)
    cards = [by_id.get(uid) or next(additions, None) for uid in old_ids]
    alternatives = {}
    if editorial_standby:
        alternatives['editorial'] = {'id': editorial_standby[0][0]['meta']['id'],
                                     'reason': 'Eight earlier eligible editorial choices were selected.'}
    if dynamic:
        item, score = dynamic[0]
        capped = (occ['theme'] != 'may-the-fourth' and item.get('franchise') and franchises[item['franchise']] >= 2)
        alternatives['dynamic'] = {'id': item['meta']['id'], 'score': score, 'reason':
                                   'Franchise limit reached.' if capped else
                                   'Outside the eight places after recent releases, visit continuity, reference coverage and scoring.'}
    return [m for m in cards if m] + list(additions), [i for i, _ in chosen], alternatives


def selection(root, config, profile, base, state, session, now):
    calendar = config.get('seasonal_calendar')
    occ = current_occurrence(now, calendar)
    boundary = occ['next_boundary'] if occ else next_calendar_boundary(now, calendar)
    view = {'seasonalValidUntil': millis(boundary), 'seasonalMovieIds': [], 'seasonalSeriesIds': []}
    known = watched_state(base, state, session)
    if 'seasonalInProgress' in session:
        state['in_progress'] = sorted(set(session['seasonalInProgress']))
    progress = set(state.get('in_progress', ())) | set(base.get('seasonal_progress', ()))
    visit = session.get('session', state.get('visit'))
    cards, chosen = [], []
    alternatives = {}
    pool = None
    if occ:
        theme = THEMES[occ['theme']]
        view['seasonalOccurrence'] = occ['id']
        listed = (*theme['references'], *theme['reserves'])
        view['seasonalSeriesIds'] = [r['id'] for r in listed if r['type'] == 'series']
        closed = {k: v for k, v in state.get('closed', {}).items() if v > millis(now)}
        state['closed'] = closed
        if occ['id'] not in closed:
            pool = read_pool(root, occ['theme'], now)
            reference_aliases = state.setdefault('reference_aliases', {})
            for item in pool['editorial']:
                meta = item['meta']
                reference_aliases[meta['id']] = sorted(aliases(meta) | set(reference_aliases.get(meta['id'], ())))
            movie_ids = []
            for item in (*pool['editorial'], *pool['dynamic']):
                meta = item['meta']
                target = view['seasonalSeriesIds'] if meta['type'] == 'series' else movie_ids
                target.extend(sorted(aliases(meta)))
            view['seasonalSeriesIds'] = list(dict.fromkeys(view['seasonalSeriesIds']))[:8]
            started = {k: v for k, v in state.get('started_series', {}).items() if v['end'] > millis(now)}
            previous_ids = started.get(occ['id'], {}).get('ids', ())
            positive = progress.intersection(view['seasonalSeriesIds']).union(previous_ids)
            if positive:
                started[occ['id']] = {'end': millis(occ['end']), 'ids': sorted(positive)}
                progress.update(positive)
            state['started_series'] = started
            previous_ids = state.get('order', []) if state.get('occurrence') == occ['id'] else []
            old_ids = previous_ids if state.get('visit') == visit else []
            excluded = set(config.get('excluded_seed_ids', {}).get(str(profile), []))
            excluded.update(config.get('seasonal_exclusions', {}).get(occ['theme'], []))
            history = state.setdefault('discoveries', {}).setdefault(occ['theme'], {})
            cards, chosen, alternatives = choose(occ, theme, pool, known, progress, excluded, old_ids,
                                                 now.astimezone(occ['start'].tzinfo).date(), history,
                                                 set(base.get('genre_pool', ())), reference_aliases, previous_ids)
            visible_ids = [uid for meta in cards if meta['type'] == 'movie' for uid in sorted(aliases(meta))]
            view['seasonalMovieIds'] = list(dict.fromkeys(visible_ids + movie_ids))[:512]
            for item in chosen:
                uid = item['meta']['id']
                history[uid] = sorted(aliases(item['meta']))
            if not cards and pool['complete']:
                closed[occ['id']] = millis(occ['end'])
                view['seasonalMovieIds'] = []
    state.update(occurrence=occ['id'] if occ else None, visit=visit, order=[m['id'] for m in cards],
                 next_alternatives=alternatives)
    row = {'id': 'seasonal', 'name': THEMES[occ['theme']]['title'], 'metas': cards} if cards else None
    return occ, pool, row, view, chosen


def profile_view(root, config, profile, session=None, now=None):
    """Publish one consistent profile, calendar occurrence and public pool generation."""
    session = session or {}
    directory = root / 'seasonal' / 'profiles'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / (str(profile) + '.json')
    with path.with_suffix('.lock').open('a') as lock, (root / '.publication.lock').open('a') as publication:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # The long-running upstream refresh takes this lock only during its atomic file swap.
        fcntl.flock(publication, fcntl.LOCK_SH)
        base = json.loads((root / 'data' / 'catalogs.json').read_text())['profiles'].get(str(profile))
        if base is None:
            return None, None, {}, 0
        previous = json.loads(path.read_text()) if path.exists() else {}
        for _ in range(3):
            state = deepcopy(previous)
            instant = now or datetime.now(timezone.utc)
            occ, pool, row, view, chosen = selection(root, config, profile, base, state, session, instant)
            guard = (pool_guard(root, occ['theme'], instant, pool['generation'], pool['complete'])
                     if pool is not None else nullcontext(True))
            with guard as valid:
                publish_time = now or datetime.now(timezone.utc)
                if (not valid or millis(publish_time) >= view['seasonalValidUntil']
                        or instant.astimezone(COPENHAGEN).date() != publish_time.astimezone(COPENHAGEN).date()):
                    continue
                if row != previous.get('row') or view != previous.get('view'):
                    state['revision'] = previous.get('revision', 0) + 1
                state.update(view=view, row=row)
                if state != previous:
                    atomic_json(path, state)
            if chosen:
                record_shown(root, occ['theme'], [item['meta']['id'] for item in chosen])
            return base, row, view, base.get('revision', 0) + state.get('revision', 0)
    raise BlockingIOError('SEASONAL_REFRESH_IN_PROGRESS')
