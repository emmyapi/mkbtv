"""Durable, profile-independent seasonal catalog. Home reads published records locally."""

from contextlib import closing, contextmanager
from datetime import date, datetime, timedelta, timezone
import fcntl
import hashlib
import json
from pathlib import Path
import re
import sqlite3

import discovery as d
from seasonal_calendar import COPENHAGEN, THEMES
from seasonal_provider import DAY, ProviderError, PublicProvider, day_value, minutes, release_year

# The keys are the editor's original seed traits. Variants describe the same
# premise in catalog language. Neither title, release month, actor nor genre is
# searched for thematic or seed evidence.
VARIANTS = {
    'family reunion': ['family reunites', 'reunite with his family', 'reunite with her family',
                     'reunion with his family', 'estranged family', 'brings his family together'],
    'family christmas': ['family christmas', 'family for christmas', 'christmas with his family',
                         'christmas with her family', 'christmas with their family', 'family for the holidays'],
    'child left home alone': ['left home alone', 'left behind by his family', 'home invasion', 'burglars'],
    'toy maker': ['toymaker', 'toy workshop', 'toy factory'],
    'letters to santa': ['letter to santa', 'christmas letters', 'letter for santa'],
    'christmas gifts': ['christmas presents', 'christmas gift', 'holiday gifts'],
    'father and son': ['father-son relationship', 'father son relationship', 'estranged father'],
    'christmas spirit': ['spirit of christmas', 'holiday spirit', 'save christmas'],
    'christmas ghosts': ['ghost of christmas', 'ghosts of christmas', 'ebenezer scrooge'],
    'redemption': ['second chance at life', 'change his ways', 'redeem himself', 'redeem herself'],
    'holiday romance': ['finds love', 'falls in love', 'fall in love', 'romantic christmas',
                        'rekindle their romance', 'rekindles a romance', 'holiday love'],
    'christmas vacation': ['christmas holiday', 'holiday vacation', 'home for christmas'],
    'christmas delivery': ['deliver the presents', 'deliver presents', 'deliver gifts', 'deliver the gifts'],
    'becoming santa': ['become santa', 'becomes santa', 'replace santa', 'new santa claus'],
    'christmas elves': ["santa's elves", 'christmas elf', 'north pole elf', 'an elf'],
    'time loop': ['temporal loop', 'caught in a loop', 'reliving the same day'],
    'same day': ['the same day over', 'relive the day'],
    'unlikely friendship': ['unlikely friends', 'unexpected friendship', 'unlikely bond'],
    'fall in love': ['falls in love', 'falling in love'], 'con artist': ['con artists', 'con man'],
    'plane crash': ['plane crashes', 'crash landing'], 'snowbound': ['snowed in'],
    'private detective': ['private investigator'], 'multiple suspects': ['suspects'],
    'family reconciliation': ['reunites with his family', 'reunites with her family'],
    'halloween night': ['on halloween', "all hallows' eve"],
    'thanksgiving dinner': ['thanksgiving meal', 'thanksgiving feast'],
    'holiday fireworks': ['fireworks display'], 'midnight countdown': ['countdown to midnight'],
    'the clone wars': ['clone wars'], 'a new hope': ['original star wars film'],
    'supernatural curse': ['ancient curse', 'deadly curse', 'mysterious curse'],
}
# A theme word cannot double as a seed link. Broad incidental keywords also
# cannot establish a seed relationship without a more specific story premise.
WEAK = {'relationship', 'family', 'friendship', 'adventure', 'holiday', 'christmas eve', 'crime', 'drama',
        'romance', 'comedy', 'horror', 'action', 'winter', 'summer', 'midnight', 'costume', 'army'}
