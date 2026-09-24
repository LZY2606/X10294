package a429.decode;

/** Gap between two consecutive received words on one channel. */
public record AdjacentGap(
        String gapId,
        String channel,
        int fromSeq,
        int toSeq,
        String fromWordId,
        String toWordId,
        long deltaMicros,
        double expectedPeriodMicros,
        double ratio,
        boolean acquisitionGap
) { }
