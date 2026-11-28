"""Pure per-profile selection for the For You catalog."""

from collections import Counter, defaultdict
from dataclasses import dataclass
from hashlib import blake2b

ROW_SIZE = 60
FRONT_SIZE = 10
EXPOSURE_EVENTS = 30
DAY_MS = 86_400_000
REWATCH_AGE_MS = 180 * DAY_MS


@dataclass
class Candidate:
    key: str
    meta: dict
    strength: float
    interests: dict
    watched_at: int
    tie: bytes


def interest_shares(tags):
    """Divide one work's vote among genres and their supported country combinations."""
    genres = set()
    combos = defaultdict(set)
    for tag in tags:
        if ' / ' in tag:
            combos[tag.rsplit(' / ', 1)[1]].add(tag)
        else:
            genres.add(tag)
    shares = {}
    for genre in sorted(genres):
        matches = combos[genre]
        share = 1 / len(genres)
        shares[genre] = share * 0.65 if matches else share
        for match in sorted(matches):
            shares[match] = share * 0.35 / len(matches)
    return shares


def _index_pool(pool, previous_cards, now_ms):
    from discovery import title_keys

    # Keep IMDb works distinct if a stale native ID was claimed by both.
    aliases = {}
    ambiguous = set(pool.get('ambiguous_aliases', []))

    def link(native, imdb):
        if native in aliases and aliases[native] != imdb:
            ambiguous.add(native)
        aliases[native] = imdb

    for key, target in pool.get('aliases', {}).items():
        if '/tmdb:' in key and '/tt' in target:
            link(key, target)
    candidates = pool.get('candidates', [])
    for meta in [item['meta'] for item in candidates] + list(previous_cards):
        ids = {kind + '/' + uid for kind, uid in title_keys(meta)}
        imdb = {key for key in ids if '/tt' in key}
        if len(imdb) == 1:
            for native in ids - imdb:
                link(native, next(iter(imdb)))
    for key in ambiguous:
        aliases.pop(key, None)

    def resolve(key):
        return aliases.get(key, key)

    watched = {}
    for key, at in pool.get('watched', {}).items():
        key = resolve(key)
        watched[key] = max(watched.get(key, 0), at)
    progress = {resolve(key) for key in pool.get('progress', [])}
    weights = pool.get('interests', {})
    uncertain = ambiguous.intersection(watched.keys() | progress)
    available = {}
    for item in candidates:
        meta = item['meta']
        key = resolve(meta['type'] + '/' + meta['id'])
        watched_at = watched.get(key, 0)
        # The export can record individual episodes. Without verified whole-series
        # completion only films qualify for rewatches.
        aged_movie = meta['type'] == 'movie' and now_ms - watched_at >= REWATCH_AGE_MS
        if key in progress or (watched_at and not aged_movie):
            continue
        if uncertain and any(kind + '/' + uid in uncertain for kind, uid in title_keys(meta)):
            continue
        strength = item['strength']
        prior = available.get(key)
        if prior and (prior.strength, prior.meta['id'].startswith('tt')) >= (strength, meta['id'].startswith('tt')):
            continue
        shares = interest_shares(set(item['interests']).intersection(weights))
        # Stable ties must not group movies or series simply by their ID prefix.
        tie = blake2b(key.encode(), digest_size=8).digest()
        available[key] = Candidate(key, meta, strength, shares, watched_at, tie)

    # The mixed Android shelf deduplicates by raw display ID across media types.
    # Prefer a verified IMDb ID for a native collision; otherwise keep one card.
    display_counts = Counter(card.meta['id'] for card in available.values())
    display = {}

    def display_rank(card):
        return not card.watched_at, card.strength, card.key

    for card in available.values():
        uid = card.meta['id']
        if display_counts[uid] > 1 and uid.startswith('tmdb:') and '/tt' in card.key:
            card.meta = dict(card.meta, id=card.key.split('/')[1], moviedb_id=uid[5:])
        uid = card.meta['id']
        if uid not in display or display_rank(card) > display_rank(display[uid]):
            display[uid] = card
    previous = list(dict.fromkeys(resolve(m['type'] + '/' + m['id']) for m in previous_cards))
    return {card.key: card for card in display.values()}, previous, resolve


def _deficits(weights, counts):
    slots = 1 + sum(counts[key] for key in weights)
    return {key: weight * slots - counts[key] for key, weight in weights.items()}


def _reserve_for_front(left, available, weights, front_counts, size, quality_floor):
    """Give a scarce, underexposed interest a chance to be genuinely new next visit."""
    supply = Counter()
    for card in available.values():
        if card.strength >= quality_floor and not card.watched_at:
            supply.update(card.interests)
    deficits = _deficits(weights, front_counts)
    scarce = {tag for tag, weight in weights.items() if deficits[tag] > 0 and 0 < supply[tag] <= size * weight}
    reserve = set()
    if not scarce:
        return reserve
    # A single strong discovery can represent several overlapping interests.
    for card in sorted(left.values(), key=lambda c: (c.strength, c.tie), reverse=True):
        if scarce.isdisjoint(card.interests) or card.watched_at or card.strength < quality_floor:
            continue
        reserve.add(card.key)
        scarce.difference_update(card.interests)
        if not scarce:
            break
    return reserve


