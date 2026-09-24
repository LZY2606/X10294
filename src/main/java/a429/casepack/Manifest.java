package a429.casepack;

import java.util.List;

public record Manifest(String casePackId, String title, List<MutationSpec> mutations,
                       List<String> narrative) {

    public record MutationSpec(String id, String kind, int seq, Object expectDirect,
                              String note) {
    }
}
