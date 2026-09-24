package arinc.model;

/** Adjudication event. visibleState pins the dependency versions and conclusions visible at that moment. */
public record Event(
    long id,
    long branchId,
    long parentId,
    long seq,
    String kind,
    String payload,
    String visibleState,
    String actor,
    String createdAt) {}
