package org.avionics.review.web;

import org.avionics.review.domain.Interval;
import org.avionics.review.domain.RateCandidate;
import org.avionics.review.domain.RateSegment;

/** Server-side SVG: arrival timeline with gaps and adaptive segments. */
public final class SvgRenderer {

    private SvgRenderer() {
    }

    public static String timeline(String channel,
                                  java.util.List<org.avionics.review.domain.RawWord> words,
                                  java.util.List<Interval> intervals,
                                  java.util.List<RateCandidate> candidates) {
        double minT = words.stream().mapToDouble(w -> w.receiveTimeMs()).min().orElse(0);
        double maxT = words.stream().mapToDouble(w -> w.receiveTimeMs()).max().orElse(1);
        double span = Math.max(maxT - minT, 0.001);
        int width = 900;
        int left = 60;
        int right = 30;
        int top = 50;
        double scale = (width - left - right) / span;

        RateCandidate adaptive = candidates.stream()
                .filter(c -> c.method().equals("RATE_ADAPTIVE") && c.channel().equals(channel))
                .findFirst().orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='").append(width)
                .append("' height='220' viewBox='0 0 ").append(width).append(" 220' "
                        + "font-family='monospace' font-size='11'>");
        sb.append("<rect width='100%' height='100%' fill='#0f1218'/>");
        sb.append("<text x='").append(left).append("' y='24' fill='#9fd7ff'>")
                .append("Channel ").append(channel).append(" arrival timeline (ms)</text>");
        sb.append("<line x1='").append(left).append("' y1='120' x2='")
                .append(width - right).append("' y2='120' stroke='#33404f'/>");

        if (adaptive != null) {
            String[] colors = {"#3d7", "#fc6", "#6cf", "#f96"};
            int i = 0;
            for (RateSegment seg : adaptive.segments()) {
                double x1 = left + (seg.startMs() - minT) * scale;
                double x2 = left + (seg.endMs() - minT) * scale;
                sb.append("<rect x='").append(Math.round(x1)).append("' y='140' width='")
                        .append(Math.max(2, Math.round(x2 - x1))).append("' height='14' fill='")
                        .append(colors[i % colors.length]).append("' opacity='0.55'/>");
                sb.append("<text x='").append(Math.round(x1 + 2)).append("' y='172' fill='#cfd8e3'>")
                        .append(Math.round(seg.rateKhz() * 1000)).append(" Hz</text>");
                i++;
            }
        }

        for (var w : words) {
            double x = left + (w.receiveTimeMs() - minT) * scale;
            sb.append("<circle data-sid='").append(w.sid())
                    .append("' cx='").append(Math.round(x)).append("' cy='120' r='4' fill='#9fd7ff'/>");
        }
        for (Interval iv : intervals) {
            if (iv.acquisitionGap()) {
                var from = words.stream().filter(w -> w.sid().equals(iv.fromWordSid())).findFirst();
                if (from.isPresent()) {
                    double x = left + (from.get().receiveTimeMs() - minT) * scale;
                    sb.append("<text x='").append(Math.round(x)).append("' y='105' fill='#ff7b72'>gap ")
                            .append(Math.round(iv.deltaMs())).append("ms</text>");
                    sb.append("<line x1='").append(Math.round(x)).append("' y1='114' x2='")
                            .append(Math.round(x)).append("' y2='126' stroke='#ff7b72' stroke-width='2'/>");
                }
            }
        }
        sb.append("<text x='").append(left).append("' y='200' fill='#8b98a9'>segments: RATE_ADAPTIVE; ")
                .append("gaps marked red; circles keep word SIDs</text>");
        sb.append("</svg>");
        return sb.toString();
    }
}
