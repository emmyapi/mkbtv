"""Read the shared media catalog. Profile-specific filters exist only in request memory."""

from contextlib import closing
from dataclasses import dataclass
from datetime import date, datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
from threading import BoundedSemaphore
import time
import unicodedata


DATA = Path(os.environ.get('MKBTV_CATALOG_DIR', Path(os.environ.get('MKBTV_STATE_DIR', Path.home() / '.local/share/mkbtv')) / 'catalog'))
PLATFORMS = {"disney-plus", "netflix", "hbo", "prime-video", "hulu"}
CHARTS = {"popular", "trending", "airing-today", "new-and-returning"}
SORTS = {"catalog", "popular", "trending", "top_rated", "newest", "oldest", "title", "title_desc"}
ORDERS = {
    "catalog": "votes DESC,id",
    "newest": "year DESC,id",
    "oldest": "year ASC NULLS LAST,id",
    "title": "name COLLATE NOCASE,id",
    "title_desc": "name COLLATE NOCASE DESC,id",
}
IMDB_CHART = "imdb-top-250"
# IMDb's published Bayesian formula, applied to all remaining rated titles.
# The prior is shared across filters so a title's score is independent of the query.
WEIGHTED_ORDER = (
    "(votes*rating + 25000.0*(SELECT avg(rating) FROM title WHERE rating IS NOT NULL))"
    "/(votes+25000.0) DESC,votes DESC,id"
)
GENRES = {
    k.casefold(): (k,)
    for k in (
        "Action",
        "Adventure",
        "Animation",
        "Biography",
        "Comedy",
        "Crime",
        "Documentary",
        "Drama",
        "Family",
        "Fantasy",
        "History",
        "Horror",
        "Music",
        "Musical",
        "Mystery",
        "News",
        "Romance",
        "Short",
        "Sport",
        "Thriller",
        "War",
        "Western",
    )
}
GENRES.update(
    {
        "science fiction": ("Sci-Fi",),
        "sci-fi": ("Sci-Fi",),
        "sci-fi & fantasy": ("Sci-Fi", "Fantasy"),
        "action & adventure": ("Action", "Adventure"),
        "film noir": ("Film-Noir",),
        "game show": ("Game-Show",),
        "reality": ("Reality-TV",),
        "talk": ("Talk-Show",),
        "war & politics": ("War",),
        "adult": (),
        "tv movie": (),
        "kids": (),
        "soap": (),
    }
)
EXTRA_GENRES = {"war & politics", "kids", "soap"}
CONTENT_ID = re.compile(r"(?:tt[0-9]{1,12}|(?:tmdb|watchmode):[0-9]{1,12})\Z")
WORKERS = BoundedSemaphore(3)
RELEASE_CANDIDATES = 24
# Match release_card's precedence at the same UTC day boundary. A future home
# date alone can announce a direct-to-streaming title.
RELEASE_WINDOWS = {
    "upcoming": "((theatrical_date>? AND (home_date IS NULL OR home_date>?)) "
                "OR (theatrical_date IS NULL AND home_date>?))",
    "cinema": "theatrical_date<=? AND (home_date IS NULL OR home_date>?)",
    "home": "home_date<=?",
}


class QueryError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


def invalid():
    raise QueryError(400, "invalid_query")


def text_field(request, key, default, choices=None, max_length=256):
    value = request.get(key, default)
    if (
        not isinstance(value, str)
        or len(value) > max_length
        or "\x00" in value
        or (choices and value not in choices)
    ):
        invalid()
    return value


def number(request, key, low, high, default=None):
    value = request.get(key, default)
    if value is not None and (type(value) is not int or not low <= value <= high):
        invalid()
    return value


@dataclass(frozen=True)
class Search:
    kind: str
    catalog: str
    genre: str
    keyword: str
    first_year: int | None
    last_year: int | None
    min_rating: int | None
    hide_after: int | None
    sort: str
    watch: str
    release_window: str
    watched: tuple
    offset: int
    limit: int
    expected: str | None


