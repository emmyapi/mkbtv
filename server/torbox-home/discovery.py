"""Read the household's configured addon metadata. Playback stays in the guard."""
import concurrent.futures
import hashlib
import re
import threading
import time
from urllib.parse import urlsplit

HOSTS = {'Comet': 'comet.elfhosted.com', 'MediaFusion': 'mediafusion.elfhosted.com'}
HEX_HASH = r'[0-9a-fA-F]{40}'
VIDEO_SUFFIX = re.compile(r'\.(?:mkv|mp4|m4v|avi|mov|ts|m2ts|webm)$', re.I)


class ProviderFeeds:
    """Share brief, bounded discovery results between interactive requests."""
    def __init__(self, urls, reader):
        self.urls = {}
        for name, url in urls.items():
            parsed = urlsplit(url)
            if (parsed.scheme != 'https' or parsed.netloc != HOSTS[name]
                    or parsed.query or parsed.fragment or not parsed.path.strip('/')):
                raise ValueError('Invalid configured addon origin')
            self.urls[name] = url.rstrip('/')
        self.reader = reader
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=4)
        self.entries = {}
        self.lock = threading.Lock()

    def start(self, path):
        now = time.monotonic()
        pending = {}
        with self.lock:
            self.entries = {k: v for k, v in self.entries.items() if v[0] > now or not v[1].done()}
            for name, base in self.urls.items():
                key = (name, path)
                if key not in self.entries:
                    if len(self.entries) >= 64:
                        oldest = next((k for k, (_, f) in self.entries.items() if f.done()), None)
                        if oldest is None:
                            continue
                        del self.entries[oldest]
                    future = self.pool.submit(self.read, name, base + path)
                    self.entries[key] = (now + 180, future)
                pending[name] = self.entries[key][1]
        return pending

    def read(self, source, url):
        # Only /stream/ metadata is requested. Drop playback URLs and upstream private
        # hints before a response can enter the in-memory cache.
        response = self.reader(url)
        return [row for raw in response.get('streams', []) if (row := provider_row(source, raw))]

    @staticmethod
    def collect(pending, deadline):
        done, _ = concurrent.futures.wait(pending.values(), timeout=max(0, deadline-time.monotonic()))
        return {source: f.result() for source, f in pending.items() if f in done and not f.exception()}


def provider_row(source, raw):
    if not isinstance(raw, dict):
        return None
    title, description, hints = raw.get('name'), raw.get('description'), raw.get('behaviorHints')
    if not isinstance(title, str) or not isinstance(description, str) or not isinstance(hints, dict):
        return None
    filename = hints.get('filename')
    if not isinstance(filename, str) or len(filename) > 1024 or not VIDEO_SUFFIX.search(filename):
        return None
    if source == 'Comet':
        # ElfHosted Comet builds this field from the configured service and info_hash.
        if not re.search(r'\[TB⚡\]\s*Comet\b', title):
            return None
        identity = re.fullmatch(r'comet\|torbox\|('+HEX_HASH+r')', str(hints.get('bingeGroup', '')))
        if not identity:
            return None
    elif source == 'MediaFusion':
        if not re.search(r'\bTRB\s*⚡', title) or 'MediaFusion' not in title:
            return None
        # The hosted instance keeps its playback references private. Its discovery
        # can be joined to an independently known cached file below.
        size = hints.get('videoSize')
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            return None
    else:
        return None
    resolution = stream_resolution(title) or stream_resolution(filename)
    name = resolution or 'Unknown'
    display_format = visual_format(title)
    if display_format:
        name += ' ' + display_format
    row = {'source': source, 'name': name, 'description': description,
           'behaviorHints': {'filename': filename, 'videoSize': hints.get('videoSize')}}
    if source == 'Comet':
        row['infoHash'] = identity[1].lower()
    return row


def conflicting_movie_year(filename, catalog_movie):
    if not catalog_movie:
        return False
    title, year = catalog_movie
    normalize = lambda text: ' '.join(re.findall(r'[^\W_]+', text.casefold()))
    words = ' ' + normalize(filename) + ' '
    marker = ' ' + normalize(title) + ' '
    if marker not in words:
        return False
    tail = words.split(marker, 1)[1]
    # Only the year in the release title can contradict the requested movie.
    # Numeric titles and WxH encodes are not dates; technical/remaster notes
    # after the identified release title are not authoritative release years.
    title_tail = re.split(r'(?i)\b(?:\d{3,4}[pi]|\d{3,4}x\d{3,4}|\d{3,4} x \d{3,4}|remaster(?:ed)?|web|bluray|blu ray|bdrip|dvd|remux|hdr|sdr|hevc|avc|x26[45]|4k)\b', tail, maxsplit=1)[0]
    years = re.findall(r'(?<![a-z0-9])(?:19[0-9]{2}|20[0-9]{2})(?![a-z0-9])', title_tail)
    return bool(years and year not in years)


