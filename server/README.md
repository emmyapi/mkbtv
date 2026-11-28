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
