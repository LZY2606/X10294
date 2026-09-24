package a429.event;

import a429.casepkg.CasePackage;
import a429.domain.Aggregator;
import a429.domain.ChannelConfig;
import a429.domain.DecodedWord;
import a429.domain.DictVersion;
import a429.domain.RateEngine;
import a429.domain.RawWord;

import java.util.List;
import java.util.Map;

/** Immutable-ish working state of one branch, rebuilt by replaying its event log. */
public class BranchState {
    public String caseId;
    public String branchId;
    public String title;
    public String rulesVersion;
    public Map<String, RawWord> words = new java.util.LinkedHashMap<>();
    public Map<String, ChannelConfig> channels = new java.util.LinkedHashMap<>();
    public Map<String, DictVersion> dictionaries = new java.util.LinkedHashMap<>();
    public String activeDictRef;
    public RateEngine.Params rateParams = RateEngine.Params.standard(20.0, 0.12);
    public String labelConvention = "CANONICAL";
    public List<Analysis> analyses = new java.util.ArrayList<>();
    public List<Ruling> rulings = new java.util.ArrayList<>();
    public List<Invalidation> invalidations = new java.util.ArrayList<>();
    public List<CounterexampleResult> counterexamples = new java.util.ArrayList<>();
    public long headSeq;

    public Map<String, Aggregator.Aggregate> aggregates = Map.of();
    public List<DecodedWord> decodedRows = List.of();
    public Map<String, RateEngine.ChannelResult> rateResults = Map.of();
    public CasePackage pkg;
}
