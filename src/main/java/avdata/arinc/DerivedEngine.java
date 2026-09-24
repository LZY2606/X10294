package avdata.arinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs derived views by replaying the append-only event log against the immutable
 * source layer.
 *
 * <p>Key rules (see docs/arinc429.md):
 * <ul>
 *   <li>octal label display never changes underlying bits;</li>
 *   <li>BNR/BCD/discrete interpretation depends on the frozen dictionary version;</li>
 *   <li>rate switches and acquisition gaps preserve segment candidates (all legal ones);</li>
 *   <li>bad-parity words never enter aggregate values;</li>
 *   <li>parameter/source changes invalidate only the dependency-derived scope.</li>
 * </ul>
 */
public final class DerivedEngine {

    public static final String SCOPE_PARAMS = "params";
    public static final String SCOPE_PATCH = "source-patch";

    private final Repository repo;
    private final EventLog events;

    public DerivedEngine(Repository repo, EventLog events) {
        this.repo = repo;
        this.events = events;
    }

    // ------------------------------------------------------------------
    // derived DTOs
    // ------------------------------------------------------------------

    public static final class Params {
        public final String version;
        public final double highScheduleMs;
        public final double lowScheduleMs;
        public final String sinceEvent;

        public Params(String version, double highScheduleMs, double lowScheduleMs,
                      String sinceEvent) {
            this.version = version;
            this.highScheduleMs = highScheduleMs;
            this.lowScheduleMs = lowScheduleMs;
            this.sinceEvent = sinceEvent;
        }
    }

    public static final class Decision {
        public final String eventUid;
        public final String targetUid;
        public final String candidate;       // candidate key, e.g. "HIGH@2"
        public final boolean accepted;       // accepted vs rejected (both retained)
        public final boolean revoked;
        public final String note;

        public Decision(String eventUid, String targetUid, String candidate, boolean accepted,
                        boolean revoked, String note) {
            this.eventUid = eventUid;
            this.targetUid = targetUid;
            this.candidate = candidate;
            this.accepted = accepted;
            this.revoked = revoked;
            this.note = note == null ? "" : note;
        }

        public boolean active() {
            return !revoked;
        }
    }

    public static final class FieldView {
        public final String name;
        public final int fromBit;
        public final int toBit;
        public final String bits;
        public final String display;

        public FieldView(String name, int fromBit, int toBit, String bits, String display) {
            this.name = name;
            this.fromBit = fromBit;
            this.toBit = toBit;
            this.bits = bits;
            this.display = display;
        }
    }

    public static final class InterpretationView {
        public final String uid;
        public final String encoding;
        public final String dictionaryVersion;
        public final String ssmRules;
        public final boolean convertible;
        public final Double value;
        public final String unit;
        public final String diagnostic;
        public final List<String> discreteStates;
        public final String dictHitLabel;
        public final String dictHitName;

        InterpretationView(String uid, String encoding, String dictionaryVersion,
                           String ssmRules, boolean convertible, Double value, String unit,
                           String diagnostic, List<String> discreteStates, String hitLabel,
                           String hitName) {
            this.uid = uid;
            this.encoding = encoding;
            this.dictionaryVersion = dictionaryVersion;
            this.ssmRules = ssmRules;
            this.convertible = convertible;
            this.value = value;
            this.unit = unit;
            this.diagnostic = diagnostic;
            this.discreteStates = List.copyOf(discreteStates);
            this.dictHitLabel = hitLabel;
            this.dictHitName = hitName;
        }
    }

    public static final class WordView {
        public final String uid;
        public final int seq;
        public final String rawHex;
        public final String effectiveHex;   // after source-level reversal flag
        public final long receivedMs;
        public final String receivedText;
        public final String channel;
        public final boolean reversedWords;
        public final boolean parityValid;   // computed under configured polarity
        public final Boolean importParity;
        public final String labelOctal;
        public final String labelBits;
        public final int sdi;
        public final int ssm;
        public final String dataHex;
        public final String dictionaryVersion;
        public final List<FieldView> fields;
        public final InterpretationView interpretation;
        public final String patchedByEvent; // null for untouched baseline words

