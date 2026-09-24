package avionics.a429;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Label dictionary: decides how BNR / BCD / discrete interpretations happen.
 * Versioned; interpretation results always record which version produced them.
 */
public final class Dictionary {

    public static final class LabelDef {
        public final String octal;
        public final String encoding;   // BNR | BCD | DISCRETE
        public final String name;
        public final String unit;       // e.g. "kt", "ft", "deg"
        public final String unitRef;    // coordinate/reference frame, e.g. "deg lat N"
        public final double resolution; // BNR only
        public final int[] discretePositions;

        LabelDef(String octal, String encoding, String name, String unit, String unitRef,
                 double resolution, int[] discretePositions) {
            this.octal = octal;
            this.encoding = encoding;
            this.name = name;
            this.unit = unit;
            this.unitRef = unitRef;
            this.resolution = resolution;
            this.discretePositions = discretePositions;
        }
    }

    public final String version;
    public final Map<String, LabelDef> labels; // keyed by octal string

    private Dictionary(String version, Map<String, LabelDef> labels) {
        this.version = version;
        this.labels = labels;
    }

    public static Dictionary parse(String version, JsonNode root) {
        Map<String, LabelDef> labels = new LinkedHashMap<>();
        for (JsonNode n : root.withArray("labels")) {
            int[] positions = new int[0];
            if (n.has("discreteBits")) {
                JsonArrayHolder h = new JsonArrayHolder();
                for (JsonNode p : n.withArray("discreteBits")) {
                    h.add(p.asInt());
                }
                positions = h.toArray();
            }
            labels.put(n.get("octal").asText(), new LabelDef(
                    n.get("octal").asText(),
                    n.get("encoding").asText(),
                    n.get("name").asText(),
                    n.has("unit") ? n.get("unit").asText() : "",
                    n.has("unitRef") ? n.get("unitRef").asText() : "",
                    n.has("resolution") ? n.get("resolution").asDouble() : 1.0,
                    positions));
        }
        return new Dictionary(version, labels);
    }

    public LabelDef label(String octal) {
        return labels.get(octal);
    }

    /** Labels whose definition differs from the other dictionary (drives invalidation scope). */
    public List<String> diffLabels(Dictionary other) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, LabelDef> e : labels.entrySet()) {
            LabelDef o = other.labels.get(e.getKey());
            if (o == null || !sameDef(e.getValue(), o)) {
                changed.add(e.getKey());
            }
        }
        for (String k : other.labels.keySet()) {
            if (!labels.containsKey(k)) {
                changed.add(k);
            }
        }
        return changed;
    }

    private static boolean sameDef(LabelDef a, LabelDef b) {
        return a.encoding.equals(b.encoding) && a.name.equals(b.name)
                && a.unit.equals(b.unit) && a.unitRef.equals(b.unitRef)
                && a.resolution == b.resolution
                && java.util.Arrays.equals(a.discretePositions, b.discretePositions);
    }

    private static final class JsonArrayHolder {
        final List<Integer> values = new ArrayList<>();
        void add(int v) { values.add(v); }
        int[] toArray() {
            int[] out = new int[values.size()];
            for (int i = 0; i < out.length; i++) out[i] = values.get(i);
            return out;
        }
    }
}