def options(request):
    fields = {
        "type",
        "catalog",
        "sort",
        "watch",
        "releaseWindow",
        "genre",
        "keyword",
        "year_min",
        "year_max",
        "hide_unreleased",
        "watched_movies",
        "watched_series",
        "offset",
        "limit",
        "snapshot",
        "min_rating",
    }
    if not isinstance(request, dict) or request.keys() - fields:
        invalid()
    kind = text_field(request, "type", "any", {"any", "movie", "series"})
    catalog = text_field(request, "catalog", "all", PLATFORMS | CHARTS | {"all"})
    sort = text_field(request, "sort", "trending", SORTS)
    watch = text_field(request, "watch", "any", {"any", "watched", "unwatched"})
    genre = "" if request.get("genre") is None else text_field(request, "genre", "").strip().casefold()
    if genre and genre not in GENRES:
        invalid()
    keyword = text_field(request, "keyword", "", max_length=300).strip()
    first, last = number(request, "year_min", 1, 9999), number(request, "year_max", 1, 9999)
    if first and last and first > last:
        invalid()
    hide = request.get("hide_unreleased", False)
    if type(hide) is not bool:
        invalid()
    release_window = text_field(request, "releaseWindow", "any", {"any", *RELEASE_WINDOWS})
    seen = set()
    if watch != "any":
        for typ, key in (("movie", "watched_movies"), ("series", "watched_series")):
            items = request.get(key, [])
            if not isinstance(items, list) or len(items) > 50000:
                invalid()
            for item in items:
                if not isinstance(item, str) or not CONTENT_ID.fullmatch(item):
                    invalid()
                if kind in ("any", typ):
                    seen.add((typ, item))
    expected = request.get("snapshot")
    offset, limit = number(request, "offset", 0, 2**31 - 1, 0), number(request, "limit", 1, 100, 60)
    if (
        offset is None
        or limit is None
        or (offset and not expected)
        or (
            expected is not None
            and (not isinstance(expected, str) or not re.fullmatch("[0-9a-f]{64}", expected))
        )
    ):
        invalid()
    return Search(
        kind,
        catalog,
        genre,
        keyword,
        first,
        last,
        number(request, "min_rating", 0, 10),
        date.today().year if hide and release_window != "upcoming" else None,
        sort,
        watch,
        release_window,
        tuple(sorted(seen)),
        offset,
        limit,
        expected,
    )


def uri(path):
    return path.resolve().as_uri() + "?mode=ro"


def feed_versions(db, directory, opt):
    wanted = set()
    if opt.catalog != "all":
        wanted.add(opt.catalog)
    if opt.sort in {"trending", "popular"}:
        wanted.add(opt.sort)
    if opt.genre in EXTRA_GENRES:
        wanted.add("genre:" + opt.genre)
    path = directory / "feeds.sqlite3"
    if not wanted and not path.exists():
        return {}
    try:
        db.execute("ATTACH ? AS feeds", (uri(path),))
        versions = {
            key: (snapshot, source)
            for key, snapshot, source in db.execute(
                "SELECT key,snapshot,source FROM feeds.feed WHERE (source != 'watchmode-us-sub' OR updated_at>?) "
                "AND (source != 'justwatch-nuvio-us-sample' OR updated_at>?)",
                (time.time() - 30 * 86400, time.time() - 14 * 86400),
            )
        }
    except sqlite3.Error:
        raise QueryError(503, "catalog_unavailable") from None
    for key in sorted(wanted):
        if key not in versions:
            raise QueryError(503, "ranking_unavailable" if key == opt.sort else "catalog_unavailable")
    return versions


_FOLD = str.maketrans({"æ": "ae", "œ": "oe", "ø": "o", "ł": "l", "ð": "d", "þ": "th", "đ": "d"})


