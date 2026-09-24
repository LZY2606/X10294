package org.avionics.review.domain;

import java.util.List;

/**
 * One legal interpretation of a raw word under one bit orientation.
 *
 * @param raw        raw 32-bit word actually decoded by this candidate (reversed when REVERSED)
 * @param parityOk   odd-parity result on {@code raw}
 * @param dictVersion dictionary version used for the engineering interpretation
 * @param dictHit    whether the dictionary contained the label for this candidate
 */
public record WordCandidate(
        String candidateId,
        Orientation orientation,
        int raw,
        boolean parityOk,
        String label,
        int labelValue,
        int sdi,
        int ssm,
        String ssmState,
        boolean ssmValid,
        String dataHex,
        List<DecodedField> fields,
        EngineeringValue engineering,
        String dictVersion,
        boolean dictHit) {

    public boolean usableForAggregate() {
        return parityOk && ssmValid && engineering != null && engineering.usableForAggregate();
    }
}
