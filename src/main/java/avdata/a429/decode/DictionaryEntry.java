package avdata.a429.decode;

import java.util.List;

/**
 * One label definition from a specific dictionary version.
 * Interpretation (BNR / BCD / discrete) is fully determined by the dictionary
 * version that was hit; the raw word never carries an encoding of its own.
 */
public final class DictionaryEntry {
    public final String labelOctal;
    public final String sdi;                 // "00","01","10","11" or "*"
    public final String encoding;            // BNR | BCD | DISCRETE
    public final String name;
    public final String unit;
    public final String coordinateBasis;     // e.g. "WGS84" or ""
    public final double resolution;          // BNR LSB engineering value
    public final double offset;              // BNR zero offset
    public final int dataLsb;                // BNR data field range (transmission order)
    public final int dataMsb;
    public final int signBit;                // 0 if unsigned
    public final List<DiscreteBit> discreteBits;

    public DictionaryEntry(String labelOctal, String sdi, String encoding, String name,
                           String unit, String coordinateBasis,
                           double resolution, double offset,
                           int dataLsb, int dataMsb, int signBit,
                           List<DiscreteBit> discreteBits) {
        this.labelOctal = labelOctal;
        this.sdi = sdi;
        this.encoding = encoding;
        this.name = name;
        this.unit = unit;
        this.coordinateBasis = coordinateBasis == null ? "" : coordinateBasis;
        this.resolution = resolution;
        this.offset = offset;
        this.dataLsb = dataLsb;
        this.dataMsb = dataMsb;
        this.signBit = signBit;
        this.discreteBits = discreteBits == null ? List.of() : List.copyOf(discreteBits);
    }

    public boolean matches(String label, String sdiCode) {
        return this.labelOctal.equals(label)
                && ("*".equals(this.sdi) || this.sdi.equals(sdiCode));
    }

    public static final class DiscreteBit {
        public final int bit;
        public final String meaningWhenOne;
        public DiscreteBit(int bit, String meaningWhenOne) {
            this.bit = bit;
            this.meaningWhenOne = meaningWhenOne;
        }
    }
}
