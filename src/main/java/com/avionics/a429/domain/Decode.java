package com.avionics.a429.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Interpretation result for one word under one dictionary version. */
public final class Decode {
    public final String wordId;
    public final String dictVersion;
    public final String labelOctal;
    public final String labelOctalRaw;
    public final String sdi;
    public final String ssmCode;
    public final String ssmState;
    public final int dataBits;
    public final boolean parityOk;
    public final boolean bitOrderReversed;
    public final boolean dictionaryHit;
    public final String entryId;
    public final String encoding;
    public final String name;
    public final String unit;
    public final String coordinateBasis;
    public final Double engineeringValue;
    public final String displayValue;
    public final List<String> diagnostics = new ArrayList<>();

    Decode(String wordId, String dictVersion, String labelOctal, String labelOctalRaw,
           String sdi, String ssmCode, String ssmState, int dataBits, boolean parityOk,
           boolean bitOrderReversed, boolean dictionaryHit, String entryId,
           String encoding, String name, String unit, String coordinateBasis,
           Double engineeringValue, String displayValue) {
        this.wordId = wordId;
        this.dictVersion = dictVersion;
        this.labelOctal = labelOctal;
        this.labelOctalRaw = labelOctalRaw;
        this.sdi = sdi;
        this.ssmCode = ssmCode;
        this.ssmState = ssmState;
        this.dataBits = dataBits;
        this.parityOk = parityOk;
        this.bitOrderReversed = bitOrderReversed;
        this.dictionaryHit = dictionaryHit;
        this.entryId = entryId;
        this.encoding = encoding;
        this.name = name;
        this.unit = unit;
        this.coordinateBasis = coordinateBasis;
        this.engineeringValue = engineeringValue;
        this.displayValue = displayValue;
    }
}
