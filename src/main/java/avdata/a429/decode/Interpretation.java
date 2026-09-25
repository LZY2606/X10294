package avdata.a429.decode;

import java.util.List;

/** One engineering-value interpretation of a word under one dictionary entry. */
public final class Interpretation {
    public final String encoding;          // BNR | BCD | DISCRETE
    public final String parameterName;
    public final String unit;
    public final String coordinateBasis;
    public final Double value;              // null when not convertible
    public final String rawText;            // original representation preserved
    public final List<Diagnostic> diagnostics;
    public final List<String> discreteStates;
    public final String dictionaryVersion;
    public final String labelOctal;
    public final String labelConvention;    // TRANSMISSION | DOCUMENT_REVERSED
    public final String sdi;
    public final String ssm;
    public final String ssmMeaning;

    public Interpretation(String encoding, String parameterName, String unit, String coordinateBasis,
                          Double value, String rawText, List<Diagnostic> diagnostics,
                          List<String> discreteStates, String dictionaryVersion,
                          String labelOctal, String labelConvention, String sdi,
                          String ssm, String ssmMeaning) {
        this.encoding = encoding;
        this.parameterName = parameterName;
        this.rawText = rawText;
        this.value = value;
        this.unit = unit == null ? "" : unit;
        this.coordinateBasis = coordinateBasis == null ? "" : coordinateBasis;
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        this.discreteStates = discreteStates == null ? List.of() : List.copyOf(discreteStates);
        this.dictionaryVersion = dictionaryVersion;
        this.labelOctal = labelOctal;
        this.labelConvention = labelConvention;
        this.sdi = sdi;
        this.ssm = ssm;
        this.ssmMeaning = ssmMeaning;
    }

    public boolean isConvertible() { return value != null; }

    public record Diagnostic(String code, String message) {}
}
