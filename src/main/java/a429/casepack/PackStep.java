package a429.casepack;

import java.util.List;

/** Narrative step with the boundary combination the replay must pass through. */
public record PackStep(
        int order,
        String action,
        String title,
        String detail,
        List<String> touches,
        List<String> assertions) {
}
