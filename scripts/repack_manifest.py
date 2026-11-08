#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Repair android:pageSizeCompat in the compiled binary AndroidManifest.

apktool's aapt2 (built against an older platform) compiles
``android:pageSizeCompat="enabled"`` into a TYPE_INT_DEC value, but the
framework declares it as a *string*.  Android's parser therefore sees no
string and silently ignores the attribute, so the 16 KB page-size warning
dialog would keep appearing.

This rewrites the value in place to TYPE_STRING pointing at the pooled
"enabled" literal, then repacks the APK entry.  Run it on the *unsigned*
rebuild, before signing.
"""
import io
import os
import struct
import sys
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
TAG = "pageSizeCompat"


def read_pool(data, base):
    cnt, = struct.unpack("<I", data[base + 8:base + 12])
    flags, = struct.unpack("<I", data[base + 16:base + 20])
    sstart, = struct.unpack("<I", data[base + 20:base + 24])
    offs = [struct.unpack("<I", data[base + 28 + 4 * k:base + 32 + 4 * k])[0]
            for k in range(cnt)]
    return cnt, flags, base + sstart, offs


def pool_str(data, pool, idx):
    _cnt, flags, sbase, offs = pool
    o = sbase + offs[idx]
    if flags & 0x100:          # UTF-8 pool
        n = data[o]
        o += 1
        if n & 0x80:
            n = ((n & 0x7f) << 8) | data[o]
            o += 1
        return data[o:o + n].decode("utf-8", "replace")
    n = struct.unpack("<H", data[o:o + 2])[0]
    o += 2
    if n & 0x8000:
        n = ((n & 0x7fff) << 16) | struct.unpack("<H", data[o:o + 2])[0]
        o += 2
    return data[o:o + n * 2].decode("utf-16-le", "replace")


def fix(xml):
    data = bytearray(xml)
    root_size, = struct.unpack("<I", data[4:8])
    pool = read_pool(data, 8)
    cnt, _flags, _sb, _offs = pool

    def find(name):
        for k in range(cnt):
            if pool_str(data, pool, k) == name:
                return k
        return None

    name_idx = find(TAG)
    if name_idx is None:
        raise SystemExit("!! %s not present in string pool" % TAG)

    pat = struct.pack("<I", name_idx)
    hits = []
    pos = 0
    while True:
        j = data.find(pat, pos)
        if j < 0:
            break
        hits.append(j)
        pos = j + 1

    patched = 0
    for j in hits:
        # AttrExt: ns(u32) @j-4, name(u32) @j, rawValue(u32) @j+4,
        #          size(u16) @j+8, res0(u8), dataType(u8), data(u32)
        if j + 16 > len(data):
            continue
        raw, = struct.unpack("<I", data[j + 4:j + 8])
        vsize, vres0, vdt = struct.unpack("<HBB", data[j + 8:j + 12])
        vdata, = struct.unpack("<I", data[j + 12:j + 16])
        if vsize != 8:
            continue
        if vdt == 0x03:                     # already a string, nothing to do
            continue
        # resolve the intended literal: current int may or may not be an index
        target = find("enabled")
        if target is None:
            raise SystemExit("!! no 'enabled' literal in string pool")
        data[j + 8:j + 12] = struct.pack("<HBB", 8, 0, 0x03)
        data[j + 12:j + 16] = struct.pack("<I", target)
        data[j + 4:j + 8] = b"\xff\xff\xff\xff"   # rawValue = none
        patched += 1
        print("  %s: dt 0x%02x->0x03 data %d->%d (raw=%d)"
              % (TAG, vdt, vdata, target, raw))

    if not patched:
        raise SystemExit("!! no writable %s attribute record found" % TAG)
    return bytes(data), patched


def main(apk_in, apk_out):
    with zipfile.ZipFile(apk_in) as z:
        info = z.getinfo("AndroidManifest.xml")
        manifest = z.read("AndroidManifest.xml")
        new_manifest, n = fix(manifest)
        print("  patched %d record(s), %d -> %d bytes"
              % (n, len(manifest), len(new_manifest)))

        with zipfile.ZipFile(apk_out, "w", zipfile.ZIP_DEFLATED) as out:
            for it in z.infolist():
                buf = new_manifest if it.filename == "AndroidManifest.xml" \
                    else z.read(it.filename)
                zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
                zi.compress_type = it.compress_type
                zi.external_attr = it.external_attr
                zi.internal_attr = it.internal_attr
                zi.create_system = it.create_system
                out.writestr(zi, buf)
    print("wrote %s" % apk_out)


if __name__ == "__main__":
    a = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, "build3.apk")
    b = sys.argv[2] if len(sys.argv) > 2 else os.path.join(BASE, "build3-fixed.apk")
    main(a, b)
