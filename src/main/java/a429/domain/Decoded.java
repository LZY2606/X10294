package a429.domain;

import java.util.List;

/**
 * Derived interpretation of one word under one dictionary version.
 * The decoder never deletes information: unconvertible values stay as
 * diagnostics with {@code converted == false} instead of being dropped.
 */
public record Decoded(
        String labelWireOctal,
        String labelCanonicalOctal,
        int labelByte,
        int canonicalLabelByte,
        int sdi,
        int ssm,
        int data,
        boolean parityOk,
        boolean labelHit,
        String dictVersion,
        String entryName,
        String kind,
        EngineeringValue engineering,
        List<FieldSpan> spans,
        List<String> diagnostics
) {
    public record EngineeringValue(
            boolean converted,
            Double numeric,
            String unit,
            String datum,
            String text,
            boolean validState
    ) {
    }
}
