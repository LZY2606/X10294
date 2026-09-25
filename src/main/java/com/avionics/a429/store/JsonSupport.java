package com.avionics.a429.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class JsonSupport {
    public static final ObjectMapper M = new ObjectMapper();

    private JsonSupport() {}

    public static JsonNode toJson(Object value) {
        return M.valueToTree(value);
    }

    public static <T> T fromJson(JsonNode node, Class<T> type) {
        try {
            return M.treeToValue(node, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON 转换失败: " + e.getMessage(), e);
        }
    }

    public static JsonNode parse(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static String write(JsonNode node) {
        try {
            return M.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String pretty(JsonNode node) {
        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ObjectNode obj() {
        return M.createObjectNode();
    }
}
