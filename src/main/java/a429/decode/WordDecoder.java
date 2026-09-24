package a429.decode;

/** Stateless structural decoding of a 32-bit ARINC 429 word. */
public final class WordDecoder {
    private WordDecoder() { }

    public static DecodedWord decode(RawWord rw, ChannelConfig cfg) {
        long w = rw.word();
        int labelRaw = Bits.fieldLsb(w, 8, 1);
        int label = cfg.standardLabelOrder() ? labelRaw : Bits.reverse(labelRaw, 8);
        int sdi = Bits.fieldMsb(w, 10, 9);
        int data = Bits.fieldMsb(w, 29, 11);
        int ssm = Bits.fieldMsb(w, 31, 30);

        return new DecodedWord(
                rw.wordId(), rw.channel(), rw.seq(), rw.tsRaw(), rw.tsMicros(),
                w, String.format("%08X", w),
                Bits.oddParityValid(w),
                labelRaw, String.format("%03o", labelRaw),
                String.format("%03o", label),
                cfg.standardLabelOrder() ? "STD" : "MSB",
                sdi, data, ssm, Bits.bit(w, 32)
        );
    }

    /** SSM interpretation depends on the data encoding declared by the dictionary. */
    public static String ssmState(int ssm, Encoding encoding) {
        boolean bnr = encoding == Encoding.BNR;
        if (bnr) {
            return switch (ssm) {
                case 0b00 -> "FWC";            // Failure Warning
                case 0b01 -> "NCD";            // No Computed Data
                case 0b10 -> "FUNCTIONAL_TEST";
                default -> "NOMINAL";
            };
        }
        return switch (ssm) {
            case 0b00 -> "FWC";
            case 0b01 -> "NCD";
            case 0b10 -> "NOMINAL";
            case 0b11 -> "FUNCTIONAL_TEST";
            default -> throw new IllegalStateException();
        };
    }
}
