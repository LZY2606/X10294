package avdata.a429.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Map;

public final class Json {
    private Json() {}

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String to(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T from(String s, Class<T> type) {
        return GSON.fromJson(s, type);
    }

    public static JsonElement tree(String s) {
        return JsonParser.parseString(s);
    }

    public static String obj(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return to(m);
    }

    public static Map<String, Object> map(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = GSON.fromJson(json, Map.class);
        return m;
    }
}
