"""Raw AIO metadata -> known TorBox cache -> atomic cache-only HTTP playback."""
import base64
import hashlib
import hmac
from functools import lru_cache
from http.client import HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import PurePosixPath
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from audio import ProbeUnavailable, language, probe_audio, provider_audio
from discovery import stream_resolution, visual_format, conflicting_movie_year, ProviderFeeds, binge_group, mediafusion_candidates, verified_pack_group

TB = 'https://api.torbox.app/v1/api/'
# TorBox Help Center, article 13181686 (official delivery domains).
DELIVERY_DOMAINS = ('torbox.app','tb-cdn.cx','tb-cdn.io','tb-cdn.pw','tb-cdn.sh','tb-cdn.st','tb-cdn.to','tb-cdn.earth')
HASH = re.compile(r'^[0-9a-fA-F]{40}$')
VIDEO = re.compile(r'\.(mkv|mp4|m4v|avi|mov|ts|m2ts|webm)$', re.I)
SAMPLE = re.compile(r'(^|[\W_])(sample|trailer|extras?|featurette)([\W_]|$)', re.I)

class Rejected(Exception):
    def __init__(self, status, reason):
        self.status, self.reason = status, reason

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def request(method, url, headers=None, body=None, timeout=35):
    try:
        req = urllib.request.Request(url, data=body, method=method,
            headers={'User-Agent':'TorBoxHome/1.0','Accept':'application/json',**(headers or {})})
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=timeout) as response:
            data = response.read(16 * 1024 * 1024 + 1)
            if len(data) > 16 * 1024 * 1024:
                raise Rejected(502, 'Upstream response too large')
            return json.loads(data)
    except urllib.error.HTTPError as error:
        not_cached = error.code == 409
        if url.startswith(TB):
            try:
                not_cached = not_cached or json.loads(error.read(65536)).get('error') == 'DOWNLOAD_NOT_CACHED'
            except (ValueError,AttributeError):pass
        raise Rejected(409 if not_cached else 503,
            'The video is no longer cached' if not_cached else 'Upstream refused the request') from None
    except (OSError, HTTPException, ValueError):
        raise Rejected(503, 'Upstream unavailable') from None

def base_name(name):
    return PurePosixPath(str(name).replace('\\', '/')).name.casefold()

def episode_matches(name, season, episode):
    pairs = re.findall(r'(?i)s(\d{1,2})[ ._-]*e(\d{1,3})|\b(\d{1,2})x(\d{1,3})\b', name)
    return any((int(a or c), int(b or d)) == (season, episode) for a,b,c,d in pairs)

def playable(name):
    return bool(VIDEO.search(name)) and not SAMPLE.search(name)

def multiple_episodes(filename):
    markers = re.findall(r'(?i)s\d{1,2}[ ._-]*e\d{1,3}|\b\d{1,2}x\d{1,3}', filename)
    return len(markers) > 1 or bool(re.search(
        r'(?i)(?:e|\d{1,2}x)\d{1,3}(?:[ ._+-]*(?:e|x)\d{1,3}|\s*[-+]\s*\d{1,3}(?!\d|p))', filename))

def positive_integer(value):
    return type(value) is int and value > 0

def public_metadata(url):
    return request('GET', url, timeout=4)

@lru_cache(maxsize=128)
def movie_metadata(title_id, get_json, cache_period):
    if not isinstance(title_id, str) or not re.fullmatch(r'tt\d+', title_id):
        return {}
    try:
        movie = get_json('https://v3-cinemeta.strem.io/meta/movie/' + title_id + '.json')['meta']
        if movie.get('id') == title_id and movie.get('type') == 'movie':
            return movie
    except (Rejected, KeyError, AttributeError, TypeError):
        pass
    return {}


