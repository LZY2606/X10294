#!/usr/bin/env python3
"""Generate the offline domain case pack for the ARINC 429 review bench.

Every 32-bit word is built explicitly from ARINC fields with odd parity, so
the baseline has a documented construction provenance and the fault words
differ from good words in exactly one intended dimension.
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(__file__), "..", "casepack")


def reverse8(v):
    return int("{:08b}".format(v & 0xFF)[::-1], 2)


def octal_label(code):
    return "{:03o}".format(reverse8(code))


def code_from_octal(oct3):
    return reverse8(int(oct3, 8))


def bitcount_odd(v):
    return bin(v & 0xFFFFFFFF).count("1") % 2 == 1


def encode(label_oct, sdi, data19, ssm, force_parity_ok=True, parity_bit=None):
    label_code = code_from_octal(label_oct)
    v = (label_code & 0xFF)
    v |= (sdi & 0x3) << 8
    v |= (data19 & 0x7FFFF) << 10
    v |= (ssm & 0x3) << 28
    if parity_bit is None:
        pb = 0 if bitcount_odd(v) else 1
        if not force_parity_ok:
            pb ^= 1
        v |= pb << 31
    else:
        v |= (parity_bit & 1) << 31
    return v & 0xFFFFFFFF


def bcd_pack(digits_lsb_first):
    v = 0
    for i, d in enumerate(digits_lsb_first):
        if d is None:
            d = 0xA  # illegal BCD nibble stays at its original position
        v |= (d & 0xF) << (i * 4)
    return v


def bnr_signed(value, lsb):
    counts = int(round(value / lsb))
    return counts & 0x7FFFF


def hex8(v):
    return "{:08X}".format(v & 0xFFFFFFFF)


LABEL_ALT = "203"      # BNR barometric altitude
LABEL_IAS = "206"      # BCD indicated airspeed
LABEL_DISCRETE = "270"  # discrete status word


def build_words():
    words = []
    idx = 0

    def add(channel, t_us, label_oct, sdi, data19, ssm, role,
            parity_ok=True, parity_bit=None):
        nonlocal idx
        raw = encode(label_oct, sdi, data19, ssm, parity_ok, parity_bit)
        idx += 1
        words.append({
            "id": "w-%03d" % idx,
            "orig_index": str(idx),
            "receive_time_us": str(t_us),
            "channel": channel,
            "raw_text": "0x" + hex8(raw),
            "role": role,
        })

    # Segment A-1: CH-A nominal 12.5 kbps low-speed labels.
    # Nominal ARINC 429 word time at 12.5 kbps is 2.56 ms; times are rounded
    # to whole microseconds, interleaved with CH-B at +1000 us.
    t = 0
    alt_good = [1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900]  # feet MSL
    good_i = 0
    ias_kmh = [312, 315, 320]
    ias_i = 0
    seq = [
        ("A", LABEL_ALT, 0, 0, bnr_signed(alt_good[good_i], 1.0), "alt good +1200"),
        ("B", LABEL_ALT, 0, 0, bnr_signed(9900, 3.0), "B alt +9900"),
        ("A", LABEL_IAS, 0, 0, bcd_pack([2, 1, 3]), "IAS 312"),
        ("B", LABEL_DISCRETE, 0, 0, 0b101, "B discrete bits"),
        ("A", LABEL_ALT, 0, 1, bnr_signed(1300, 1.0), "ALT SSM=NCD excluded"),
        ("B", LABEL_ALT, 0, 3, bnr_signed(-300, 3.0), "B ALT negative -300"),
        ("A", LABEL_ALT, 0, 0, bnr_signed(1400, 1.0), "alt good +1400"),
        ("B", LABEL_IAS, 0, 0, bcd_pack([5, 1, 3]), "B IAS 315"),
        ("A", LABEL_DISCRETE, 0, 0, 0b01000000, "discrete GEAR-DOWN"),
        ("B", LABEL_ALT, 0, 0, bnr_signed(10200, 3.0), "B alt good"),
        ("A", LABEL_ALT, 0, 0, bnr_signed(1500, 1.0), "alt good +1500"),
        ("B", LABEL_DISCRETE, 0, 2, 0b100000, "B discrete SSM=FW invalid"),
        ("A", LABEL_IAS, 0, 0, bcd_pack([5, 1, 3]), "IAS 315"),
    ]
    for ch, lab, sdi, ssm, data19, role in seq:
        add(ch, t + (1000 if ch == "B" else 0), lab, sdi, data19, ssm, role)
        if ch == "B":
            t += 2560

    # Explicit boundary words embedded in the record.
    # CH-A bad parity altitude (one flipped parity bit): excluded everywhere.
    add("A", 14 * 2560, LABEL_ALT, 0, bnr_signed(1750, 1.0), 0,
        "ALT bad parity excluded", parity_ok=False)
    # CH-A illegal BCD nibble in IAS: value retained with diagnostic.
    bad_bcd = bcd_pack([0xB, 2, 3])  # rendered "32?" style with ? at digit
    add("A", 15 * 2560, LABEL_IAS, 0, bad_bcd, 0, "IAS illegal BCD nibble retained")
    # CH-A negative BNR altitude.
    add("A", 16 * 2560, LABEL_ALT, 0, bnr_signed(-450, 1.0), 0, "ALT negative -450")

    # Acquisition gap (500 ms) then rate switch to 100 kbps (0.32 ms nominal).
    t2 = 17 * 2560 + 500_000
    seg2 = [
        ("A", LABEL_ALT, 0, 0, bnr_signed(2100, 1.0), "seg2 alt +2100"),
        ("B", LABEL_ALT, 0, 0, bnr_signed(11100, 3.0), "seg2 B alt"),
        ("A", LABEL_ALT, 0, 0, bnr_signed(2200, 1.0), "seg2 alt +2200"),
        ("B", LABEL_DISCRETE, 0, 0, 0b11, "seg2 B discrete"),
        ("A", LABEL_ALT, 0, 0, bnr_signed(2300, 1.0), "seg2 alt +2300"),
        ("B", LABEL_ALT, 0, 0, bnr_signed(-600, 3.0), "seg2 B ALT negative"),
        ("A", LABEL_IAS, 0, 0, bcd_pack([0, 2, 3]), "seg2 IAS 320"),
        ("B", LABEL_IAS, 0, 0, bcd_pack([0, 2, 3]), "seg2 B IAS 320"),
    ]
    step = 320
    tt = t2
    for ch, lab, sdi, ssm, data19, role in seg2:
        add(ch, tt + (1000 if ch == "B" else 0), lab, sdi, data19, ssm, role)
        if ch == "B":
            tt += step
    return words


def channels():
    return [
        {"id": "ch-A", "name": "Avionics Bus A", "configured_bps": None,
         "note": "configured rate withheld so the bench must infer it"},
        {"id": "ch-B", "name": "Avionics Bus B", "configured_bps": None},
    ]


def ssm_table():
    return {
        "BNR": {"0": "NO (normal operation)", "1": "NCD (no computed data)",
                "2": "FT (functional test)", "3": "FW (failure warning)"},
        "BCD": {"3": "NO (normal operation)", "0": "NCD (no computed data)",
                "1": "FT (functional test)", "2": "FW (failure warning)"},
        "DISCRETE": {"3": "NO (normal operation)", "0": "NCD (no computed data)",
                     "1": "FT (functional test)", "2": "FW (failure warning)"}
    }


def dictionaries():
    v1 = {
        "version": "dict-1.0",
        "coord_datum": "all altitudes are feet above MSL",
        "entries": [
            {"key": LABEL_ALT + "/00", "label_octal": LABEL_ALT, "sdi": "00",
             "name": "Baro Altitude", "encoding": "BNR", "lsb_weight": 1.0,
             "units": "ft MSL", "note": "19-bit signed, 1 ft/count"},
            {"key": LABEL_IAS + "/00", "label_octal": LABEL_IAS, "sdi": "00",
             "name": "Indicated Airspeed", "encoding": "BCD", "bcd_digits": 3,
             "units": "km/h", "note": "three packed BCD digits, LSB digit first"},
            {"key": LABEL_DISCRETE + "/00", "label_octal": LABEL_DISCRETE, "sdi": "00",
             "name": "Landing Gear Status", "encoding": "DISCRETE",
             "discrete_bits": {"1": "GEAR-UP", "3": "GEAR-DOWN",
                               "7": "GEAR-BUS-FAULT", "13": "GEAR-DOOR"},
             "units": "discrete", "note": "data bit positions are 1-based"},
        ],
    }
    v2 = json.loads(json.dumps(v1))
    v2["version"] = "dict-2.0"
    v2["coord_datum"] = "all altitudes are metres above MSL"
    v2["entries"][0]["lsb_weight"] = 0.3048
    v2["entries"][0]["units"] = "m MSL"
    v2["entries"][0]["note"] = "19-bit signed, 0.3048 m/count (same raw bits)"
    return v1, v2


def rules():
    v1 = {
        "version": "rules-1.0",
        "rate_tolerance": 0.15,
        "acquisition_gap_us": 20000,
        "ssm_table": ssm_table(),
        "note": "15% jitter tolerance, gaps >=20 ms split segments",
    }
    v2 = json.loads(json.dumps(v1))
    v2["version"] = "rules-2.0"
    v2["rate_tolerance"] = 0.30
    v2["note"] = "30% jitter tolerance after parameter revision; SSM table unchanged"
    return v1, v2


MUTATIONS = [
    {
        "id": "M1-bit-order-reversal",
        "kind": "reverse32",
        "target_word": "w-001",
        "changes": "raw32",
        "label": "位序反转 (bit order reversal)",
        "expected_scope": ["decoded:w-001", "aggregate:ch-A/203"],
    },
    {
        "id": "M2-parity-error",
        "kind": "flipParity",
        "target_word": "w-013",
        "changes": "parity bit",
        "label": "奇偶错误 (bad parity)",
        "expected_scope": ["decoded:w-013", "aggregate:ch-A/206"],
    },
    {
        "id": "M3-octal-label-presentation",
        "kind": "octalPresentation",
        "target_word": "w-013",
        "changes": "presentation only",
        "label": "八进制 label 展示 (octal label presentation)",
        "expected_scope": [],
        "note": "displaying label as octal never mutates the underlying 32 bits",
    },
    {
        "id": "M4-bnr-negative",
        "kind": "sourceEdit",
        "target_word": "w-019",
        "raw_text": "0x" + hex8(encode(LABEL_ALT, 0, bnr_signed(-825, 1.0), 0)),
        "changes": "BNR data field sign",
        "label": "BNR 负值 (negative BNR)",
        "expected_scope": ["decoded:w-019", "aggregate:ch-A/203"],
    },
    {
        "id": "M5-bcd-illegal-digit",
        "kind": "sourceEdit",
        "target_word": "w-023",
        "raw_text": "0x" + hex8(encode(LABEL_IAS, 0, bcd_pack([0xC, 9, 4]), 0)),
        "changes": "BCD low nibble -> 0xC",
        "label": "BCD 非法数字 (illegal BCD digit)",
        "expected_scope": ["decoded:w-023", "aggregate:ch-A/206"],
    },
    {
        "id": "M6-rate-switch",
        "kind": "sourceEdit",
        "target_word": "w-019",
        "receive_time_us": "544160",
        "changes": "receive timestamp aligns with the 100 kbps cadence",
        "label": "速率切换 (rate switch timing)",
        "expected_scope": ["rate:ch-A"],
    },
    {
        "id": "M7-ssm-invalid",
        "kind": "sourceEdit",
        "target_word": "w-021",
        "raw_text": "0x" + hex8(encode(LABEL_ALT, 0, bnr_signed(2300, 1.0), 3)),
        "changes": "SSM -> FW",
        "label": "SSM 无效状态 (SSM failure warning)",
        "expected_scope": ["decoded:w-021", "aggregate:ch-A/203"],
    },
]


REPLAY_STAGES = [
    {
        "stage": 1,
        "name": "baseline-import",
        "title": "导入基线并核对原位字段",
        "action": {"type": "import-casepack"},
        "assertions": [
            "raw words preserve orig_index/receive_time_us/channel/raw_text",
            "parity, label octal, SDI, SSM, data and dictionary version visible",
        ],
    },
    {
        "stage": 2,
        "name": "parameter-revision",
        "title": "规则参数修订 (rules-1.0 -> rules-2.0)",
        "action": {"type": "use-rule", "version": "rules-2.0"},
        "assertions": ["only rate analyses invalidate; decoded words untouched"],
    },
    {
        "stage": 3,
        "name": "local-source-edit",
        "title": "只影响局部依赖的源数据改动",
        "action": {"type": "material-version", "change": "flip parity of w-007",
                   "target_word": "w-007"},
        "assertions": [
            "only decoded:w-007 and aggregate:ch-A/203 are invalidated",
            "rate and other labels keep their conclusions",
        ],
    },
    {
        "stage": 4,
        "name": "conflicting-decisions",
        "title": "互相冲突的人工决定",
        "action": {"type": "conflict", "object": "rate:ch-A",
                   "candidates": ["12500", "100000"]},
        "assertions": [
            "second submit sees conflict and both diffs",
            "undo restores the previous branch state",
        ],
    },
]


def manifest(words):
    return {
        "id": "arinc429-word-decoding-bench",
        "title": "字路译码台：ARINC 429 标签、奇偶与速率分段证据",
        "domain": "ARINC 429",
        "casepack_version": "1.0.0",
        "narrative": [
            "航电数据小组导入时间戳与32位原始字、通道配置和标签字典；",
            "审阅字序、速率分段和工程值解释，所有结论保留来源链。",
        ],
        "rules": [
            "label 的八进制展示不改变底层位",
            "BNR/BCD/discrete 解释由字典版本决定",
            "速率变化与采集缺口保留分段候选",
            "坏奇偶字不进入聚合值",
            "无法唯一决定时保留全部合法候选",
        ],
        "words": words,
        "mutations": MUTATIONS,
        "replay_stages": REPLAY_STAGES,
        "expectations": {
            "channels": ["ch-A", "ch-B"],
            "ch_a_segment_rates_bps": [12500, 100000],
            "acquisition_gap_min_us": 20000,
            "baseline_counts": {
                "words": len(words),
                "bad_parity": 1,
                "illegal_bcd": 1,
                "negative_bnr": 2,
                "ssm_invalid_bnr": 1,
            },
        },
    }


def main():
    out = ROOT
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    words = build_words()
    with open(os.path.join(out, "words.json"), "w", encoding="utf-8") as f:
        json.dump(words, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out, "channels.json"), "w", encoding="utf-8") as f:
        json.dump(channels(), f, ensure_ascii=False, indent=2)
    v1, v2 = dictionaries()
    for d in (v1, v2):
        with open(os.path.join(out, "dictionary-%s.json" % d["version"]), "w",
                  encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
    r1, r2 = rules()
    for r in (r1, r2):
        with open(os.path.join(out, "rules-%s.json" % r["version"]), "w",
                  encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest(words), f, ensure_ascii=False, indent=2)
    print("wrote", len(words), "words to", os.path.abspath(out))
    for w in words:
        print(w["id"], w["channel"], w["receive_time_us"], w["raw_text"], w["role"])


if __name__ == "__main__":
    main()
