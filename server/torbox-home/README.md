# TorBox Home

This optional mkbtv playback addon checks source metadata against your TorBox cache. It verifies the selected file and its audio tracks before redirecting the player to TorBox. It requires an English or original-language audio track. The guard requests cached files only and caps estimated bitrate at 80 Mbit/s by default.

## Configure the addon

On a Linux host reachable from the TV, install Python 3.10 or newer, FFmpeg (with `ffprobe` on the service's `PATH`) and Debian's `iso-codes` data under `/usr/share/iso-codes/json`. On Debian or Ubuntu, the packages are `python3 ffmpeg iso-codes`. The guard uses the Python standard library.

1. Import [aiostreams-profile.json](aiostreams-profile.json) into your AIOStreams instance and save the profile. It configures Torrentio's raw stream metadata and the Prism formatter used for language information. Copy the profile's manifest URL and remove its final `/manifest.json` to get `AIO_UPSTREAM`.
2. Generate two independent keys in a private file. Run this once in Bash. The shell's noclobber setting protects keys from an earlier installation.

   ```bash
   install -d -m 700 "$HOME/.config/mkbtv"
   (
     umask 077
     set -C
     for name in GUARD_ACCESS_KEY GUARD_SIGNING_KEY; do
       printf '%s=%s\n' "$name" "$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
     done > "$HOME/.config/mkbtv/torbox-home.env"
   )
   ```

3. Open that file in an editor. Append these settings, replacing the placeholders. Use the host's private IPv4 address for the listener and the origin the TV will reach. The default port is 4301. The access key is part of the private addon URL, and the signing key stays on the server.

   ```sh
   GUARD_LISTEN_HOST='<server LAN IPv4>'
   GUARD_BASE_URL='http://<server LAN IPv4>:4301'
   AIO_UPSTREAM='<your AIOStreams profile base URL>'
   TORBOX_API_KEY='<your TorBox API key>'
   ```

   To enable Comet or MediaFusion, append `COMET_UPSTREAM` or `MEDIAFUSION_UPSTREAM` with the configured TorBox profile's HTTPS base URL. The guard supports profiles from `comet.elfhosted.com` and `mediafusion.elfhosted.com`. Strip the final `/manifest.json` from each. To change the bitrate cap, set `GUARD_MAX_MBIT`. To use another port, set `GUARD_PORT` and update `GUARD_BASE_URL`.

4. From this directory, load the settings and start the guard. The health endpoint at `/health` returns `{"ok": true}` when the listener is ready.

   ```sh
   set -a
   . "$HOME/.config/mkbtv/torbox-home.env"
   set +a
   python3 -B guard.py
   ```

5. In mkbtv's addon manager install `<GUARD_BASE_URL>/<GUARD_ACCESS_KEY>/manifest.json`. Use TorBox Home as the playback addon and keep the raw AIOStreams profile on the server. The Android client recognizes the name `TorBox Home` and the manifest ID `local.mkb.torboxhome`. Keep access on your trusted LAN. Private manifest URLs and signed playback URLs grant access to anyone who has them.

## Tests

Run the offline suite from this directory. It uses synthetic TorBox responses, audio fixtures and a temporary loopback HTTP server.

```sh
python3 -B -m unittest discover -s tests
```