def fold(term):
    text = unicodedata.normalize("NFKD", term.casefold().translate(_FOLD))
    return "".join(c for c in text if c.isalnum())


def keyword_ids(db, term):
    """Match the same folded title, alternate title and cast surfaces used on import."""
    db.execute("CREATE TEMP TABLE matching(id INTEGER PRIMARY KEY)")
    needle = fold(term)
    if not needle:
        return
    if len(needle) >= 3:
        titles = "SELECT rowid FROM title_search WHERE title_search MATCH ?"
        # Identically named people may each have credits. Match the entered
        # person's full name while preserving substring matching for titles.
        people = "SELECT rowid FROM person_search WHERE person_search MATCH ? AND surface=?"
        phrase = '"' + needle + '"'
        title_args, person_args = (phrase,), (phrase, needle)
    else:
        # Trigrams cannot index shorter terms. Read stored text without tokenization.
        titles = "SELECT id FROM title_search_content WHERE instr(c0,?)>0"
        people = "SELECT id FROM person_search_content WHERE c0=?"
        title_args = person_args = (needle,)
    db.execute("INSERT OR IGNORE INTO matching " + titles, title_args)
    db.execute(
        "INSERT OR IGNORE INTO matching SELECT title_id FROM acting WHERE person_id IN (" + people + ")",
        person_args,
    )


def prepare_pools(db, opt, versions):
    """Keep source-native titles discoverable alongside the full IMDb index."""
    pools = [("title", "t.imdb_id")] if opt.catalog == "all" else []
    if not versions:
        return pools
    keys = sorted(versions) if opt.catalog == "all" else [opt.catalog]
    slots = ",".join("?" for _ in keys)
    # Canonicalize a native ID if another active feed publishes its IMDb ID.
    db.execute(
        f"""CREATE TEMP TABLE aliases AS SELECT type,tmdb_id,min(imdb_id) AS imdb_id
        FROM feeds.member WHERE key IN ({",".join("?" for _ in versions)})
        AND imdb_id IS NOT NULL AND tmdb_id IS NOT NULL
        AND ((type='series' AND tmdb_type='tv') OR (type='movie' AND tmdb_type='movie'))
        GROUP BY type,tmdb_id HAVING count(DISTINCT imdb_id)=1""",
        sorted(versions),
    )
    db.execute("CREATE UNIQUE INDEX temp.alias_key ON aliases(type,tmdb_id)")
    missing = "WHERE t.id IS NULL" if opt.catalog == "all" else ""
    db.execute(
        f"""CREATE TEMP TABLE pool AS
        WITH candidates AS (
            SELECT m.type,coalesce(m.imdb_id,a.imdb_id) AS imdb_id,m.tmdb_id,m.source_id,m.name,m.year,m.rank
            FROM feeds.member m LEFT JOIN aliases a ON m.type=a.type AND m.tmdb_id=a.tmdb_id
            WHERE m.key IN ({slots})
        ), members AS (
            SELECT type,coalesce(imdb_id,'tmdb:'||tmdb_id,source_id) AS content_id,
                   imdb_id,name,year,min(rank) AS pos FROM candidates GROUP BY type,content_id
        )
        SELECT coalesce(t.id,-row_number() OVER (ORDER BY m.type,m.content_id)) AS id,
            m.content_id,m.imdb_id,m.type,t.subtype,coalesce(t.name,m.name) AS name,
            coalesce(t.year,m.year) AS year,t.genres,t.rating,t.votes,t.adult,m.pos
        FROM members m LEFT JOIN title t
            ON t.id=cast(substr(m.imdb_id,3) AS INTEGER) AND t.imdb_id=m.imdb_id AND t.type=m.type
        {missing}""",
        keys,
    )
    db.execute("CREATE UNIQUE INDEX temp.pool_id ON pool(id)")
    db.execute("CREATE UNIQUE INDEX temp.pool_content ON pool(type,content_id)")
    pools.append(("temp.pool", "t.content_id"))
    return pools


