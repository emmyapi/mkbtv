"""Discover must use the full result set for counts, sort and page boundaries."""

import json
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from threading import Thread

import discovery
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import time
import unittest
from unittest.mock import patch

import discover_query as dq


class DiscoverQueries(unittest.TestCase):
    def setUp(self):
        temp = TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.data = Path(temp.name)
        db = sqlite3.connect(self.data / "catalog.sqlite3")
        db.executescript("""
          CREATE TABLE meta(key,value);
          INSERT INTO meta VALUES ('snapshot','day-one');
          CREATE TABLE title(id INTEGER PRIMARY KEY,imdb_id,type,subtype,name,original,year,genres,rating,votes,adult);
          CREATE TABLE acting(person_id,title_id);
          CREATE VIRTUAL TABLE title_search USING fts5(surface,tokenize='trigram');
          CREATE VIRTUAL TABLE person_search USING fts5(surface,tokenize='trigram');
          INSERT INTO title VALUES (1,'tt0000001','movie','movie','Up',NULL,1999,'Adventure',8.0,900,0),
            (2,'tt0000002','movie','movie','Spider-Man 2',NULL,2014,'Action,Sci-Fi',8.9,11,0),
            (3,'tt0000003','movie','movie','Spider-Man 3',NULL,2017,'Drama',9.1,4,0),
            (4,'tt0000004','series','tvSeries','Series',NULL,2017,'Drama',NULL,0,0),
            (5,'tt0000005','movie','movie','Unreleased',NULL,2080,'Drama',NULL,0,0);
          INSERT INTO title_search(rowid,surface) VALUES (1,'up'),(2,'spiderman2'),(3,'spiderman3'),(4,'series'),(5,'unreleased');
          INSERT INTO person_search(rowid,surface) VALUES(42,'reneetest'),(43,'notreneetest');
          INSERT INTO acting VALUES(42,3),(42,4),(43,1);
        """)
        db.commit()
        db.close()
        db = sqlite3.connect(self.data / "feeds.sqlite3")
        db.executescript("""
          CREATE TABLE chart_gap(key,type,skip);
          CREATE TABLE feed(key TEXT PRIMARY KEY,snapshot,updated_at,source);
          CREATE TABLE member(key,type,imdb_id,rank,source_id,name,year,tmdb_id,tmdb_type);
          INSERT INTO member VALUES
           ('hulu','movie','tt0000003',1,'watchmode:13','alt name',2016,880,'movie'),
           ('hulu','series',NULL,2,'watchmode:77','Renée Test Show',2015,77,'tv'),
           ('trending','series','tt0000004',0,'n4','s',2017,NULL,'tv'),
           ('trending','movie','tt0000003',1,'n3','m',2017,NULL,'movie'),
           ('genre:soap','series','tt0000004',1,'n4','s',2017,NULL,'tv');
        """)
        for k in ("hulu", "trending", "genre:soap"):
            db.execute(
                "INSERT INTO feed VALUES(?,?,?,?)",
                (k, "v1", int(time.time()), "watchmode-us-sub" if k == "hulu" else "nuvio-chart"),
            )
        db.commit()
        db.close()

    def call(self, profile=1, resolve=None, **params):
        return dq.query_page(params, profile=profile, directory=self.data, resolve=resolve)

    def test_global_order_before_paging_and_stable_end(self):
        all_items = self.call(sort="top_rated")
        page = self.call(sort="top_rated", limit=1)
        self.assertEqual(6, page["total"])
        self.assertEqual("Spider-Man 2", page["metas"][0]["name"])
        self.assertEqual(
            all_items["metas"][1:], self.call(sort="top_rated", offset=1, snapshot=page["snapshot"])["metas"]
        )
        self.assertIsNone(self.call(sort="top_rated", offset=5, snapshot=page["snapshot"])["next_offset"])

    def test_highest_rated_regularizes_sparse_votes_without_changing_ratings_or_counts(self):
        with sqlite3.connect(self.data / "catalog.sqlite3") as db:
            db.executemany("UPDATE title SET rating=?,votes=? WHERE id=?", [
                (8.4, 600000, 1), (10.0, 1, 2), (6.0, 90000, 3), (4.0, 20, 4)
            ])
        result = self.call(type="movie", sort="top_rated")
        self.assertEqual([("Up", "8.4"), ("Spider-Man 2", "10.0"), ("Spider-Man 3", "6.0"),
                          ("Unreleased", None)], [(m["name"], m.get("imdbRating")) for m in result["metas"]])
        self.assertEqual(["Spider-Man 2"], [m["name"] for m in self.call(
            type="movie", min_rating=9, sort="top_rated")["metas"]])

    def test_chart_order_survives_filtering_paging_and_chart_refresh(self):
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute("INSERT INTO feed VALUES ('imdb-top-250','chart-a',?,'imdb')", (int(time.time()),))
            db.executemany("INSERT INTO member VALUES ('imdb-top-250',?,?,?,?,?,2017,NULL,NULL)", [
                ("movie","tt0000003",99,"tt0000003","Spider-Man 3"),
                ("movie","tt0000002",215,"tt0000002","Spider-Man 2"),
                ("series","tt0000004",1,"tt0000004","Series")
            ])
        first = self.call(sort="top_rated", type="movie", limit=1)
        self.assertEqual("Spider-Man 3", first["metas"][0]["name"])
        second = self.call(sort="top_rated", type="movie", offset=1, snapshot=first["snapshot"])
        self.assertEqual(["Spider-Man 2","Up","Unreleased"], [m["name"] for m in second["metas"]])
        self.assertEqual(["Spider-Man 3","Spider-Man 2"], [m["name"] for m in self.call(
            keyword="Spider-Man", sort="top_rated")["metas"]])
        mixed = self.call(sort="top_rated")
        self.assertEqual(["Series","Spider-Man 3","Spider-Man 2"], [m["name"] for m in mixed["metas"][:3]])
        self.assertEqual("Spider-Man 3", self.call(catalog="hulu", sort="top_rated")["metas"][0]["name"])
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute("UPDATE feed SET snapshot='chart-b' WHERE key='imdb-top-250'")
        with self.assertRaises(dq.QueryError) as changed:
            self.call(sort="top_rated", type="movie", offset=1, snapshot=first["snapshot"])
        self.assertEqual(409, changed.exception.status)

    def test_titles_cast_and_combined_filters(self):
        r = self.call(
            keyword="Spider man",
            type="movie",
            year_min=2010,
            year_max=2019,
            genre="Science Fiction",
            min_rating=8,
        )
        self.assertEqual(["Spider-Man 2"], [x["name"] for x in r["metas"]])
        self.assertEqual(3, self.call(keyword="RENÉE TEST")["total"])
        self.assertEqual(1, self.call(keyword="UP")["total"])
        self.assertEqual(0, self.call(keyword="%_")["total"])
        self.assertEqual(1, self.call(genre="Soap")["total"])
        self.assertEqual(5, self.call(hide_unreleased=True)["total"])

    def test_named_catalog_includes_native_ids_and_credits(self):
        r = self.call(catalog="hulu")
        self.assertEqual(["tt0000003", "tmdb:77"], [x["id"] for x in r["metas"]])
        self.assertIn("Watchmode", r["coverage"])
        self.assertEqual(1, self.call(catalog="hulu", type="series", keyword="Renée Test")["total"])
        self.assertEqual(1, self.call(catalog="hulu", min_rating=8)["total"])
        self.assertEqual(0, self.call(catalog="hulu", genre="Action")["total"])

    def test_named_selection_has_its_own_source_credit(self):
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute("UPDATE feed SET source='justwatch-nuvio-us-sample' WHERE key='hulu'")
        r = self.call(catalog="hulu")
        self.assertEqual(2, r["total"])
        self.assertIn("250 movies", r["coverage"])
        self.assertNotIn("Watchmode", r["coverage"])

    def test_ranked_then_remaining_across_boundary(self):
        first = self.call(sort="trending", limit=1)
        self.assertEqual(first, self.call(limit=1))
        self.assertEqual(6, first["total"])
        second = self.call(sort="trending", offset=1, limit=2, snapshot=first["snapshot"])
        self.assertEqual(["Spider-Man 3", "Up"], [x["name"] for x in second["metas"]])
        self.assertEqual(2, self.call(catalog="trending", sort="trending")["total"])
        self.assertEqual(2, self.call(catalog="hulu", sort="trending")["total"])
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute("INSERT INTO chart_gap VALUES ('trending','movie',320)")
        self.assertIn("empty pages", self.call(type="movie", sort="trending")["coverage"])
        self.assertNotIn("coverage", self.call(type="series", catalog="trending"))

    def test_profile_and_changed_data_reject_old_pages(self):
        page = self.call(limit=1)
        for request in ({"profile": 2}, {"genre": "Drama"}):
            with self.assertRaises(dq.QueryError) as ex:
                self.call(offset=1, snapshot=page["snapshot"], **request)
            self.assertEqual(409, ex.exception.status)
        db = sqlite3.connect(self.data / "catalog.sqlite3")
        db.execute("UPDATE meta SET value='two'")
        db.commit()
        db.close()
        with self.assertRaises(dq.QueryError):
            self.call(offset=1, snapshot=page["snapshot"])

    def test_private_watch_sets_and_missing_feed(self):
        request = {"watch": "watched", "watched_movies": ["tmdb:880"]}
        self.assertEqual(
            ["Spider-Man 3"],
            [x["name"] for x in self.call(resolve=lambda *_: "tt0000003", **request)["metas"]],
        )
        self.assertEqual(0, self.call(watch="watched", watched_series=["tt0000003"])["total"])
        with self.assertRaises(dq.QueryError) as ex:
            self.call(catalog="netflix")
        self.assertEqual("catalog_unavailable", ex.exception.code)
        with self.assertRaises(dq.QueryError):
            self.call(**request)
        with patch("discover_query.time.time", return_value=time.time() + 31 * 86400):
            with self.assertRaises(dq.QueryError):
                self.call(catalog="hulu")

    def test_genre_and_profile_status_for_native_ids(self):
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute("INSERT INTO member VALUES ('genre:soap','series',NULL,2,'n77','native',2015,77,'tv')")
        page = self.call(
            catalog="hulu",
            genre="Soap",
            watch="watched",
            watched_series=["tmdb:77"],
            resolve=lambda kind, uid: uid,
        )
        self.assertEqual(["tmdb:77"], [item["id"] for item in page["metas"]])
        with self.assertRaises(dq.QueryError) as ex:
            self.call(
                catalog="hulu",
                genre="Soap",
                watch="watched",
                watched_series=["tmdb:77"],
                resolve=lambda *_: "tt0000004",
                snapshot=page["snapshot"],
            )
        self.assertEqual(409, ex.exception.status)

    def test_cross_feed_id_and_expired_native_title(self):
        self.assertEqual(1, self.call(keyword="Renée Test Show")["total"])
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute(
                "INSERT INTO member VALUES ('trending','series','tt0000004',5,'n22','other name',2015,77,'tv')"
            )
        self.assertEqual(5, self.call()["total"])
        self.assertIn("tt0000004", [m["id"] for m in self.call(catalog="hulu")["metas"]])
        with sqlite3.connect(self.data / "feeds.sqlite3") as db:
            db.execute("DELETE FROM member WHERE source_id='n22'")
        with patch("discover_query.time.time", return_value=time.time() + 31 * 86400):
            self.assertEqual(5, self.call()["total"])

    def test_http_authentication_validation_and_paging(self):
        (self.data / "config.json").write_text(json.dumps({"tokens": {"1": "unitA", "2": "unitB"}}))
        server = ThreadingHTTPServer(("127.0.0.1", 0), discovery.handler_for(self.data, self.data))
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)

        def post(token, query, mime="application/json"):
            http = HTTPConnection(*server.server_address, timeout=5)
            try:
                http.request("POST", f"/{token}/discover.json", json.dumps(query), {"Content-Type": mime})
                response = http.getresponse()
                self.assertEqual("close", response.getheader("Connection"))
                return response.status, json.loads(response.read())
            finally:
                http.close()

        with patch.object(dq, "DATA", self.data):
            self.assertEqual(404, post("missing", {})[0])
            self.assertEqual(400, post("unitA", {}, "text/plain")[0])
            self.assertEqual(400, post("unitA", {"region": "DK"})[0])
            status, first = post("unitA", {"limit": 1})
            url = first["metas"][0]["poster"]
            self.assertTrue(url.startswith(f"http://127.0.0.1:{server.server_port}/poster/"))
            self.assertNotIn("unitA", url)
            with patch(
                "discovery.PosterArt.resolve",
                return_value=(302, "https://m.media-amazon.com/images/M/test.jpg"),
            ) as lookup:
                poster = HTTPConnection(*server.server_address, timeout=5)
                try:
                    poster.request("GET", "/poster/tt0000001.jpg")
                    image = poster.getresponse()
                    self.assertEqual(302, image.status)
                    self.assertEqual(
                        "https://m.media-amazon.com/images/M/test.jpg", image.getheader("Location")
                    )
                    self.assertEqual(b"", image.read())
                    lookup.assert_called_once_with("tt0000001")
                finally:
                    poster.close()
            self.assertEqual((200, 6, 1), (status, first["total"], first["next_offset"]))
            cursor = {"limit": 1, "offset": 1, "snapshot": first["snapshot"]}
            self.assertEqual(409, post("unitB", cursor)[0])
            self.assertEqual(200, post("unitA", cursor)[0])

    def test_reverse_proxy_posters_use_configured_origin(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0),
                                     discovery.handler_for(self.data, self.data, "https://tv.example.invalid"))
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        (self.data / "config.json").write_text(json.dumps({"tokens": {"1": "unitA"}}))
        with patch.object(dq, "DATA", self.data):
            http = HTTPConnection(*server.server_address, timeout=5)
            try:
                http.request("POST", "/unitA/discover.json", json.dumps({"limit": 1}),
                             {"Content-Type": "application/json", "Host": "untrusted.example.invalid"})
                response = http.getresponse()
                self.assertEqual(200, response.status)
                item = json.loads(response.read())["metas"][0]
                self.assertEqual(f"https://tv.example.invalid/poster/{item['imdb_id']}.jpg", item["poster"])
            finally:
                http.close()

    def test_invalid_input(self):
        for req in (
            {"catalog": "all;drop table"},
            {"limit": 0},
            {"limit": None},
            {"type": "any", "year_min": 2020, "year_max": 2000},
            {"sort": "x"},
            {"watch": "watched", "watched_movies": [{}]},
            {"offset": 1},
            {"min_rating": True},
        ):
            with self.subTest(req=req), self.assertRaises(dq.QueryError) as err:
                self.call(**req)
            self.assertEqual(400, err.exception.status)


if __name__ == "__main__":
    unittest.main()
