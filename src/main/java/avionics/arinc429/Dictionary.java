package avionics.arinc429;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标签字典：按版本保存 label -> 解释方式（BNR/BCD/discrete、分辨率、单位、坐标基准）。
 * BNR/BCD/discrete 的解释完全由字典版本决定；版本变化只使依赖它的派生值失效。
 */
public final class Dictionary {

    public record Entry(
            String id,
            int labelOctal,
            String encoding,   // BNR | BCD | DISCRETE
            double resolution,
            String unit,       // 单位，如 "deg"、"kt"、"ft"
            String frame,      // 坐标基准，如 "body-axis"、"WGS84"
            String description
    ) {
        public String labelText() { return Arinc.octalText(labelOctal); }
    }

    public final String version;
    private final Map<Integer, Entry> byLabel = new LinkedHashMap<>();

    public Dictionary(String version) { this.version = version; }

    public void add(Entry e) { byLabel.put(e.labelOctal(), e); }

    public Entry find(int labelOctal) { return byLabel.get(labelOctal); }

    public Map<Integer, Entry> entries() { return byLabel; }
}
