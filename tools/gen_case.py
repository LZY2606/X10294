#!/usr/bin/env python3
"""Offline generator for the fixed ARINC 429 evidence case pack.

The produced JSON is committed under examples/ and loaded by the application
and the replay/end-to-end tests. This script is NOT a runtime dependency.

ARINC 429 convention used by the tool:
  * word transmitted bit 1 (label MSB) .. bit 32 (parity).
  * label bits are stored MSB-first and DISPLAYED as 3-digit octal.
  * odd parity over all 32 bits.
"""
import json
import os


class Word:
    def __init__(self, msb0=0):
        self.v = msb0 & 0xFFFFFFFF

    def put(self, arinc_hi: int, arinc_lo: int, value: int):
        for i, arinc in enumerate(range(arinc_lo, arinc_hi + 1)):
            if (value >> i) & 1:
                self.v |= 1 << (32 - arinc)
        return self

    def put_label(self, octal_str: str):
        # label MSB first: ARINC bit 1 is the label MSB (value 0o200).
        self.put(8, 1, int(octal_str, 8))
        return self

    def odd_parity(self):
        # parity bit 32 must make the total number of ones odd.
        ones = bin(self.v & 0x7FFFFFFF).count("1")
        if ones % 2 == 0:
            self.v |= 1
        else:
            self.v &= 0xFFFFFFFE
        return self.v

    def build(self):
        self.odd_parity()
        return self.v


def bnr(us):
    w = Word()
    w.put_label("205")
    # SDI bits 9-10 = 00; magnitude bits 11..28 (18 bits); sign bit 29; SSM 30-31
    w.put(28, 11, abs(us) & 0x3FFFF)
    w.put(29, 29, 1 if us < 0 else 0)
    return w


def bcd(digits_nibbles):
    w = Word()
    w.put_label("240")
    w.put(10, 9, 0)
    # nibble anchors (msb ARINC bit): 28, 24, 20, 16 ; sign bit 12
    anchors = [28, 24, 20, 16]
    for nib, anchor in zip(digits_nibbles, anchors):
        w.put(anchor, anchor - 3, nib)
    w.put(12, 12, 0)
    return w


def discrete(bits_map):
    w = Word()
    w.put_label("041")
    w.put(10, 9, 0)
    for arinc, val in bits_map.items():
        w.put(arinc, arinc, val)
    return w


def ssm_set(w, ssm):
    w.put(31, 30, ssm)
    return w


# ---- channel A words (high rate 100 kHz, then low rate 12.5 kHz) ----
# SSM=3 (normal operation) everywhere except w11 which carries SSM=2
# (functional test) as the fixed "SSM invalid state" situation.
w1 = ssm_set(bnr(1200), 3).build()        # +120.0 ft
w2 = ssm_set(bnr(1216), 3).build()        # +121.6 ft
w3 = ssm_set(bnr(1232), 3).build()        # +123.2 ft
w4 = ssm_set(bnr(1248), 3).build()        # +124.8 ft
w5 = ssm_set(bnr(-200), 3).build()        # -20.0 ft (BNR negative)
w6 = ssm_set(bcd([2, 3, 7, 5]), 3).build()   # 237.5 kg (fuel, 0.1 scale)
w7 = ssm_set(bcd([0, 0, 12, 0]), 3).build()  # illegal BCD digit 12 in tens
w8 = ssm_set(discrete({11: 1, 12: 0, 13: 0, 14: 0}), 3).build()  # gear DOWN
w9 = ssm_set(bnr(2960), 3).build()        # +296.0 ft
w10 = ssm_set(bnr(2940), 3).build()       # +294.0 ft
w11 = ssm_set(bnr(2920), 2).build()       # +292.0 ft but SSM=2 (invalid state)
w12 = ssm_set(bnr(2900), 3).build()       # +290.0 ft


def ts(us):
    return f"2026-09-24T01:10:{us // 1_000_000:02d}.{us % 1_000_000:06d}Z"