def mediafusion_candidates(rows, cached, basename, accepts_file):
    """Bind an opaque discovery to an independently known TorBox file.

    Both the full video basename and the provider's reported byte count must
    match. MediaFusion reports season-pack bytes on some episode rows. Keep
    the pack match separate from the file bytes used for bitrate calculation.
    """
    wanted = {}
    for row in rows:
        filename = row['behaviorHints']['filename']
        if accepts_file(filename):
            wanted.setdefault(basename(filename), {}).setdefault(row['behaviorHints']['videoSize'], row)
    matches = {}
    for infohash, torrent in cached.items():
        for file in torrent.get('files') or []:
            if not isinstance(file, dict):
                continue
            name = file.get('name')
            if not isinstance(name, str) or not accepts_file(name):
                continue
            key = basename(name)
            for size in wanted.get(key, ()):
                if size in (file.get('size'), torrent.get('size')):
                    matches.setdefault((key, size), []).append(infohash)
    output = []
    emitted = set()
    for key, found in wanted.items():
        for row in found.values():
            hints = row['behaviorHints']
            hits = matches.get((key, hints['videoSize']), [])
            if len(hits) == 1 and (hits[0], key) not in emitted:
                output.append((row, hits[0], hints['filename']))
                emitted.add((hits[0], key))
    return output


def release_group(stream, filename):
    upstream = str(stream.get('behaviorHints', {}).get('bingeGroup', '')).split('|')
    # AIO's first three fields identify the addon, resolution and quality.
    if len(upstream) == 4 and upstream[0].startswith('com.aiostreams.'):
        group = upstream[-1]
    else:
        provided = re.search(r'🏷️?\s*([A-Za-z0-9][A-Za-z0-9_-]{1,39})(?:\s|$)', stream.get('description', ''))
        stem = VIDEO_SUFFIX.sub('', filename)
        suffix = re.search(r'[-–]\s*([A-Za-z0-9][A-Za-z0-9_-]{1,31})[)\]]?(?:\s*\[[A-Za-z0-9]+\])?$', stem)
        group = provided[1] if provided else (suffix[1] if suffix else '')
    if (not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{1,39}', group) or not re.search(r'[A-Za-z]', group)
            or group.casefold() in {'nogrp','unknown','dl','rip','hd','ma','hevc','avc','x264','x265','webdl','hdr','sdr','eng'}):
        return None
    return group.casefold()


def visual_format(text):
    tags = text.casefold().replace('_', ' ')
    if re.search(r'(?<![a-z0-9])(?:dv|dovi|dolby[ .-]?vision)(?![a-z0-9])', tags):
        return 'DV'
    if re.search(r'(?<![a-z0-9])(?:hlg|hdr(?:10plus|10\+?)?)(?![a-z0-9])', tags):
        return 'HDR'
    return None


def stream_resolution(text):
    match = re.search(r'(?<![a-z0-9])(4320p|2160p|1440p|1080[pi]|720p|576[pi]|480[pi]|360p|240p|144p|8k|4k|uhd|qhd|fhd)(?![a-z0-9])', text, re.I)
    if not match:
        # Audio labels such as DTS-HD MA and Dolby TrueHD do not describe video resolution.
        without_audio = re.sub(r'dts[ ._-]*hd|true[ ._-]*hd', '', text, flags=re.I)
        match = re.search(r'(?<![a-z0-9])(sd|hd)(?![a-z0-9])', without_audio, re.I)
    if not match:
        return None
    value = match[1].lower()
    return {'8k':'4320p','4k':'2160p','uhd':'2160p','qhd':'1440p','fhd':'1080p','hd':'720p'}.get(value, value)


def verified_pack_group(files, season, filename, basename, accepts):
    # The same physical season release can be repacked under a different hash.
    # Identify its exact episode-file inventory, excluding ambiguous multi-cut packs.
    episodes = {}
    for file in files:
        if not isinstance(file, dict):
            continue
        name, size = file.get('name'), file.get('size')
        if not isinstance(name, str) or not accepts(name) or type(size) is not int or size <= 0:
            continue
        name = basename(name)
        markers = re.findall(r'(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(?<![0-9])(\d{1,2})x(\d{1,3})(?![0-9]))', name, re.I)
        if len(markers) != 1:
            continue
        s,e,sx,ex = markers[0]
        if int(s or sx) != season:
            continue
        episode = int(e or ex)
        if episode in episodes:
            return None
        episodes[episode] = (name, size)
    if len(episodes) < 2 or not any(n == basename(filename) for n,_ in episodes.values()):
        return None
    digest = hashlib.sha256()
    for key in sorted(episodes):
        n, size = episodes[key]
        digest.update(f'{key}:{size}:{n}\0'.encode())
    return 'pack-'+digest.hexdigest()[:24]


def binge_group(source, stream, filename, verified_group=None):
    group = release_group(stream, filename) or verified_group
    if not group:
        return None
    detail = (filename + ' ' + stream.get('name', '') + ' ' + stream.get('description', '')).casefold()
    resolution = stream_resolution(filename) or stream_resolution(stream.get('name','')) or 'unknown'
    quality = next((q for q, pattern in (
        ('remux', 'remux'), ('bluray', r'blu[ .-]?ray|bdrip'), ('webdl', r'web[ .-]?dl'),
        ('webrip', r'web[ .-]?rip'), ('hdtv', 'hdtv')) if re.search(pattern,detail)), 'other')
    formats = [label for label, pattern in (
        ('hevc', r'hevc|[hx][ .-]?265'), ('avc', r'\bavc\b|[hx][ .-]?264'),
        ('dv', r'(?<![a-z0-9])dv(?![a-z0-9])|dovi|dolby[ ._-]?vision'), ('hdr', 'hdr'), ('sdr','sdr'),
        ('atmos','atmos'), ('truehd','truehd'), ('dts','dts'), ('ddp',r'ddp|eac3|dolby digital plus')
    ) if re.search(pattern,detail)]
    return '|'.join(['torboxhome',source.casefold(),group,resolution,quality,*formats])
