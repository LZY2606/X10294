package avionics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Label dictionary, versioned. The dictionary version alone decides how a
 * word's data field is interpreted (BNR / BCD / discrete) and which unit and
 * scale apply. Changing the dictionary version never rewrites stored words;
 * it only invalidates the derived conclusions that depended on the old version.
 */
public final class Dictionary {

    public enum DataType { BNR, BCD, DISCRETE }

    public static final class Entry {
        public final String labelOctal;
        public final DataType type;
        public final String unit;
        public final double scale;
        public final int bcdDigits;
        public final String description;

        public Entry(String labelOctal, DataType type, String unit, double scale, int bcdDigits, String description) {
            this.labelOctal = labelOctal;
            this.type = type;
            this.unit = unit;
            this.scale = scale;
            this.bcdDigits = bcdDigits;
            this.description = description;
        }
    }

    public final String version;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Dictionary(String version) {
        this.version = version;
    }

    public void put(Entry e) {
        entries.put(e.labelOctal, e);
    }

    public Optional<Entry> find(String labelOctal) {
        return Optional.ofNullable(entries.get(labelOctal));
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Dictionary load(String version, InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        Dictionary d = new Dictionary(version);
        for (JsonNode n : root.get("labels")) {
            d.put(new Entry(
                    n.get("label").asText(),
                    DataType.valueOf(n.get("type").asText()),
                    n.get("unit").asText(),
                    n.has("scale") ? n.get("scale").asDouble() : 1.0,
                    n.has("bcdDigits") ? n.get("bcdDigits").asInt() : 5,
                    n.has("description") ? n.get("description").asText() : ""));
        }
        return d;
    }

    /** Engineering value for a word under this dictionary, or a diagnostic when not convertible. */
    public DecodedValue decode(long raw) {
        String label = Arinc.labelOctal(raw);
        Optional<Entry> e = find(label);
        if (e.isEmpty()) {
            return DecodedValue.diagnostic(label, version,
                    "no dictionary entry for label " + label + " in dictionary " + version
                            + "; raw word kept in place");
        }
        Entry entry = e.get();
        if (!Arinc.parityOk(raw)) {
            return DecodedValue.diagnostic(label, version,
                    "bad parity; word excluded from aggregated values");
        }
        if (!Arinc.ssmOperational(Arinc.ssm(raw))) {
            return DecodedValue.diagnostic(label, version,
                    "SSM=" + Arinc.ssm(raw) + " is not an operational state; value flagged, raw kept");
        }
        switch (entry.type) {
            case BNR -> {
                double v = Arinc.decodeBnr(raw, entry.scale);
                return DecodedValue.value(label, version, entry, v);
            }
            case BCD -> {
                Arinc.BcdResult r = Arinc.decodeBcd(raw, entry.bcdDigits);
                if (r.value == null) {
                    return DecodedValue.diagnostic(label, version, r.diagnostic);
                }
                return DecodedValue.value(label, version, entry, r.value * entry.scale);
            }
            case DISCRETE -> {
                long bit = (Arinc.dataField(raw) & 1);
                return DecodedValue.value(label, version, entry, bit);
            }
        }
        throw new IllegalStateException();
    }

    /** A decoded engineering value or a diagnostic. Always carries unit basis and source. */
    public static final class DecodedValue {
        public final String labelOctal;
        public final String dictVersion;
        public final String unit;        // null when diagnostic
        public final Double value;       // null when diagnostic
        public final String diagnostic;  // null when value
        public final String source;      // human readable provenance

        private DecodedValue(String labelOctal, String dictVersion, String unit, Double value,
                             String diagnostic, String source) {
            this.labelOctal = labelOctal;
            this.dictVersion = dictVersion;
            this.unit = unit;
            this.value = value;
            this.diagnostic = diagnostic;
            this.source = source;
        }

        static DecodedValue value(String label, String version, Entry e, double v) {
            return new DecodedValue(label, version, e.unit, v, null,
                    "dict=" + version + " label=" + label + " type=" + e.type + " scale=" + e.scale);
        }

        static DecodedValue diagnostic(String label, String version, String diag) {
            return new DecodedValue(label, version, null, null, diag,
                    "dict=" + version + " label=" + label);
        }

        public boolean ok() {
            return value != null;
        }
    }
}