ANNOUNCEMENT = {
    'may-the-fourth': ('The Hero Awakens', 2027, 'tt0076759',
                       'https://www.starwars.com/news/star-wars-the-hero-awakens'),
}
# The studio explicitly lists this IMDb "TV movie" as a special belonging to a
# series. These format corrections take precedence over coarser catalog types.
ATTACHED_SPECIALS = {
    'tt23546646': 'https://prod.dreamworks.com/shows/boss-baby%3A-back-in-the-crib',
}
REVISION = 3
THEME_KEYWORD_PAGES = 3
SCHEMA = '''
CREATE TABLE IF NOT EXISTS run(theme TEXT PRIMARY KEY, revision TEXT NOT NULL, day TEXT NOT NULL,
    full_day TEXT NOT NULL, checked TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS job(theme TEXT, kind TEXT, arg TEXT, priority INTEGER, due REAL DEFAULT 0,
    done INTEGER DEFAULT 0, PRIMARY KEY(theme,kind,arg));
CREATE INDEX IF NOT EXISTS job_due ON job(theme,done,priority,due);
CREATE TABLE IF NOT EXISTS item(theme TEXT, uid TEXT, revision TEXT, seen TEXT, data TEXT,
    PRIMARY KEY(theme,uid));
CREATE TABLE IF NOT EXISTS origin(theme TEXT, uid TEXT, source TEXT, seed TEXT,
    PRIMARY KEY(theme,uid,source,seed));
CREATE TABLE IF NOT EXISTS shown(theme TEXT, uid TEXT, data TEXT, PRIMARY KEY(theme,uid));
'''


