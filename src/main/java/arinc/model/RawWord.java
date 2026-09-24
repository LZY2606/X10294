package arinc.model;

/** One imported ARINC 429 word. Raw representation is preserved exactly as received. */
public record RawWord(
    long id,
    long importId,
    int seq,
    long rxTimeNs,
    String channel,
    long word32,
    boolean parityOk,
    String labelOctal,
    int sdi,
    int ssm,
    long dataField,
    String dictVersion,
    String note) {}
