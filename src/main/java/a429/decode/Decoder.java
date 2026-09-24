package a429.decode;

import a429.domain.ChannelConfig;
import a429.domain.WordFields;

/**
 * Stateless word-level decoder. The baseline decode always uses the order
 * declared by the channel configuration; the reversed order is additionally
 * tested so an inverted capture fragment remains diagnosable instead of being
 * silently cleaned at import.
 */
public final class Decoder {

    public DecodeResult decode(String wireHex, ChannelConfig channel) {
        int configured = (int) Long.parseLong(wireHex, 16);
        int ordered = channel.bitReversed() ? Bits429.reverse32(configured) : configured;
        return build(ordered, "config", true, wireHex);
    }

    public DecodeResult decodeAlternate(String wireHex, ChannelConfig channel) {
        int configured = (int) Long.parseLong(wireHex, 16);
        int reversed = channel.bitReversed() ? configured : Bits429.reverse32(configured);
        return build(reversed, "reversed", false, wireHex);
    }

    private DecodeResult build(int word, String order, boolean configOrder, String wireHex) {
        int label = Bits429.labelField(word);
        WordFields fields = new WordFields(
                wireHex,
                order,
                label,
                Bits429.labelOctal(label),
                Bits429.sdi(word),
                Bits429.ssm(word),
                String.format("%05X", Bits429.dataBits(word)),
                Bits429.parityBit(word),
                Bits429.oddParityValid(word));
        return new DecodeResult(word, order, fields, configOrder);
    }
}
