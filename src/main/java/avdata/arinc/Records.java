package avdata.arinc;

import java.util.List;
import java.util.Map;

/** Immutable domain records. Source layer is never mutated; interpretation lives on derived
 *  layers keyed by rule version and branch. */
public final class Records {

    private Records() {
    }

    /** Source word: every imported field is preserved verbatim. */
    public static final class Word {
        public final String uid;
        public final String materialId;
        public final int seq;                  // in-place sequence
        public final long receivedEpochMs;     // receive time
        public final String receivedText;      // original representation
        public final String channel;
        public final String rawHex;            // 32-bit raw word, original representation
        public final boolean reversedWords;    // source-level bit-order-reversal flag
        public final Boolean importParity;     // parity result supplied on import (may be null)
        public final String dictionaryVersionHint;

        public Word(String uid, String materialId, int seq, long receivedEpochMs,
                    String receivedText, String channel, String rawHex, boolean reversedWords,
                    Boolean importParity, String dictionaryVersionHint) {
            this.uid = uid;
            this.materialId = materialId;
            this.seq = seq;
            this.receivedEpochMs = receivedEpochMs;
            this.receivedText = receivedText;
            this.channel = channel;
            this.rawHex = rawHex;
            this.reversedWords = reversedWords;
            this.importParity = importParity;
            this.dictionaryVersionHint = dictionaryVersionHint;
        }
    }

    public static final class ChannelConfig {
        public final String channel;
        public final double nominalKbps;       // configured nominal link rate
        public final double scheduleSlotMs;
        public final String polarity;          // "ODD"

        public ChannelConfig(String channel, double nominalKbps, double scheduleSlotMs,
                             String polarity) {
            this.channel = channel;
            this.nominalKbps = nominalKbps;
            this.scheduleSlotMs = scheduleSlotMs;
            this.polarity = polarity;
        }
    }

    public static final class DictionaryVersion {
        public final String version;
        public final ArincCodec.SsmRuleSet ssmRules;
        public final String note;
        public final List<ArincCodec.LabelDef> labels;

        public DictionaryVersion(String version, ArincCodec.SsmRuleSet ssmRules, String note,
                                 List<ArincCodec.LabelDef> labels) {
            this.version = version;
            this.ssmRules = ssmRules;
            this.note = note;
            this.labels = List.copyOf(labels);
        }

        public ArincCodec.LabelDef find(String octalText, int sdi) {
            for (ArincCodec.LabelDef def : labels) {
                if (def.matches(octalText, sdi)) {
                    return def;
                }
            }
            return null;
        }
    }

    /** Provenance-tagged event. Events are append-only; replaying them reconstructs state. */
    public static final class Event {
        public final String uid;
        public final long epochMs;
        public final String branchId;
        public final String type;
        public final String parentEventId;     // branch base / previous head
        public final String summary;
        public final Map<String, Object> payload;
        public final String prevHash;          // hash chain
        public final String selfHash;
        /** Base version of dependencies this conclusion was computed under. */
        public final String depBasis;

        public Event(String uid, long epochMs, String branchId, String type, String parentEventId,
                     String summary, Map<String, Object> payload, String prevHash, String selfHash,
                     String depBasis) {
            this.uid = uid;
            this.epochMs = epochMs;
            this.branchId = branchId;
            this.type = type;
            this.parentEventId = parentEventId;
            this.summary = summary;
            this.payload = payload;
            this.prevHash = prevHash;
            this.selfHash = selfHash;
            this.depBasis = depBasis;
        }
    }
}
