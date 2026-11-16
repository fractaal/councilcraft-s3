#!/usr/bin/env python3
"""Reads a recording made by packcheck.sh (PACKCHECK_SOUND) and says what was heard in it.

    heard.py <file.wav> <clock time the sound engine started, HH:MM:SS.mmm> [clock time to look for a bell, HH:MM:SS]

The lab's speaker test is a steady 330 + 495 Hz tone with a 2 s 1500 Hz tone over it every 8 s. Prints each stretch of
the steady tone, each 1500 Hz burst with its clock time and the gap since the one before, and (with a third argument)
how much sound that is neither tone there was in the ten seconds from that time, against a quiet stretch.
"""
import sys
import numpy as np

path, started = sys.argv[1], sys.argv[2]
h, m, s = started.split(':')
t0 = int(h) * 3600 + int(m) * 60 + float(s)


def clock(sec):
    t = t0 + sec
    return '%02d:%02d:%04.1f' % (t // 3600 % 24, t // 60 % 60, t % 60)


raw = open(path, 'rb').read()
j = raw.find(b'data')
raw = raw[j + 8:]
x = np.frombuffer(raw[:len(raw) // 8 * 8], dtype=np.float32).reshape(-1, 2).astype(np.float64)
rate = 48000
mono = x.mean(axis=1)
secs = len(mono) / rate
print('%.1f s recorded, from %s to %s; peak %.3f; NaN samples %d' % (secs, clock(0), clock(secs), np.nanmax(np.abs(mono)), np.isnan(mono).sum()))
loud = np.nonzero(np.abs(mono) > 1e-6)[0]
print('first sound at %s' % (clock(loud[0] / rate) if len(loud) else 'never'))

STEP = 0.25
n = int(STEP * rate)
t = np.arange(n) / rate


def tone(seg, hz):
    return 2 * abs(np.dot(seg, np.exp(-2j * np.pi * hz * t))) / n


steps = int(secs / STEP)
low = np.array([tone(mono[i * n:(i + 1) * n], 330) for i in range(steps)])
high = np.array([tone(mono[i * n:(i + 1) * n], 1500) for i in range(steps)])


def stretches(mask):
    out, start = [], None
    for i, on in enumerate(mask):
        if on and start is None: start = i
        if not on and start is not None: out.append((start, i)); start = None
    if start is not None: out.append((start, len(mask)))
    return out


print('steady tone (330 Hz):')
for a, b in stretches(low > 0.01):
    seg = low[a:b]
    print('  %s to %s  (%.1f s)  level full %.3f, lowest %.3f' % (clock(a * STEP), clock(b * STEP), (b - a) * STEP, np.median(seg), seg.min()))
bursts = stretches(high > 0.01)
print('1500 Hz bursts: %d' % len(bursts))
last = None
for a, b in bursts:
    gap = '' if last is None else '  %.1f s after the one before' % ((a - last) * STEP)
    print('  %s  %.2f s long, level %.3f%s' % (clock(a * STEP), (b - a) * STEP, high[a:b].max(), gap))
    last = a

if len(sys.argv) > 3:
    h, m, s = sys.argv[3].split(':')
    at = int(h) * 3600 + int(m) * 60 + float(s) - t0

    def rest(a, b):   # what is left once the three test tones are taken out, as an rms
        seg = mono[int(a * rate):int(b * rate)]
        spec = np.fft.rfft(seg)
        f = np.fft.rfftfreq(len(seg), 1 / rate)
        for hz in (330, 495, 1500):
            spec[np.abs(f - hz) < 15] = 0
        return np.sqrt((np.fft.irfft(spec, len(seg)) ** 2).mean())

    print('sound that is not a test tone: %.5f rms in the 10 s from %s, %.5f in the 10 s before' % (rest(at, at + 10), sys.argv[3], rest(at - 10, at)))
