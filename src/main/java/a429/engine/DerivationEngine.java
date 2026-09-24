package a429.engine;

import a429.codec.Words;
import a429.decode.WordDecoder;
import a429.decode.WordFields;
import a429.ids.Ids;
import a429.interpret.EngValue;
import a429.interpret.Interpreter;
import a429.model.Dictionary;
import a429.model.RateParams;
import a429.model.RawCapture;
import a429.rate.Interval;
import a429.rate.RateCandidate;
import a429.rate.RateSegmenter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure derivation layer. Computes every review value with a stable UID, and builds the provenance
 * graph used to calculate invalidation blast radius. No normalization replaces the source.
 */
public final class DerivationEngine {
    private DerivationEngine() {}

    public record Derived(Snapshot snapshot, List<Findings.WordFinding> words,
                          List<Findings.EngFinding> engValues,
                          List<Findings.IntervalFinding> intervals,
                          List<Findings.RateScopeFinding> rateScopes,
                          List<Findings.AggregateFinding> aggregates,
                          DependencyGraph graph) {
    }

    public static Derived derive(Snapshot snapshot) {
        DependencyGraph graph = new DependencyGraph();
        List<Snapshot.LiveWord> live = snapshot.words().values().stream()
                .filter(Snapshot.LiveWord::live)
                .toList();

        List<Findings.WordFinding> wordFindings = new ArrayList<>();
        List<Findings.EngFinding> engFindings = new ArrayList<>();
        Dictionary dictionary = snapshot.dictionary();
        String dictNode = dictNodeId(dictionary.dictId(), dictionary.version());
        graph.node(dictNode);
        String paramsNode = paramsNodeId(snapshot.params().paramsId(),
                snapshot.params().version());
        graph.node(paramsNode);

        for (Snapshot.LiveWord liveWord : live) {
            RawCapture capture = liveWord.capture();
            int word = capture.word();
            String wordNode = liveWord.wordUid();
            graph.node(wordNode);
            String fieldUid = Ids.fieldUid(wordNode);
            String octalUid = Ids.labelOctalUid(wordNode);
            graph.edge(wordNode, fieldUid, "DECODES");
            graph.edge(wordNode, octalUid, "OCTAL_PRESENTS");
            graph.edge(dictNode, octalUid, "DICT_REF");

            WordFields fields = WordDecoder.decode(word, liveWord.reversedCapture());
            wordFindings.add(new Findings.WordFinding(wordNode, fieldUid, octalUid,
                    capture.seq(), capture.tsNanos(), capture.channel(),
                    String.format("%08x", word), fields.parityOk(), liveWord.reversedCapture(),
                    fields.labelFieldHex(), fields.labelOctal(),
                    Integer.parseInt(fields.labelOctal(), 8), fields.sdi(), fields.ssmBits(),
                    fields.ssmName(), fields.dataHex(), dictionary.version()));

            String engUid = Ids.engUid(wordNode, dictionary.dictId(), dictionary.version());
            graph.edge(wordNode, engUid, "INTERPRETS");
            graph.edge(dictNode, engUid, "DICT_DEFINES");
            EngValue eng = Interpreter.interpret(
                    liveWord.reversedCapture() ? Words.reverseWord(word) : word, dictionary);
            engFindings.add(new Findings.EngFinding(engUid, wordNode, capture.seq(),
                    capture.channel(), fields.labelOctal(), eng.valid(),
                    eng.valid() ? eng.value() : Double.NaN, eng.display(), eng.unit(),
                    eng.diagnosis(), eng.dictVersion(), eng.dictEntryUid()));
        }

        Set<Integer> badParity = new java.util.HashSet<>();
        Map<Integer, String> wordUidBySeq = new LinkedHashMap<>();
        for (Snapshot.LiveWord liveWord : live) {
            wordUidBySeq.put(liveWord.capture().seq(), liveWord.wordUid());
        }
        for (Findings.WordFinding finding : wordFindings) {
            if (!finding.parityOk()) {
                badParity.add(finding.seq());
            }
        }

        List<RawCapture> captures = live.stream().map(Snapshot.LiveWord::capture).toList();
        Map<String, List<Interval>> intervalsByChannel =
                RateSegmenter.intervalsByChannel(captures, badParity);
        List<Findings.IntervalFinding> intervalFindings = new ArrayList<>();
        List<Findings.RateScopeFinding> scopeFindings = new ArrayList<>();
        RateParams params = snapshot.params();
        for (Map.Entry<String, List<Interval>> entry : intervalsByChannel.entrySet()) {
            String channel = entry.getKey();
            String scopeUid = Ids.rateScopeUid(channel, params.version());
            for (Interval interval : entry.getValue()) {
                String intervalUid = Ids.intervalUid(channel, interval.leftSeq(),
                        interval.rightSeq());
                graph.edge(wordUidBySeq.get(interval.leftSeq()), intervalUid, "TIMES");
                graph.edge(wordUidBySeq.get(interval.rightSeq()), intervalUid, "TIMES");
                graph.edge(paramsNode, intervalUid, "PARAMS_APPLY");
                List<Integer> rates = new ArrayList<>();
                if (!RateSegmenter.isAcquisitionGap(interval.gapNanos(), params)) {
                    rates.addAll(RateSegmenter.compatibleRates(interval.gapNanos(), params));
                }
                intervalFindings.add(new Findings.IntervalFinding(intervalUid, channel,
                        interval.leftSeq(), interval.rightSeq(), interval.gapNanos(), rates,
                        RateSegmenter.isAcquisitionGap(interval.gapNanos(), params),
                        interval.leftParityBad(), interval.rightParityBad(), params.version()));
                graph.edge(intervalUid, scopeUid, "SEGMENTS");
            }
            List<RateCandidate> candidates = RateSegmenter.segment(channel, entry.getValue(),
                    params);
            Snapshot.Decision decision = snapshot.adjudications().get(scopeUid);
            scopeFindings.add(new Findings.RateScopeFinding(scopeUid, channel, params.version(),
                    candidates, decision));
            graph.edge(paramsNode, scopeUid, "PARAMS_DEFINE");
        }

        List<Findings.AggregateFinding> aggregates = aggregate(wordFindings, engFindings,
                dictionary, graph, dictNode);

        return new Derived(snapshot, wordFindings, engFindings, intervalFindings,
                scopeFindings, aggregates, graph);
    }

