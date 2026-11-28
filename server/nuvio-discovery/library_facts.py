"""Read Discover evidence for a finite set of saved titles. Membership stays in request memory."""

from collections import defaultdict, namedtuple
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import sqlite3

from discover_query import CONTENT_ID, EXTRA_GENRES, GENRES, RATING_PRIOR_VOTES, QueryError, WORKERS, active_feeds, fold, invalid, text_field, uri
from movie_releases import release_card


# The public mean is constant throughout one published IMDb generation.
_public_rating_prior = (None, None)


def rating_prior(db, generation):
    global _public_rating_prior
    cached_key, value = _public_rating_prior
    if cached_key != generation:
        value = db.execute("SELECT avg(rating) FROM title WHERE rating IS NOT NULL").fetchone()[0]
        _public_rating_prior = (generation, value)
    return value


def request_titles(request):
    if not isinstance(request, dict) or request.keys() - {"titles", "keyword"}:
        invalid()
    titles = request.get("titles")
    if not isinstance(titles, list) or not 1 <= len(titles) <= 100:
        invalid()
    seen = set()
    for item in titles:
        if not isinstance(item, dict) or item.keys() - {"id", "type", "tmdbId"}:
            invalid()
        uid = item.get("id")
        if item.get("type") not in ("movie", "series") or not isinstance(uid, str) or not CONTENT_ID.fullmatch(uid):
            invalid()
        if "tmdbId" in item and (not uid.startswith("tt") or type(item["tmdbId"]) is not int
                                 or not 0 < item["tmdbId"] < 10**12):
            invalid()
        key = item["type"], uid, item.get("tmdbId")
        if key in seen:
            invalid()
        seen.add(key)
    return titles, text_field(request, "keyword", "", max_length=300).strip()


Member = namedtuple("Member", "feed imdb native source name year rank")


def members_for(db, versions, kind, column, values):
    if not versions or not values:
        return []
    # Column names come from the three internal identity lookups below.
    binds = (kind, json.dumps(list(versions)), json.dumps(sorted(values)))
    rows = db.execute(f"""
        SELECT key,imdb_id,tmdb_id,source_id,name,year,rank FROM feeds.member
        WHERE type=? AND key IN (SELECT value FROM json_each(?))
          AND {column} IN (SELECT value FROM json_each(?))
          AND (tmdb_type IS NULL OR tmdb_type=CASE type WHEN 'series' THEN 'tv' ELSE 'movie' END)
    """, binds)
    return map(Member._make, rows)


def resolve_titles(db, versions, kind, titles, cached_imdb, release_natives):
    """Join source-, IMDb- and native-ID feed rows without transferring ambiguous native mappings."""
    by_source, by_imdb, by_native = defaultdict(list), defaultdict(list), defaultdict(list)

    # Finding one feed row for an ID does not mean we have read its other feeds.
    queried = defaultdict(set)

    def load(column, values):
        missing = values - queried[column]
        queried[column].update(missing)
        for row in members_for(db, versions, kind, column, missing):
            for key, dest in ((row.imdb, by_imdb), (row.native, by_native), (row.source, by_source)):
                if key is not None and row not in dest[key]:
                    dest[key].append(row)

    # A Watchmode-only saved card may now have a published TMDB or IMDb identifier.
    load("source_id", {item["id"] for item in titles if item["id"].startswith("watchmode:")})
    imdb_ids = {item["id"] for item in titles if item["id"].startswith("tt")}
    natives = {int(item["id"][5:]) for item in titles if item["id"].startswith("tmdb:")}
    natives.update(item["tmdbId"] for item in titles if "tmdbId" in item)
    load("imdb_id", imdb_ids | by_imdb.keys())
    load("tmdb_id", natives | by_native.keys())
    resolved = []
    for item in titles:
        uid = item["id"]
        imdb = uid if uid.startswith("tt") else None
        native = int(uid[5:]) if uid.startswith("tmdb:") else item.get("tmdbId")
        if uid in by_source:
            source_rows = by_source[uid]
            source_imdb = {row.imdb for row in source_rows if row.imdb}
            source_native = {row.native for row in source_rows if row.native}
            if not imdb and len(source_imdb) == 1:
                imdb = next(iter(source_imdb))
            if not native and len(source_native) == 1:
                native = next(iter(source_native))
        if not imdb and native:
            candidates = {row.imdb for row in by_native[native] if row.imdb}
            if len(candidates) == 1:
                imdb = next(iter(candidates))
            elif not candidates and cached_imdb:
                imdb = cached_imdb(kind, f"tmdb:{native}")
        resolved.append((item, imdb, native))

    load("imdb_id", {imdb for _, imdb, _ in resolved if imdb})
    load("tmdb_id", set(by_native) | {release_natives[imdb] for _, imdb, _ in resolved if imdb in release_natives})
    load("source_id", {source for source in by_source if source.startswith("watchmode:")})
    for item, imdb, native in resolved:
        uid = item["id"]
        rows = set(by_imdb[imdb]) if imdb else set()
        native_keys = {row.native for row in rows if row.native}
        if native:
            native_keys.add(native)
        if imdb in release_natives:
            native_keys.add(release_natives[imdb])
        safe_native = []
        verified_native = set()
        for key in native_keys:
            links = {row.imdb for row in by_native[key] if row.imdb}
            cached = None
            if not links and imdb and cached_imdb:
                cached = cached_imdb(kind, f"tmdb:{key}")
                if cached and cached != imdb:
                    continue
            if not links or links == {imdb}:
                rows.update(by_native[key])
                safe_native.append(key)
                # A request's TMDB hint alone cannot mark an IMDb-only title as watched.
                if links or (imdb and cached == imdb) or (not imdb and key == native):
                    verified_native.add(key)
            elif not imdb and key == native:
                rows.update(row for row in by_native[key] if not row.imdb)
        if uid.startswith("watchmode:"):
            rows.update(row for row in by_source[uid] if row.imdb in (None, imdb))
        aliases = {f"tmdb:{key}" for key in verified_native}
        if imdb:
            aliases.add(imdb)
        for source in {row.source for row in rows if row.source.startswith("watchmode:")}:
            source_rows = by_source[source]
            if all((row.imdb == imdb if row.imdb else row.native in verified_native) for row in source_rows):
                aliases.add(source)
        aliases.discard(uid)
        yield item, imdb, sorted(safe_native), sorted(rows, key=lambda row: (row.rank, row.feed, row.source)), sorted(aliases)


