package a429.decode;

/** One imported 32-bit word. Raw representations are preserved verbatim. */
public record RawWord(
        String wordId,
        String importId,
        int seq,              // in-situ sequence index inside the import (0 based)
        String tsRaw,         // timestamp exactly as imported
        long tsMicros,        // normalized timestamp, microseconds since epoch
        String channel,
        String wordRaw,       // word exactly as imported (hex/octal text)
        long word             // packed 32-bit word in bit1..bit32 order
) { }
