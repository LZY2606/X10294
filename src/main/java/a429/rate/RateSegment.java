package a429.rate;

/** One constant-rate segment of a candidate segmentation. */
public record RateSegment(int rateBps, int startSeq, int endSeq) {
}