def title_rows(db, kind, imdb_ids):
    return {row[0]: row[1:] for row in db.execute("""
        SELECT imdb_id,id,subtype,name,year,genres,rating,votes,adult FROM title
        WHERE id IN (SELECT cast(substr(value,3) AS INTEGER) FROM json_each(?)) AND type=?
    """, (json.dumps(sorted(imdb_ids)), kind))}


def keyword_matches(db, keyword, title_ids):
    needle = fold(keyword)
    if not needle or not title_ids:
        return set()
    ids = json.dumps(sorted(title_ids))
    matches = {row[0] for row in db.execute("""
        SELECT id FROM title_search_content
        WHERE id IN (SELECT value FROM json_each(?)) AND instr(c0,?)>0
    """, (ids, needle))}
    if len(needle) >= 3:
        people = "SELECT rowid FROM person_search WHERE person_search MATCH ? AND surface=?"
        args = ('"' + needle + '"', needle)
    else:
        people = "SELECT id FROM person_search_content WHERE c0=?"
        args = (needle,)
    matches.update(row[0] for row in db.execute("SELECT title_id FROM acting WHERE person_id IN (" + people +
                   ") AND title_id IN (SELECT value FROM json_each(?))", (*args, ids)))
    return matches


def matching_genres(genres, ranks, subtype, adult):
    tags = set((genres or "").split(","))
    keys = {key for key, alternatives in GENRES.items() if tags.intersection(alternatives)}
    if subtype == "tvMovie":
        keys.add("tv movie")
    if adult or "Adult" in tags:
        keys.add("adult")
    keys.update(key for key in EXTRA_GENRES if "genre:" + key in ranks)
    return sorted(key.title() if key != "tv movie" else "TV Movie" for key in keys)


def cached_movie_releases(db, ids, day):
    # Keep the recorded IMDb identity alongside the dates so a conflicting
    # client-side TMDB hint cannot apply another film's release.
    imdb_ids = [uid for uid in ids if uid.startswith("tt")]
    native_ids = [uid[5:] for uid in ids if uid.startswith("tmdb:")]
    records = {}
    for imdb, native, theatrical, home, age in db.execute("""
            SELECT imdb_id,tmdb_id,theatrical_date,home_date,age_rating FROM releases.movie_release
            WHERE imdb_id IN (SELECT value FROM json_each(?)) OR tmdb_id IN (SELECT value FROM json_each(?))
            """, (json.dumps(imdb_ids), json.dumps(native_ids))):
        row = (imdb, release_card(theatrical, home, day, age))
        records["tmdb:" + native] = row
        if imdb:
            records[imdb] = row
    return records