        WordView(String uid, int seq, String rawHex, String effectiveHex, long receivedMs,
                 String receivedText, String channel, boolean reversedWords, boolean parityValid,
                 Boolean importParity, String labelOctal, String labelBits, int sdi, int ssm,
                 String dataHex, String dictionaryVersion, List<FieldView> fields,
                 InterpretationView interpretation, String patchedByEvent) {
            this.uid = uid;
            this.seq = seq;
            this.rawHex = rawHex;
            this.effectiveHex = effectiveHex;
            this.receivedMs = receivedMs;
            this.receivedText = receivedText;
            this.channel = channel;
            this.reversedWords = reversedWords;
            this.parityValid = parityValid;
            this.importParity = importParity;
            this.labelOctal = labelOctal;
            this.labelBits = labelBits;
            this.sdi = sdi;
            this.ssm = ssm;
            this.dataHex = dataHex;
            this.dictionaryVersion = dictionaryVersion;
            this.fields = List.copyOf(fields);
            this.interpretation = interpretation;
            this.patchedByEvent = patchedByEvent;
        }
    }

    public static final class CandidateView {
        public final String key;             // HIGH@2
        public final String rateKey;
        public final double nominalKbps;
        public final int slotMultiple;
        public final double expectedMs;
        public boolean accepted;
        public boolean rejected;

        CandidateView(String key, String rateKey, double nominalKbps, int slotMultiple,
                      double expectedMs) {
            this.key = key;
            this.rateKey = rateKey;
            this.nominalKbps = nominalKbps;
            this.slotMultiple = slotMultiple;
            this.expectedMs = expectedMs;
        }
    }

    public static final class IntervalView {
        public final String uid;
        public final int fromSeq;
        public final int toSeq;
        public final double deltaMs;
        public final boolean acquisitionGap;
        public final List<CandidateView> candidates;

        IntervalView(String uid, int fromSeq, int toSeq, double deltaMs, boolean gap,
                     List<CandidateView> candidates) {
            this.uid = uid;
            this.fromSeq = fromSeq;
            this.toSeq = toSeq;
            this.deltaMs = deltaMs;
            this.acquisitionGap = gap;
            this.candidates = List.copyOf(candidates);
        }
    }

    public static final class SegmentView {
        public final String uid;
        public final int startSeq;
        public final int endSeq;
        public final List<String> rateCandidates;
        public final String chosenRate;      // may be null when ambiguous/undecided
        public final double medianIntervalMs;
        public final String gapReason;

        SegmentView(String uid, int startSeq, int endSeq, List<String> rateCandidates,
                    String chosenRate, double medianIntervalMs, String gapReason) {
            this.uid = uid;
            this.startSeq = startSeq;
            this.endSeq = endSeq;
            this.rateCandidates = List.copyOf(rateCandidates);
            this.chosenRate = chosenRate;
            this.medianIntervalMs = medianIntervalMs;
            this.gapReason = gapReason == null ? "" : gapReason;
        }
    }

    public static final class ChannelView {
        public final String channel;
        public final List<IntervalView> intervals;
        public final List<SegmentView> segments;

        ChannelView(String channel, List<IntervalView> intervals, List<SegmentView> segments) {
            this.channel = channel;
            this.intervals = List.copyOf(intervals);
            this.segments = List.copyOf(segments);
        }
    }

    public static final class AggregateView {
        public final String uid;
        public final String label;
        public final String encoding;
        public final int includedCount;
        public final int excludedParityCount;
        public final int excludedSsmCount;
        public final Double mean;
        public final String unit;

        AggregateView(String uid, String label, String encoding, int includedCount,
                      int excludedParityCount, int excludedSsmCount, Double mean, String unit) {
            this.uid = uid;
            this.label = label;
            this.encoding = encoding;
            this.includedCount = includedCount;
            this.excludedParityCount = excludedParityCount;
            this.excludedSsmCount = excludedSsmCount;
            this.mean = mean;
            this.unit = unit;
        }
    }

