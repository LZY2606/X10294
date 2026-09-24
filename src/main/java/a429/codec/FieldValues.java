package a429.codec;

/**
 * Decoders for the 19-bit data field. Decoding is a pure derivation from the stored bits and
 * the dictionary definition; nothing here mutates the raw word.
 */
public final class FieldValues {
    private FieldValues() {}

    /**
     * Signed BNR interpretation of {@code bitCount} consecutive data bits (MSB-aligned within
     * the 19-bit field). Range is [-fullScale, fullScale * (1 - 1/2^(bitCount-1))).
     * Sign uses two's complement.
     */
    public static double bnr(int data19, int bitCount, double fullScale) {
        if (bitCount <= 0 || bitCount > 19) {
            throw new IllegalArgumentException("BNR bit count out of range: " + bitCount);
        }
        int shift = Words.DATA_BITS - bitCount;
        int mask = (1 << bitCount) - 1;
        int aligned = (data19 >>> shift) & mask;
        int signed = (aligned << (Integer.SIZE - bitCount)) >> (Integer.SIZE - bitCount);
        return (signed / (double) (1 << (bitCount - 1))) * fullScale;
    }

    /**
     * Packed BCD: four 4-bit digits occupying data bits 14-29 (MS nibble first). Bits 11-13 are
     * not part of the digits and stay untouched in the stored word.
     * Returns the raw nibbles; callers check each is a decimal digit 0-9.
     */
    public static int[] bcdDigits(int data19) {
        int[] digits = new int[4];
        for (int i = 0; i < 4; i++) {
            digits[i] = (data19 >>> (13 - i * 4)) & 0xF;
        }
        return digits;
    }

    public static boolean bcdDigitsValid(int[] digits) {
        for (int digit : digits) {
            if (digit > 9) {
                return false;
            }
        }
        return true;
    }

    public static int bcdMagnitude(int[] digits) {
        int value = 0;
        for (int digit : digits) {
            value = value * 10 + digit;
        }
        return value;
    }

    public static int discreteBit(int data19, int dataBitIndex1to19) {
        if (dataBitIndex1to19 < 1 || dataBitIndex1to19 > 19) {
            throw new IllegalArgumentException("discrete bit index must be 1..19");
        }
        return (data19 >>> (dataBitIndex1to19 - 1)) & 0x1;
    }
}