def library_facts(request, directory, releases=None, cached_imdb=None):
    titles, keyword = request_titles(request)
    if not WORKERS.acquire(timeout=1):
        raise QueryError(503, "busy")
    try:
        with closing(sqlite3.connect(uri(directory / "catalog.sqlite3"), uri=True, timeout=15)) as db:
            db.execute("PRAGMA temp_store=MEMORY")
            db.execute("BEGIN")
            version = db.execute("SELECT value FROM meta WHERE key='snapshot'").fetchone()
            if not version:
                raise QueryError(503, "index_unavailable")
            versions = active_feeds(db, directory)
            prior = rating_prior(db, (directory, version[0]))
            facts, release_keys = {}, {}
            day = datetime.now(timezone.utc).date().isoformat()
            known = {}
            available = releases is not None
            movie_titles = [item for item in titles if item["type"] == "movie"]
            release_revision = None
            if available:
                try:
                    db.execute("ATTACH ? AS releases", (uri(releases.path),))
                    revision = db.execute("SELECT revision FROM releases.movie_release_revision WHERE id=1").fetchone()
                    if not revision:
                        raise sqlite3.DatabaseError("missing release revision")
                    release_revision = revision[0]
                    ids = {item["id"] for item in movie_titles}
                    ids.update(f"tmdb:{item['tmdbId']}" for item in movie_titles if "tmdbId" in item)
                    if ids:
                        known = cached_movie_releases(db, ids, day)
                except sqlite3.Error:
                    available = False

            def local_imdb(kind, uid):
                from_release = known.get(uid, (None,))[0] if kind == "movie" else None
                return from_release or (cached_imdb(kind, uid) if cached_imdb else None)

            release_natives = {imdb: int(uid[5:]) for uid, (imdb, _) in known.items()
                               if imdb and uid.startswith("tmdb:")}
            needle = fold(keyword)
            for kind in {item["type"] for item in titles}:
                selected = [item for item in titles if item["type"] == kind]
                resolved = list(resolve_titles(db, versions, kind, selected, local_imdb,
                                               release_natives if kind == "movie" else {}))
                indexed = title_rows(db, kind, {imdb for _, imdb, _, _, _ in resolved if imdb})
                matched = keyword_matches(db, keyword, {row[0] for row in indexed.values()})
                for item, imdb, natives, members, aliases in resolved:
                    uid = item["id"]
                    key = (kind, uid, item.get("tmdbId"))
                    canonical_id = imdb or (f"tmdb:{natives[0]}" if natives else uid)
                    fact = {"identity": item, "imdbId": imdb, "canonicalId": canonical_id, "aliases": aliases, "ranks": {}}
                    facts[key] = fact
                    for member in members:
                        fact["ranks"].setdefault(member.feed, member.rank)
                    genres, subtype, adult = None, None, False
                    fact["keywordMatch"] = any(needle in fold(row.name) for row in members) if needle else False
                    if members:
                        fact["year"] = members[0].year
                    if imdb in indexed:
                        ident, subtype, _, year, genres, rating, votes, adult = indexed[imdb]
                        fact.update(year=year or fact.get("year"), rating=rating, votes=votes, keywordMatch=ident in matched)
                        if rating is not None and prior is not None:
                            fact["weightedRating"] = (votes * rating + RATING_PRIOR_VOTES * prior) / (votes + RATING_PRIOR_VOTES)
                    fact["genres"] = matching_genres(genres, fact["ranks"], subtype, adult)
                    if kind == "movie":
                        release_keys[key] = ([imdb] if imdb else []) + [f"tmdb:{n}" for n in natives]
                        if uid.startswith("tmdb:") and not imdb:
                            release_keys[key].append(uid)
            if available and release_keys:
                try:
                    missing = {uid for keys in release_keys.values() for uid in keys if uid not in known}
                    if missing:
                        known.update(cached_movie_releases(db, missing, day))
                except sqlite3.Error:
                    available = False
                if available:
                    for key, aliases in release_keys.items():
                        expected = facts[key]["imdbId"]
                        for uid in aliases:
                            if uid in known:
                                imdb, card = known[uid]
                                if expected and imdb and expected != imdb:
                                    continue
                                if card["status"] != "unknown":
                                    facts[key]["release"] = card
                                    break
            snapshot = hashlib.sha256(json.dumps([version[0], versions, day, release_revision], sort_keys=True).encode()).hexdigest()
            return {"facts": list(facts.values()), "snapshot": snapshot, "availableCatalogs": sorted(versions),
                    "releaseAvailable": available}
    except (sqlite3.Error, OSError) as error:
        raise QueryError(503, "index_unavailable") from error
    finally:
        WORKERS.release()
