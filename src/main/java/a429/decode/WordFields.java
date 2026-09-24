package a429.decode;

/**
 * Structured view of a word. These are derivations shown in the review UI; the raw bits remain
 * the source of truth.
 */
public record WordFields(
        int labelFieldHex,
        String labelOctal,
        int sdi,
        int ssmBits,
        String ssmName,
        int dataHex,
        boolean parityOk,
        boolean bitReversed) {
}
