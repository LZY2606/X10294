package arinc.model;

import java.util.List;

/** One label entry of a label dictionary version. Interpretation rules live here, never in raw data. */
public record DictEntry(
    String label,
    String name,
    String encoding,
    String unit,
    double resolution,
    List<Integer> validSsm) {}
