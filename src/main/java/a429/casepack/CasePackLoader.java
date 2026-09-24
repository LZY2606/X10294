package a429.casepack;

import a429.events.Payloads;
import a429.model.ChannelConfig;
import a429.store.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the in-repository ARINC 429 case pack from resources. This is domain material, not a
 * generic spreadsheet: every word, channel and rule revision ships with the repository.
 */
public final class CasePackLoader {
    public static final String CASE_DIR = "cases/word-road-decoding-bench/";

    private CasePackLoader() {}

    public static CasePack load() {
        Manifest manifest = parse(CASE_DIR + "manifest.json", Manifest.class);
        CaseJson caseJson = parse(CASE_DIR + "case.json", CaseJson.class);
        Payloads.DictRef v1 = parse(CASE_DIR + "dictionary-v1.json", Payloads.DictRef.class);
        Payloads.DictRef v2 = parse(CASE_DIR + "dictionary-v2.json", Payloads.DictRef.class);
        Payloads.ParamsRef p1 = parse(CASE_DIR + "params-r1.json", Payloads.ParamsRef.class);
        Payloads.ParamsRef p2 = parse(CASE_DIR + "params-r2.json", Payloads.ParamsRef.class);

        List<ChannelConfig> channels = new ArrayList<>();
        for (CaseJson.Channel channel : caseJson.channels) {
            channels.add(new ChannelConfig(channel.channel, channel.bus,
                    List.copyOf(channel.expectedLabels)));
        }
        List<Payloads.ImportedWord> words = new ArrayList<>();
        for (CaseJson.Word word : caseJson.words) {
            words.add(new Payloads.ImportedWord(word.seq, word.tsNanos, word.channel,
                    parseHex(word.word), word.reversedCapture != null && word.reversedCapture));
        }
        return new CasePack(caseJson.casePackId, caseJson.title, channels, words, v1, v2,
                p1, p2, manifest, caseJson.sourceName);
    }

    static int parseHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("missing word");
        }
        String value = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return (int) Long.parseLong(value, 16);
    }

    private static <T> T parse(String resource, Class<T> type) {
        try (InputStream in = CasePackLoader.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing case resource " + resource);
            }
            return Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + resource, e);
        }
    }

    public record CaseJson(String casePackId, String title, String sourceName,
                           List<Channel> channels, List<Word> words) {
        public record Channel(String channel, String bus, List<String> expectedLabels) {
        }

        public record Word(int seq, long tsNanos, String channel, String word,
                           Boolean reversedCapture, String note) {
        }
    }
}
