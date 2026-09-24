package org.avionics.review.casepack;

import org.avionics.review.domain.Arinc429;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the in-repository fixed case pack.
 *
 * <p>The scenario is hand authored against concrete ARINC 429 labels instead
 * of a generic spreadsheet. All words are encoded with the same bit
 * conventions the decoder reads, and parity is fixed to odd.</p>
 */
public final class CasePackFactory {

    public static final String DICT_V1 = "wordlabel-dict-v1";
    public static final String DICT_V2 = "wordlabel-dict-v2";
    public static final String IMPORT_ID = "pack-baseline-001";

    private CasePackFactory() {
    }

    public static CasePack build() {
        List<CasePack.ChannelDto> channels = List.of(
                new CasePack.ChannelDto("CH_A", "Left Bus A", 12.5, "ARINC429-TX-A"),
                new CasePack.ChannelDto("CH_B", "Right Bus B", 100.0, "ARINC429-TX-B"));

        List<CasePack.DictDto> dicts = List.of(dictV1(), dictV2());

        List<CasePack.WordDto> words = List.of(
                word(0, 0.000, "CH_A", pitch(-12.5, DICT_V1, 0)),
                word(1, 0.080, "CH_A", altitude(2500, 0)),
                word(2, 0.160, "CH_A", freq(121, 5, 0)),
                word(3, 0.240, "CH_A", discrete(1, false)),
                word(4, 0.320, "CH_A", ias(180, 0)),
                word(5, 0.400, "CH_A", pitch(5.0, DICT_V1, 0)),
                word(6, 0.480, "CH_A", altitude(2510, 0)),
                word(7, 0.560, "CH_A", freq(121, 7, 0)),
                word(8, 8.000, "CH_A", pitch(5.2, DICT_V1, 0)),
                word(9, 8.500, "CH_A", altitude(2520, 0)),
                word(10, 9.000, "CH_A", freq(136, 0, 0)),
                word(11, 10.000, "CH_A", pitch(5.4, DICT_V1, 0)),
                word(12, 10.500, "CH_A", altitude(2530, 0)),
                word(13, 0.000, "CH_B", altitude(31000, 2)),
                word(14, 0.010, "CH_B", ias(220, 0)),
                word(15, 0.020, "CH_B", altitude(31010, 2)),
                word(16, 0.030, "CH_B", ias(221, 0)),
                word(17, 0.040, "CH_B", altitude(31020, 2)));

        List<CasePack.ProbeDto> probes = List.of(
                probeBitReversal(words), probeParityError(words), probeOctalLabel(words),
                probeBnrNegative(words), probeBcdIllegal(words), probeRateSwitch(words), probeSsmInvalid(words));

        List<CasePack.JudgementDto> judgements = List.of(
                new CasePack.JudgementDto("octal_label_preserves_bits",
                        "Octal label rendering is presentation of the same 8 on-wire bits and never rewrites the raw word.",
                        "OCTAL_LABEL_BASIS"),
                new CasePack.JudgementDto("encoding_by_dict_version",
                        "BNR, BCD and discrete interpretations are chosen by the cited dictionary version.",
                        "ENCODING_DICT_VERSION_BASIS"),
                new CasePack.JudgementDto("rate_segments_keep_candidates",
                        "Rate changes and acquisition gaps produce segmentation candidates that all remain on record.",
                        "RATE_CANDIDATE_BASIS"),
                new CasePack.JudgementDto("bad_parity_excluded",
                        "Bad parity words stay visible with diagnostics but never enter an aggregate engineering value.",
                        "BAD_PARITY_EXCLUSION_BASIS"));

        return new CasePack("arinc429-word-decoder-bench",
                "字路译码台：ARINC 429 标签、奇偶与速率分段证据",
                DICT_V1, channels, dicts, words, probes, judgements);
    }

    private static CasePack.DictDto dictV1() {
        Map<String, String> disc = new LinkedHashMap<>();
        disc.put("11", "AP_DISENGAGED");
        disc.put("12", "YD_DISENGAGED");
        disc.put("13", "GLIDESLOPE_CAPTURE");
        return new CasePack.DictDto(DICT_V1, "2024-01-15", "SIGNED_SYMMETRIC", "BODY_AXIS",
                List.of(
                        new CasePack.DefDto("310", "PITCH_ATTITUDE", "BNR", "deg",
                                180.0, 28, 11, 0, Map.of()),
                        new CasePack.DefDto("203", "VHF_SELECTED_FREQ", "BCD", "MHz",
                                0, 28, 13, 2, Map.of()),
                        new CasePack.DefDto("270", "AP_STATUS", "DISCRETE", "state",
                                0, 28, 11, 0, disc),
                        new CasePack.DefDto("204", "ALTITUDE", "BNR", "ft",
                                65536.0, 28, 11, 0, Map.of()),
                        new CasePack.DefDto("206", "INDICATED_AIRSPEED", "BNR", "kn",
                                1024.0, 28, 11, 0, Map.of())));
    }

