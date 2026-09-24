package a429.rate;

import java.util.List;

/**
 * A legal segmentation of a channel stream: boundaries align with rate switches or acquisition
 * gaps. Multiple candidates are retained whenever the gaps do not uniquely decide a segmentation.
 *
 * @param uid stable, deterministic identity of the candidate (function of channel, params, boundaries)
 */
public record RateCandidate(String uid, int index, String channel,
                            List<RateSegment> segments,
                            List<Integer> gapAfterSeq) {
}