t_us = [
    0,
    320,
    640,
    960,
    1280,
    1600,
    1920,
    2240,
    53440,      # +51200 us = 20 low-rate word times -> collection GAP
    56000,      # +2560 us: ambiguous {LOW continuous, high-rate GAP}
    137920,     # +81920 us = 32 low-rate word times -> GAP
    140480,     # +2560 us: ambiguous {LOW, GAP}
]

raws_a = [w1, w2, w3, w4, w5, w6, w7, w8, w9, w10, w11, w12]
assert len(t_us) == len(raws_a) == 12

words = []
for idx, (t, raw) in enumerate(zip(t_us, raws_a), start=1):
    words.append(
        {
            "id": f"raw:CH-A:{idx:02d}",
            "seq": idx,
            "channel": "CH-A",
            "receivedAt": ts(t),
            "receivedUs": t,
            "raw32": raw,
            "rawHex": f"{raw:08X}",
            "sourceRef": f"bus-capture-2026-09-24#CH-A@{t}",
        }
    )

# ---- channel B control words (clean 12.5 kHz, unaffected by CH-A edits) ----
tb = [0, 2560, 5120]
rb = [
    ssm_set(discrete({11: 1}), 3).build(),
    ssm_set(discrete({11: 1}), 3).build(),
    ssm_set(discrete({11: 1}), 3).build(),
]
for idx, (t, raw) in enumerate(zip(tb, rb), start=1):
    words.append(
        {
            "id": f"raw:CH-B:{idx:02d}",
            "seq": idx,
            "channel": "CH-B",
            "receivedAt": ts(t),
            "receivedUs": t,
            "raw32": raw,
            "rawHex": f"{raw:08X}",
            "sourceRef": f"bus-capture-2026-09-24#CH-B@{t}",
        }
    )

