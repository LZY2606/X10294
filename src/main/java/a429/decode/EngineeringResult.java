package a429.decode;

import a429.domain.Quantity;

import java.util.List;
import java.util.Map;

/**
 * Engineering interpretation of one word under one dictionary version.
 */
public record EngineeringResult(
        String dictVersion,
        String labelOctal,
        String entryName,
        String encoding,
        Quantity quantity,
        String ssmMeaning,
        boolean ssmInvalid,
        boolean parityValid,
        boolean entersAggregate,
        List<String> discreteStates,
        Map<String, Object> raw) {
}
