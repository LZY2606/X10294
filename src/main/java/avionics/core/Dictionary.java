package avionics.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标签字典（版本化）。BNR/BCD/discrete 的解释完全由字典版本决定。
 */
public final class Dictionary {
    public enum Encoding { BNR, BCD, DISCRETE }

    public record Entry(String labelOctal, Encoding encoding, String unit,
                        double scale, double offset, String description,
                        Map<Integer, String> ssmMeaning) {}

    public final String dictId;
    public final int version;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public Dictionary(String dictId, int version) {
        this.dictId = dictId;
        this.version = version;
    }

    public void add(Entry e) { entries.put(e.labelOctal(), e); }

    public Entry lookup(String labelOctal) { return entries.get(labelOctal); }

    public Map<String, Entry> entries() { return entries; }
}
