# Fetches one file from a remote zip with HTTP range requests, without downloading the whole archive.
# Used for libc++_shared.so from the NDK r27c zip (udx-native needs it; bare-kit does not ship it).
# Usage: python3 -I fetch_from_zip.py <zip url> <member path suffix> <out file>
import sys, io, zipfile, urllib.request
url, member, out = sys.argv[1:4]
class R(io.RawIOBase):
    def __init__(s):
        r = urllib.request.urlopen(urllib.request.Request(url, method='HEAD')); s.n = int(r.headers['Content-Length']); s.p = 0; s.got = 0
    def seekable(s): return True
    def readable(s): return True
    def tell(s): return s.p
    def seek(s, o, w=0):
        s.p = o if w == 0 else s.p + o if w == 1 else s.n + o; return s.p
    def readinto(s, b):
        if s.p >= s.n: return 0
        end = min(s.n, s.p + len(b)) - 1
        d = urllib.request.urlopen(urllib.request.Request(url, headers={'Range': f'bytes={s.p}-{end}'})).read()
        b[:len(d)] = d; s.p += len(d); s.got += len(d); return len(d)
r = R(); z = zipfile.ZipFile(io.BufferedReader(r, 1 << 20))
names = [n for n in z.namelist() if n.endswith(member)]
print('zip', r.n // 1048576, 'MB; matches', names)
open(out, 'wb').write(z.read(names[0])); print('fetched', r.got // 1024, 'KB')
