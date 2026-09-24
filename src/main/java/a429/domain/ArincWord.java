package a429.domain;

/**
 * Immutable view of one raw ARINC 429 32-bit word.
 * The original representation (hex string as imported) is preserved by the
 * store layer; this class only interprets the 32-bit value.
 *
 * Bit layout (bit 1 = LSB of the 32-bit word):
 *   bits 1-8   label (displayed in octal; octal display never changes bits)
 *   bits 9-10  SDI
 *   bits 11-29 data field (19 bits)
 *   bits 30-31 SSM
 *   bit  32    parity (odd parity over all 32 bits)
 */
public final class ArincWord {
    public final long raw; // unsigned 32-bit value kept in a long

    public ArincWord(long raw) {
        if (raw < 0 || raw > 0xFFFF_FFFFL) throw new IllegalArgumentException("not a 32-bit word: " + raw);
        this.raw = raw;
    }

    public static ArincWord parse(String hex) {
        String s = hex.trim().toLowerCase();
        if (s.startsWith("0x")) s = s.substring(2);
        return new ArincWord(Long.parseLong(s, 16));
    }

    public int labelByte() { return (int) (raw & 0xFF); }

    /** Octal rendering of the label byte. Purely a display transform. */
    public String labelOctal() { return String.format("%03o", labelByte()); }

    public int sdi() { return (int) ((raw >> 8) & 0x3); }

    /** 19-bit data field, bits 11..29. */
    public long dataField() { return (raw >> 10) & 0x7_FFFFL; }

    public int ssm() { return (int) ((raw >> 29) & 0x3); }

    public int parityBit() { return (int) ((raw >> 31) & 1); }

    /** ARINC 429 uses odd parity across all 32 bits. */
    public boolean parityOk() { return (Long.bitCount(raw) & 1) == 1; }

    public String ssmName() {
        return switch (ssm()) {
            case 0 -> "PLUS_OR_FW";
            case 1 -> "NO_COMPUTED_DATA";
            case 2 -> "FUNCTIONAL_TEST";
            default -> "NORMAL_OR_MINUS";
        };
    }

    /** Reverse all 32 bits (bit-order reversal counterexample). */
    public ArincWord reversed() {
        return new ArincWord(Long.reverse(raw) >>> 32);
    }

    public String hex() { return String.format("0x%08X", raw); }

    /** bits[0] = bit 32 (parity, leftmost on the wire diagram). */
    public int[] bitsMsbFirst() {
        int[] b = new int[32];
        for (int i = 0; i < 32; i++) b[i] = (int) ((raw >> (31 - i)) & 1);
        return b;
    }
}
