package a429.review.state;

import a429.review.decode.Decoder;

/**
 * One visible raw word with its immutable provenance fields and the derived
 * interpretation under the branch's dictionary version.
 */
public record WordView(String wordUid,
                       String materialId,
                       int ordinal,
                       String rawHex,
                       String timestampRaw,
                       long timeNs,
                       String channel,
                       String parityResult,
                       String octalLabel,
                       String labelBits,
                       int sdi,
                       int ssm,
                       long dataField,
                       String dictionaryVersion,
                       boolean patched,
                       int patchSeq,
                       Decoder.Interpretation interpretation,
                       String interpUid) {
}
