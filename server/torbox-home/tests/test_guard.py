import json
import time
import unittest
import threading
from unittest.mock import patch
import io
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
import guard

HASH='a'*40
NAME='Show.S01E01.2160p.REMUX.mkv'

class Fake:
    def __init__(self):
        self.calls=[]
        self.raw=[{'infoHash':HASH,'name':'4K','description':'🗣️ 🇬🇧\n⚠️ P2P test','behaviorHints':{'filename':NAME,'videoSize':1000}}]
        self.cache={HASH:{'hash':HASH,'size':1000}}
        self.failure=None
        self.torrent={'id':7,'hash':HASH,'download_finished':True,'download_present':True,'files':[{'id':2,'name':'folder/'+NAME,'size':1000}]}
        self.link='https://nord.tb-cdn.st/video'
    def __call__(self,method,url,headers=None,body=None):
        self.calls.append((method,url,body))
        if '/stream/' in url:return {'streams':self.raw}
        if self.failure:raise self.failure
        if 'checkcached' in url:return {'success':True,'data':self.cache}
        if 'createtorrent' in url:return {'success':True,'data':{'torrent_id':7}}
        if 'mylist' in url:return {'success':True,'data':self.torrent}
        if 'requestdl' in url:return {'success':True,'data':self.link}
        raise AssertionError('Unexpected request')

