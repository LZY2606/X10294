package avdata.a429.casepack;

import avdata.a429.decode.Bits;
import avdata.a429.decode.Parity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, domain-specific offline evidence pack:
 * "字路译码台：ARINC 429 标签、奇偶与速率分段证据".
 *
 * <p>Words are assembled field by field in transmission order and given correct
 * odd parity, except for the deliberately bad-parity specimen. The pack covers:
 * bit-order reversal, parity error, octal label display, BNR negative value,
 * BCD illegal digit, rate switch (acquisition boundary) and invalid SSM.</p>
 */
public final class CasePack {
    private CasePack() {}

    public static final String PACK_NAME = "word-decoder-evidence";
    public static final String CHANNEL = "CH-A";
    public static final String DICT_V1 = "dict-label-v1";
    public static final String DICT_V2 = "dict-label-v2";
    public static final String CFG_V1 = "channel-cfg-v1";
    public static final String CFG_V2 = "channel-cfg-v2";

    public record Specimen(String id, long seq, long receivedAtUs, int word,
                           String rawRepr, String scenario, String note) {}

    /** Set a field (1-based transmission order) and return the new word. */
    static int setField(int word, int lsb, int msb, int value) {
        int width = msb - lsb + 1;
        int mask = width == 32 ? -1 : ((1 << width) - 1);
        int cleared = word & ~(mask << (lsb - 1));
        return cleared | ((value & mask) << (lsb - 1));
    }

    static int mkWord(String documentOctalLabel, String sdi, String ssm, int data11_29) {
        int octal = 0;
        for (char c : documentOctalLabel.toCharArray()) {
            octal = octal * 8 + (c - '0');
        }
        int labelBits = Bits.reverse8(octal & 0xFF);
        int w = 0;
        w = setField(w, 1, 8, labelBits);
        w = setField(w, 9, 10, Integer.parseInt(new StringBuilder(sdi).reverse().toString(), 2));
        w = setField(w, 11, 29, data11_29);
        w = setField(w, 30, 31, Integer.parseInt(new StringBuilder(ssm).reverse().toString(), 2));
        w = Parity.withOddParity(w);
        return w;
    }

    /** Corrupt parity by flipping bit 32 only. */
    static int breakParity(int w) {
        return w ^ 0x80000000;
    }

    public static List<Specimen> specimens() {
        List<Specimen> out = new ArrayList<>();
        long t0 = 1_000_000L; // 1 s in us
        int i = 0;

        // baseline normal altitude, label 307, BNR +100 ft (res 1 ft)
        int alt = mkWord("307", "00", "11", 100);
        out.add(new Specimen("s-alt-ok", i++, t0, alt, Bits.hex8(alt),
                "BASELINE", "normal BNR altitude +100 ft"));

        // BNR negative: -25 ft => two's complement over 19 bits
        int neg = mkWord("307", "00", "11", (1 << 19) - 25);
        out.add(new Specimen("s-alt-neg", i++, t0 + 2_564, neg, Bits.hex8(neg),
                "BNR_NEGATIVE", "two's-complement altitude -25 ft"));

        // octal label display specimen: label 203 transmission vs reversed 304
        int oct = mkWord("203", "00", "11", 42);
        out.add(new Specimen("s-octal-label", i++, t0 + 2 * 2_564L, oct, Bits.hex8(oct),
                "OCTAL_LABEL", "document octal 203; reversal changes only the display, not bits"));

        // bit-order reversal: the octal-label word with all 32 bits reversed
        int rev = Bits.reverse32(oct);
        out.add(new Specimen("s-bit-reversed", i++, t0 + 3 * 2_564L, rev, Bits.hex8(rev),
                "BIT_REVERSAL", "full 32-bit transmission-order reversal of s-octal-label"));

        // parity error: normal altitude word with parity bit flipped
        int bad = breakParity(alt);
        out.add(new Specimen("s-parity-bad", i++, t0 + 4 * 2_564L, bad, Bits.hex8(bad),
                "PARITY_ERROR", "bit 32 flipped; odd parity fails and value stays out of aggregation"));

        // BCD illegal digit: altitude label 204, data has nibble A
        int bcdBad = setField(mkWord("204", "00", "11", 0), 11, 14, 0xA);
        out.add(new Specimen("s-bcd-illegal", i++, t0 + 5 * 2_564L, bcdBad, Bits.hex8(bcdBad),
                "BCD_ILLEGAL_DIGIT", "lowest BCD nibble = 0xA; value left in place with diagnostic"));

        // SSM invalid: altitude with SSM 01 (no computed data)
        int ncd = mkWord("307", "00", "01", 500);
        out.add(new Specimen("s-ssm-invalid", i++, t0 + 6 * 2_564L, ncd, Bits.hex8(ncd),
                "SSM_INVALID", "SSM=01 NO_COMPUTED_DATA; engineering value excluded"));

        // ---- rate switch: first ~2.564 ms cadence, then high-speed cadence ----
        for (int k = 0; k < 4; k++) {
            int w = mkWord("307", "00", "11", 200 + k);
            long ts = t0 + 20_000L + k * 320L; // ~3125 words/s => 100 kbps segment
            out.add(new Specimen("s-fast-" + k, i++, ts, w, Bits.hex8(w),
                    "RATE_SWITCH", "high-speed segment after acquisition boundary"));
        }
        return out;
    }

    /** v1: altitude BNR ft, BCD numeric, a discrete word. */
    public static String dictionaryV1() {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry("307", "BNR", "BARO_ALTITUDE", "ft", "MSL", 1, 0, 11, 29, 29, List.of()));
        entries.add(entry("204", "BCD", "ALTITUDE_BCD", "ft", "MSL", 0, 0, 11, 26, 0, List.of()));
        entries.add(entry("203", "DISCRETE", "DISCRETE_A", "state", "", 0, 0, 11, 29, 0,
                List.of(Map.of("bit", 11, "meaning", "DISCRETE_BIT_11"))));
        return wrap(entries);
    }

    /** v2: reinterprets label 203 as BNR heading, showing version-driven meaning. */
    public static String dictionaryV2() {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry("307", "BNR", "BARO_ALTITUDE", "ft", "MSL", 0.5, 0, 11, 29, 29, List.of()));
        entries.add(entry("204", "BCD", "ALTITUDE_BCD", "ft", "MSL", 0, 0, 11, 26, 0, List.of()));
        entries.add(entry("203", "BNR", "MAGNETIC_HEADING", "deg", "MAGNETIC_NORTH",
                180.0 / (1 << 18), 0, 11, 28, 0, List.of()));
        return wrap(entries);
    }

    private static Map<String, Object> entry(String label, String enc, String name, String unit,
                                             String basis, double res, double off,
                                             int lsb, int msb, int signBit,
                                             List<Map<String, Object>> discrete) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("sdi", "*");
        m.put("encoding", enc);
        m.put("name", name);
        m.put("unit", unit);
        if (basis != null && !basis.isEmpty()) {
            m.put("basis", basis);
        }
        if (res != 0) {
            m.put("resolution", res);
        }
        if (off != 0) {
            m.put("offset", off);
        }
        m.put("dataLsb", lsb);
        m.put("dataMsb", msb);
        if (signBit > 0) {
            m.put("signBit", signBit);
        }
        if (!discrete.isEmpty()) {
            m.put("discrete", discrete);
        }
        return m;
    }

    private static String wrap(List<Map<String, Object>> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", entries);
        return avdata.a429.service.Json.to(root);
    }
}
