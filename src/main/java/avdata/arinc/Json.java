package avdata.arinc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;
import java.util.TreeMap;

/** Small JSON helpers (deterministic key order for stable serialisation). */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize json", e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot serialize json", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String text) {
        try {
            return MAPPER.readValue(text, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> sorted(Map<String, Object> in) {
        return new TreeMap<>(in);
    }
}
