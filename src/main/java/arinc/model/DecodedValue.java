package arinc.model;

import java.util.List;

/**
 * Derived interpretation of one raw word under one dictionary version.
 * Immutable and keyed by (rawWordId, dictVersion) so old conclusions stay readable.
 */
public record DecodedValue(
    String id,
    long rawWordId,
    String dictVersion,
    String encoding,
    Double engineeringValue,
    String unit,
    String display,
    List<String> diagnostics,
    String dictHit) {}
