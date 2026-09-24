package arinc.model;

/** A retained hypothesis (rate switch, acquisition gap, bit-order reversal...). Never auto-decided. */
public record Candidate(long id, long importId, String kind, String payload, String createdAt) {}
