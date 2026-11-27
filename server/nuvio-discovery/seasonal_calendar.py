"""Danish seasonal programming. Windows are half-open, with aware Copenhagen times.

Optional calendar_config is JSON shaped. school_years["2026-2027"] can have winter,
summer and autumn objects with ISO dates "first" and "last" (full break, inclusive).
Autumn belongs to the first year of the pair, winter and summer to the second.
"first_day_back" in that same school year is the return from its summer holiday.
events["2027"] can override oscars or eurovision with the same records as EVENTS
below, or null to suspend an announcement. solstices["2027"] can override the
astronomical instant with an offset-bearing ISO timestamp, or null if unconfirmed.
"""

import argparse
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo

COPENHAGEN = ZoneInfo("Europe/Copenhagen")
DAY = timedelta(days=1)
# IMDb title.basics primary/original titles, years and title types verified on 2026-11-27.
# https://data.imdb.com/non-commercial-datasets/
with Path(__file__).with_name("seasonal_themes.json").open(encoding="utf-8") as f:
    THEMES = {theme["slug"]: theme for theme in json.load(f)}
ORDER = {slug: i for i, slug in enumerate(THEMES)}

ACADEMY_ANNOUNCEMENT = "https://press.oscars.org/news/academy-and-abc-announce-show-dates-99th-and-100th-oscarsr"
EVENTS = {
    "2026": {
        "oscars": {"date": "2026-03-15", "zone": "America/Los_Angeles",
                   "source": "https://www.oscars.org/oscars/ceremonies/2026"},
        "eurovision": {"first": "2026-05-12", "final": "2026-05-16", "zone": "Europe/Vienna",
                       "source": "https://www.eurovision.com/eurovision-song-contest/vienna-2026/"},
    },
    "2027": {
        "oscars": {"date": "2027-03-14", "zone": "America/Los_Angeles", "source": ACADEMY_ANNOUNCEMENT},
        "eurovision": {"first": "2027-05-11", "final": "2027-05-15", "zone": "Europe/Sofia",
                       "source": "https://www.eurovision.com/newsroom/frequently-asked-questions/faqs-burgas-2027/"},
    },
    "2028": {
        "oscars": {"date": "2028-03-05", "zone": "America/Los_Angeles", "source": ACADEMY_ANNOUNCEMENT},
    },
}
# Fred Espenak's astronomical ephemeris, December solstice (GMT, to the minute).
# https://astropixels.com/ephemeris/soleq2001.html
SOLSTICES = {stamp[:4]: stamp for stamp in (
    "2001-12-21T19:22+00:00", "2002-12-22T01:15+00:00", "2003-12-22T07:04+00:00",
    "2004-12-21T12:42+00:00", "2005-12-21T18:35+00:00", "2006-12-22T00:22+00:00",
    "2007-12-22T06:08+00:00", "2008-12-21T12:04+00:00", "2009-12-21T17:47+00:00",
    "2010-12-21T23:38+00:00", "2011-12-22T05:30+00:00", "2012-12-21T11:12+00:00",
    "2013-12-21T17:11+00:00", "2014-12-21T23:03+00:00", "2015-12-22T04:48+00:00",
    "2016-12-21T10:45+00:00", "2017-12-21T16:29+00:00", "2018-12-21T22:22+00:00",
    "2019-12-22T04:19+00:00", "2020-12-21T10:03+00:00", "2021-12-21T15:59+00:00",
    "2022-12-21T21:48+00:00", "2023-12-22T03:28+00:00", "2024-12-21T09:20+00:00",
    "2025-12-21T15:03+00:00", "2026-12-21T20:50+00:00", "2027-12-22T02:43+00:00",
    "2028-12-21T08:20+00:00", "2029-12-21T14:14+00:00", "2030-12-21T20:09+00:00",
    "2031-12-22T01:56+00:00", "2032-12-21T07:57+00:00", "2033-12-21T13:45+00:00",
    "2034-12-21T19:35+00:00", "2035-12-22T01:31+00:00", "2036-12-21T07:12+00:00",
    "2037-12-21T13:08+00:00", "2038-12-21T19:01+00:00", "2039-12-22T00:41+00:00",
    "2040-12-21T06:33+00:00", "2041-12-21T12:19+00:00", "2042-12-21T18:04+00:00",
    "2043-12-22T00:02+00:00", "2044-12-21T05:43+00:00", "2045-12-21T11:36+00:00",
    "2046-12-21T17:28+00:00", "2047-12-21T23:07+00:00", "2048-12-21T05:02+00:00",
    "2049-12-21T10:51+00:00", "2050-12-21T16:39+00:00", "2051-12-21T22:33+00:00",
    "2052-12-21T04:18+00:00", "2053-12-21T10:09+00:00", "2054-12-21T16:10+00:00",
    "2055-12-21T21:56+00:00", "2056-12-21T03:52+00:00", "2057-12-21T09:42+00:00",
    "2058-12-21T15:24+00:00", "2059-12-21T21:18+00:00", "2060-12-21T03:00+00:00",
    "2061-12-21T08:49+00:00", "2062-12-21T14:42+00:00", "2063-12-21T20:22+00:00",
    "2064-12-21T02:10+00:00", "2065-12-21T07:59+00:00", "2066-12-21T13:45+00:00",
    "2067-12-21T19:44+00:00", "2068-12-21T01:34+00:00", "2069-12-21T07:21+00:00",
    "2070-12-21T13:19+00:00", "2071-12-21T19:05+00:00", "2072-12-21T00:54+00:00",
    "2073-12-21T06:50+00:00", "2074-12-21T12:36+00:00", "2075-12-21T18:28+00:00",
    "2076-12-21T00:12+00:00", "2077-12-21T06:00+00:00", "2078-12-21T11:59+00:00",
    "2079-12-21T17:46+00:00", "2080-12-20T23:31+00:00", "2081-12-21T05:22+00:00",
    "2082-12-21T11:06+00:00", "2083-12-21T16:51+00:00", "2084-12-20T22:40+00:00",
    "2085-12-21T04:29+00:00", "2086-12-21T10:24+00:00", "2087-12-21T16:07+00:00",
    "2088-12-20T21:56+00:00", "2089-12-21T03:53+00:00", "2090-12-21T09:45+00:00",
    "2091-12-21T15:37+00:00", "2092-12-20T21:31+00:00", "2093-12-21T03:21+00:00",
    "2094-12-21T09:11+00:00", "2095-12-21T15:00+00:00", "2096-12-20T20:46+00:00",
    "2097-12-21T02:38+00:00", "2098-12-21T08:19+00:00", "2099-12-21T14:04+00:00",
    "2100-12-21T19:51+00:00",
)}


