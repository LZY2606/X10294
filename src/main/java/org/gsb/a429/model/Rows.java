package org.gsb.a429.model;

/** 数据库行类型集中定义。sid 为跨导出/导入保持稳定的字符串身份。 */
public final class Rows {
    private Rows() {
    }

    public record Channel(String id, String name, boolean bitReversed, String note) {
    }

    public record RawWord(long id, String sid, String materialId, int revisionSeq,
                          String wordId, int seq, double recvTimeMs,
                          String channelId, int word32, String note) {
    }

    public record MaterialRevision(String materialId, int seq, String note) {
    }

    public record Branch(String id, String name, String parentBranchId, int parentEventSeq,
                         int materialSeq, String dictVersion, String note) {
    }

    public record Event(long id, String sid, String branchId, int seq, String type,
                        String targetSid, String payloadJson, String actor,
                        double tsMs, String parentEventSid, String basisLabel) {
    }
}
