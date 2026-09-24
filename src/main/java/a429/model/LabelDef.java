package a429.model;

import java.util.List;

/**
 * One dictionary entry for an octal label (e.g. "270"). All engineering metadata lives here so
 * the raw word stays untouched and the same bits can be explained differently across versions.
 */
public record LabelDef(
        String labelOctal,
        String name,
        Encoding encoding,
        int bnrBits,
        double fullScale,
        String unit,
        List<String> discreteNames) {

    public String discreteName(int dataBitIndex1to19) {
        if (discreteNames == null || discreteNames.isEmpty()) {
            return "DISCRETE_D" + dataBitIndex1to19;
        }
        int idx = discreteNames.size() - dataBitIndex1to19;
        if (idx < 0 || idx >= discreteNames.size()) {
            return "DISCRETE_D" + dataBitIndex1to19;
        }
        return discreteNames.get(idx);
    }
}