    public static final class Snapshot {
        public String caseId;
        public String branchId;
        public String atEventUid;
        public String depBasis;
        public Params params;
        public String activeDictionaryVersion;
        public List<WordView> words = new ArrayList<>();
        public List<ChannelView> channels = new ArrayList<>();
        public List<AggregateView> aggregates = new ArrayList<>();
        public List<Decision> decisions = new ArrayList<>();
        public List<Records.Event> events = new ArrayList<>();
        public List<String> invalidated = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // replay
    // ------------------------------------------------------------------

    /** Mutable replay accumulator. */
    private static final class Replay {
        String materialUid;
        String dictionaryVersion;
        Params params = new Params("1", RateSegments.HIGH_SCHEDULE_MS,
                RateSegments.LOW_SCHEDULE_MS, "GENESIS");
        /** seq -> patched raw hex (single direct source change). */
        Map<Integer, String> patchedRaw = new LinkedHashMap<>();
        String patchEvent;
        /** targetUid -> decisions (history retained). */
        Map<String, List<Decision>> decisions = new LinkedHashMap<>();
        Set<String> revokedEvents = new LinkedHashSet<>();
    }

    /** Reconstruct the state of {@code branchId} at event {@code atEventUid} (inclusive).
     *  Pass null for the branch head. Old conclusions use the dependency basis frozen on that
     *  event, never the newest dictionary/parameters. */
    public DerivedEngine.Snapshot snapshot(String caseId, String branchId, String atEventUid) {
        List<Records.Event> all = events.ancestry(branchId);
        List<Records.Event> until = new ArrayList<>();
        for (Records.Event e : all) {
            until.add(e);
            if (atEventUid != null && e.uid.equals(atEventUid)) {
                break;
            }
        }
        Replay replay = new Replay();
        String depBasis = "GENESIS";
        String lastEvent = null;
        for (Records.Event e : until) {
            applyEvent(caseId, e, replay);
            depBasis = e.depBasis;
            lastEvent = e.uid;
        }

        Snapshot s = new Snapshot();
        s.caseId = caseId;
        s.branchId = branchId;
        s.atEventUid = lastEvent;
        s.depBasis = depBasis;
        s.params = replay.params;
        s.activeDictionaryVersion = replay.dictionaryVersion;
        for (Records.Event e : until) {
            collectDecisions(e, replay, s);
        }
        if (replay.materialUid != null) {
            buildViews(caseId, replay, s);
        }
        s.events = until;
        return s;
    }

    private void applyEvent(String caseId, Records.Event e, Replay r) {
        switch (e.type) {
            case "IMPORTED":
                r.materialUid = str(e.payload, "materialUid");
                r.dictionaryVersion = str(e.payload, "dictionaryVersion");
                break;
            case "PARAM_REVISION":
                double high = num(e.payload, "highScheduleMs", r.params.highScheduleMs);
                double low = num(e.payload, "lowScheduleMs", r.params.lowScheduleMs);
                r.params = new Params(str(e.payload, "paramsVersion"), high, low, e.uid);
                break;
            case "DICT_REVISION":
                r.dictionaryVersion = str(e.payload, "dictionaryVersion");
                break;
            case "SOURCE_PATCH":
                r.patchedRaw.put((int) num(e.payload, "seq", -1),
                        str(e.payload, "rawHex"));
                r.patchEvent = e.uid;
                break;
            case "RULING_REVOKED":
                r.revokedEvents.add(str(e.payload, "revokesEvent"));
                break;
            default:
                // IMPORTED/BRANCH/RULING handled at collection time
                break;
        }
    }

