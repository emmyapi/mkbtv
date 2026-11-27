import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest

from seasonal_calendar import THEMES
import seasonal_catalog as catalog
from seasonal_provider import ProviderError, PublicProvider, availability, detail_tags, home_dates, parse_awards, regional_page


CHRISTMAS = 'christmas-favorites'
NOW = datetime(2027, 12, 12, 12, tzinfo=timezone.utc)


class FixtureProvider:
    def __init__(self):
        self.theme = THEMES[CHRISTMAS]
        self.records = {s['id']: {'id': s['id'], 'name': s['title'], 'type': s['type'], 'runtime': '89 min',
                                  'releaseInfo': str(s['year']), 'released': str(s['year']) + '-02-15'}
                        for s in self.theme['references'] + self.theme['reserves']}
        self.rows = []
        self.failed = False
        self.secondary = {}
        self.available = {}
        self.page_log = []
        self.denied = set()

    def movie(self, num, premise='At Christmas a family reunion changes the plans of two estranged sisters.'):
        uid = 'tt' + str(num)
        self.records[uid] = {'id': uid, 'imdb_id': uid, 'moviedb_id': str(num), 'type': 'movie', 'name': 'Film ' + str(num),
                             'description': premise, 'released': '2027-12-01T00:00:00Z', 'releaseInfo': '2027',
                             'runtime': '89 min', 'imdbRating': '1.1'}
        return uid

    def card(self, typ, uid):
        m = self.records.get(uid if uid.startswith('tt') else 'tt' + uid[5:])
        return m if m and m['type'] == typ else None

    def related(self, seed, source):
        return self.secondary.get(seed['id'], [])

    def search(self, phrase):
        return []

    def keyword_movies(self, theme, seed, page):
        return {'ids': [], 'next': None}

    def releases(self, start, end, page):
        self.page_log.append((start, end, page))
        if page == 2 and self.failed:
            raise ProviderError('offline')
        return {'ids': self.rows[(page-1)*4:page*4], 'next': page + 1 if len(self.rows) > page*4 else None}

    def details(self, meta):
        return {'texts': {'catalog synopsis': meta.get('description', '')}, 'keywords': [], 'countries': [],
                'franchise': '', 'edition_minutes': 89}

    def release_evidence(self, meta, today):
        if meta['id'] in self.denied:
            return None
        return {'market': 'DK', 'source': 'Danish home release', 'date': meta['released'][:10]}

    def subtype(self, uid):
        return 'movie'


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.provider = FixtureProvider()

    def run_refresh(self, when=NOW):
        for _ in range(20):
            state = catalog.refresh_theme(self.root, CHRISTMAS, when, provider=self.provider, limit=14)
            if state['complete'] or state['processed'] == 0:
                break
        return catalog.read_pool(self.root, CHRISTMAS, when)

    def test_canonical_eligibility_before_publication(self):
        p = self.provider
        ids = [p.movie(900000 + i) for i in range(18)]
        p.denied.add(ids[-1])
        nontheme = p.movie(998877, 'A December action premiere with the cast of Home Alone.')
        drift = p.movie(998878, 'A shark attacks a Christmas beach party.')
        p.rows = [*ids, 'tmdb:900000', nontheme, drift, p.theme['reserves'][0]['id']]
        pool = self.run_refresh()
        self.assertTrue(pool['complete'])
        self.assertEqual([s['id'] for s in p.theme['references'] + p.theme['reserves']],
                         [s['meta']['id'] for s in pool['editorial']])
        self.assertEqual(2, sum(i['reserve'] for i in pool['editorial']))
        self.assertEqual(set(ids[:-1]), {i['meta']['id'] for i in pool['dynamic']})
        self.assertEqual('2027-12-01', pool['dynamic'][0]['release'])
        for item in pool['dynamic']:
            self.assertEqual(item['refs'], [p.theme['references'][0]['id']])
            self.assertIn('family reunion', item['traits'])
            self.assertIn('release', item['evidence'])
        # Editorial exemption is ID based; false matches can be suppressed by either alias.
        (self.root / 'config.json').write_text(json.dumps({'seasonal_exclusions': {CHRISTMAS: ['tmdb:900000']}}))
        self.assertEqual(16, len(catalog.read_pool(self.root, CHRISTMAS, NOW)['dynamic']))

    def test_interrupted_import_reuses_cards_then_releases_or_withdraws_on_correction(self):
        p = self.provider
        p.rows = [p.movie(123450 + i) for i in range(6)]
        p.failed = True
        first = self.run_refresh()
        self.assertFalse(first['complete'])
        self.assertEqual(4, len(first['dynamic']))
        p.failed = False
        restored = self.run_refresh(NOW + timedelta(hours=1))
        self.assertTrue(restored['complete'])
        self.assertEqual(6, len(restored['dynamic']))
        p.denied.add(p.rows[0])
        p.records[p.rows[1]]['description'] = 'A police investigation.'
        p.rows.extend([p.movie(123456)])
        next_day = self.run_refresh(NOW + timedelta(days=1))
        self.assertTrue(next_day['complete'])
        self.assertEqual(set(p.rows[2:]), {i['meta']['id'] for i in next_day['dynamic']})
        self.assertTrue(any(s == '2027-12-05' and e == '2027-12-13' for s,e,n in p.page_log))
        self.run_refresh(NOW + timedelta(days=7))
        self.assertTrue(any(date.fromisoformat(e) - date.fromisoformat(s) == timedelta(days=548)
                            and e == '2027-12-19' for s,e,n in p.page_log))

    def test_former_discovery_after_restart_does_not_become_an_original_reference(self):
        p = self.provider
        former, valid, drift = p.movie(22221), p.movie(22222), p.movie(22223, 'A bleak family reunion in June.')
        p.rows = [former]
        self.run_refresh()
        catalog.record_shown(self.root, CHRISTMAS, [former, 'tt99999999999'])
        self.assertFalse(catalog.read_pool(self.root, CHRISTMAS, NOW)['complete'])
        p.secondary = {former: [valid, drift]}
        pool = self.run_refresh()
        found = {i['meta']['id']: i for i in pool['dynamic']}
        self.assertNotIn(drift, found)
        self.assertEqual([former], found[valid]['via'])
        self.assertEqual([p.theme['references'][0]['id']], found[valid]['refs'])
        self.assertTrue(pool['complete'])

    def test_nightly_refresh_rechecks_a_previously_unavailable_new_release(self):
        p = self.provider
        pending = p.movie(111112)
        p.rows = [pending]
        p.denied.add(pending)
        self.assertFalse(self.run_refresh()['dynamic'])
        p.denied.clear()
        fresh = self.run_refresh(NOW + timedelta(days=1))['dynamic']
        self.assertEqual([pending], [x['meta']['id'] for x in fresh])

    def test_every_theme_needs_independent_reference_and_title_evidence(self):
        for slug, theme in THEMES.items():
            with self.subTest(slug=slug):
                signal = 'over the course of a single night' if slug == 'until-sunrise' else theme['signals'][0]
                trait = next(t for seed in theme['references'] for t in seed['traits']
                             if t not in theme['signals'] and t not in catalog.WEAK)
                facts = {'subtype': 'movie', 'countries': ['Denmark'], 'franchise': 'tmdb:10',
                         'edition_minutes': 151 if slug == 'the-extra-hour' else 89,
                         'texts': {'catalog synopsis': f'{signal}. A story about {trait}.'}, 'keywords': [],
                         'award': {'winner': 'tt444', 'ceremony': '98', 'source': 'Academy results'}}
                meta = {'id': 'tt555', 'type': 'movie', 'name': 'Fixture', 'releaseInfo': '2027',
                        'runtime': str(facts['edition_minutes'])+'m', 'genres': ['Family']}
                item = catalog.qualify(theme, meta, facts)
                self.assertIsNotNone(item)
                self.assertTrue(item['refs'])
                for subtype in ('tvSeries', 'tvEpisode', 'tvSpecial', 'short'):
                    self.assertIsNone(catalog.qualify(theme, meta, dict(facts, subtype=subtype)))
                self.assertIsNone(catalog.qualify(theme, dict(meta, type='series'), facts))
                self.assertIsNone(catalog.qualify(theme, meta, dict(facts, texts={}, keywords=[])))

    def test_krisha_alternate_year_applies_only_to_the_reviewed_feature(self):
        theme = THEMES['dinner-is-served']
        krisha = next(seed for seed in theme['reserves'] if seed['title'] == 'Krisha')
        movie = {'type': 'movie', 'id': krisha['id'], 'name': 'Krisha', 'releaseInfo': '2016',
                 'released': '2016-03-18', 'runtime': '83m'}
        with catalog.closing(catalog.database(self.root)) as db, db:
            catalog.start_pass(db, theme, NOW.date())
            def accepted(entry, meta):
                self.provider.card = lambda *_: meta
                catalog.evaluate(db, theme, entry['id'], self.provider, NOW.date(), entry)
                return bool(db.execute('SELECT 1 FROM item WHERE theme=? AND uid=?', (theme['slug'],entry['id'])).fetchone())

            self.assertTrue(accepted(krisha, movie))
            self.assertFalse(accepted(krisha, dict(movie, releaseInfo='2014')))
            self.assertFalse(accepted(krisha, dict(movie, id='tt777')))
            self.assertFalse(accepted(krisha, dict(movie, type='series')))
            other = theme['reserves'][1]
            self.assertFalse(accepted(other, dict(movie, id=other['id'], releaseInfo=str(other['year'] + 1))))

    def test_broad_theme_keyword_is_bounded_and_still_needs_an_original_story_link(self):
        provider = self.provider
        accepted = provider.movie(660001, 'Two strangers meet again.')
        only_theme = provider.movie(660002, 'Two strangers spend Thanksgiving together.')
        stayed_out = provider.movie(660003, 'A Thanksgiving family reunion.')
        pages = []

        def by_keyword(theme, seed, page):
            if seed:
                return {'ids': [], 'next': None}
            pages.append(page)
            return {'ids': {1: [only_theme], 2: [accepted], 4: [stayed_out]}.get(page, []), 'next': page + 1}

        details = provider.details
        def with_overview(meta):
            result = details(meta)
            if meta['id'] == accepted:
                result['texts']['TMDB overview'] = 'They prepare for Thanksgiving dinner and a family reunion.'
            return result

        provider.keyword_movies = by_keyword
        provider.details = with_overview
        slug = 'dinner-is-served'
        for _ in range(6):
            state = catalog.refresh_theme(self.root, slug, NOW, provider=provider, limit=14)
            if state['complete']:
                break
        self.assertEqual([1, 2, 3], pages)
        rows = catalog.read_pool(self.root, slug, NOW)['dynamic']
        self.assertEqual([accepted], [item['meta']['id'] for item in rows])
        self.assertIn(['TMDB theme keyword', ''], rows[0]['evidence']['retrieved'])
        self.assertTrue(any(v['source'] == 'TMDB overview' for vs in rows[0]['evidence']['references'].values() for v in vs))

    def test_every_original_reference_keeps_a_distinct_retrieval_trait(self):
        for theme in THEMES.values():
            for seed in theme['references']:
                self.assertTrue(set(seed['traits']) - set(theme['signals']) - catalog.WEAK, seed['title'])

    def test_story_scope_setting_and_specials(self):
        meta = {'type': 'movie', 'id': 'tt100', 'name': 'Fixture', 'runtime': '151 min'}
        examples = [
            ('fourth-of-july', "During India's Independence Day, a family arranges a fireworks display."),
            ('until-sunrise', 'The story spans twenty years. During an overnight train ride, a contract killer boards.'),
            ('until-sunrise', 'A babysitting job takes place over one night, then the narrative spans 20 years.'),
            ('the-extra-hour', 'Two people sit for an interview on grief.'),
            ('christmas-favorites', 'In this holiday special, Santa Claus discovers a lost letter to Santa.'),
        ]
        for slug, text in examples:
            facts = {'texts': {'synopsis': text}, 'keywords': [], 'subtype': 'tvMovie', 'edition_minutes': 151}
            self.assertIsNone(catalog.qualify(THEMES[slug], meta, facts))
        normal_film = {'subtype':'tvMovie', 'keywords':[], 'texts':{'synopsis':'A family reunion at Christmas.'}}
        self.assertIsNotNone(catalog.qualify(THEMES[CHRISTMAS], meta, normal_film))
        self.assertIsNone(catalog.qualify(THEMES[CHRISTMAS], meta, dict(normal_film,subtype='')))
        self.assertIsNone(catalog.qualify(THEMES[CHRISTMAS], dict(meta,id='tt23546646'), normal_film))

    def test_pool_guard_checks_generation_and_exhaustion_without_network(self):
        empty = catalog.read_pool(self.root, CHRISTMAS, NOW)
        with catalog.pool_guard(self.root, CHRISTMAS, NOW, empty['generation'], False) as stable:
            self.assertTrue(stable)
        imported = self.run_refresh()
        with catalog.pool_guard(self.root, CHRISTMAS, NOW, empty['generation'], False) as stable:
            self.assertFalse(stable)
        with catalog.pool_guard(self.root, CHRISTMAS, NOW, imported['generation'], True) as stable:
            self.assertTrue(stable)

    def test_runtimes_and_oscar_winner_are_factual_gates(self):
        facts = {'subtype':'movie','keywords':['real time','family saga','grief'], 'texts': {},
                 'edition_minutes':89,'award':{'winner':'tt100'}}
        meta = {'id':'tt100','name':'Fixture','type':'movie','runtime':'89m'}
        self.assertIsNotNone(catalog.qualify(THEMES['under-90-minutes'],meta,facts))
        self.assertIsNone(catalog.qualify(THEMES['under-90-minutes'],dict(meta,runtime='90m'),facts))
        self.assertIsNone(catalog.qualify(THEMES['the-extra-hour'],dict(meta,runtime='151m'),facts))
        self.assertIsNone(catalog.qualify(THEMES['the-one-that-got-away'],meta,facts))

    def test_dated_announcements_require_movie_identity_and_regional_proof(self):
        theme = THEMES['may-the-fourth']
        meta = {'id':'tt555','type':'movie','name':'The Hero Awakens','releaseInfo':'2027','runtime':'84m',
                'genres':['Documentary']}
        facts = {'subtype':'movie','texts':{},'keywords':[],'franchise':''}
        self.assertEqual(['tt0076759'], catalog.qualify(theme,meta,facts)['refs'])
        self.assertIsNone(catalog.qualify(theme,dict(meta,type='series'),facts))
        self.assertIsNone(catalog.qualify(theme,dict(meta,releaseInfo='2019'),facts))


