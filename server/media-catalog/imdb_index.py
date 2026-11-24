#!/usr/bin/env python3
"""Build the public, local movie/series index from IMDb's daily non-commercial datasets."""
import argparse
from array import array
from contextlib import closing
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import time
import unicodedata
from urllib.request import Request, urlopen

BASE = "https://datasets.imdbws.com/"
DEFAULT_DB = Path(os.environ.get('MKBTV_STATE_DIR', Path.home() / '.local/share/mkbtv')) / 'catalog/catalog.sqlite3'
GIB = 1024 ** 3
MAX_ID = 64_000_000
VERSION = "2"
HEADERS = {
    "title.basics": b"tconst titleType primaryTitle originalTitle isAdult startYear endYear runtimeMinutes genres",
    "title.episode": b"tconst parentTconst seasonNumber episodeNumber",
    "title.ratings": b"tconst averageRating numVotes",
    "title.akas": b"titleId ordering title region language types attributes isOriginalTitle",
    "title.principals": b"tconst ordering nconst category job characters",
    "name.basics": b"nconst primaryName birthYear deathYear primaryProfession knownForTitles",
}
MIN_COUNTS = {"title": 1_500_000, "aka": 2_000_000, "person": 1_000_000,
              "acting": 5_000_000, "rated": 400_000, "episodes": 5_000_000}
SUBTYPES = {b"movie", b"tvMovie", b"video", b"tvSpecial", b"short", b"tvShort", b"tvSeries", b"tvMiniSeries"}
NULL = b"\\N"

SCHEMA = """
CREATE TABLE title (
    id INTEGER PRIMARY KEY,
    imdb_id TEXT AS (printf('tt%07d',id)) VIRTUAL,
    type TEXT AS (CASE WHEN subtype IN ('tvSeries','tvMiniSeries') THEN 'series' ELSE 'movie' END) VIRTUAL,
    subtype TEXT NOT NULL, name TEXT NOT NULL, original TEXT, year INTEGER,
    genres TEXT, rating REAL, votes INTEGER NOT NULL DEFAULT 0, adult INTEGER NOT NULL
);
CREATE TABLE aka (title_id INTEGER NOT NULL, name TEXT NOT NULL,
                  PRIMARY KEY(title_id,name)) WITHOUT ROWID;
CREATE TABLE person (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE acting (person_id INTEGER NOT NULL, title_id INTEGER NOT NULL,
                     PRIMARY KEY(person_id,title_id)) WITHOUT ROWID;
CREATE TABLE meta (key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID;
"""
INDEXES = """
CREATE INDEX title_rating ON title(rating DESC,votes DESC,id);
CREATE INDEX title_votes ON title(votes DESC,id);
CREATE INDEX title_year ON title(year DESC,id);
CREATE INDEX title_name ON title(name COLLATE NOCASE);
CREATE INDEX aka_name ON aka(name COLLATE NOCASE);
CREATE INDEX person_name ON person(name COLLATE NOCASE);
"""

FOLD = str.maketrans({"æ": "ae", "œ": "oe", "ø": "o", "ł": "l", "ð": "d", "þ": "th", "đ": "d"})
NON_ALNUM = re.compile(r"[\W_]+")


def normalize_search(value):
    if not value:
        return ""
    folded = value.casefold()
    if not folded.isascii():
        folded = unicodedata.normalize("NFKD", folded.translate(FOLD))
    return NON_ALNUM.sub("", folded)


def title_surfaces(db):
    aliases = iter(db.execute("SELECT title_id,name FROM aka ORDER BY title_id"))
    alias = next(aliases, None)
    for ident, name, original in db.execute("SELECT id,name,original FROM title ORDER BY id"):
        surfaces = {normalize_search(name), normalize_search(original)}
        while alias and alias[0] == ident:
            surfaces.add(normalize_search(alias[1]))
            alias = next(aliases, None)
        yield ident, "\n".join(sorted(surface for surface in surfaces if surface))


