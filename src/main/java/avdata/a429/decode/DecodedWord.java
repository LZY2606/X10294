package avdata.a429.decode;

import java.util.List;

/** Structural decode of one 32-bit word; no engineering value lives here. */
public final class DecodedWord {
    public final int word;
    public final int labelBits;
    public final String labelOctalTransmission;
    public final String labelOctalDocument;
    public final String sdi;
    public final String ssm;
    public final int dataField;
    public final boolean parityValid;
    public final List<Interpretation> interpretations;

    public DecodedWord(int word, int labelBits, String labelOctalTransmission,
                       String labelOctalDocument, String sdi, String ssm, int dataField,
                       boolean parityValid, List<Interpretation> interpretations) {
        this.word = word;
        this.labelBits = labelBits;
        this.labelOctalTransmission = labelOctalTransmission;
        this.labelOctalDocument = labelOctalDocument;
        this.sdi = sdi;
        this.ssm = ssm;
        this.dataField = dataField;
        this.parityValid = parityValid;
        this.interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
    }
}
