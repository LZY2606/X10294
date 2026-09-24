package a429.events;

import a429.casepack.CasePack;
import a429.casepack.PackWord;
import a429.domain.RawWord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable fold state while replaying one branch. At any target sequence the
 * state is exactly the conclusion set visible then; replay is deterministic.
 */
final class FoldState {

    final CasePack pack;
    final Map<String, RawWord> activeWords = new LinkedHashMap<>();
    final Map<String, String> activeDictByChannel = new LinkedHashMap<>();
    double gapFactor;
    double switchRatio;
    String selectedCandidate;
    String lastEventId;
    int headSeq = -1;

    FoldState(CasePack pack) {
        this.pack = pack;
        this.gapFactor = pack.gapFactor();
        this.switchRatio = pack.switchRatio();
        for (var ch : pack.channels()) {
            activeDictByChannel.put(ch.channelId(), pack.defaultDictVersion());
        }
    }

    RawWord toRaw(PackWord w) {
        return new RawWord(w.wordId(), w.channelId(), w.position(),
                w.receivedAtNs(), w.receivedAtIso(), w.wireHex(), w.fragmentId());
    }

    RawWord toRaw(PackFragment fragment, PackWord w) {
        return new RawWord(w.wordId(), w.channelId(), w.position(),
                w.receivedAtNs(), w.receivedAtIso(), w.wireHex(), fragment.fragmentId());
    }
}
