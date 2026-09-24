package a429.decode;

import a429.codec.SsmState;
import a429.codec.Words;

/**
 * Decodes one captured word for display. The {@code bitReversed} flag documents that capture was
 * performed with reversed bit order; the octal label shown after reversal is derived from the
 * same stored bits (octal presentation never changes the underlying word).
 */
public final class WordDecoder {
    private WordDecoder() {}

    public static WordFields decode(int storedWord, boolean reversedCapture) {
        int viewWord = reversedCapture ? Words.reverseWord(storedWord) : storedWord;
        return new WordFields(
                Words.labelField(viewWord) & 0xFF,
                Words.labelOctal(viewWord),
                Words.sdi(viewWord),
                Words.ssm(viewWord),
                SsmState.fromBits(Words.ssm(viewWord)).name(),
                Words.data(viewWord),
                Words.oddParityValid(storedWord),
                reversedCapture);
    }
}
