package a429.decode;

import java.util.List;

/**
 * Dictionary-version-dependent interpretation of one word.
 * Values that cannot be converted stay at the source position as diagnostics;
 * they are never dropped for plotting convenience.
 */
public record EngineeringValue(
        String wordId,
        String labelOctal,
        String dictionaryVersion,
        Encoding encoding,
        String ssmState,
        boolean usable,
        Double numericValue,
        String displayText,
        String unit,
        String basis,
        List<String> diagnostics
) { }