    private static List<Findings.AggregateFinding> aggregate(
            List<Findings.WordFinding> words, List<Findings.EngFinding> engValues,
            Dictionary dictionary, DependencyGraph graph, String dictNode) {
        Map<String, List<Findings.EngFinding>> groups = new LinkedHashMap<>();
        Map<String, String> channels = new LinkedHashMap<>();
        Map<String, String> wordByUid = new LinkedHashMap<>();
        for (Findings.WordFinding word : words) {
            wordByUid.put(word.wordUid(), word.labelOctal());
        }
        for (Findings.EngFinding eng : engValues) {
            String key = eng.channel() + "\u001f" + eng.labelOctal();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(eng);
            channels.put(key, eng.channel());
        }
        List<Findings.AggregateFinding> result = new ArrayList<>();
        for (Map.Entry<String, List<Findings.EngFinding>> entry : groups.entrySet()) {
            String[] key = entry.getKey().split("\u001f");
            String channel = key[0];
            String label = key[1];
            List<Findings.EngFinding> members = new ArrayList<>();
            List<Findings.AggregateFinding.Exclusion> exclusions = new ArrayList<>();
            for (Findings.EngFinding eng : entry.getValue()) {
                Findings.WordFinding word = words.stream()
                        .filter(w -> w.wordUid().equals(eng.wordUid())).findFirst().orElseThrow();
                if (!word.parityOk()) {
                    exclusions.add(new Findings.AggregateFinding.Exclusion(
                            eng.wordUid(), word.seq(), "BAD_PARITY"));
                } else if (!eng.valid()) {
                    exclusions.add(new Findings.AggregateFinding.Exclusion(
                            eng.wordUid(), word.seq(), eng.diagnosis()));
                } else {
                    members.add(eng);
                }
            }
            List<String> memberUids = members.stream().map(Findings.EngFinding::wordUid).toList();
            String uid = Ids.aggregateUid(channel, label, dictionary.dictId(), memberUids);
            double mean = members.isEmpty() ? Double.NaN
                    : members.stream().mapToDouble(Findings.EngFinding::value).average().orElse(Double.NaN);
            String unit = members.isEmpty()
                    ? (entry.getValue().stream().findFirst().map(Findings.EngFinding::unit)
                            .orElse(""))
                    : members.get(0).unit();
            for (String memberUid : memberUids) {
                graph.edge(memberUid, uid, "AGGREGATES");
            }
            for (Findings.AggregateFinding.Exclusion exclusion : exclusions) {
                graph.edge(exclusion.wordUid(), uid, "EXCLUDED:" + exclusion.reason());
            }
            graph.edge(dictNode, uid, "DICT_DEFINES");
            result.add(new Findings.AggregateFinding(uid, channel, label, unit, mean,
                    members.size(), memberUids, exclusions, dictionary.version()));
        }
        return result;
    }

    public static String seqNode(String channel, int seq) {
        return "seq:" + channel + ":" + seq;
    }

    public static String wordSeqNodeId(String wordUid) {
        return "wordref:" + wordUid;
    }

    public static String dictNodeId(String dictId, String version) {
        return "dict:" + dictId + ":" + version;
    }

    public static String paramsNodeId(String paramsId, String version) {
        return "params:" + paramsId + ":" + version;
    }
}
