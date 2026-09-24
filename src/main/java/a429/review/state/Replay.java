package a429.review.state;

import java.util.List;

/** Immutable imported source material; raw representations are never rewritten. */
public record RawWord(String materialId,
                      int ordinal,
                      String rawHex,
                      String timestampRaw,
                      long timeNs,
                      String channel) {
}

/** A single-source source revision: replaces one word's 32 bits only. */
public record SourcePatch(String targetWordUid,
                          String newRawHex,
                          int patchSeq,
                          String reason) {
}

/** A direct-source word injected into the material during a replay mutation. */
public record InjectedWord(String materialId,
                           int ordinal,
                           String rawHex,
                           String timestampRaw,
                           long timeNs,
                           String channel,
                           int patchSeq,
                           String reason) {
}

/** Material bundle used by the replay engine. */
public record Material(String materialId,
                       String caseId,
                       List<RawWord> rawWords,
                       List<SourcePatch> patches,
                       List<InjectedWord> injected) {
}
