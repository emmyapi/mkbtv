# mkbtv companion server

Discovery serves personalized Home rows and the Discover search API. One Linux machine can host all three components. The optional [TorBox Home addon](torbox-home/) has its own setup.

| Component | Role |
| --- | --- |
| `nuvio-backup/backup.py` | Initializes empty local profiles or exports library, watched titles and playback progress from your Nuvio-compatible backend. |
| `nuvio-discovery/discovery.py` | Builds recommendation rows, issues a separate manifest URL for each profile and serves TV requests. |
| `media-catalog/` | Builds the shared SQLite title, actor and feed indexes for Discover filters, counts, sorting and paging. |

## First run

Run these commands as the user who will operate the server, from the repository's `server/` directory. Use Python 3.11 or later and SQLite with FTS5 trigram and JSON support. Keep at least 8 GiB free on the catalog filesystem when starting an index build.

```sh
umask 077
export MKBTV_STATE_DIR="$HOME/.local/share/mkbtv"
mkdir -p "$MKBTV_STATE_DIR"
python3 -m venv "$MKBTV_STATE_DIR/venv"
. "$MKBTV_STATE_DIR/venv/bin/activate"
python -m pip install requests
```

`MKBTV_STATE_DIR` defaults to the path shown. Use the same absolute path for the HTTP service and scheduled jobs. Account exports live in `history/`, profile tokens and recommendations in `discovery/`, and the shared SQLite databases in `catalog/`.

For an existing installation with separate state locations, Discovery accepts optional absolute paths in `MKBTV_DISCOVERY_DIR`, `MKBTV_HISTORY_DIR` and `MKBTV_CATALOG_DIR`. Point them at the existing Discovery state, history export and published catalog indexes respectively. This preserves profile tokens and history in place.

Choose one source of starting history.

- **TV-only profiles.** Run `python nuvio-backup/backup.py init --profiles 1 2` once, substituting your TV profile numbers (1–6). This creates a verified, empty history without an account. The registered TV can then send recent watched titles for its active profile. Initialization refuses an existing manifest.
- **An existing account.** Supply the four variables below and run `python nuvio-backup/backup.py export`. The export enumerates the account's profiles. Use `python nuvio-backup/backup.py verify` to check the published generation, including its profile checksums.

| Export environment | Value from your installation |
| --- | --- |
| `NUVIO_API_URL` | HTTPS origin of your Nuvio-compatible Supabase backend, e.g. `https://backend.example.org`. |
| `NUVIO_PUBLIC_KEY` | Publishable/anon API key for that backend. |
| `NUVIO_EMAIL` | Email for the account whose history you want to export. |
| `NUVIO_PASSWORD` | Password for that account. |

The exporter requires password authentication, the profile-scoped `sync_pull_profiles`, `sync_pull_library`, `sync_pull_watched_items` and `sync_pull_watch_progress` RPCs, and count access to the account's own `watch_progress` rows. Give these settings only to the exporter process. It writes complete generations before updating the manifest and keeps the latest available generation for each of 31 days and 24 months.

Build the initial recommendations and Discover indexes. The first IMDb build streams the public datasets and can take a while. Home recommendations can be served as soon as the first command completes.

```sh
python nuvio-discovery/discovery.py refresh
python media-catalog/imdb_index.py
python media-catalog/feeds.py
```

For full US subscription-provider catalogs, supply your own `WATCHMODE_API_KEY` to `feeds.py`. Without it, the job uses available US provider samples. Each feed carries its source and publication time.

## Connect a TV

1. Open `$MKBTV_STATE_DIR/discovery/config.json` locally. The first refresh creates `listen`, `port` and a `tokens` map keyed by profile number. For direct LAN access, set `listen` to the server's LAN IPv4 address. Its initial value is `127.0.0.1` and the default port is `8387`. For a reverse proxy, keep the listener on loopback and add `"public_origin": "https://tv.example.org"` for the TV-facing origin. Proxy the root routes and disable access logging for URLs containing profile tokens.
2. Run `python nuvio-discovery/discovery.py serve` in the foreground or as a long-running service. From a device on the TV network, open `/health` on the configured origin. A ready listener returns `{"status":"ok"}`.
3. For Discover queries and live recommendation updates, set `MKBTV_DISCOVERY_ORIGIN` in the Android checkout's `local.properties` before building the TV app. Use the exact TV-facing scheme, host and port, with no token or path. For example, `MKBTV_DISCOVERY_ORIGIN=https://tv.example.org/`. The default is blank.
4. In the active TV profile's addon manager, add the URL `https://tv.example.org/<profile-token>/manifest.json`, using your server's actual origin and that profile's generated token. Do this separately for each profile. Each URL grants access to its profile. The app checks the selected origin and profile ID before sending searches and recent watched titles.

