package a429.casepack;

import a429.events.Payloads;
import a429.model.ChannelConfig;

import java.util.List;

/** Loaded offline case pack. */
public record CasePack(
        String casePackId,
        String title,
        List<ChannelConfig> channels,
        List<Payloads.ImportedWord> words,
        Payloads.DictRef dictV1,
        Payloads.DictRef dictV2,
        Payloads.ParamsRef paramsV1,
        Payloads.ParamsRef paramsV2,
        Manifest manifest,
        String sourceName) {
}
