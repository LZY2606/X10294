package gsb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARINC 429 word decoding. Raw layer keeps the original 32-bit word untouched;
 * all normalization happens here in the derived layer.
 *
 * Bit numbering: bit 1 is the first transmitted bit (LSB of the 32-bit word),
 * bit 32 is the parity bit. Label octal display never changes underlying bits.
 */
public final class Arinc {

    private Arinc() {}

    /** Raw word as stored in the raw layer (immutable fact). */
    public record RawWord(String id, int seq, double recvTimeMs, String channel, long raw32) {}

    /** Dictionary entry; interpretation is decided by dictionary version. */
    public record DictEntry(String dictVersion, String labelOct, String name, String encoding,
                            double scale, String unit, String ssmConvention, List<Integer> validSsm) {}

    /** Result of decoding one word under one dictionary version. */
    public record Decoded(
            String labelOct,          // octal display of bits 1-8 (display only)
            String labelOctReversed,  // octal if the label byte were bit-reversed
            int sdi,
            int ssm,
            long dataField,           // bits 11-29
            boolean parityOk,
            String encoding,          // BNR / BCD / DISC / NONE (dictionary miss)
            String status,            // OK / PARITY_BAD / SSM_INVALID / BCD_ILLEGAL / NO_DICT_ENTRY
            boolean engineeringValid, // may enter aggregation
            Double engineeringValue,  // null when not convertible; raw stays in place
            String unit,
            String diagnostic,
            String dictVersion,
            boolean dictHit
    ) {}

    public static int bit(long raw, int bit1to32) {
        return (int) ((raw >>> (bit1to32 - 1)) & 1L);
    }

    public static int labelByte(long raw) {
        return (int) (raw & 0xFFL);
    }

