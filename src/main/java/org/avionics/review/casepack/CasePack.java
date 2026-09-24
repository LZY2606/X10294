package org.avionics.review.casepack;

import java.util.List;
import java.util.Map;

/** In-repository domain case pack: 字路译码台 ARINC 429 固定证据档案. */
public record CasePack(
        String packId,
        String title,
        String initialDictVersion,
        List<ChannelDto> channels,
        List<DictDto> dictionaries,
        List<WordDto> words,
        List<ProbeDto> probes,
        List<JudgementDto> judgements) {

    public record ChannelDto(String channel, String name, double expectedRateKhz, String bus) {
    }

    public record WordDto(long seq, double timeMs, String channel, String rawHex) {
    }

    public record DictDto(String versionId, String publishedAt, String unitSign,
                          String coordinateBasis, List<DefDto> defs) {
    }

    public record DefDto(String labelOctal, String name, String encoding, String unit,
                         double range, int msb, int lsb, int impliedDecimals,
                         Map<String, String> discreteBits) {
    }

    public record ProbeDto(String id, String kind, String title, String description,
                           Map<String, String> wordHexOverrides,
                           Map<String, Double> timeOverrides,
                           List<ExpectationDto> expectations) {
    }

    public record ExpectationDto(String id, String description, List<String> affectedSidPatterns,
                                 List<String> mustPreserveSidPatterns,
                                 List<String> adoptedSignals, List<String> rejectedSignals) {
    }

    public record JudgementDto(String key, String statement, String evidenceEventKind) {
    }
}
