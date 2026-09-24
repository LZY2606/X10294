package arinc.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) throw new IllegalArgumentException("Trailing JSON content at " + parser.pos);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON object required");
        return (Map<String, Object>) value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    public static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("JSON object requires key/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    public static List<Object> arr(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    public static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static long lng(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    public static int integer(Map<String, Object> map, String key) {
        return (int) lng(map, key);
    }

    public static double dbl(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    public static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON object required");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("JSON array required");
        return (List<Object>) value;
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Number number) {
            writeNumber(number, out);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
                first = false;
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                write(item, out);
                first = false;
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeNumber(Number number, StringBuilder out) {
        double d = number.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("JSON cannot represent " + number);
        if (number instanceof Double || number instanceof Float) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) out.append(Long.toString((long) d));
            else out.append(number.toString());
        } else {
            out.append(number.toString());
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;

        private Parser(String text) { this.text = text; }
        public Object readValue() {
            skipWhitespace();
            if (end()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = text.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (consume('}')) return map;
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                if (consume('}')) return map;
                expect(',');
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (consume(']')) return list;
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (consume(']')) return list;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (end()) throw new IllegalArgumentException("Unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (end()) throw new IllegalArgumentException("Unterminated escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new IllegalArgumentException("Bad unicode escape");
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported escape \\" + e);
                    }
                } else out.append(c);
            }
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("Invalid literal at " + pos);
        }

        private Object readNull() {
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("Invalid literal at " + pos);
        }

        private Number readNumber() {
            int start = pos;
            if (consume('-') || peek() == '+') consume('+');
            while (!end() && Character.isDigit(peek())) pos++;
            boolean floating = false;
            if (!end() && peek() == '.') {
                floating = true; pos++;
                while (!end() && Character.isDigit(peek())) pos++;
            }
            if (!end() && (peek() == 'e' || peek() == 'E')) {
                floating = true; pos++;
                if (!end() && (peek() == '+' || peek() == '-')) pos++;
                while (!end() && Character.isDigit(peek())) pos++;
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || "-".equals(token)) throw new IllegalArgumentException("Invalid number at " + start);
            return floating ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private char peek() { return text.charAt(pos); }
        private boolean end() { return pos >= text.length(); }
        private void skipWhitespace() { while (!end() && Character.isWhitespace(text.charAt(pos))) pos++; }
        private boolean consume(char c) {
            if (!end() && text.charAt(pos) == c) { pos++; return true; }
            return false;
        }
        private void expect(char c) {
            if (!consume(c)) throw new IllegalArgumentException("Expected '" + c + "' at " + pos);
        }
    }
}
