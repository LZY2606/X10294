#!/usr/bin/env python3
"""Generate the ARINC 429 'word road decoding bench' offline case pack.
Kept in-repo so reviewers can reproduce every bit; the JSON is shipped as the domain material."""
import json, os

N = 32

def reverse_low(value, bits):
    out = 0
    for i in range(bits):
        if value & (1 << i):
            out |= 1 << (bits - 1 - i)
    return out

def odd_parity(w):
    w &= 0x7FFFFFFF
    if bin(w).count("1") % 2 == 0:
        w |= 1 << 31
    return w

def label_field(octal_str):
    num = int(octal_str, 8)
    return reverse_low(num, 8)

def encode(label_oct, sdi, data, ssm, bad_parity=False):
    w = label_field(label_oct)
    w |= (sdi & 0x3) << 8
    w |= (data & 0x7FFFF) << 10
    w |= (ssm & 0x3) << 29
    w = odd_parity(w)
    if bad_parity:
        w ^= 1 << 31
    return w

def bnr_data(value_over_fs, bits=19):
    n = int(round(value_over_fs * (1 << (bits - 1))))
    n = max(-(1 << (bits-1)), min((1 << (bits-1)) - 1, n))
    if n < 0:
        n += (1 << bits)
    return n << (19 - bits)

def bcd_data(digits, lower=0):
    d = 0
    for i, x in enumerate(digits):
        d |= (x & 0xF) << (13 - i*4)
    d |= lower & 0x1FFF
    return d

words = []
def add(seq, t_us, channel, label, sdi, data, ssm, bad=False, note="", reversed_capture=False):
    w = encode(label, sdi, data, ssm, bad)
    if reversed_capture:
        # captured byte stream reversed: reverse full 32 bits; parity of stored raw is then bad
        w = reverse_low(w, 32)
    words.append({
        "seq": seq, "tsNanos": t_us * 1000, "channel": channel,
        "word": "0x%08X" % w, "reversedCapture": reversed_capture, "note": note
    })

# ---- channel A: 100k high speed (spacing 320 us) with one rate switch and an acquisition gap ----
t = 0
US_HIGH = 320
US_LOW = 2560
add(1, t, "A1", "203", 0, bnr_data(0.25), 0b11, note="BNR +60 kt baseline")
t += US_HIGH
add(2, t, "A1", "203", 0, bnr_data(-0.5), 0b11, note="BNR negative -120 kt")
t += US_HIGH
add(3, t, "A1", "203", 0, bnr_data(0.125), 0b11, bad=True, note="parity error: excluded from aggregate")
t += US_HIGH
add(4, t, "A1", "203", 0, bnr_data(-0.125), 0b11, note="BNR negative small")
t += US_HIGH
add(5, t, "A1", "270", 0, bcd_data([0,1,2,3]), 0b11, note="BCD 0123 m")
# rate switch high -> low
t += US_LOW
add(6, t, "A1", "270", 0, bcd_data([0,0,4,2]), 0b11, note="BCD 0042 m, low speed begins")
t += US_LOW
add(7, t, "A1", "270", 0, bcd_data([1,0,0,5]), 0b11, note="BCD 1005 m")
# acquisition gap (still same low speed) -> segmentation candidates
t += US_LOW * 6
add(8, t, "A1", "270", 0, bcd_data([1,0,1,0]), 0b11, note="after acquisition gap")
t += US_LOW
add(9, t, "A1", "270", 0, bcd_data([1,0,1,2]), 0b11, note="after gap 2")

