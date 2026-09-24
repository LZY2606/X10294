package a429.domain;

/** Pair of an original word and its derived interpretation. */
public record DecodedWord(RawWord word, Decoded decoded) {
}
