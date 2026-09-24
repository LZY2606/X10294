package com.avionics.a429.domain;

import java.util.List;
import java.util.Map;

/**
 * One label definition from a label dictionary version.
 *
 * Labels are keyed by their standard octal rendering (see
 * {@link Bits#octalStandard(int)}) plus SDI. The dictionary version is part of
 * every key: changing the dictionary version changes interpretation without
 * touching raw bits.
 */
public final class DictEntry {
    public final String id;
    public final String version;
    public final String labelOctal;
    public final String sdiCode;
    public final String encoding;
    public final String name;
    public final String unit;
    public final String coordinateBasis;
    public final Double fullScale;
    public final Double resolution;
    public final int bcdDecimalPlaces;
    public final boolean bcdSigned;
    public final Map<Integer, String> discreteStates;

    public DictEntry(String id, String version, String labelOctal, String sdiCode,
                     String encoding, String name, String unit, String coordinateBasis,
                     Double fullScale, Double resolution, int bcdDecimalPlaces,
                     boolean bcdSigned, Map<Integer, String> discreteStates) {
        this.id = id;
        this.version = version;
        this.labelOctal = labelOctal;
        this.sdiCode = sdiCode == null ? "00" : sdiCode;
        this.encoding = encoding;
        this.name = name;
        this.unit = unit;
        this.coordinateBasis = coordinateBasis;
        this.fullScale = fullScale;
        this.resolution = resolution;
        this.bcdDecimalPlaces = bcdDecimalPlaces;
        this.bcdSigned = bcdSigned;
        this.discreteStates = discreteStates;
    }

    public static String key(String version, String labelOctal, String sdiCode) {
        return version + "|" + labelOctal + "|" + (sdiCode == null ? "00" : sdiCode);
    }

    public List<String> sdiAlternatives() {
        return List.of(sdiCode);
    }
}