def movie_seconds(title_id, get_json):
    movie = movie_metadata(title_id, get_json, int(time.monotonic() // 300))
    runtime = re.fullmatch(r'(\d+)\s*min', str(movie.get('runtime')), re.I)
    if runtime and int(runtime[1]) > 0:
        return int(runtime[1]) * 60
    return None

@lru_cache(maxsize=128)
def catalog_record(kind, title_id, get_json, cache_period):
    """Return only an unambiguous Radarr movie or Skyhook series for this ID."""
    imdb = title_id.startswith('tt')
    identity = title_id if imdb else int(title_id.removeprefix('tmdb:'))
    try:
        if kind == 'movie':
            path = 'imdb/' + title_id if imdb else str(identity)
            records = get_json('https://api.radarr.video/v1/movie/' + path)
            records = records if isinstance(records, list) else [records]
            field = 'ImdbId' if imdb else 'TmdbId'
        else:
            term = 'imdb:' + title_id if imdb else title_id
            records = get_json('https://skyhook.sonarr.tv/v1/tvdb/search/en/?' + urllib.parse.urlencode({'term': term}))
            field = 'imdbId' if imdb else 'tmdbId'
        rows = [row for row in records if isinstance(row, dict) and row.get(field) == identity]
        return rows[0] if len(rows) == 1 else {}
    except (Rejected, AttributeError, TypeError):
        return {}


@lru_cache(maxsize=128)
def title_runtimes(kind, title_id, get_json, cache_period):
    """Return seconds keyed by (season, episode), or (None, None) for a movie."""
    imdb = title_id.startswith('tt')
    identity = title_id if imdb else int(title_id.removeprefix('tmdb:'))
    try:
        if kind == 'movie':
            if imdb:
                seconds = movie_seconds(title_id, get_json)
                if seconds:
                    return {(None, None): seconds}
            movie = catalog_record(kind, title_id, get_json, cache_period)
            if movie:
                if not imdb:
                    seconds = movie_seconds(movie.get('ImdbId'), get_json)
                    if seconds:
                        return {(None, None): seconds}
                if positive_integer(movie.get('Runtime')):
                    return {(None, None): movie['Runtime'] * 60}
            return {}

        field = 'imdbId' if imdb else 'tmdbId'
        base = 'https://skyhook.sonarr.tv/v1/tvdb/'
        series = catalog_record(kind, title_id, get_json, cache_period)
        tvdb_id = series.get('tvdbId')
        if not positive_integer(tvdb_id):
            return {}
        show = get_json(base + 'shows/en/' + str(tvdb_id))
        if show.get(field) != identity or show.get('tvdbId') != tvdb_id:
            return {}
        runtimes = {}
        for row in show.get('episodes') or []:
            if not isinstance(row, dict):
                continue
            key = row.get('seasonNumber'), row.get('episodeNumber')
            if any(type(n) is not int or n < 0 for n in key):
                continue
            minutes = row.get('runtime')
            # Duplicate episode numbers have ambiguous runtime.
            runtimes[key] = minutes * 60 if key not in runtimes and positive_integer(minutes) else None
        return runtimes
    except (Rejected, AttributeError, TypeError):
        return {}

@lru_cache(maxsize=128)
def provider_imdb(kind, tmdb_id, get_json, cache_period):
    record = catalog_record(kind, 'tmdb:' + str(tmdb_id), get_json, cache_period)
    imdb = record.get('ImdbId' if kind == 'movie' else 'imdbId')
    if isinstance(imdb, str) and re.fullmatch(r'tt\d+', imdb):
        return imdb
    return None


def quality_order(stream):
    hints = stream['behaviorHints']
    resolution = stream_resolution(hints['filename']) or stream_resolution(stream['name'])
    detail = ' '.join([stream['name'], hints['filename'], *[line for line in stream['description'].splitlines()
                      if line.startswith(('🎥','📹','⭐','🎨'))]]).casefold().replace('_', ' ')
    hdr = bool(visual_format(detail))
    if resolution == '2160p':
        tier = 0 if hdr else 1
    elif resolution in ('1080i', '1080p'):
        tier = 2 if hdr else 3
    else:
        tier = 4
    return (tier, -hints.get('videoSize', 0))


def card_description(source_text, size, seconds):
    # Prism mixes display-only estimates, individual bytes and pack totals.
    text = re.sub(r'⚠️[ \t]*P2P[ \t]*', '', source_text)
    text = re.sub(r'⏱️?[ \t]*(?:\d+[ \t]*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|[hms])[: \t]*)+', '', text, flags=re.I)
    text = re.sub(r'📊[ \t]*[\d.]+[ \t]*[KMG]?bps[ \t]*', '', text, flags=re.I)
    unit = r'[\d.,]+[ \t]*(?:[KMGT]i?)?B'
    text = re.sub(r'(?:📦|💾)[ \t]*' + unit, '', text, flags=re.I)
    text = re.sub(r'/[ \t]*' + unit, '', text, flags=re.I)
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if positive_integer(size):
        label = f'📦 {size / 1_000_000_000:.2f} GB'
        if seconds:
            label += f'  ⏱️ {seconds // 60} min  📊 {size * 8 / seconds / 1_000_000:.1f} Mbit/s'
        lines.append(label)
    return '\n'.join(lines)

def matching_files(files, wanted):
    matches = []
    for file in files:
        if not isinstance(file, dict):
            continue
        names = [str(file.get(k) or '') for k in ('name','short_name','absolute_path')]
        if not any(playable(n) for n in names) or any(SAMPLE.search(n) for n in names):
            continue
        if not any(base_name(n) == base_name(wanted['f']) for n in names):
            continue
        if wanted.get('s') is not None and not any(episode_matches(n,wanted['s'],wanted['e']) for n in names):
            continue
        matches.append(file)
    return matches

def choose_file(files, wanted):
    matches = [file for file in matching_files(files, wanted) if type(file.get('id')) is int]
    if len(matches) != 1:
        raise Rejected(409, 'The requested video file is unavailable or ambiguous')
    return matches[0]

class Guard:
    def __init__(self, upstream, base, access, signing, torbox, requester=request, max_mbps=80, metadata_get=public_metadata,
                 provider_urls=None, provider_reader=None, track_reader=None):
        self.upstream = upstream.rstrip('/')
        self.base = base.rstrip('/')
        self.access = access
        self.signing = signing.encode()
        self.torbox = torbox
        self.request = requester
        self.feeds = ProviderFeeds(provider_urls or {}, provider_reader or (lambda url: request('GET', url, timeout=12)))
        self.max_mbps = max_mbps
        self.metadata_get = metadata_get
        self.tracks = {}
        self.track_lock = threading.Lock()
        opener = urllib.request.build_opener(NoRedirect())
        self.track_reader = track_reader or (lambda link, size, filename: probe_audio(link, size, filename, opener))
        self.resolved = {}
        self.resolve_lock = threading.Lock()

    def tb(self, method, path, data=None, multipart=False):
        headers = {'Authorization':'Bearer '+self.torbox}
        body = None
        if data is not None and multipart:
            boundary = 'guard-' + uuid.uuid4().hex
            body = ''.join('--'+boundary+'\r\nContent-Disposition: form-data; name="'+k+'"\r\n\r\n'+v+'\r\n' for k,v in data.items()).encode()
            body += ('--'+boundary+'--\r\n').encode()
            headers['Content-Type'] = 'multipart/form-data; boundary='+boundary
        elif data is not None:
            body = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        value = self.request(method, TB+path, headers, body)
        if not isinstance(value,dict) or value.get('success') is not True:
            raise Rejected(503, 'TorBox could not verify this request')
        return value.get('data')

    def sign(self, value):
        payload = base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
        return payload+'.'+hmac.new(self.signing,payload.encode(),hashlib.sha256).hexdigest()

    def verify(self, token):
        try:
            if len(token)>4096:raise ValueError()
            payload, signature = token.rsplit('.',1)
            expected=hmac.new(self.signing,payload.encode(),hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature,expected):raise ValueError()
            value=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))
            if value['t'] < time.time():raise Rejected(410,'Refresh the source list')
            if 'o' not in value:
                raise Rejected(410, 'Refresh the source list for audio verification')
            if not HASH.fullmatch(value['h']) or not playable(value['f']):raise ValueError()
            return value
        except (ValueError,KeyError,TypeError):
            raise Rejected(403,'Invalid playback link') from None

    def streams(self, kind, item):
        if kind not in ('movie','series') or not re.fullmatch(r'(tt\d+|tmdb:\d+)(:\d+:\d+)?',item):
            raise Rejected(400,'Unsupported title identifier')
        season=episode=None
        if kind=='series':
            match=re.search(r':(\d+):(\d+)$',item)
            if not match:raise Rejected(400,'Select a specific episode')
            season,episode=map(int,match.groups())
        elif not re.fullmatch(r'tt\d+|tmdb:\d+', item):
            raise Rejected(400, 'Select a specific movie')
        path='/stream/'+kind+'/'+urllib.parse.quote(item,safe=':')+'.json'
        deadline=time.monotonic()+12
        provider_item=item
        if self.feeds.urls and item.startswith('tmdb:'):
            _, title_number, *episode_numbers=item.split(':')
            imdb=provider_imdb(kind,int(title_number),self.metadata_get,int(time.monotonic()//300))
            provider_item=':'.join([imdb,*episode_numbers]) if imdb else None
        pending=self.feeds.start('/stream/'+kind+'/'+provider_item+'.json') if provider_item else {}
        try:
            raw=self.request('GET',self.upstream+path).get('streams', [])
        except Rejected:
            if not pending:raise
            raw=[]
        providers=self.feeds.collect(pending, deadline)
        streams = []
        for row in raw:
            if isinstance(row, dict) and isinstance(row.get('behaviorHints'), dict):
                streams.append(dict(row, source='Torrentio',
                                    name=row.get('name') if isinstance(row.get('name'), str) else '',
                                    description=row.get('description') if isinstance(row.get('description'), str) else ''))
        streams.extend(providers.get('Comet', []))
        catalog_movie = None
        if self.feeds.urls and kind == 'movie' and provider_item:
            movie = movie_metadata(provider_item,self.metadata_get,int(time.monotonic()//300))
            movie_year = str(movie.get('year') or movie.get('releaseInfo',''))
            if isinstance(movie.get('name'),str) and re.fullmatch(r'(?:19|20)\d{2}',movie_year):
                catalog_movie=(movie['name'],movie_year)
        candidates=[]
        for stream in streams:
            filename=stream.get('behaviorHints',{}).get('filename')
            hash_=stream.get('infoHash','')
            if stream.get('url') or not isinstance(hash_,str) or not HASH.fullmatch(hash_):continue
            if not isinstance(filename,str) or len(filename)>1024 or not playable(filename):continue
            if season is not None and not episode_matches(filename,season,episode):continue
            if kind=='movie' and conflicting_movie_year(filename,catalog_movie):continue
            candidates.append((stream,hash_.lower(),filename))
        hashes=list(dict.fromkeys(h for _,h,_ in candidates))
        cached={}
        for offset in range(0,len(hashes),25):
            batch = {'hashes': hashes[offset:offset+25]}
            try:
                result=self.tb('POST','torrents/checkcached?format=object&list_files=true',batch)
            except Rejected as error:
                # Large packs can exceed the response limit with a file listing.
                if error.status != 502:
                    raise
                result=self.tb('POST','torrents/checkcached?format=object',batch)
            if not isinstance(result,dict):raise Rejected(503,'TorBox cache state is unknown')
            for key, value in result.items():
                if (isinstance(key,str) and HASH.fullmatch(key) and isinstance(value,dict)
                        and str(value.get('hash','')).lower()==key.lower() and positive_integer(value.get('size'))):
                    cached[key.lower()]=value
        title_id = item if kind == 'movie' else item.rsplit(':', 2)[0]
        catalog = catalog_record(kind, title_id, self.metadata_get, int(time.monotonic() // 300))
        original = language(catalog.get('OriginalLanguage' if kind == 'movie' else 'originalLanguage'))
        allowed = {'en', original} - {None}
        seconds = None
        if any(entry.get('files') for entry in cached.values()):
            runtimes = title_runtimes(kind, title_id, self.metadata_get, int(time.monotonic() // 300))
            seconds = runtimes.get((season, episode))
        def requested_video(name):
            return playable(name) and not conflicting_movie_year(base_name(name),catalog_movie) and (season is None or episode_matches(name,season,episode))
        candidates.extend(mediafusion_candidates(providers.get('MediaFusion', []), cached, base_name, requested_video))
        now = time.monotonic()
        with self.track_lock:
            known = {key: languages for key, (expires, languages) in self.tracks.items() if expires > now}
        # One release may be returned by several providers. Only share evidence
        # across entries for an unambiguous, independently matched TorBox file.
        eligible_candidates = []
        claims = {}
        for stream, hash_, filename in candidates:
            if hash_ not in cached:
                continue
            key = (hash_, base_name(filename))
            wanted = {'f': filename, 's': season, 'e': episode}
            catalog_files = cached[hash_].get('files') or []
            files = matching_files(catalog_files, wanted)
            if stream['source'] != 'Torrentio' and catalog_files and len(files) != 1:
                continue
            own = provider_audio(stream['source'], stream.get('description',''))
            if own and len(files) == 1:
                claims.setdefault(key, []).append(own)
            eligible_candidates.append((stream, hash_, filename, key, files, own))
        output = []
        for stream, hash_, filename, key, files, own in eligible_candidates:
            if key in known:
                if not allowed.intersection(known[key] or ()):
                    continue
            else:
                reports = claims.get(key, []) if len(files) == 1 else [own]
                if not reports or not all(allowed.intersection(report) for report in reports):
                    continue
            size = files[0].get('size') if len(files) == 1 else None
            runtime = None if season is not None and multiple_episodes(filename) else seconds
            if runtime and positive_integer(size) and size * 8 > self.max_mbps * 1_000_000 * runtime:
                continue
            token=self.sign({'h':hash_,'f':filename,'s':season,'e':episode,'p':stream['source'],'o':original,'t':int(time.time())+21600})
            description = card_description(stream.get('description',''), size, runtime)
            source_id = hashlib.sha256(('\0'.join((stream['source'], *key))).encode()).hexdigest()
            hints = {'filename': filename, 'nuvioSourceId': source_id}
            if positive_integer(size):
                hints['videoSize'] = size
            if season is not None:
                group=binge_group(stream['source'],stream,filename)
                if not group and len(files)==1:
                    pack=verified_pack_group(cached[hash_].get('files') or [],season,filename,base_name,playable)
                    group=binge_group(stream['source'],stream,filename,pack)
                if group:
                    hints['bingeGroup']=group
            output.append({'name':stream['source']+' · '+stream.get('name','')+' · TorBox ✓','description':description,
                'url':self.base+'/'+self.access+'/play/'+token,
                'behaviorHints':hints})
        output.sort(key=quality_order)
        return {'streams':output}

    def resolve(self, token):
        wanted=self.verify(token)
        with self.resolve_lock:
            now=time.monotonic()
            self.resolved={k:v for k,v in self.resolved.items() if v[0]>now}
            if token in self.resolved:return self.resolved[token][1]
            key = (wanted['h'], base_name(wanted['f']))
            allowed = {'en', wanted['o']} - {None}
            with self.track_lock:
                expires, tracks = self.tracks.get(key, (0, None))
            if expires <= now:
                tracks = None
            elif tracks is None:
                raise Rejected(503, 'Could not verify the selected file audio tracks')
            if tracks is not None and not allowed.intersection(tracks):
                raise Rejected(409, 'This file has no identified original or English audio track')
            created=self.tb('POST','torrents/createtorrent',{
                'magnet':'magnet:?xt=urn:btih:'+wanted['h'],
                'add_only_if_cached':'true','allow_zip':'false'},multipart=True)
            torrent_id=(created.get('torrent_id') or created.get('id')) if isinstance(created,dict) else None
            if not isinstance(torrent_id,int) or isinstance(torrent_id,bool):raise Rejected(503,'TorBox did not return the cached item')
            torrent=self.tb('GET','torrents/mylist?'+urllib.parse.urlencode({'id':torrent_id,'bypass_cache':'true'}))
            if isinstance(torrent,list):
                torrent=next((x for x in torrent if x.get('id')==torrent_id),None)
            if not isinstance(torrent,dict) or str(torrent.get('hash','')).lower()!=wanted['h']:
                raise Rejected(409,'Cached item identity changed')
            if torrent.get('download_finished') is not True or torrent.get('download_present') is not True:
                raise Rejected(409,'The video is no longer cached')
            file=choose_file(torrent.get('files') or [],wanted)
            link=self.tb('GET','torrents/requestdl?'+urllib.parse.urlencode({
                'token':self.torbox,'torrent_id':torrent_id,'file_id':file['id'],
                'zip_link':'false','redirect':'false','append_name':'false'}))
            parsed=urllib.parse.urlsplit(link) if isinstance(link,str) else None
            host=parsed.hostname if parsed else ''
            if (not parsed or parsed.scheme!='https' or not host or parsed.username is not None or parsed.port not in (None,443)
                    or not any(host==d or host.endswith('.'+d) for d in DELIVERY_DOMAINS)):
                raise Rejected(502,'Unexpected TorBox delivery address')
            if tracks is None:
                try:
                    tracks = self.track_reader(link, file.get('size'), wanted['f'])
                except ProbeUnavailable:
                    tracks = None
                with self.track_lock:
                    if len(self.tracks) >= 512:
                        self.tracks.pop(next(iter(self.tracks)))
                    self.tracks[key] = (time.monotonic() + (180 if tracks is None else 21600), tracks)
                if tracks is None:
                    raise Rejected(503, 'Could not verify the selected file audio tracks')
            if not allowed.intersection(tracks):
                raise Rejected(409, 'This file has no identified original or English audio track')
            if len(self.resolved)>=128:self.resolved.pop(next(iter(self.resolved)))
            self.resolved[token]=(now+30,link)
            return link

def serve(guard, host, port):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass  # URL paths contain bearer capabilities.
        def do_HEAD(self):self.handle_request(head=True)
        def do_GET(self):self.handle_request(head=False)
        def handle_request(self,head):
            try:
                path=urllib.parse.urlsplit(self.path).path
                if path=='/health':return self.send_json({'ok':True},head=head)
                prefix='/'+guard.access+'/'
                if not path.startswith(prefix):raise Rejected(404,'Not found')
                path=path[len(prefix):]
                if path=='manifest.json':
                    return self.send_json({'id':'local.mkb.torboxhome','version':'1.1.0','name':'TorBox Home',
                        'description':'Torrentio, Comet and MediaFusion with verified TorBox cache.',
                        'types':['movie','series'],'catalogs':[],
                        'resources':[{'name':'stream','types':['movie','series'],'idPrefixes':['tt','tmdb:']}]},head=head)
                if path.startswith('play/'):
                    link=guard.resolve(path[5:])
                    self.send_response(302);self.send_header('Location',link)
                    self.send_header('Cache-Control','no-store');self.send_header('Content-Length','0');self.end_headers();return
                match=re.fullmatch(r'stream/(movie|series)/(.+)\.json',path)
                if match:return self.send_json(guard.streams(match[1],urllib.parse.unquote(match[2])),head=head)
                raise Rejected(404,'Not found')
            except Rejected as error:self.send_json({'error':error.reason},error.status,head=head)
            except Exception:self.send_json({'error':'Playback unavailable'},503,head=head)
        def send_json(self,value,status=200,head=False):
            body=json.dumps(value,ensure_ascii=False).encode()
            self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(body)))
            self.end_headers()
            if not head:self.wfile.write(body)
    with ThreadingHTTPServer((host, port), Handler) as server:
        server.serve_forever()


def main():
    required = ('AIO_UPSTREAM', 'GUARD_BASE_URL', 'GUARD_ACCESS_KEY', 'GUARD_SIGNING_KEY', 'TORBOX_API_KEY')
    missing = [name for name in required if not os.environ.get(name, '').strip()]
    if missing:
        raise SystemExit('Set these environment variables: ' + ', '.join(missing))
    providers = {
        source: os.environ[name] for source, name in
        (('Comet', 'COMET_UPSTREAM'), ('MediaFusion', 'MEDIAFUSION_UPSTREAM'))
        if os.environ.get(name)
    }
    guard = Guard(
        *(os.environ[name] for name in required),
        max_mbps=int(os.environ.get('GUARD_MAX_MBIT', '80')),
        provider_urls=providers,
    )
    serve(guard, os.environ.get('GUARD_LISTEN_HOST', '127.0.0.1'), int(os.environ.get('GUARD_PORT', '4301')))


if __name__ == '__main__':
    main()
