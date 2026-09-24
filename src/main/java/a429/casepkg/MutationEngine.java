package a429.casepkg;

import a429.db.Json;
import a429.domain.Bits;
import a429.domain.RawWord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies exactly one declared direct-source alteration per counterexample.
 * Baseline words are never modified; a mutated copy map is returned.
 *
 * Operation kinds:
 *  REVERSE_32        - reverse all 32 bits (位序反转)
 *  FLIP_BITS         - flip listed ARINC bits (e.g. parity error)
 *  SET_FIELD         - overwrite an inclusive ARINC bit field (BNR sign, SSM, BCD nibble)
 *  SET_RECEIVE_TEXT  - replace the verbatim receive time of one word (rate switch)
 *  PRESENTATION_ONLY - no bit change; handled by the service via labelConvention
 */
public class MutationEngine {

    public record MutatedCase(Map<String, RawWord> words, RawWord mutatedWord,
                              String opKind, ObjectNode inputFragment,
                              String presentationOverride) {
    }

    public MutatedCase apply(CasePackage.MutationSpec mutation, JsonNode op,
                             Map<String, RawWord> baseline) {
        RawWord original = baseline.get(mutation.wordId());
        if (original == null) {
            throw new IllegalStateException("mutation " + mutation.id()
                    + " targets missing word " + mutation.wordId());
        }
        long raw = original.raw();
        String kind = op.path("kind").asText();
        String receiveText = original.receiveText();
        String presentationOverride = null;

        switch (kind) {
            case "REVERSE_32" -> raw = Bits.reverse32(raw);
            case "FLIP_BITS" -> {
                for (JsonNode bit : op.path("bits")) {
                    raw ^= 1L << (32 - bit.asInt());
                }
            }
            case "SET_FIELD" -> {
                int msb = op.path("msb").asInt();
                int lsb = op.path("lsb").asInt();
                int value = op.path("value").asInt();
                int width = msb - lsb + 1;
                int lsbIndex = 32 - lsb;
                long mask = (1L << width) - 1;
                raw = (raw & ~(mask << lsbIndex)) | ((long) (value & mask) << lsbIndex);
            }
            case "SET_RECEIVE_TEXT" -> receiveText = op.path("receiveText").asText();
            case "PRESENTATION_ONLY" -> presentationOverride = op.path("convention").asText();
            default -> throw new IllegalStateException("unknown mutation op: " + kind);
        }

        String rawHex = "0x" + String.format("%08X", raw);
        RawWord mutated = new RawWord(original.wordId(), original.seq(), receiveText,
                TimeStamp.parseEpochNanos(receiveText), original.channelId(), raw, rawHex);

        ObjectNode fragment = Json.obj();
        fragment.put("mutationId", mutation.id());
        fragment.put("opKind", kind);
        fragment.put("wordId", original.wordId());
        fragment.put("seq", original.seq());
        fragment.put("channelId", original.channelId());
        fragment.put("beforeHex", "0x" + String.format("%08X", original.raw()));
        fragment.put("afterHex", rawHex);
        fragment.put("beforeReceiveText", original.receiveText());
        fragment.put("afterReceiveText", receiveText);

        Map<String, RawWord> words = new LinkedHashMap<>(baseline);
        words.put(mutated.wordId(), mutated);
        return new MutatedCase(Map.copyOf(words), mutated, kind, fragment, presentationOverride);
    }
}
