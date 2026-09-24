package arinc429.casepack;

import arinc429.domain.Json;
import arinc429.domain.Models.DictEntry;
import arinc429.domain.Models.Dictionary;
import arinc429.domain.Models.RuleParams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the bundled offline case pack ("字路译码台") from the classpath. */
public final class CasepackService {

    private final Path root;
    private Map<String, Object> manifest;

    public CasepackService(Path root) {
        this.root = root;
    }

    private String readFile(String name) {
        Path p = root.resolve(name);
        if (Files.exists(p)) {
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + p, e);
            }
        }
        try (InputStream in = CasepackService.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("casepack file missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read classpack resource " + name, e);
        }
    }

    public synchronized Map<String, Object> manifest() {
        if (manifest == null) {
            manifest = Json.read(readFile("manifest.json"));
        }
        return manifest;
    }

    public List<Map<String, Object>> words() {
        return castList(Json.read(readFile("words.json")));
    }

    public List<Map<String, Object>> channels() {
        return castList(Json.read(readFile("channels.json")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) o;
    }

    public Dictionary dictionary(String version) {
        Map<String, Object> d = Json.read(readFile("dictionary-" + version + ".json"));
        Map<String, DictEntry> entries = new LinkedHashMap<>();
        for (Map<String, Object> e : castList(d.get("entries"))) {
            Map<Integer, String> bits = new HashMap<>();
            Object db = e.get("discrete_bits");
            if (db instanceof Map) {
                for (Map.Entry<?, ?> en : ((Map<?, ?>) db).entrySet()) {
                    bits.put(Integer.valueOf(String.valueOf(en.getKey())), String.valueOf(en.getValue()));
                }
            }
            DictEntry entry = new DictEntry(
                    String.valueOf(e.get("label_octal")),
                    strOr(e, "sdi", "**"),
                    String.valueOf(e.get("name")),
                    String.valueOf(e.get("encoding")),
                    e.get("lsb_weight") == null ? null : ((Number) e.get("lsb_weight")).doubleValue(),
                    e.get("units") == null ? null : String.valueOf(e.get("units")),
                    e.get("bcd_digits") == null ? null : ((Number) e.get("bcd_digits")).intValue(),
                    bits,
                    e.get("note") == null ? null : String.valueOf(e.get("note")));
            String key = String.valueOf(e.getOrDefault("key", entry.labelOctal() + "/" + entry.sdiPattern()));
            entries.put(key, entry);
        }
        return new Dictionary(version, entries);
    }

    private String strOr(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v == null ? dflt : String.valueOf(v);
    }

    public RuleParams rules(String version) {
        Map<String, Object> r = Json.read(readFile("rules-" + version + ".json"));
        double tol = ((Number) r.get("rate_tolerance")).doubleValue();
        long gap = ((Number) r.get("acquisition_gap_us")).longValue();
        Map<String, Map<Integer, String>> ssm = new LinkedHashMap<>();
        Object table = r.get("ssm_table");
        if (table instanceof Map) {
            for (Map.Entry<?, ?> fam : ((Map<?, ?>) table).entrySet()) {
                Map<Integer, String> codes = new LinkedHashMap<>();
                for (Map.Entry<?, ?> code : ((Map<?, ?>) fam.getValue()).entrySet()) {
                    codes.put(Integer.valueOf(String.valueOf(code.getKey())), String.valueOf(code.getValue()));
                }
                ssm.put(String.valueOf(fam.getKey()), codes);
            }
        }
        return new RuleParams(version, tol, gap, ssm);
    }

    public List<Map<String, Object>> mutations() {
        Object m = manifest().get("mutations");
        return m == null ? new ArrayList<>() : castList(m);
    }

    public List<Map<String, Object>> stages() {
        Object s = manifest().get("replay_stages");
        return s == null ? new ArrayList<>() : castList(s);
    }
}
