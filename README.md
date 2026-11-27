# mkbtv

mkbtv is an Android TV fork of [NuvioTV 1.1.0](https://github.com/NuvioMedia/NuvioTV/tree/1.1.0). It adds a Discover screen designed for the remote, title-based recommendations, and full-screen YouTube trailers.

- **Discover** combines a title or actor with filters and sorting in one request. The optional catalog indexes IMDb metadata and US streaming availability. Posters load from their providers.
- **Home** can show three **Because You Watched** rows with recommendations for individual titles. Three genre rows rotate among the most frequent genres in the last fifteen distinct titles and draw their posters from Trending.
- **Title details** open the cast directly and show related titles. **Play trailer** compares up to three catalog-linked YouTube videos and prefers playable 4K HDR, 4K SDR, 1440p and 1080p formats, with lower resolutions available for older uploads.
- **Playback** supports an optional TorBox Home addon for finding and remembering a verified source for a film or episode.

The companion Discovery server can show a seasonal Home shelf with up to eight editorial picks and eight related movies. It refills as you watch, draws from films released anywhere in the world, and follows the Danish calendar. The [seasonal recommendations guide](docs/seasonal-home.md) lists the 27 themes and their selections.

## Build for Android TV

Use JDK 17, Android SDK 36 and the included Gradle wrapper. From a fresh clone, run:

```sh
git clone https://github.com/emmyapi/mkbtv.git
cd mkbtv
./gradlew :app:assembleFullDebug
```

The mkbtv launcher is in the `fullDebug` variant. Gradle writes the APKs under `app/build/outputs/apk/full/debug/`. Choose the APK for your TV’s CPU architecture or the universal APK. A fresh checkout uses the Android SDK’s local debug signing key. To update an existing installation, supply the keystore that signed it. For cloud login and optional provider integrations, use your own settings in `local.properties`. Available property names are listed in [the example configuration](local.example.properties).

## Run the optional services

The [server setup guide](server/README.md) covers the shared catalog, personalized Discovery addon, watch-history export and TorBox Home integration. It describes the database import, nightly updates and registration for each profile.

To enable server-backed Discover and recommendations, set `MKBTV_DISCOVERY_ORIGIN` in `local.properties` before building. Use the exact scheme, host and port of your server, for example `http://192.0.2.10:8387/`, then install the generated addon manifest for the matching profile. The default is empty. Profile history is sent only to the configured origin for that profile. Keep the manifest token on your own devices.

## License and upstream

mkbtv is distributed under the [GNU General Public License v3.0](LICENSE). The original Android TV project is maintained by [NuvioMedia](https://github.com/NuvioMedia/NuvioTV).