    private void collectDecisions(Records.Event e, Replay r, Snapshot s) {
        if ("RULING".equals(e.type)) {
            Decision d = new Decision(e.uid, str(e.payload, "targetUid"),
                    str(e.payload, "candidate"), bool(e.payload, "accepted", true),
                    r.revokedEvents.contains(e.uid), str(e.payload, "note"));
            s.decisions.add(d);
            r.decisions.computeIfAbsent(d.targetUid, k -> new ArrayList<>()).add(d);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static double num(Map<String, Object> m, String k, double fallback) {
        Object v = m.get(k);
        if (v == null) {
            return fallback;
        }
        return ((Number) v).doubleValue();
    }

    private static boolean bool(Map<String, Object> m, String k, boolean fallback) {
        Object v = m.get(k);
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    // ------------------------------------------------------------------
    // view construction
    // ------------------------------------------------------------------

    private void buildViews(String caseId, Replay r, Snapshot s) {
        List<Records.Word> words = repo.loadWords(r.materialUid);
        Records.DictionaryVersion dict = repo.loadDictionary(caseId, r.dictionaryVersion);
        Map<String, Records.ChannelConfig> channels = repo.loadChannels(caseId);
        Map<String, List<Records.Word>> byChannel = new LinkedHashMap<>();
        Map<String, List<WordView>> viewsByChannel = new LinkedHashMap<>();

        for (Records.Word w : words) {
            WordView view = buildWordView(w, dict, r);
            s.words.add(view);
            byChannel.computeIfAbsent(w.channel, k -> new ArrayList<>()).add(w);
            viewsByChannel.computeIfAbsent(w.channel, k -> new ArrayList<>()).add(view);
        }

        for (Map.Entry<String, List<Records.Word>> en : byChannel.entrySet()) {
            Records.ChannelConfig cfg = channels.get(en.getKey());
            double high = cfg != null && cfg.channel.contains("")
                    ? pickHigh(cfg, r.params) : r.params.highScheduleMs;
            high = pickHigh(cfg, r.params);
            double low = pickLow(cfg, r.params);
            List<IntervalView> intervalViews = new ArrayList<>();
            List<SegmentView> segmentViews = new ArrayList<>();
            RateSegments.Result seg = RateSegments.segment(
                    StableId.of("ch", caseId, r.materialUid, en.getKey()),
                    seqs(en.getValue()), epochs(en.getValue()), high, low);
            Map<String, Decision> decisionByTarget = activeDecisions(r);
            for (RateSegments.Interval iv : seg.intervals) {
                List<CandidateView> cands = new ArrayList<>();
                for (RateSegments.RateCandidate c :
                        RateSegments.candidatesFor(iv.deltaMs, high, low,
                                RateSegments.TOLERANCE)) {
                    CandidateView cv = new CandidateView(c.rateKey + "@" + c.slotMultiple,
                            c.rateKey, c.nominalKbps, c.slotMultiple, c.expectedMs);
                    applyDecision(decisionByTarget, iv.uid, cv.key, cv);
                    cands.add(cv);
                }
                intervalViews.add(new IntervalView(iv.uid, iv.fromSeq, iv.toSeq, iv.deltaMs,
                        iv.acquisitionGap, cands));
            }
            for (RateSegments.Segment sg : seg.segments) {
                String chosen = chosenRate(decisionByTarget, sg.uid, sg.rateCandidates);
                segmentViews.add(new SegmentView(sg.uid, sg.startSeq, sg.endSeq,
                        sg.rateCandidates, chosen, sg.medianIntervalMs, sg.gapReason));
            }
            s.channels.add(new ChannelView(en.getKey(), intervalViews, segmentViews));
        }

        buildAggregates(caseId, r, s);
        buildInvalidation(caseId, r, s);
    }

    private double pickHigh(Records.ChannelConfig cfg, Params p) {
        // parameter revision (event-derived) wins; channel nominal is the imported baseline
        return p.highScheduleMs;
    }

    private double pickLow(Records.ChannelConfig cfg, Params p) {
        return p.lowScheduleMs;
    }

    private List<Integer> seqs(List<Records.Word> ws) {
        List<Integer> out = new ArrayList<>();
        for (Records.Word w : ws) {
            out.add(w.seq);
        }
        return out;
    }

    private List<Long> epochs(List<Records.Word> ws) {
        List<Long> out = new ArrayList<>();
        for (Records.Word w : ws) {
            out.add(applyEpochPatch(w));
        }
        return out;
    }

    private long applyEpochPatch(Records.Word w) {
        return w.receivedEpochMs;
    }

    private Map<String, Decision> activeDecisions(Replay r) {
        Map<String, Decision> out = new LinkedHashMap<>();
        for (List<Decision> ds : r.decisions.values()) {
            for (Decision d : ds) {
                if (d.active()) {
                    out.put(d.targetUid + "|" + d.candidate, d);
                }
            }
        }
        return out;
    }

    private void applyDecision(Map<String, Decision> decisions, String targetUid, String candKey,
                               CandidateView cv) {
        Decision d = decisions.get(targetUid + "|" + candKey);
        if (d != null) {
            cv.accepted = d.accepted;
            cv.rejected = !d.accepted;
        }
    }

    private String chosenRate(Map<String, Decision> decisions, String segmentUid,
                              List<String> candidates) {
        String chosen = null;
        for (String key : candidates) {
            Decision d = decisions.get(segmentUid + "|" + key);
            if (d != null && d.active() && d.accepted) {
                chosen = key;
            }
            if (d != null && d.active() && !d.accepted && key.equals(chosen)) {
                chosen = null;
            }
        }
        return chosen;
    }

    private WordView buildWordView(Records.Word w, Records.DictionaryVersion dict, Replay r) {
        int raw = ArincCodec.parseRawHex(w.rawHex);
        int effective = w.reversedWords ? ArincCodec.reverse32(raw) : raw;
        if (r.patchedRaw.containsKey(w.seq)) {
            effective = ArincCodec.parseRawHex(r.patchedRaw.get(w.seq));
        }
        boolean parityValid = ArincCodec.oddParityValid(effective);
        String labelOctal = ArincCodec.octalLabelText(effective);
        int sdi = ArincCodec.sdi(effective);
        int ssm = ArincCodec.ssm(effective);
        int data = ArincCodec.dataBits(effective);

        List<FieldView> fields = new ArrayList<>();
        fields.add(new FieldView("label", 1, 8,
                ArincCodec.bitString(effective).substring(0, 8),
                "octal " + labelOctal + " (display only; bits unchanged)"));
        fields.add(new FieldView("sdi", 9, 10,
                ArincCodec.bitString(effective).substring(8, 10), Integer.toString(sdi)));
        fields.add(new FieldView("data", 11, 29,
                ArincCodec.bitString(effective).substring(10, 29),
                String.format("0x%05X", data)));
        fields.add(new FieldView("ssm", 30, 31,
                ArincCodec.bitString(effective).substring(29, 31),
                ArincCodec.ssmText(ssm)));
        fields.add(new FieldView("parity", 32, 32,
                ArincCodec.bitString(effective).substring(31, 32),
                parityValid ? "odd OK" : "BAD PARITY"));

        InterpretationView iv = null;
        if (dict != null) {
            ArincCodec.LabelDef def = dict.find(labelOctal, sdi);
            String interpUid = StableId.of("interp", w.uid, dict.version, r.params.version);
            if (def == null) {
                iv = new InterpretationView(interpUid, "NONE", dict.version,
                        dict.ssmRules.name(), false, null, "",
                        "no dictionary hit for label " + labelOctal + " SDI " + sdi,
                        List.of(), "", "");
            } else {
                ArincCodec.Interpretation v = ArincCodec.interpret(effective, def,
                        String.format("0x%08X", effective), dict.ssmRules);
                iv = new InterpretationView(interpUid, def.encoding.name(), dict.version,
                        dict.ssmRules.name(), v.convertible, v.value, v.unit, v.diagnostic,
                        v.discreteStates, def.label, def.name);
            }
        }
        return new WordView(w.uid, w.seq, String.format("0x%08X", raw),
                String.format("0x%08X", effective), w.receivedEpochMs, w.receivedText,
                w.channel, w.reversedWords, parityValid, w.importParity, labelOctal,
                String.format("%08s", Integer.toBinaryString(ArincCodec.labelField(effective)))
                        .replace(' ', '0'),
                sdi, ssm, String.format("0x%05X", data),
                dict == null ? "" : dict.version, fields, iv,
                r.patchedRaw.containsKey(w.seq) ? r.patchEvent : null);
    }

    /** Aggregate engineering values: bad-parity words never enter the aggregate; unconvertible
     *  SSM values are counted separately and kept in place instead of dropped. */
    private void buildAggregates(String caseId, Replay r, Snapshot s) {
        Map<String, List<WordView>> groups = new LinkedHashMap<>();
        for (WordView w : s.words) {
            if (w.interpretation == null || w.interpretation.dictHitLabel.isEmpty()) {
                continue;
            }
            if ("DISCRETE".equals(w.interpretation.encoding)) {
                continue;
            }
            groups.computeIfAbsent(w.interpretation.dictHitLabel + "|"
                    + w.interpretation.encoding + "|" + w.channel, k -> new ArrayList<>()).add(w);
        }
        for (Map.Entry<String, List<WordView>> en : groups.entrySet()) {
            int parityExcluded = 0;
            int ssmExcluded = 0;
            double sum = 0;
            int n = 0;
            String unit = "";
            String[] parts = en.getKey().split("\\|");
            for (WordView w : en.getValue()) {
                if (!w.parityValid) {
                    parityExcluded++;
                    continue;
                }
                if (!w.interpretation.convertible || w.interpretation.value == null) {
                    ssmExcluded++;
                    continue;
                }
                sum += w.interpretation.value;
                n++;
                unit = w.interpretation.unit;
            }
            String uid = StableId.of("agg", caseId, r.materialUid, r.dictionaryVersion,
                    r.params.version, en.getKey());
            Double mean = n == 0 ? null : sum / n;
            s.aggregates.add(new AggregateView(uid, parts[0], parts[1], n, parityExcluded,
                    ssmExcluded, mean, unit));
        }
    }

    /** Compute the invalidation scope of parameter/source changes: only objects derived from
     *  the changed dependency are flagged. Source words themselves are never overwritten. */
    private void buildInvalidation(String caseId, Replay r, Snapshot s) {
        if ("GENESIS".equals(r.params.sinceEvent) && r.patchEvent == null) {
            return;
        }
        Set<String> out = new LinkedHashSet<>();
        if (!"GENESIS".equals(r.params.sinceEvent)) {
            // rate params affect intervals/segments/aggregates only, not bit fields/parity
            for (ChannelView ch : s.channels) {
                for (IntervalView iv : ch.intervals) {
                    out.add(iv.uid);
                }
                for (SegmentView sg : ch.segments) {
                    out.add(sg.uid);
                }
            }
            for (AggregateView a : s.aggregates) {
                out.add(a.uid);
            }
        }
        if (r.patchEvent != null) {
            // a single-source patch invalidates that word's derived views, its adjacent
            // intervals, and aggregates of that label
            Set<String> aggLabels = new LinkedHashSet<>();
            for (Integer seq : r.patchedRaw.keySet()) {
                WordView w = s.words.stream().filter(x -> x.seq == seq).findFirst().orElse(null);
                if (w == null) {
                    continue;
                }
                out.add(w.uid);
                if (w.interpretation != null) {
                    out.add(w.interpretation.uid);
                    aggLabels.add(w.interpretation.dictHitLabel);
                }
                for (ChannelView ch : s.channels) {
                    for (IntervalView iv : ch.intervals) {
                        if (iv.fromSeq == seq || iv.toSeq == seq) {
                            out.add(iv.uid);
                        }
                    }
                    for (SegmentView sg : ch.segments) {
                        if (seq >= sg.startSeq && seq <= sg.endSeq) {
                            out.add(sg.uid);
                        }
                    }
                }
            }
            for (AggregateView a : s.aggregates) {
                if (aggLabels.contains(a.label)) {
                    out.add(a.uid);
                }
            }
        }
        s.invalidated = new ArrayList<>(out);
    }
}
