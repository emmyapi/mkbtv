"""Provider language claims and bounded inspection of the selected container."""
from functools import lru_cache
import json
from http.client import HTTPException
import os
import re
import subprocess
import time
import urllib.request

# AIO Prism shares India's flag between multiple distinct languages. A flag is
# accepted only when the provider uses it for one unambiguous language.
FLAG_CODES = dict(zip(
    '🇬🇧 🇯🇵 🇨🇳 🇷🇺 🇸🇦 🇵🇹 🇧🇷 🇪🇸 🇫🇷 🇩🇪 🇮🇹 🇰🇷 🇧🇩 🇹🇭 🇻🇳 🇮🇩 🇹🇷 🇮🇱 🇮🇷 🇺🇦 🇬🇷 🇱🇹 🇱🇻 🇪🇪 🇵🇱 🇨🇿 🇸🇰 🇭🇺 🇷🇴 🇧🇬 🇷🇸 🇭🇷 🇸🇮 🇳🇱 🇩🇰 🇫🇮 🇸🇪 🇳🇴 🇲🇾'.split(),
    'en ja zh ru ar pt pt es fr de it ko bn th vi id tr he fa uk el lt lv et pl cs sk hu ro bg sr hr sl nl da fi sv no ms'.split()
))


@lru_cache(maxsize=1)
def language_names():
    # Debian's iso-codes data is also used by the host's ffprobe installation.
    names = {}
    for standard in ('639-2', '639-3'):
        with open('/usr/share/iso-codes/json/iso_' + standard + '.json', encoding='utf-8') as file:
            records = json.load(file)[standard]
        for record in records:
            code = record.get('alpha_2', record['alpha_3'])
            if code in {'und', 'mul', 'zxx', 'mis'}:
                continue
            for alias in [code, record['alpha_3'], record.get('bibliographic', ''), *record['name'].split(';')]:
                if alias:
                    names.setdefault(alias.strip().casefold(), code)
    return names


def language(value):
    if not isinstance(value, str):
        return None
    value = value.strip().casefold()
    if re.fullmatch(r'[a-z]{2,3}(?:-[a-z0-9]{2,8})+', value):
        value = value.split('-', 1)[0]
    return language_names().get(value)


def provider_audio(source, description):
    """Only parse a dedicated language field, never a filename or subtitle list."""
    for line in description.splitlines():
        if source == 'Torrentio':
            # Prism can print spoken languages and subtitles back to back.
            spoken = re.search(r'🗣️?\s*([^📝]*)', line.partition('📝')[0])
            if spoken and (line.startswith(('🎧', '🔊', '🗣'))):
                return {code for flag, code in FLAG_CODES.items() if flag in spoken[1]}
        elif source == 'Comet' and re.fullmatch(r'[\U0001F1E6-\U0001F1FF🌎💃🏻/ ]+', line):
            return {code for flag, code in FLAG_CODES.items() if flag in line}
        elif source == 'MediaFusion' and line.startswith('🌐'):
            return {code for token in line[1:].split('|') if (code := language(token))}
    return set()


class ProbeUnavailable(Exception):
    pass


COMMENTARY = re.compile(r'\b(?:commentar(?:y|ies)|commentaires?|kommentare?|comentarios?|komentarz)\b', re.I)
HEAD_BYTES = 512 * 1024
TAIL_BYTES = 2 * 1024 * 1024