    private static CasePack.DictDto dictV2() {
        Map<String, String> disc = new LinkedHashMap<>();
        disc.put("11", "AP_DISENGAGED");
        disc.put("12", "YD_DISENGAGED");
        disc.put("13", "GLIDESLOPE_CAPTURE");
        disc.put("14", "FLARE_ARMED");
        return new CasePack.DictDto(DICT_V2, "2025-06-01", "SIGNED_SYMMETRIC", "BODY_AXIS",
                List.of(
                        new CasePack.DefDto("310", "PITCH_ATTITUDE", "BNR", "deg",
                                90.0, 28, 11, 0, Map.of()),
                        new CasePack.DefDto("203", "VHF_SELECTED_FREQ", "BCD", "MHz",
                                0, 28, 13, 2, Map.of()),
                        new CasePack.DefDto("270", "AP_STATUS", "DISCRETE", "state",
                                0, 28, 11, 0, disc),
                        new CasePack.DefDto("204", "ALTITUDE", "BNR", "ft",
                                65536.0, 28, 11, 0, Map.of()),
                        new CasePack.DefDto("206", "INDICATED_AIRSPEED", "BNR", "kn",
                                1024.0, 28, 11, 0, Map.of())));
    }

    private static CasePack.WordDto word(long seq, double timeMs, String channel, long raw) {
        return new CasePack.WordDto(seq, timeMs, channel, Arinc429.toHex32(raw));
    }

    private static long labelBits(String octalLabel) {
        return Integer.parseInt(octalLabel, 8) & 0xFFL;
    }

    private static long pitch(double degrees, String dictVersion, int ssm) {
        long w = labelBits("310");
        long[] holder = {w};
        Arinc429.setBnrSigned(holder, 28, 11, 180.0, degrees);
        w = holder[0];
        w = Arinc429.setField(w, 30, 29, ssm);
        return Arinc429.forceParity(w);
    }

    private static long altitude(long feet, int ssm) {
        long w = labelBits("204");
        long[] holder = {w};
        Arinc429.setBnrSigned(holder, 28, 11, 65536.0, feet);
        w = holder[0];
        w = Arinc429.setField(w, 30, 29, ssm);
        return Arinc429.forceParity(w);
    }

    private static long ias(long knots, int ssm) {
        long w = labelBits("206");
        long[] holder = {w};
        Arinc429.setBnrSigned(holder, 28, 11, 1024.0, knots);
        w = holder[0];
        w = Arinc429.setField(w, 30, 29, ssm);
        return Arinc429.forceParity(w);
    }

    /** Four BCD digits across bits 28..13, implied decimal after two digits. */
    private static long freq(int hundreds, int fractionHundredths, int ssm) {
        long w = labelBits("203");
        int d1 = (hundreds / 100) % 10;
        int d2 = (hundreds / 10) % 10;
        int d3 = hundreds % 10;
        int d4 = fractionHundredths % 10;
        long[] holder = {w};
        Arinc429.setBcdDigit(holder, 28, 25, d1);
        Arinc429.setBcdDigit(holder, 24, 21, d2);
        Arinc429.setBcdDigit(holder, 20, 17, d3);
        Arinc429.setBcdDigit(holder, 16, 13, d4);
        w = holder[0];
        w = Arinc429.setField(w, 30, 29, ssm);
        return Arinc429.forceParity(w);
    }

    private static long discrete(int setBit, boolean invalidSsm) {
        long w = labelBits("270");
        if (setBit > 0) {
            w |= (1L << (setBit - 1));
        }
        w = Arinc429.setField(w, 30, 29, invalidSsm ? 0b11 : 0b00);
        return Arinc429.forceParity(w);
    }

    private static CasePack.ProbeDto probeBitReversal(List<CasePack.WordDto> base) {
        long raw = Long.parseLong(base.get(0).rawHex(), 16);
        long reversed = Arinc429.reverse32(raw);
        return new CasePack.ProbeDto("probe-bit-reversal", "BIT_REVERSAL",
                "位序反转变体",
                "Only the byte ordering of one direct source word is inverted; the reversed candidate must stay visible and scoped.",
                Map.of("w:" + IMPORT_ID + "#0", Arinc429.toHex32(reversed)),
                Map.of(),
                List.of(new CasePack.ExpectationDto(
                        "expect-reversed-candidate",
                        "The inverted feed yields a viable alternative convention for the same word SID only.",
                        List.of("ev:w:" + IMPORT_ID + "#0:BIT_REVERSED_32:*", "w:" + IMPORT_ID + "#0:probe@*"),
                        List.of("ev:w:" + IMPORT_ID + "#1:*", "rc:CH_A:*", "agg:*"),
                        List.of("BIT_REVERSAL_CANDIDATE_PRESERVED"),
                        List.of())));
    }

