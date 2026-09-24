package av.a429.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Versioned label dictionary. The dictionary version alone decides how
 * BNR / BCD / discrete fields are interpreted; every engineering value
 * records which dictionary entry (and version) produced it.
 */
public final class LabelDictionary {

    public enum Encoding { BNR, BCD, DISCRETE }

    public record DictEntry(String entryId, String dictVersion, String labelOctal,
                            Encoding encoding, String unit, double scale,
                            String coordBasis, String description) {}

    private final String version;
    private final Map<String, DictEntry> byLabel;

    public LabelDictionary(String version, List<DictEntry> entries) {
        this.version = version;
        this.byLabel = entries.stream()
                .filter(e -> e.dictVersion().equals(version))
                .collect(Collectors.toMap(DictEntry::labelOctal, e -> e, (a, b) -> a));
    }

    public String version() {
        return version;
    }

    public Optional<DictEntry> lookup(String labelOctal) {
        return Optional.ofNullable(byLabel.get(labelOctal));
    }

    public Map<String, DictEntry> entries() {
        return byLabel;
    }
}
