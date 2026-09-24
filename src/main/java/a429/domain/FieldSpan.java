package a429.domain;

/** A coloured bit-field span for the SVG bit view (ARINC bit numbers, inclusive). */
public record FieldSpan(String key, String title, int msb, int lsb, int value, String note) {
}
