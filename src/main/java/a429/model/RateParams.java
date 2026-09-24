package a429.model;

/** Versioned rate-segmentation parameters. */
public record RateParams(
        String paramsId,
        String version,
        double toleranceRatio,
        double gapFactor,
        int maxCandidates) {

    public static final int RATE_LOW = 12_500;
    public static final int RATE_HIGH = 100_000;
}
