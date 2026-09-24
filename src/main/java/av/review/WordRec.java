package av.review;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable record of one imported 32-bit word. Source fields and parsed
 * fields are kept together; parsing at import only extracts bit fields and
 * parity, it never normalises an engineering value.
 */
public record WordRec(
        String id,
        String importId,
        long seq,
        long rxTimeNs,
        String channel,
        String rawHex,
        int labelBits,
        String labelOctal,
        int sdi,
        int ssm,
        long dataField,
        boolean parityOk,
        String dictVersion,
        String note
) {
    public long word() {
        return Long.parseUnsignedLong(rawHex.startsWith("0x") ? rawHex.substring(2) : rawHex, 16);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("importId", importId);
        m.put("seq", seq);
        m.put("rxTimeNs", rxTimeNs);
        m.put("rxTimeUnit", "ns");
        m.put("channel", channel);
        m.put("rawHex", rawHex);
        m.put("labelBits", labelBits);
        m.put("labelOctal", labelOctal);
        m.put("sdi", sdi);
        m.put("ssm", ssm);
        m.put("dataField", dataField);
        m.put("dataFieldHex", String.format("0x%05X", dataField));
        m.put("parityOk", parityOk);
        m.put("dictVersion", dictVersion);
        m.put("note", note);
        return m;
    }
}
