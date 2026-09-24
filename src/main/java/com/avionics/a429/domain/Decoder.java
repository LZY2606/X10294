package com.avionics.a429.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Derives fields and engineering values from raw words. Pure computation:
 * nothing here mutates stored data, and every output carries the dictionary
 * version that produced it.
 */
public final class Decoder {

    private static final Map<String, String> SSM_BNR = Map.of(
            "11", "NOMINAL",
            "00", "FAILURE_WARNING",
            "01", "NO_COMPUTED_DATA",
            "10", "FUNCTIONAL_TEST");

    private static final Map<String, String> SSM_BCD = Map.of(
            "00", "NOMINAL",
            "11", "FAILURE_WARNING",
            "01", "NO_COMPUTED_DATA",
            "10", "FUNCTIONAL_TEST");

    private final Map<String, DictEntry> entries = new HashMap<>();

    public void add(DictEntry entry) {
        entries.put(DictEntry.key(entry.version, entry.labelOctal, entry.sdiCode), entry);
    }

    public Decode decode(Word word, String dictVersion, boolean bitOrderReversed) {
        int raw = bitOrderReversed ? Bits.reverseAll32(word.raw) : word.raw;
        boolean parityOk = Bits.oddParityOk(raw);
        String labelStd = Bits.octalStandard(raw);
        String labelRaw = Bits.octalRaw(raw);
        String sdi = String.format("%d%d", Bits.bit(raw, 10), Bits.bit(raw, 9));
        String ssm = String.format("%d%d", Bits.bit(raw, 31), Bits.bit(raw, 30));
        int data = Bits.field(raw, 29, 11);

        DictEntry entry = entries.get(DictEntry.key(dictVersion, labelStd, sdi));
        if (entry == null) {
            entry = entries.get(DictEntry.key(dictVersion, labelStd, "XX"));
        }
        boolean bnr = entry != null && "BNR".equalsIgnoreCase(entry.encoding);
        String ssmState = (bnr ? SSM_BNR : SSM_BCD).getOrDefault(ssm, "INVALID");

        Decode d = new Decode(word.id, dictVersion, labelStd, labelRaw, sdi, ssm,
                ssmState, data, parityOk, bitOrderReversed, entry != null,
                entry == null ? null : entry.id,
                entry == null ? null : entry.encoding,
                entry == null ? null : entry.name,
                entry == null ? null : entry.unit,
                entry == null ? null : entry.coordinateBasis,
                null, null);

        if (entry == null) {
            d.diagnostics.add("NO_DICTIONARY_ENTRY:" + dictVersion + ":" + labelStd
                    + ":SDI" + sdi);
            return d;
        }
        if (entry.encoding.equalsIgnoreCase("BNR")) {
            decodeBnr(raw, entry, d);
        } else if (entry.encoding.equalsIgnoreCase("BCD")) {
            decodeBcd(raw, entry, d);
        } else {
            decodeDiscrete(raw, entry, d);
        }
        if (!"NOMINAL".equals(ssmState)) {
            d.diagnostics.add("SSM_" + ssmState);
        }
        if (!parityOk) {
            d.diagnostics.add("PARITY_FAIL");
        }
        if (bitOrderReversed) {
            d.diagnostics.add("BIT_ORDER_REVERSED");
        }
        return d;
    }

    private void decodeBnr(int raw, DictEntry entry, Decode d) {
        int sign = Bits.bit(raw, 29);
        long magnitude = Bits.field(raw, 28, 11);
        long signed;
        if (sign == 1) {
            signed = magnitude - (1L << 18);
        } else {
            signed = magnitude;
        }
        double full = entry.fullScale != null ? entry.fullScale : 1.0;
        double value = signed * (full / (1L << 18));
        double rounded = Math.rint(value * 1e9) / 1e9;
        d.engineeringValue = rounded;
        d.displayValue = rounded + (entry.unit == null ? "" : " " + entry.unit)
                + (entry.coordinateBasis == null ? "" : " [" + entry.coordinateBasis + "]");
    }

    private void decodeBcd(int raw, DictEntry entry, Decode d) {
        boolean illegal = false;
        long accumulator = 0;
        for (int arincBit = 29; arincBit >= 11; arincBit -= 4) {
            int nibble = Bits.field(raw, arincBit, Math.max(arincBit - 3, 11));
            if (nibble > 9) {
                illegal = true;
                d.diagnostics.add("BCD_ILLEGAL_NIBBLE:bits"
                        + Math.max(arincBit - 3, 11) + "-" + arincBit
                        + "=0x" + Integer.toHexString(nibble).toUpperCase());
            }
            accumulator = accumulator * 10 + Math.min(nibble, 9);
        }
        if (entry.bcdSigned && Bits.bit(raw, 29) == 1) {
            d.diagnostics.add("BCD_SIGN_BIT_SET");
        }
        double value = accumulator;
        for (int i = 0; i < entry.bcdDecimalPlaces; i++) {
            value /= 10.0;
        }
        d.engineeringValue = value;
        if (illegal) {
            d.displayValue = "UNCONVERTIBLE" + (entry.unit == null ? "" : " " + entry.unit)
                    + " (raw BCD digits retained)";
        } else {
            d.displayValue = value + (entry.unit == null ? "" : " " + entry.unit);
        }
    }

    private void decodeDiscrete(int raw, DictEntry entry, Decode d) {
        Map<Integer, String> states = entry.discreteStates;
        StringBuilder sb = new StringBuilder();
        if (states != null) {
            for (Map.Entry<Integer, String> e : states.entrySet()) {
                if (Bits.bit(raw, e.getKey()) == 1) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(e.getValue());
                }
            }
        }
        d.engineeringValue = (double) data;
        d.displayValue = sb.length() == 0
                ? "0b" + String.format("%19s", Integer.toBinaryString(d.data)).replace(' ', '0')
                : sb.toString();
    }
}