def _clock(day, hour=0):
    return datetime.combine(day, time(hour), COPENHAGEN)


def _local(instant):
    if instant.utcoffset() is None:
        raise ValueError("An absolute time with a time zone is required")
    return instant.astimezone(COPENHAGEN)


def _easter(year):
    # Anonymous Gregorian computus (Meeus/Jones/Butcher).
    a = year % 19
    b, c = divmod(year, 100)
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    weekday = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * weekday) // 451
    month, day = divmod(h + weekday - 7 * m + 114, 31)
    return date(year, month, day + 1)


def _changes(year):
    """Find actual Copenhagen offset changes, including years without a change."""
    current = date(year, 1, 1)
    previous = _clock(current, 12).utcoffset()
    while current.year == year:
        offset = _clock(current, 12).utcoffset()
        if offset != previous:
            yield current, offset > previous
        previous = offset
        current += DAY


def _school_break(spec, first, last):
    if not spec:
        return first, last
    first = date.fromisoformat(spec["first"])
    last = date.fromisoformat(spec["last"])
    while first.weekday() >= 5:
        first += DAY
    while last.weekday() >= 5:
        last -= DAY
    if first > last:
        raise ValueError("School break must contain a weekday")
    # Extend only adjoining weekends. This also accepts a published range
    # which already includes one or both weekends.
    if first.weekday() == 0:
        first -= 2 * DAY
    if last.weekday() == 4:
        last += 2 * DAY
    return first, last


@dataclass(frozen=True)
class _Window:
    theme: str
    id: str
    start: datetime
    end: datetime
    peak: date
    protected: tuple

    def record(self):
        return {"theme": self.theme, "id": self.id, "start": self.start, "end": self.end}


