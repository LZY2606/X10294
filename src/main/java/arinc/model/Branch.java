package arinc.model;

public record Branch(long id, long importId, String name, long headEventId, long version, String createdAt) {}