def build_search(db):
    """Keep normalized text for 1–2 character searches and positions for longer exact substrings."""
    people = ((ident, normalize_search(name)) for ident, name in db.execute("SELECT id,name FROM person ORDER BY id"))
    for table, rows in (("title_search", title_surfaces(db)), ("person_search", people)):
        log("Indexing normalized names in " + table)
        db.execute(f"CREATE VIRTUAL TABLE {table} USING fts5(surface, "
                   "tokenize='trigram case_sensitive 1', columnsize=0)")
        db.executemany(f"INSERT INTO {table}(rowid,surface) VALUES (?,?)",
                       ((ident, surface) for ident, surface in rows if surface))
        db.execute(f"INSERT INTO {table}({table}) VALUES ('integrity-check')")
        db.commit()


def log(message):
    print(f"{datetime.now(timezone.utc).isoformat(timespec='seconds')} {message}", flush=True)


class Official:
    @staticmethod
    def request(name, method="GET", etag=None):
        headers = {"Accept-Encoding": "identity", "User-Agent": "mkbtv-catalog/1"}
        if etag:
            headers["If-Match"] = etag
        return urlopen(Request(BASE + name + ".tsv.gz", method=method, headers=headers), timeout=90)

    def manifest(self):
        now = datetime.now(timezone.utc)
        result = {}
        for name in HEADERS:
            with self.request(name, method="HEAD") as response:
                modified = parsedate_to_datetime(response.headers["Last-Modified"])
                age = (now - modified).total_seconds()
                size = int(response.headers["Content-Length"])
                etag = response.headers["ETag"]
                if not etag or not 0 < size <= 2 * GIB or not -600 <= age <= 72 * 3600:
                    raise ValueError(f"Invalid or stale official source: {name}")
                result[name] = {"etag": etag, "size": size, "modified": modified.isoformat()}
        times = [datetime.fromisoformat(entry["modified"]) for entry in result.values()]
        if (max(times) - min(times)).total_seconds() > 12 * 3600:
            raise ValueError("Official sources span different daily publications")
        return result

    def open(self, name, entry):
        response = self.request(name, etag=entry["etag"])
        if response.headers["ETag"] != entry["etag"] or int(response.headers["Content-Length"]) != entry["size"]:
            response.close()
            raise ValueError(f"Source changed before download: {name}")
        return response


def blocks(source, name, entry):
    """Validate gzip CRC at EOF and bound both input size and individual line allocations."""
    with source.open(name, entry) as response, gzip.GzipFile(fileobj=response) as stream:
        header = stream.readline(1024).rstrip(b"\r\n")
        if header != HEADERS[name].replace(b" ", b"\t"):
            raise ValueError(f"Unexpected header for {name}")
        carry = b""
        expanded = 0
        while chunk := stream.read(1024 * 1024):
            expanded += len(chunk)
            if expanded > 24 * GIB:
                raise ValueError(f"Oversize dataset: {name}")
            lines = (carry + chunk).split(b"\n")
            carry = lines.pop()
            if len(carry) > 1024 * 1024:
                raise ValueError(f"Oversize row: {name}")
            yield lines
        if carry:
            yield [carry]
        if getattr(response, "length", 0) not in (0, None):
            raise ValueError(f"Incomplete download: {name}")


def text(value):
    return None if value == NULL else value.decode("utf-8")


def number(value, prefix):
    if not value.startswith(prefix) or not value[2:].isdigit():
        raise ValueError(f"Invalid IMDb {prefix.decode()} identifier")
    result = int(value[2:])
    if not 0 < result < MAX_ID:
        raise ValueError("IMDb identifier exceeds bounded lookup")
    return result


