from concurrent.futures import ThreadPoolExecutor
from email.message import Message
from threading import Event
import unittest
from urllib.error import HTTPError
from unittest.mock import Mock

from poster_art import PosterArt, primary_image


class Posters(unittest.TestCase):
    def test_exact_id_trusted_cdn_and_tv_size(self):
        ident = "tt11258284"
        src = "https://m.media-amazon.com/images/M/MV5Babc@._V1_.jpg"
        row = {"id": ident, "i": {"imageUrl": src, "width": 4961}}
        self.assertEqual(primary_image({"d": [row]}, ident), src.replace("._V1_.jpg", "._V1_QL85_UX600_.jpg"))
        row["i"]["width"] = 278
        self.assertEqual(primary_image({"d": [row]}, ident), src)
        self.assertIsNone(primary_image({"d": [row]}, "tt0000001"))
        row["i"]["imageUrl"] = "https://m.media-amazon.com.example.org/images/M/poster.jpg"
        self.assertIsNone(primary_image({"d": [row]}, ident))
        self.assertIsNone(primary_image({"d": [{"id": ident}]}, ident))

    def test_missing_image_cached_and_invalid_route_no_call(self):
        source = Mock(return_value=None)
        art = PosterArt(source)
        self.assertEqual((404, None), art.resolve("tt1/../../other"))
        self.assertEqual((404, None), art.resolve("tt2586634"))
        self.assertEqual((404, None), art.resolve("tt2586634"))
        source.assert_called_once_with("tt2586634")

    def test_concurrent_visible_cards_share_lookup(self):
        started, release = Event(), Event()

        def get(_):
            started.set()
            release.wait(3)
            return "https://m.media-amazon.com/images/M/a.jpg"

        source = Mock(side_effect=get)
        art = PosterArt(source)
        with ThreadPoolExecutor(max_workers=3) as threads:
            first = threads.submit(art.resolve, "tt12590502")
            self.assertTrue(started.wait(3))
            second = threads.submit(art.resolve, "tt12590502")
            release.set()
            self.assertEqual(first.result(), second.result())
        source.assert_called_once()

    def test_upstream_rate_limit_pauses_other_titles(self):
        headers = Message()
        headers["Retry-After"] = "120"
        source = Mock(side_effect=HTTPError("https://example.invalid", 429, "", headers, None))
        art = PosterArt(source)
        self.assertEqual((503, None), art.resolve("tt12590502"))
        self.assertEqual((503, None), art.resolve("tt11258284"))
        source.assert_called_once()


if __name__ == "__main__":
    unittest.main()
