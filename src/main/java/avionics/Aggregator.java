package avionics;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregation over a rate segment. Bad-parity words and invalid-SSM words
 * never enter aggregate values; they stay visible as raw rows with diagnostics.
 */
public final class Aggregator {

    private Aggregator() {}

    public record Member(String wordId, boolean parityOk, boolean ssmNormal, Double value) {}

    public record Aggregate(int memberCount, List<String> excludedWordIds, Double mean, String unit) {}

    public static Aggregate mean(List<Member> members, String unit) {
        List<String> excluded = new ArrayList<>();
        double sum = 0;
        int n = 0;
        for (Member m : members) {
            if (!m.parityOk() || !m.ssmNormal() || m.value() == null) {
                excluded.add(m.wordId());
                continue;
            }
            sum += m.value();
            n++;
        }
        return new Aggregate(n, excluded, n > 0 ? sum / n : null, unit);
    }
}
