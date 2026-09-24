package a429.decode;

import java.util.List;

/**
 * One dictionary entry. The dictionary version is held by the dictionary that
 * contains this definition; the same label may decode differently per version.
 */
public record LabelDef(
        String labelOctal,
        String labelName,
        Encoding encoding,
        double bnrResolution,   // BNR: value per LSB (unit), e.g. 1.0 ft
        String unit,            // engineering unit / coordinate basis, e.g. "ft (MSL)"
        int bcdDecimals,        // BCD: number of implicit decimal places
        List<DiscreteBit> discreteBits
) {
    public record DiscreteBit(int dataBit, String meaningFalse, String meaningTrue) { }

    public static LabelDef none(String labelOctal) {
        return new LabelDef(labelOctal, "(unknown label)", Encoding.NONE,
                Double.NaN, "", 0, List.of());
    }
}
