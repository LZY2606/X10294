package a429.domain;

/**
 * Immutable baseline fields decoded at import from a 32-bit word.
 * Bit numbering follows ARINC 429: bit 1 is the first transmitted bit and maps
 * to the least-significant bit of the captured integer.
 */
public record WordFields(
        String wireHex,
        String bitOrder,
        int labelFieldRaw,
        String labelOctal,
        int sdi,
        int ssm,
        String dataHex,
        boolean parityOdd,
        boolean parityValid) {
}
