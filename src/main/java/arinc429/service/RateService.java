package arinc429.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers link-rate segments from retained receive timestamps.
 *
 * ARINC 429 word times are 32 bits: 2560 us at 12.5 kbps and 320 us at
 * 100 kbps. A gap larger than the rule's acquisition-gap threshold is an
 * acquisition gap, not a rate switch. Every legal interpretation is kept;
 * the bench never silently picks one when several are still consistent.
 */
public final class RateService {

    static final int LOW_BPS = 12500;
    static final int HIGH_BPS = 100000;
    static final double LOW_GAP_US = 2560.0;
    static final double HIGH_GAP_US = 320.0;

    public Map<String, Object> analyse(String channel, List<Long> times, List<String> wordIds,
                                       List<Integer> origs, double tolerance, long acquisitionGapUs) {
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            double gap = times.get(i) - times.get(i - 1);
            List<Integer> candidates = new ArrayList<>();
            if (fits(gap, LOW_GAP_US, tolerance)) {
                candidates.add(LOW_BPS);
            }
            if (fits(gap, HIGH_GAP_US, tolerance)) {
                candidates.add(HIGH_BPS);
            }
            boolean acquisition = gap >= acquisitionGapUs;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", "gap:" + channel + ":" + wordIds.get(i - 1) + "->" + wordIds.get(i));
            g.put("fromWord", wordIds.get(i - 1));
            g.put("toWord", wordIds.get(i));
            g.put("fromOrig", origs.get(i - 1));
            g.put("toOrig", origs.get(i));
            g.put("fromUs", String.valueOf(times.get(i - 1)));
            g.put("toUs", String.valueOf(times.get(i)));
            g.put("gapUs", round(gap));
            g.put("acquisitionGap", acquisition);
            g.put("candidateRatesBps", candidates);
            g.put("ambiguous", candidates.size() > 1);
            g.put("unmatched", candidates.isEmpty() && !acquisition);
            gaps.add(g);
        }

        List<Map<String, Object>> proposals = new ArrayList<>();
        proposals.add(proposal("strict-" + LOW_BPS + "-or-" + HIGH_BPS, gaps,
                new double[]{tolerance}, acquisitionGapUs, wordIds, times, origs));
        proposals.add(proposal("wide-tolerance-merging", gaps,
                new double[]{Math.max(tolerance, 0.30)}, acquisitionGapUs, wordIds, times, origs));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel);
        result.put("tolerance", tolerance);
        result.put("acquisitionGapUs", acquisitionGapUs);
        result.put("gaps", gaps);
        result.put("proposals", proposals);
        return result;
    }

    private Map<String, Object> proposal(String name, List<Map<String, Object>> baseGaps,
                                         double[] tolerances, long acquisitionGapUs,
                                         List<String> wordIds, List<Long> times, List<Integer> origs) {
        double tol = tolerances[0];
        List<Map<String, Object>> classified = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            double gap = times.get(i) - times.get(i - 1);
            List<Integer> cands = new ArrayList<>();
            if (fits(gap, LOW_GAP_US, tol)) {
                cands.add(LOW_BPS);
            }
            if (fits(gap, HIGH_GAP_US, tol)) {
                cands.add(HIGH_BPS);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("toWord", wordIds.get(i));
            g.put("gapUs", round(gap));
            g.put("acquisitionGap", gap >= acquisitionGapUs);
            g.put("candidateRatesBps", cands);
            classified.add(g);
        }

        List<Map<String, Object>> segments = new ArrayList<>();
        int start = 0;
        Integer currentRate = null;
        for (int i = 0; i < classified.size(); i++) {
            Map<String, Object> g = classified.get(i);
            boolean acq = Boolean.TRUE.equals(g.get("acquisitionGap"));
            List<?> cands = (List<?>) g.get("candidateRatesBps");
            Integer chosen = cands.size() == 1 ? ((Number) cands.get(0)).intValue()
                    : cands.isEmpty() ? null : ((Number) cands.get(0)).intValue();
            if (acq || (currentRate != null && chosen != null && !chosen.equals(currentRate))) {
                segments.add(segment(start, i, currentRate, wordIds, times, origs, acq && chosen == null));
                start = i + 1;
                currentRate = acq ? null : chosen;
            } else if (currentRate == null && chosen != null) {
                currentRate = chosen;
            }
        }
        segments.add(segment(start, classified.size(), currentRate, wordIds, times, origs, false));

        boolean legal = true;
        List<String> rates = new ArrayList<>();
        for (Map<String, Object> s : segments) {
            Object r = s.get("rateBps");
            if (r == null) {
                legal = false;
            } else {
                rates.add(String.valueOf(r));
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "proposal:" + name);
        p.put("tolerance", tol);
        p.put("segments", segments);
        p.put("legal", legal);
        p.put("ratesBps", rates);
        p.put("switchesRate", rates.size() > 1 && !rates.isEmpty()
                && !rates.get(0).equals(rates.get(rates.size() - 1)));
        return p;
    }

    private Map<String, Object> segment(int startGapIndex, int endGapIndex, Integer rate,
                                        List<String> wordIds, List<Long> times, List<Integer> origs,
                                        boolean acquisitionStart) {
        int startWord = startGapIndex;
        int endWord = endGapIndex;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", "segment:" + wordIds.get(startWord) + ":" + wordIds.get(endWord));
        s.put("startWord", wordIds.get(startWord));
        s.put("endWord", wordIds.get(endWord));
        s.put("startOrig", origs.get(startWord));
        s.put("endOrig", origs.get(endWord));
        s.put("startUs", String.valueOf(times.get(startWord)));
        s.put("endUs", String.valueOf(times.get(endWord)));
        s.put("rateBps", rate);
        s.put("nominalGapUs", rate == null ? null : (rate == LOW_BPS ? LOW_GAP_US : HIGH_GAP_US));
        s.put("boundary", acquisitionStart || startGapIndex > 0);
        return s;
    }

    private boolean fits(double gap, double nominal, double tol) {
        return gap >= nominal * (1 - tol) && gap <= nominal * (1 + tol);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static List<Integer> standardRates() {
        return Arrays.asList(LOW_BPS, HIGH_BPS);
    }
}