Recommendation lookups send individual title IDs to metadata providers. Search matches use the local SQLite indexes. The TV can fetch a missing poster from a token-free `/poster/<IMDb-id>.jpg` route.

## Movie releases in search

With the Discovery addon connected, **Movie release** appears in Search and Discover. Its filters use the earliest recorded theatrical and home releases worldwide. [TMDB release types](https://developer.themoviedb.org/reference/movie-release-dates)

- **Any** includes series and movies with unknown release dates.
- **Announced** shows films whose known theatrical and home releases are all in the future. A single confirmed date qualifies, including for a film going straight to streaming. Choosing this filter also shows upcoming films when **Hide Unreleased Content** is enabled.
- **In Cinemas** shows films with a recorded theatrical opening on or before today and no recorded home release on or before today.
- **Released** shows films whose first digital or physical release has arrived, including direct-to-streaming films.

The TV displays regular search results immediately and requests release information separately, in batches. Discovery keeps the worldwide dates and available US age certificates in `movie-releases.sqlite3` in its state directory. A background worker looks up movies by ID, checking the exact title against TMDB's country release tables. It also prepares popular films in advance. The release filters include only confirmed matches, so a new search can gain results as the cache fills. In Discover, the match count and every results page use the same filter. With a watch-history filter active, posters use release information already in the shared cache.

Series cards in Search, See All and Discover show the age rating on the left and the series status on the right, using the same labels as the detail page. Visible cards fill missing fields in the background. A card shows whichever fields its metadata provides.

## Long-term For You

Each profile gets one **For You** row of up to 60 films and whole series. Discovery combines the complete verified account export with recent completions reported by the TV. Once IMDb and TMDB identifiers have been matched, each distinct watched work contributes one vote, split across its genres. Finishing several episodes of the same series gives it one vote. A newly completed episode can still refresh the suggestions.

TMDB and Simkl supply recommendations for individual watched titles. A close match to one title can qualify, and recommendations from several distinct titles strengthen a match. Genre catalogs and worldwide releases supply additional candidates. A country and genre seen together in at least two different works can become an interest of its own. Production-country evidence supports this association. Catalogs use worldwide supply and do not require a local streaming subscription.

For You aims to put about one recent release in every five suggestions, including near the front. On a full row that works out to about 12 of 60 titles, with about two among the first ten. The mix can vary with the strength of the matches and which titles are available. A film's earliest known release or a series' first air date determines its age. Worldwide catalog results can also confirm that the premiere fell inside the two-year window. A new season does not change a series' premiere date. Uncertain dates do not earn a recency boost.

Every new foreground visit or newly observed completion can publish a new lineup. For a full row, the target is 30 new cards and 30 eligible returning cards. More can change when upgrading an older lineup or when earlier selections lose eligibility. The first ten are all new to the previous lineup, and the other arrivals are spread through the row. Returning cards keep their relative order when the rewatch limit allows. The last 30 rotations help balance genres and smaller interests among the first ten and farther down the row. Recent repeat appearances reduce a candidate's priority. With a smaller pool, suitable cards can stay longer. A repeated request, opening title details, or a nightly refresh does not spend another rotation. Multiple completions received together trigger one update.

Up to one in ten visible cards can be a rewatch, rounded down using the actual number of titles. At most one can appear in the first ten. Previously watched films become eligible after 180 days. Whole series can appear as discoveries. Episode history alone cannot establish that a series has been finished, so watched series stay out of the rewatch pool. **Because You Watched** and the genre rows continue to use recent viewing.

The Discovery service prepares public metadata and related titles in bounded batches in the background. Its shared cache is under `for-you/public.sqlite3` in the Discovery state directory. Each profile has a private preparation cursor and its own saved lineup, visit IDs and exposure counts. An upstream outage preserves successful lookups. The app can continue rotating from the prepared pool.

From the server directory, run one preparation batch or simulate visits against a prepared profile. The preview leaves the saved lineup and history untouched. Add `--details` to inspect individual selections, matching interests and source titles locally. That output contains private viewing information.

```sh
python nuvio-discovery/discovery.py refresh-for-you
python nuvio-discovery/discovery.py for-you-preview --profile 1 --visits 3
```

## Seasonal Home shelf

The Discovery server prepares the current calendar theme and the next 45 days of public movie data in the background. Keep the Python modules and `seasonal_themes.json` from the same revision together under `nuvio-discovery/`. All season calculations use the system time-zone database for Europe/Copenhagen. The worker stores provider data under the Discovery state directory. The existing profile token controls access to the lineup and viewing feedback.

A fresh profile can see up to eight editorial choices and eight discoveries. The client uses the usual film or whole-series details screen and places the combined row at the bottom of Home by default. Watching a title refills its place from the same theme when a suitable replacement exists. A short row stays visible. An exhausted occurrence stays hidden for that profile until the calendar changes. New films can join an open occurrence on a successful catalog import.

From the server directory, inspect the schedule without a TV, or advance a bounded batch of public-data preparation.

```sh
python nuvio-discovery/discovery.py seasonal-preview --at 2027-12-24T20:00:00+01:00 --theme christmas-favorites
python nuvio-discovery/seasonal_calendar.py 2027
python nuvio-discovery/discovery.py refresh-seasonal
```

To override the editorial school-break defaults, add a `seasonal_calendar` object to the existing private Discovery `config.json`. Use the school year's first and last dates (inclusive). The first day back belongs to the school year containing that summer holiday. Here is an example.

```json
{
  "school_years": {
    "2026-2027": {
      "winter": {"first": "2027-02-20", "last": "2027-02-28"},
      "first_day_back": "2027-08-09"
    }
  }
}
```

The object also accepts `events` by year with organizer-confirmed Oscar and Eurovision dates, and `solstices` by year with an offset-bearing instant. The [calendar and editorial guide](../docs/seasonal-home.md) lists all 27 themes and explains scheduling priorities and how movies qualify.

To remove an incorrect match, add its IMDb or `tmdb:` ID to the list for that theme in the top-level `seasonal_exclusions` object. Discovery applies an exclusion on the next Home refresh. Seasonal previews show each eligible discovery's original references and the matched story trait. A public release in any country qualifies a title for consideration. The shelf uses worldwide release metadata across theatrical, digital and other releases. Playback uses the household’s configured sources, including TorBox. The Copenhagen calendar determines when a theme appears.

## Nightly refresh

Run the two public-data jobs independently. Refresh recommendations after a successful account export. A local-only installation keeps the history created by `init` and schedules `refresh` directly. Here is an example user crontab for local-only profiles. Substitute absolute paths for `SERVER` and `PY`.

```cron
SERVER=/path/to/mkbtv/server
PY=/path/to/mkbtv-state/venv/bin/python
MKBTV_STATE_DIR=/path/to/mkbtv-state

0 1 * * * "$PY" "$SERVER/media-catalog/imdb_index.py"
0 2 * * * "$PY" "$SERVER/media-catalog/feeds.py"
0 4 * * * "$PY" "$SERVER/nuvio-discovery/discovery.py" refresh
```

For account exports, put the four export variables in a private shell environment file readable only by the service user. Replace the last cron entry with the following. The parentheses scope the credentials to the exporter.

```cron
0 4 * * * (set -a && . "$HOME/.config/mkbtv/backup.env" && "$PY" "$SERVER/nuvio-backup/backup.py" export) && "$PY" "$SERVER/nuvio-discovery/discovery.py" refresh
```

## Data sources and tests

IMDb supplies the daily title, alias, rating, episode, name and cast datasets for personal and non-commercial use. IMDb's own terms cover the data. [IMDb Non-Commercial Datasets](https://data.imdb.com/non-commercial-datasets/)

Home uses Nuvio Catalog and Cinemeta for cards and charts, plus TMDB and Simkl for related titles. IMDb supplies the fallback poster. US provider listings come from Watchmode or the published JustWatch Nuvio samples. The Top 250 feed uses the published movie and TV charts mirrored by crazyuploader. [Nuvio Catalog](https://catalog.nuvio.tv/manifest.json) · [Cinemeta](https://v3-cinemeta.strem.io/manifest.json) · [TMDB](https://www.themoviedb.org/) · [Simkl](https://simkl.com/) · [Watchmode](https://api.watchmode.com/) · [JustWatch Nuvio](https://github.com/rdfornasero/justwatch-nuvio) · [IMDb chart mirror](https://github.com/crazyuploader/IMDb-Top-50)

The companion implementation is distributed under the repository's [GPL-3.0 license](../LICENSE). Its tests use synthetic profiles, temporary SQLite indexes and loopback HTTP. From `server/` with the virtual environment active, run:

```sh
python -m unittest discover -s nuvio-backup
python -m unittest discover -s media-catalog
python -m unittest discover -s nuvio-discovery
```
