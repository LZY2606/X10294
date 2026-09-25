package avdata.a429.casepack;

import avdata.a429.service.Models;
import avdata.a429.service.ReviewService;

import java.util.ArrayList;
import java.util.List;

/** Loads the repository's own offline case pack deterministically. */
public final class CasePackInstaller {
    private CasePackInstaller() {}

    public record Installed(String baselineBranchId, String batchId, List<CasePack.Specimen> specimens) {}

    public static Installed install(ReviewService svc) {
        svc.addChannelConfig(CasePack.CFG_V1, "DOCUMENT_REVERSED",
                "ARINC 429 document octal labels, odd parity");
        svc.addChannelConfig(CasePack.CFG_V2, "DOCUMENT_REVERSED",
                "parameter revision: review notes only; label convention unchanged");
        svc.addDictionary(CasePack.DICT_V1, CasePack.dictionaryV1());
        svc.addDictionary(CasePack.DICT_V2, CasePack.dictionaryV2());

        List<CasePack.Specimen> specimens = CasePack.specimens();
        List<Models.RawWordIn> words = new ArrayList<>();
        for (CasePack.Specimen s : specimens) {
            words.add(new Models.RawWordIn(s.seq(), s.receivedAtUs(), CasePack.CHANNEL,
                    s.word(), s.rawRepr()));
        }
        String batchId = svc.importWords("cases/" + CasePack.PACK_NAME + "/words.jsonl",
                CasePack.CHANNEL, CasePack.DICT_V1, CasePack.CFG_V1, words,
                "ARINC 429 label/parity/rate evidence baseline");
        String trunk = svc.createBranch("trunk: baseline", null);
        return new Installed(trunk, batchId, specimens);
    }
}
