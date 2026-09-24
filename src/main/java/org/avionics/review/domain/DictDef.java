package org.avionics.review.domain;

import java.util.Map;

/**
 * One label definition inside a dictionary version.
 *
 * <p>The dictionary is an explicit reference artifact: the same raw word is
 * interpreted differently across dictionary versions, and the version used is
 * always carried with the produced engineering value.</p>
 */
public record DictDef(
        String labelOctal,
        String name,
        String encoding,
        String unit,
        double range,
        int msb,
        int lsb,
        int impliedDecimals,
        Map<Integer, String> discreteBits) {

    public boolean isBnr() {
        return "BNR".equalsIgnoreCase(encoding);
    }

    public boolean isBcd() {
        return "BCD".equalsIgnoreCase(encoding);
    }

    public boolean isDiscrete() {
        return "DISCRETE".equalsIgnoreCase(encoding);
    }

    public int fieldBits() {
        return msb - lsb + 1;
    }
}
