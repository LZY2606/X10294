package gsb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 标签字典版本：BNR/BCD/discrete 的解释完全由字典版本决定。 */
public record Dictionary(String version, Map<String, Entry> entries, java.util.List<Integer> ssmValid) {

    public record Entry(String label, String mnemonic, String encoding, String unit,
                        Double lsb, Integer bits, Integer digits, String coord) {}

    public Entry find(String labelOctal) {
        return entries.get(labelOctal);
    }

    public boolean ssmValid(int ssm) {
        return ssmValid.contains(ssm);
    }

    public static Dictionary parse(JsonNode node) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (JsonNode e : node.withArray("entries")) {
            Entry entry = new Entry(
                    e.get("label").asText(),
                    e.get("mnemonic").asText(),
                    e.get("encoding").asText(),
                    e.has("unit") ? e.get("unit").asText() : null,
                    e.has("lsb") ? e.get("lsb").asDouble() : null,
                    e.has("bits") ? e.get("bits").asInt() : null,
                    e.has("digits") ? e.get("digits").asInt() : null,
                    e.has("coord") ? e.get("coord").asText() : null);
            entries.put(entry.label(), entry);
        }
        java.util.List<Integer> ssmValid = new java.util.ArrayList<>();
        for (JsonNode s : node.withArray("ssmValid")) {
            ssmValid.add(s.asInt());
        }
        return new Dictionary(node.get("version").asText(), entries, ssmValid);
    }
}
