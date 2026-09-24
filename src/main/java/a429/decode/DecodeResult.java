package a429.decode;

import a429.domain.WordFields;

/**
 * Result of decoding one word in one proposed bit order.
 */
public record DecodeResult(
        int word,
        String bitOrder,
        WordFields fields,
        boolean configOrder) {
}
