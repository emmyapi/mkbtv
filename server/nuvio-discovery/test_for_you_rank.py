import json
import unittest
from collections import Counter
from copy import deepcopy

from for_you_rank import DAY_MS, EXPOSURE_EVENTS, REWATCH_AGE_MS, select_lineup


NOW = 1_800_000_000_000
KOREAN_SF = 'South Korea / Science Fiction'


def item(number, interests=('Drama',), strength=1.6, **fields):
    meta = {'id': 'tt' + str(number), 'type': 'movie', 'name': f'Film {number}', **fields}
    return {'meta': meta, 'interests': list(interests), 'strength': strength,
            'sources': [{'seed': 'movie/tt9999', 'basis': 'TMDB title recommendations', 'rank': 1}]}


def ids(cards):
    return [(card['type'], card['id']) for card in cards]


class ForYouRankTests(unittest.TestCase):
    def test_thirty_events_turn_over_half_with_calibrated_front_and_variety_at_depth(self):
        pool = {'interests': {'Drama': 0.67, 'Comedy': 0.27, 'Science Fiction': 0.053, KOREAN_SF: 0.007}}
        candidates = []
        groups = {}
        for genre, count in [('Drama', 140), ('Comedy', 84), ('Science Fiction', 28), (KOREAN_SF, 12)]:
            for _ in range(count):
                number = len(candidates) + 1
                tags = [genre, 'Science Fiction'] if genre == KOREAN_SF else [genre]
                candidates.append(item(number, tags, 1.6 + (number % 4) / 12))
                groups['tt' + str(number)] = genre
        pool['candidates'] = candidates
        front_counts, front_ids, previous, state = Counter(), set(), [], {}
        rare_events = set()
        for tick in range(30):
            at = NOW + tick * 300_000  # Several genuine events can occur the same day.
            before = deepcopy(state)
            cards, next_state = select_lineup(pool, previous, state, now_ms=at)
            self.assertEqual(before, state)
            self.assertEqual((len(cards), len(set(ids(cards)))), (60, 60))
            head = [groups[c['id']] for c in cards[:10]]
            front_counts.update(head)
            front_ids.update(ids(cards[:10]))
            if KOREAN_SF in head:
                rare_events.add(tick)
            if previous:
                old = set(ids(previous))
                arrivals = [i for i, key in enumerate(ids(cards)) if key not in old]
                self.assertEqual(arrivals[:10], list(range(10)))
                self.assertEqual(len(arrivals), 30)
                self.assertTrue(any(i in arrivals for i in range(50, 60)))
                self.assertTrue(any(i in arrivals for i in range(10, 20)))
                self.assertEqual([k for k in ids(cards) if k in old],
                                 [k for k in ids(previous) if k in set(ids(cards))])
            # A retry and restart can serve the same order and exposure record.
            restored = json.loads(json.dumps(next_state))
            self.assertEqual(select_lineup(pool, cards, restored, now_ms=at + DAY_MS, rotate=False),
                             (cards, next_state))
            previous, state = cards, next_state
        self.assertGreaterEqual(len(front_ids), 100)
        self.assertEqual(len(state['events']), min(30, EXPOSURE_EVENTS))
        self.assertGreaterEqual(front_counts['Drama'], 190)
        self.assertLessEqual(front_counts['Drama'], 212)
        self.assertGreaterEqual(front_counts['Comedy'], 74)
        self.assertLessEqual(front_counts['Comedy'], 90)
        self.assertGreaterEqual(len(rare_events), 4)
        self.assertLessEqual(len(rare_events), 8)

    def test_combo_rich_catalog_preserves_parent_genres_and_counts_each_poster_once(self):
        countries = [('US', 'Drama', 0.65), ('US', 'Comedy', 0.28),
                     ('GB', 'Science Fiction', 0.05), ('South Korea', 'Science Fiction', 0.02)]
        pool = {'candidates': [], 'interests': Counter()}
        actual_genres = {}
        for country, genre, fraction in countries:
            tag = f'{country} / {genre}'
            pool['interests'][genre] += fraction * 0.65
            pool['interests'][tag] += fraction * 0.35
            for _ in range(90):
                number = len(pool['candidates']) + 1
                pool['candidates'].append(item(number, [genre, tag]))
                actual_genres['tt' + str(number)] = (country, genre)
        previous, state = [], {}
        actual = Counter()
        for tick in range(30):
            previous, state = select_lineup(pool, previous, state, now_ms=NOW + tick)
            actual.update(actual_genres[c['id']] for c in previous[:10])
            self.assertAlmostEqual(sum(state['events'][-1]['front'].values()), 10)
        for country, genre, fraction in countries:
            self.assertAlmostEqual(actual[country, genre], 300 * fraction, delta=4)
        recorded = Counter()
        for event in state['events']:
            recorded.update(event['front'])
        self.assertAlmostEqual(recorded['Drama'], actual['US', 'Drama'] * 0.65)
        self.assertAlmostEqual(recorded[KOREAN_SF], actual['South Korea', 'Science Fiction'] * 0.35)

    def test_single_scarce_discovery_can_return_to_front_without_repeating_every_visit(self):
        entries = [item(i) for i in range(240)]
        entries.append(item(777, ['Science Fiction', KOREAN_SF], strength=1.7))
        pool = {'candidates': entries,
                'interests': {'Drama': .95, 'Science Fiction': .0325, KOREAN_SF: .0175}}
        previous, state, prominent = [], {}, []
        for tick in range(30):
            cards, state = select_lineup(pool, previous, state, now_ms=NOW + tick)
            if previous:
                old = set(ids(previous))
                self.assertEqual(len(set(ids(cards)) - old), 30)
                self.assertTrue(set(ids(cards[:10])).isdisjoint(old))
            if ('movie', 'tt777') in ids(cards[:10]):
                prominent.append(tick)
            previous = cards
        self.assertGreaterEqual(len(prominent), 3)
        self.assertTrue(all(later - earlier >= 5 for earlier, later in zip(prominent, prominent[1:])))

    def test_flat_cold_start_preserves_mixed_movies_and_series(self):
        entries = [item(i, type='movie' if i % 2 else 'series', strength=.05) for i in range(200)]
        cards, _ = select_lineup({'candidates': entries}, [], {}, now_ms=NOW)
        self.assertEqual({c['type'] for c in cards[:10]}, {'movie', 'series'})

    def test_quality_beats_novelty_and_sparse_pool_uses_every_worthwhile_arrival(self):
        incumbents = [item(number, strength=2) for number in range(60)]
        weak = [item(number, strength=0.1) for number in range(60, 140)]
        pool = {'candidates': incumbents + weak, 'interests': {'Drama': 1.0}}
        previous = [card['meta'] for card in incumbents]
        same, _ = select_lineup(pool, previous, {}, now_ms=NOW)
        self.assertEqual(same, previous)
        good = [item(number, strength=1.4) for number in range(200, 203)]
        pool['candidates'].extend(good)
        cards, _ = select_lineup(pool, previous, {}, now_ms=NOW)
        self.assertEqual(set(ids(cards[:3])), set(ids([c['meta'] for c in good])))
        self.assertEqual(len(set(ids(cards)) & set(ids(previous))), 57)

    def test_short_row_deduplicates_filters_both_id_forms_and_caps_actual_rewatches(self):
        unseen = [item(number) for number in range(18)]
        olds = [item(number, strength=2.5) for number in range(30, 35)]
        recent = item(80, moviedb_id='80')
        in_progress = item(81, type='series', moviedb_id='81')
        duplicate = item(0, id='tmdb:400')
        duplicate['meta']['imdb_id'] = unseen[0]['meta']['id']
        pool = {'candidates': unseen + olds + [recent, duplicate, in_progress],
                'interests': {'Drama': 1.0}, 'progress': ['series/tmdb:81'],
                'watched': {**{'movie/' + c['meta']['id']: NOW - 2 * REWATCH_AGE_MS for c in olds},
                            'movie/tt80': NOW - 3 * REWATCH_AGE_MS, 'movie/tmdb:80': NOW,
                            'series/tt81': NOW - 3 * REWATCH_AGE_MS}}
        cards, _ = select_lineup(pool, [], {}, now_ms=NOW)
        self.assertEqual(len(cards), 20)  # 18 unseen permit two old rewatches.
        known = set(ids(c['meta'] for c in olds))
        self.assertEqual(len(set(ids(cards)) & known), 2)
        self.assertLessEqual(len(set(ids(cards[:10])) & known), 1)
        self.assertFalse(set(ids(cards)) & set(ids([recent['meta'], in_progress['meta']])))
        self.assertEqual(sum(card['id'] in ('tt0', 'tmdb:400') for card in cards), 1)

        # The second old watch must leave even with many more to choose from.
        pool['candidates'] = unseen + olds[:1] + [recent, duplicate, in_progress]
        nineteen, _ = select_lineup(pool, cards, {}, now_ms=NOW, rotate=False)
        self.assertEqual(len(nineteen), 19)
        self.assertEqual(len(set(ids(nineteen)) & known), 1)
        pool['candidates'] = unseen[:8] + olds
        eight, _ = select_lineup(pool, nineteen, {}, now_ms=NOW, rotate=False)
        self.assertEqual(len(eight), 8)
        self.assertTrue(set(ids(eight)).isdisjoint(known))

    def test_reconcile_preserves_order_exposure_and_refills_after_removal(self):
        entries = [item(number) for number in range(100)]
        pool = {'candidates': entries, 'interests': {'Drama': 1.0}}
        original, state = select_lineup(pool, [], {}, now_ms=NOW)
        old = ids(original)
        removed = old[3]
        pool['watched'] = {'/'.join(removed): NOW}
        revised, state_after = select_lineup(pool, original, state, now_ms=NOW, rotate=False)
        self.assertEqual(state, state_after)
        self.assertEqual(ids(revised[:-1]), [key for key in old if key != removed])
        self.assertNotIn(ids(revised)[-1], old)
        # An undo can restore eligibility without forcing another published rotation.
        pool['watched'] = {}
        self.assertEqual(select_lineup(pool, revised, state, now_ms=NOW, rotate=False), (revised, state))

    def test_same_work_under_new_alias_is_a_return_and_repeated_calls_are_deterministic(self):
        old = [item(i)['meta'] for i in range(60)]
        candidates = [item(i, id=f'tmdb:{i + 100}') for i in range(60)] + [item(1000 + i) for i in range(30)]
        aliases = {f'movie/tmdb:{i + 100}': f'movie/tt{i}' for i in range(60)}
        pool = {'candidates': candidates, 'aliases': aliases, 'interests': {}}
        result = select_lineup(pool, old, {}, now_ms=NOW)
        self.assertEqual(result, select_lineup(pool, old, {}, now_ms=NOW))
        cards, state = result
        self.assertEqual(len([c for c in cards if c['id'].startswith('tmdb:')]), 30)
        self.assertTrue(all(c['id'].startswith('tt') for c in cards[:10]))
        self.assertEqual(state['events'][0]['front'], {})

    def test_known_series_requires_evidence_of_whole_series_completion(self):
        entries = [item(i, type='series') for i in range(30)]
        pool = {'candidates': entries, 'watched': {'series/tt1': NOW - 3 * REWATCH_AGE_MS}}
        cards, _ = select_lineup(pool, [], {}, now_ms=NOW)
        self.assertEqual(len(cards), 29)
        self.assertNotIn(('series', 'tt1'), ids(cards))

    def test_raw_android_id_collision_is_resolved_before_actual_length_and_caps(self):
        plain = [item(i) for i in range(16)]
        native = [item(101, id='tmdb:42'), item(102, id='tmdb:42', type='series')]
        olds = [item(70 + i, strength=2.2) for i in range(3)]
        pool = {'candidates': plain + native + olds, 'interests': {'Drama': 1.0},
                'watched': {f'movie/tt{70 + i}': NOW - 2 * REWATCH_AGE_MS for i in range(3)}}
        cards, _ = select_lineup(pool, [], {}, now_ms=NOW)
        self.assertEqual(len(cards), len({c['id'] for c in cards}))
        self.assertEqual(len(cards), 18)  # 17 discoveries and at most one rewatch.
        self.assertEqual(sum('movie/' + c['id'] in pool['watched'] for c in cards), 1)
        # A verified IMDb alias allows the app to display both titles.
        pool['aliases'] = {'movie/tmdb:42': 'movie/tt101'}
        cards, _ = select_lineup(pool, [], {}, now_ms=NOW)
        self.assertEqual(len(cards), len({c['id'] for c in cards}))
        self.assertIn(('movie', 'tt101'), ids(cards))
        self.assertIn(('series', 'tmdb:42'), ids(cards))
        self.assertEqual(len(cards), 20)

    def test_conflicting_native_alias_never_joins_two_imdb_works(self):
        entries = [item(1, moviedb_id='71'), item(2, moviedb_id='71')]
        pool = {'candidates': entries, 'watched': {'movie/tt1': NOW}}
        cards, _ = select_lineup(pool, [], {}, now_ms=NOW)
        self.assertEqual(ids(cards), [('movie', 'tt2')])
        pool['watched'] = {'movie/tmdb:71': NOW}
        self.assertEqual(select_lineup(pool, [], {}, now_ms=NOW)[0], [])

    def test_no_interest_no_candidates_and_only_watches_are_safe(self):
        self.assertEqual(select_lineup({}, [], {}, now_ms=NOW, rotate=False), ([], {}))
        entries = [item(1), item(2)]
        pool = {'candidates': entries, 'watched': {'movie/tt1': 1, 'movie/tt2': 2}}
        self.assertEqual(select_lineup(pool, [], {}, now_ms=NOW)[0], [])
        pool['watched'] = {}
        cards, state = select_lineup(pool, [], {}, now_ms=NOW)
        self.assertEqual(len(cards), 2)
        self.assertEqual(state['events'][0]['front'], {})
