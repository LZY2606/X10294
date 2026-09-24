package a429.review.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A versioned label dictionary. BNR/BCD/discrete interpretation is *only*
 * defined by the dictionary version used for a branch; the raw word never
 * carries an encoding of its own.
 */
public final class Dictionary {

    public enum DataType { BNR, BCD, DISCRETE }

    /** One dictionary entry; a label+SDI pair may legally resolve to several entries. */
    public static final class Entry {
        public final String octalLabel;      // "301"
        public final int sdi;                // -1 means "any SDI"
        public final DataType dataType;
        public final String name;
        public final String unit;            // engineering unit / coordinate datum
        public final double resolutionLsb;   // BNR: engineering units per LSB
        public final int signBit;            // BNR sign bit position in ARINC numbering (normally 29)
        public final int msb;                // BNR MSB position (normally 28)
        public final int lsb;                // BNR/BCD LSB position (normally 11)
        public final List<String> discreteNames; // DISCRETE: name per data bit, index 0 = bit 11

        public Entry(String octalLabel, int sdi, DataType dataType, String name, String unit,
                     double resolutionLsb, int signBit, int msb, int lsb, List<String> discreteNames) {
            this.octalLabel = octalLabel;
            this.sdi = sdi;
            this.dataType = dataType;
            this.name = name;
            this.unit = unit;
            this.resolutionLsb = resolutionLsb;
            this.signBit = signBit;
            this.msb = msb;
            this.lsb = lsb;
            this.discreteNames = discreteNames == null ? List.of() : List.copyOf(discreteNames);
        }

        public boolean matches(String oct, int wordSdi) {
            if (!octalLabel.equals(oct)) {
                return false;
            }
            return sdi < 0 || sdi == wordSdi;
        }
    }

    private final String version;
    private final List<Entry> entries;

    public Dictionary(String version, List<Entry> entries) {
        this.version = version;
        this.entries = List.copyOf(entries);
    }

    public String version() {
        return version;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** All entries matching label+SDI; multiple legal candidates are preserved. */
    public List<Entry> match(String octalLabel, int sdi) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.matches(octalLabel, sdi)) {
                out.add(e);
            }
        }
        return out;
    }
}
