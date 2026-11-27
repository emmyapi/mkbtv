import unittest
from datetime import datetime, timedelta, timezone

from seasonal_calendar import (
    COPENHAGEN, THEMES, current_occurrence, next_calendar_boundary, upcoming_occurrences, year_preview,
)


def local(value):
    return datetime.fromisoformat(value).replace(tzinfo=COPENHAGEN)


def winner(value, config=None):
    current = current_occurrence(local(value), config)
    return current["theme"] if current else None


def windows(year, config=None):
    return upcoming_occurrences(local(f"{year}-01-01"), days=365, calendar_config=config)


class CalendarTests(unittest.TestCase):
    def test_editorial_identity_and_factual_gates(self):
        self.assertEqual(len(THEMES), 27)
        series = []
        for slug, theme in THEMES.items():
            with self.subTest(theme=slug):
                self.assertEqual((len(theme["references"]), len(theme["reserves"])), (8, 2))
                titles = theme["references"] + theme["reserves"]
                self.assertEqual(len({t["id"] for t in titles}), 10)
                self.assertTrue(theme["rule"] and theme["signals"])
                for t in titles:
                    self.assertRegex(t["id"], r"^tt\d{7,}$")
                    self.assertTrue(t["title"] and t["traits"] and t["note"])
                    self.assertGreater(t["year"], 1900)
                    self.assertIn(t["type"], ("series", "movie"))
                    if t["type"] == "series":
                        series.append(t["id"])
                        self.assertIn(t, theme["references"])
                    if slug == "the-extra-hour":
                        self.assertGreaterEqual(t["runtime_minutes"], 150)
                    if slug == "under-90-minutes":
                        self.assertLess(t["runtime_minutes"], 90)
                    if slug == "the-one-that-got-away":
                        self.assertNotEqual(t["best_picture"]["winner"], t["title"])
        self.assertCountEqual(series, ["tt1526318", "tt0211153"])
        july = THEMES["fourth-of-july"]["references"]
        jaws = next(t for t in july if t["id"] == "tt0073195")
        self.assertIn("fourth of july", jaws["traits"])
        self.assertNotIn("shark", jaws["traits"])

    def test_every_2027_window(self):
        # Inclusive date conventions in the plan become half-open publish intervals.
        expected = {
            "the-morning-after": ("01-01T08:00", "01-02"),
            "groundhog-day-on-repeat": ("02-02", "02-03"),
            "behind-the-mask": ("02-05T16:00", "02-08"),
            "its-complicated": ("02-10", "02-15"),
            "snowed-in": ("02-13", "02-22"),
            "the-one-that-got-away": ("03-12", "03-16"),
            "under-90-minutes": ("03-26T16:00", "03-29"),
            "the-easter-mystery-club": ("03-21", "03-30"),
            "trust-no-one": ("04-01", "04-02"),
            "one-wild-planet": ("04-20", "04-23"),
            "may-the-fourth": ("05-03T16:00", "05-05"),
            "twelve-points": ("05-11", "05-17"),
            "pride-on-screen": ("06-01", "07-01"),
            "made-in-denmark": ("06-04", "06-07"),
            "midsummer-after-dark": ("06-20", "06-24"),
            "one-more-song": ("06-24", "06-28"),
            "out-of-town": ("06-26", "08-09"),
            "fourth-of-july": ("07-02", "07-06"),
            "back-to-school": ("08-06", "08-16"),
            "halloween-after-dark": ("10-01", "11-01"),
            "cabin-weekend": ("10-16", "10-25"),
            "the-extra-hour": ("10-29T16:00", "11-01"),
            "dinner-is-served": ("11-24", "11-29"),
            "christmas-favorites": ("11-28", "12-27"),
            "until-sunrise": ("12-22T18:00", "12-23T06:00"),
            "one-last-movie-before-new-year": ("12-27", "2028-01-01"),
            "unlucky-friday": ("08-13", "08-14"),
        }
        actual = {w["theme"]: (w["start"], w["end"]) for w in windows(2027) if w["start"].year == 2027}
        expected = {slug: tuple(local(s if s.startswith("2028-") else "2027-" + s) for s in dates)
                    for slug, dates in expected.items()}
        self.assertEqual(actual, expected)

    def test_absolute_clocks_and_midnight_boundaries(self):
        self.assertIsNone(winner("2027-01-01T07:59:59"))
        utc = datetime(2027, 1, 1, 7, tzinfo=timezone.utc)
        self.assertEqual(current_occurrence(utc)["theme"], "the-morning-after")
        self.assertEqual(next_calendar_boundary(utc), local("2027-01-02"))
        self.assertEqual(next_calendar_boundary(local("2027-01-02")), local("2027-02-02"))
        self.assertIsNone(winner("2027-05-03T15:59:59"))
        self.assertEqual(winner("2027-05-03T16:00"), "may-the-fourth")
        self.assertEqual(winner("2027-12-26T23:59:59"), "christmas-favorites")
        self.assertEqual(winner("2027-12-27"), "one-last-movie-before-new-year")
        self.assertEqual(next_calendar_boundary(local("2027-12-31T23:59")), local("2028-01-01"))
        self.assertIsNone(winner("2028-01-01"))

    def test_clock_changes_and_protected_dates(self):
        for date, expected in [
            ("2027-03-26T15:59", "the-easter-mystery-club"), ("2027-03-26T16:00", "under-90-minutes"),
            ("2027-03-27", "under-90-minutes"), ("2027-03-28", "the-easter-mystery-club"),
            ("2027-03-29", "the-easter-mystery-club"), ("2027-06-23", "midsummer-after-dark"),
            ("2027-07-04", "fourth-of-july"), ("2027-10-29T16:00", "the-extra-hour"),
            ("2027-10-30", "halloween-after-dark"), ("2027-10-31", "halloween-after-dark"),
            ("2027-12-22T18:00", "until-sunrise"), ("2027-12-23", "christmas-favorites"),
        ]:
            with self.subTest(date=date):
                self.assertEqual(winner(date), expected)
        selected = current_occurrence(local("2027-03-26T16:00"))
        self.assertEqual(selected["start"].utcoffset(), timedelta(hours=1))
        self.assertEqual(selected["end"].utcoffset(), timedelta(hours=2))
        self.assertEqual(selected["next_boundary"], local("2027-03-28"))
        selected = current_occurrence(local("2027-10-29T16:00"))
        self.assertEqual(selected["start"].utcoffset(), timedelta(hours=2))
        self.assertEqual(selected["end"].utcoffset(), timedelta(hours=1))
        self.assertEqual(selected["next_boundary"], local("2027-10-30"))

    def test_interrupted_occurrence_keeps_annual_id_and_real_end(self):
        first = current_occurrence(local("2027-06-03"))
        self.assertEqual(first["next_boundary"], local("2027-06-04"))
        takeover = current_occurrence(local("2027-06-04"))
        self.assertEqual(takeover["theme"], "made-in-denmark")
        self.assertEqual(takeover["next_boundary"], local("2027-06-07"))
        resumed = current_occurrence(local("2027-06-07"))
        for key in ("id", "start", "end"):
            self.assertEqual(first[key], resumed[key])
        self.assertEqual(resumed["end"], local("2027-07-01"))
        self.assertNotEqual(resumed["id"], current_occurrence(local("2028-06-01"))["id"])
        fridays = [w for w in windows(2026) if w["theme"] == "unlucky-friday"]
        self.assertEqual(len({w["id"] for w in fridays}), 3)

    def test_school_year_and_weekends_are_applied_once(self):
        config = {"school_years": {
            "2026-2027": {
                "winter": {"first": "2027-02-20", "last": "2027-02-28"},
                "summer": {"first": "2027-06-28", "last": "2027-07-30"},
                "first_day_back": "2027-08-04",
                "autumn": {"first": "2026-10-05", "last": "2026-10-09"},
            },
            "2027-2028": {"autumn": {"first": "2027-10-11", "last": "2027-10-15"}},
        }}
        table = {w["theme"]: w for w in windows(2027, config)}
        for theme, first, end in [
            ("snowed-in", "02-20", "03-01"), ("out-of-town", "06-26", "08-02"),
            ("back-to-school", "08-01", "08-11"), ("cabin-weekend", "10-09", "10-18"),
        ]:
            self.assertEqual(table[theme]["start"], local("2027-" + first))
            self.assertEqual(table[theme]["end"], local("2027-" + end))

    def test_unknown_announcements_and_solstice_conversion(self):
        self.assertNotIn("twelve-points", {w["theme"] for w in windows(2028)})
        self.assertNotIn("the-one-that-got-away", {w["theme"] for w in windows(2029)})
        config = {"events": {"2027": {"oscars": None}}, "solstices": {"2027": "2027-12-20T23:30:00Z"}}
        self.assertIsNone(winner("2027-03-15", config))
        self.assertEqual(winner("2027-12-21T18:00", config), "until-sunrise")
        self.assertEqual(winner("2027-12-22T05:59", config), "until-sunrise")
        self.assertEqual(winner("2027-12-22T06:00", config), "christmas-favorites")
        self.assertEqual(winner("2027-12-21T18:00"), "christmas-favorites")
        partial = {"events": {"2027": {"eurovision": {
            "final": "2027-05-15", "zone": "Europe/Sofia", "source": "fixture",
        }}}}
        self.assertIsNone(winner("2027-05-11", partial))
        self.assertEqual(winner("2027-06-04", partial), "made-in-denmark")

    def test_preparation_precedes_visibility_and_includes_displaced_occurrences(self):
        current = local("2027-09-01")
        self.assertIsNone(current_occurrence(current))
        upcoming = upcoming_occurrences(current)
        self.assertIn("halloween-after-dark", {w["theme"] for w in upcoming})
        self.assertNotIn("the-extra-hour", {w["theme"] for w in upcoming})
        self.assertIn("under-90-minutes", {w["theme"] for w in upcoming_occurrences(local("2027-03-28"), 0)})

    def test_preview_with_displaced_and_unseen_events(self):
        preview = year_preview(2027)
        self.assertEqual(len(preview["days"]), 365)
        self.assertEqual(preview["unscheduled"], [])
        halloween = preview["days"]["2027-10-31"][0]
        self.assertEqual(halloween["winner"], "halloween-after-dark:2027")
        self.assertEqual(halloween["displaced"], ["the-extra-hour:2027"])
        # Same four-day window and peak as One More Song. Earlier declaration wins.
        config = {"events": {"2027": {"oscars": {
            "date": "2027-06-26", "zone": "America/Los_Angeles", "source": "fixture",
        }}}}
        preview = year_preview(2027, config)
        self.assertIn("one-more-song:2027", preview["never_selected"])
        self.assertEqual(preview["days"]["2027-06-25"][0]["winner"], "the-one-that-got-away:2027")
        self.assertIn("twelve-points", year_preview(2028)["unscheduled"])
        # On June 26 the closer peak changes the winner without a window opening or closing.
        config["events"]["2027"]["oscars"]["date"] = "2027-06-25"
        self.assertEqual(winner("2027-06-25", config), "the-one-that-got-away")
        self.assertEqual(next_calendar_boundary(local("2027-06-25"), config), local("2027-06-26"))
        self.assertEqual(winner("2027-06-26", config), "one-more-song")


if __name__ == "__main__":
    unittest.main()
