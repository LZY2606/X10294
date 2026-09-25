package avdata.a429.decode;

/** Two's complement BNR conversion. All magnitudes carry the entry's unit. */
public final class Bnr {
    private Bnr() {}

    public record Result(double value, long rawMagnitude, boolean negative, String rawText) {}

    public static Result decode(int word, DictionaryEntry entry) {
        int width = entry.dataMsb - entry.dataLsb + 1;
        long mask = width >= 32 ? 0xFFFFFFFFL : (1L << width) - 1L;
        long magnitude = ((long) word >>> (entry.dataLsb - 1)) & mask;
        boolean negative = entry.signBit > 0 && Bits.bit(word, entry.signBit) == 1;
        long signed = magnitude;
        if (negative) {
            signed = magnitude - (1L << width);
        }
        double value = entry.offset + signed * entry.resolution;
        String raw = Long.toBinaryString(magnitude);
        return new Result(value, magnitude, negative, raw);
    }
}