case = {
    "caseId": "arinc429-wordpath-evidence-v1",
    "title": "字路译码台：ARINC 429 标签、奇偶与速率分段证据",
    "schemaVersion": 1,
    "createdAt": "2026-09-24T01:00:00Z",
    "unitBasis": {
        "altitude": "ft (geometric, channel-independent)",
        "fuel": "kg (quantity)",
        "angle": "deg (geometric)",
        "rateHigh": "bit/s",
        "rateLow": "bit/s",
        "time": "microseconds since 2026-09-24T01:10:00Z",
    },
    "channels": [
        {"id": "CH-A", "name": "Avionics Bus A", "nominalRateBps": 100000, "wordBits": 32},
        {"id": "CH-B", "name": "Avionics Bus B", "nominalRateBps": 12500, "wordBits": 32},
    ],
    "dictionaries": [
        {
            "id": "dict-v1",
            "labelOctal": "205",
            "labelInt": 133,
            "name": "ALTITUDE",
            "encoding": "BNR",
            "signed": True,
            "dataMsb": 28,
            "dataLsb": 11,
            "resolution": 0.1,
            "unit": "ft",
            "ssmMode": "bnr",
        },
        {
            "id": "dict-v1",
            "labelOctal": "240",
            "labelInt": 160,
            "name": "FUEL_QUANTITY",
            "encoding": "BCD",
            "nibbles": [
                {"name": "d1", "msb": 28, "lsb": 25},
                {"name": "d2", "msb": 24, "lsb": 21},
                {"name": "d3", "msb": 20, "lsb": 17},
                {"name": "d4", "msb": 16, "lsb": 13},
            ],
            "signMsb": 12,
            "signLsb": 12,
            "resolution": 0.1,
            "unit": "kg",
            "ssmMode": "discrete",
        },
        {
            "id": "dict-v1",
            "labelOctal": "041",
            "labelInt": 33,
            "name": "GEAR_DISCRETE",
            "encoding": "DISCRETE",
            "bits": [
                {"arincBit": 11, "name": "gear_down_locked"},
                {"arincBit": 12, "name": "gear_up_locked"},
                {"arincBit": 13, "name": "gear_in_transit"},
                {"arincBit": 14, "name": "gear_fault"},
            ],
            "unit": "state",
            "ssmMode": "discrete",
        },
        {
            "id": "dict-v2",
            "labelOctal": "041",
            "labelInt": 33,
            "name": "FLAP_ANGLE_BNR",
            "encoding": "BNR",
            "signed": True,
            "dataMsb": 28,
            "dataLsb": 11,
            "resolution": 0.5,
            "unit": "deg",
            "ssmMode": "discrete",
            "supersedes": "dict-v1#041",
        },
    ],
    "parameters": {
        "labelRadix": 8,
        "activeDictionary": "dict-v1",
        "rateHighBps": 100000,
        "rateLowBps": 12500,
        "wordBits": 32,
        "gapMultiple": 4,
        "ambiguityLowMaxN": 1,
        "ambiguityHighMaxN": 10,
    },
    "batches": [
        {
            "id": "batch-2026-09-24",
            "importedAt": "2026-09-24T01:05:00Z",
            "source": "bus-capture-2026-09-24",
            "wordIds": [w["id"] for w in words],
        }
    ],
    "words": words,
    "replay": [
        {"seq": 1, "kind": "IMPORT", "actor": "avionics-data-team",
         "summary": "导入时间戳、32 位原始字、通道配置与标签字典"},
        {"seq": 2, "kind": "CHECK", "name": "baseline-preservation",
         "summary": "原位序列/接收时间/通道/奇偶/label/SDI/SSM/数据域/字典版本全部保留"},
        {"seq": 3, "kind": "PARAM_REV", "param": "labelRadix", "value": 8,
         "summary": "label 八进制展示参数确认（不改变底层位）"},
        {"seq": 4, "kind": "PARAM_REV", "param": "activeDictionary", "value": "dict-v2",
         "summary": "一次参数修订：041 由 discrete 切换为 BNR flap 角（字典版本决定解释）"},
        {"seq": 5, "kind": "PARAM_REV", "param": "activeDictionary", "value": "dict-v1",
         "summary": "回到 dict-v1 依赖版本（旧结论可恢复）"},
        {"seq": 6, "kind": "DECIDE", "session": "S1", "target": "iv:CH-A:09-10",
         "candidate": "LOW", "action": "ACCEPT",
         "summary": "窗口 S1 接受歧义区间的 LOW 连续候选"},
        {"seq": 7, "kind": "DECIDE", "session": "S2", "baseHead": 5, "conflictWith": 6,
         "target": "iv:CH-A:09-10", "candidate": "GAP", "action": "ACCEPT",
         "expectConflict": True,
         "summary": "窗口 S2 基于旧版本后提交 GAP 候选，必须看到版本冲突与双方差异"},
        {"seq": 8, "kind": "REVERT", "revertSeq": 6,
         "summary": "撤销 S1 的接受，恢复 CANDIDATE 多候选分支"},
        {"seq": 9, "kind": "BRANCH_SOURCE", "branch": "cb-bitrev",
         "sourceKind": "BIT_REVERSE", "wordId": "raw:CH-A:01",
         "summary": "反例：位序反转（label/数据/SSM 全部换位，仅影响该字）"},
        {"seq": 10, "kind": "BRANCH_SOURCE", "branch": "cb-parity",
         "sourceKind": "PARITY_ERROR", "wordId": "raw:CH-A:02",
         "summary": "反例：奇偶错误（坏奇偶字不进入聚合值）"},
        {"seq": 11, "kind": "BRANCH_SOURCE", "branch": "cb-rateswitch",
         "sourceKind": "RATE_SWITCH", "wordId": "raw:CH-A:08", "newReceivedUs": 4480,
         "summary": "反例：速率切换（只影响相邻区间与速率分段候选）"},
        {"seq": 12, "kind": "CHECK", "name": "counterexample-scope",
         "summary": "断言每个反例的失效范围被限制在正确对象"},
    ],
}

out = os.path.join(os.path.dirname(__file__), "..", "examples", "case-arinc429.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    json.dump(case, f, ensure_ascii=False, indent=2)
print("wrote", out, len(words), "words")
