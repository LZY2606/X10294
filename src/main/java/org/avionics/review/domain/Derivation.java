package org.avionics.review.domain;

import org.avionics.review.store.State;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the read-only review model from a materialized {@link State}:
 * bit-field rows, inter-word intervals, rate segmentation candidates,
 * engineering-value candidates and label aggregates.
 *
 * <p>Nothing here writes data. Human choices arrive via events and merely mark
 * engineering values accepted/rejected in this view.</p>
 */
public class Derivation {

    public record WordRow(RawWord word, boolean parityOk, String labelOctal, int sdi, int ssm,
                          String ssmName, String dataHex, List<WordField> fields,
                          List<EngineeringValue> engineeringValues,
                          List<DecisionMark> marks) {
    }

    public record DecisionMark(String evSid, String action, String decisionEventId, String note) {
    }

    public record Model(List<WordRow> wordRows, List<Interval> intervals,
                        List<RateCandidate> rateCandidates,
                        List<AggregateValue> aggregates,
                        List<String> decodeNotes,
                        Map<String, Aggregator.Choice> choices,
                        Map<String, List<DecisionMark>> marksByWord) {
    }

    private final State state;

    public Derivation(State state) {
        this.state = state;
    }

    public Model derive() {
        List<RawWord> words = state.activeWords();
        words.sort((a, b) -> {
            int c = Long.compare(a.seq(), b.seq());
            return c != 0 ? c : Double.compare(a.receiveTimeMs(), b.receiveTimeMs());
        });

        RateAnalyzer analyzer = new RateAnalyzer(state.gapThresholdMs, state.rateSwitchRatio);
        List<Interval> intervals = analyzer.intervals(words);
        List<RateCandidate> rateCandidates = analyzer.candidates(words, intervals);

        Map<String, List<EngineeringValue>> valuesByWord = new LinkedHashMap<>();
        Map<String, List<DecisionMark>> marksByWord = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<WordRow> rows = new ArrayList<>();

        Map<String, Aggregator.Choice> choices = new LinkedHashMap<>();

        for (RawWord w : words) {
            Decoder.CandidateResult cr = Decoder.decodeAll(w.sid(), w.raw(), state.dictByLabel);
            valuesByWord.put(w.sid(), cr.values());
            notes.addAll(cr.notes());

            List<DecisionMark> marks = new ArrayList<>();
            for (State.Decision d : state.decisions) {
                for (EngineeringValue ev : cr.values()) {
                    if (d.targetSid().equals(ev.sid())) {
                        marks.add(new DecisionMark(ev.sid(), d.action(), d.eventId(), d.note()));
                        if ("ACCEPT".equals(d.action())) {
                            choices.put(w.sid(), new Aggregator.Choice(ev.convention(), ev.dictVersion()));
                        }
                    }
                }
            }
            marksByWord.put(w.sid(), marks);

            long word = w.raw();
            List<WordField> fields = new ArrayList<>();
            fields.add(new WordField("fld:" + w.sid() + ":label", "LABEL", 8, 1,
                    binary(word, 8, 1), String.format("%03o", Arinc429.labelRaw(word)),
                    "on-wire LSB-first; shown as octal " + Arinc429.labelOctal(word)));
            fields.add(new WordField("fld:" + w.sid() + ":sdi", "SDI", 10, 9,
                    binary(word, 10, 9), String.valueOf(Arinc429.sdi(word)),
                    "source/destination identifier"));
            fields.add(new WordField("fld:" + w.sid() + ":data", "DATA", 28, 11,
                    binary(word, 28, 11), String.format("%05X", Arinc429.field(word, 28, 11)),
                    "BNR/BCD/discrete payload"));
            fields.add(new WordField("fld:" + w.sid() + ":ssm", "SSM", 30, 29,
                    binary(word, 30, 29), String.format("%02o", Arinc429.ssm(word)),
                    Arinc429.ssmName(Arinc429.ssm(word), "BNR")));
            fields.add(new WordField("fld:" + w.sid() + ":parity", "PARITY", 32, 32,
                    Arinc429.bitAt(word, 32) ? "1" : "0",
                    Arinc429.oddParityOk(word) ? "ODD_OK" : "ODD_FAIL",
                    "odd parity over all 32 bits"));

            rows.add(new WordRow(w, Arinc429.oddParityOk(word), Arinc429.labelOctal(word),
                    Arinc429.sdi(word), Arinc429.ssm(word),
                    Arinc429.ssmName(Arinc429.ssm(word), "BNR"),
                    String.format("%05X", Arinc429.field(word, 28, 11)), fields,
                    cr.values(), marks));
        }

        List<AggregateValue> aggregates = Aggregator.aggregate(words, valuesByWord,
                choices, Map.of());

        return new Model(rows, intervals, rateCandidates, aggregates, notes, choices, marksByWord);
    }

    private static String binary(long word, int msb, int lsb) {
        StringBuilder sb = new StringBuilder();
        for (int bit = msb; bit >= lsb; bit--) {
            sb.append(Arinc429.bitAt(word, bit) ? '1' : '0');
        }
        return sb.toString();
    }
}
