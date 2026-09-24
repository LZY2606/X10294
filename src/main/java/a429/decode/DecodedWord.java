package a429.decode;

/** Result of parsing one raw word under one channel configuration. */
public record DecodedWord(
        String wordId,
        String channel,
        int seq,
        String tsRaw,
        long tsMicros,
        long rawBits,
        String rawHex,
        boolean parityOk,
        int labelRawField,      // bits 8..1 as transmitted, integer value
        String labelRawOctal,   // octal rendering of the raw field
        String labelOctal,      // octal label after channel bit-order convention
        String labelBitOrder,   // STD or MSB applied
        int sdi,                // bits 10..9 MSB-first
        int dataRaw,            // bits 29..11 MSB-first (19 bits)
        int ssmRaw,             // bits 31..30 MSB-first
        int parityBit           // bit 32
) { }