# ---- channel B: discrete, SSM states, illegal BCD, bit-reversed capture ----
tb = 0
add(10, tb, "B2", "377", 0, 0b1010101010101010101, 0b11, note="discrete normal", reversed_capture=True)
tb += US_LOW
add(11, tb, "B2", "270", 0, bcd_data([1,2,0xA,3]), 0b11, note="illegal BCD digit A")
tb += US_LOW
add(12, tb, "B2", "203", 0, bnr_data(0.3), 0b00, note="SSM failure warning")
tb += US_LOW
add(13, tb, "B2", "203", 0, bnr_data(0.3), 0b01, note="SSM no computed data")
tb += US_LOW
add(14, tb, "B2", "203", 0, bnr_data(0.3), 0b10, note="SSM functional test")
tb += US_LOW
add(15, tb, "B2", "203", 0, bnr_data(0.3), 0b11, note="normal, aggregates")

base = {
  "casePackId": "word-road-decoding-bench",
  "title": "字路译码台：ARINC 429 标签、奇偶与速率分段证据",
  "version": "1.0.0",
  "sourceName": "avionics-data-team-bus-capture-A/B",
  "channels": [
    {"channel": "A1", "bus": "ARINC429-BUS-1", "expectedLabels": ["203", "270"]},
    {"channel": "B2", "bus": "ARINC429-BUS-2", "expectedLabels": ["203", "270", "377"]}
  ],
  "words": words
}

dict_v1 = {
  "dictId": "a429-lab-dict", "version": "v1",
  "defs": [
    {"labelOctal": "203", "name": "AIRSPEED", "encoding": "BNR", "bnrBits": 17,
     "fullScale": 240.0, "unit": "kt"},
    {"labelOctal": "270", "name": "ALTITUDE", "encoding": "BCD", "bnrBits": 0,
     "fullScale": 0.0, "unit": "m", "discreteNames": []},
    {"labelOctal": "377", "name": "DISCRETE_STATUS", "encoding": "DISCRETE", "bnrBits": 0,
     "fullScale": 0.0, "unit": "bits",
     "discreteNames": ["D19","D18","D17","D16","D15","D14","D13","D12","D11","D10",
                       "D9","D8","D7","D6","D5","D4","D3","D2","D1"]}
  ]
}
dict_v2 = {
  "dictId": "a429-lab-dict", "version": "v2",
  "defs": [
    {"labelOctal": "203", "name": "AIRSPEED_KMH", "encoding": "BNR", "bnrBits": 17,
     "fullScale": 400.0, "unit": "km/h"},
    {"labelOctal": "270", "name": "ALTITUDE", "encoding": "BCD", "bnrBits": 0,
     "fullScale": 0.0, "unit": "m", "discreteNames": []},
    {"labelOctal": "377", "name": "DISCRETE_STATUS", "encoding": "DISCRETE", "bnrBits": 0,
     "fullScale": 0.0, "unit": "bits",
     "discreteNames": ["D19","D18","D17","D16","D15","D14","D13","D12","D11","D10",
                       "D9","D8","D7","D6","D5","D4","D3","D2","D1"]}
  ]
}
params_v1 = {"paramsId": "rate-segmentation", "version": "r1",
             "toleranceRatio": 0.15, "gapFactor": 3.0, "maxCandidates": 16}
params_v2 = {"paramsId": "rate-segmentation", "version": "r2",
             "toleranceRatio": 0.25, "gapFactor": 2.0, "maxCandidates": 16}

out = os.path.join(os.path.dirname(__file__),
                   "../src/main/resources/cases/word-road-decoding-bench")
with open(os.path.join(out, "case.json"), "w") as f:
    json.dump(base, f, ensure_ascii=False, indent=2)
with open(os.path.join(out, "dictionary-v1.json"), "w") as f:
    json.dump(dict_v1, f, ensure_ascii=False, indent=2)
with open(os.path.join(out, "dictionary-v2.json"), "w") as f:
    json.dump(dict_v2, f, ensure_ascii=False, indent=2)
with open(os.path.join(out, "params-r1.json"), "w") as f:
    json.dump(params_v1, f, ensure_ascii=False, indent=2)
with open(os.path.join(out, "params-r2.json"), "w") as f:
    json.dump(params_v2, f, ensure_ascii=False, indent=2)

print(json.dumps(words, indent=2))