def _ordered_returners(picks, previous):
    """Restore the relative order of survivors while respecting the first-ten rewatch cap."""
    order = {key: position for position, key in enumerate(previous)}
    returning = sorted((card for card in picks if card.key in order), key=lambda card: order[card.key])
    room = 1 - sum(bool(card.watched_at) for card in picks[:FRONT_SIZE] if card.key not in order)
    result = []
    for position, card in enumerate(picks):
        if card.key in order:
            index = 0
            if position < FRONT_SIZE and room == 0:
                index = next(i for i, candidate in enumerate(returning) if not candidate.watched_at)
            card = returning.pop(index)
            if position < FRONT_SIZE and card.watched_at:
                room -= 1
        result.append(card)
    return result


def select_lineup(pool, previous_cards, state, *, now_ms, rotate=True):
    """Return (public cards, JSON exposure state). Only a real event calls for rotation."""
    available, previous, resolve = _index_pool(pool, previous_cards, now_ms)
    old = set(previous)
    known = sum(bool(card.watched_at) for card in available.values())
    unseen = len(available) - known
    # With U discoveries and R rewatches, 10R <= U + R. Derive the cap
    # *after* alias merging and eligibility filtering, including on short rows.
    size = min(ROW_SIZE, unseen + min(known, unseen // 9))
    rewatch_cap = size // 10

    weights = pool.get('interests', {})
    events = state.get('events', [])[-EXPOSURE_EVENTS:]
    counts = [Counter(), Counter()]
    for event in events:
        counts[0].update(event['front'])
        counts[1].update(event['rest'])
    recent_front = {resolve(key) for event in events[-4:] for key in event['titles'][:FRONT_SIZE]}
    repeats = Counter()
    for age, event in enumerate(reversed(events[-8:])):
        for pos, key in enumerate(event['titles']):
            repeats[resolve(key)] += (0.65 ** age) * (1.0 if pos < FRONT_SIZE else 0.5)

    utility = {}
    for card in available.values():
        score = 0.45 * card.strength - 0.65 * repeats[card.key]
        if card.watched_at:
            years = (now_ms - card.watched_at) / (365 * DAY_MS)
            score -= 0.35 / max(1, years)
        utility[card.key] = score

    # A verified country/genre or direct title match scores at least 0.95 in
    # the pool. In a thinner catalog use the marginal playable card as the bar.
    by_strength = sorted(available.values(), key=lambda card: -card.strength)
    playable = []
    rewatches = 0
    for card in by_strength:
        if card.watched_at and rewatches >= rewatch_cap:
            continue
        playable.append(card)
        rewatches += bool(card.watched_at)
        if len(playable) == size:
            break
    quality_floor = min(0.95, 0.85 * playable[-1].strength) if playable else 0

    picks = []
    left = available.copy()
    new_count = known_count = 0

    def allowed(card):
        cap = min(rewatch_cap, 1) if len(picks) < FRONT_SIZE else rewatch_cap
        return not card.watched_at or known_count < cap

    def append(card):
        nonlocal new_count, known_count
        region = int(len(picks) >= FRONT_SIZE)
        counts[region].update(card.interests)
        picks.append(card)
        del left[card.key]
        new_count += card.key not in old
        known_count += bool(card.watched_at)

    if not rotate:
        for key in previous:
            card = left.get(key)
            if card and allowed(card) and len(picks) < size:
                append(card)

    front_size = min(FRONT_SIZE, size)
    fresh_target = max(front_size, (size + 1) // 2)
    reserve = set()
    while len(picks) < size:
        position = len(picks)
        front = position < front_size
        if rotate and position == FRONT_SIZE:
            reserve = _reserve_for_front(left, available, weights, counts[0], size, quality_floor)
        # Spread the remaining arrivals from the eleventh position through the last.
        fresh_due = position + 1 if front else front_size + (
            (position + 1 - front_size) * (fresh_target - front_size) // (size - front_size))
        seek_new = new_count < fresh_due
        deficits = _deficits(weights, counts[int(not front)])
        unmatched = min(deficits.values(), default=0) - 1

        def priority(card):
            balance = (sum(deficits[tag] * share for tag, share in card.interests.items())
                       if card.interests else unmatched)
            score = balance + utility[card.key]
            return (card.strength >= quality_floor, (card.key not in old) == seek_new,
                    not front or card.key not in recent_front, card.key not in reserve, score, card.tie)

        card = max((card for card in left.values() if allowed(card)), key=priority)
        append(card)

    if not rotate:
        return [card.meta for card in picks], state

    picks = _ordered_returners(picks, previous)
    # Count the interests actually visible at each depth after restoring survivor order.
    front_counts, rest_counts = Counter(), Counter()
    for position, card in enumerate(picks):
        (front_counts if position < FRONT_SIZE else rest_counts).update(card.interests)
    event = {'titles': [card.key for card in picks], 'front': dict(front_counts), 'rest': dict(rest_counts)}
    return [card.meta for card in picks], {**state, 'events': (events + [event])[-EXPOSURE_EVENTS:]}
