package avi.a429.core;

import java.util.Map;

/**
 * One label dictionary entry. The dictionary version decides how BNR/BCD/
 * discrete payloads are interpreted; the raw word bits are never altered.
 *
 * @param id        stable identity of this entry row
 * @param dictId    stable identity of the dictionary version it belongs to
 * @param labelOctal 3-digit octal label, e.g. "204"
 * @param encoding  BNR | BCD | DISC
 * @param unit      engineering unit of the decoded value, e.g. "deg"
 * @param basis     coordinate / reference basis, e.g. "IRU1 body axis pitch"
 * @param scale     LSB weight for BNR, or decimal point scale for BCD
 * @param offset    engineering offset added after scaling
 * @param ssmMap    meaning of SSM codes 0..3 for this entry ("normal","ncd","test","invalid")
 */
public record DictEntry(
        String id,
        String dictId,
        String labelOctal,
        String encoding,
        String unit,
        String basis,
        double scale,
        double offset,
        Map<String, String> ssmMap) {

    public String ssmMeaning(int ssm) {
        return ssmMap.getOrDefault(String.valueOf(ssm), "invalid");
    }
}
