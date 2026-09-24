package a429.casepkg;

import a429.domain.DictEntry;
import a429.domain.DictVersion;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads a dictionary revision JSON into the versioned {@link DictVersion} model. */
public final class DictMapper {
    private DictMapper() {
    }

    public static DictVersion parse(CasePackage.DictionarySpec spec, InputStream in) {
        try {
            JsonNode root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(in);
            String id = root.path("id").asText(spec.id());
            String version = root.path("version").asText(spec.version());
            Map<String, DictEntry> entries = new LinkedHashMap<>();
            for (JsonNode e : root.path("entries")) {
                String label = e.path("canonicalOctal").asText();
                String name = e.path("name").asText();
                String kind = e.path("kind").asText();
                String unit = e.path("unit").asText("");
                String datum = e.path("datum").asText("");
                String ssmScheme = e.path("ssmScheme").asText("NONE");
                Integer sdiMatch = e.has("sdiMatch") && !e.path("sdiMatch").isNull()
                        ? e.path("sdiMatch").asInt() : null;
                DictEntry entry = switch (kind) {
                    case "BNR" -> {
                        double resolution = e.path("resolution").asDouble();
                        int signArinc = e.path("signArincBit").asInt(29);
                        yield DictEntry.bnr(label, name, unit, datum, resolution, signArinc,
                                sdiMatch, ssmScheme);
                    }
                    case "BCD" -> {
                        int width = e.path("width").asInt(19);
                        List<DictEntry.BcdDigit> digits = new ArrayList<>();
                        for (JsonNode d : e.path("digits")) {
                            digits.add(new DictEntry.BcdDigit(d.path("msb").asInt(),
                                    d.path("lsb").asInt(), d.path("weight").asInt()));
                        }
                        yield DictEntry.bcd(label, name, unit, datum, width, digits, sdiMatch,
                                ssmScheme);
                    }
                    case "DISCRETE" -> {
                        List<DictEntry.DiscreteBit> bits = new ArrayList<>();
                        for (JsonNode b : e.path("bits")) {
                            bits.add(new DictEntry.DiscreteBit(b.path("arincBit").asInt(),
                                    b.path("code").asText(), b.path("meaningWhenOne").asText()));
                        }
                        yield DictEntry.discrete(label, name, bits, sdiMatch, ssmScheme);
                    }
                    default -> throw new IllegalStateException("unknown dictionary entry kind: " + kind);
                };
                entries.put(label, entry);
            }
            Map<String, Object> rules = new LinkedHashMap<>();
            JsonNode rulesNode = root.path("rules");
            if (rulesNode.isObject()) {
                rulesNode.fields().forEachRemaining(f ->
                        rules.put(f.getKey(), a429.db.Json.MAPPER.convertValue(f.getValue(), Object.class)));
            }
            rules.put("dictionaryId", id);
            rules.put("version", version);
            return new DictVersion(id, version, Map.copyOf(entries), Map.copyOf(rules));
        } catch (Exception e) {
            throw new IllegalStateException("bad dictionary JSON", e);
        }
    }
}
