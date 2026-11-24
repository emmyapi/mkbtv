import unittest

from discovery import conflicting_movie_year, ProviderFeeds, binge_group, mediafusion_candidates, provider_row, stream_resolution, verified_pack_group
from guard import base_name, playable, provider_imdb

HASH = 'a'*40
FILE = 'Show.S01E01.1080p.WEB-DL.HEVC-GROUP.mkv'


def hit(source, file=FILE, size=100, group='GROUP'):
    is_comet = source == 'Comet'
    return {'name': '[TB⚡] Comet 1080p' if is_comet else 'MediaFusion | ElfHosted 🧲 TRB ⚡️ 1080p',
            'description': f'🎥 WEB-DL 🎞️ HEVC | 🏷️ {group}',
            'url': 'https://untrusted.invalid/never-follow',
            'behaviorHints': {'filename': file, 'videoSize': size,
                              'bingeGroup': 'comet|torbox|'+HASH if is_comet else 'MediaFusion-1080P'}}


class DiscoveryTests(unittest.TestCase):
    def test_movie_filename_that_identifies_a_different_installment_is_rejected(self):
        self.assertTrue(conflicting_movie_year('The.Matrix.Revolutions.2003.1080p.mkv',('The Matrix','1999')))
        self.assertFalse(conflicting_movie_year('The.Matrix.1999.1080p.mkv',('The Matrix','1999')))
        self.assertFalse(conflicting_movie_year('The.Matrix.2160p.mkv',('The Matrix','1999')))

        self.assertFalse(conflicting_movie_year('2012.1080p.mkv',('2012','2009')))
        self.assertFalse(conflicting_movie_year('The.Matrix.1920x1080.mkv',('The Matrix','1999')))
        self.assertFalse(conflicting_movie_year('The.Matrix.Remastered.2021.mkv',('The Matrix','1999')))

    def test_tmdb_lookup_checks_requested_identity_and_rejects_ambiguity(self):
        self.assertEqual(provider_imdb('movie',603,lambda _: {'TmdbId':603,'ImdbId':'tt0133093'},0),'tt0133093')
        self.assertIsNone(provider_imdb('movie',603,lambda _: {'TmdbId':601,'ImdbId':'tt0133093'},0))
        self.assertIsNone(provider_imdb('series',1396,lambda _: [{'tmdbId':1396,'imdbId':'tt12'},
                                                                {'tmdbId':1396,'imdbId':'tt34'}],0))

    def test_comet_requires_documented_torbox_identity_and_discards_url(self):
        raw = hit('Comet')
        self.assertEqual(provider_row('Comet', raw)['infoHash'], HASH)
        self.assertNotIn('url', provider_row('Comet', raw))
        for group in ['comet|realdebrid|'+HASH, 'comet|torbox|bad', 'other|torbox|'+HASH]:
            raw['behaviorHints']['bingeGroup'] = group
            self.assertIsNone(provider_row('Comet',raw))

    def test_quality_codec_and_missing_tags_do_not_remove_sources(self):
        raw = hit('Comet')
        for resolution, file in [('720p', FILE), ('2160p', 'Movie.2160p.AVC.mkv'),
                                 ('1080p','Movie.1080p.10Bit.AVC.mkv'),('1080p','Movie.1080p.AV1.mkv'),('Unknown','Movie.mkv')]:
            raw['name'] = '[TB⚡] Comet '+resolution
            raw['behaviorHints']['filename'] = file
            self.assertIsNotNone(provider_row('Comet', raw))

    def test_mediafusion_requires_exact_filename_bytes_and_unique_file(self):
        raw=provider_row('MediaFusion',hit('MediaFusion', size=1000))
        cache={HASH:{'size':1000,'files':[{'name':'folder/'+FILE,'size':100}]}}
        def bound():
            return [(h,f) for _,h,f in mediafusion_candidates([raw,raw],cache,base_name,playable)]
        self.assertEqual(bound(),[(HASH,FILE)])
        raw['behaviorHints']['videoSize']=100
        self.assertEqual(bound(),[(HASH,FILE)])
        raw['behaviorHints']['videoSize']=101
        self.assertEqual(bound(),[])
        raw['behaviorHints']['videoSize']=100
        cache[HASH]['files'].append({'name':'other/'+FILE,'size':100})
        self.assertEqual(bound(),[])
        cache[HASH]['files'].pop()
        cache['b'*40]=cache[HASH]
        self.assertEqual(bound(),[])

    def test_binge_group_follows_non_first_release_across_three_episode_hashes(self):
        groups=[]
        for episode in (1,2,3):
            streams=[]
            for release in ('FIRST','MANUAL'):
                file=f'Show.S01E{episode:02}.1080p.WEB-DL.HEVC-{release}.mkv'
                row=hit('Comet',file,group=release)
                row['behaviorHints']['bingeGroup']='comet|torbox|'+f'{episode:040x}'
                stream=provider_row('Comet',row)
                streams.append(binge_group('Comet',stream,file))
                self.assertNotEqual(streams[-1],binge_group('Torrentio',stream,file))
            groups.append(streams)
        chosen=groups[0][1]
        self.assertTrue(chosen)
        self.assertNotEqual(chosen,groups[0][0])
        self.assertEqual(groups[1].index(chosen),1)
        self.assertEqual(groups[2].index(chosen),1)
        self.assertIsNone(binge_group('Comet',{},'Show.S01E01.1080p.mkv'))

    def test_unknown_group_can_identify_an_exact_multiepisode_pack(self):
        files=[{'name':f'Show.S01E{n:02}.mkv','size':300+n} for n in (1,2,3)]
        fingerprints=[verified_pack_group(files,1,f['name'],base_name,playable) for f in files]
        self.assertTrue(fingerprints[0])
        self.assertEqual(len(set(fingerprints)),1)
        self.assertIsNone(verified_pack_group(files+[files[0]],1,files[0]['name'],base_name,playable))
        self.assertIsNone(verified_pack_group(files[:1],1,files[0]['name'],base_name,playable))

    def test_audio_hd_is_not_a_video_resolution(self):
        self.assertEqual(stream_resolution('Show.S01E02.DTS-HD.MA.1080p-GROUP.mkv'),'1080p')
        self.assertIsNone(stream_resolution('Show.S01E02.DTS-HD.MA-GROUP.mkv'))

    def test_standard_definition_and_underscore_delimited_formats_preserve_release(self):
        for tag,expected in [('720p','720p'),('480p','480p'),('SD','sd'),('4K','2160p')]:
            filename='Show_S01E01_'+tag+'_DV_HEVC-GROUP.mkv'
            group=binge_group('Comet',{'name':'Unknown'},filename)
            self.assertIn('|'+expected+'|',group)
            self.assertIn('|dv',group)

    def test_provider_requests_are_cached_origin_bound_and_return_metadata_only(self):
        urls=[]
        def reader(url):
            urls.append(url)
            return {'streams':[hit('Comet')]}
        feeds=ProviderFeeds({'Comet':'https://comet.elfhosted.com/configured'},reader)
        try:
            for _ in range(2):
                rows=feeds.start('/stream/movie/tt123.json')['Comet'].result(timeout=2)
                self.assertEqual(rows[0]['source'],'Comet')
                self.assertNotIn('url',rows[0])
            self.assertEqual(urls,['https://comet.elfhosted.com/configured/stream/movie/tt123.json'])
            with self.assertRaises(ValueError):
                ProviderFeeds({'Comet':'https://other.invalid/config'},reader)
        finally:
            feeds.pool.shutdown()