def feed_id_sql():
    return "coalesce(m.imdb_id,a.imdb_id,'tmdb:'||m.tmdb_id,m.source_id)"


FEED_MEMBERS = " FROM feeds.member m LEFT JOIN aliases a ON a.type=m.type AND a.tmdb_id=m.tmdb_id "


def watched_ids(opt, resolve):
    seen = set(opt.watched)
    for kind, uid in opt.watched:
        if uid.startswith("tmdb:"):
            canonical = resolve(kind, uid) if resolve else None
            if not canonical:
                raise QueryError(503, "watch_unavailable")
            seen.add((kind, canonical))
    return sorted(seen)


def predicates(opt, pool, content_id, *, include_watch=True):
    terms, args = [], []
    if opt.kind != "any":
        terms.append("t.type=?")
        args.append(opt.kind)
    for clause, value in [
        ("t.year>=?", opt.first_year),
        ("t.year<=?", opt.last_year),
        ("t.rating>=?", opt.min_rating),
        ("(t.year<=? OR t.year IS NULL)", opt.hide_after),
    ]:
        if value is not None:
            terms.append(clause)
            args.append(value)
    if opt.keyword:
        if pool == "title":
            terms.append("t.id IN matching")
        else:
            terms.append("(t.id IN matching OR (t.id<0 AND pool_match(t.name)))")
    if opt.genre:
        pieces = []
        for genre in GENRES[opt.genre]:
            pieces.append("instr(','||t.genres||',',?)>0")
            args.append("," + genre + ",")
        if opt.genre == "tv movie":
            pieces.append("t.subtype='tvMovie'")
        elif opt.genre == "adult":
            pieces.append("(t.adult=1 OR instr(','||t.genres||',',',Adult,')>0)")
        elif opt.genre in EXTRA_GENRES:
            pieces.append(
                "(t.type,"
                + content_id
                + ") IN (SELECT m.type,"
                + feed_id_sql()
                + FEED_MEMBERS
                + " WHERE m.key=?)"
            )
            args.append("genre:" + opt.genre)
        terms.append("(" + " OR ".join(pieces) + ")")
    if include_watch and opt.watch != "any":
        exists = "EXISTS" if opt.watch == "watched" else "NOT EXISTS"
        terms.append(exists + " (SELECT 1 FROM watched w WHERE w.type=t.type AND w.uid=" + content_id + ")")
    return terms, args


def filtered(terms):
    return " WHERE " + " AND ".join(terms) if terms else ""


def filter_releases(db, opt, clauses, path, today):
    """Materialize eligible catalog IDs once for counting, ranking and stable paging."""
    db.execute("ATTACH ? AS releases", (uri(path),))
    db.execute("CREATE TEMP TABLE release_matches(id INTEGER PRIMARY KEY)")
    if opt.kind != "series":
        window = RELEASE_WINDOWS[opt.release_window]
        dates = [today] * window.count("?")
        for pool, content_id, terms, args in clauses:
            if pool == "title":
                # The full IMDb index has a numeric primary key and a generated IMDb string.
                key = "t.id"
                identities = "SELECT cast(substr(imdb_id,3) AS INTEGER) FROM eligible WHERE imdb_id IS NOT NULL"
            else:
                key = content_id
                identities = "SELECT imdb_id FROM eligible UNION ALL SELECT 'tmdb:'||tmdb_id FROM eligible"
            db.execute(
                "WITH eligible AS (SELECT imdb_id,tmdb_id FROM releases.movie_release WHERE "
                + window + ") INSERT INTO release_matches SELECT t.id FROM "
                + pool + " t" + filtered(terms + ["t.type='movie'", key + " IN (" + identities + ")"]),
                dates + args,
            )
    # An update to a different movie must not invalidate the next page. Pool
    # identities are stable for the catalog/feeds generations in the snapshot.
    membership = db.execute(
        "SELECT group_concat(id) FROM (SELECT id FROM release_matches ORDER BY id)"
    ).fetchone()[0] or ""
    return membership


