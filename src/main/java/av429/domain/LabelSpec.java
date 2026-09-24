package av429.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One label definition from a dictionary version.
 *
 * JSON shape:
 * {
 *   "labelOctal":"251", "name":"ALTITUDE", "dataType":"BNR",
 *   "msbBit":11, "lsbBit":29, "signed":"twos", "resolution":0.3048,
 *   "unit":"m", "coordinateDatum":"MSL",
 *   "bcdDigits":[{"startBit":27,"endBit":11}],
 *   "discrete":[{"bit":11,"name":"GP_OK","trueState":"VALID"}]
 * }
 */
public final class LabelSpec {

    public enum SignMode { TWOS_COMPLEMENT, SIGN_MAGNITUDE }

    public final int labelValue;
    public final String labelOctal;
    public final String name;
    public final String dataType; // BNR | BCD | DISCRETE
    public final int msbBit;
    public final int lsbBit;
    public final SignMode signMode;
    public final double resolution;
    public final String unit;
    public final String coordinateDatum;
    public final int sdi; // -1 means any
    public final List<int[]> bcdDigits; // each {startBit(msd side),endBit}
    public final List<DiscreteBit> discreteBits;

    public static final class DiscreteBit {
        public final int bit;
        public final String name;
        public final String trueState;
        public final String falseState;

        public DiscreteBit(int bit, String name, String trueState, String falseState) {
            this.bit = bit;
            this.name = name;
            this.trueState = trueState;
            this.falseState = falseState;
        }
    }

    public LabelSpec(int labelValue, String name, String dataType, int msbBit, int lsbBit,
                     SignMode signMode, double resolution, String unit, String coordinateDatum,
                     int sdi, List<int[]> bcdDigits, List<DiscreteBit> discreteBits) {
        this.labelValue = labelValue;
        this.labelOctal = String.format("%03o", labelValue);
        this.name = name;
        this.dataType = dataType;
        this.msbBit = msbBit;
        this.lsbBit = lsbBit;
        this.signMode = signMode;
        this.resolution = resolution;
        this.unit = unit;
        this.coordinateDatum = coordinateDatum;
        this.sdi = sdi;
        this.bcdDigits = bcdDigits;
        this.discreteBits = discreteBits;
    }

    public int bnrWidth() {
        return lsbBit - msbBit + 1;
    }

    public boolean sdiMatches(int wordSdi) {
        return sdi < 0 || sdi == wordSdi;
    }

    public static LabelSpec fromMap(Map<String, Object> m) {
        int label = m.containsKey("labelValue")
                ? ((Number) m.get("labelValue")).intValue()
                : Integer.parseUnsignedInt(((String) m.get("labelOctal")).trim(), 8);
        String dataType = String.valueOf(m.getOrDefault("dataType", "BNR")).toUpperCase();
        int msb = m.containsKey("msbBit") ? ((Number) m.get("msbBit")).intValue() : 11;
        int lsb = m.containsKey("lsbBit") ? ((Number) m.get("lsbBit")).intValue() : 29;
        String signText = String.valueOf(m.getOrDefault("signed", "twos")).toLowerCase();
        SignMode signMode = signText.startsWith("sign") || signText.contains("magnitude")
                ? SignMode.SIGN_MAGNITUDE : SignMode.TWOS_COMPLEMENT;
        double resolution = m.containsKey("resolution")
                ? ((Number) m.get("resolution")).doubleValue() : 1.0;
        String unit = m.containsKey("unit") ? String.valueOf(m.get("unit")) : "";
        String datum = m.containsKey("coordinateDatum")
                ? String.valueOf(m.get("coordinateDatum")) : null;
        int sdi = m.containsKey("sdi") ? ((Number) m.get("sdi")).intValue() : -1;

        List<int[]> bcd = new ArrayList<>();
        Object digits = m.get("bcdDigits");
        if (digits instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> dm = (Map<?, ?>) o;
                int start = ((Number) dm.get("startBit")).intValue();
                int end = ((Number) dm.get("endBit")).intValue();
                bcd.add(new int[]{start, end});
            }
        }
        List<DiscreteBit> discrete = new ArrayList<>();
        Object disc = m.get("discrete");
        if (disc instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> dm = (Map<?, ?>) o;
                int bit = ((Number) dm.get("bit")).intValue();
                String nm = String.valueOf(dm.get("name"));
                String ts = dm.containsKey("trueState") ? String.valueOf(dm.get("trueState")) : "1";
                String fs = dm.containsKey("falseState") ? String.valueOf(dm.get("falseState")) : "0";
                discrete.add(new DiscreteBit(bit, nm, ts, fs));
            }
        }
        return new LabelSpec(label, String.valueOf(m.getOrDefault("name", "L" + label)),
                dataType, msb, lsb, signMode, resolution, unit, datum, sdi, bcd, discrete);
    }
}
