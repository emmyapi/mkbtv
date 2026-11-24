import fcntl
import gzip
from contextlib import closing
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from io import BytesIO
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import imdb_index as index


class Feeds:
    def __init__(self):
        self.sequence = 1
        self.reads = 0
        self.rows = {
            "title.basics": [
                r"tt0000001|movie|Film|Film|1|1999|\N|90|Drama",
                r"tt0000002|tvMiniSeries|Series|Original|0|2020|\N|30|Comedy,Drama",
                r"tt0000003|tvEpisode|Episode|Episode|0|2021|\N|30|Comedy",
                r"tt0000004|tvEpisode|Episode 2|Episode 2|0|2022|\N|30|Comedy",
                r"tt0000005|videoGame|Game|Game|0|2020|\N|\N|\N",
                r"tt0000006|short|Short|Short|0|\N|\N|2|\N",
            ],
            "title.episode": ["tt0000003|tt0000002|1|1", "tt0000004|tt0000002|1|2"],
            "title.ratings": ["tt0000001|6.4|3", "tt0000003|9.0|2"],
            "title.akas": [
                r"tt0000001|1|Film|US|\N|\N|\N|0",
                r"tt0000001|2|Film|GB|\N|\N|\N|0",
                r"tt0000002|1|Série|FR|fr|\N|\N|0",
                r"tt0000003|1|Episode|US|en|\N|\N|0",
            ],
            "title.principals": [
                r"tt0000001|1|nm0000001|actor|\N|[]",
                r"tt0000002|1|nm0000001|actor|\N|[]",
                r"tt0000003|1|nm0000001|actor|\N|[]",
                r"tt0000003|2|nm0000002|actress|\N|[]",
                r"tt0000004|1|nm0000002|actress|\N|[]",
                r"tt0000002|2|nm0000003|composer|\N|[]",
                r"tt0000005|1|nm0000004|actor|\N|[]",
            ],
            "name.basics": [rf"nm000000{i}|Person {i}|1980|\N|actor|tt0000001" for i in range(1, 5)],
        }
        self.truncated = False
        self.change_while_building = False

    def manifest(self):
        if self.change_while_building and self.reads:
            self.sequence += 1
        return {name: {"etag": str(self.sequence), "size": 100, "modified": "2026-01-01"}
                for name in reversed(index.HEADERS)}

    def open(self, name, _entry):
        self.reads += 1
        data = index.HEADERS[name].replace(b" ", b"\t") + b"\n"
        data += ("\n".join(self.rows[name]) + "\n").replace("|", "\t").encode()
        payload = gzip.compress(data)
        return BytesIO(payload[:-5] if self.truncated else payload)


class OfficialSourceTest(unittest.TestCase):
    def test_current_complete_publication_required(self):
        source = index.Official()
        current = datetime.now(timezone.utc) - timedelta(minutes=10)
        dates = dict.fromkeys(index.HEADERS, current)

        def answer(name, method="GET", etag=None):
            response = BytesIO()
            response.headers = {"Last-Modified": format_datetime(dates[name]), "ETag": '"v1"',
                                "Content-Length": "100"}
            return response

        with patch.object(source, "request", side_effect=answer):
            manifest = source.manifest()
            self.assertEqual(set(manifest), set(index.HEADERS))
            with self.assertRaisesRegex(ValueError, "changed before download"):
                source.open("title.basics", dict(etag='"wrong"', size=100))
            dates["title.akas"] -= timedelta(days=1)
            with self.assertRaisesRegex(ValueError, "different daily publications"):
                source.manifest()
            dates["title.akas"] -= timedelta(days=3)
            with self.assertRaisesRegex(ValueError, "stale"):
                source.manifest()


class IndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.temp.cleanup)
        self.db = Path(self.temp.name) / "catalog.sqlite3"
        self.source = Feeds()
        self.minima = dict.fromkeys(index.MIN_COUNTS, 1)
        self.addCleanup(patch.stopall)
        patch.object(index, "log").start()
        patch.object(index.shutil, "disk_usage").start().return_value.free = 32 * index.GIB
        original_tmp = os.environ.get("SQLITE_TMPDIR")
        self.addCleanup(self.restore_tmp, original_tmp)

    @staticmethod
    def restore_tmp(previous):
        if previous is None:
            os.environ.pop("SQLITE_TMPDIR", None)
        else:
            os.environ["SQLITE_TMPDIR"] = previous

    def build(self):
        index.refresh(self.db, self.source, self.minima)

    def test_published_rows_and_unchanged_refresh(self):
        self.build()
        with closing(index.readonly(self.db)) as db:
            self.assertEqual(db.execute("SELECT imdb_id,type,subtype,name,year,genres,rating,votes,adult FROM title ORDER BY id").fetchall(), [
                ("tt0000001", "movie", "movie", "Film", 1999, "Drama", 6.4, 3, 1),
                ("tt0000002", "series", "tvMiniSeries", "Series", 2020, "Comedy,Drama", None, 0, 0),
                ("tt0000006", "movie", "short", "Short", None, None, None, 0, 0)])
            self.assertEqual(db.execute("SELECT * FROM acting ORDER BY 1,2").fetchall(), [(1, 1), (1, 2), (2, 2)])
            self.assertEqual(db.execute("SELECT * FROM aka ORDER BY 1,2").fetchall(), [(1, "Film"), (2, "Série")])
            self.assertEqual(db.execute("SELECT * FROM person ORDER BY id").fetchall(), [(1, "Person 1"), (2, "Person 2")])
        self.assertEqual(self.db.stat().st_mode & 0o777, 0o444)
        inode = self.db.stat().st_ino
        self.build()
        self.assertEqual(self.source.reads, 6)
        self.assertEqual(self.db.stat().st_ino, inode)

    def test_failed_sources_leave_published_snapshot(self):
        self.build()
        original = self.db.read_bytes()
        self.source.sequence += 1
        self.source.truncated = True
        with self.assertRaises(EOFError):
            self.build()
        self.assertEqual(self.db.read_bytes(), original)
        self.source.truncated = False
        self.source.reads = 0
        self.source.change_while_building = True
        with self.assertRaisesRegex(ValueError, "changed during indexing"):
            self.build()
        self.assertEqual(self.db.read_bytes(), original)
        self.source.change_while_building = False
        self.source.rows["title.principals"].clear()
        with self.assertRaisesRegex(ValueError, "low .* count"):
            self.build()
        self.assertEqual(self.db.read_bytes(), original)
        self.assertFalse((self.db.parent / ".imdb-next.sqlite3").exists())

    def test_active_reader_survives_refresh_and_overlap_is_rejected(self):
        self.build()
        with (self.db.parent / ".imdb.lock").open("rb") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            with self.assertRaises(BlockingIOError):
                self.build()
        self.source.sequence += 1
        self.source.rows["title.ratings"] = ["tt0000001|8.2|10"]
        with closing(index.readonly(self.db)) as old:
            old.execute("SELECT * FROM meta").fetchall()
            self.build()
            self.assertEqual(old.execute("SELECT rating FROM title WHERE id=1").fetchone()[0], 6.4)
        with closing(index.readonly(self.db)) as current:
            self.assertEqual(current.execute("SELECT rating FROM title WHERE id=1").fetchone()[0], 8.2)

    def test_exact_normalized_substrings_and_short_terms(self):
        self.source.rows["title.akas"] += [
            r"tt0000002|2|Spidér-Man|DK|da|\N|\N|0",
            r"tt0000002|3|Up|DK|da|\N|\N|0"]
        self.source.rows["name.basics"][0] = r"nm0000001|Søren Ægir|\N|\N|actor|tt0000001"
        self.build()
        with closing(index.readonly(self.db)) as db:
            for table, phrase, expected in [
                    ("title_search", "Der Man", [2]), ("title_search", "original", [2]),
                    ("person_search", "Rën Ae", [1]), ("title_search", "Mana", [])]:
                needle = '"' + index.normalize_search(phrase) + '"'
                result = db.execute(f"SELECT rowid FROM {table} WHERE {table} MATCH ?", (needle,))
                self.assertEqual([row[0] for row in result], expected)
            self.assertEqual(db.execute("SELECT rowid FROM title_search WHERE instr(surface,'up')").fetchall(), [(2,)])

    def test_atomic_core_upgrade_uses_already_validated_feeds(self):
        self.build()
        self.db.chmod(0o600)
        with closing(sqlite3.connect(self.db)) as db:
            db.executescript("""
                DROP TABLE title_search;
                DROP TABLE person_search;
                UPDATE meta SET value='1' WHERE key='version';
            """)
        with closing(index.readonly(self.db)) as old:
            self.build()
            self.assertEqual(old.execute("SELECT value FROM meta WHERE key='version'").fetchone()[0], "1")
        with closing(index.readonly(self.db)) as new:
            self.assertEqual(new.execute("SELECT rowid FROM title_search WHERE title_search MATCH 'film'").fetchone()[0], 1)
        self.assertEqual(self.source.reads, 6)


if __name__ == "__main__":
    unittest.main()