class Tests(unittest.TestCase):
    def setUp(self):
        self.fake=Fake()
        self.g=guard.Guard('http://aio/private','http://guard','access','signing','fake-key',self.fake, metadata_get=lambda _: {}, track_reader=lambda *_: {'en'})
    def token(self):return self.g.sign({'h':HASH,'f':NAME,'s':1,'e':1,'o':'en','t':int(time.time())+60})
    def test_cached_output_is_http_only_and_does_not_create(self):
        rows=self.g.streams('series','tt123:1:1')['streams']
        self.assertEqual(len(rows),1)
        self.assertEqual(set(rows[0]),{'name','description','url','behaviorHints'})
        self.assertNotIn('P2P',rows[0]['description'])
        self.assertFalse(any('createtorrent' in x[1] for x in self.fake.calls))
    def test_uncached_empty(self):
        self.fake.cache={}
        self.assertEqual(self.g.streams('series','tt123:1:1'),{'streams':[]})
    def test_unknown_cache_entries_rejected(self):
        for value in [None,False,{}, {'hash':HASH}, {'hash':'b'*40,'size':1000}]:
            self.fake.cache={HASH:value}
            self.assertEqual(self.g.streams('series','tt123:1:1'),{'streams':[]})
    def test_cache_api_error_fails_closed(self):
        self.fake.failure=guard.Rejected(503,'unavailable')
        with self.assertRaises(guard.Rejected):self.g.streams('series','tt123:1:1')
    def test_upstream_http_never_returned_or_followed(self):
        self.fake.raw[0]['url']='https://untrusted.invalid/resolve'
        self.assertEqual(self.g.streams('series','tt123:1:1'),{'streams':[]})
        self.assertEqual(len(self.fake.calls),1)
    def test_wrong_episode_and_sample_not_offered(self):
        for filename in ['Show.S01E02.mkv','Show.S01E01.sample.mkv','unknown.mkv']:
            self.fake.raw[0]['behaviorHints']['filename']=filename
            self.assertEqual(self.g.streams('series','tt123:1:1'),{'streams':[]})
    def test_resolve_cache_only_exact_file_once(self):
        token=self.token()
        self.assertEqual(self.g.resolve(token),self.fake.link)
        self.assertEqual(self.g.resolve(token),self.fake.link)
        creates=[x for x in self.fake.calls if 'createtorrent' in x[1]]
        self.assertEqual(len(creates),1)
        self.assertIn(b'name="add_only_if_cached"\r\n\r\ntrue',creates[0][2])
        self.assertIn(b'name="allow_zip"\r\n\r\nfalse',creates[0][2])
        self.assertIn('file_id=2',self.fake.calls[-1][1])
    def test_cache_disappearance_does_not_request_download_link(self):
        self.fake.failure=guard.Rejected(409,'not cached')
        with self.assertRaises(guard.Rejected) as caught:self.g.resolve(self.token())
        self.assertEqual(caught.exception.status,409)
        self.assertEqual(len(self.fake.calls),1)
    def test_incomplete_or_missing_download_fails(self):
        for field in ['download_finished','download_present']:
            saved=self.fake.torrent.pop(field)
            with self.assertRaises(guard.Rejected):self.g.resolve(self.token())
            self.fake.torrent[field]=saved
        self.assertFalse(any('requestdl' in x[1] for x in self.fake.calls))
    def test_hash_mismatch_fails(self):
        self.fake.torrent['hash']='b'*40
        with self.assertRaises(guard.Rejected):self.g.resolve(self.token())
    def test_pack_requires_unique_exact_filename(self):
        good=self.fake.torrent['files'][0]
        for files in [[],[dict(good,name='Show.S01E02.mkv')],[good,dict(good,id=3)],[dict(good,name='sample.mkv')]]:
            with self.assertRaises(guard.Rejected):guard.choose_file(files,{'f':NAME,'s':1,'e':1})
    def test_invalid_and_expired_tokens_do_not_touch_torbox(self):
        for token in ['invalid',self.token()+'a',self.g.sign({'h':HASH,'f':NAME,'t':0})]:
            with self.assertRaises(guard.Rejected):self.g.resolve(token)
        self.assertEqual(self.fake.calls,[])
    def test_documented_torbox_cdn_zones_and_exact_host_boundary(self):
        self.fake.link='https://store-001.wnam.tb-cdn.io/media'
        self.assertEqual(self.g.resolve(self.token()),self.fake.link)
        self.g.resolved.clear()
        for invalid in ['https://tb-cdn.io.other.invalid/media','https://user@store.tb-cdn.io/media',
                        'http://store.tb-cdn.io/media','https://store.tb-cdn.io:8443/media']:
            self.fake.link=invalid
            with self.assertRaises(guard.Rejected):
                self.g.resolve(self.token())

    def test_non_torbox_delivery_rejected(self):
        self.fake.link='https://evil.invalid/video'
        with self.assertRaises(guard.Rejected):self.g.resolve(self.token())
    def test_upstream_redirect_is_not_followed_with_credentials(self):
        observed=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                observed.append(self.path)
                self.send_response(302)
                self.send_header('Location','/unexpected-target')
                self.end_headers()
        server=HTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with self.assertRaises(guard.Rejected):
                guard.request('GET','http://127.0.0.1:'+str(server.server_port)+'/start',{'Authorization':'Bearer synthetic'})
            self.assertEqual(observed,['/start'])
        finally:server.shutdown();server.server_close();thread.join()
    def test_torbox_404_not_cached_is_a_safe_conflict(self):
        error=urllib.error.HTTPError(guard.TB+'torrents/createtorrent',404,'not found',{},io.BytesIO(b'{"success":false,"error":"DOWNLOAD_NOT_CACHED"}'))
        with patch('urllib.request.OpenerDirector.open',side_effect=error):
            with self.assertRaises(guard.Rejected) as caught:guard.request('POST',guard.TB+'torrents/createtorrent')
        self.assertEqual(caught.exception.status,409)
    def set_files(self, *sizes, filename=NAME):
        self.fake.cache[HASH]['files'] = [{'name': filename, 'size': n} for n in sizes]

    def test_movie_boundary_uses_decimal_bits_and_individual_cached_file(self):
        filename = 'Movie.2160p.REMUX.mkv'
        self.fake.raw[0].update(description='🗣️ 🇬🇧\n🎥 BluRay REMUX ⏱️ 2h:0m:0s\n📦 110 GB / 200 GB 📊 99 Mbps')
        self.fake.raw[0]['behaviorHints'] = {'filename': filename, 'videoSize': 100_000_000_000}
        self.fake.cache[HASH]['size'] = 200_000_000_000
        self.g.metadata_get = lambda url: [{'ImdbId': 'tt123', 'Runtime': 100}]
        at_limit = 60_000_000_000
        self.set_files(at_limit, filename=filename)
        self.fake.cache[HASH]['files'].append({'name': 'extras.mkv', 'size': 45_000_000_000})
        row, = self.g.streams('movie', 'tt123')['streams']
        self.assertEqual(row['behaviorHints']['videoSize'], at_limit)
        self.assertIn('80.0 Mbit/s', row['description'])
        self.assertNotIn('99 Mbps', row['description'])
        self.assertNotIn('200 GB', row['description'])
        self.assertNotIn('2h:', row['description'])
        self.assertIn('60.00 GB', row['description'])
        self.assertIn('100 min', row['description'])
        self.set_files(at_limit + 1, filename=filename)
        self.assertEqual(self.g.streams('movie', 'tt123')['streams'], [])
        self.assertTrue(any('list_files=true' in url for _,url,_ in self.fake.calls))

    def test_series_runtime_uses_exact_show_season_and_episode(self):
        def metadata(url):
            show = {'tmdbId': 19, 'tvdbId': 90, 'runtime': 120}
            if '/search/' in url:
                self.assertIn('tmdb%3A19', url)
                return [{'tmdbId': 20, 'tvdbId': 81}, show]
            self.assertTrue(url.endswith('/90'))
            return dict(show, episodes=[
                {'seasonNumber': 1, 'episodeNumber': 1, 'runtime': 40},
                {'seasonNumber': 1, 'episodeNumber': 2, 'runtime': 60},
                {'seasonNumber': 2, 'episodeNumber': 1, 'runtime': 60}])
        self.g.metadata_get = metadata
        self.set_files(30_000_000_000)
        self.assertEqual(self.g.streams('series', 'tmdb:19:1:1')['streams'], [])
        name = NAME.replace('E01', 'E02')
        self.set_files(30_000_000_000, filename=name)
        self.fake.raw[0]['behaviorHints']['filename'] = name
        row, = self.g.streams('series', 'tmdb:19:1:2')['streams']
        self.assertIn('66.7 Mbit/s', row['description'])

    def test_missing_unmatched_or_ambiguous_episode_keeps_candidate(self):
        self.set_files(60_000_000_000)
        self.fake.raw[0]['description'] = '🗣️ 🇬🇧\n📊 190 Mbps ⏱️ 60s'
        show = {'imdbId':'tt123', 'tvdbId': 9, 'runtime': 50}
        row = {'seasonNumber': 1, 'episodeNumber': 1, 'runtime': 45}
        for episodes in ([], [dict(row, seasonNumber=2)], [dict(row, runtime=0)], [row, row]):
            self.g.metadata_get = lambda url: [show] if '/search/' in url else dict(show, episodes=episodes)
            stream, = self.g.streams('series', 'tt123:1:1')['streams']
            self.assertNotIn('📊', stream['description'])

    def test_unknown_size_or_lookup_outage_keeps_candidate(self):
        self.g.metadata_get = lambda url: [{'ImdbId': 'tt123', 'Runtime': 10}]
        self.fake.raw[0]['behaviorHints']['videoSize'] = 90_000_000_000
        self.fake.raw[0]['description'] = '🗣️ 🇬🇧\n📊 900 Mbps'
        for sizes in ([], [None], [0], [True], [90_000_000_000, 90_000_000_000]):
            self.set_files(*sizes)
            stream, = self.g.streams('movie', 'tt123')['streams']
            self.assertNotIn('videoSize', stream['behaviorHints'])
            self.assertNotIn('📊', stream['description'])
        self.set_files(90_000_000_000, filename='Another.mkv')
        stream, = self.g.streams('movie', 'tt123')['streams']
        self.assertNotIn('videoSize', stream['behaviorHints'])
        def unavailable(url):
            raise guard.Rejected(503, 'Unavailable')
        self.set_files(90_000_000_000)
        self.g.metadata_get = unavailable
        self.assertEqual(len(self.g.streams('movie','tt123')['streams']), 1)

    def test_multi_episode_file_does_not_use_one_episode_runtime(self):
        show = {'imdbId':'tt123', 'tvdbId': 9}
        episodes = [{'seasonNumber':1, 'episodeNumber':1, 'runtime':20}]
        self.g.metadata_get = lambda url: [show] if '/search/' in url else dict(show, episodes=episodes)
        for marker in ('S01E01E02', 'S01E01-02', 'S01E01.S01E02', '1x01-02'):
            name = 'Show.' + marker + '.mkv'
            self.set_files(90_000_000_000, filename=name)
            self.fake.raw[0]['behaviorHints']['filename'] = name
            row, = self.g.streams('series', 'tt123:1:1')['streams']
            self.assertNotIn('📊', row['description'])

    def test_merged_rows_rank_quality_without_a_soft_ceiling(self):
        self.g.metadata_get = lambda url: [{'ImdbId': 'tt123', 'Runtime': 100}]
        names = ['SD.DTS-HD', '720p', '1080p.REMUX.DV', '1440p', '2160p.WEB-DL.DV', '2160p.REMUX', '2160p.REMUX.HDR10', '2160p.REMUX.DV.HDR10']
        sizes = [None, None, 10_000_000_000, None, 20_000_000_000, None, 40_000_000_000, 58_000_000_000]
        self.fake.raw = []
        files = []
        for name, size in zip(names, sizes):
            filename = 'Movie.' + name + '.mkv'
            self.fake.raw.append({'infoHash':HASH, 'name':'', 'description':'🗣️ 🇬🇧', 'behaviorHints':{'filename':filename}})
            files.append({'name':filename,'size':size})
        self.fake.cache[HASH]['files'] = files
        rows = self.g.streams('movie','tt123')['streams']
        expected = [7, 6, 4, 5, 2, 0, 1, 3]
        self.assertEqual([r['behaviorHints']['filename'] for r in rows], [files[i]['name'] for i in expected])

    def test_movie_lookup_uses_same_imdb_runtime_for_either_identifier(self):
        def metadata(url):
            if '/movie/19' in url:
                return {'TmdbId': 19, 'ImdbId': 'tt123', 'Runtime': 8}
            self.assertTrue(url.endswith('tt123.json'))
            return {'meta':{'id':'tt123', 'type':'movie', 'runtime':'10 min'}}
        for title in ('tt123','tmdb:19'):
            self.assertEqual(guard.title_runtimes('movie', title, metadata, 0), {(None,None):600})

if __name__=='__main__':unittest.main()
