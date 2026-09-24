package a429.event;

import a429.db.Json;
import a429.domain.Aggregator;
import a429.domain.DecodedWord;
import a429.domain.DictVersion;
import a429.domain.Interpreter;
import a429.domain.RateEngine;
import a429.domain.RawWord;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recomputes derived views (bit-field decode, adjacent intervals, rate
 * candidates, engineering values, aggregates) from the preserved originals.
 * Produces stable analysis identities and explicit dependency keys so a
 * later parameter/reference revision can compute an exact invalidation scope.
 */
public class DerivationEngine {

    private final Interpreter interpreter = new Interpreter();
    private final RateEngine rateEngine = new RateEngine();
    private final Aggregator aggregator = new Aggregator();

    public record Derived(List<DecodedWord> rows,
                          Map<String, RateEngine.ChannelResult> rateResults,
                          List<Aggregator.Aggregate> aggregates,
                          List<AnalysisSeed> seeds) {
    }

    public record AnalysisSeed(String analysisId, String kind, String targetId,
                               List<String> candidates, boolean unique,
                               List<String> dependencyKeys, String basisSummary,
                               ObjectNode detail) {
    }

    public Derived derive(BranchState state) {
        DictVersion dict = state.dictionaries.get(state.activeDictRef);
        List<DecodedWord> rows = new ArrayList<>();
        for (RawWord w : state.words.values()) {
            rows.add(new DecodedWord(w, interpreter.decode(w, dict, state.labelConvention)));
        }
        Map<String, List<RawWord>> byChannel = new LinkedHashMap<>();
        for (RawWord w : state.words.values()) {
            byChannel.computeIfAbsent(w.channelId(), k -> new ArrayList<>()).add(w);
        }
        Map<String, RateEngine.ChannelResult> rateResults = new LinkedHashMap<>();
        List<AnalysisSeed> seeds = new ArrayList<>();
        for (Map.Entry<String, List<RawWord>> e : byChannel.entrySet()) {
            RateEngine.ChannelResult result = rateEngine.analyse(
                    e.getKey(), e.getValue(), state.rateParams,
                    "ana-" + state.branchId + "-ch-" + e.getKey());
            rateResults.put(e.getKey(), result);
            for (var seg : result.segments()) {
                if (!"TRAFFIC".equals(seg.kind())) {
                    continue;
                }
                ObjectNode detail = Json.obj();
                detail.put("segmentId", seg.segmentId());
                detail.put("channelId", seg.channelId());
                detail.put("startSeq", seg.startSeq());
                detail.put("endSeq", seg.endSeq());
                detail.putPOJO("candidateSpeedsBps", seg.candidateSpeedsBps());
                detail.put("meanIntervalMs", seg.meanIntervalMs());
                detail.putPOJO("intervalIds", seg.intervalIds());
                List<String> deps = List.of(
                        "params:gapThreshold=" + state.rateParams.gapThresholdMs(),
                        "params:tolerance=" + state.rateParams.toleranceFraction(),
                        "channel:" + seg.channelId());
                String summary = "segment " + seg.segmentId() + " speeds=" + seg.candidateSpeedsBps()
                        + " meanInterval=" + seg.meanIntervalMs() + "ms";
                seeds.add(new AnalysisSeed(seg.segmentId(), "RATE_SEGMENT", seg.segmentId(),
                        List.copyOf(seg.candidateSpeedsBps().stream().map(String::valueOf).toList()),
                        seg.unique(), deps, summary, detail));
            }
        }
        List<Aggregator.Aggregate> aggregates = aggregator.aggregate(rows);
        for (Aggregator.Aggregate agg : aggregates) {
            ObjectNode detail = Json.obj();
            detail.put("key", agg.key());
            detail.put("channelId", agg.channelId());
            detail.put("label", agg.canonicalLabel());
            detail.put("dictVersion", agg.dictVersion());
            detail.put("included", agg.includedCount());
            detail.put("parityExcluded", agg.parityExcludedCount());
            detail.put("stateExcluded", agg.stateExcludedCount());
            detail.put("unconverted", agg.unconvertedCount());
            if (agg.mean() != null) {
                detail.put("mean", agg.mean());
            }
            detail.putPOJO("includedWordIds", agg.includedWordIds());
            detail.putPOJO("excludedWordIds", agg.excludedWordIds());
            ArrayNode reasons = detail.putArray("excludedReasons");
            agg.excludedReasons().forEach(reasons::add);
            List<String> deps = List.of(
                    "dict:" + agg.dictVersion(),
                    "channel:" + agg.channelId(),
                    "label:" + agg.canonicalLabel());
            seeds.add(new AnalysisSeed("agg-" + state.branchId + "-" + agg.key(),
                    "ENGINEERING_AGGREGATE", agg.key(),
                    agg.mean() == null ? List.of() : List.of(String.valueOf(agg.mean())),
                    true, deps,
                    agg.name() + " n=" + agg.includedCount()
                            + " parityExcluded=" + agg.parityExcludedCount()
                            + " stateExcluded=" + agg.stateExcludedCount(), detail));
        }
        return new Derived(List.copyOf(rows), Map.copyOf(rateResults), aggregates,
                List.copyOf(seeds));
    }
}
