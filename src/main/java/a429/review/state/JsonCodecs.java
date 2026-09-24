package a429.review.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Tiny JSON codec helpers shared by state, store and web layers. */
public final class JsonCodecs {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public interface Reader<T> {
        T fromJson(String json);
    }

    public static final Reader<BranchState.Params> PARAMS_READER =
            json -> {
                try {
                    return MAPPER.readValue(json, BranchState.Params.class);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

    public static final Reader<BranchState.Decision> DECISION_READER =
            json -> {
                try {
                    return MAPPER.readValue(json, BranchState.Decision.class);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

    private JsonCodecs() {
    }

    public static String singleString(String json, String key) {
        try {
            return MAPPER.readTree(json).get(key).asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
