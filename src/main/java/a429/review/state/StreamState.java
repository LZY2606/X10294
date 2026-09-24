package a429.review.state;

import a429.review.decode.RateSegmenter;

import java.util.List;

/** Rate segmentation read-model for one (channel,label) stream within a branch. */
public record StreamState(String streamUid,
                          String channel,
                          String octalLabel,
                          List<RateSegmenter.Interval> intervals,
                          List<RateSegmenter.Segment> segments,
                          List<Aggregate> aggregates,
                          List<String> excludedWordUids) {

    public record Aggregate(String segmentUid,
                            int count,
                            Double minValue,
                            Double maxValue,
                            Double meanValue,
                            String unit,
                            List<String> excludedReasons) {
    }
}
