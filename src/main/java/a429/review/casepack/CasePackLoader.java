package a429.review.casepack;

import a429.review.domain.Dictionary;
import a429.review.state.JsonCodecs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Parses case pack JSON and builds versioned dictionaries. */
public final class CasePackLoader {

    private CasePackLoader() {
    }

    public static CasePack.Pack parse(String json) {
        try {
            return JsonCodecs.MAPPER.readValue(json, CasePack.Pack.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid case pack: " + e.getMessage(), e);
        }
    }

    public static Dictionary toDictionary(CasePack.DictBlob blob) {
        List<Dictionary.Entry> entries = new ArrayList<>();
        for (CasePack.Entry e : blob.entries) {
            Dictionary.DataType dt = Dictionary.DataType.valueOf(e.encoding.toUpperCase());
            double res = e.resolutionLsb == null ? 0.0 : e.resolutionLsb;
            int signBit = e.signBit == null ? 29 : e.signBit;
            int msb = e.msb == null ? 28 : e.msb;
            int lsb = e.lsb == null ? 11 : e.lsb;
            entries.add(new Dictionary.Entry(e.label, e.sdi, dt, e.name, e.unit,
                    res, signBit, msb, lsb, e.bits));
        }
        return new Dictionary(blob.version, entries);
    }

    /** Parses ISO-8601 with up to nanosecond fraction; raw string stays stored too. */
    public static long parseTimeNs(String raw) {
        String s = raw.trim();
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1) + "Z";
        }
        try {
            Instant i = Instant.parse(s);
            return i.getEpochSecond() * 1_000_000_000L + i.getNano();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid timestamp (expected ISO-8601 UTC): " + raw, e);
        }
    }
}
