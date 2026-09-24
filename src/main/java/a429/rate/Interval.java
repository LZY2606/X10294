package a429.rate;

/** Time gap between two adjacent in-order words on one channel (nanoseconds). */
public record Interval(int leftSeq, int rightSeq, long gapNanos, boolean leftParityBad,
                       boolean rightParityBad) {
}
