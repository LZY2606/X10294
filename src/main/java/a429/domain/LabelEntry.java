package a429.domain;

import java.util.List;
import java.util.Map;

/**
 * One dictionary definition of a label. Resolution only applies to BNR.
 * bitNames maps discrete data-bit positions (ARINC 11..29) to signal names.
 */
public record LabelEntry(
        String labelOctal,
        int sdi,
        String name,
        EncodingType encoding,
        double resolution,
        String unit,
        String basis,
        boolean signed,
        Map<Integer, String> bitNames) {

    public LabelEntry bnrKey() {
        return this;
    }

    public List<String> orderedBitNames() {
        return bitNames == null ? List.of() : bitNames.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }
}