@lru_cache(maxsize=48)
def _year_windows(year, config_json):
    config = json.loads(config_json)
    school_years = config.get("school_years", {})
    spring = school_years.get(f"{year - 1}-{year}", {})
    fall = school_years.get(f"{year}-{year + 1}", {})
    events = {**EVENTS.get(str(year), {}), **config.get("events", {}).get(str(year), {})}
    windows = []

    def day(month, number):
        return date(year, month, number)

    def add(theme, first, last=None, *, peak=None, hour=0, until=0, protected=(), repeat=False):
        # last is the inclusive final civil date. A nonzero until gives the
        # exclusive hour on that date (used by the overnight solstice window).
        if last is None:
            last = first
        identity = first.isoformat() if repeat else str(year)
        end = _clock(last, until) if until else _clock(last + DAY)
        if first.year != year or _clock(first, hour) >= end:
            raise ValueError(f"Invalid {theme} window in {year}")
        windows.append(_Window(theme, f"{theme}:{identity}", _clock(first, hour), end,
                               peak or first, tuple(protected)))

    easter = _easter(year)
    fastelavn = easter - 49 * DAY
    add("the-morning-after", day(1, 1), hour=8)
    add("groundhog-day-on-repeat", day(2, 2))
    add("behind-the-mask", fastelavn - 2 * DAY, fastelavn, peak=fastelavn, hour=16)
    add("its-complicated", day(2, 10), day(2, 14), peak=day(2, 14))

    winter = date.fromisocalendar(year, 7, 1)
    first, last = _school_break(spring.get("winter"), winter - 2 * DAY, winter + 6 * DAY)
    add("snowed-in", first, last, peak=first + (last - first) // 2)
    ceremony = _event_date(events.get("oscars"), "date", year)
    if ceremony:
        add("the-one-that-got-away", ceremony - 2 * DAY, ceremony + DAY, peak=ceremony)

    for change, is_spring in _changes(year):
        theme = "under-90-minutes" if is_spring else "the-extra-hour"
        friday = change - ((change.weekday() - 4) % 7) * DAY
        add(theme, friday, change, peak=change, hour=16)

    add("the-easter-mystery-club", easter - 7 * DAY, easter + DAY, peak=easter,
        protected=(easter, easter + DAY))
    add("trust-no-one", day(4, 1))
    add("one-wild-planet", day(4, 20), day(4, 22), peak=day(4, 22))
    add("may-the-fourth", day(5, 3), day(5, 4), peak=day(5, 4), hour=16)
    first = _event_date(events.get("eurovision"), "first", year)
    final = _event_date(events.get("eurovision"), "final", year)
    if first and final:
        if final < first:
            raise ValueError("Eurovision final must follow the first semifinal")
        add("twelve-points", first, final + DAY, peak=final)

    add("pride-on-screen", day(6, 1), day(6, 30), peak=day(6, 15))
    add("made-in-denmark", day(6, 4), day(6, 6), peak=day(6, 5))
    add("midsummer-after-dark", day(6, 20), day(6, 23), peak=day(6, 23), protected=(day(6, 23),))
    june_end = day(6, 30)
    thursday = june_end - ((june_end.weekday() - 3) % 7) * DAY
    add("one-more-song", thursday, thursday + 3 * DAY, peak=thursday + 2 * DAY)
    saturday = june_end - ((june_end.weekday() - 5) % 7) * DAY
    first, last = _school_break(spring.get("summer"), saturday, saturday + 43 * DAY)
    add("out-of-town", first, last, peak=first + (last - first) // 2)
    add("fourth-of-july", day(7, 2), day(7, 5), peak=day(7, 4), protected=(day(7, 4),))
    back = date.fromisoformat(spring["first_day_back"]) if spring.get("first_day_back") else last + DAY
    add("back-to-school", back - 3 * DAY, back + 6 * DAY, peak=back)
    add("halloween-after-dark", day(10, 1), day(10, 31), peak=day(10, 31),
        protected=(day(10, 30), day(10, 31)))
    autumn = date.fromisocalendar(year, 42, 1)
    first, last = _school_break(fall.get("autumn"), autumn - 2 * DAY, autumn + 6 * DAY)
    add("cabin-weekend", first, last, peak=first + (last - first) // 2)

    november = day(11, 1)
    thanksgiving = november + ((3 - november.weekday()) % 7 + 21) * DAY
    add("dinner-is-served", thanksgiving - DAY, thanksgiving + 3 * DAY, peak=thanksgiving)
    advent = day(11, 27) + ((6 - day(11, 27).weekday()) % 7) * DAY
    add("christmas-favorites", min(advent, day(12, 1)), day(12, 26), peak=day(12, 25),
        protected=tuple(day(12, n) for n in range(23, 27)))
    solstice = config.get("solstices", {}).get(str(year), SOLSTICES.get(str(year)))
    if solstice:
        local_date = _local(datetime.fromisoformat(solstice.replace("Z", "+00:00"))).date()
        add("until-sunrise", local_date, local_date + DAY, hour=18, until=6)
    add("one-last-movie-before-new-year", day(12, 27), day(12, 31), peak=day(12, 31))
    for month in range(1, 13):
        thirteenth = day(month, 13)
        if thirteenth.weekday() == 4:
            add("unlucky-friday", thirteenth, repeat=True)
    return tuple(windows)


def _event_date(event, key, year):
    # Organizers publish these as host-city CIVIL dates. A televised start
    # time converted to Copenhagen would put the Oscars on the following day.
    if not event or not event.get("confirmed", True) or not all(event.get(k) for k in (key, "source", "zone")):
        return None
    ZoneInfo(event["zone"])
    value = date.fromisoformat(event[key])
    if value.year != year:
        raise ValueError("Organizer date must belong to its declared year")
    return value


def _windows(first_year, last_year, calendar_config):
    config = json.dumps(calendar_config or {}, sort_keys=True)
    return tuple(w for year in range(first_year, last_year + 1) for w in _year_windows(year, config))


def _ranked(now, windows):
    today = now.date()
    return sorted((w for w in windows if w.start <= now < w.end), key=lambda w: (
        today not in w.protected, w.end.timestamp() - w.start.timestamp(),
        abs((today - w.peak).days), ORDER[w.theme],
    ))


def _boundaries(start, end, windows):
    # Local midnight can change holiday protection or distance to a tied
    # event even when no window starts or ends there.
    changes = {start, end}
    midnight = _clock(start.date() + DAY)
    while midnight < end:
        changes.add(midnight)
        midnight += DAY
    for w in windows:
        changes.update(t for t in (w.start, w.end) if start < t < end)
    return sorted(changes)


def _selection(now, calendar_config):
    now = _local(now)
    windows = _windows(now.year, now.year + 1, calendar_config)
    ranked = _ranked(now, windows)
    winner = ranked[0] if ranked else None
    # The January 1 slot and January 2 gap bound the next possible change.
    end = _clock(date(now.year + 1, 1, 2))
    for instant in _boundaries(now, end, windows)[1:]:
        choices = _ranked(instant, windows)
        if (choices[0] if choices else None) != winner:
            return winner, instant
    raise ValueError("No upcoming calendar boundary")


def current_occurrence(now, calendar_config=None):
    """The calendar winner, independent of catalog contents or user history."""
    winner, boundary = _selection(now, calendar_config)
    return {**winner.record(), "next_boundary": boundary} if winner else None


def next_calendar_boundary(now, calendar_config=None):
    """Next change of winner, including the end of an empty interval."""
    return _selection(now, calendar_config)[1]


def upcoming_occurrences(now, days=45, calendar_config=None):
    """All windows intersecting the horizon, including currently displaced ones."""
    now = _local(now)
    if days < 0:
        raise ValueError("Lookahead must be nonnegative")
    end = now + days * DAY
    windows = _windows(now.year, end.year, calendar_config)
    active = [w for w in windows if now < w.end and w.start <= end]
    return [w.record() for w in sorted(active, key=lambda w: (w.start, ORDER[w.theme]))]


def year_preview(year, calendar_config=None):
    """Date-indexed segments with the winner and every displaced occurrence ID."""
    windows = _windows(year, year, calendar_config)
    boundaries = _boundaries(_clock(date(year, 1, 1)), _clock(date(year + 1, 1, 1)), windows)
    days = {}
    winning_ids = set()
    for start, end in zip(boundaries, boundaries[1:]):
        ranked = _ranked(start, windows)
        winner = ranked[0].id if ranked else None
        winning_ids.add(winner)
        days.setdefault(start.date().isoformat(), []).append({
            "start": start.isoformat(), "end": end.isoformat(),
            "winner": winner, "displaced": [w.id for w in ranked[1:]],
        })
    return {"days": days, "never_selected": sorted(w.id for w in windows if w.id not in winning_ids),
            "unscheduled": sorted(THEMES.keys() - {w.theme for w in windows})}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Preview the Danish seasonal Home programming calendar")
    parser.add_argument("year", type=int)
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text()) if args.config else None
    print(json.dumps(year_preview(args.year, config), indent=2))