def range_bytes(opener, link, start, end, total, deadline):
    request = urllib.request.Request(link, headers={'Range': f'bytes={start}-{end}', 'Accept-Encoding':'identity'})
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ProbeUnavailable
    with opener.open(request, timeout=min(4, remaining)) as response:
        if (response.status != 206 or response.headers.get('Content-Range') != f'bytes {start}-{end}/{total}'
                or response.headers.get('Content-Encoding', 'identity').lower() != 'identity'):
            raise ProbeUnavailable
        data = bytearray()
        while len(data) < end - start + 1 and time.monotonic() < deadline:
            chunk = response.read1(min(65536, end - start + 1 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
        if len(data) != end - start + 1:
            raise ProbeUnavailable
        return data


def inspected_tracks(fd, executable, deadline, complete=False):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ProbeUnavailable
    command = [executable, '-v', 'quiet', '-max_alloc', '16777216', '-probesize', '262144',
               '-analyzeduration', '100000', '-protocol_whitelist', 'file',
               '-format_whitelist', 'mov,mp4,m4a,3gp,3g2,mj2,mpegts,mpeg,avi,asf,ogg',
               '-select_streams', 'a', '-show_entries',
               'stream=index:stream_tags=language,title,handler_name:stream_disposition=comment', '-of', 'json',
               '/proc/self/fd/' + str(fd)]
    result = subprocess.run(command, pass_fds=(fd,), capture_output=True, timeout=min(2, remaining))
    if result.returncode:
        return None
    rows = json.loads(result.stdout).get('streams', [])
    if not rows:
        return frozenset() if complete else None
    languages = set()
    for track in rows:
        tags = track.get('tags') or {}
        label = ' '.join(str(tags.get(k,'')) for k in ('title', 'handler_name'))
        if track.get('disposition', {}).get('comment') or COMMENTARY.search(label):
            continue
        if code := language(tags.get('language')):
            languages.add(code)
    return frozenset(languages)


def ebml_fields(data, start, end):
    """Walk direct EBML children. Payloads may extend beyond a partial header."""
    while start < min(len(data), end):
        pos = start
        values = []
        for is_size in (False, True):
            if pos >= len(data) or not data[pos]:
                raise ProbeUnavailable
            width = 9 - data[pos].bit_length()
            if width > (8 if is_size else 4) or pos + width > len(data):
                raise ProbeUnavailable
            value = int.from_bytes(data[pos:pos + width], 'big')
            pos += width
            if is_size:
                mask = (1 << (width * 7)) - 1
                value &= mask
                if value == mask:
                    value = end - pos
            values.append(value)
        element, length = values
        if pos + length > end:
            raise ProbeUnavailable
        yield element, pos, pos + length
        start = pos + length


def matroska_audio(head, size):
    # Matroska defines an omitted Language element to default to English.
    # ffprobe displays that default indistinguishably from an explicit tag.
    segment = next(((a, b) for key, a, b in ebml_fields(head, 0, size) if key == 0x18538067), None)
    if not segment:
        raise ProbeUnavailable
    tracks = next(((a, b) for key, a, b in ebml_fields(head, *segment) if key == 0x1654AE6B), None)
    if not tracks or tracks[1] > len(head):
        raise ProbeUnavailable
    languages = set()
    for key, a, b in ebml_fields(head, *tracks):
        if key != 0xAE:  # TrackEntry
            continue
        fields = {k: bytes(head[x:y]) for k, x, y in ebml_fields(head, a, b)
                  if k in (0x83, 0xB9, 0x55AF, 0x536E, 0x22B59C, 0x22B59D)}
        if (int.from_bytes(fields.get(0x83, b''), 'big') != 2
                or not int.from_bytes(fields.get(0xB9, b'\x01'), 'big')
                or int.from_bytes(fields.get(0x55AF, b''), 'big')
                or COMMENTARY.search(fields.get(0x536E, b'').decode('utf-8', 'replace'))):
            continue
        label = fields.get(0x22B59D, fields.get(0x22B59C, b'')).rstrip(b'\0').decode('ascii', 'replace')
        if code := language(label):
            languages.add(code)
    return frozenset(languages)


def probe_audio(link, size, filename, opener, executable='ffprobe'):
    """Inspect a sparse, anonymous local file. ffprobe has no network protocols."""
    if type(size) is not int or not 0 < size < (1 << 50):
        raise ProbeUnavailable
    deadline = time.monotonic() + 8
    try:
        head = range_bytes(opener, link, 0, min(size, HEAD_BYTES) - 1, size, deadline)
        if head.startswith(bytes.fromhex('1a45dfa3')):
            return matroska_audio(head, size)
        with os.fdopen(os.memfd_create('guard-selected-file'), 'w+b', buffering=0) as file:
            file.write(head)
            # Preserve MP4 absolute box offsets without allocating the whole video.
            file.truncate(size)
            tracks = inspected_tracks(file.fileno(), executable, deadline, complete=len(head) == size)
            if tracks is not None:
                return tracks
            if size > len(head) and filename.casefold().endswith(('.mp4', '.m4v', '.mov')):
                offset = max(len(head), size - TAIL_BYTES)
                tail = range_bytes(opener, link, offset, size - 1, size, deadline)
                file.seek(offset)
                file.write(tail)
                tracks = inspected_tracks(file.fileno(), executable, deadline, complete=offset == len(head))
                if tracks is not None:
                    return tracks
    except (OSError, HTTPException, ValueError, subprocess.TimeoutExpired):
        pass
    raise ProbeUnavailable