    public static int reverse8(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | ((b >>> i) & 1);
        }
        return r;
    }

    /** Octal display of the label; pure presentation, never alters the stored bits. */
    public static String labelOctal(long raw) {
        return String.format(Locale.ROOT, "%03o", labelByte(raw));
    }

    public static String labelOctalReversed(long raw) {
        return String.format(Locale.ROOT, "%03o", reverse8(labelByte(raw)));
    }

    public static int sdi(long raw) {
        return (int) ((raw >>> 8) & 0x3L);
    }

    public static int ssm(long raw) {
        return (int) ((raw >>> 29) & 0x3L);
    }

    public static long dataField(long raw) {
        return (raw >>> 10) & 0x7FFFFL; // bits 11-29
    }

    public static boolean parityOk(long raw) {
        int ones = Long.bitCount(raw & 0xFFFFFFFFL);
        return (ones & 1) == 1; // ARINC 429 odd parity
    }

    /** Decode a word against one dictionary version. Never mutates the raw word. */
    public static Decoded decode(RawWord w, DictEntry entry) {
        String labelOct = labelOctal(w.raw32());
        String labelRev = labelOctalReversed(w.raw32());
        int sdi = sdi(w.raw32());
        int ssm = ssm(w.raw32());
        long data = dataField(w.raw32());
        boolean parity = parityOk(w.raw32());

        if (entry == null) {
            return new Decoded(labelOct, labelRev, sdi, ssm, data, parity,
                    "NONE", parity ? "NO_DICT_ENTRY" : "PARITY_BAD", false, null, "",
                    "no dictionary entry for label " + labelOct, "", false);
        }

        StringBuilder diag = new StringBuilder();
        boolean valid = true;
        String status = "OK";
        Double eng = null;

        switch (entry.encoding()) {
            case "BNR" -> {
                long signed = (data & 0x40000L) != 0 ? data - 0x80000L : data; // 19-bit two's complement
                eng = signed * entry.scale();
                if ("SIGN".equals(entry.ssmConvention())) {
                    // sign matrix: 00 = plus, 11 = minus, 01 = NCD, 10 = functional test
                    if (ssm == 3) eng = -Math.abs(eng);
                    if (ssm == 0) eng = Math.abs(eng);
                }
            }
            case "BCD" -> {
                long value = 0;
                boolean illegal = false;
                for (int nib = 3; nib >= 0; nib--) {
                    int digit = (int) ((data >>> (nib * 4)) & 0xFL);
                    if (digit > 9) {
                        illegal = true;
                        diag.append("illegal BCD digit 0x").append(Integer.toHexString(digit).toUpperCase(Locale.ROOT))
                                .append(" at nibble ").append(nib).append("; ");
                    }
                    value = value * 10 + Math.min(digit, 9);
                }
                if (illegal) {
                    valid = false;
                    status = "BCD_ILLEGAL";
                    eng = null; // unconvertible value stays raw, with diagnosis
                } else {
                    eng = value * entry.scale();
                }
            }
            case "DISC" -> eng = (double) data; // bitmap, carried as integer value
            default -> {
                valid = false;
                status = "NO_DICT_ENTRY";
                diag.append("unknown encoding ").append(entry.encoding());
            }
        }

        if (valid && !entry.validSsm().contains(ssm)) {
            valid = false;
            status = "SSM_INVALID";
            diag.append("ssm ").append(ssm).append(" not valid under ")
                    .append(entry.ssmConvention()).append(" convention; ");
            eng = null;
        }

        if (!parity) {
            valid = false; // bad-parity words never enter aggregated values
            status = "PARITY_BAD";
            diag.append("odd parity check failed; excluded from aggregation; ");
        }

        return new Decoded(labelOct, labelRev, sdi, ssm, data, parity,
                entry.encoding(), status, valid, eng, valid ? entry.unit() : "",
                diag.toString().trim(), entry.dictVersion(), true);
    }

    /** A segmentation/rate candidate; multiple candidates are kept until a decision event rules on them. */
    public record RateCandidate(String id, String type, // GAP or RATE_SWITCH
                                String channel, String labelOct,
                                int afterSeq, double gapMs,
                                double beforeRateHz, double afterRateHz,
                                String status) {}

    /** Compute inter-word gaps and rate candidates for one channel+label stream. */
    public static List<RateCandidate> rateCandidates(List<RawWord> stream, String channel, String labelOct) {
        List<RateCandidate> out = new ArrayList<>();
        if (stream.size() < 3) return out;
        double[] dt = new double[stream.size() - 1];
        for (int i = 0; i < dt.length; i++) {
            dt[i] = stream.get(i + 1).recvTimeMs() - stream.get(i).recvTimeMs();
        }
        double median = median(dt);
        if (median <= 0) return out;
        for (int i = 0; i < dt.length; i++) {
            int afterSeq = stream.get(i + 1).seq();
            if (dt[i] > 2.5 * median) {
                out.add(new RateCandidate(
                        "rc-" + channel + "-" + labelOct + "-" + afterSeq + "-gap",
                        "GAP", channel, labelOct, afterSeq, dt[i],
                        1000.0 / median, 0.0, "PENDING"));
                continue;
            }
            if (i >= 2 && i <= dt.length - 3) {
                double before = median(java.util.Arrays.copyOfRange(dt, Math.max(0, i - 3), i));
                double after = median(java.util.Arrays.copyOfRange(dt, i + 1, Math.min(dt.length, i + 4)));
                if (before > 0 && after > 0 && Math.abs(after - before) / before > 0.20) {
                    out.add(new RateCandidate(
                            "rc-" + channel + "-" + labelOct + "-" + afterSeq + "-rate",
                            "RATE_SWITCH", channel, labelOct, afterSeq, dt[i],
                            1000.0 / before, 1000.0 / after, "PENDING"));
                }
            }
        }
        return out;
    }

    /** Adjacent-word interval table (ms), derived view only. */
    public static List<double[]> gaps(List<RawWord> stream) {
        List<double[]> g = new ArrayList<>();
        for (int i = 1; i < stream.size(); i++) {
            g.add(new double[]{stream.get(i - 1).seq(), stream.get(i).seq(),
                    stream.get(i).recvTimeMs() - stream.get(i - 1).recvTimeMs()});
        }
        return g;
    }

    /** Mean of engineering-valid words only; bad-parity words are excluded upstream. */
    public static Double aggregate(List<Decoded> decoded) {
        double sum = 0;
        int n = 0;
        for (Decoded d : decoded) {
            if (d.engineeringValid() && d.engineeringValue() != null) {
                sum += d.engineeringValue();
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        java.util.Arrays.sort(c);
        return c.length == 0 ? 0 : c[c.length / 2];
    }

    /** Build a 32-bit word from fields; helper for fixtures and the case generator. */
    public static long buildWord(int labelByte, int sdi, long dataField, int ssm, boolean forceBadParity) {
        long raw = (labelByte & 0xFFL)
                | ((sdi & 0x3L) << 8)
                | ((dataField & 0x7FFFFL) << 10)
                | ((ssm & 0x3L) << 29);
        int ones = Long.bitCount(raw);
        if (((ones & 1) == 1) == forceBadParity) {
            raw |= 0x80000000L; // set parity bit to reach odd parity (or break it)
        }
        return raw & 0xFFFFFFFFL;
    }
}
