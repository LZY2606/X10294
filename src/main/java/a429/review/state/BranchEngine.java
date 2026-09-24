package a429.review.state;

import a429.review.domain.Arinc429;
import a429.review.domain.Dictionary;
import a429.review.domain.Ids;
import a429.review.decode.Decoder;
import a429.review.decode.RateSegmenter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure replay: event log -> {@link BranchState}. The reducer is deterministic,
 * so jumping back to any old event restores that event's dictionary version,
 * parameters and visible conclusions. Newer computations never overwrite older
 * snapshots; invalidation is computed only over objects that actually depend
 * on the changed source/parameter.
 */
public final class BranchEngine {

    private final Map<String, Dictionary> dictionaries;
    private final Material material;

    public BranchEngine(Map<String, Dictionary> dictionaries, Material material) {
        this.dictionaries = dictionaries;
        this.material = material;
    }

    public BranchState initial(String branchId, String dictionaryVersion) {
        return new BranchState(branchId, material.materialId(), dictionaryVersion,
                Arinc429.DECODER_VERSION,
                new BranchState.Params(15.0, 5.0, List.of(12500.0, 100000.0), 0, "initial"),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public BranchState apply(BranchState s, EventBean e) {
        return switch (e.type) {
            case "PARAMS_REVISED" -> reviseParams(s, e);
            case "RATE_DECIDED", "ENCODING_DECIDED" -> decide(s, e);
            case "DECISION_UNDONE" -> undo(s, e);
            default -> s;
        };
    }

    /** Lightweight event application that preserves an existing read model. */
    public BranchState applyOnModel(BranchState s, EventBean e) {
        return switch (e.type) {
            case "PARAMS_REVISED" -> markParamsInvalidated(s, e),
            case "RATE_DECIDED", "ENCODING_DECIDED" -> decide(s, e);
            case "DECISION_UNDONE" -> undo(s, e);
            default -> s;
        };
    }

    private BranchState markParamsInvalidated(BranchState s, EventBean e) {
        BranchState.Params p = JsonCodecs.PARAMS_READER.fromJson(e.detailJson);
        BranchState.Params newParams = new BranchState.Params(p.tolerancePct(), p.gapFactor(),
                p.nominalRates(), e.seq, p.revisionReason());
        List<BranchState.InvalidatedRef> inv = new ArrayList<>(s.invalidated());
        List<String> advisory = new ArrayList<>(s.advisory());
        for (StreamState st : s.streams()) {
            for (int i = 0; i < st.segments().size(); i++) {
                String oldUid = Ids.segmentUid(st.streamUid(), i, "p" + s.params().effectiveEventSeq());
                inv.add(new BranchState.InvalidatedRef(oldUid, "RATE_SEGMENT", e.seq,
                        "PARAMS_REVISED: dependency on rate params@" + s.params().effectiveEventSeq()
                                + " changed; old conclusion retained, not replaced"));
            }
        }
        advisory.add("PARAMS_REVISED@" + e.seq + " invalidated " + countSegments(s)
                + " prior rate segment(s); re-segment with new params required");
        return materialize(s, s.dictionaryVersion(), newParams, s.decisions(),
                currentRevisions(s), List.copyOf(inv), List.copyOf(advisory));
    }

    private Map<Integer, ActiveRevision> currentRevisions(BranchState s) {
        Map<Integer, ActiveRevision> out = new LinkedHashMap<>();
        for (WordView w : s.words()) {
            if (w.patched()) {
                out.put(w.ordinal(), new ActiveRevision(w.ordinal(), w.rawHex(), w.timestampRaw(),
                        w.timeNs(), w.channel(), w.patchSeq(), false));
            }
        }
        return out;
    }

    /** Materialize the full visible read model after structural events. */
    public BranchState materialize(BranchState base,
                                   String dictionaryVersion,
                                   BranchState.Params params,
                                   List<BranchState.Decision> decisions,
                                   Map<Integer, ActiveRevision> activeRevisions,
                                   List<BranchState.InvalidatedRef> invalidated,
                                   List<String> advisory) {
        Dictionary dict = dictionaries.get(dictionaryVersion);
        Decoder decoder = new Decoder(dict);

        List<WordView> views = new ArrayList<>();
        for (RawWord rw : material.rawWords()) {
            ActiveRevision rev = activeRevisions.get(rw.ordinal());
            String hex = rev == null ? rw.rawHex() : rev.rawHex();
            int pseq = rev == null ? -1 : rev.seq();
            views.add(buildView(rw, hex, pseq, dict.version(), decoder));
        }
        for (ActiveRevision rev : activeRevisions.values()) {
            if (rev.injected()) {
                RawWord rw = new RawWord(material.materialId(), rev.ordinal(), rev.rawHex(),
                        rev.timestampRaw(), rev.timeNs(), rev.channel());
                views.add(buildView(rw, rev.rawHex(), rev.seq(), dict.version(), decoder));
            }
        }
        views.sort(Comparator.comparingInt(WordView::ordinal));

        List<StreamState> streams = buildStreams(views, params, decisions, invalidated);
        return new BranchState(base.branchId(), material.materialId(), dict.version(),
                Arinc429.DECODER_VERSION, params, List.copyOf(views), List.copyOf(streams),
                List.copyOf(decisions), List.copyOf(invalidated), List.copyOf(advisory));
    }

    /** An active direct-source revision visible in the current replay. */
    public record ActiveRevision(int ordinal, String rawHex, String timestampRaw, long timeNs,
                                 String channel, int seq, boolean injected) {
    }

    private WordView buildView(RawWord rw, String hex, int patchSeq, String dictVersion, Decoder decoder) {
        long word = Arinc429.parseWord(hex);
        String uid = patchSeq < 0
                ? Ids.wordUid(rw.materialId(), rw.ordinal(), word)
                : Ids.patchedWordUid(rw.materialId(), rw.ordinal(), word, patchSeq);
        Decoder.Interpretation interp = decoder.interpret(word);
        String interpUid = Ids.interpUid(uid, dictVersion);
        return new WordView(uid, rw.materialId(), rw.ordinal(), hex, rw.timestampRaw(),
                rw.timeNs(), rw.channel(), Arinc429.oddParityOk(word) ? "ODD_OK" : "ODD_FAIL",
                Arinc429.octalLabel(word), Arinc429.labelBitString(word),
                Arinc429.sdi(word), Arinc429.ssm(word), Arinc429.data(word),
                dictVersion, patchSeq >= 0, Math.max(patchSeq, -1), interp, interpUid);
    }

    private List<StreamState> buildStreams(List<WordView> views, BranchState.Params params,
                                           List<BranchState.Decision> decisions,
                                           List<BranchState.InvalidatedRef> invalidated) {
        Map<String, List<WordView>> groups = new LinkedHashMap<>();
        for (WordView w : views) {
            String key = w.channel() + "|" + w.octalLabel();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
        }
        String effParams = "p" + params.effectiveEventSeq();
        List<StreamState> out = new ArrayList<>();
        for (Map.Entry<String, List<WordView>> en : groups.entrySet()) {
            String[] parts = en.getKey().split("\\|", 2);
            String channel = parts[0];
            String oct = parts[1];
            String streamUid = Ids.streamUid(material.materialId(), channel, oct);
            List<WordView> ws = en.getValue();

            List<RateSegmenter.Point> points = new ArrayList<>();
            for (WordView w : ws) {
                boolean eligible = hasAggregateEligibleCandidate(w);
                String reason = eligible ? null : exclusionReason(w);
                points.add(new RateSegmenter.Point(w.wordUid(), w.timeNs(), eligible, reason));
            }
            RateSegmenter seg = new RateSegmenter(params.nominalRates(),
                    params.tolerancePct(), params.gapFactor());
            RateSegmenter.Result res = seg.segment(channel, oct, points);

            List<StreamState.Aggregate> aggs = new ArrayList<>();
            for (RateSegmenter.Segment s : res.segments()) {
                String segUid = Ids.segmentUid(streamUid, s.index(), effParams);
                aggs.add(aggregate(segUid, s, ws));
            }
            List<String> excludedUids = res.excluded().stream().map(RateSegmenter.Point::wordUid).toList();
            out.add(new StreamState(streamUid, channel, oct, res.intervals(),
                    res.segments(), List.copyOf(aggs), excludedUids));
        }
        return out;
    }

    private boolean hasAggregateEligibleCandidate(WordView w) {
        if (!w.interpretation().parityOk()) {
            return false;
        }
        for (Decoder.Candidate c : w.interpretation().candidates()) {
            if (c.aggregateEligible()) {
                return true;
            }
        }
        return false;
    }

    private String exclusionReason(WordView w) {
        if (!w.interpretation().parityOk()) {
            return "BAD_PARITY";
        }
        for (Decoder.Candidate c : w.interpretation().candidates()) {
            if (!c.ssmNormal()) {
                return "SSM_" + c.ssmMeaning();
            }
            if (!c.convertible()) {
                return "UNCONVERTIBLE";
            }
        }
        return "NOT_ELIGIBLE";
    }

    private StreamState.Aggregate aggregate(String segUid, RateSegmenter.Segment s, List<WordView> ws) {
        String unit = null;
        List<Double> values = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (WordView w : ws) {
            boolean within = w.timeNs() >= s.startNs() && w.timeNs() <= s.endNs()
                    && w.channel().equals(s.channel()) && w.octalLabel().equals(s.octalLabel());
            if (!within) {
                continue;
            }
            Decoder.Candidate chosen = firstEligible(w);
            if (chosen == null) {
                reasons.add(w.wordUid() + ":" + exclusionReason(w));
                continue;
            }
            unit = chosen.unit();
            if (chosen.dataType() == Dictionary.DataType.BNR && chosen.bnrValue() != null) {
                values.add(chosen.bnrValue());
            }
        }
        Double mn = null, mx = null, mean = null;
        if (!values.isEmpty()) {
            mn = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            mx = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        return new StreamState.Aggregate(segUid, values.size(), mn, mx, mean, unit, List.copyOf(reasons));
    }

    private Decoder.Candidate firstEligible(WordView w) {
        for (Decoder.Candidate c : w.interpretation().candidates()) {
            if (c.aggregateEligible()) {
                return c;
            }
        }
        return null;
    }

    private int countSegments(BranchState s) {
        int n = 0;
        for (StreamState st : s.streams()) {
            n += st.segments().size();
        }
        return n;
    }

    private int maxPatch(BranchState s) {
        int m = -1;
        for (WordView w : s.words()) {
            m = Math.max(m, w.patchSeq());
        }
        return m;
    }

    private BranchState decide(BranchState s, EventBean e) {
        BranchState.Decision d = JsonCodecs.DECISION_READER.fromJson(e.detailJson);
        List<BranchState.Decision> ds = new ArrayList<>(s.decisions());
        // optimistic concurrency: caller must be on top of this target's last decision
        String targetUid = d.kind().equals("RATE") ? d.segmentUid() : d.wordUid();
        long last = 0;
        for (BranchState.Decision prior : ds) {
            String ptu = prior.kind().equals("RATE") ? prior.segmentUid() : prior.wordUid();
            if (ptu.equals(targetUid)) {
                last = Math.max(last, prior.madeAtSeq());
            }
        }
        if (d.baseVersion() < last) {
            return BranchConflict.conflict(s, d, last);
        }
        ds.add(new BranchState.Decision(d.decisionUid(), d.kind(), d.segmentUid(), d.candidateKey(),
                d.wordUid(), e.seq, -1, d.actor(), d.note(), d.baseVersion(), true));
        List<String> advisory = new ArrayList<>(s.advisory());
        advisory.add((d.kind() + "_ACCEPTED@" + e.seq + " target=" + targetUid
                + " candidate=" + d.candidateKey()));
        return new BranchState(s.branchId(), s.materialId(), s.dictionaryVersion(), s.decoderVersion(),
                s.params(), s.words(), s.streams(), List.copyOf(ds), s.invalidated(), List.copyOf(advisory));
    }

    private BranchState undo(BranchState s, EventBean e) {
        String decisionUid = JsonCodecs.singleString(e.detailJson, "decisionUid");
        List<BranchState.Decision> ds = new ArrayList<>();
        for (BranchState.Decision d : s.decisions()) {
            if (d.decisionUid().equals(decisionUid)) {
                ds.add(new BranchState.Decision(d.decisionUid(), d.kind(), d.segmentUid(), d.candidateKey(),
                        d.wordUid(), d.madeAtSeq(), e.seq, d.actor(), d.note(), d.baseVersion(), false));
            } else {
                ds.add(d);
            }
        }
        List<String> advisory = new ArrayList<>(s.advisory());
        advisory.add("UNDO@" + e.seq + " restored prior branch for " + decisionUid);
        return new BranchState(s.branchId(), s.materialId(), s.dictionaryVersion(), s.decoderVersion(),
                s.params(), s.words(), s.streams(), List.copyOf(ds), s.invalidated(), List.copyOf(advisory));
    }

    /** Minimal typed event bean used by the reducer. */
    public record EventBean(long seq, String type, String detailJson) {
    }
}