def encode(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def revision(theme):
    return hashlib.sha256(encode((REVISION, theme)).encode()).hexdigest()[:16]


def public_path(root):
    return Path(root) / 'seasonal' / 'catalog.sqlite3'


def database(root):
    path = public_path(root)
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    db = sqlite3.connect(path, timeout=8)
    db.row_factory = sqlite3.Row
    if db.execute('PRAGMA user_version').fetchone()[0] == 0:
        db.execute('PRAGMA journal_mode=WAL')
        db.executescript('BEGIN IMMEDIATE; ' + SCHEMA + ' PRAGMA user_version=1; COMMIT;')
    return db


def normal(text):
    return ' ' + ' '.join(re.findall(r'[\w]+', str(text).casefold().replace('’', "'"))) + ' '


def evidence(phrase, surfaces):
    for fragment in [phrase, *VARIANTS.get(phrase, [])]:
        needle = normal(fragment)
        for source, text in surfaces:
            if needle in text:
                return {'trait': phrase, 'matched': fragment, 'source': source}
    return None


def qualify(theme, meta, facts):
    slug = theme['slug']
    if meta.get('type') != 'movie' or facts.get('subtype') not in ('movie', 'tvMovie', 'video'):
        return None
    if meta['id'] in ATTACHED_SPECIALS or meta.get('imdb_id') in ATTACHED_SPECIALS:
        return None
    if any(re.search(r'\b(?:tv special|television special|holiday special|special episode|series special)\b',
                     text, re.I) for text in [*facts['texts'].values(), *facts['keywords']]):
        return None
    duration = minutes(meta.get('runtime'))
    if not duration or duration < 40 or {'Short', 'Adult'}.intersection(meta.get('genres') or []):
        return None
    if re.search(r'\b(?:featurette|trailer|season \d+|episode \d+ of)\b', meta['name'], re.I):
        return None
    # A synopsis duplicated by providers counts once, and keywords stay separate
    # from it. Require a documented story trait in addition to the theme rule.
    surfaces, seen = [], set()
    for source, text in [*facts['texts'].items(), *(('TMDB keyword', kw) for kw in facts['keywords'])]:
        value = normal(text)
        if value.strip() and value not in seen:
            seen.add(value)
            surfaces.append((source, value))
    signals = [e for signal in theme['signals'] if (e := evidence(signal, surfaces))]
    if slug in ('under-90-minutes', 'the-extra-hour'):
        if duration != facts.get('edition_minutes') or not (
            duration < 90 if slug == 'under-90-minutes' else duration >= 150
        ):
            return None
        if slug == 'the-extra-hour':
            scale = ('epic journey', 'epic quest', 'historical epic', 'epic romance', 'political epic',
                     'ensemble drama', 'family saga', 'epic spectacle', 'historical disaster', 'crime dynasty',
                     'spans generations', 'across generations', 'over several decades', 'spanning decades')
            if not any(evidence(term, surfaces) for term in scale):
                return None
        signals = [{'trait': f'{duration} minute feature edition', 'source': 'catalog and TMDB runtimes'}]
    elif slug == 'the-one-that-got-away':
        award = facts.get('award')
        if not award or award.get('winner') in (None, meta['id'], meta.get('imdb_id')):
            return None
        signals = [{'trait': 'Best Picture nomination', **award}]
    elif slug == 'made-in-denmark':
        if not facts['countries'] or facts['countries'][0].casefold() not in ('denmark', 'dk'):
            return None
        signals = [{'trait': 'Danish production', 'source': 'Cinemeta production country'}]
    elif slug == 'may-the-fourth':
        announcement = ANNOUNCEMENT[slug]
        announced = normal(meta['name']) == normal(announcement[0]) and release_year(meta) == announcement[1]
        documentary = 'Documentary' in (meta.get('genres') or [])
        if announced and documentary:
            signals = [{'trait': 'documentary on the original Star Wars film', 'source': announcement[3]}]
            surfaces.append(('Lucasfilm announcement', normal('a new hope')))
        elif facts['franchise'] == 'tmdb:10':
            signals = [{'trait': 'canonical Star Wars film', 'source': 'TMDB Star Wars Collection'}]
        elif not (documentary and signals and any(evidence(s, surfaces) for s in
                       ('making of', 'behind the scenes', 'filmmaking', 'production of', 'making star wars'))):
            return None
    elif slug == 'fourth-of-july':
        dates = ('fourth of july', '4th of july', 'july 4', 'july 4th', 'july fourth',
                 'american independence day', 'u.s. independence day', 'us independence day')
        signals = [e for term in dates if (e := evidence(term, surfaces))]
    elif slug == 'until-sunrise':
        # An overnight train, a nocturnal prologue or a deadline in a multiyear
        # story does not say that the *principal action* takes place that night.
        structure = ('over the course of a night', 'over the course of one night', 'over the course of a single night',
                     'over the course of the night', 'in a single night', 'one-night odyssey', 'all in one night',
                     'takes place in one night', 'takes place over one night', 'over one night', 'one eventful night',
                     'over a single night', 'in the span of one night', 'through one night')
        signals = [e for term in structure if (e := evidence(term, surfaces))]
        multi_year = r'\b(?:spans?|spanning|over|across|throughout) (?:the (?:course of |next )?)?(?:several |many |[a-z]+ |[0-9]+ )?(?:years|decades|generations)\b'
        if any(re.search(multi_year, text) for source, text in surfaces if source != 'TMDB keyword'):
            return None
    elif slug == 'the-morning-after' and not {'Family', 'Comedy'}.intersection(meta.get('genres') or []):
        return None
    if not signals:
        return None
    factual = slug in ('under-90-minutes', 'the-extra-hour', 'the-one-that-got-away', 'made-in-denmark', 'may-the-fourth')
    blocked = {normal(s['trait']) for s in signals}
    if not factual:
        blocked.update(normal(s) for s in theme['signals'])
    matches = {}
    for seed in theme['references']:
        rows = [row for t in seed['traits'] if t not in WEAK and normal(t) not in blocked
                if (row := evidence(t, surfaces)) and normal(row['matched']) not in blocked]
        if rows:
            matches[seed['id']] = rows
    if not matches:
        return None
    traits = sorted({row['trait'] for values in matches.values() for row in values})
    independent_links = min(len(matches), len(traits))
    # Multiple synonyms or providers repeating a synopsis cannot inflate its score.
    keyword_and_story = {row.get('source') == 'TMDB keyword' for row in signals} == {True, False}
    score = (7.7 + .2 * min(2, len(signals) - 1) + .2 * keyword_and_story +
             .6 * min(4, independent_links) + .2 * min(5, len(traits)))
    first_id, first_rows = next(iter(matches.items()))
    seed_title = next(seed['title'] for seed in theme['references'] if seed['id'] == first_id)
    return {'refs': list(matches), 'traits': traits, 'reason': f"{first_rows[0]['trait'].capitalize()}, as in {seed_title}.",
            'score': round(score, 2),
            'evidence': {'theme': signals, 'references': matches}}


def enqueue(db, slug, kind, arg, priority, *, repeat=False):
    arg = encode(arg)
    if repeat:
        db.execute('INSERT INTO job(theme,kind,arg,priority) VALUES(?,?,?,?) '
                   'ON CONFLICT(theme,kind,arg) DO UPDATE SET done=0,priority=min(job.priority,excluded.priority) '
                   'WHERE job.done=1 OR job.priority>excluded.priority', (slug, kind, arg, priority))
    else:
        db.execute('INSERT OR IGNORE INTO job(theme,kind,arg,priority) VALUES(?,?,?,?)',
                   (slug, kind, arg, priority))


def record_ids(db, slug, ids, source, seed='', priority=25):
    for rank, uid in enumerate(dict.fromkeys(ids)):
        if not isinstance(uid, str) or not d.ID.fullmatch(uid):
            continue
        new = db.execute('INSERT OR IGNORE INTO origin VALUES(?,?,?,?)', (slug, uid, source, seed)).rowcount
        enqueue(db, slug, 'candidate', uid, priority + min(rank, 49), repeat=bool(new))


def start_pass(db, theme, today):
    slug, rev = theme['slug'], revision(theme)
    run = db.execute('SELECT * FROM run WHERE theme=?', (slug,)).fetchone()
    if run and run['day'] == str(today) and run['revision'] == rev:
        return
    full = not run or run['revision'] != rev or today - date.fromisoformat(run['full_day']) >= timedelta(days=7)
    if run and run['revision'] != rev:
        db.execute('DELETE FROM job WHERE theme=?', (slug,))
        db.execute('DELETE FROM origin WHERE theme=?', (slug,))
    # Retain unfinished work from an interrupted pass, including future pages.
    db.execute('DELETE FROM job WHERE theme=? AND done=1', (slug,))
    old = day_value(run['day']) if run else None
    since = today - timedelta(days=548) if full else min(old or today, today) - timedelta(days=7)
    if run and not old and not full:
        since = today - timedelta(days=7)
    full_day = str(today) if full else run['full_day']
    db.execute('INSERT INTO run VALUES(?,?,?,?,?,0) ON CONFLICT(theme) DO UPDATE SET revision=excluded.revision,'
               'day=excluded.day,full_day=excluded.full_day', (slug, rev, str(today), full_day, ''))
    for index, seed in enumerate(theme['references'] + theme['reserves']):
        enqueue(db, slug, 'editorial', seed['id'], index)
    for i, seed in enumerate(theme['references']):
        enqueue(db, slug, 'keywords', [seed['id'], 1], 38 + i)
        for j, provider in enumerate(('tmdb', 'simkl')):
            enqueue(db, slug, 'related', (seed['id'], provider, False), 10 + 2*i + j)
    enqueue(db, slug, 'theme_keyword', 1, 11)
    for index, phrase in enumerate(theme['signals']):
        enqueue(db, slug, 'search', phrase, 16 + index)
    announcement = ANNOUNCEMENT.get(slug)
    if announcement:
        enqueue(db, slug, 'search', announcement[0], 10)
    if slug == 'the-one-that-got-away':
        enqueue(db, slug, 'award_list', '', 25)
    enqueue(db, slug, 'releases', (str(since), str(today), 1), 20)
    editorial_ids = {seed['id'] for seed in theme['references'] + theme['reserves']}
    for row in db.execute('SELECT uid FROM item WHERE theme=?', (slug,)):
        if row[0] not in editorial_ids:
            enqueue(db, slug, 'candidate', row[0], 35)
    for row in db.execute('SELECT uid FROM shown WHERE theme=?', (slug,)):
        for provider in ('tmdb', 'simkl'):
            enqueue(db, slug, 'related', (row[0], provider, True), 30)


def store(db, theme, uid, today, item):
    slug = theme['slug']
    if item is None:
        changed = db.execute('DELETE FROM item WHERE theme=? AND uid=?', (slug, uid)).rowcount
    else:
        payload = encode(item)
        old = db.execute('SELECT data,revision FROM item WHERE theme=? AND uid=?', (slug, uid)).fetchone()
        rev = revision(theme)
        changed = not old or old['data'] != payload or old['revision'] != rev
        db.execute('INSERT OR REPLACE INTO item VALUES(?,?,?,?,?)', (slug, uid, rev, str(today), payload))
    if changed:
        db.execute('UPDATE run SET generation=generation+1 WHERE theme=?', (slug,))


def evaluate(db, theme, requested, provider, today, selected=None):
    slug = theme['slug']
    meta = provider.card(selected['type'] if selected else 'movie', requested)
    if not meta:
        return store(db, theme, requested, today, None)
    actual_ids = {uid for _, uid in d.title_keys(meta)}
    if selected:
        # Some distributors date a feature from its general release. An editor
        # can cite that year for this precise IMDb title and format only.
        year = release_year(meta)
        accepted_year = year == selected['year'] or str(year) in selected.get('alternate_release_years', {})
        imdb_ids = {uid for uid in actual_ids if uid.startswith('tt')}
        if imdb_ids != {selected['id']} or meta['type'] != selected['type'] or not accepted_year:
            return store(db, theme, selected['id'], today, None)
    keys = actual_ids | {requested}
    canonical = next((uid for uid in sorted(keys) if uid.startswith('tt')), meta['id'])
    meta = dict(meta, id=canonical)
    if canonical.startswith('tt'):
        meta['imdb_id'] = canonical
    if not selected and keys.intersection(seed['id'] for seed in theme['references'] + theme['reserves']):
        return
    if selected:
        if selected.get('runtime_minutes') and minutes(meta.get('runtime')) != selected['runtime_minutes']:
            return store(db, theme, canonical, today, None)
        item = {'refs': [canonical] if selected in theme['references'] else [], 'traits': selected['traits'],
                'score': 9.1, 'reason': selected['note'], 'reserve': selected in theme['reserves'],
                'evidence': {'editorial': selected['note']}, 'group': 'editorial'}
    else:
        if meta['type'] != 'movie' or (minutes(meta.get('runtime')) or 0) < 40:
            return store(db, theme, canonical, today, None)
    facts = provider.details(meta)
    if selected:
        surfaces = [('synopsis', normal(t)) for t in facts['texts'].values()]
        surfaces += [('TMDB keyword', normal(t)) for t in facts['keywords']]
        corroborated = [t for t in selected['traits'] if evidence(t, surfaces)]
        item['score'] = round(9.0 + min(3, len(corroborated)) * .28, 2)
        item['evidence']['confirmed_traits'] = corroborated
    else:
        facts['subtype'] = provider.subtype(canonical)
        if slug == 'the-one-that-got-away':
            facts['award'] = provider.awards().get(canonical)
        item = qualify(theme, meta, facts)
        if not item:
            return store(db, theme, canonical, today, None)
        item['group'] = 'dynamic'
    release = provider.release_evidence(meta, today)
    if not release:
        return store(db, theme, canonical, today, None)
    sources = []
    for alias in sorted(keys):
        sources.extend(db.execute('SELECT source,seed FROM origin WHERE theme=? AND uid=?', (slug, alias)))
    origins = sorted({(row['source'], row['seed']) for row in sources})
    item['evidence']['retrieved'] = origins
    item['evidence']['release'] = release
    item.update(meta=meta, release=release['date'], franchise=facts['franchise'],
                via=sorted({seed for provider, seed in origins if provider.startswith('secondary:')}))
    store(db, theme, canonical, today, item)


def process(db, theme, kind, arg, provider, today):
    slug = theme['slug']
    editorial = {seed['id']: seed for seed in theme['references'] + theme['reserves']}
    if kind in ('editorial', 'candidate'):
        evaluate(db, theme, arg, provider, today, editorial.get(arg) if kind == 'editorial' else None)
    elif kind == 'related':
        uid, source, secondary = arg
        if secondary:
            row = db.execute('SELECT data FROM shown WHERE theme=? AND uid=?', (slug, uid)).fetchone()
            seed = json.loads(row[0])['meta'] if row else None
        else:
            entry = editorial[uid]
            seed = provider.card(entry['type'], uid) or {'id': uid, 'type': entry['type']}
        if seed:
            record_ids(db, slug, provider.related(seed, source),
                       ('secondary:' if secondary else 'reference:') + source, uid, 27)
    elif kind == 'theme_keyword':
        rows = provider.keyword_movies(theme, None, arg)
        record_ids(db, slug, rows['ids'], 'TMDB theme keyword', priority=14)
        if rows['next'] and arg < THEME_KEYWORD_PAGES:
            enqueue(db, slug, kind, rows['next'], 15 + arg)
    elif kind == 'keywords':
        seed_id, page = arg
        rows = provider.keyword_movies(theme, editorial[seed_id], page)
        record_ids(db, slug, rows['ids'], 'TMDB theme and seed keywords', seed_id, 32)
        if rows['next']:
            enqueue(db, slug, kind, [seed_id, rows['next']], 42 + min(page, 45))
    elif kind == 'search':
        record_ids(db, slug, provider.search(arg), 'search', arg, 25)
    elif kind == 'announcement':
        title = ANNOUNCEMENT[slug][0]
        for uid in provider.search(title):
            if d.ID.fullmatch(uid):
                record_ids(db, slug, [uid], 'Lucasfilm announcement search', title, 10)
                enqueue(db, slug, 'candidate', uid, 10, repeat=True)
    elif kind == 'award_list':
        record_ids(db, slug, reversed(list(provider.awards())), 'Best Picture nominations', priority=39)
    elif kind == 'releases':
        start, end, page = arg
        rows = provider.releases(start, end, page)
        record_ids(db, slug, rows['ids'], 'DK home release', priority=40)
        if rows['next']:
            enqueue(db, slug, 'releases', [start, end, rows['next']], 29 + min(page, 45))


def refresh_theme(root, theme_id, now=None, *, limit=24, provider=None):
    now = now or datetime.now(timezone.utc)
    today = now.astimezone(COPENHAGEN).date()
    theme = THEMES[theme_id]
    path = public_path(root)
    announced = ANNOUNCEMENT.get(theme_id)
    probe = announced and today.year == announced[1] and today.month == 5 and today.day in (3, 4)
    interval = 4 * 3600
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    with (path.parent / (theme_id + '.lock')).open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {'complete': False, 'pending': 1, 'next_check': now.timestamp() + 60, 'processed': 0}
        with closing(database(root)) as db:
            with db:
                start_pass(db, theme, today)
                if probe:
                    enqueue(db, theme_id, 'announcement', int(now.timestamp()) // interval, 10)
            provider = provider or PublicProvider(root)
            processed = 0
            for _ in range(max(1, min(limit, 100))):
                job = db.execute('SELECT kind,arg FROM job WHERE theme=? AND done=0 AND due<=? '
                                 'ORDER BY priority,rowid LIMIT 1', (theme_id, now.timestamp())).fetchone()
                if not job:
                    break
                try:
                    with db:
                        process(db, theme, job['kind'], json.loads(job['arg']), provider, today)
                        db.execute('UPDATE job SET done=1 WHERE theme=? AND kind=? AND arg=?',
                                   (theme_id, job['kind'], job['arg']))
                except (d.DiscoveryError, ProviderError, ValueError, OSError):
                    with db:
                        db.execute('UPDATE job SET due=? WHERE theme=? AND kind=? AND arg=?',
                                   (now.timestamp() + 1800, theme_id, job['kind'], job['arg']))
                processed += 1
            pending, due = db.execute('SELECT count(*),min(due) FROM job WHERE theme=? AND done=0',
                                      (theme_id,)).fetchone()
            if not pending:
                with db:
                    db.execute('UPDATE run SET checked=? WHERE theme=?', (str(today), theme_id))
            boundary = datetime.combine(today + timedelta(days=1), datetime.min.time(), COPENHAGEN).timestamp()
            if probe:
                boundary = min(boundary, (int(now.timestamp()) // interval + 1) * interval)
            return {'complete': not pending, 'pending': pending, 'processed': processed,
                    'next_check': min(boundary, max(now.timestamp() + 1, due or 0)) if pending else boundary}


def exclusion_ids(root, theme_id):
    config = Path(root) / 'config.json'
    return set(json.loads(config.read_text()).get('seasonal_exclusions', {}).get(theme_id, [])) if config.exists() else set()


def generation_key(rev, counter, excluded):
    value = f'{rev}-{counter}'
    if excluded:
        value += '-' + hashlib.sha256(encode(sorted(excluded)).encode()).hexdigest()[:16]
    return value


def read_pool(root, theme_id, now=None):
    now = now or datetime.now(timezone.utc)
    today = now.astimezone(COPENHAGEN).date()
    theme = THEMES[theme_id]
    rev = revision(theme)
    pool = {'editorial': [], 'dynamic': [], 'complete': False, 'generation': rev + '-0'}
    path = public_path(root)
    if not path.exists():
        return pool
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=8)) as db:
        db.execute('BEGIN')
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='run'").fetchone():
            return pool
        run = db.execute('SELECT revision,generation,checked FROM run WHERE theme=?', (theme_id,)).fetchone()
        if not run or run[0] != rev:
            return pool
        pending = db.execute('SELECT 1 FROM job WHERE theme=? AND done=0 LIMIT 1', (theme_id,)).fetchone()
        pool['complete'] = run[2] == str(today) and not pending
        excluded = exclusion_ids(root, theme_id)
        pool['generation'] = generation_key(rev, run[1], excluded)
        for row in db.execute('SELECT data FROM item WHERE theme=? AND revision=? AND seen>=?',
                              (theme_id, rev, str(today - timedelta(days=7)))):
            item = json.loads(row[0])
            if not excluded.intersection(uid for _, uid in d.title_keys(item['meta'])):
                pool[item['group']].append(item)
    order = {seed['id']: i for i, seed in enumerate(theme['references'] + theme['reserves'])}
    pool['editorial'].sort(key=lambda item: order[item['meta']['id']])
    pool['dynamic'].sort(key=lambda item: (-item['score'], item['meta']['id']))
    return pool


def record_shown(root, theme_id, dynamic_ids):
    if not dynamic_ids or not public_path(root).exists():
        return
    with closing(sqlite3.connect(public_path(root), timeout=8)) as db, db:
        for uid in set(dynamic_ids):
            row = db.execute('SELECT data FROM item WHERE theme=? AND uid=?', (theme_id, uid)).fetchone()
            if not row or json.loads(row[0])['group'] != 'dynamic':
                continue
            added = db.execute('INSERT OR IGNORE INTO shown VALUES(?,?,?)', (theme_id, uid, row[0])).rowcount
            if added:
                for source in ('tmdb', 'simkl'):
                    enqueue(db, theme_id, 'related', (uid, source, True), 30)

@contextmanager
def pool_guard(root, theme_id, now, expected_generation, expected_complete):
    """Verify a profile publication against a consistent short-lived local catalog view."""
    rev = revision(THEMES[theme_id])
    day = str(now.astimezone(COPENHAGEN).date())
    with closing(database(root)) as db:
        db.execute('BEGIN IMMEDIATE')
        try:
            row = db.execute('SELECT revision,generation,checked FROM run WHERE theme=?', (theme_id,)).fetchone()
            generation, complete = rev + '-0', False
            if row and row['revision'] == rev:
                generation = generation_key(rev, row['generation'], exclusion_ids(root, theme_id))
                pending = db.execute('SELECT 1 FROM job WHERE theme=? AND done=0 LIMIT 1', (theme_id,)).fetchone()
                complete = row['checked'] == day and not pending
            yield generation == expected_generation and complete == expected_complete
        finally:
            db.rollback()
