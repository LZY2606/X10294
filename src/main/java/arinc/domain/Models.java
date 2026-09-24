package arinc.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RawWord(String id, int sequence, String receivedAt, long receivedNanos, String channelId,
                      long word, String sourceBitOrder, String dictionaryVersion) {
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "sequence", sequence, "receivedAt", receivedAt, "receivedNanos", receivedNanos,
                "channelId", channelId, "wordHex", String.format("%08X", word), "wordDecimal", word & 0xffffffffL,
                "sourceBitOrder", sourceBitOrder, "dictionaryVersion", dictionaryVersion);
    }

    public static RawWord fromJson(Map<String, Object> json) {
        String hex = Json.str(json, "wordHex");
        long raw = hex != null ? Long.parseLong(hex, 16) : Json.lng(json, "wordDecimal");
        return new RawWord(Json.str(json, "id"), Json.integer(json, "sequence"), Json.str(json, "receivedAt"),
                Json.lng(json, "receivedNanos"), Json.str(json, "channelId"), raw,
                Json.str(json, "sourceBitOrder"), Json.str(json, "dictionaryVersion"));
    }
}

public record ChannelConfig(String id, String name, List<Long> candidateRatesBps, double gapThresholdMs,
                            String timeBasis, String expectedBitOrder) {
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "candidateRatesBps", candidateRatesBps,
                "gapThresholdMs", gapThresholdMs, "timeBasis", timeBasis, "expectedBitOrder", expectedBitOrder);
    }

    public static ChannelConfig fromJson(Map<String, Object> json) {
        return new ChannelConfig(Json.str(json, "id"), Json.str(json, "name"),
                Json.list(json.get("candidateRatesBps")).stream().map(value -> ((Number) value).longValue()).toList(),
                Json.dbl(json, "gapThresholdMs"), Json.str(json, "timeBasis"), Json.str(json, "expectedBitOrder"));
    }
}

public record LabelDefinition(String version, String labelOctal, String sdi, String name, String encoding,
                              boolean signed, int msb, int lsb, double resolution, String unit,
                              String coordinateDatum, Map<String, String> discreteNames) {
    public String key() { return version + "/" + labelOctal + "/" + sdi; }

    public Map<String, Object> toJson() {
        Map<String, Object> names = new LinkedHashMap<>();
        discreteNames.forEach(names::put);
        return Json.obj("version", version, "labelOctal", labelOctal, "sdi", sdi, "name", name,
                "encoding", encoding, "signed", signed, "msb", msb, "lsb", lsb, "resolution", resolution,
                "unit", unit, "coordinateDatum", coordinateDatum, "discreteNames", names);
    }

    public static LabelDefinition fromJson(Map<String, Object> json) {
        Map<String, String> names = new LinkedHashMap<>();
        Json.map(json.get("discreteNames")).forEach((key, value) -> names.put(key, String.valueOf(value)));
        return new LabelDefinition(Json.str(json, "version"), Json.str(json, "labelOctal"), Json.str(json, "sdi"),
                Json.str(json, "name"), Json.str(json, "encoding"), Json.bool(json, "signed"),
                Json.integer(json, "msb"), Json.integer(json, "lsb"), Json.dbl(json, "resolution"),
                Json.str(json, "unit"), Json.str(json, "coordinateDatum"), names);
    }
}

public record BitField(String id, int bit, String name, int value, String role) {}

public record EngineeringValue(String stableId, String wordId, String labelOctal, String encoding,
                               String displayValue, String unit, String coordinateDatum, String status,
                               String dictionaryVersion, List<String> diagnostics) {}

public record DecodedWord(String stableId, RawWord word, String labelOctal, int rawLabelBits, int sdi, int ssm,
                          long data, boolean parityValid, int parityBit, int oneBits, String bitOrderInterpretation,
                          List<BitField> fields, List<EngineeringValue> values, List<String> diagnostics) {}

public record RateInterval(String stableId, String channelId, String fromWordId, String toWordId,
                           long deltaNanos, double deltaMs, List<Long> candidateRatesBps, boolean acquisitionGap,
                           List<String> diagnostics) {}

public record RateSegment(String stableId, String channelId, int firstSequence, int lastSequence,
                          List<Long> candidateRatesBps, List<String> wordIds, boolean afterAcquisitionGap,
                          List<String> intervalIds, List<String> diagnostics) {}