class ProviderTest(unittest.TestCase):
    def test_danish_home_release_records_and_real_offers(self):
        self.assertFalse(availability(b'<div class="ott_title">There are no offers for X.</div><script>value: "DK"</script>'))
        self.assertTrue(availability(b'<a href="https://click.justwatch.com/a?uct_country=dk"></a>'))
        for html in (b'Access denied', b'<a href="https://click.justwatch.com/a?uct_country=us"></a>'):
            with self.assertRaises(ProviderError):
                availability(html)
        table = b'<table class="card releases"><h2 id="DK">Denmark</h2>'
        row = b'<tr><td>04/02/2027</td><td>PG</td><td>'
        self.assertEqual([], home_dates(table + row + b'Theatrical</td></tr></table>'))
        self.assertEqual(['2027-04-02'], home_dates(table + row + b'Digital</td></tr></table>'))

    def test_region_cursor_shape_and_runtime_parser(self):
        markup = b'<div id="media-list"><div id="page_1" class="page_wrapper"><a href="/movie/1-hi?q=x">Hi</a>'
        self.assertEqual({'ids':['tmdb:1'],'next':2},
                         regional_page(markup+b'<a class="load_more" data-next-page="2">More</a>',1))
        with self.assertRaises(ProviderError):
            regional_page(markup, 2)
        tags = detail_tags(b'<section class="keywords"><a href="/keyword/12-time-loop/movie">time loop</a>'
                           b'<span class="runtime">1h 29m</span></section>')
        self.assertEqual(['time loop'],tags['keywords'])
        self.assertEqual(89,tags['edition_minutes'])

    def test_results_must_identify_an_actual_different_best_picture_winner(self):
        data = ('Ceremony\tCanonicalCategory\tFilmId\tWinner\n'
                '98\tBEST PICTURE\ttt111\tTrue\n98\tBEST PICTURE\ttt222\t\n'
                '99\tBEST PICTURE\ttt333\t\n98\tBEST DIRECTOR\ttt444\t\n')
        self.assertEqual({'tt222'},set(parse_awards(data.encode())))

    def test_first_exact_theme_keyword_and_english_overview_use_the_existing_detail_request(self):
        provider = PublicProvider(Path('.'))
        looked_up, requests = [], []
        def keyword(phrase):
            looked_up.append(phrase)
            return {'thanksgiving': '1683'}.get(phrase, '')

        def request(url, parse, ttl, payload=None, **kwargs):
            requests.append((url, payload))
            if payload:
                return {'ids': ['tmdb:7'], 'next': None}
            return parse(b'<section class="keywords"></section><div class="overview" dir="auto"><p>They reunite '
                         b'for <em>Thanksgiving</em> dinner &amp; a journey home.</p></div><p>Unrelated review.</p>')

        provider.keyword = keyword
        provider.request = request
        result = provider.keyword_movies({'signals': ['no exact match', 'thanksgiving', 'meal']}, None, 2)
        self.assertEqual(['no exact match', 'thanksgiving'], looked_up)
        self.assertEqual('1683', requests[0][1]['with_keywords'])
        self.assertEqual(['tmdb:7'], result['ids'])
        meta = {'type': 'movie', 'id': 'tmdb:7', 'moviedb_id': '7', 'description': 'An independent synopsis.'}
        facts = provider.details(meta)
        self.assertEqual('They reunite for Thanksgiving dinner & a journey home.', facts['texts']['TMDB overview'])
        self.assertEqual(2, len(requests))
        self.assertTrue(requests[-1][0].endswith('/movie/7?language=en-US'))

    def test_reissue_never_changes_the_fresh_date(self):
        provider = PublicProvider(Path('.'))
        provider.offer = lambda _: 'DK offer'
        provider.request = lambda *args: ['2027-12-10']
        for year, expected in ((2027, '2027-12-10'), (1995, '1995-06-01')):
            meta = {'type':'movie','moviedb_id':'1','released':f'{year}-06-01'}
            self.assertEqual(expected, provider.release_evidence(meta, NOW.date())['date'])


if __name__ == '__main__':
    unittest.main()
