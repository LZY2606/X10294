package a429.event;

import java.util.List;

/** Returned to the late saver: both sides' decisions plus the conflicting target. */
public record ConflictView(
        String targetId,
        long baseSeq,
        long headSeq,
        Ruling attempted,
        List<Ruling> committed
) {
}
