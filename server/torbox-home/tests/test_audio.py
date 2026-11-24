import io
import time
import unittest
from unittest.mock import patch

import audio
import guard
from test_guard import Fake, NAME, HASH


def element(key, payload):
    if len(payload) >= 127:
        raise ValueError('Fixture too long')
    return key.to_bytes((key.bit_length() + 7) // 8, 'big') + bytes([0x80 + len(payload)]) + payload


def track(kind, code=None, *, commentary=False):
    entry = element(0x83, bytes([kind]))
    if code is not None:
        entry += element(0x22B59C, code.encode())
    if commentary:
        entry += element(0x55AF, b'\x01')
    return element(0xAE, entry)


def mkv(*tracks):
    return element(0x1A45DFA3, b'') + element(0x18538067, element(0x1654AE6B, b''.join(tracks)))


class AudioTests(unittest.TestCase):
    def test_distinct_provider_fields_and_no_subtitle_leakage(self):
        cases = [('Torrentio', '🎧 DD 🔊 5.1 🗣️ 🇵🇱📝 🇬🇧', {'pl'}),
                 ('Torrentio', '📝 🇬🇧\n📄 The.English.Movie.ENG.mkv', set()),
                 ('Torrentio', '🗣️ 🇬🇧 / 🇩🇪', {'en','de'}),
                 ('Torrentio', '🗣️ 🇮🇳 / 🌎', set()),
                 ('Comet', '📄 Film.English.mkv\n⭐ BluRay\n🌎/🇵🇱', {'pl'}),
                 ('Comet', '📄 Film 🇬🇧\n📝 🇬🇧', set()),
                 ('MediaFusion', '🌐 Polish | Korean\n📝 English', {'pl','ko'}),
                 ('MediaFusion', '📂 English | French\n🌐 Multi | Unknown', set())]
        for provider, description, expected in cases:
            with self.subTest(provider=provider, description=description):
                self.assertEqual(audio.provider_audio(provider, description), expected)

    def test_iso_codes_and_no_unspecified_or_nonlinguistic_track(self):
        self.assertEqual([audio.language(x) for x in ['en-GB', 'ENG', 'pol','Korean','ger','deu','de']], ['en','en','pl','ko','de','de','de'])
        self.assertEqual([audio.language(x) for x in ('Multi','und','zxx','mis',None,'Unknown')], [None]*6)

    def test_matroska_only_explicit_main_audio_and_ietf_precedence(self):
        data = mkv(track(1,'eng'),track(17,'eng'),track(2), track(2,'eng',commentary=True), track(2,'pol'))
        self.assertEqual(audio.matroska_audio(data,len(data)), {'pl'})
        entry = element(0x83,b'\x02') + element(0x22B59C,b'eng') + element(0x22B59D,b'de-DE')
        data = mkv(element(0xAE,entry))
        self.assertEqual(audio.matroska_audio(data,len(data)), {'de'})
        with self.assertRaises(audio.ProbeUnavailable):
            audio.matroska_audio(data[:-1],len(data))

    def test_bounded_range_requires_exact_response_and_known_total(self):
        class Response(io.BytesIO):
            status=206
            headers={'Content-Range':'bytes 0-2/10'}
        class Opener:
            def open(self, req, timeout):
                self.request=req
                return Response(b'abc')
        opener=Opener()
        self.assertEqual(audio.range_bytes(opener,'https://delivery.invalid',0,2,10,time.monotonic()+1),b'abc')
        self.assertEqual(opener.request.get_header('Range'),'bytes=0-2')
        for total,status in [(11,206),(10,200)]:
            Response.status=status
            with self.assertRaises(audio.ProbeUnavailable):
                audio.range_bytes(opener,'https://delivery.invalid',0,2,total,time.monotonic()+1)

    def test_mp4_tail_is_only_read_on_undetermined_head(self):
        size=10_000_000
        def read_range(_opener,_link,start,end,*_):
            return bytes(end - start + 1)
        for results, expected_requests in [([{'en'}],1), ([None,{'de'}],2), ([frozenset()],1)]:
            with patch('audio.range_bytes',side_effect=read_range) as read, patch('audio.inspected_tracks',side_effect=results):
                self.assertEqual(audio.probe_audio('https://delivery.invalid',size,'x.mp4',None),results[-1])
                self.assertEqual(read.call_count,expected_requests)
                self.assertEqual(read.call_args_list[0].args[2:4], (0,audio.HEAD_BYTES-1))
                if expected_requests == 2:
                    self.assertEqual(read.call_args.args[2:4],(size-audio.TAIL_BYTES,size-1))

    def test_mp4_commentary_handler_does_not_identify_spoken_language(self):
        from subprocess import CompletedProcess
        result = CompletedProcess([], 0, b'{"streams":[{"tags":{"language":"eng","handler_name":"Director Commentary"}},'
                                         b'{"tags":{"language":"pol","handler_name":"SoundHandler"}}]}')
        with patch('audio.subprocess.run',return_value=result) as run:
            self.assertEqual(audio.inspected_tracks(3,'ffprobe',time.monotonic()+1), {'pl'})
            self.assertIn('stream=index:stream_tags=language,title,handler_name:stream_disposition=comment',run.call_args.args[0])

    def test_complete_file_without_audio_is_confirmed_empty(self):
        from subprocess import CompletedProcess
        with patch('audio.subprocess.run', return_value=CompletedProcess([], 0, b'{"streams":[]}')):
            self.assertEqual(audio.inspected_tracks(3,'ffprobe',time.monotonic()+1, complete=True), frozenset())
            self.assertIsNone(audio.inspected_tracks(3,'ffprobe',time.monotonic()+1, complete=False))

    def test_original_or_english_audio_and_catalog_outage(self):
        fake=Fake()
        for catalog,claim,expected in [('pol','🇵🇱',True),('eng','🇵🇱',False),('deu','🇩🇪',True),
                                       ('deu','🇬🇧',True),(None,'🇬🇧',True),(None,'🇵🇱',False),('eng','',False)]:
            meta=lambda _: [{'imdbId':'tt123','originalLanguage':catalog}]
            g=guard.Guard('http://aio','http://guard','access','sign','fake',fake,metadata_get=meta)
            fake.raw[0]['description']='🗣️ '+claim+'📝 🇬🇧'
            with self.subTest(catalog=catalog,claim=claim):
                self.assertEqual(bool(g.streams('series','tt123:1:1')['streams']),expected)
        self.assertFalse(any('createtorrent' in call[1] for call in fake.calls))

    def test_wrong_audio_fails_before_redirect_and_disappears_across_provider_claims(self):
        fake=Fake()
        g=guard.Guard('http://aio','http://guard','access','sign','fake',fake,
                      metadata_get=lambda _: [{'imdbId':'tt123','originalLanguage':'eng'}],
                      track_reader=lambda *_: frozenset({'pl'}))
        row, = g.streams('series','tt123:1:1')['streams']
        token=row['url'].rsplit('/',1)[1]
        with self.assertRaises(guard.Rejected) as e:g.resolve(token)
        self.assertEqual(e.exception.status,409)
        self.assertEqual(g.streams('series','tt123:1:1')['streams'],[])
        calls=len(fake.calls)
        with self.assertRaises(guard.Rejected) as e:g.resolve(token)
        self.assertEqual(e.exception.status,409)
        self.assertEqual(calls,len(fake.calls))

    def test_probe_outage_old_selection_and_identity(self):
        fake=Fake()
        def unavailable(*_):raise audio.ProbeUnavailable
        g=guard.Guard('http://aio','http://guard','access','sign','fake',fake, metadata_get=lambda _: {},track_reader=unavailable)
        row, = g.streams('series','tt123:1:1')['streams']
        other, = g.streams('series','tt123:1:1')['streams']
        key=row['behaviorHints']['nuvioSourceId']
        self.assertRegex(key,r'^[0-9a-f]{64}$')
        self.assertEqual(key,other['behaviorHints']['nuvioSourceId'])
        legacy=g.sign({'h':HASH,'f':NAME,'s':1,'e':1,'t':int(time.time())+60})
        calls=len(fake.calls)
        with self.assertRaises(guard.Rejected) as e:g.resolve(legacy)
        self.assertEqual(e.exception.status,410)
        self.assertEqual(len(fake.calls),calls)
        with self.assertRaises(guard.Rejected) as e:g.resolve(row['url'].rsplit('/',1)[1])
        self.assertEqual(e.exception.status,503)
        self.assertFalse(g.resolved)

    def test_malformed_row_does_not_suppress_valid_release(self):
        fake = Fake()
        fake.raw.extend([dict(fake.raw[0], infoHash='b'*40, description=None),
                         dict(fake.raw[0], behaviorHints=None)])
        g=guard.Guard('http://aio','http://guard','access','sign','fake',fake,metadata_get=lambda _: {})
        self.assertEqual(len(g.streams('series','tt123:1:1')['streams']), 1)

    def test_conflicting_provider_claims_and_missing_evidence(self):
        fake = Fake()
        fake.cache[HASH]['files'] = [{'name': NAME, 'size': 1000}]
        base = dict(fake.raw[0], description='📝 🇬🇧')
        fake.raw = [base, dict(base,description='🗣️ 🇬🇧')]
        g=guard.Guard('http://aio','http://guard','access','sign','fake',fake,metadata_get=lambda _: {})
        self.assertEqual(len(g.streams('series','tt123:1:1')['streams']), 2)
        fake.raw.append(dict(base,description='🗣️ 🇵🇱'))
        self.assertFalse(g.streams('series','tt123:1:1')['streams'])

    def test_visual_format_in_provider_display_name(self):
        from discovery import provider_row
        from test_discovery import hit
        for source in ('Comet','MediaFusion'):
            raw = hit(source)
            raw['name'] += ' Dolby Vision'
            stream = provider_row(source,raw)
            self.assertIn('DV', stream['name'])
            self.assertEqual(guard.quality_order(stream)[0],2)

    def test_size_inside_four_preferred_tiers_includes_unknown_last(self):
        def row(name, size):
            hints={'filename':'Movie.'+name+'.mkv'}
            if size: hints['videoSize']=size
            return {'name':'Comet','description':'','behaviorHints':hints}
        items=[('2160p.REMUX',100),('1080p.DV',300),('2160p.HDR10.WEBRip',None),
               ('1080p.REMUX',400),('2160p.DV.CAM',30),('2160p.HDR10.WEB-DL',40),('720p.HDR',900),('4320p.DV',100),('SD',None)]
        rows=sorted([row(*x) for x in items],key=guard.quality_order)
        self.assertEqual([r['behaviorHints']['videoSize'] if 'videoSize' in r['behaviorHints'] else None for r in rows],
                         [40,30,None,100,300,400,900,100,None])