def extend(ids, ident):
    if ident >= len(ids):
        end = min(MAX_ID, (1 + ident // 1_000_000) * 1_000_000)
        size = end - len(ids)
        if isinstance(ids, array):
            ids.extend(array("I", [0]) * size)
        else:
            ids.extend(bytes(size))


class Loader:
    def __init__(self, conn):
        self.db = conn
        # Zero = absent, id = retained title, parent id = roll episode credits into this series.
        self.titles = array("I")
        self.people = bytearray()
        self.episodes = 0

    def basics(self, lines):
        rows = []
        for line in lines:
            imdb, kind, rest = line.split(b"\t", 2)
            if kind not in SUBTYPES:
                continue
            # Split from the right: source titles occasionally contain embedded tabs.
            names, adult, year, _end, _runtime, genres = rest.rsplit(b"\t", 5)
            primary, original = names.split(b"\t", 1)
            ident = number(imdb, b"tt")
            extend(self.titles, ident)
            self.titles[ident] = ident
            rows.append((ident, kind.decode(), text(primary) or text(original) or imdb.decode(),
                         text(original), int(year) if year.isdigit() else None, text(genres),
                         int(adult == b"1")))
        self.db.executemany("INSERT INTO title(id,subtype,name,original,year,genres,adult) VALUES (?,?,?,?,?,?,?)", rows)

    def episode(self, lines):
        for line in lines:
            child, parent, _ = line.split(b"\t", 2)
            if parent == NULL:
                continue
            parent_id = number(parent, b"tt")
            if parent_id >= len(self.titles) or self.titles[parent_id] != parent_id:
                continue
            child_id = number(child, b"tt")
            extend(self.titles, child_id)
            if not self.titles[child_id]:
                self.titles[child_id] = parent_id
                self.episodes += 1

    def ratings(self, lines):
        rows = []
        for line in lines:
            imdb, rating, votes = line.split(b"\t")
            ident = number(imdb, b"tt")
            if ident < len(self.titles) and self.titles[ident] == ident:
                score, count = float(rating), int(votes)
                if not 0 <= score <= 10 or count < 1:
                    raise ValueError("Invalid public rating")
                rows.append((score, count, ident))
        self.db.executemany("UPDATE title SET rating=?,votes=? WHERE id=?", rows)

    def akas(self, lines):
        rows = set()
        for line in lines:
            imdb, _, rest = line.split(b"\t", 2)
            ident = number(imdb, b"tt")
            if ident < len(self.titles) and self.titles[ident] == ident:
                name = text(rest.rsplit(b"\t", 5)[0])
                if name:
                    rows.add((ident, name))
        self.db.executemany("INSERT OR IGNORE INTO aka VALUES (?,?)", sorted(rows))

    def principals(self, lines):
        rows = set()
        for line in lines:
            if b"\tactor\t" not in line and b"\tactress\t" not in line:
                continue
            imdb, _, person, category, _ = line.split(b"\t", 4)
            if category not in (b"actor", b"actress"):
                continue
            ident = number(imdb, b"tt")
            title_id = self.titles[ident] if ident < len(self.titles) else 0
            if title_id:
                person_id = number(person, b"nm")
                extend(self.people, person_id)
                self.people[person_id] = 1
                rows.add((person_id, title_id))
        self.db.executemany("INSERT INTO temp.cast_input VALUES (?,?)", rows)

    def names(self, lines):
        rows = []
        for line in lines:
            imdb, rest = line.split(b"\t", 1)
            ident = number(imdb, b"nm")
            if ident < len(self.people) and self.people[ident]:
                name = text(rest.rsplit(b"\t", 4)[0])
                if name:
                    rows.append((ident, name))
        self.db.executemany("INSERT INTO person VALUES (?,?)", rows)


def readonly(path):
    return sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)


def build_core(db, source, manifest):
    db.executescript(SCHEMA)
    # Appending title-ordered credits, then sorting once, avoids random writes to a
    # multi-million-row person-first index throughout the principal-credit download.
    db.execute("CREATE TEMP TABLE cast_input(person_id INTEGER NOT NULL, title_id INTEGER NOT NULL)")
    loader = Loader(db)
    handlers = {"title.basics": loader.basics, "title.episode": loader.episode,
                "title.ratings": loader.ratings, "title.akas": loader.akas,
                "title.principals": loader.principals, "name.basics": loader.names}
    for name, handler in handlers.items():
        started = time.monotonic()
        log("Loading " + name)
        for lines in blocks(source, name, manifest[name]):
            handler(lines)
        if name == "title.principals":
            db.execute("""INSERT INTO acting SELECT person_id,title_id FROM temp.cast_input
                          GROUP BY person_id,title_id ORDER BY person_id,title_id""")
            db.execute("DROP TABLE temp.cast_input")
        db.commit()
        log(f"{name} finished in {time.monotonic()-started:.1f}s")
    episodes = loader.episodes
    del loader, handlers
    log("Creating indexes")
    db.executescript(INDEXES)
    db.execute("ANALYZE")
    return episodes


def refresh(destination, source, minima=MIN_COUNTS):
    destination = Path(destination).resolve()
    directory = destination.parent
    directory.mkdir(exist_ok=True, parents=True)
    # Keep SQLite's temporary sorts on the same, capacity-checked filesystem.
    os.environ["SQLITE_TMPDIR"] = str(directory)
    with (directory / ".imdb.lock").open("a+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        manifest = source.manifest()
        encoded = json.dumps(manifest, sort_keys=True, separators=(",", ":"))
        snapshot = hashlib.sha256((VERSION + "\n" + encoded).encode()).hexdigest()
        previous = {}
        if destination.exists():
            with closing(readonly(destination)) as db:
                previous = dict(db.execute("SELECT key,value FROM meta"))
            if previous.get("snapshot") == snapshot and previous.get("version") == VERSION:
                log("Already current " + snapshot)
                return
            old_manifest = json.loads(previous["sources"])
            if any(entry["modified"] < old_manifest[name]["modified"] for name, entry in manifest.items()):
                raise ValueError("Official source is older than the published snapshot")

        stage = directory / ".imdb-next.sqlite3"
        # Safe after interruption: a second builder cannot pass the exclusive lock.
        stage.unlink(missing_ok=True)
        if shutil.disk_usage(directory).free < 8 * GIB:
            raise OSError("Need 8 GiB free to start building the catalog")
        try:
            stage.touch(mode=0o600, exist_ok=False)
            # The initial v1 snapshot already has the complete public catalog and actor links.
            reuse_core = previous.get("version") == "1" and previous.get("sources") == encoded
            if reuse_core:
                log("Reusing published core from the identical official source generation")
                shutil.copyfile(destination, stage)
            with closing(sqlite3.connect(stage)) as db:
                db.executescript("""
                    PRAGMA page_size=16384;
                    PRAGMA max_page_count=393216;
                    PRAGMA cache_size=-98304;
                    PRAGMA temp_store=FILE;
                    PRAGMA temp.page_size=16384;
                    PRAGMA temp.max_page_count=131072;
                    PRAGMA journal_mode=OFF;
                    PRAGMA synchronous=OFF;
                    PRAGMA locking_mode=EXCLUSIVE;
                """)
                episodes = int(previous["count_episodes"]) if reuse_core else build_core(db, source, manifest)
                build_search(db)
                counts = {key: db.execute(f"SELECT count(*) FROM {key}").fetchone()[0]
                          for key in ("title", "aka", "person", "acting")}
                counts["rated"] = db.execute("SELECT count(*) FROM title WHERE rating IS NOT NULL").fetchone()[0]
                counts["episodes"] = episodes
                for key, count in counts.items():
                    prior = int(previous.get("count_" + key, "0"))
                    if count < max(minima[key], prior * 0.90):
                        raise ValueError(f"Unexpectedly low {key} count: {count}")
                if db.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                    raise ValueError("SQLite quick_check failed")
                if manifest != source.manifest():
                    raise ValueError("Official snapshot changed during indexing")
                meta = {"snapshot": snapshot, "version": VERSION, "sources": encoded,
                        "updated_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
                meta.update({"count_" + key: str(value) for key, value in counts.items()})
                db.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)", meta.items())
                db.commit()
            stage.chmod(0o444)
            with stage.open("rb") as checked:
                os.fsync(checked.fileno())
            os.replace(stage, destination)
            descriptor = os.open(directory, os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            log(f"Published {snapshot} bytes={destination.stat().st_size} rows={counts}")
        finally:
            stage.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, default=DEFAULT_DB)
    args = parser.parse_args()
    refresh(args.database, Official())
