package org.avionics.review.casepack;

import com.fasterxml.jackson.databind.JsonNode;
import org.avionics.review.domain.Derivation;
import org.avionics.review.domain.EngineeringValue;
import org.avionics.review.domain.Invalidation;
import org.avionics.review.domain.Interval;
import org.avionics.review.domain.RateCandidate;
import org.avionics.review.domain.RawWord;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the invalidation blast radius of an event from explicit
 * dependency edges - never a blanket recomputation.
 *
 * <p>Dictionary revisions touch only labels defined in the new version;
 * source patches touch one word chain; parameter changes touch only derived
 * rate objects; decisions touch their target SID.</p>
 */
public final class Invalidations {

    private Invalidations() {
    }

    public static List<Invalidation> forEvent(String eventId, String type, JsonNode payload,
                                              Derivation.Model before) {
        List<Invalidation> out = new ArrayList<>();
        switch (type) {
            case "IMPORT" -> out.add(new Invalidation(eventId, "BRANCH", "main",
                    "initial import establishes baseline"));
            case "DICT_REVISE" -> {
                String versionId = payload.get("dict").get("versionId").asText();
                JsonNode defs = payload.get("dict").get("defs");
                defs.forEach(def -> {
                    String label = def.get("labelOctal").asText();
                    out.add(new Invalidation(eventId, "ENGINEERING_VALUE",
                            "agg:" + label + ":*:" + versionId,
                            "dictionary revision " + versionId + " redefines label " + label));
                    if (before != null) {
                        for (Derivation.WordRow row : before.wordRows()) {
                            if (row.labelOctal().equals(label)) {
                                out.add(new Invalidation(eventId, "ENGINEERING_VALUE",
                                        "ev:" + row.word().sid() + ":*:" + versionId,
                                        "reinterpretation candidate under " + versionId));
                            }
                        }
                    }
                });
            }
            case "SOURCE_PATCH" -> {
                long seq = payload.get("seq").asLong();
                String importId = payload.path("importId").asText("*");
                String key = "w:" + importId + "#" + seq;
                out.add(new Invalidation(eventId, "RAW_WORD", key + ":rev@*",
                        "source patch replaces one direct source word"));
                out.add(new Invalidation(eventId, "ENGINEERING_VALUE", "ev:" + key + ":*",
                        "engineering values derived from patched word"));
                out.add(new Invalidation(eventId, "INTERVAL", "iv:*",
                        "adjacent intervals may change on the patched channel"));
                out.add(new Invalidation(eventId, "RATE_CANDIDATE", "rc:*",
                        "rate segmentation re-evaluated for the patched channel"));
                out.add(new Invalidation(eventId, "AGGREGATE", "agg:*",
                        "only aggregates sharing the patched word's label are recomputed"));
            }
            case "PARAM_CHANGE" -> {
                if (payload.has("gapThresholdMs")) {
                    out.add(new Invalidation(eventId, "INTERVAL", "iv:*",
                            "gap threshold change recomputes acquisition-gap flags"));
                }
                out.add(new Invalidation(eventId, "RATE_CANDIDATE", "rc:*",
                        "rate parameters change segmentation candidates"));
                out.add(new Invalidation(eventId, "RATE_SEGMENT", "rs:*",
                        "rate parameters change segment boundaries"));
            }
            case "DECISION" -> out.add(new Invalidation(eventId, "ENGINEERING_VALUE",
                    payload.get("targetSid").asText(),
                    "human adjudication overrides candidate selection"));
            case "UNDO_DECISION" -> out.add(new Invalidation(eventId, "ENGINEERING_VALUE",
                    "ev:*",
                    "undo restores the prior candidate branch"));
            case "PROBE" -> out.add(new Invalidation(eventId, "PROBE_BRANCH",
                    "probe:" + payload.path("kind").asText("PROBE"),
                    "probe is isolated to its forked branch"));
            default -> {
            }
        }
        return out;
    }
}
