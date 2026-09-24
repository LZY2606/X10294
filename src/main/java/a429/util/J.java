package a429.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Jackson 薄封装，整个系统共用一个 mapper。 */
public final class J {
    public static final ObjectMapper M = new ObjectMapper();

    private J() {
    }

    public static ObjectNode obj() {
        return M.createObjectNode();
    }

    public static ArrayNode arr() {
        return M.createArrayNode();
    }

    public static ObjectNode obj(Object... kv) {
        ObjectNode n = M.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return n;
    }

    public static JsonNode read(String s) {
        try {
            return M.readTree(s == null || s.isBlank() ? "{}" : s);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad json: " + e.getMessage(), e);
        }
    }

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
