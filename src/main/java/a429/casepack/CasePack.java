package a429.casepack;

import a429.domain.ChannelConfig;
import a429.domain.Dictionary;

import java.util.List;
import java.util.Map;

/**
 * The bundled offline domain case pack
 * "字路译码台：ARINC 429 标签、奇偶与速率分段证据".
 */
public record CasePack(
        String packId,
        String caseId,
        String title,
        double gapFactor,
        double switchRatio,
        String defaultDictVersion,
        List<ChannelConfig> channels,
        Map<String, Dictionary> dictionaries,
        List<PackFragment> fragments,
        List<SourcePatch> patches,
        List<PackStep> steps,
        String narrative) {
}
