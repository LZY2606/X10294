package a429.domain;

import java.util.List;
import java.util.Map;

/**
 * 标签字典（不可变）。字典是解释的唯一来源：BNR/BCD/discrete 的解释、
 * 分辨率、范围、单位与坐标基准均由版本化字典决定。
 */
public record Dictionary(
        String version,
        String description,
        Map<String, LabelDef> labels
) {
    public LabelDef label(String octal) {
        return labels == null ? null : labels.get(octal);
    }

    /** 单条标签定义；{@code sdi} 为允许匹配的 SDI（null 表示任意）。 */
    public record LabelDef(
            String label,
            String name,
            String encoding,          // BNR | BCD | DISCRETE
            Double resolution,        // BNR：每 LSB 工程量
            Double minValue,          // BNR 合法范围（可选）
            Double maxValue,
            boolean signed,
            String unit,              // 工程单位，如 deg
            String datum,             // 坐标/测量基准，如 "aircraft level"
            int bcdDigits,            // BCD 位数（<=5）
            List<DiscreteBit> discreteBits
    ) {
        public LabelDef {
            if (encoding == null) {
                encoding = "BNR";
            }
        }
    }

    public record DiscreteBit(
            int bit,                 // wire bit 编号（11..29）
            String name,
            String zeroMeaning,
            String oneMeaning
    ) {
    }
}
