package a429.domain;

import java.util.List;
import java.util.Map;

/** A versioned label dictionary. Interpretation kind and layout come from the dictionary. */
public record DictVersion(
        String dictId,
        String version,
        Map<String, DictEntry> entriesByCanonicalLabel,
        Map<String, Object> rules
) {
}