def release_candidates(db, opt, pools, today):
    """Select a small public sample. Private watch sets never reach the worker."""
    if opt.offset or opt.kind == "series":
        return []
    ids = []
    # An unindexed combination of rare filters must not delay the result grid
    # just to warm facts. The actual result query has no such time limit.
    # Indexed title/actor matches take priority on a keyword search. Share the
    # time budget so an expensive native feed scan can't starve the IMDb pool.
    broad = not (opt.keyword or opt.first_year or opt.last_year)
    broad_announced = broad and opt.release_window == "upcoming"
    scans = []
    for pool, uid in (pools if opt.keyword or opt.release_window == "upcoming" else reversed(pools)):
        if broad_announced and pool == "title":
            # Reserve a separate index scan for films still to open this year.
            scans.append((pool, uid, True))
        scans.append((pool, uid, False))
    for pool, uid, current_year_only in scans:
        deadline = time.monotonic() + 0.04 / len(scans)
        db.set_progress_handler(lambda: time.monotonic() >= deadline, 1000)
        try:
            terms, args = predicates(opt, pool, uid, include_watch=False)
            terms.extend(["t.type='movie'", "NOT EXISTS (SELECT 1 FROM releases.movie_release_check c "
                          "WHERE c.content_id=" + uid + " AND c.next_check>?)"])
            args.append(int(time.time()))
            order = "t.votes DESC,t.id" if pool == "title" else "t.pos,t.id"
            if pool != "title":
                terms.append("(" + uid + " GLOB 'tt[0-9]*' OR " + uid + " GLOB 'tmdb:[0-9]*')")
            limit = RELEASE_CANDIDATES - len(ids)
            if broad_announced:
                if pool == "title":
                    terms.append("t.year=?" if current_year_only else "t.year>?")
                    if current_year_only:
                        limit = min(limit, RELEASE_CANDIDATES // 2)
                    order = "t.year,t.id DESC"
                else:
                    terms.append("t.year>=?")
                args.append(today.year)
            elif broad and pool == "title" and opt.release_window == "cinema":
                # Let SQLite stop after the first recent titles in vote order.
                # A year-index range scan would sort every film in those years.
                terms.append("+t.year BETWEEN ? AND ?")
                args.extend([today.year - 2, today.year + 1])
            cursor = db.execute("SELECT " + uid + " FROM " + pool + " t" + filtered(terms)
                                + " ORDER BY " + order + " LIMIT ?", [*args, limit])
            ids.extend(row[0] for row in cursor)
        except sqlite3.OperationalError as error:
            if error.sqlite_errorcode != sqlite3.SQLITE_INTERRUPT:
                raise
        finally:
            db.set_progress_handler(None, 0)
        if len(ids) == RELEASE_CANDIDATES:
            break
    return ids


def query_page(request, *, profile, directory=DATA, resolve=None, releases=None):
    opt = options(request)
    if not WORKERS.acquire(timeout=1):
        raise QueryError(503, "busy")
    try:
        seen = watched_ids(opt, resolve)
        with closing(sqlite3.connect(uri(directory / "catalog.sqlite3"), uri=True, timeout=15)) as db:
            db.execute("PRAGMA temp_store=MEMORY")
            db.execute("PRAGMA mmap_size=268435456")
            db.execute("BEGIN")
            version = db.execute("SELECT value FROM meta WHERE key='snapshot'").fetchone()
            if not version:
                raise QueryError(503, "index_unavailable")
            generation = version[0]
            versions = feed_versions(db, directory, opt)
            criteria = (
                profile,
                generation,
                versions,
                opt.kind,
                opt.catalog,
                opt.genre,
                opt.keyword,
                opt.first_year,
                opt.last_year,
                opt.min_rating,
                opt.hide_after,
                "imdb-chart-weighted-v1" if opt.sort == "top_rated" else opt.sort,
                opt.watch,
                seen,
            )
            snapshot = hashlib.sha256(
                json.dumps(criteria, sort_keys=True, separators=(",", ":")).encode()
            ).hexdigest()
            if opt.release_window == "any" and opt.expected is not None and snapshot != opt.expected:
                raise QueryError(409, "snapshot_changed")
            if opt.keyword:
                keyword_ids(db, opt.keyword)
            pools = prepare_pools(db, opt, versions)
            if opt.keyword:
                needle = fold(opt.keyword)
                db.create_function(
                    "pool_match", 1, lambda value: bool(needle) and needle in fold(value or "")
                )
            if opt.watch != "any":
                db.execute(
                    "CREATE TEMP TABLE watched(type TEXT,uid TEXT, PRIMARY KEY(type,uid)) WITHOUT ROWID"
                )
                db.executemany("INSERT INTO watched VALUES (?,?)", seen)
            clauses = []
            for pool, content_id in pools:
                terms, args = predicates(opt, pool, content_id)
                clauses.append((pool, content_id, terms, args))

            candidates = []
            if opt.release_window != "any":
                if releases is None:
                    raise QueryError(503, "release_unavailable")
                today = datetime.now(timezone.utc).date()
                members = filter_releases(db, opt, clauses, releases.path, today.isoformat())
                snapshot = hashlib.sha256((snapshot + opt.release_window + members).encode()).hexdigest()
                if opt.expected is not None and snapshot != opt.expected:
                    raise QueryError(409, "snapshot_changed")
                clauses = [(p, uid, ["t.id IN release_matches"], []) for p, uid in pools]
                candidates = release_candidates(db, opt, pools, today)

            def count(extra=()):
                return sum(
                    db.execute(
                        "SELECT count(*) FROM " + pool + " t" + filtered(terms + list(extra)), args
                    ).fetchone()[0]
                    for pool, _, terms, args in clauses
                )

            def page(extra, order, limit, offset, ranked=False):
                selects, binds = [], []
                for pool, content_id, terms, args in clauses:
                    pos = (
                        "(SELECT pos FROM ranks WHERE id=t.id)"
                        if ranked
                        else ("NULL" if pool == "title" else "t.pos")
                    )
                    selects.append(
                        "SELECT " + content_id + " AS uid,t.imdb_id,t.type,t.name,t.year,t.genres,"
                        "t.rating,t.id,t.votes,"
                        + pos
                        + " AS pos FROM "
                        + pool
                        + " t"
                        + filtered(terms + list(extra))
                    )
                    binds.extend(args)
                union = " UNION ALL ".join(selects)
                if order == WEIGHTED_ORDER:
                    # SQLite requires a surrounding SELECT for a compound result's expression sort.
                    union = "SELECT * FROM (" + union + ")"
                sql = union + " ORDER BY " + order + " LIMIT ? OFFSET ?"
                return db.execute(sql, [*binds, limit, offset]).fetchall()

            total = count()
            ranking = IMDB_CHART if opt.sort == "top_rated" else opt.sort
            ranked_first = ranking in versions and (
                opt.sort == "top_rated" or (opt.sort in {"trending", "popular"} and opt.sort != opt.catalog)
            )
            if ranked_first:
                db.execute("CREATE TEMP TABLE ranks(id INTEGER PRIMARY KEY, pos INTEGER)")
                for pool, content_id in pools:
                    join = (
                        "t.id=cast(substr(" + feed_id_sql() + ",3) AS INTEGER) AND "
                        if pool == "title"
                        else ""
                    )
                    db.execute(
                        "INSERT INTO ranks SELECT t.id,min(m.rank)"
                        + FEED_MEMBERS
                        + " JOIN "
                        + pool
                        + " t ON "
                        + join
                        + content_id
                        + "="
                        + feed_id_sql()
                        + " AND t.type=m.type WHERE m.key=? GROUP BY t.id",
                        (ranking,),
                    )
                first_terms = ["t.id IN (SELECT id FROM ranks)"]
                first_count = count(first_terms)
                chart_order = "pos,rating DESC,votes DESC,id" if opt.sort == "top_rated" else "pos,id"
                rows = (
                    page(first_terms, chart_order, opt.limit, opt.offset, ranked=True)
                    if opt.offset < first_count
                    else []
                )
                if len(rows) < opt.limit:
                    rows.extend(
                        page(
                            ["t.id NOT IN (SELECT id FROM ranks)"],
                            WEIGHTED_ORDER if opt.sort == "top_rated" else "votes DESC,id",
                            opt.limit - len(rows),
                            max(0, opt.offset - first_count),
                        )
                    )
            elif opt.sort == "top_rated":
                rows = page([], WEIGHTED_ORDER, opt.limit, opt.offset)
            else:
                order = (
                    "pos,id"
                    if opt.catalog != "all" and opt.sort in {"catalog", opt.catalog}
                    else ORDERS[opt.sort]
                )
                rows = page([], order, opt.limit, opt.offset)
            metas = []
            for uid, imdb, kind, name, year, genres, rating, _, _, _ in rows:
                item = {"id": uid, "type": kind, "name": name, "posterShape": "poster"}
                if imdb:
                    item["imdb_id"] = imdb
                if year:
                    item["releaseInfo"] = str(year)
                if genres:
                    item["genres"] = genres.split(",")
                if rating is not None:
                    item["imdbRating"] = str(rating)
                metas.append(item)
            end = opt.offset + len(rows)
            result = {
                "metas": metas,
                "total": total,
                "next_offset": end if end < total else None,
                "snapshot": snapshot,
            }
            notes = []
            displayed = (
                PLATFORMS.intersection(versions)
                if opt.catalog == "all"
                else PLATFORMS.intersection({opt.catalog})
            )
            sources = {versions[key][1] for key in displayed}
            if "watchmode-us-sub" in sources:
                notes.append("US subscription availability from Watchmode.")
            if "justwatch-nuvio-us-sample" in sources:
                notes.append(
                    "JustWatch US selection via JustWatch Nuvio, up to 250 movies and 250 series per service."
                )
            if opt.genre in EXTRA_GENRES:
                notes.append("Supplemental genre tags come from Nuvio's published series catalogs.")
            if opt.catalog in CHARTS or opt.sort in {"popular", "trending"}:
                gaps = db.execute(
                    "SELECT 1 FROM feeds.chart_gap WHERE key IN (?,?) AND (type=? OR ?='any') LIMIT 1",
                    (opt.catalog, opt.sort, opt.kind, opt.kind),
                ).fetchone()
                if gaps:
                    notes.append("The source returned empty pages within this chart.")
            if notes:
                result["coverage"] = " ".join(notes)
        if opt.release_window != "any":
            result["releasePending"] = False
            if candidates:
                try:
                    result["releasePending"] = releases.lookup(candidates)["pending"]
                except (OSError, sqlite3.Error):
                    pass  # A busy preparation queue cannot invalidate the completed page.
        if opt.watch != "any" and releases is not None:
            # The client doesn't send a separate enrichment request for pages
            # based on private watched history. Only read already known facts.
            movie_ids = [meta["id"] for meta in metas if meta["type"] == "movie"]
            if movie_ids:
                try:
                    result["movieReleases"] = releases.lookup(movie_ids, enqueue=False)["releases"]
                except (OSError, sqlite3.Error):
                    pass
        return result
    except (sqlite3.Error, OSError) as error:
        raise QueryError(503, "index_unavailable") from error
    finally:
        WORKERS.release()