    private static CasePack.ProbeDto probeParityError(List<CasePack.WordDto> base) {
        long raw = Long.parseLong(base.get(1).rawHex(), 16);
        long broken = Arinc429.breakParity(raw);
        return new CasePack.ProbeDto("probe-parity-error", "PARITY_ERROR",
                "奇偶错误变体",
                "Flip the parity bit of one altitude word; the word remains in place but must be excluded from aggregates.",
                Map.of("w:" + IMPORT_ID + "#1", Arinc429.toHex32(broken)),
                Map.of(),
                List.of(new CasePack.ExpectationDto(
                        "expect-bad-parity-excluded",
                        "BAD_PARITY diagnostic on word #1 and the 204 aggregate reports one excluded word.",
                        List.of("ev:w:" + IMPORT_ID + "#1:*", "agg:204:*"),
                        List.of("agg:310:*", "agg:203:*"),
                        List.of("BAD_PARITY"),
                        List.of())));
    }

    private static CasePack.ProbeDto probeOctalLabel(List<CasePack.WordDto> base) {
        return new CasePack.ProbeDto("probe-octal-label", "OCTAL_LABEL",
                "八进制 label 展示",
                "Request octal rendering for all labels without touching any raw hex; raw bytes and parity are unchanged.",
                Map.of(), Map.of(),
                List.of(new CasePack.ExpectationDto(
                        "expect-octal-is-presentation-only",
                        "Every rawHex in the probe state equals the baseline and labels render as three octal digits.",
                        List.of(),
                        List.of("w:*"),
                        List.of("OCTAL_LABEL_BASIS"),
                        List.of())));
    }

    private static CasePack.ProbeDto probeBnrNegative(List<CasePack.WordDto> base) {
        long negativePitch = pitch(-45.0, DICT_V1, 0);
        return new CasePack.ProbeDto("probe-bnr-negative", "BNR_NEGATIVE",
                "BNR 负值变体",
                "Replace one pitch word with -45 deg two's complement; only pitch-derived values change.",
                Map.of("w:" + IMPORT_ID + "#5", Arinc429.toHex32(negativePitch)),
                Map.of(),
                List.of(new CasePack.ExpectationDto(
                        "expect-negative-bnr-scoped",
                        "Pitch decodes to a negative degree value; altitude, frequency and rate rows are untouched.",
                        List.of("ev:w:" + IMPORT_ID + "#5:*", "agg:310:*"),
                        List.of("agg:204:*", "agg:203:*", "rc:*"),
                        List.of("-45.0 deg"),
                        List.of())));
    }

    private static CasePack.ProbeDto probeBcdIllegal(List<CasePack.WordDto> base) {
        long bad = Long.parseLong(base.get(2).rawHex(), 16);
        bad = Arinc429.setField(bad, 24, 21, 0xA);
        bad = Arinc429.forceParity(bad);
        return new CasePack.ProbeDto("probe-bcd-illegal", "BCD_ILLEGAL",
                "BCD 非法数字变体",
        "Force nibble A into the second frequency digit; the value is kept in place with a BCD diagnostic.",
                Map.of("w:" + IMPORT_ID + "#2", Arinc429.toHex32(bad)),
                Map.of(),
                List.of(new CasePack.ExpectationDto(
                        "expect-illegal-digit-kept",
                        "Word #2 shows '?' in the BCD text and does not contribute a numeric aggregate.",
                        List.of("ev:w:" + IMPORT_ID + "#2:*", "agg:203:*"),
                        List.of("agg:310:*"),
                        List.of("BCD_ILLEGAL"),
                        List.of())));
    }

    private static CasePack.ProbeDto probeRateSwitch(List<CasePack.WordDto> base) {
        return new CasePack.ProbeDto("probe-rate-switch", "RATE_SWITCH",
                "速率切换变体",
                "Move later CH_A arrivals to a slower cadence so a rate switch joins the existing gap.",
                Map.of(),
                Map.of("w:" + IMPORT_ID + "#11", 11.000,
                        "w:" + IMPORT_ID + "#12", 12.000),
                List.of(new CasePack.ExpectationDto(
                        "expect-rate-segmentation",
                        "CH_A keeps all three segmentation candidates and the adaptive candidate becomes preferred.",
                        List.of("rc:CH_A:RATE_ADAPTIVE", "rs:CH_A:RATE_ADAPTIVE#*", "iv:CH_A#*"),
                        List.of("ev:*", "agg:*"),
                        List.of("RATE_CANDIDATE_BASIS"),
                        List.of())));
    }

    private static CasePack.ProbeDto probeSsmInvalid(List<CasePack.WordDto> base) {
        long invalid = discrete(1, true);
        return new CasePack.ProbeDto("probe-ssm-invalid", "SSM_INVALID",
                "SSM 无效状态变体",
                "Set the discrete SSM to functional failure for one AP status word.",
                Map.of("w:" + IMPORT_ID + "#3", Arinc429.toHex32(invalid)),
                Map.of(),
                List.of(new CasePack.ExpectationDto(
                        "expect-ssm-invalid-visible",
                        "Word #3 keeps its discrete text but is marked FUNCTIONAL_FAILURE/SSM_INVALID.",
                        List.of("ev:w:" + IMPORT_ID + "#3:*"),
                        List.of("ev:w:" + IMPORT_ID + "#4:*", "agg:*"),
                        List.of("SSM_INVALID"),
                        List.of())));
    }
}
