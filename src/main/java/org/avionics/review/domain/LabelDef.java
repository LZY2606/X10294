package org.avionics.review.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One dictionary entry: how a label (optionally narrowed by SDI) is decoded.
 *
 * @param nibbles BCD digit nibbles as wire bit-ranges, MSB first; null for non-BCD labels
 * @param bits    discrete signal map: signal name -> wire bit number; null for non-discrete
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LabelDef(
        String label,
        Integer sdi,
        String name,
        Encoding encoding,
        String unit,
        Double resolution,
        Integer signBit,
        Integer msb,
        Integer lsb,
        List<int[]> nibbles,
        Map<String, Integer> bits) {

    public boolean matchesSdi(int wordSdi) {
        return sdi == null || sdi == wordSdi;
    }
}
